from app.query.models import QueryResult


class GenerationResponseNormalizer:
    """Convert internal query results into the stable application contract."""

    @staticmethod
    def to_data(result: QueryResult) -> dict:
        return {
            "answer": result.answer,
            "sources": [
                {
                    "source_id": item.source_id,
                    "filename": item.filename,
                    "chunk_id": item.chunk_id,
                    "start_page": item.start_page,
                    "end_page": item.end_page,
                    "sequence": item.sequence,
                    "rank": item.rank,
                    "distance": item.distance,
                }
                for item in result.sources
            ],
            "status": result.status,
            "retrieval": {
                "candidates": result.candidate_count,
                "accepted": result.accepted_count,
                "top_k": result.top_k,
                "relevance_threshold": result.relevance_threshold,
                "context_token_estimate": result.context_token_estimate,
                "mode": result.mode,
            },
        }