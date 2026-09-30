from dataclasses import dataclass

from app.chunking.models import Chunk
from app.core.exceptions import ApplicationException
from app.observability.logging import get_logger, log_event
from app.vector_store.chroma import ChromaStore, VectorRecord


logger = get_logger("pdf_rag.indexing")


@dataclass(frozen=True)
class IndexResult:
    document_fingerprint: str
    chunk_count: int
    indexed_count: int


class ChromaIndexer:
    def __init__(self, store: ChromaStore) -> None:
        self.store = store

    def ensure_document_not_indexed(
        self,
        document_fingerprint: str,
    ) -> None:
        existing_ids = self.store.find_ids_by_document(
            document_fingerprint
        )
        if existing_ids:
            raise ApplicationException(
                message="This document is already indexed.",
                status_code=409,
                error_code="DOCUMENT_ALREADY_INDEXED",
                details={
                    "document_fingerprint": document_fingerprint,
                    "existing_records": len(existing_ids),
                },
            )

    def index_document(
        self,
        chunks: list[Chunk],
        embeddings: list[list[float]],
    ) -> IndexResult:
        if not chunks:
            raise ApplicationException(
                message="Cannot index an empty chunk list.",
                status_code=422,
                error_code="NO_CHUNKS_TO_INDEX",
            )

        if len(chunks) != len(embeddings):
            raise ApplicationException(
                message="Chunk count and embedding count do not match.",
                status_code=500,
                error_code="CHUNK_EMBEDDING_COUNT_MISMATCH",
                details={
                    "chunks": len(chunks),
                    "embeddings": len(embeddings),
                },
            )

        fingerprint = chunks[0].document_fingerprint

        for chunk in chunks:
            if chunk.document_fingerprint != fingerprint:
                raise ApplicationException(
                    message="Chunks from multiple documents cannot be indexed atomically.",
                    status_code=500,
                    error_code="MIXED_DOCUMENT_CHUNKS",
                )

        self.ensure_document_not_indexed(fingerprint)

        dimensions = {len(vector) for vector in embeddings}
        if len(dimensions) != 1:
            raise ApplicationException(
                message="Embedding vectors do not have consistent dimensionality.",
                status_code=502,
                error_code="INDEX_EMBEDDING_DIMENSION_MISMATCH",
            )

        new_dimension = next(iter(dimensions))
        stored_dimension = self.store.stored_dimension()

        if stored_dimension is not None and stored_dimension != new_dimension:
            raise ApplicationException(
                message=(
                    "The stored vectors have a different dimensionality "
                    f"({stored_dimension}) than the new embeddings "
                    f"({new_dimension}). Use 'Clear memory' and upload again."
                ),
                status_code=409,
                error_code="INDEX_EMBEDDING_DIMENSION_CONFLICT",
                details={
                    "stored_dimension": stored_dimension,
                    "new_dimension": new_dimension,
                },
            )

        records = [
            VectorRecord(
                record_id=chunk.chunk_id,
                document=chunk.text,
                embedding=embedding,
                metadata=chunk.metadata,
            )
            for chunk, embedding in zip(chunks, embeddings)
        ]

        try:
            self.store.add_records(records)
            indexed_ids = self.store.find_ids_by_document(fingerprint)

            if len(indexed_ids) != len(records):
                raise ApplicationException(
                    message="Chroma indexed an unexpected number of document records.",
                    status_code=502,
                    error_code="INDEX_COUNT_MISMATCH",
                    details={
                        "expected": len(records),
                        "actual": len(indexed_ids),
                    },
                )

            return IndexResult(
                document_fingerprint=fingerprint,
                chunk_count=len(chunks),
                indexed_count=len(indexed_ids),
            )

        except ApplicationException:
            self._rollback(fingerprint)
            raise
        except Exception as exc:
            log_event(
                logger,
                40,
                "indexing_failed",
                exception_type=type(exc).__name__,
                exception_message=str(exc),
                document_fingerprint=fingerprint,
            )
            self._rollback(fingerprint)
            raise ApplicationException(
                message="Document indexing failed.",
                status_code=502,
                error_code="INDEXING_FAILED",
                details={"exception_type": type(exc).__name__},
            ) from exc

    def _rollback(self, document_fingerprint: str) -> None:
        try:
            self.store.delete_by_document(document_fingerprint)
        except Exception as exc:
            raise ApplicationException(
                message="Indexing failed and rollback could not be completed.",
                status_code=500,
                error_code="INDEX_ROLLBACK_FAILED",
                details={
                    "document_fingerprint": document_fingerprint,
                },
            ) from exc
