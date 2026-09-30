import json
import logging
import sys
from datetime import datetime, timezone
from typing import Any


class JsonFormatter(logging.Formatter):
    """Small dependency-free JSON formatter for application diagnostics."""

    def format(self, record: logging.LogRecord) -> str:
        payload: dict[str, Any] = {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "event": getattr(record, "event", record.getMessage()),
            "message": record.getMessage(),
        }

        fields = getattr(record, "fields", None)
        if isinstance(fields, dict):
            payload["fields"] = fields

        if record.exc_info:
            payload["exception"] = self.formatException(record.exc_info)

        return json.dumps(payload, separators=(",", ":"), default=str)


def configure_logging() -> None:
    root = logging.getLogger()

    if any(isinstance(handler.formatter, JsonFormatter) for handler in root.handlers):
        return

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter())
    root.handlers.clear()
    root.addHandler(handler)
    root.setLevel(logging.INFO)


def get_logger(name: str) -> logging.Logger:
    configure_logging()
    return logging.getLogger(name)


def log_event(
    logger: logging.Logger,
    level: int,
    event: str,
    **fields: Any,
) -> None:
    logger.log(
        level,
        event,
        extra={"event": event, "fields": fields},
    )
