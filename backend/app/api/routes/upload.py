from fastapi import APIRouter, File, UploadFile, status

from app.core.config import settings
from app.schemas.contracts import UploadResponse
from app.services.factory import get_rag_orchestrator


router = APIRouter(prefix="/documents", tags=["Documents"])


@router.post(
    "/upload",
    response_model=UploadResponse,
    status_code=status.HTTP_200_OK,
)
async def upload_document(file: UploadFile = File(...)):
    """
    Public ingestion endpoint.

    The endpoint owns HTTP concerns only. The complete ingestion pipeline
    is executed by the Stage 37 RAG orchestrator.
    """

    try:
        max_read_size = settings.MAX_UPLOAD_SIZE_BYTES + 1
        file_bytes = await file.read(max_read_size)

        result = await get_rag_orchestrator().ingest_document(
            file_bytes=file_bytes,
            filename=file.filename or "",
            content_type=file.content_type,
        )

        return {
            "success": True,
            "data": {
                "document_id": result.document_id,
                "filename": result.filename,
                "status": result.status,
                "message": result.message,
                "page_count": result.page_count,
                "empty_page_count": result.empty_page_count,
                "chunk_count": result.chunk_count,
                "indexed_count": result.indexed_count,
            },
        }
    finally:
        await file.close()