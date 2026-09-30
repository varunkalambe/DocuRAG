from contextlib import asynccontextmanager

from fastapi import FastAPI, HTTPException, Request
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from fastapi.encoders import jsonable_encoder
from fastapi.responses import JSONResponse

from app.api.router import api_router
from app.observability.logging import configure_logging, get_logger, log_event
from app.core.config import ConfigurationError, settings
from app.core.exceptions import (
    ApplicationException,
    application_exception_handler,
)


configure_logging()
logger = get_logger("pdf_rag.application")


@asynccontextmanager
async def lifespan(app: FastAPI):
    try:
        settings.validate()
    except ConfigurationError as exc:
        raise RuntimeError(
            f"Invalid application configuration: {exc}"
        ) from exc

    log_event(logger, 20, "application_started", environment=settings.APP_ENV)
    yield
    log_event(logger, 20, "application_stopping")


app = FastAPI(
    title=settings.APP_NAME,
    version="1.0.0",
    lifespan=lifespan,
)

# The browser frontend is intentionally kept credential-free for this local
# application. Credentials for Hugging Face and Groq remain server-side.
app.add_middleware(
    CORSMiddleware,
    allow_origins=list(settings.CORS_ALLOWED_ORIGINS),
    allow_credentials=False,
    allow_methods=["GET", "POST", "DELETE", "OPTIONS"],
    allow_headers=["*"],
)

app.add_exception_handler(
    ApplicationException,
    application_exception_handler,
)


@app.exception_handler(HTTPException)
async def http_exception_handler(
    request: Request,
    exc: HTTPException,
):
    return JSONResponse(
        status_code=exc.status_code,
        content={
            "success": False,
            "error": {
                "code": "HTTP_ERROR",
                "message": str(exc.detail),
                "details": None,
            },
        },
    )


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(
    request: Request,
    exc: RequestValidationError,
):
    return JSONResponse(
        status_code=422,
        content={
            "success": False,
            "error": {
                "code": "VALIDATION_ERROR",
                "message": "Request validation failed.",
                "details": jsonable_encoder(exc.errors()),
            },
        },
    )


@app.exception_handler(Exception)
async def generic_exception_handler(
    request: Request,
    exc: Exception,
):
    logger.error(
        "unhandled_exception",
        exc_info=(type(exc), exc, exc.__traceback__),
        extra={
            "event": "unhandled_exception",
            "fields": {
                "method": request.method,
                "path": request.url.path,
            },
        },
    )
    return JSONResponse(
        status_code=500,
        content={
            "success": False,
            "error": {
                "code": "INTERNAL_SERVER_ERROR",
                "message": "An unexpected internal error occurred.",
                "details": None,
            },
        },
    )



@app.middleware("http")
async def request_timing_middleware(request: Request, call_next):
    import time

    started = time.perf_counter()
    response = None
    try:
        response = await call_next(request)
        return response
    finally:
        elapsed_ms = round((time.perf_counter() - started) * 1000, 2)
        log_event(
            logger,
            20,
            "http_request_completed",
            method=request.method,
            path=request.url.path,
            status_code=response.status_code if response is not None else 500,
            latency_ms=elapsed_ms,
        )

app.include_router(
    api_router,
    prefix=settings.API_PREFIX,
)


@app.get("/")
async def root():
    return {
        "success": True,
        "data": {
            "message": "PDF RAG backend is running.",
            "environment": settings.APP_ENV,
        },
    }
