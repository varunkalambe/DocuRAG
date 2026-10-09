package com.pdfrag.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Selects accepted evidence within a token budget and renders it in document order. */
public class ContextAssembler {

    private final int maxTokens;

    public ContextAssembler(int maxTokens) {
        this.maxTokens = maxTokens;
    }

    public static int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    public BuiltContext build(List<RetrievedChunk> accepted) {
        if (accepted.isEmpty()) {
            return new BuiltContext(List.of(), 0);
        }

        List<ContextBlock> selected = new ArrayList<>();
        int total = 0;

        // Strongest results first; weaker candidates that overflow the budget are skipped.
        for (RetrievedChunk item : accepted) {
            int estimate = estimateTokens(item.text());
            if (total + estimate > maxTokens) {
                continue;
            }
            selected.add(new ContextBlock(item.sourceId(), item.chunkId(), item.filename(), item.startPage(),
                    item.endPage(), item.sequence(), item.distance(), item.text(), estimate));
            total += estimate;
        }

        selected.sort(Comparator.comparing(ContextBlock::filename)
                .thenComparingInt(ContextBlock::startPage)
                .thenComparingInt(ContextBlock::sequence));

        return new BuiltContext(selected, total);
    }

    public String render(BuiltContext context) {
        List<String> blocks = new ArrayList<>();
        int index = 1;
        for (ContextBlock block : context.blocks()) {
            blocks.add(String.join("\n",
                    "[SOURCE " + index + "]",
                    "source_id: " + block.sourceId(),
                    "filename: " + block.filename(),
                    "pages: " + block.startPage() + "-" + block.endPage(),
                    "chunk_id: " + block.chunkId(),
                    "sequence: " + block.sequence(),
                    "distance: " + String.format(Locale.ROOT, "%.6f", block.distance()),
                    "text:",
                    "<document_chunk>",
                    block.text(),
                    "</document_chunk>"));
            index++;
        }
        return String.join("\n\n", blocks);
    }
}
