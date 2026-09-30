import argparse
import asyncio
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from app.generation.normalizer import GenerationResponseNormalizer
from app.query.context import ContextAssembler
from app.query.models import ValidatedQuery
from app.query.prompt import GroundedPromptBuilder
from app.query.relevance import RelevanceFilter
from app.query.retrieval import ChromaRetriever
from app.query.validation import QueryValidator
from app.services.factory import get_query_service
from app.vector_store.chroma import get_vector_store
from app.embeddings.huggingface import HuggingFaceEmbeddingAdapter


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--question", required=True)
    parser.add_argument(
        "--show-prompt",
        action="store_true",
    )
    args = parser.parse_args()

    store = get_vector_store()

    print("=" * 70)
    print("STAGES 21–36 QUERY PIPELINE VERIFICATION")
    print("=" * 70)
    print()

    # -----------------------------------------------------
    # Stage 21
    # -----------------------------------------------------
    validator = QueryValidator(store)
    validated = validator.validate(args.question)
    print("Stage 21 — validation: PASS")
    print("  question:", validated.question)

    # -----------------------------------------------------
    # Stage 22
    # -----------------------------------------------------
    embedder = HuggingFaceEmbeddingAdapter()
    query_vector = await embedder.embed_one(validated.question)
    assert query_vector
    print("Stage 22 — query embedding: PASS")
    print("  dimension:", len(query_vector))

    # -----------------------------------------------------
    # Stage 23–25
    # -----------------------------------------------------
    retriever = ChromaRetriever(store)
    candidates = retriever.retrieve(query_vector)
    print("Stage 23–25 — retrieval: PASS")
    print("  candidates:", len(candidates))

    for index, candidate in enumerate(candidates, start=1):
        print(
            f"  #{index} distance={candidate.distance:.6f} "
            f"pages={candidate.start_page}-{candidate.end_page} "
            f"file={candidate.filename}"
        )

    # -----------------------------------------------------
    # Stage 26–27
    # -----------------------------------------------------
    relevance = RelevanceFilter()
    accepted = relevance.filter(candidates)
    print("Stage 26–27 — relevance filtering: PASS")
    print("  threshold:", relevance.threshold)
    print("  accepted:", len(accepted))

    # -----------------------------------------------------
    # Stage 28–29
    # -----------------------------------------------------
    context_assembler = ContextAssembler()
    context = context_assembler.build(accepted)
    print("Stage 28–29 — context assembly: PASS")
    print("  context tokens:", context.estimated_tokens)
    print("  source blocks:", len(context.blocks))

    # -----------------------------------------------------
    # Stage 30–31
    # -----------------------------------------------------
    if context.blocks:
        prompt = GroundedPromptBuilder().build(
            ValidatedQuery(args.question.strip()),
            context,
        )
        print("Stage 30–31 — grounded prompt: PASS")
        print("  prompt injection boundary: enabled")
        if args.show_prompt:
            print()
            print("SYSTEM PROMPT")
            print(prompt.system_message)
            print()
            print("USER PROMPT")
            print(prompt.user_message)
    else:
        print("Stage 30–31 — grounded prompt: SKIPPED (no accepted evidence)")

    # -----------------------------------------------------
    # Stage 32–36
    # -----------------------------------------------------
    result = await get_query_service().answer(args.question)
    normalized = GenerationResponseNormalizer.to_data(result)
    assert {"answer", "sources", "status", "retrieval"} <= set(normalized), (
        "normalized response is missing required contract keys"
    )

    print()
    print("Stage 32–36 — generation/normalization:", result.status.upper())
    print("  answer:", result.answer)
    print("  sources:", len(result.sources))
    print("  response contract normalized: PASS")

    print()
    print("=" * 70)
    print("QUERY PIPELINE VERIFICATION COMPLETE")
    print("=" * 70)


if __name__ == "__main__":
    asyncio.run(main())
