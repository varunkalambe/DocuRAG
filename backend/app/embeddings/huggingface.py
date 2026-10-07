import asyncio
import math
from typing import Any

from huggingface_hub import AsyncInferenceClient

try:  # huggingface_hub >= 0.30
    from huggingface_hub.errors import HfHubHTTPError, InferenceTimeoutError
except ImportError:  # pragma: no cover - older releases
    from huggingface_hub import InferenceTimeoutError
    from huggingface_hub.utils import HfHubHTTPError

from app.core.config import settings
from app.core.exceptions import ApplicationException
from app.observability.logging import get_logger, log_event


logger = get_logger("pdf_rag.embeddings.huggingface")


def _safe_text(exc: Exception, limit: int = 300) -> str:
    """Provider error text with the API token scrubbed, length-capped."""
    text = str(exc) or type(exc).__name__
    token = settings.HUGGINGFACE_API_TOKEN
    if token:
        text = text.replace(token, "***")
    text = " ".join(text.split())
    return text[:limit]


class HuggingFaceEmbeddingAdapter:
    def __init__(self, client: AsyncInferenceClient | None = None) -> None:
        self.model = settings.HF_EMBEDDING_MODEL
        self.batch_size = settings.HF_BATCH_SIZE
        self.max_retries = settings.HF_MAX_RETRIES
        self.retry_base_seconds = settings.HF_RETRY_BASE_SECONDS
        self._dimension: int | None = None

        self.client = client or AsyncInferenceClient(
            model=self.model,
            provider=(settings.HF_PROVIDER or None),
            token=settings.HUGGINGFACE_API_TOKEN,
            timeout=settings.HTTP_TIMEOUT_SECONDS,
        )

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

        vectors: list[list[float]] = []
        for start in range(0, len(cleaned), self.batch_size):
            batch = cleaned[start : start + self.batch_size]
            vectors.extend(await self._embed_batch_with_retry(batch))

        if len(vectors) != len(cleaned):
            raise ApplicationException(
                message="Embedding provider returned an unexpected number of vectors.",
                status_code=502,
                error_code="EMBEDDING_COUNT_MISMATCH",
                details={
                    "expected": len(cleaned),
                    "received": len(vectors),
                },
            )

        return vectors

    async def _embed_batch_with_retry(
        self,
        batch: list[str],
    ) -> list[list[float]]:
        for attempt in range(self.max_retries + 1):
            try:
                raw = await self.client.feature_extraction(batch)
                return self._validate_provider_response(
                    raw,
                    expected_count=len(batch),
                )

            except InferenceTimeoutError as exc:
                log_event(
                    logger,
                    40,
                    "hf_embedding_timeout",
                    attempt=attempt,
                    exception_message=_safe_text(exc),
                )
                if attempt >= self.max_retries:
                    raise ApplicationException(
                        message="Hugging Face embedding request timed out.",
                        status_code=504,
                        error_code="EMBEDDING_TIMEOUT",
                    ) from exc

                await asyncio.sleep(
                    self.retry_base_seconds * (2**attempt)
                )

            except HfHubHTTPError as exc:
                status = self._status_code(exc)
                detail = _safe_text(exc)

                log_event(
                    logger,
                    40,
                    "hf_embedding_http_error",
                    attempt=attempt,
                    provider_status=status,
                    model=self.model,
                    provider=settings.HF_PROVIDER,
                    exception_type=type(exc).__name__,
                    exception_message=detail,
                )

                if status in {401, 403}:
                    raise ApplicationException(
                        message=(
                            "Hugging Face credentials were rejected. Check "
                            "HUGGINGFACE_API_TOKEN and that it has permission "
                            "to call Inference Providers."
                        ),
                        status_code=502,
                        error_code="EMBEDDING_INVALID_CREDENTIALS",
                        details={"provider_status": status},
                    ) from exc

                if status == 402:
                    # Monthly Inference Providers credits are used up. Retrying
                    # cannot succeed, so fail fast with an actionable message.
                    raise ApplicationException(
                        message=(
                            "Hugging Face Inference credits are exhausted "
                            "(402 Payment Required). Add credits to the "
                            "Hugging Face account, or set "
                            "EMBEDDING_PROVIDER=local on the backend."
                        ),
                        status_code=502,
                        error_code="EMBEDDING_CREDITS_EXHAUSTED",
                        details={"provider_status": status},
                    ) from exc

                retryable = status == 429 or (
                    status is not None and status >= 500
                )

                if retryable and attempt < self.max_retries:
                    await asyncio.sleep(
                        self.retry_base_seconds * (2**attempt)
                    )
                    continue

                if status == 429:
                    raise ApplicationException(
                        message="Hugging Face embedding rate limit was reached.",
                        status_code=429,
                        error_code="EMBEDDING_RATE_LIMIT",
                        details={"provider_status": status},
                    ) from exc

                if status in {400, 404, 422}:
                    raise ApplicationException(
                        message=(
                            f"Hugging Face rejected the embedding request "
                            f"(model '{self.model}', provider "
                            f"'{settings.HF_PROVIDER or 'auto'}'): {detail}"
                        ),
                        status_code=502,
                        error_code="EMBEDDING_MODEL_UNAVAILABLE",
                        details={"provider_status": status},
                    ) from exc

                raise ApplicationException(
                    message=f"Hugging Face embedding provider request failed: {detail}",
                    status_code=502,
                    error_code="EMBEDDING_PROVIDER_ERROR",
                    details={"provider_status": status},
                ) from exc

            except ApplicationException:
                # Already a well-formed application error (for example a
                # malformed provider response). Preserve its specific code.
                raise

            except (OSError, asyncio.TimeoutError) as exc:
                # Network-level failure (DNS, reset, connect timeout).
                log_event(
                    logger,
                    40,
                    "hf_embedding_network_error",
                    attempt=attempt,
                    exception_type=type(exc).__name__,
                    exception_message=_safe_text(exc),
                )
                if attempt >= self.max_retries:
                    raise ApplicationException(
                        message="Hugging Face could not be reached.",
                        status_code=502,
                        error_code="EMBEDDING_PROVIDER_ERROR",
                        details={"exception_type": type(exc).__name__},
                    ) from exc

                await asyncio.sleep(
                    self.retry_base_seconds * (2**attempt)
                )

            except Exception as exc:
                detail = _safe_text(exc)
                log_event(
                    logger,
                    40,
                    "hf_embedding_unexpected_exception",
                    attempt=attempt,
                    model=self.model,
                    provider=settings.HF_PROVIDER,
                    exception_type=type(exc).__name__,
                    exception_message=detail,
                )
                raise ApplicationException(
                    message=(
                        f"Hugging Face embedding failed "
                        f"({type(exc).__name__}): {detail}"
                    ),
                    status_code=502,
                    error_code="EMBEDDING_PROVIDER_ERROR",
                    details={"exception_type": type(exc).__name__},
                ) from exc

        raise ApplicationException(
            message="Embedding provider failed after retries.",
            status_code=502,
            error_code="EMBEDDING_RETRY_EXHAUSTED",
        )

    def _validate_provider_response(
        self,
        raw_result: Any,
        expected_count: int,
    ) -> list[list[float]]:
        if hasattr(raw_result, "tolist"):
            data = raw_result.tolist()
        else:
            data = raw_result

        if data is None:
            raise ApplicationException(
                message="Embedding provider returned no data.",
                status_code=502,
                error_code="EMBEDDING_EMPTY_RESPONSE",
            )

        if (
            isinstance(data, list)
            and data
            and isinstance(data[0], (int, float))
        ):
            data = [data]

        if not isinstance(data, list):
            raise ApplicationException(
                message="Embedding provider returned an invalid response shape.",
                status_code=502,
                error_code="EMBEDDING_MALFORMED_RESPONSE",
            )

        if len(data) != expected_count:
            raise ApplicationException(
                message="Embedding provider returned an unexpected vector count.",
                status_code=502,
                error_code="EMBEDDING_COUNT_MISMATCH",
                details={
                    "expected": expected_count,
                    "received": len(data),
                },
            )

        validated: list[list[float]] = []

        for index, vector in enumerate(data):
            if not isinstance(vector, list) or not vector:
                raise ApplicationException(
                    message="Embedding provider returned an invalid vector.",
                    status_code=502,
                    error_code="EMBEDDING_MALFORMED_RESPONSE",
                    details={"index": index},
                )

            converted: list[float] = []

            for value in vector:
                try:
                    numeric = float(value)
                except (TypeError, ValueError) as exc:
                    raise ApplicationException(
                        message="Embedding vector contains a non-numeric value.",
                        status_code=502,
                        error_code="EMBEDDING_NON_NUMERIC_VALUE",
                        details={"index": index},
                    ) from exc

                if not math.isfinite(numeric):
                    raise ApplicationException(
                        message="Embedding vector contains a non-finite value.",
                        status_code=502,
                        error_code="EMBEDDING_INVALID_VALUE",
                        details={"index": index},
                    )

                converted.append(numeric)

            if self._dimension is None:
                self._dimension = len(converted)
            elif len(converted) != self._dimension:
                raise ApplicationException(
                    message="Embedding dimensionality changed unexpectedly.",
                    status_code=502,
                    error_code="EMBEDDING_DIMENSION_MISMATCH",
                    details={
                        "expected": self._dimension,
                        "received": len(converted),
                    },
                )

            validated.append(converted)

        return validated

    @staticmethod
    def _status_code(exc: Exception) -> int | None:
        response = getattr(exc, "response", None)
        return getattr(response, "status_code", None) if response is not None else None

    @property
    def dimension(self) -> int | None:
        return self._dimension
