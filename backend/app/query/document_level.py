"""
Document-level question support.

Chunk retrieval answers questions about *content inside* the document. It
cannot answer questions about the document *as a whole* ("What is this
document about?", "Summarize it", "How many pages?", "Who wrote it?"),
because such a question is not semantically close to any single chunk.

This module provides:

1. ``is_document_level_question`` - a fast, typo-tolerant, format-agnostic
   rule-based classifier for whole-document questions.
2. ``DocumentOverviewBuilder`` - builds a representative view of each indexed
   document (profile metadata + excerpts sampled from beginning, middle and
   end) directly from the vector store, without needing a query embedding.
"""

from __future__ import annotations

import difflib
import re
import unicodedata
from dataclasses import dataclass

from app.core.config import settings
from app.core.exceptions import ApplicationException
from app.query.models import RetrievedChunk
from app.vector_store.chroma import ChromaStore


# ---------------------------------------------------------------------------
# Question normalisation (typos, casing, markdown, spacing)
# ---------------------------------------------------------------------------

_TYPO_MAP = {
    "waht": "what", "wat": "what", "wht": "what", "whta": "what",
    "whats": "what's", "wats": "what's", "whts": "what's",
    "thsi": "this", "ths": "this", "tihs": "this", "taht": "that",
    "abuot": "about", "abotu": "about", "abt": "about", "aboutt": "about",
    "hwo": "how", "hw": "how", "wht's": "what's",
    "doc": "document", "docs": "documents", "docu": "document",
    "documnet": "document", "documet": "document", "doucment": "document",
    "docuemnt": "document", "documnt": "document",
    "sumary": "summary", "summry": "summary", "sumarize": "summarize",
    "sumarise": "summarise", "summerize": "summarize",
    "summerise": "summarise", "summery": "summary",
    "tldr": "tl;dr", "pls": "please", "plz": "please",
    "u": "you", "ur": "your",
}

_FUZZY_VOCABULARY = (
    "document", "documents", "summary", "summaries", "summarize",
    "summarise", "summarization", "overview", "explain", "describe",
    "contents", "structure", "outline", "purpose", "paper", "papers",
    "report", "reports", "article", "articles", "uploaded", "about",
    "pages", "author", "authors", "title", "topic", "topics", "theme",
    "takeaways", "highlights", "synopsis", "abstract", "chapters",
    "sections", "attached", "analyze", "analyse", "mention",
)
_FUZZY_SET = frozenset(_FUZZY_VOCABULARY)
_LONG_WORD = re.compile(r"[a-z]{6,}")
_WORD = re.compile(r"[a-z']+")


def _fix_long_word(match: re.Match[str]) -> str:
    word = match.group(0)
    if word in _FUZZY_SET:
        return word
    close = difflib.get_close_matches(word, _FUZZY_VOCABULARY, n=1, cutoff=0.84)
    if not close:
        return word
    candidate = close[0]
    # Leave plain inflections alone (section/sections, summarized/summarize):
    # only genuine misspellings are corrected.
    if candidate.startswith(word) or word.startswith(candidate):
        return word
    return candidate


def _fix_word(match: re.Match[str]) -> str:
    word = match.group(0)
    return _TYPO_MAP.get(word, word)


def normalize_question(question: str) -> str:
    text = unicodedata.normalize("NFKC", question or "").lower()
    text = text.replace("\u2019", "'").replace("`", "'").replace("\u2018", "'")
    text = re.sub(r"[*_#>~|]+", " ", text)          # markdown noise
    text = re.sub(r"\s+", " ", text).strip()
    text = _WORD.sub(_fix_word, text)
    text = _LONG_WORD.sub(_fix_long_word, text)
    return text


# ---------------------------------------------------------------------------
# Rule-based classifier
# ---------------------------------------------------------------------------

