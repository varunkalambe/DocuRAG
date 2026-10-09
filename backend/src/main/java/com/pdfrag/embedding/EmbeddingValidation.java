package com.pdfrag.embedding;

import com.pdfrag.error.ApplicationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Shared input cleaning / output validation for embedding providers. */
final class EmbeddingValidation {

    private EmbeddingValidation() {}

    static List<String> cleanInputs(List<String> texts) {
        List<String> cleaned = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i) == null ? "" : texts.get(i).strip();
            if (text.isEmpty()) {
                throw new ApplicationException(
                        "Embedding input " + i + " is empty.", 400, "EMPTY_EMBEDDING_INPUT", Map.of("index", i));
            }
            cleaned.add(text);
        }
        return cleaned;
    }

    static boolean finite(float[] vector) {
        for (float v : vector) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                return false;
            }
        }
        return true;
    }
}
