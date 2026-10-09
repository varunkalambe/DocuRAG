package com.pdfrag.service;

import com.pdfrag.query.QueryResult;
import org.springframework.stereotype.Service;

/** Composes the two independent pipelines: ingestion and query. Orchestration only. */
@Service
public class RagOrchestrator {

    private final IngestionService ingestionService;
    private final QueryService queryService;

    public RagOrchestrator(IngestionService ingestionService, QueryService queryService) {
        this.ingestionService = ingestionService;
        this.queryService = queryService;
    }

    public IngestionResult ingestDocument(byte[] fileBytes, String filename, String contentType) {
        return ingestionService.ingest(fileBytes, filename, contentType);
    }

    public QueryResult answerQuestion(String question) {
        return queryService.answer(question);
    }
}
