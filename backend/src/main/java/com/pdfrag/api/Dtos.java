package com.pdfrag.api;

import com.pdfrag.store.DocumentSummary;
import java.util.List;

/** Response payloads. Property names are serialized as snake_case (see application.yml). */
public final class Dtos {

    private Dtos() {}

    public record HealthData(String status, String application, String environment) {}

    public record UploadData(
            String documentId,
            String filename,
            String status,
            String message,
            int pageCount,
            int emptyPageCount,
            int chunkCount,
            int indexedCount) {}

    public record DocumentListData(List<DocumentSummary> documents, int totalChunks) {}

    public record SourceMetadata(
            String sourceId,
            String filename,
            String chunkId,
            int startPage,
            int endPage,
            int sequence,
            int rank,
            double distance) {}

    public record RetrievalMetadata(
            int candidates,
            int accepted,
            int topK,
            double relevanceThreshold,
            int contextTokenEstimate,
            String mode) {}

    public record QueryData(
            String answer, List<SourceMetadata> sources, String status, RetrievalMetadata retrieval) {}

    public record MemoryResetData(String status, String message) {}

    public record QueryRequest(String question) {}
}
