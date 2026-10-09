package com.pdfrag.embedding;

import java.util.List;

public interface EmbeddingProvider {

    List<float[]> embedTexts(List<String> texts);

    default float[] embedOne(String text) {
        return embedTexts(List.of(text)).get(0);
    }

    /** Preferred batch size, used for diagnostics only. */
    int batchSize();
}