_DOC = (
    r"(?:documents?|files?|pdfs?|papers?|reports?|articles?|texts?|uploads?|"
    r"materials?|books?|resumes?|cvs?|thesis|theses|manuals?|contracts?|"
    r"studies|study|policy|policies|presentations?|decks?|essays?|notes|"
    r"handouts?|brochures?|pages?|docs?)"
)
_DOC_ADJ = (
    r"(?:uploaded|attached|whole|entire|full|complete|given|provided|"
    r"current|indexed|pdf)"
)
_REF_BODY = rf"(?:(?:this|that|the|my|our|these|those|your)\s+)?(?:{_DOC_ADJ}\s+)*{_DOC}"
_REF = rf"(?:{_REF_BODY})"
_PRON = rf"(?:{_REF_BODY}|this|it|that)"
_END = r"\s*[?!.]*\s*$"

_SUMMARY_WORD = re.compile(
    r"\b(?:summar(?:y|ies|ize|ise|ization|isation|ized|ised)|synopsis|"
    r"tl;?dr|recap|gist|rundown|run\s?down|abstract|overview|digest)\b"
)

_BARE = re.compile(
    r"^(?:please\s+)?(?:summary|summaries|summarize|summarise|overview|"
    r"synopsis|tl;?dr|gist|recap|abstract|outline|about|title|author|authors|"
    r"toc|contents|table of contents|structure|topic|topics|pages|length|"
    r"key points|main points|highlights|takeaways|key takeaways)"
    r"\s*[?!.]*$"
)

_ABOUT_TAIL = (
    r"\babout(?:\s+(?:all|exactly|really|actually|overall|in\s+(?:short|brief|"
    r"general|a\s+nutshell|simple\s+terms|a\s+(?:line|sentence|word|nutshell)|"
    r"one\s+(?:line|sentence)|\d+\s+(?:lines?|sentences?|words?|points?|"
    r"bullets?)))|\s*,?\s*(?:briefly|in\s+short))?\s*[?!.]*\s*$"
)

_FOCUS = re.compile(
    r"\b(?:of|for|in|on|about|regarding|concerning|related\s+to|from|during)\s+"
    rf"(?!(?:{_REF_BODY}|this|it|that|everything|all|these|those|my|our|"
    r"the\s+(?:whole|entire|full|complete|overall)|\d+|one|two|three|four|"
    r"five|six|a\s+(?:few|couple|short|brief|single|nutshell|sentence|"
    r"paragraph|line|word|bullet)|few|short|brief|simple|plain|bullets?|"
    r"points?|lines?|sentences?|words?|paragraphs?|detail|depth|english|"
    r"hindi|marathi|tamil|telugu|bengali|gujarati|urdu|spanish|french|"
    r"german|arabic|chinese|japanese|portuguese|russian|italian|"
    r"simple\s+terms)\b)\w"
)

_NUMBERED_SCOPE = re.compile(
    r"\b(?:sections?|chapters?|parts?|appendix|appendices|clauses?|articles?|"
    r"slides?|figures?|tables?)\s*(?:\d+(?:\.\d+)*|[ivxlc]+)\b|\bpages?\s*\d+\b"
)

_SCOPE_NOUN = re.compile(
    r"\b(?:section|chapter|paragraph|clause|appendix|table|figure|slide|"
    r"introduction|conclusion|methodology|methods|results|discussion|"
    r"abstract|foreword|preface|footnote)s?\b"
)

_STRUCTURE_WORD = re.compile(
    r"\b(?:outline|structure|structured|organi[sz]ation|organi[sz]ed|layout|"
    r"table\s+of\s+contents|toc|headings?|chapters?|sections?|sub-?sections?|"
    r"topics\s+covered|agenda)\b"
)

_FORMAT_ONLY_TAIL = re.compile(
    r"^(?:\s*(?:in|with|using|as)\s+(?:\d+|one|two|three|four|five|a\s+few|"
    r"bullet|bullets|points|short|brief|simple|plain)\b.*)?$"
)


def _is_scoped(text: str) -> bool:
    """True when the question narrows to a specific topic/section/page."""
    return bool(
        _FOCUS.search(text)
        or _NUMBERED_SCOPE.search(text)
        or _SCOPE_NOUN.search(text)
    )


