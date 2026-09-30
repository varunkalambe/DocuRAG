import time

from fastapi.concurrency import run_in_threadpool

from app.core.config import settings
from app.generation.groq import GroqAdapter
from app.observability.logging import get_logger, log_event
from app.query.context import ContextAssembler
from app.query.models import QueryResult
from app.query.prompt import GroundedPromptBuilder
from app.query.relevance import RelevanceFilter
from app.query.retrieval import ChromaRetriever
from app.query.validation import QueryValidator


logger = get_logger("pdf_rag.query")

ABSTENTION_MESSAGE = (
    "The uploaded document does not provide enough evidence to answer this question."
)


class QueryService:
    """
    Complete query pipeline with retrieval/generation diagnostics and stage timing.
    """

    def __init__(
        self,
        validator: QueryValidator,
        embedder,
        retriever: ChromaRetriever,
        relevance_filter: RelevanceFilter,
        context_assembler: ContextAssembler,
        prompt_builder: GroundedPromptBuilder,
        generator: GroqAdapter,
    ) -> None:
        self.validator = validator
        self.embedder = embedder
        self.retriever = retriever
        self.relevance_filter = relevance_filter
        self.context_assembler = context_assembler
        self.prompt_builder = prompt_builder
        self.generator = generator

    async def answer(self, question: str | None) -> QueryResult:
        started = time.perf_counter()
        timings: dict[str, float] = {}

        stage_started = time.perf_counter()
        validated = await run_in_threadpool(self.validator.validate, question)
        timings["validation_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)

        stage_started = time.perf_counter()
        query_embedding = await self.embedder.embed_one(validated.question)
        timings["query_embedding_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)

        stage_started = time.perf_counter()
        candidates = await run_in_threadpool(self.retriever.retrieve, query_embedding)
        timings["retrieval_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)

        stage_started = time.perf_counter()
        accepted = self.relevance_filter.filter(candidates)
        timings["relevance_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)

        log_event(
            logger,
            20,
            "retrieval_diagnostics",
            question_length=len(validated.question),
            candidate_count=len(candidates),
            accepted_count=len(accepted),
            threshold=self.relevance_filter.threshold,
            candidate_distances=[round(item.distance, 6) for item in candidates],
            accepted_ranks=[item.rank for item in accepted],
            accepted_chunk_ids=[item.chunk_id for item in accepted],
        )

        if not accepted:
            timings["total_ms"] = round((time.perf_counter() - started) * 1000, 2)
            log_event(
                logger,
                20,
                "query_abstained_retrieval_threshold",
                question_length=len(validated.question),
                retrieval_count=len(candidates),
                timings_ms=timings,
            )
            return QueryResult(
                answer=ABSTENTION_MESSAGE,
                status="abstained",
                sources=[],
                candidate_count=len(candidates),
                accepted_count=0,
                top_k=min(settings.TOP_K, len(candidates)),
                relevance_threshold=self.relevance_filter.threshold,
                context_token_estimate=0,
            )

        stage_started = time.perf_counter()
        context = self.context_assembler.build(accepted)
        timings["context_assembly_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)

        if not context.blocks:
            timings["total_ms"] = round((time.perf_counter() - started) * 1000, 2)
            log_event(
                logger,
                20,
                "query_abstained_empty_context",
                question_length=len(validated.question),
                accepted_count=len(accepted),
                timings_ms=timings,
            )
            return QueryResult(
                answer=ABSTENTION_MESSAGE,
                status="abstained",
                sources=[],
                candidate_count=len(candidates),
                accepted_count=len(accepted),
                top_k=min(settings.TOP_K, len(candidates)),
                relevance_threshold=self.relevance_filter.threshold,
                context_token_estimate=0,
            )

        selected_by_id = {block.chunk_id: block for block in context.blocks}
        selected_sources = [item for item in accepted if item.chunk_id in selected_by_id]

        stage_started = time.perf_counter()
        prompt = self.prompt_builder.build(query=validated, context=context)
        timings["prompt_build_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)

        stage_started = time.perf_counter()
        answer = await self.generator.generate(prompt)
        timings["generation_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)
        timings["total_ms"] = round((time.perf_counter() - started) * 1000, 2)

        log_event(
            logger,
            20,
            "query_generation_completed",
            question_length=len(validated.question),
            status="answered",
            candidate_count=len(candidates),
            accepted_count=len(accepted),
            selected_source_ids=[item.source_id for item in selected_sources],
            context_token_estimate=context.estimated_tokens,
            answer_length=len(answer),
            timings_ms=timings,
        )

        return QueryResult(
            answer=answer,
            status="answered",
            sources=selected_sources,
            candidate_count=len(candidates),
            accepted_count=len(accepted),
            top_k=min(settings.TOP_K, len(candidates)),
            relevance_threshold=self.relevance_filter.threshold,
            context_token_estimate=context.estimated_tokens,
        )
