import math

from app.core.config import settings
from app.query.models import (
    BuiltContext,
    ContextBlock,
    RetrievedChunk,
)


class ContextAssembler:
    """
    Select accepted evidence and build an ordered context.

    Selection happens by retrieval strength first. The final context is
    then presented in document order so neighboring evidence reads naturally.
    """

    def __init__(
        self,
        max_tokens: int | None = None,
    ) -> None:
        self.max_tokens = (
            settings.MAX_CONTEXT_TOKENS
            if max_tokens is None
            else max_tokens
        )

    @staticmethod
    def estimate_tokens(text: str) -> int:
        # A deliberately simple conservative estimate. A model-specific
        # tokenizer can replace this later without changing the contract.
        return max(1, math.ceil(len(text) / 4))

    def build(
        self,
        accepted: list[RetrievedChunk],
    ) -> BuiltContext:
        if not accepted:
            return BuiltContext(blocks=[], estimated_tokens=0)

        selected: list[ContextBlock] = []
        total_tokens = 0

        # Strongest results are processed first. Once the budget is reached,
        # weaker candidates are dropped first because they appear later.
        for item in accepted:
            estimate = self.estimate_tokens(item.text)

            if total_tokens + estimate > self.max_tokens:
                continue

            selected.append(
                ContextBlock(
                    source_id=item.source_id,
                    chunk_id=item.chunk_id,
                    filename=item.filename,
                    start_page=item.start_page,
                    end_page=item.end_page,
                    sequence=item.sequence,
                    distance=item.distance,
                    text=item.text,
                    estimated_tokens=estimate,
                )
            )

            total_tokens += estimate

        # Present source evidence in document order instead of similarity order.
        selected.sort(
            key=lambda block: (
                block.filename,
                block.start_page,
                block.sequence,
            )
        )

        return BuiltContext(
            blocks=selected,
            estimated_tokens=total_tokens,
        )

    def render(self, context: BuiltContext) -> str:
        blocks: list[str] = []

        for index, block in enumerate(context.blocks, start=1):
            blocks.append(
                "\n".join(
                    [
                        f"[SOURCE {index}]",
                        f"source_id: {block.source_id}",
                        f"filename: {block.filename}",
                        f"pages: {block.start_page}-{block.end_page}",
                        f"chunk_id: {block.chunk_id}",
                        f"sequence: {block.sequence}",
                        f"distance: {block.distance:.6f}",
                        "text:",
                        "<document_chunk>",
                        block.text,
                        "</document_chunk>",
                    ]
                )
            )

        return "\n\n".join(blocks)