def _compile(*patterns: str) -> tuple[re.Pattern[str], ...]:
    return tuple(re.compile(pattern) for pattern in patterns)


# Patterns that are document-level on their own (unless scoped).
_SCOPE_SENSITIVE = _compile(
    # main/key points family
    r"\b(?:main|key|central|core|primary|major|important|principal|overall|"
    r"big|general|top)\s+(?:points?|ideas?|topics?|themes?|takeaways?|"
    r"messages?|purposes?|objectives?|goals?|aims?|focus|subjects?|"
    r"arguments?|findings?|highlights?|contents?|claims?|conclusions?|"
    r"insights?|lessons?|concepts?|contributions?|outcomes?)\b",
    r"\bhighlights\b",
    r"\btakeaways?\b",
    # explain / describe / analyse the document
    r"^(?:(?:please|pls|can you|could you|would you|kindly|just|now|hey|hi|"
    r"ok|okay)\s+)*(?:explain|describe|analy[sz]e|review|read|examine|"
    r"break\s+down|walk\s+me\s+through|go\s+through|go\s+over|brief\s+me\s+on|"
    r"help\s+me\s+understand|digest|decode)\b.{0,30}?\b" + _PRON + r"\b",
    r"^(?:(?:please|pls|can you|could you|would you|kindly|just|now|hey|hi|"
    r"ok|okay)\s+)*give\s+me\s+(?:an?\s+|the\s+)?"
    r"(?:quick\s+|short\s+|brief\s+|simple\s+|detailed\s+|high[- ]level\s+|"
    r"general\s+|basic\s+|rough\s+)*(?:idea|picture|sense|gist|rundown|"
    r"understanding|introduction|intro|description|explanation|brief|info|"
    r"information|details)\b",
)

