from fastapi import APIRouter

from app.generation.normalizer import GenerationResponseNormalizer
from app.schemas.contracts import QueryRequest, QueryResponse
from app.services.factory import get_rag_orchestrator


router = APIRouter(tags=["Query"])


@router.post("/query", response_model=QueryResponse)
async def query_document(request: QueryRequest):
    """
    Public query endpoint.

    The endpoint delegates the complete query path to the Stage 37
    RAG orchestrator and returns the application-normalized contract.
    """

    result = await get_rag_orchestrator().answer_question(
        request.question
    )

    return {
        "success": True,
        "data": GenerationResponseNormalizer.to_data(result),
    }
