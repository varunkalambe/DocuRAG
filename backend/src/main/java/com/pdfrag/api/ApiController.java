package com.pdfrag.api;

import com.pdfrag.api.Dtos.DocumentListData;
import com.pdfrag.api.Dtos.HealthData;
import com.pdfrag.api.Dtos.MemoryResetData;
import com.pdfrag.api.Dtos.QueryData;
import com.pdfrag.api.Dtos.RetrievalMetadata;
import com.pdfrag.api.Dtos.SourceMetadata;
import com.pdfrag.api.Dtos.UploadData;
import com.fasterxml.jackson.databind.JsonNode;
import com.pdfrag.config.AppProperties;
import com.pdfrag.error.ApplicationException;
import com.pdfrag.query.QueryResult;
import com.pdfrag.service.IngestionResult;
import com.pdfrag.service.RagOrchestrator;
import com.pdfrag.store.DocumentSummary;
import com.pdfrag.store.VectorStore;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** HTTP concerns only; the pipelines are executed by the RagOrchestrator. */
@RestController
public class ApiController {

    private final AppProperties props;
    private final RagOrchestrator orchestrator;
    private final VectorStore store;

    public ApiController(AppProperties props, RagOrchestrator orchestrator, VectorStore store) {
        this.props = props;
        this.orchestrator = orchestrator;
        this.store = store;
    }

    @GetMapping("/")
    public Map<String, Object> root() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("message", "PDF RAG backend is running.");
        data.put("environment", props.env());
        return Map.of("success", true, "data", data);
    }

    @GetMapping("${app.api-prefix}/health")
    public ApiResponse<HealthData> health() {
        return ApiResponse.ok(new HealthData("healthy", props.name(), props.env()));
    }

    @GetMapping("${app.api-prefix}/documents")
    public ApiResponse<DocumentListData> listDocuments() {
        List<DocumentSummary> documents = store.listDocuments();
        int totalChunks = documents.stream().mapToInt(DocumentSummary::chunkCount).sum();
        return ApiResponse.ok(new DocumentListData(documents, totalChunks));
    }

    @PostMapping("${app.api-prefix}/documents/upload")
    public ApiResponse<UploadData> upload(@RequestParam("file") MultipartFile file) throws IOException {
        byte[] bytes;
        try (var in = file.getInputStream()) {
            // Read one byte past the limit so oversize files are rejected with FILE_TOO_LARGE.
            bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8L, props.maxUploadSizeBytes() + 1));
        }

        IngestionResult r = orchestrator.ingestDocument(
                bytes, file.getOriginalFilename() == null ? "" : file.getOriginalFilename(),
                file.getContentType());

        return ApiResponse.ok(new UploadData(r.documentId(), r.filename(), r.status(), r.message(),
                r.pageCount(), r.emptyPageCount(), r.chunkCount(), r.indexedCount()));
    }

    @PostMapping("${app.api-prefix}/query")
    public ApiResponse<QueryData> query(@RequestBody JsonNode body) {
        JsonNode node = body == null ? null : body.get("question");
        if (node == null || node.isNull()) {
            throw validation("missing", "Field required");
        }
        if (!node.isTextual()) {
            throw validation("string_type", "Input should be a valid string");
        }
        String question = node.asText();
        if (question.isEmpty()) {
            throw validation("string_too_short", "String should have at least 1 character");
        }
        if (question.length() > props.maxQuestionLength()) {
            throw validation("string_too_long",
                    "String should have at most " + props.maxQuestionLength() + " characters");
        }

        QueryResult result = orchestrator.answerQuestion(question);
        return ApiResponse.ok(toData(result));
    }

    @DeleteMapping("${app.api-prefix}/memory")
    public ApiResponse<MemoryResetData> resetMemory() {
        store.resetCollection();
        return ApiResponse.ok(new MemoryResetData("cleared", "Semantic memory was cleared."));
    }

    static QueryData toData(QueryResult result) {
        List<SourceMetadata> sources = result.sources().stream()
                .map(s -> new SourceMetadata(s.sourceId(), s.filename(), s.chunkId(), s.startPage(),
                        s.endPage(), s.sequence(), s.rank(), s.distance()))
                .toList();
        return new QueryData(result.answer(), sources, result.status(),
                new RetrievalMetadata(result.candidateCount(), result.acceptedCount(), result.topK(),
                        result.relevanceThreshold(), result.contextTokenEstimate(), result.mode()));
    }

    private static ApplicationException validation(String type, String message) {
        return new ApplicationException("Request validation failed.", 422, "VALIDATION_ERROR",
                List.of(Map.of("type", type, "loc", List.of("body", "question"), "msg", message)));
    }
}