# Patterns that are always document-level (identity / metadata / listing).
_ALWAYS = _compile(
    # what is this document / what's this / what is it
    r"^(?:so\s+|ok\s+|okay\s+)?(?:what|which)(?:\s+(?:is|are|was)|'s)\s+"
    r"(?:this|that|the|it)(?:\s+(?:uploaded|attached|given|provided|indexed))*"
    rf"(?:\s+{_DOC})?{_END}",
    # what is this document about
    rf"\b(?:what|which|who|tell|explain|describe|say|state|is|are|does|do)\b"
    rf".*\b{_PRON}\b.*{_ABOUT_TAIL}",
    # tell me about this document
    r"\b(?:tell|talk|explain|say|speak|describe|brief|inform|teach)\s+"
    r"(?:me\s+)?(?:something\s+|more\s+|everything\s+|a\s+(?:little|bit)\s+)?"
    rf"(?:about|of)\s+{_PRON}(?:\s+in\s+\w+(?:\s+\w+){{0,3}})?{_END}",
    # what does this document contain / cover / say (no object)
    rf"\b(?:what|which)\b.{{0,40}}?\b{_PRON}\s+(?:contains?|covers?|includes?|"
    r"discuss(?:es)?|talks?|describes?|says?|deals?|focus(?:es)?|presents?|"
    r"explains?|mentions?|is\s+(?:all\s+)?about|is\s+(?:for|regarding|"
    rf"concerned|related))(?:\s+(?:with|on|about))?{_END}",
    # what's in this document
    rf"\bwhat(?:'s|\s+is|\s+are)\s+(?:\w+\s+){{0,2}}?(?:in|inside|within)\s+"
    rf"{_PRON}{_END}",
    # what kind/type of document
    rf"\b(?:what|which)\s+(?:kind|type|sort|category|genre|format)\s+of\s+"
    rf"(?:{_DOC}|content|text|material)\b",
    # is this a resume / is this about X
    rf"\bis\s+(?:this|it|that|{_REF})\s+(?:an?\s+)\w+(?:\s+\w+){{0,2}}{_END}",
    rf"\bis\s+(?:this|it|that|{_REF})\s+(?:about|related\s+to|regarding|"
    r"concerning)\b",
    # topic / purpose / theme of the document
    rf"\b(?:purpose|goal|aim|objective|intent|scope|context|background|"
    rf"subject|topic|theme|gist|idea|essence|message|focus|summary|overview|"
    rf"outline|structure|contents?)\s+(?:of|behind|for)\s+{_PRON}\b",
    r"\bwhat(?:'s|\s+is|\s+are)\s+(?:the\s+)?(?:main\s+|primary\s+|general\s+|"
    r"overall\s+|core\s+|central\s+)?(?:topic|subject|theme|purpose|gist|idea|"
    rf"focus|scope|context|background|contents?|message)s?{_END}",
    rf"\bwhy\s+(?:was|is)\s+{_PRON}\s+(?:written|made|created|prepared|"
    r"published|needed|important)\b",
    # audience
    r"\b(?:target|intended|primary)\s+(?:audience|readers?|users?)\b",
    rf"\bwho\s+(?:is|are)\s+{_PRON}\s+(?:for|aimed|intended|meant|written\s+for)\b",
    rf"\bwho\s+should\s+read\s+{_PRON}\b",
    # what should I know / learn from this document
    r"\bwhat\s+(?:do|should|can|could|would)\s+(?:i|you|we)\s+(?:really\s+|"
    r"actually\s+)?(?:need\s+to\s+|have\s+to\s+|want\s+to\s+)?(?:know|learn|"
    rf"understand|get|take|gather|infer)\b.{{0,30}}?\b{_PRON}\b",
    # title / name
    rf"\b(?:title|name|heading|headline)\s+(?:of|for)\s+{_PRON}\b",
    rf"\bwhat(?:'s|\s+is)\s+(?:the\s+)?(?:title|name){_END}",
    rf"\b(?:called|titled|named)\b.{{0,20}}\b{_PRON}\b|\b{_PRON}\b.{{0,30}}"
    r"\b(?:called|titled|named)\b",
    r"\bfile\s*name\b",
    # author / date
    rf"\bwho\b.{{0,20}}\b(?:wrote|written|authored|created|prepared|"
    rf"published|compiled|produced|made|issued)\b.{{0,30}}\b{_PRON}\b",
    rf"\bwho\s+(?:is|are|was|were)\s+(?:the\s+)?(?:authors?|writers?|"
    rf"creators?|publishers?)\b",
    rf"\b(?:authors?|writers?)\s+(?:of|for)\s+{_PRON}\b",
    rf"\b(?:when|what\s+(?:date|year)|which\s+(?:date|year))\b.{{0,40}}?"
    rf"\b(?:written|published|created|made|issued|dated|prepared|released|"
    rf"authored|submitted)\b",
    # size / length
    r"\bhow\s+many\s+(?:pages?|words?|chunks?|sections?|chapters?)\b",
    rf"\bhow\s+(?:long|big|large|lengthy|extensive)\s+(?:is|was)\s+{_PRON}\b",
    r"\b(?:page\s+count|number\s+of\s+pages|word\s+count|total\s+pages)\b",
    # several documents
    r"\b(?:which|what|list|show|how\s+many)\b.{0,20}\b(?:documents?|files?|"
    r"pdfs?)\b.{0,30}\b(?:uploaded|indexed|available|have|loaded|stored|"
    r"there|do\s+i|in\s+memory)\b",
    r"\bcompare\b.{0,30}\b(?:documents|files|pdfs|papers|reports)\b",
    r"\bdifferences?\s+between\s+(?:the|these|both|my)\s+(?:documents|files|"
    r"pdfs|papers|reports|two)\b",
)


