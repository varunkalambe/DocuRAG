import asyncio

import groq
from groq import AsyncGroq

from app.core.config import settings
from app.core.exceptions import ApplicationException
from app.observability.logging import get_logger, log_event
from app.query.prompt import GroundedPrompt


logger = get_logger("pdf_rag.generation.groq")


class GroqAdapter:
    """
    Isolated Groq generation provider.

    Responsibility:
        grounded prompt -> generated response

    It has no knowledge of Angular, PDFs, chunks, or Chroma.
    """

    def __init__(
        self,
        client: AsyncGroq | None = None,
    ) -> None:
        if client is not None:
            self.client = client
        else:
            self.client = AsyncGroq(
                api_key=settings.GROQ_API_KEY,
                timeout=settings.HTTP_TIMEOUT_SECONDS,
                # Retry classification is handled explicitly below.
                max_retries=0,
            )

    async def generate(
        self,
        prompt: GroundedPrompt,
    ) -> str:
        last_exception: Exception | None = None

        for attempt in range(settings.GROQ_MAX_RETRIES + 1):
            try:
                response = await self.client.chat.completions.create(
                    model=settings.GROQ_MODEL,
                    messages=[
                        {
                            "role": "system",
                            "content": prompt.system_message,
                        },
                        {
                            "role": "user",
                            "content": prompt.user_message,
                        },
                    ],
                    temperature=settings.GROQ_TEMPERATURE,
                    max_completion_tokens=settings.GROQ_MAX_COMPLETION_TOKENS,

                    stream=False,
                    **self._reasoning_options(),
                )

                return self._validate_response(response)

            except ApplicationException:
                # Already a well-formed application error (for example an
                # empty completion). Preserve its specific error code.
                raise

            except groq.AuthenticationError as exc:
                log_event(
                    logger,
                    40,
                    "groq_authentication_error",
                    exception_type=type(exc).__name__,
                    exception_message=str(exc),
                )

                raise ApplicationException(
                    message="Groq authentication failed.",
                    status_code=502,
                    error_code="GENERATION_INVALID_CREDENTIALS",
                ) from exc

            except groq.PermissionDeniedError as exc:
                log_event(
                    logger,
                    40,
                    "groq_permission_error",
                    exception_type=type(exc).__name__,
                    exception_message=str(exc),
                )

                raise ApplicationException(
                    message="Groq permission was denied.",
                    status_code=502,
                    error_code="GENERATION_PERMISSION_DENIED",
                ) from exc

            except groq.NotFoundError as exc:
                log_event(
                    logger,
                    40,
                    "groq_model_not_found",
                    exception_type=type(exc).__name__,
                    exception_message=str(exc),
                )

                raise ApplicationException(
                    message="The configured Groq model was not found.",
                    status_code=502,
                    error_code="GENERATION_MODEL_NOT_FOUND",
                ) from exc

            except groq.BadRequestError as exc:
                log_event(
                    logger,
                    40,
                    "groq_bad_request",
                    exception_type=type(exc).__name__,
                    exception_message=str(exc),
                )

                raise ApplicationException(
                    message="Groq rejected the generation request.",
                    status_code=502,
                    error_code="GENERATION_BAD_REQUEST",
                ) from exc

            except groq.RateLimitError as exc:
                last_exception = exc

                log_event(
                    logger,
                    40,
                    "groq_rate_limit",
                    attempt=attempt,
                    max_retries=settings.GROQ_MAX_RETRIES,
                    exception_type=type(exc).__name__,
                    exception_message=str(exc),
                )

                if attempt >= settings.GROQ_MAX_RETRIES:
                    raise ApplicationException(
                        message="Groq rate limit was reached.",
                        status_code=429,
                        error_code="GENERATION_RATE_LIMIT",
                    ) from exc

                await self._backoff(attempt)

            except groq.APITimeoutError as exc:
                last_exception = exc

                log_event(
                    logger,
                    40,
                    "groq_timeout",
                    attempt=attempt,
                    max_retries=settings.GROQ_MAX_RETRIES,
                    exception_type=type(exc).__name__,
                    exception_message=str(exc),
                )

                if attempt >= settings.GROQ_MAX_RETRIES:
                    raise ApplicationException(
                        message="Groq generation timed out.",
                        status_code=504,
                        error_code="GENERATION_TIMEOUT",
                    ) from exc

                await self._backoff(attempt)

            except groq.APIConnectionError as exc:
                last_exception = exc

                log_event(
                    logger,
                    40,
                    "groq_connection_error",
                    attempt=attempt,
                    max_retries=settings.GROQ_MAX_RETRIES,
                    exception_type=type(exc).__name__,
                    exception_message=str(exc),
                )

                if attempt >= settings.GROQ_MAX_RETRIES:
                    raise ApplicationException(
                        message="Groq could not be reached.",
                        status_code=502,
                        error_code="GENERATION_CONNECTION_ERROR",
                    ) from exc

                await self._backoff(attempt)

            except groq.InternalServerError as exc:
                last_exception = exc

                log_event(
                    logger,
                    40,
                    "groq_internal_server_error",
                    attempt=attempt,
                    max_retries=settings.GROQ_MAX_RETRIES,
                    exception_type=type(exc).__name__,
                    exception_message=str(exc),
                )

                if attempt >= settings.GROQ_MAX_RETRIES:
                    raise ApplicationException(
                        message="Groq returned an internal error.",
                        status_code=502,
                        error_code="GENERATION_PROVIDER_ERROR",
                    ) from exc

                await self._backoff(attempt)

            except groq.APIStatusError as exc:
                log_event(
                    logger,
                    40,
                    "groq_api_status_error",
                    exception_type=type(exc).__name__,
                    exception_message=str(exc),
                    provider_status=exc.status_code,
                )

                raise ApplicationException(
                    message="Groq returned an API error.",
                    status_code=502,
                    error_code="GENERATION_PROVIDER_ERROR",
                    details={
                        "provider_status": exc.status_code,
                    },
                ) from exc

            except Exception as exc:
                # This block is intentionally last.
                # It catches unexpected SDK/runtime errors while also
                # preserving useful diagnostics in the backend terminal.
                log_event(
                    logger,
                    40,
                    "groq_unexpected_exception",
                    exception_type=type(exc).__name__,
                    exception_message=str(exc),
                )

                raise ApplicationException(
                    message="Unexpected Groq generation failure.",
                    status_code=502,
                    error_code="GENERATION_PROVIDER_ERROR",
                    details={
                        "exception_type": type(exc).__name__,
                    },
                ) from exc

        raise ApplicationException(
            message="Groq generation failed after retries.",
            status_code=502,
            error_code="GENERATION_RETRY_EXHAUSTED",
        ) from last_exception

    @staticmethod
    def _reasoning_options() -> dict:
        """
        Reasoning parameters are only accepted by reasoning models such as
        GPT-OSS. Sending them to other models makes Groq reject the request.
        Reasoning content is never exposed to the frontend.
        """

        if "gpt-oss" in settings.GROQ_MODEL.lower():
            return {
                "include_reasoning": False,
                "reasoning_effort": "low",
            }

        return {}

    @staticmethod
    def _validate_response(response) -> str:
        """
        Validate the Groq SDK response and return assistant text.
        """

        if response is None:
            raise ApplicationException(
                message="Groq returned no response.",
                status_code=502,
                error_code="GENERATION_EMPTY_RESPONSE",
            )

        choices = getattr(response, "choices", None)

        if not choices:
            raise ApplicationException(
                message="Groq response contained no choices.",
                status_code=502,
                error_code="GENERATION_INVALID_RESPONSE",
            )

        message = getattr(choices[0], "message", None)
        content = getattr(message, "content", None)

        if not isinstance(content, str) or not content.strip():
            raise ApplicationException(
                message="Groq returned empty assistant content.",
                status_code=502,
                error_code="GENERATION_EMPTY_RESPONSE",
            )

        return content.strip()

    @staticmethod
    async def _backoff(attempt: int) -> None:
        """
        Exponential retry delay:
            attempt 0 -> 1 second
            attempt 1 -> 2 seconds
            attempt 2 -> 4 seconds
            ...
        Maximum delay is capped at 8 seconds.
        """

        delay = min(2**attempt, 8)
        await asyncio.sleep(delay)

    async def close(self) -> None:
        """
        Close the underlying Groq async client when supported.
        """

        close = getattr(self.client, "close", None)

        if close is not None:
            result = close()

            if hasattr(result, "__await__"):
                await result
