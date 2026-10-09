package com.pdfrag.config;

import com.pdfrag.chunking.SemanticChunker;
import com.pdfrag.query.ContextAssembler;
import com.pdfrag.query.DocumentOverviewBuilder;
import com.pdfrag.query.PromptBuilder;
import com.pdfrag.query.QueryValidator;
import com.pdfrag.query.RelevanceFilter;
import com.pdfrag.query.Retriever;
import com.pdfrag.store.VectorStore;
import java.nio.file.Path;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the framework-free pipeline classes as singletons. */
@Configuration
public class AppBeans {

    @Bean
    public VectorStore vectorStore(AppProperties p) {
        return new VectorStore(
                Path.of(p.chromaPersistDir()).toAbsolutePath().normalize(), p.chromaDistanceMetric());
    }

    @Bean
    public SemanticChunker semanticChunker(AppProperties p) {
        return new SemanticChunker(p.chunkSize(), p.chunkOverlap());
    }

    @Bean
    public ContextAssembler contextAssembler(AppProperties p) {
        return new ContextAssembler(p.maxContextTokens());
    }

    @Bean
    public RelevanceFilter relevanceFilter(AppProperties p) {
        return new RelevanceFilter(p.relevanceThreshold());
    }

    @Bean
    public PromptBuilder promptBuilder(ContextAssembler assembler) {
        return new PromptBuilder(assembler);
    }

    @Bean
    public QueryValidator queryValidator(VectorStore store, AppProperties p) {
        return new QueryValidator(store, p.maxQuestionLength());
    }

    @Bean
    public Retriever retriever(VectorStore store, AppProperties p) {
        return new Retriever(store, p.topK());
    }

    @Bean
    public DocumentOverviewBuilder documentOverviewBuilder(VectorStore store, AppProperties p) {
        return new DocumentOverviewBuilder(
                store, p.overviewMaxChunksPerDocument(), p.overviewExcerptWords(),
                p.overviewMaxDocuments(), p.maxContextTokens());
    }
}