def is_document_level_question(question: str | None) -> bool:
    """
    Decide whether a question concerns the document as a whole.

    The classifier is intentionally forgiving about phrasing: casing,
    punctuation, markdown, missing question marks, polite prefixes,
    imperatives ("summarize it") and common typos are all handled.
    """
    if not question or not question.strip():
        return False

    text = normalize_question(question)

    # Very long questions carry specific context and are content questions.
    if not text or len(text) > 400:
        return False

    if _BARE.match(text):
        return True

    for pattern in _ALWAYS:
        if pattern.search(text):
            return True

    # Summary words: document-level unless narrowed to a topic/section/page.
    if _SUMMARY_WORD.search(text) and not _is_scoped(text):
        return True

    # Structure words need an explicit document reference or list-style
    # phrasing, and must not point at a numbered section/page.
    if (
        _STRUCTURE_WORD.search(text)
        and not _NUMBERED_SCOPE.search(text)
        and (
            re.search(rf"\b{_PRON}\b", text)
            or re.match(r"^(?:what|which|list|show|give|how many|tell)\b", text)
        )
        and not _FOCUS.search(re.sub(r"\b(?:in|of)\s+(?:this|the|it)\b", "", text))
    ):
        return True

    for pattern in _SCOPE_SENSITIVE:
        match = pattern.search(text)
        if match and not _is_scoped(text[match.end():]):
            return True

    return False


# ---------------------------------------------------------------------------
# Overview construction
# ---------------------------------------------------------------------------

@dataclass(frozen=True)
class OverviewExcerpt:
    chunk: RetrievedChunk
    text: str
    position: int
    total_chunks: int


@dataclass(frozen=True)
class DocumentProfile:
    document_id: str
    filename: str
    page_count: int
    chunk_count: int
    excerpts: tuple[OverviewExcerpt, ...]


@dataclass(frozen=True)
class DocumentOverview:
    profiles: tuple[DocumentProfile, ...]
    total_documents: int
    estimated_tokens: int

    @property
    def sources(self) -> list[RetrievedChunk]:
        sources: list[RetrievedChunk] = []
        for profile in self.profiles:
            for excerpt in profile.excerpts:
                sources.append(excerpt.chunk)
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
            for index, item in enumerate(sources, start=1)
        ]


