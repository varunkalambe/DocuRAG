from app.core.config import settings
from app.core.exceptions import ApplicationException
from app.query.models import ValidatedQuery
from app.vector_store.chroma import ChromaStore


class QueryValidator:
    def __init__(self, store: ChromaStore) -> None:
        self.store = store

    def validate(
        self,
        question: str | None,
    ) -> ValidatedQuery:
        if question is None:
            raise ApplicationException(
                message="Request question is required.",
                status_code=400,
                error_code="QUESTION_REQUIRED",
            )

        if not isinstance(question, str):
            raise ApplicationException(
                message="Question must be textual.",
                status_code=400,
                error_code="QUESTION_MUST_BE_TEXT",
            )

        cleaned = question.strip()

        if not cleaned:
            raise ApplicationException(
                message="Question cannot be empty.",
                status_code=400,
                error_code="EMPTY_QUESTION",
            )

        if len(cleaned) > settings.MAX_QUESTION_LENGTH:
            raise ApplicationException(
                message="Question exceeds the maximum allowed length.",
                status_code=413,
                error_code="QUESTION_TOO_LONG",
                details={
                    "max_length": settings.MAX_QUESTION_LENGTH,
                },
            )

        if self.store.count() == 0:
            raise ApplicationException(
                message="No indexed document is available for querying.",
                status_code=409,
                error_code="NO_DOCUMENTS_INDEXED",
            )

        return ValidatedQuery(question=cleaned)
