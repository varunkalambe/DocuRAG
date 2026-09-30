from fastapi import Request
from fastapi.responses import JSONResponse

from app.observability.logging import get_logger, log_event


logger = get_logger("pdf_rag.errors")


class ApplicationException(Exception):
    def __init__(
        self,
        message: str,
        status_code: int = 500,
        error_code: str = "APPLICATION_ERROR",
        details=None,
    ):
        self.message = message
        self.status_code = status_code
        self.error_code = error_code
        self.details = details
        super().__init__(message)


async def application_exception_handler(
    request: Request,
    exc: ApplicationException,
):
    log_event(
        logger,
        30,
        "application_error",
        error_code=exc.error_code,
        status_code=exc.status_code,
    )

    return JSONResponse(
        status_code=exc.status_code,
        content={
            "success": False,
            "error": {
                "code": exc.error_code,
                "message": exc.message,
                "details": exc.details,
            },
        },
    )
