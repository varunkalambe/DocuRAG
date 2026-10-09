package com.pdfrag.query;

import java.util.List;

public record QueryResult(
        String answer,
        String status,
        List<RetrievedChunk> sources,
        int candidateCount,
        int acceptedCount,
        int topK,
        double relevanceThreshold,
        int contextTokenEstimate,
        String mode) {

    public QueryResult(String answer, String status, List<RetrievedChunk> sources, int candidateCount,
            int acceptedCount, int topK, double relevanceThreshold, int contextTokenEstimate) {
        this(answer, status, sources, candidateCount, acceptedCount, topK, relevanceThreshold,
                contextTokenEstimate, "retrieval");
    }
}
