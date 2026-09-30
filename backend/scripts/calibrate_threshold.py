import argparse
import asyncio
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.embeddings.huggingface import HuggingFaceEmbeddingAdapter
from app.query.retrieval import ChromaRetriever
from app.vector_store.chroma import get_vector_store


async def main() -> None:
    parser = argparse.ArgumentParser(
        description="Inspect Chroma distances for threshold calibration."
    )
    parser.add_argument(
        "--relevant",
        action="append",
        default=[],
        help="Question expected to be answerable from the documents.",
    )
    parser.add_argument(
        "--irrelevant",
        action="append",
        default=[],
        help="Question expected to be unrelated to the documents.",
    )
    args = parser.parse_args()

    if not args.relevant and not args.irrelevant:
        raise SystemExit(
            "Provide at least one --relevant or --irrelevant question."
        )

    store = get_vector_store()
    retriever = ChromaRetriever(store)
    embedder = HuggingFaceEmbeddingAdapter()

    for category, questions in (
        ("RELEVANT", args.relevant),
        ("IRRELEVANT", args.irrelevant),
    ):
        for question in questions:
            vector = await embedder.embed_one(question)
            candidates = retriever.retrieve(vector)

            print()
            print(category)
            print("Question:", question)

            for index, candidate in enumerate(candidates, start=1):
                print(
                    f"  #{index}: distance={candidate.distance:.6f} "
                    f"file={candidate.filename} "
                    f"pages={candidate.start_page}-{candidate.end_page}"
                )


if __name__ == "__main__":
    asyncio.run(main())
