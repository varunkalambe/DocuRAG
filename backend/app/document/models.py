from dataclasses import dataclass


@dataclass(frozen=True)
class ExtractedPage:
    page_number: int
    text: str
    is_empty: bool


@dataclass(frozen=True)
class NormalizedPage:
    page_number: int
    text: str
    is_empty: bool
