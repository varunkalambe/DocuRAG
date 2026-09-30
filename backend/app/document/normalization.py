import re

from app.document.models import ExtractedPage, NormalizedPage

CONTROL_CHARACTERS = re.compile(r"[\x00-\x08\x0B\x0C\x0E-\x1F\x7F]")
MULTIPLE_SPACES = re.compile(r"[ \t]+")
EXCESSIVE_NEWLINES = re.compile(r"\n{3,}")
LINE_BREAK_HYPHEN = re.compile(r"(?<=[a-z])-\n(?=[a-z])")


def normalize_text(text: str) -> str:
    if not text:
        return ""

    normalized = text.replace("\r\n", "\n").replace("\r", "\n")
    normalized = CONTROL_CHARACTERS.sub("", normalized)
    normalized = normalized.replace("\t", " ")
    normalized = LINE_BREAK_HYPHEN.sub("", normalized)

    lines: list[str] = []
    for line in normalized.split("\n"):
        cleaned = MULTIPLE_SPACES.sub(" ", line).strip()
        lines.append(cleaned)

    normalized = "\n".join(lines)
    normalized = EXCESSIVE_NEWLINES.sub("\n\n", normalized)
    return normalized.strip()


def normalize_pages(pages: list[ExtractedPage]) -> list[NormalizedPage]:
    normalized_pages: list[NormalizedPage] = []

    for page in pages:
        text = normalize_text(page.text)
        normalized_pages.append(
            NormalizedPage(
                page_number=page.page_number,
                text=text,
                is_empty=not bool(text.strip()),
            )
        )

    return normalized_pages
