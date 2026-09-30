from dataclasses import dataclass


@dataclass(frozen=True)
class ValidatedQuery:
    question: str


@dataclass(frozen=True)
class RetrievedChunk:
    chunk_id: str
    text: str
    filename: str
    document_fingerprint: str
    start_page: int
    end_page: int
    sequence: int
    word_count: int
    distance: float
    rank: int = 0

    @property
    def source_id(self) -> str:
        return f"{self.document_fingerprint}:{self.chunk_id}"


@dataclass(frozen=True)
class ContextBlock:
    source_id: str
    chunk_id: str
    filename: str
    start_page: int
    end_page: int
    sequence: int
    distance: float
    text: str
    estimated_tokens: int


@dataclass(frozen=True)
class BuiltContext:
    blocks: list[ContextBlock]
    estimated_tokens: int


@dataclass(frozen=True)
class QueryResult:
    answer: str
    status: str
    sources: list[RetrievedChunk]
    candidate_count: int
    accepted_count: int
    top_k: int
    relevance_threshold: float
    context_token_estimate: int
