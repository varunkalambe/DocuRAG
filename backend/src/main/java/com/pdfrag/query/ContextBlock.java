package com.pdfrag.query;

public record ContextBlock(
        String sourceId,
        String chunkId,
        String filename,
        int startPage,
        int endPage,
        int sequence,
        double distance,
        String text,
        int estimatedTokens) {}
