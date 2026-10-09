package com.pdfrag.query;

import com.pdfrag.error.ApplicationException;
import com.pdfrag.store.ScoredChunk;
import com.pdfrag.store.StoredChunk;
import com.pdfrag.store.VectorStore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class Retriever {

    private final VectorStore store;
    private final int topK;

    public Retriever(VectorStore store, int topK) {
        this.store = store;
        this.topK = topK;
    }

    public List<RetrievedChunk> retrieve(float[] queryEmbedding) {
        int count = store.count();
        if (count == 0) {
            throw new ApplicationException(
                    "No indexed document is available for retrieval.", 409, "NO_DOCUMENTS_INDEXED");
        }

        List<ScoredChunk> hits = store.queryByEmbedding(queryEmbedding, Math.min(topK, count));

        List<RetrievedChunk> items = new ArrayList<>(hits.size());
        for (ScoredChunk hit : hits) {
            double distance = hit.distance();
            if (Double.isNaN(distance) || Double.isInfinite(distance)) {
                throw new ApplicationException(
                        "The vector store returned a non-finite distance.", 502, "INVALID_RETRIEVAL_DISTANCE");
            }
            StoredChunk c = hit.chunk();
            items.add(new RetrievedChunk(c.chunkId(), c.text(), c.filename(), c.documentFingerprint(),
                    c.startPage(), c.endPage(), c.sequence(), c.wordCount(), distance, 0));
        }

        // Deterministic order independent of store internals.
        items.sort(Comparator.comparingDouble(RetrievedChunk::distance)
                .thenComparingInt(RetrievedChunk::sequence));

        List<RetrievedChunk> ranked = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            ranked.add(items.get(i).withRank(i + 1));
        }
        return ranked;
    }
}
