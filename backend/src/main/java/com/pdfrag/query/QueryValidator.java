package com.pdfrag.query;

import com.pdfrag.error.ApplicationException;
import com.pdfrag.store.VectorStore;
import java.util.Map;

public class QueryValidator {

    private final VectorStore store;
    private final int maxQuestionLength;

    public QueryValidator(VectorStore store, int maxQuestionLength) {
        this.store = store;
        this.maxQuestionLength = maxQuestionLength;
    }

    public ValidatedQuery validate(String question) {
        if (question == null) {
            throw new ApplicationException("Request question is required.", 400, "QUESTION_REQUIRED");
        }

        String cleaned = question.strip();
        if (cleaned.isEmpty()) {
            throw new ApplicationException("Question cannot be empty.", 400, "EMPTY_QUESTION");
        }
        if (cleaned.length() > maxQuestionLength) {
            throw new ApplicationException(
                    "Question exceeds the maximum allowed length.", 413, "QUESTION_TOO_LONG",
                    Map.of("max_length", maxQuestionLength));
        }
        if (store.count() == 0) {
            throw new ApplicationException(
                    "No indexed document is available for querying.", 409, "NO_DOCUMENTS_INDEXED");
        }
        return new ValidatedQuery(cleaned);
    }
}
