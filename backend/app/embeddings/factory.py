import threading
import time

from app.core.config import settings
from app.core.exceptions import ApplicationException
from app.embeddings.huggingface import HuggingFaceEmbeddingAdapter
from app.embeddings.local import LocalEmbeddingAdapter
from app.observability.logging import get_logger, log_event


logger = get_logger("pdf_rag.embeddings.factory")

# Failures of the remote provider for which the local model is a safe stand-in.
FALLBACK_ERROR_CODES = frozenset(
    {
        "EMBEDDING_CREDITS_EXHAUSTED",  # HTTP 402
        "EMBEDDING_RATE_LIMIT",         # HTTP 429
        "EMBEDDING_INVALID_CREDENTIALS",
        "EMBEDDING_MODEL_UNAVAILABLE",
        "EMBEDDING_PROVIDER_ERROR",
        "EMBEDDING_TIMEOUT",
        "EMBEDDING_RETRY_EXHAUSTED",
    }
)


class FallbackEmbeddingAdapter:
    """
    Remote Hugging Face first, local model when the remote provider is
    unavailable. After a failure the remote provider is skipped for a cooldown
    period so every request does not pay the failed round-trip again.

    A whole embed_texts() call is served by exactly one provider, so the
    vectors of a single document are never mixed.
    """

    def __init__(
        self,
        primary: HuggingFaceEmbeddingAdapter,
        fallback: LocalEmbeddingAdapter,
        cooldown_seconds: int,
    ) -> None:
        self.primary = primary
        self.fallback = fallback
        self.cooldown_seconds = cooldown_seconds
        self.batch_size = primary.batch_size
        self._skip_primary_until = 0.0
        self._last_primary_error: ApplicationException | None = None

    async def embed_one(self, text: str) -> list[float]:
        return (await self.embed_texts([text]))[0]

    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        if time.monotonic() >= self._skip_primary_until:
            try:
                return await self.primary.embed_texts(texts)
            except ApplicationException as exc:
                if exc.error_code not in FALLBACK_ERROR_CODES:
                    raise
                self._last_primary_error = exc
                self._skip_primary_until = (
                    time.monotonic() + self.cooldown_seconds
                )
                log_event(
                    logger,
                    30,
                    "embedding_primary_failed_using_local_fallback",
                    error_code=exc.error_code,
                    cooldown_seconds=self.cooldown_seconds,
                )

        try:
            return await self.fallback.embed_texts(texts)
        except ApplicationException as fallback_exc:
            if fallback_exc.error_code == "EMBEDDING_LOCAL_UNAVAILABLE":
                # Fallback is not usable (package missing): retry the primary
                # on the next call and report the original, real cause.
                self._skip_primary_until = 0.0
                if self._last_primary_error is not None:
                    raise self._last_primary_error from fallback_exc
            raise


_embedder = None
_embedder_lock = threading.Lock()


def get_embedder():
    """Process-wide embedder (the local ONNX model must be loaded only once)."""
    global _embedder

    if _embedder is None:
        with _embedder_lock:
            if _embedder is None:
                _embedder = _build_embedder()

    return _embedder


def _build_embedder():
    provider = settings.EMBEDDING_PROVIDER

    if provider == "local":
        embedder = LocalEmbeddingAdapter()
    else:
        primary = HuggingFaceEmbeddingAdapter()
        if settings.EMBEDDING_FALLBACK_TO_LOCAL:
            embedder = FallbackEmbeddingAdapter(
                primary=primary,
                fallback=LocalEmbeddingAdapter(),
                cooldown_seconds=settings.EMBEDDING_PRIMARY_COOLDOWN_SECONDS,
            )
        else:
            embedder = primary

    log_event(
        logger,
        20,
        "embedder_configured",
        provider=provider,
        fallback_to_local=settings.EMBEDDING_FALLBACK_TO_LOCAL,
    )
    return embedder
