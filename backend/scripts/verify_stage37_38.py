import argparse
import sys
import asyncio
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.generation.normalizer import GenerationResponseNormalizer
from app.services.factory import get_rag_orchestrator
from app.vector_store.chroma import get_vector_store


async def main() -> None:
    parser = argparse.ArgumentParser(
        description="Stage 37–38 backend-only end-to-end verification."
    )
    parser.add_argument(
        "--pdf",
        required=True,
        help="Path to a valid PDF to ingest.",
    )
    parser.add_argument(
        "--question",
        required=True,
        help="Question to ask after ingestion.",
    )
    parser.add_argument(
        "--reset-first",
        action="store_true",
        help="Clear the current ephemeral Chroma collection before the run.",
    )
    args = parser.parse_args()

    pdf_path = Path(args.pdf).resolve()
    if not pdf_path.exists():
        raise FileNotFoundError(pdf_path)

    store = get_vector_store()
    if args.reset_first:
        store.reset_collection()

    orchestrator = get_rag_orchestrator()

    print("=" * 72)
    print("STAGE 37–38 — BACKEND-ONLY END-TO-END TEST")
    print("=" * 72)

    print("\n[1] Ingesting PDF")
    result = await orchestrator.ingest_document(
        file_bytes=pdf_path.read_bytes(),
        filename=pdf_path.name,
        content_type="application/pdf",
    )

    print(f"  status      : {result.status}")
    print(f"  document ID : {result.document_id}")
    print(f"  pages       : {result.page_count}")
    print(f"  chunks      : {result.chunk_count}")
    print(f"  indexed     : {result.indexed_count}")

    if result.status not in {"indexed", "already_indexed"}:
        raise AssertionError(
            f"Unexpected ingestion status: {result.status}"
        )

    print("  PASS")

    print("\n[2] Verifying indexed state")
    indexed_count = store.count()
    print(f"  total Chroma records: {indexed_count}")
    assert indexed_count > 0
    print("  PASS")

    print("\n[3] Asking question")
    query_result = await orchestrator.answer_question(
        args.question
    )

    print(f"  status     : {query_result.status}")
    print(f"  candidates : {query_result.candidate_count}")
    print(f"  accepted   : {query_result.accepted_count}")
    print(f"  answer     : {query_result.answer}")

    if query_result.status == "answered":
        print("  sources:")
        for source in query_result.sources:
            print(
                "    - "
                f"{source.filename} "
                f"pages {source.start_page}-{source.end_page} "
                f"distance={source.distance:.6f}"
            )
    elif query_result.status != "abstained":
        raise AssertionError(
            f"Unexpected query status: {query_result.status}"
        )

    print("  PASS")

    normalized = GenerationResponseNormalizer.to_data(
        query_result
    )
    assert normalized["status"] in {
        "answered",
        "abstained",
    }

    print("\n[4] Stable application response")
    print(normalized)
    print("  PASS")

    print("\n" + "=" * 72)
    print("STAGES 37–38 PASSED")
    print("=" * 72)


if __name__ == "__main__":
    asyncio.run(main())
