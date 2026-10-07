import time
from dataclasses import dataclass

from fastapi.concurrency import run_in_threadpool

from app.chunking.semantic import SemanticChunker
from app.core.exceptions import ApplicationException
from app.core.config import settings
from app.document.extraction import extract_pdf_pages
from app.document.fingerprint import fingerprint_document
from app.document.normalization import normalize_pages
from app.document.validation import validate_pdf
from app.indexing.chroma_indexer import ChromaIndexer
from app.observability.logging import get_logger, log_event


logger = get_logger("pdf_rag.ingestion")


@dataclass(frozen=True)
class IngestionResult:
    document_id: str
    filename: str
    page_count: int
    empty_page_count: int
    chunk_count: int
    indexed_count: int
    status: str
    message: str


class IngestionService:
    """Complete document-ingestion pipeline with Stage 70–72 diagnostics."""

    def __init__(
        self,
        chunker: SemanticChunker,
        embedder,
        indexer: ChromaIndexer,
    ) -> None:
        self.chunker = chunker
        self.embedder = embedder
        self.indexer = indexer

    async def ingest(
        self,
        file_bytes: bytes,
        filename: str,
        content_type: str | None,
    ) -> IngestionResult:
        started = time.perf_counter()
        timings: dict[str, float] = {}

        stage_started = time.perf_counter()
        validation_result = await run_in_threadpool(
            validate_pdf,
            file_bytes=file_bytes,
            filename=filename,
            content_type=content_type,
            max_size_bytes=settings.MAX_UPLOAD_SIZE_BYTES,
        )
        timings["validation_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)

        stage_started = time.perf_counter()
        extracted_pages = await run_in_threadpool(extract_pdf_pages, file_bytes)
        timings["extraction_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)

        page_count = len(extracted_pages)
        empty_page_count = sum(1 for page in extracted_pages if page.is_empty)

        stage_started = time.perf_counter()
        normalized_pages = await run_in_threadpool(normalize_pages, extracted_pages)
        timings["normalization_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)

        meaningful_pages = [page for page in normalized_pages if page.text.strip()]
        if not meaningful_pages:
            raise ApplicationException(
                message=(
                    "The PDF contains no meaningful extractable text. "
                    "Scanned/image-only PDFs are not supported by this ingestion pipeline."
                ),
                status_code=422,
                error_code="NO_EXTRACTABLE_TEXT",
                details={"page_count": page_count},
            )

        stage_started = time.perf_counter()
        document_id = await run_in_threadpool(fingerprint_document, file_bytes)
        timings["fingerprint_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)

        existing_ids = await run_in_threadpool(
            self.indexer.store.find_ids_by_document, document_id
        )
        if existing_ids:
            timings["total_ms"] = round((time.perf_counter() - started) * 1000, 2)
            log_event(
                logger,
                20,
                "document_already_indexed",
                filename=validation_result.filename,
                size_bytes=len(file_bytes),
                page_count=page_count,
                empty_page_count=empty_page_count,
                chunk_count=len(existing_ids),
                indexed_count=len(existing_ids),
                timings_ms=timings,
            )
            return IngestionResult(
                document_id=document_id,
                filename=validation_result.filename,
                page_count=page_count,
                empty_page_count=empty_page_count,
                chunk_count=len(existing_ids),
                indexed_count=len(existing_ids),
                status="already_indexed",
                message="This document is already indexed. No duplicate embedding or indexing was performed.",
            )

        stage_started = time.perf_counter()
        chunks = await run_in_threadpool(
            self.chunker.chunk_pages,
            pages=normalized_pages,
            document_fingerprint=document_id,
            filename=validation_result.filename,
        )
        timings["chunking_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)

        stage_started = time.perf_counter()
        embeddings = await self.embedder.embed_texts([chunk.text for chunk in chunks])
        timings["embedding_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)

        stage_started = time.perf_counter()
        index_result = await run_in_threadpool(
            self.indexer.index_document,
            chunks=chunks,
            embeddings=embeddings,
        )
        timings["indexing_ms"] = round((time.perf_counter() - stage_started) * 1000, 2)
        timings["total_ms"] = round((time.perf_counter() - started) * 1000, 2)

        embed_batch_size = max(
            1, int(getattr(self.embedder, "batch_size", settings.HF_BATCH_SIZE))
        )

        log_event(
            logger,
            20,
            "document_ingestion_completed",
            filename=validation_result.filename,
            size_bytes=len(file_bytes),
            page_count=page_count,
            empty_page_count=empty_page_count,
            chunk_count=len(chunks),
            embedding_batch_count=(len(chunks) + embed_batch_size - 1) // embed_batch_size,
            vector_count=index_result.indexed_count,
            timings_ms=timings,
        )

        return IngestionResult(
            document_id=document_id,
            filename=validation_result.filename,
            page_count=page_count,
            empty_page_count=empty_page_count,
            chunk_count=len(chunks),
            indexed_count=index_result.indexed_count,
            status="indexed",
            message=(
                "Document ingestion completed successfully. "
                f"{page_count} pages processed, "
                f"{len(chunks)} chunks created, "
                f"{index_result.indexed_count} vectors indexed."
            ),
        )
