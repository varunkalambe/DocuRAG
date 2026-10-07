import asyncio
import math
import threading
from pathlib import Path
from typing import Any

from app.core.config import settings
from app.core.exceptions import ApplicationException
from app.observability.logging import get_logger, log_event


logger = get_logger("pdf_rag.embeddings.local")


class LocalEmbeddingAdapter:
    """
    In-process embeddings (fastembed / ONNX Runtime).

    No API key, no network call at request time, no credits. The default
    model (sentence-transformers/all-MiniLM-L6-v2, 384 dimensions) is the same
    model the Hugging Face adapter uses, so vectors already stored in Chroma
    stay compatible and no re-indexing is required.

    The ONNX model is loaded lazily on first use and inference runs in a
    worker thread, so the event loop (and Render's health check) is never
    blocked while a large PDF is being embedded.
    """

    def __init__(self, model_name: str | None = None) -> None:
        self.model = model_name or settings.LOCAL_EMBEDDING_MODEL
        self.batch_size = settings.LOCAL_EMBEDDING_BATCH_SIZE
        self._engine: Any = None
        self._lock = threading.Lock()
        self._dimension: int | None = None

    async def embed_one(self, text: str) -> list[float]:
        vectors = await self.embed_texts([text])
        return vectors[0]

    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        if not texts:
            return []

        cleaned = [text.strip() for text in texts]
        for index, text in enumerate(cleaned):
            if not text:
                raise ApplicationException(
                    message=f"Embedding input {index} is empty.",
                    status_code=400,
                    error_code="EMPTY_EMBEDDING_INPUT",
                    details={"index": index},
                )

        try:
            vectors = await asyncio.to_thread(self._embed_sync, cleaned)
        except ApplicationException:
            raise
        except Exception as exc:  # noqa: BLE001
            log_event(
                logger,
                40,
                "local_embedding_failed",
                model=self.model,
                exception_type=type(exc).__name__,
                exception_message=str(exc)[:300],
            )
            raise ApplicationException(
                message=(
                    f"Local embedding failed ({type(exc).__name__}): "
                    f"{str(exc)[:200]}"
                ),
                status_code=502,
                error_code="EMBEDDING_LOCAL_FAILED",
                details={"exception_type": type(exc).__name__},
            ) from exc

        return self._validate(vectors, expected_count=len(cleaned))

    # ------------------------------------------------------------------
    # Worker-thread code
    # ------------------------------------------------------------------
    def _load_engine(self) -> Any:
        if self._engine is not None:
            return self._engine

        try:
            from fastembed import TextEmbedding
        except ImportError as exc:
            raise ApplicationException(
                message=(
                    "Local embeddings require the 'fastembed' package. "
                    "Add 'fastembed' to requirements.txt and redeploy."
                ),
                status_code=502,
                error_code="EMBEDDING_LOCAL_UNAVAILABLE",
            ) from exc

        cache_dir = Path(settings.LOCAL_EMBEDDING_CACHE_DIR).expanduser()
        cache_dir.mkdir(parents=True, exist_ok=True)

        log_event(logger, 20, "local_embedding_model_loading", model=self.model)
        self._engine = TextEmbedding(
            model_name=self.model,
            cache_dir=str(cache_dir),
            threads=settings.LOCAL_EMBEDDING_THREADS,
        )
        log_event(logger, 20, "local_embedding_model_loaded", model=self.model)
        return self._engine

    def _embed_sync(self, texts: list[str]) -> list[list[float]]:
        # One inference at a time keeps peak memory predictable.
        with self._lock:
            engine = self._load_engine()
            return [
                [float(value) for value in vector]
                for vector in engine.embed(texts, batch_size=self.batch_size)
            ]

    # ------------------------------------------------------------------
    def _validate(
        self,
        vectors: list[list[float]],
        expected_count: int,
    ) -> list[list[float]]:
        if len(vectors) != expected_count:
            raise ApplicationException(
                message="Local embedding returned an unexpected vector count.",
                status_code=502,
                error_code="EMBEDDING_COUNT_MISMATCH",
                details={"expected": expected_count, "received": len(vectors)},
            )

        for index, vector in enumerate(vectors):
            if not vector or not all(math.isfinite(v) for v in vector):
                raise ApplicationException(
                    message="Local embedding produced an invalid vector.",
                    status_code=502,
                    error_code="EMBEDDING_INVALID_VALUE",
                    details={"index": index},
                )

            if self._dimension is None:
                self._dimension = len(vector)
            elif len(vector) != self._dimension:
                raise ApplicationException(
                    message="Embedding dimensionality changed unexpectedly.",
                    status_code=502,
                    error_code="EMBEDDING_DIMENSION_MISMATCH",
                    details={
                        "expected": self._dimension,
                        "received": len(vector),
                    },
                )

        return vectors

    @property
    def dimension(self) -> int | None:
        return self._dimension
