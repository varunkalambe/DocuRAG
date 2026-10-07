from app.core.config import settings
from typing import Any, Optional

from pydantic import BaseModel, Field


class ErrorDetail(BaseModel):
    code: str
    message: str
    details: Optional[Any] = None


class ErrorResponse(BaseModel):
    success: bool = False
    error: ErrorDetail


class HealthData(BaseModel):
    status: str
    application: str
    environment: str


class HealthResponse(BaseModel):
    success: bool = True
    data: HealthData


class UploadData(BaseModel):
    document_id: str
    filename: str
    status: str
    message: str
    page_count: int
    empty_page_count: int
    chunk_count: int
    indexed_count: int


class UploadResponse(BaseModel):
    success: bool = True
    data: UploadData


class DocumentSummary(BaseModel):
    document_id: str
    filename: str
    chunk_count: int
    page_count: int


class DocumentListData(BaseModel):
    documents: list[DocumentSummary]
    total_chunks: int


class DocumentListResponse(BaseModel):
    success: bool = True
    data: DocumentListData


class QueryRequest(BaseModel):
    question: str = Field(
        ...,
        strict=True,
        min_length=1,
        max_length=settings.MAX_QUESTION_LENGTH,
        description="Question to ask against the indexed document corpus.",
    )


class SourceMetadata(BaseModel):
    source_id: str
    filename: str
    chunk_id: str
    start_page: int
    end_page: int
    sequence: int
    rank: int
    distance: float


class RetrievalMetadata(BaseModel):
    candidates: int
    accepted: int
    top_k: int
    relevance_threshold: float
    context_token_estimate: int
    mode: str = "retrieval"


class QueryData(BaseModel):
    answer: str
    sources: list[SourceMetadata]
    status: str
    retrieval: RetrievalMetadata


class QueryResponse(BaseModel):
    success: bool = True
    data: QueryData


class MemoryResetData(BaseModel):
    status: str
    message: str


class MemoryResetResponse(BaseModel):
    success: bool = True
    data: MemoryResetData