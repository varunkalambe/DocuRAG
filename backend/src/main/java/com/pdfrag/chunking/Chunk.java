package com.pdfrag.chunking;

import java.util.List;

public record Chunk(
        String chunkId,
        String documentFingerprint,
        String filename,
        String text,
        int startPage,
        int endPage,
        int sequence,
        int wordCount,
        List<String> sourceBoundaries,
        int documentPageCount) {

    public Chunk withDocumentPageCount(int pageCount) {
        return new Chunk(
                chunkId, documentFingerprint, filename, text, startPage, endPage,
                sequence, wordCount, sourceBoundaries, pageCount);
    }
}
