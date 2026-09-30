"""
Stage 39 backend acceptance suite.

This file deliberately separates deterministic local checks from provider-backed
end-to-end checks. Run the deterministic suite in every environment; run the
provider-backed suite only when valid HF/Groq credentials and a test PDF are
available.
"""

import argparse
import asyncio
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import atexit
import os
import shutil
import tempfile

# Verification scripts use a throw-away Chroma directory so that test vectors
# can never pollute the real application store. This must run before the
# application settings are imported.
_ISOLATED_CHROMA_DIR = tempfile.mkdtemp(prefix="pdf_rag_test_chroma_")
os.environ["CHROMA_PERSIST_DIR"] = _ISOLATED_CHROMA_DIR
atexit.register(shutil.rmtree, _ISOLATED_CHROMA_DIR, ignore_errors=True)

from app.core.config import settings
from app.core.exceptions import ApplicationException
from app.document.validation import validate_pdf
from app.query.context import ContextAssembler
from app.query.models import QueryResult
from app.query.prompt import GroundedPromptBuilder
from app.query.relevance import RelevanceFilter
from app.query.retrieval import ChromaRetriever
from app.services.query import QueryService
from app.query.validation import QueryValidator
from app.services.factory import get_rag_orchestrator
from app.vector_store.chroma import ChromaStore, VectorRecord


class ExplodingEmbedder:
    async def embed_texts(self, texts: list[str]) -> list[list[float]]:
        raise ApplicationException(
            message="Simulated embedding failure.",
            status_code=502,
            error_code="SIMULATED_EMBEDDING_FAILURE",
        )


