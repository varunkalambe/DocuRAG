package com.pdfrag.service;

public record IngestionResult(
        String documentId,
        String filename,
        int pageCount,
        int emptyPageCount,
        int chunkCount,
        int indexedCount,
        String status,
        String message) {}
