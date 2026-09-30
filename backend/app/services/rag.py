from dataclasses import dataclass

from app.query.models import QueryResult
from app.services.ingestion import IngestionResult, IngestionService
from app.services.query import QueryService


@dataclass(frozen=True)
class RagOrchestrator:
    """
    Stage 37 application-level RAG orchestrator.

    It deliberately composes the two independently verified pipelines:

    Ingestion:
        upload -> validate -> extract -> normalize -> fingerprint
        -> chunk -> embed -> index

    Query:
        question -> validate -> embed -> retrieve -> threshold
        -> context -> grounded prompt -> Groq -> normalize

    This class contains orchestration only. Provider-specific logic remains
    inside the ingestion/query services and adapters.
    """

    ingestion_service: IngestionService
    query_service: QueryService

    async def ingest_document(
        self,
        file_bytes: bytes,
        filename: str,
        content_type: str | None,
    ) -> IngestionResult:
        return await self.ingestion_service.ingest(
            file_bytes=file_bytes,
            filename=filename,
            content_type=content_type,
        )

    async def answer_question(
        self,
        question: str | None,
    ) -> QueryResult:
        return await self.query_service.answer(question)