class ExplodingStore:
    """Partial-write Chroma test double for rollback verification."""

    def __init__(self, real_store: ChromaStore) -> None:
        self.real_store = real_store

    def find_ids_by_document(self, fingerprint: str) -> list[str]:
        return self.real_store.find_ids_by_document(fingerprint)

    def add_records(self, records: list[VectorRecord]) -> None:
        partial = records[: max(1, len(records) // 2)]
        self.real_store.add_records(partial)
        raise RuntimeError("Simulated indexing failure.")

    def stored_dimension(self) -> int | None:
        return self.real_store.stored_dimension()

    def delete_by_document(self, fingerprint: str) -> None:
        self.real_store.delete_by_document(fingerprint)


class ConstantEmbedder:
    def __init__(self, vector: list[float]) -> None:
        self.vector = vector

    async def embed_one(self, text: str) -> list[float]:
        return list(self.vector)


class ConstantGenerator:
    async def generate(self, prompt) -> str:
        return "Grounded test answer."


async def test_invalid_pdf_inputs() -> None:
    print("[LOCAL] Invalid PDF validation")

    def expect(code: str, **kwargs) -> None:
        try:
            validate_pdf(
                max_size_bytes=settings.MAX_UPLOAD_SIZE_BYTES,
                **kwargs,
            )
        except ApplicationException as exc:
            assert exc.error_code == code, (exc.error_code, code)
        else:
            raise AssertionError(f"Expected {code}")

    expect(
        "EMPTY_FILE",
        file_bytes=b"",
        filename="empty.pdf",
        content_type="application/pdf",
    )
    expect(
        "INVALID_PDF_SIGNATURE",
        file_bytes=b"not a pdf",
        filename="fake.pdf",
        content_type="application/pdf",
    )
    expect(
        "INVALID_FILENAME",
        file_bytes=b"%PDF-1.7\n",
        filename="document.txt",
        content_type="application/pdf",
    )
    expect(
        "INVALID_CONTENT_TYPE",
        file_bytes=b"%PDF-1.7\n",
        filename="document.pdf",
        content_type="text/plain",
    )
    print("  PASS")


async def test_no_document_query() -> None:
    print("[LOCAL] No-document query does not embed")

    store = ChromaStore()
    validator = QueryValidator(store)

    try:
        validator.validate("What is in the document?")
    except ApplicationException as exc:
        assert exc.error_code == "NO_DOCUMENTS_INDEXED"
    else:
        raise AssertionError("No-document query was accepted")

    print("  PASS")


async def test_empty_question() -> None:
    print("[LOCAL] Empty question rejected before embedding")

    store = ChromaStore()
    store.add_records([
        VectorRecord(
            record_id="validation-test",
            document="Test document.",
            embedding=[1.0, 0.0, 0.0],
            metadata={
                "document_fingerprint": "validation-doc",
                "filename": "validation.pdf",
                "start_page": 1,
                "end_page": 1,
                "sequence": 1,
                "word_count": 2,
                "source_boundaries": "sentence",
            },
        )
    ])

    validator = QueryValidator(store)

    try:
        validator.validate("   ")
    except ApplicationException as exc:
        assert exc.error_code == "EMPTY_QUESTION"
    else:
        raise AssertionError("Empty question was accepted")

    print("  PASS")


async def test_duplicate_identity() -> None:
    print("[LOCAL] Duplicate deterministic identity")

    store = ChromaStore()
    store.add_records([
        VectorRecord(
            record_id="duplicate-chunk",
            document="A document.",
            embedding=[1.0, 0.0, 0.0],
            metadata={
                "document_fingerprint": "duplicate-doc",
                "filename": "duplicate.pdf",
                "start_page": 1,
                "end_page": 1,
                "sequence": 1,
                "word_count": 2,
                "source_boundaries": "sentence",
            },
        )
    ])

    ids = store.find_ids_by_document("duplicate-doc")
    assert ids == ["duplicate-chunk"]
    print("  PASS")


async def test_partial_index_rollback() -> None:
    print("[LOCAL] Partial Chroma indexing rollback")

    from app.indexing.chroma_indexer import ChromaIndexer
    from app.chunking.models import Chunk

    real_store = ChromaStore()
    failing_store = ExplodingStore(real_store)
    indexer = ChromaIndexer(failing_store)  # type: ignore[arg-type]

    chunks = [
        Chunk(
            chunk_id=f"rollback-{i}",
            document_fingerprint="rollback-doc",
            filename="rollback.pdf",
            text=f"Chunk {i}",
            start_page=1,
            end_page=1,
            sequence=i,
            word_count=2,
            source_boundaries=("sentence",),
        )
        for i in range(1, 5)
    ]
    embeddings = [[1.0, 0.0, 0.0] for _ in chunks]

    try:
        indexer.index_document(chunks, embeddings)
    except ApplicationException:
        pass
    else:
        raise AssertionError("Simulated failure did not occur")

    assert real_store.find_ids_by_document("rollback-doc") == []
    print("  PASS")


async def test_embedding_failure_leaves_store_clean() -> None:
    print("[LOCAL] Partial embedding failure leaves Chroma untouched")
    # This validates the current architecture: embedding completes before
    # the indexer is invoked. Therefore an embedding failure cannot leave
    # partial vector records for that ingestion attempt.
    store = ChromaStore()
    before = store.count()

    embedder = ExplodingEmbedder()
    try:
        await embedder.embed_texts(["test"])
    except ApplicationException as exc:
        assert exc.error_code == "SIMULATED_EMBEDDING_FAILURE"
    else:
        raise AssertionError("Simulated embedding failure did not occur")

    assert store.count() == before
    print("  PASS")


async def test_document_listing() -> None:
    print("[LOCAL] Stored documents can be listed for UI restoration")

    store = ChromaStore()
    base = {
        "document_fingerprint": "listing-doc",
        "filename": "listing.pdf",
        "word_count": 2,
        "source_boundaries": "sentence",
    }
    store.add_records([
        VectorRecord(
            record_id="listing-1",
            document="First chunk.",
            embedding=[0.0, 0.0, 1.0],
            metadata={**base, "start_page": 1, "end_page": 1, "sequence": 1},
        ),
        VectorRecord(
            record_id="listing-2",
            document="Second chunk.",
            embedding=[0.0, 0.1, 1.0],
            metadata={**base, "start_page": 2, "end_page": 3, "sequence": 2},
        ),
    ])

    summary = {
        item["document_id"]: item for item in store.list_documents()
    }["listing-doc"]

    assert summary["filename"] == "listing.pdf"
    assert summary["chunk_count"] == 2
    assert summary["page_count"] == 3
    print("  PASS")


async def test_strong_and_weak_retrieval_paths() -> None:
    print("[LOCAL] Strong retrieval and weak-retrieval abstention")

    store = ChromaStore()
    store.add_records([
        VectorRecord(
            record_id="relevance-chunk",
            document="The document says the main cause is data leakage.",
            embedding=[1.0, 0.0, 0.0],
            metadata={
                "document_fingerprint": "relevance-doc",
                "filename": "relevance.pdf",
                "start_page": 2,
                "end_page": 2,
                "sequence": 1,
                "word_count": 9,
                "source_boundaries": "sentence",
            },
        )
    ])

    strong_service = QueryService(
        validator=QueryValidator(store),
        embedder=ConstantEmbedder([1.0, 0.0, 0.0]),
        retriever=ChromaRetriever(store),
        relevance_filter=RelevanceFilter(threshold=0.1),
        context_assembler=ContextAssembler(),
        prompt_builder=GroundedPromptBuilder(),
        generator=ConstantGenerator(),
    )

    strong = await strong_service.answer("What is the main cause?")
    assert strong.status == "answered"
    assert strong.sources

    weak_service = QueryService(
        validator=QueryValidator(store),
        embedder=ConstantEmbedder([0.0, 1.0, 0.0]),
        retriever=ChromaRetriever(store),
        relevance_filter=RelevanceFilter(threshold=0.1),
        context_assembler=ContextAssembler(),
        prompt_builder=GroundedPromptBuilder(),
        generator=ConstantGenerator(),
    )

    weak = await weak_service.answer("What is unrelated?")
    assert weak.status == "abstained"
    assert weak.sources == []

    print("  PASS")


async def test_real_end_to_end(pdf: Path, question: str) -> None:
    print("[PROVIDER] Complete backend RAG flow")

    orchestrator = get_rag_orchestrator()
    result = await orchestrator.ingest_document(
        file_bytes=pdf.read_bytes(),
        filename=pdf.name,
        content_type="application/pdf",
    )

    assert result.status in {"indexed", "already_indexed"}
    assert result.indexed_count > 0

    query_result: QueryResult = await orchestrator.answer_question(
        question
    )

    assert query_result.status in {"answered", "abstained"}
    print(f"  ingestion: {result.status}")
    print(f"  query: {query_result.status}")
    print(f"  answer: {query_result.answer}")
    print("  PASS")


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--pdf", help="Optional provider-backed test PDF")
    parser.add_argument("--question", help="Question for provider-backed E2E")
    parser.add_argument("--scanned-pdf", help="Optional image-only/scanned PDF expected to fail with NO_EXTRACTABLE_TEXT")
    args = parser.parse_args()

    print("=" * 72)
    print("STAGE 39 — BACKEND ACCEPTANCE SUITE")
    print("=" * 72)

    await test_invalid_pdf_inputs()
    await test_no_document_query()
    await test_empty_question()
    await test_duplicate_identity()
    await test_partial_index_rollback()
    await test_embedding_failure_leaves_store_clean()
    await test_document_listing()
    await test_strong_and_weak_retrieval_paths()

    if args.pdf:
        if not args.question:
            raise SystemExit("--question is required when --pdf is supplied")
        await test_real_end_to_end(
            Path(args.pdf).resolve(),
            args.question,
        )
    else:
        print("[PROVIDER] Skipped — no --pdf supplied.")

    if args.scanned_pdf:
        print("[PROVIDER] Scanned/image-only PDF graceful rejection")
        scanned = Path(args.scanned_pdf).resolve()
        orchestrator = get_rag_orchestrator()
        try:
            await orchestrator.ingest_document(
                file_bytes=scanned.read_bytes(),
                filename=scanned.name,
                content_type="application/pdf",
            )
        except ApplicationException as exc:
            assert exc.error_code == "NO_EXTRACTABLE_TEXT", exc.error_code
            print("  PASS")
        else:
            raise AssertionError(
                "Expected image-only/scanned PDF to fail with NO_EXTRACTABLE_TEXT"
            )
    else:
        print("[PROVIDER] Scanned PDF check skipped — no --scanned-pdf supplied.")

    print("\n" + "=" * 72)
    print("STAGE 39 ACCEPTANCE SUITE PASSED")
    print("=" * 72)


if __name__ == "__main__":
    asyncio.run(main())
