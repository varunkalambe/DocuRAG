import os
from dataclasses import dataclass

from dotenv import load_dotenv

load_dotenv()


class ConfigurationError(Exception):
    pass


def _string(name: str, default: str = "") -> str:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip()


def _csv(name: str, default: str) -> tuple[str, ...]:
    raw = os.getenv(name)
    value = default if raw is None or not raw.strip() else raw
    return tuple(
        item.strip().rstrip("/")
        for item in value.split(",")
        if item.strip()
    )


def _positive_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise ConfigurationError(f"{name} must be an integer.") from exc
    if value <= 0:
        raise ConfigurationError(f"{name} must be greater than zero.")
    return value


def _non_negative_int(name: str, default: int) -> int:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise ConfigurationError(f"{name} must be an integer.") from exc
    if value < 0:
        raise ConfigurationError(f"{name} cannot be negative.")
    return value


def _positive_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = float(raw)
    except ValueError as exc:
        raise ConfigurationError(f"{name} must be a number.") from exc
    if value <= 0:
        raise ConfigurationError(f"{name} must be greater than zero.")
    return value


def _non_negative_float(name: str, default: float) -> float:
    raw = os.getenv(name)
    if raw is None or not raw.strip():
        return default
    try:
        value = float(raw)
    except ValueError as exc:
        raise ConfigurationError(f"{name} must be a number.") from exc
    if value < 0:
        raise ConfigurationError(f"{name} cannot be negative.")
    return value


@dataclass(frozen=True)
class Settings:
    APP_NAME: str
    APP_ENV: str
    API_PREFIX: str

    HUGGINGFACE_API_TOKEN: str
    HF_EMBEDDING_MODEL: str
    HF_PROVIDER: str
    HF_BATCH_SIZE: int
    HF_MAX_RETRIES: int
    HF_RETRY_BASE_SECONDS: float

    GROQ_API_KEY: str
    GROQ_MODEL: str
    GROQ_MAX_COMPLETION_TOKENS: int
    GROQ_TEMPERATURE: float
    GROQ_MAX_RETRIES: int

    CHROMA_COLLECTION_NAME: str
    CHROMA_DISTANCE_METRIC: str
    CHROMA_PERSIST_DIR: str

    CORS_ALLOWED_ORIGINS: tuple[str, ...]

    MAX_UPLOAD_SIZE_BYTES: int

    CHUNK_SIZE: int
    CHUNK_OVERLAP: int

    MAX_QUESTION_LENGTH: int
    TOP_K: int
    RELEVANCE_THRESHOLD: float
    MAX_CONTEXT_TOKENS: int

    HTTP_TIMEOUT_SECONDS: float

    def validate(self) -> None:
        mandatory = {
            "HUGGINGFACE_API_TOKEN": self.HUGGINGFACE_API_TOKEN,
            "HF_EMBEDDING_MODEL": self.HF_EMBEDDING_MODEL,
            "GROQ_API_KEY": self.GROQ_API_KEY,
            "GROQ_MODEL": self.GROQ_MODEL,
        }

        for name, value in mandatory.items():
            if not value.strip():
                raise ConfigurationError(
                    f"Missing mandatory configuration value: {name}"
                )

        if not self.CHROMA_PERSIST_DIR.strip():
            raise ConfigurationError("CHROMA_PERSIST_DIR cannot be empty.")

        if not self.CORS_ALLOWED_ORIGINS:
            raise ConfigurationError(
                "CORS_ALLOWED_ORIGINS must contain at least one origin."
            )

        if "*" in self.CORS_ALLOWED_ORIGINS:
            raise ConfigurationError(
                "CORS_ALLOWED_ORIGINS cannot contain '*'."
            )

        if self.CHROMA_DISTANCE_METRIC not in {"cosine", "l2", "ip"}:
            raise ConfigurationError(
                "CHROMA_DISTANCE_METRIC must be cosine, l2, or ip."
            )

        if self.CHUNK_OVERLAP >= self.CHUNK_SIZE:
            raise ConfigurationError(
                "CHUNK_OVERLAP must be smaller than CHUNK_SIZE."
            )

        if self.TOP_K <= 0:
            raise ConfigurationError("TOP_K must be greater than zero.")

        if not (0 < self.RELEVANCE_THRESHOLD <= 2):
            raise ConfigurationError(
                "RELEVANCE_THRESHOLD must be > 0 and <= 2 for cosine distance."
            )

        if not (0 <= self.GROQ_TEMPERATURE <= 2):
            raise ConfigurationError(
                "GROQ_TEMPERATURE must be between 0 and 2."
            )


settings = Settings(
    APP_NAME=_string("APP_NAME", "PDF RAG Application"),
    APP_ENV=_string("APP_ENV", "development"),
    API_PREFIX=_string("API_PREFIX", "/api"),
    HUGGINGFACE_API_TOKEN=_string("HUGGINGFACE_API_TOKEN"),
    HF_EMBEDDING_MODEL=_string("HF_EMBEDDING_MODEL"),
    HF_PROVIDER=_string("HF_PROVIDER", "auto"),
    HF_BATCH_SIZE=_positive_int("HF_BATCH_SIZE", 8),
    HF_MAX_RETRIES=_positive_int("HF_MAX_RETRIES", 3),
    HF_RETRY_BASE_SECONDS=_positive_float("HF_RETRY_BASE_SECONDS", 1.0),
    GROQ_API_KEY=_string("GROQ_API_KEY"),
    GROQ_MODEL=_string("GROQ_MODEL"),
    GROQ_MAX_COMPLETION_TOKENS=_positive_int(
        "GROQ_MAX_COMPLETION_TOKENS", 512
    ),
    GROQ_TEMPERATURE=_non_negative_float("GROQ_TEMPERATURE", 0.2),
    GROQ_MAX_RETRIES=_non_negative_int("GROQ_MAX_RETRIES", 2),
    CHROMA_COLLECTION_NAME=_string(
        "CHROMA_COLLECTION_NAME", "pdf_rag_documents"
    ),
    CHROMA_DISTANCE_METRIC=_string(
        "CHROMA_DISTANCE_METRIC", "cosine"
    ),
    CHROMA_PERSIST_DIR=_string("CHROMA_PERSIST_DIR", "./chroma_data"),
    CORS_ALLOWED_ORIGINS=_csv(
        "CORS_ALLOWED_ORIGINS",
        "http://127.0.0.1:4200,http://localhost:4200",
    ),
    MAX_UPLOAD_SIZE_BYTES=_positive_int(
        "MAX_UPLOAD_SIZE_BYTES", 10 * 1024 * 1024
    ),
    CHUNK_SIZE=_positive_int("CHUNK_SIZE", 500),
    CHUNK_OVERLAP=_non_negative_int("CHUNK_OVERLAP", 50),
    MAX_QUESTION_LENGTH=_positive_int("MAX_QUESTION_LENGTH", 10000),
    TOP_K=_positive_int("TOP_K", 5),
    RELEVANCE_THRESHOLD=_positive_float(
        "RELEVANCE_THRESHOLD", 0.70
    ),
    MAX_CONTEXT_TOKENS=_positive_int("MAX_CONTEXT_TOKENS", 4000),
    HTTP_TIMEOUT_SECONDS=_positive_float(
        "HTTP_TIMEOUT_SECONDS", 30.0
    ),
)
