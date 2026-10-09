package com.pdfrag.store;

public record DocumentSummary(String documentId, String filename, int chunkCount, int pageCount) {}
