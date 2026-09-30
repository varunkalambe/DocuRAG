from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class Chunk:
    chunk_id: str
    document_fingerprint: str
    filename: str
    text: str
    start_page: int
    end_page: int
    sequence: int
    word_count: int
    source_boundaries: tuple[str, ...]

    @property
    def metadata(self) -> dict[str, Any]:
        return {
            "document_fingerprint": self.document_fingerprint,
            "filename": self.filename,
            "start_page": self.start_page,
            "end_page": self.end_page,
            "sequence": self.sequence,
            "word_count": self.word_count,
            "source_boundaries": "|".join(self.source_boundaries),
        }
