import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import chromadb

from app.core.config import settings


@dataclass(frozen=True)
class VectorRecord:
    record_id: str
    document: str
    embedding: list[float]
    metadata: dict[str, Any]


class ChromaStore:
    """Persistent Chroma store. Data survives process restarts."""

    def __init__(self) -> None:
        persist_dir = Path(settings.CHROMA_PERSIST_DIR).expanduser().resolve()
        persist_dir.mkdir(parents=True, exist_ok=True)

        self._lock = threading.RLock()
        self.client = chromadb.PersistentClient(path=str(persist_dir))
        self.collection = self._create_collection()

    def _create_collection(self):
        return self.client.get_or_create_collection(
            name=settings.CHROMA_COLLECTION_NAME,
            configuration={
                "hnsw": {
                    "space": settings.CHROMA_DISTANCE_METRIC,
                }
            },
        )

    def count(self) -> int:
        with self._lock:
            return self.collection.count()

    def list_documents(self) -> list[dict[str, Any]]:
        """Summarise every stored document from chunk metadata."""
        with self._lock:
            result = self.collection.get(include=["metadatas"])

        documents: dict[str, dict[str, Any]] = {}

        for metadata in result.get("metadatas") or []:
            metadata = metadata or {}
            fingerprint = str(metadata.get("document_fingerprint", ""))

            if not fingerprint:
                continue

            entry = documents.setdefault(
                fingerprint,
                {
                    "document_id": fingerprint,
                    "filename": str(metadata.get("filename", "")),
                    "chunk_count": 0,
                    "page_count": 0,
                },
            )
            entry["chunk_count"] += 1
            # Prefer the true PDF page count stored at ingestion time and fall
            # back to the highest page seen for documents indexed earlier.
            entry["page_count"] = max(
                entry["page_count"],
                int(metadata.get("end_page", 0) or 0),
                int(metadata.get("document_page_count", 0) or 0),
            )

        return sorted(
            documents.values(),
            key=lambda item: (item["filename"].lower(), item["document_id"]),
        )

    def get_document_chunks(
        self,
        document_fingerprint: str,
    ) -> list[dict[str, Any]]:
        """
        Return every chunk of one document in reading order.

        Used by document-level question answering, which needs a
        representative sample of the whole document rather than the
        nearest neighbours of a query vector.
        """
        with self._lock:
            result = self.collection.get(
                where={"document_fingerprint": document_fingerprint},
                include=["documents", "metadatas"],
            )

        ids = result.get("ids") or []
        documents = result.get("documents") or []
        metadatas = result.get("metadatas") or []

        chunks: list[dict[str, Any]] = []
        for chunk_id, document, metadata in zip(ids, documents, metadatas):
            metadata = metadata or {}
            chunks.append(
                {
                    "chunk_id": str(chunk_id),
                    "text": str(document or ""),
                    "metadata": dict(metadata),
                    "sequence": int(metadata.get("sequence", 0) or 0),
                }
            )

        chunks.sort(key=lambda item: item["sequence"])
        return chunks

    def stored_dimension(self) -> int | None:
        """Dimensionality of vectors already stored, or None if empty."""
        with self._lock:
            result = self.collection.get(limit=1, include=["embeddings"])
            embeddings = result.get("embeddings")

            if embeddings is None or len(embeddings) == 0:
                return None

            return len(embeddings[0])

    def add_records(self, records: list[VectorRecord]) -> None:
        if not records:
            return

        with self._lock:
            try:
                max_batch_size = max(
                    1, int(self.client.get_max_batch_size())
                )
            except Exception:
                max_batch_size = len(records)

            for start in range(0, len(records), max_batch_size):
                batch = records[start : start + max_batch_size]
                self.collection.add(
                    ids=[record.record_id for record in batch],
                    documents=[record.document for record in batch],
                    embeddings=[record.embedding for record in batch],
                    metadatas=[record.metadata for record in batch],
                )

    def get_records(
        self,
        record_ids: list[str] | None = None,
    ) -> dict[str, Any]:
        include = ["documents", "metadatas", "embeddings"]
        with self._lock:
            if record_ids:
                return self.collection.get(
                    ids=record_ids,
                    include=include,
                )
            return self.collection.get(include=include)

    def find_ids_by_document(
        self,
        document_fingerprint: str,
    ) -> list[str]:
        with self._lock:
            result = self.collection.get(
                where={
                    "document_fingerprint": document_fingerprint,
                },
                include=["metadatas"],
            )
            return result.get("ids", [])

    def delete_by_document(
        self,
        document_fingerprint: str,
    ) -> None:
        with self._lock:
            ids = self.find_ids_by_document(document_fingerprint)
            if ids:
                self.collection.delete(ids=ids)

    def query_by_embedding(
        self,
        query_embedding: list[float],
        n_results: int = 5,
    ) -> dict[str, Any]:
        with self._lock:
            return self.collection.query(
                query_embeddings=[query_embedding],
                n_results=n_results,
                include=["documents", "metadatas", "distances"],
            )

    def reset_collection(self) -> None:
        with self._lock:
            try:
                self.client.delete_collection(
                    name=settings.CHROMA_COLLECTION_NAME
                )
            except Exception:
                pass

            self.collection = self._create_collection()


_vector_store: ChromaStore | None = None
_vector_store_lock = threading.Lock()


def get_vector_store() -> ChromaStore:
    global _vector_store

    if _vector_store is None:
        with _vector_store_lock:
            if _vector_store is None:
                _vector_store = ChromaStore()

    return _vector_store