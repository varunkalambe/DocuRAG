package com.pdfrag.service;

import com.pdfrag.config.AppProperties;
import com.pdfrag.embedding.EmbeddingProvider;
import com.pdfrag.generation.AnswerGenerator;
import com.pdfrag.obs.Logs;
import com.pdfrag.query.BuiltContext;
import com.pdfrag.query.ContextAssembler;
import com.pdfrag.query.DocumentLevelClassifier;
import com.pdfrag.query.DocumentOverview;
import com.pdfrag.query.DocumentOverviewBuilder;
import com.pdfrag.query.GroundedPrompt;
import com.pdfrag.query.PromptBuilder;
import com.pdfrag.query.QueryResult;
import com.pdfrag.query.QueryValidator;
import com.pdfrag.query.RelevanceFilter;
import com.pdfrag.query.RetrievedChunk;
import com.pdfrag.query.Retriever;
import com.pdfrag.query.ValidatedQuery;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** question -> validate -> (document overview | embed -> retrieve -> threshold -> context) -> Groq. */
@Service
public class QueryService {

    private static final Logger LOG = LoggerFactory.getLogger("pdf_rag.query");

    static final String ABSTENTION_MESSAGE =
            "The uploaded document does not provide enough evidence to answer this question.";

    private final QueryValidator validator;
    private final EmbeddingProvider embedder;
    private final Retriever retriever;
    private final RelevanceFilter relevanceFilter;
    private final ContextAssembler contextAssembler;
    private final PromptBuilder promptBuilder;
    private final AnswerGenerator generator;
    private final DocumentOverviewBuilder overviewBuilder;
    private final AppProperties props;

    public QueryService(
            QueryValidator validator,
            EmbeddingProvider embedder,
            Retriever retriever,
            RelevanceFilter relevanceFilter,
            ContextAssembler contextAssembler,
            PromptBuilder promptBuilder,
            AnswerGenerator generator,
            DocumentOverviewBuilder overviewBuilder,
            AppProperties props) {
        this.validator = validator;
        this.embedder = embedder;
        this.retriever = retriever;
        this.relevanceFilter = relevanceFilter;
        this.contextAssembler = contextAssembler;
        this.promptBuilder = promptBuilder;
        this.generator = generator;
        this.overviewBuilder = overviewBuilder;
        this.props = props;
    }

