package com.pdfrag.store;

/** One indexed chunk: text, metadata and its embedding vector. */
public record StoredChunk(
        String chunkId,
        String text,
        String filename,
        String documentFingerprint,
        int startPage,
        int endPage,
        int sequence,
        int wordCount,
        String sourceBoundaries,
        int documentPageCount,
        float[] embedding) {}
