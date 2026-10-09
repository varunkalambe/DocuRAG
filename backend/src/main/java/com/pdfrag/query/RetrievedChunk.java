package com.pdfrag.query;

public record RetrievedChunk(
        String chunkId,
        String text,
        String filename,
        String documentFingerprint,
        int startPage,
        int endPage,
        int sequence,
        int wordCount,
        double distance,
        int rank) {

    public String sourceId() {
        return documentFingerprint + ":" + chunkId;
    }

    public RetrievedChunk withRank(int newRank) {
        return new RetrievedChunk(chunkId, text, filename, documentFingerprint, startPage, endPage,
                sequence, wordCount, distance, newRank);
    }
}