    public QueryResult answer(String question) {
        long started = System.nanoTime();
        Map<String, Double> timings = new LinkedHashMap<>();

        long stage = System.nanoTime();
        ValidatedQuery validated = validator.validate(question);
        timings.put("validation_ms", Logs.ms(stage));

        // Whole-document questions are answered from a representative overview, not nearest chunks.
        if (DocumentLevelClassifier.isDocumentLevelQuestion(validated.question())) {
            QueryResult overviewResult = answerFromOverview(validated, started, timings, false, 0);
            if (overviewResult != null) {
                return overviewResult;
            }
        }

        stage = System.nanoTime();
        float[] queryEmbedding = embedder.embedOne(validated.question());
        timings.put("query_embedding_ms", Logs.ms(stage));

        stage = System.nanoTime();
        List<RetrievedChunk> candidates = retriever.retrieve(queryEmbedding);
        timings.put("retrieval_ms", Logs.ms(stage));

        stage = System.nanoTime();
        List<RetrievedChunk> accepted = relevanceFilter.filter(candidates);
        timings.put("relevance_ms", Logs.ms(stage));

        Logs.info(LOG, "retrieval_diagnostics", "question_length", validated.question().length(),
                "candidate_count", candidates.size(), "accepted_count", accepted.size(),
                "threshold", relevanceFilter.threshold(),
                "candidate_distances", candidates.stream().map(c -> Math.round(c.distance() * 1e6) / 1e6).toList(),
                "accepted_ranks", accepted.stream().map(RetrievedChunk::rank).toList(),
                "accepted_chunk_ids", accepted.stream().map(RetrievedChunk::chunkId).toList());

        if (accepted.isEmpty() && props.enableOverviewFallback()) {
            // Nothing matched strongly; the question may still be document-level phrased unusually.
            QueryResult fallback = answerFromOverview(validated, started, timings, true, candidates.size());
            if (fallback != null) {
                return fallback;
            }
        }

        if (accepted.isEmpty()) {
            timings.put("total_ms", Logs.ms(started));
            Logs.info(LOG, "query_abstained_retrieval_threshold",
                    "question_length", validated.question().length(),
                    "retrieval_count", candidates.size(), "timings_ms", timings);
            return new QueryResult(ABSTENTION_MESSAGE, "abstained", List.of(), candidates.size(), 0,
                    Math.min(props.topK(), candidates.size()), relevanceFilter.threshold(), 0);
        }

        stage = System.nanoTime();
        BuiltContext context = contextAssembler.build(accepted);
        timings.put("context_assembly_ms", Logs.ms(stage));

        if (context.blocks().isEmpty()) {
            timings.put("total_ms", Logs.ms(started));
            Logs.info(LOG, "query_abstained_empty_context",
                    "question_length", validated.question().length(),
                    "accepted_count", accepted.size(), "timings_ms", timings);
            return new QueryResult(ABSTENTION_MESSAGE, "abstained", List.of(), candidates.size(),
                    accepted.size(), Math.min(props.topK(), candidates.size()),
                    relevanceFilter.threshold(), 0);
        }

        Set<String> selectedIds = context.blocks().stream()
                .map(b -> b.chunkId()).collect(Collectors.toSet());
        List<RetrievedChunk> selectedSources = accepted.stream()
                .filter(item -> selectedIds.contains(item.chunkId())).toList();

        stage = System.nanoTime();
        GroundedPrompt prompt = promptBuilder.build(validated, context);
        timings.put("prompt_build_ms", Logs.ms(stage));

        stage = System.nanoTime();
        String answer = generator.generate(prompt);
        timings.put("generation_ms", Logs.ms(stage));
        timings.put("total_ms", Logs.ms(started));

        Logs.info(LOG, "query_generation_completed", "question_length", validated.question().length(),
                "status", "answered", "candidate_count", candidates.size(),
                "accepted_count", accepted.size(),
                "selected_source_ids", selectedSources.stream().map(RetrievedChunk::sourceId).toList(),
                "context_token_estimate", context.estimatedTokens(), "answer_length", answer.length(),
                "timings_ms", timings);

        return new QueryResult(answer, "answered", selectedSources, candidates.size(), accepted.size(),
                Math.min(props.topK(), candidates.size()), relevanceFilter.threshold(),
                context.estimatedTokens());
    }

    /** Returns null only when the overview cannot be built (caller continues with retrieval/abstention). */
    private QueryResult answerFromOverview(
            ValidatedQuery validated, long started, Map<String, Double> timings, boolean fallback,
            int candidateCount) {

        long stage = System.nanoTime();
        DocumentOverview overview;
        try {
            overview = overviewBuilder.build();
        } catch (RuntimeException e) {
            Logs.warn(LOG, "overview_build_failed", "exception_type", e.getClass().getSimpleName(),
                    "exception_message", String.valueOf(e.getMessage()), "fallback", fallback);
            return null;
        }
        timings.put("overview_build_ms", Logs.ms(stage));

        stage = System.nanoTime();
        GroundedPrompt prompt = promptBuilder.buildDocumentLevel(validated, overview, fallback);
        timings.put("prompt_build_ms", Logs.ms(stage));

        stage = System.nanoTime();
        String answer = generator.generate(prompt);
        timings.put("generation_ms", Logs.ms(stage));
        timings.put("total_ms", Logs.ms(started));

        if (fallback && answer.strip().toUpperCase().startsWith(PromptBuilder.INSUFFICIENT_EVIDENCE_TOKEN)) {
            Logs.info(LOG, "query_abstained_after_overview_fallback",
                    "question_length", validated.question().length(),
                    "candidate_count", candidateCount, "timings_ms", timings);
            return new QueryResult(ABSTENTION_MESSAGE, "abstained", List.of(), candidateCount, 0,
                    Math.min(props.topK(), candidateCount), relevanceFilter.threshold(), 0);
        }

        List<RetrievedChunk> sources = overview.sources();

        Logs.info(LOG, "query_document_level_completed", "question_length", validated.question().length(),
                "status", "answered", "fallback", fallback, "document_count", overview.profiles().size(),
                "excerpt_count", sources.size(), "context_token_estimate", overview.estimatedTokens(),
                "answer_length", answer.length(), "timings_ms", timings);

        return new QueryResult(answer, "answered", sources, sources.size(), sources.size(), sources.size(),
                relevanceFilter.threshold(), overview.estimatedTokens(), "document_overview");
    }
}
