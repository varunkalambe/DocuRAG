from app.core.config import settings
from app.query.models import RetrievedChunk


class RelevanceFilter:
    """
    Stage 26-27 relevance gate.

    Because the current Chroma collection is configured for cosine,
    this implementation interprets RELEVANCE_THRESHOLD as a maximum
    allowed cosine distance: lower distance is more similar.

    The exact useful threshold is embedding-model dependent and should
    be calibrated empirically as described by the project design.
    """

    def __init__(
        self,
        threshold: float | None = None,
    ) -> None:
        self.threshold = (
            settings.RELEVANCE_THRESHOLD
            if threshold is None
            else threshold
        )

    def filter(
        self,
        candidates: list[RetrievedChunk],
    ) -> list[RetrievedChunk]:
        accepted = [
            candidate
            for candidate in candidates
            if candidate.distance <= self.threshold
        ]

        accepted.sort(
            key=lambda item: (
                item.distance,
                item.sequence,
            )
        )

        return accepted