def _estimate_tokens(text: str) -> int:
    return max(1, -(-len(text) // 4))


def _select_positions(total: int, limit: int) -> list[int]:
    """Evenly spaced positions that always include the first and last chunk."""
    if total <= limit:
        return list(range(total))

    if limit == 1:
        return [0]

    positions = {
        round(index * (total - 1) / (limit - 1)) for index in range(limit)
    }
    positions.add(0)
    positions.add(total - 1)

    ordered = sorted(positions)

    # Keep the selection within the limit by dropping interior positions
    # closest to their neighbours first.
    while len(ordered) > limit:
        interior = range(1, len(ordered) - 1)
        drop = min(
            interior,
            key=lambda i: ordered[i + 1] - ordered[i - 1],
        )
        ordered.pop(drop)

    return ordered


def _truncate_words(text: str, limit: int) -> str:
    words = text.split()
    if len(words) <= limit:
        return " ".join(words)
    return " ".join(words[:limit]) + " …"


class DocumentOverviewBuilder:
    """Build a representative whole-document view from the vector store."""

    def __init__(
        self,
        store: ChromaStore,
        max_chunks_per_document: int | None = None,
        excerpt_words: int | None = None,
        max_documents: int | None = None,
        max_tokens: int | None = None,
    ) -> None:
        self.store = store
        self.max_chunks_per_document = (
            max_chunks_per_document
            or settings.OVERVIEW_MAX_CHUNKS_PER_DOCUMENT
        )
        self.excerpt_words = excerpt_words or settings.OVERVIEW_EXCERPT_WORDS
        self.max_documents = max_documents or settings.OVERVIEW_MAX_DOCUMENTS
        self.max_tokens = max_tokens or settings.MAX_CONTEXT_TOKENS

    def build(self) -> DocumentOverview:
        documents = self.store.list_documents()

        if not documents:
            raise ApplicationException(
                message="No indexed document is available for querying.",
                status_code=409,
                error_code="NO_DOCUMENTS_INDEXED",
            )

        selected = documents[: self.max_documents]
        per_document_budget = max(400, self.max_tokens // len(selected))
        # With many documents each one gets fewer excerpts.
        chunk_limit = max(
            3,
            min(
                self.max_chunks_per_document,
                self.max_chunks_per_document * 2 // max(1, len(selected)),
            ),
        )

        profiles: list[DocumentProfile] = []
        total_tokens = 0

        for document in selected:
            chunks = self.store.get_document_chunks(document["document_id"])
            if not chunks:
                continue

            profile = self._build_profile(
                document=document,
                chunks=chunks,
                chunk_limit=chunk_limit,
                budget=per_document_budget,
            )
            profiles.append(profile)
            total_tokens += sum(
                _estimate_tokens(item.text) for item in profile.excerpts
            )

        if not profiles:
            raise ApplicationException(
                message="No indexed document is available for querying.",
                status_code=409,
                error_code="NO_DOCUMENTS_INDEXED",
            )

        return DocumentOverview(
            profiles=tuple(profiles),
            total_documents=len(documents),
            estimated_tokens=total_tokens,
        )

    def _build_profile(
        self,
        document: dict,
        chunks: list[dict],
        chunk_limit: int,
        budget: int,
    ) -> DocumentProfile:
        total = len(chunks)
        positions = _select_positions(total, chunk_limit)

        def make(position: int) -> OverviewExcerpt:
            chunk = chunks[position]
            metadata = chunk["metadata"]
            # The opening chunk usually holds the title/abstract/intro, so it
            # receives a larger excerpt than the sampled ones.
            limit = self.excerpt_words * (2 if position == 0 else 1)
            retrieved = RetrievedChunk(
                chunk_id=chunk["chunk_id"],
                text=chunk["text"],
                filename=str(metadata.get("filename", document["filename"])),
                document_fingerprint=str(
                    metadata.get("document_fingerprint", document["document_id"])
                ),
                start_page=int(metadata.get("start_page", 0) or 0),
                end_page=int(metadata.get("end_page", 0) or 0),
                sequence=int(metadata.get("sequence", 0) or 0),
                word_count=int(metadata.get("word_count", 0) or 0),
                distance=0.0,
                rank=0,
            )
            return OverviewExcerpt(
                chunk=retrieved,
                text=_truncate_words(chunk["text"], limit),
                position=position + 1,
                total_chunks=total,
            )

        excerpts = [make(position) for position in positions]

        # Enforce the token budget by removing interior excerpts first so the
        # beginning and end of the document are always preserved.
        while (
            len(excerpts) > 2
            and sum(_estimate_tokens(item.text) for item in excerpts) > budget
        ):
            excerpts.pop(len(excerpts) // 2)

        return DocumentProfile(
            document_id=document["document_id"],
            filename=document["filename"],
            page_count=int(document.get("page_count", 0) or 0),
            chunk_count=int(document.get("chunk_count", total) or total),
            excerpts=tuple(excerpts),
        )

    @staticmethod
    def render(overview: DocumentOverview) -> str:
        parts: list[str] = []

        profile_lines = ["<document_profile>"]
        profile_lines.append(
            f"indexed_documents_total: {overview.total_documents}"
        )
        if overview.total_documents > len(overview.profiles):
            profile_lines.append(
                f"documents_shown_below: {len(overview.profiles)} "
                "(the rest are omitted for length)"
            )
        for index, profile in enumerate(overview.profiles, start=1):
            profile_lines.append(
                f"document {index}: filename={profile.filename} | "
                f"total_pages={profile.page_count or 'unknown'} | "
                f"indexed_sections={profile.chunk_count} | "
                f"excerpts_shown={len(profile.excerpts)}"
            )
        profile_lines.append("</document_profile>")
        parts.append("\n".join(profile_lines))

        for doc_index, profile in enumerate(overview.profiles, start=1):
            for excerpt in profile.excerpts:
                chunk = excerpt.chunk
                parts.append(
                    "\n".join(
                        [
                            f"[EXCERPT document {doc_index}: {profile.filename}]",
                            f"pages: {chunk.start_page}-{chunk.end_page}",
                            f"position: section {excerpt.position} of "
                            f"{excerpt.total_chunks}",
                            "<document_chunk>",
                            excerpt.text,
                            "</document_chunk>",
                        ]
                    )
                )

        return "\n\n".join(parts)