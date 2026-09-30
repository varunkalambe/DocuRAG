from fastapi import APIRouter
from fastapi.concurrency import run_in_threadpool

from app.schemas.contracts import DocumentListResponse
from app.vector_store.chroma import get_vector_store


router = APIRouter(prefix="/documents", tags=["Documents"])


@router.get("", response_model=DocumentListResponse)
async def list_documents():
    """List the documents currently stored in persistent semantic memory."""

    store = get_vector_store()
    documents = await run_in_threadpool(store.list_documents)

    return {
        "success": True,
        "data": {
            "documents": documents,
            "total_chunks": sum(item["chunk_count"] for item in documents),
        },
    }
