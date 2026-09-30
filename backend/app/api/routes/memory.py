from fastapi import APIRouter
from fastapi.concurrency import run_in_threadpool

from app.schemas.contracts import MemoryResetResponse
from app.vector_store.chroma import get_vector_store


router = APIRouter(prefix="/memory", tags=["Memory"])



@router.delete("", response_model=MemoryResetResponse)
async def reset_memory():
    store = get_vector_store()
    await run_in_threadpool(store.reset_collection)

    return {
        "success": True,
        "data": {
            "status": "cleared",
            "message": "Semantic memory was cleared.",
        },
    }
