import hashlib
import re
from dataclasses import dataclass, replace

from app.chunking.models import Chunk
from app.core.config import settings
from app.core.exceptions import ApplicationException
from app.document.models import NormalizedPage


@dataclass(frozen=True)
class _TextUnit:
    text: str
    page_number: int
    kind: str
    words: tuple[str, ...]


NUMBERED_HEADING = re.compile(r"^\s*\d+(?:\.\d+)*[\)\].:\-]?\s+\S+")
SENTENCE_BOUNDARY = re.compile(
    r'(?<=[.!?])\s+(?=(?:["“”\'(\[]?[A-Z0-9]))'
)


class SemanticChunker:
    def __init__(
        self,
        target_words: int | None = None,
        overlap_words: int | None = None,
    ) -> None:
        self.target_words = target_words or settings.CHUNK_SIZE
        self.overlap_words = (
            settings.CHUNK_OVERLAP
            if overlap_words is None
            else overlap_words
        )

        if self.target_words <= 0:
            raise ValueError("target_words must be greater than zero.")
        if self.overlap_words < 0 or self.overlap_words >= self.target_words:
            raise ValueError(
                "overlap_words must be >= 0 and smaller than target_words."
            )

    def chunk_pages(
        self,
        pages: list[NormalizedPage],
        document_fingerprint: str,
        filename: str,
    ) -> list[Chunk]:
        if not document_fingerprint:
            raise ApplicationException(
                message="Document fingerprint is required.",
                status_code=500,
                error_code="MISSING_DOCUMENT_FINGERPRINT",
            )
        if not filename.strip():
            raise ApplicationException(
                message="Filename is required.",
                status_code=500,
                error_code="MISSING_FILENAME",
            )

        units: list[_TextUnit] = []
        for page in pages:
            if not page.is_empty:
                units.extend(self._build_page_units(page))

        if not units:
            raise ApplicationException(
                message="No extractable text was available to create document chunks.",
                status_code=422,
                error_code="NO_EXTRACTABLE_TEXT",
            )

        chunks: list[Chunk] = []
        current_units: list[_TextUnit] = []
        sequence = 1

        for unit in units:
            if unit.kind == "section" and self._contains_source_content(current_units):
                chunks.append(
                    self._build_chunk(
                        current_units, sequence, document_fingerprint, filename
                    )
                )
                sequence += 1
                current_units = self._build_overlap_prefix(current_units)

            if (
                current_units
                and self._contains_source_content(current_units)
                and self._count_words(current_units) + len(unit.words)
                > self.target_words
            ):
                chunks.append(
                    self._build_chunk(
                        current_units, sequence, document_fingerprint, filename
                    )
                )
                sequence += 1
                current_units = self._build_overlap_prefix(current_units)

            current_units.append(unit)

            if self._count_words(current_units) >= self.target_words:
                chunks.append(
                    self._build_chunk(
                        current_units, sequence, document_fingerprint, filename
                    )
                )
                sequence += 1
                current_units = self._build_overlap_prefix(current_units)

        if self._contains_source_content(current_units):
            chunks.append(
                self._build_chunk(
                    current_units, sequence, document_fingerprint, filename
                )
            )

        if not chunks:
            raise ApplicationException(
                message="Chunking produced no usable chunks.",
                status_code=422,
                error_code="NO_CHUNKS_CREATED",
            )

        # Record the true page count of the source PDF on every chunk so
        # document-level questions ("how many pages?") are answered exactly,
        # even when trailing pages contain no extractable text.
        total_pages = len(pages)
        return [
            replace(chunk, document_page_count=total_pages)
            for chunk in chunks
        ]

    def _build_page_units(self, page: NormalizedPage) -> list[_TextUnit]:
        units: list[_TextUnit] = []

        blocks = [
            block.strip()
            for block in re.split(r"\n\s*\n+", page.text)
            if block.strip()
        ]

        for block in blocks:
            lines = [line.strip() for line in block.split("\n") if line.strip()]
            if not lines:
                continue

            if len(lines) == 1 and self._is_section_heading(lines[0]):
                words = tuple(lines[0].split())
                units.append(
                    _TextUnit(
                        text=lines[0],
                        page_number=page.page_number,
                        kind="section",
                        words=words,
                    )
                )
                continue

            if len(lines) > 1 and self._is_section_heading(lines[0]):
                heading = lines[0]
                units.append(
                    _TextUnit(
                        text=heading,
                        page_number=page.page_number,
                        kind="section",
                        words=tuple(heading.split()),
                    )
                )
                block = " ".join(lines[1:])
            else:
                block = " ".join(lines)

            sentences = self._split_sentences(block)
            for sentence in sentences:
                words = sentence.split()
                if not words:
                    continue

                if len(words) <= self.target_words:
                    units.append(
                        _TextUnit(
                            text=" ".join(words),
                            page_number=page.page_number,
                            kind="sentence",
                            words=tuple(words),
                        )
                    )
                    continue

                for start in range(0, len(words), self.target_words):
                    sentence_part = words[start : start + self.target_words]
                    units.append(
                        _TextUnit(
                            text=" ".join(sentence_part),
                            page_number=page.page_number,
                            kind="word",
                            words=tuple(sentence_part),
                        )
                    )

        return units

    @staticmethod
    def _split_sentences(paragraph: str) -> list[str]:
        parts = [
            part.strip()
            for part in SENTENCE_BOUNDARY.split(paragraph)
            if part.strip()
        ]
        return parts or [paragraph.strip()]

    @staticmethod
    def _is_section_heading(text: str) -> bool:
        value = text.strip()
        if not value or len(value) > 140:
            return False
        if value.endswith((".", "!", "?")):
            return False
        if NUMBERED_HEADING.match(value):
            return True

        letters = [character for character in value if character.isalpha()]
        if letters and value.upper() == value:
            return True

        words = value.split()
        if len(words) > 12:
            return False

        alpha_words = [
            word for word in words if any(character.isalpha() for character in word)
        ]
        if len(alpha_words) < 2:
            return False

        title_case_ratio = sum(
            1 for word in alpha_words if word[0].isupper()
        ) / len(alpha_words)
        return title_case_ratio >= 0.75

    def _build_overlap_prefix(
        self,
        units: list[_TextUnit],
    ) -> list[_TextUnit]:
        if self.overlap_words == 0:
            return []

        words: list[str] = []
        page_number = units[-1].page_number if units else 1

        for unit in reversed(units):
            if len(words) >= self.overlap_words:
                break

            remaining = self.overlap_words - len(words)
            unit_words = list(unit.words)
            selected = unit_words[max(0, len(unit_words) - remaining) :]
            words = selected + words

        if not words:
            return []

        return [
            _TextUnit(
                text=" ".join(words),
                page_number=page_number,
                kind="overlap",
                words=tuple(words),
            )
        ]

    @staticmethod
    def _build_chunk(
        units: list[_TextUnit],
        sequence: int,
        document_fingerprint: str,
        filename: str,
    ) -> Chunk:
        real_units = [unit for unit in units if unit.kind != "overlap"]
        if not real_units:
            raise ApplicationException(
                message="Internal chunking error: chunk contains no source content.",
                status_code=500,
                error_code="INVALID_CHUNK",
            )

        words: list[str] = []
        pages: list[int] = []
        boundary_types: list[str] = []

        for unit in units:
            words.extend(unit.words)
            pages.append(unit.page_number)
            if unit.kind not in boundary_types:
                boundary_types.append(unit.kind)

        text = " ".join(words).strip()
        if not text:
            raise ApplicationException(
                message="Internal chunking error: empty chunk produced.",
                status_code=500,
                error_code="EMPTY_CHUNK",
            )

        start_page = min(pages)
        end_page = max(pages)
        word_count = len(words)

        identity = (
            f"{document_fingerprint}|{sequence}|{start_page}|"
            f"{end_page}|{text}"
        )
        chunk_hash = hashlib.sha256(identity.encode("utf-8")).hexdigest()
        chunk_id = f"chunk_{chunk_hash[:32]}"

        return Chunk(
            chunk_id=chunk_id,
            document_fingerprint=document_fingerprint,
            filename=filename,
            text=text,
            start_page=start_page,
            end_page=end_page,
            sequence=sequence,
            word_count=word_count,
            source_boundaries=tuple(boundary_types),
        )

    @staticmethod
    def _contains_source_content(units: list[_TextUnit]) -> bool:
        return any(unit.kind != "overlap" for unit in units)

    @staticmethod
    def _count_words(units: list[_TextUnit]) -> int:
        return sum(len(unit.words) for unit in units)