import time

from fastapi.concurrency import run_in_threadpool

from app.core.config import settings
from app.generation.groq import GroqAdapter
from app.observability.logging import get_logger, log_event
from app.query.context import ContextAssembler
from app.query.document_level import (
    DocumentOverviewBuilder,
    is_document_level_question,
)
from app.query.models import QueryResult, ValidatedQuery
from app.query.prompt import INSUFFICIENT_EVIDENCE_TOKEN, GroundedPromptBuilder
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
        overview_builder: DocumentOverviewBuilder | None = None,
    ) -> None:
        self.validator = validator
        self.embedder = embedder
        self.retriever = retriever
        self.relevance_filter = relevance_filter
        self.context_assembler = context_assembler
        self.prompt_builder = prompt_builder
        self.generator = generator
        # Optional: when absent, document-level questions and the overview
        # fallback are disabled and behaviour matches plain retrieval.
        self.overview_builder = overview_builder

    async def answer(self, question: str | None) -> QueryResult:
        started = time.perf_counter()
        timings: dict[str, float] = {}

        stage_started = time.perf_counter()
        validated = await run_in_threadpool(self.validator.validate, question)
        timings["validation_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)

        # Whole-document questions ("what is this about?", "summarize",
        # "how many pages?") are not close to any single chunk, so they are
        # answered from a representative overview instead of nearest chunks.
        if self.overview_builder is not None and is_document_level_question(
            validated.question
        ):
            overview_result = await self._answer_from_overview(
                validated=validated,
                started=started,
                timings=timings,
                fallback=False,
            )
            if overview_result is not None:
                return overview_result

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

        if not accepted and self.overview_builder is not None and (
            settings.ENABLE_OVERVIEW_FALLBACK
        ):
            # Nothing matched strongly. The question may still be a
            # document-level one phrased in an unusual way, so give the model
            # a representative overview and let it abstain if it cannot answer.
            fallback_result = await self._answer_from_overview(
                validated=validated,
                started=started,
                timings=timings,
                fallback=True,
                candidate_count=len(candidates),
            )
            if fallback_result is not None:
                return fallback_result

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

    async def _answer_from_overview(
        self,
        validated: ValidatedQuery,
        started: float,
        timings: dict[str, float],
        fallback: bool,
        candidate_count: int = 0,
    ) -> QueryResult | None:
        """
        Answer from a sampled whole-document overview.

        Returns None only when the overview cannot be built (the caller then
        continues with ordinary retrieval / abstention).
        """
        assert self.overview_builder is not None

        stage_started = time.perf_counter()
        try:
            overview = await run_in_threadpool(self.overview_builder.build)
        except Exception as exc:  # noqa: BLE001 - overview is best-effort
            log_event(
                logger,
                30,
                "overview_build_failed",
                exception_type=type(exc).__name__,
                exception_message=str(exc),
                fallback=fallback,
            )
            return None
        timings["overview_build_ms"] = round(
            (time.perf_counter() - stage_started) * 1000, 2
        )

        stage_started = time.perf_counter()
        prompt = self.prompt_builder.build_document_level(
            query=validated,
            overview=overview,
            fallback=fallback,
        )
        timings["prompt_build_ms"] = round(
            (time.perf_counter() - stage_started) * 1000, 2
        )

        stage_started = time.perf_counter()
        answer = await self.generator.generate(prompt)
        timings["generation_ms"] = round(
            (time.perf_counter() - stage_started) * 1000, 2
        )
        timings["total_ms"] = round((time.perf_counter() - started) * 1000, 2)

        if fallback and answer.strip().upper().startswith(
            INSUFFICIENT_EVIDENCE_TOKEN
        ):
            log_event(
                logger,
                20,
                "query_abstained_after_overview_fallback",
                question_length=len(validated.question),
                candidate_count=candidate_count,
                timings_ms=timings,
            )
            return QueryResult(
                answer=ABSTENTION_MESSAGE,
                status="abstained",
                sources=[],
                candidate_count=candidate_count,
                accepted_count=0,
                top_k=min(settings.TOP_K, candidate_count),
                relevance_threshold=self.relevance_filter.threshold,
                context_token_estimate=0,
            )

        sources = overview.sources

        log_event(
            logger,
            20,
            "query_document_level_completed",
            question_length=len(validated.question),
            status="answered",
            fallback=fallback,
            document_count=len(overview.profiles),
            excerpt_count=len(sources),
            context_token_estimate=overview.estimated_tokens,
            answer_length=len(answer),
            timings_ms=timings,
        )

        return QueryResult(
            answer=answer,
            status="answered",
            sources=sources,
            candidate_count=len(sources),
            accepted_count=len(sources),
            top_k=len(sources),
            relevance_threshold=self.relevance_filter.threshold,
            context_token_estimate=overview.estimated_tokens,
            mode="document_overview",
        )