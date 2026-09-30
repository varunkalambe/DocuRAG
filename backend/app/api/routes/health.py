from fastapi import APIRouter

from app.core.config import settings
from app.schemas.contracts import HealthResponse


router = APIRouter(tags=["Health"])


@router.get("/health", response_model=HealthResponse)
async def health_check():
    return {
        "success": True,
        "data": {
            "status": "healthy",
            "application": settings.APP_NAME,
            "environment": settings.APP_ENV,
        },
    }
