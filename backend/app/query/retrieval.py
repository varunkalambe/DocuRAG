import math

from app.core.config import settings
from app.core.exceptions import ApplicationException
from app.query.models import RetrievedChunk
from app.vector_store.chroma import ChromaStore


class ChromaRetriever:
    def __init__(self, store: ChromaStore) -> None:
        self.store = store

    def retrieve(
        self,
        query_embedding: list[float],
    ) -> list[RetrievedChunk]:
        count = self.store.count()

        if count == 0:
            raise ApplicationException(
                message="No indexed document is available for retrieval.",
                status_code=409,
                error_code="NO_DOCUMENTS_INDEXED",
            )

        n_results = min(settings.TOP_K, count)

        result = self.store.query_by_embedding(
            query_embedding=query_embedding,
            n_results=n_results,
        )

        ids = result.get("ids", [[]])[0]
        documents = result.get("documents", [[]])[0]
        metadatas = result.get("metadatas", [[]])[0]
        distances = result.get("distances", [[]])[0]

        if not (
            len(ids)
            == len(documents)
            == len(metadatas)
            == len(distances)
        ):
            raise ApplicationException(
                message="Chroma returned inconsistent retrieval arrays.",
                status_code=502,
                error_code="RETRIEVAL_SHAPE_MISMATCH",
            )

        items: list[RetrievedChunk] = []

        for chunk_id, document, metadata, distance in zip(
            ids, documents, metadatas, distances
        ):
            try:
                numeric_distance = float(distance)
            except (TypeError, ValueError) as exc:
                raise ApplicationException(
                    message="Chroma returned a non-numeric distance.",
                    status_code=502,
                    error_code="INVALID_RETRIEVAL_DISTANCE",
                ) from exc

            if not math.isfinite(numeric_distance):
                raise ApplicationException(
                    message="Chroma returned a non-finite distance.",
                    status_code=502,
                    error_code="INVALID_RETRIEVAL_DISTANCE",
                )

            metadata = metadata or {}

            items.append(
                RetrievedChunk(
                    chunk_id=str(chunk_id),
                    text=str(document or ""),
                    filename=str(metadata.get("filename", "")),
                    document_fingerprint=str(
                        metadata.get("document_fingerprint", "")
                    ),
                    start_page=int(metadata.get("start_page", 0)),
                    end_page=int(metadata.get("end_page", 0)),
                    sequence=int(metadata.get("sequence", 0)),
                    word_count=int(metadata.get("word_count", 0)),
                    distance=numeric_distance,
                )
            )

        # Chroma normally returns nearest results first. We sort explicitly
        # so this service has deterministic behavior independent of provider
        # implementation details.
        items.sort(
            key=lambda item: (
                item.distance,
                item.sequence,
            )
        )

        return [
            RetrievedChunk(
                chunk_id=item.chunk_id,
                text=item.text,
                filename=item.filename,
                document_fingerprint=item.document_fingerprint,
                start_page=item.start_page,
                end_page=item.end_page,
                sequence=item.sequence,
                word_count=item.word_count,
                distance=item.distance,
                rank=index,
            )
            for index, item in enumerate(items, start=1)
        ]
