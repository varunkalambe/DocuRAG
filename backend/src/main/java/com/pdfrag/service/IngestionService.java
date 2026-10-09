package com.pdfrag.service;

import com.pdfrag.chunking.Chunk;
import com.pdfrag.chunking.SemanticChunker;
import com.pdfrag.config.AppProperties;
import com.pdfrag.document.ExtractedPage;
import com.pdfrag.document.Fingerprint;
import com.pdfrag.document.NormalizedPage;
import com.pdfrag.document.PdfExtractor;
import com.pdfrag.document.PdfValidator;
import com.pdfrag.document.TextNormalizer;
import com.pdfrag.embedding.EmbeddingProvider;
import com.pdfrag.error.ApplicationException;
import com.pdfrag.indexing.Indexer;
import com.pdfrag.obs.Logs;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/** upload -> validate -> extract -> normalize -> fingerprint -> chunk -> embed -> index. */
@Service
public class IngestionService {

    private static final Logger LOG = LoggerFactory.getLogger("pdf_rag.ingestion");

    private final SemanticChunker chunker;
    private final EmbeddingProvider embedder;
    private final Indexer indexer;
    private final AppProperties props;

    public IngestionService(
            SemanticChunker chunker, EmbeddingProvider embedder, Indexer indexer, AppProperties props) {
        this.chunker = chunker;
        this.embedder = embedder;
        this.indexer = indexer;
        this.props = props;
    }

    public IngestionResult ingest(byte[] fileBytes, String filename, String contentType) {
        long started = System.nanoTime();
        Map<String, Double> timings = new LinkedHashMap<>();

        long stage = System.nanoTime();
        PdfValidator.Result validation =
                PdfValidator.validate(fileBytes, filename, contentType, props.maxUploadSizeBytes());
        timings.put("validation_ms", Logs.ms(stage));

        stage = System.nanoTime();
        List<ExtractedPage> extracted = PdfExtractor.extractPages(fileBytes);
        timings.put("extraction_ms", Logs.ms(stage));

        int pageCount = extracted.size();
        int emptyPageCount = (int) extracted.stream().filter(ExtractedPage::empty).count();

        stage = System.nanoTime();
        List<NormalizedPage> normalized = TextNormalizer.normalizePages(extracted);
        timings.put("normalization_ms", Logs.ms(stage));

        boolean meaningful = normalized.stream().anyMatch(p -> !p.text().isBlank());
        if (!meaningful) {
            throw new ApplicationException(
                    "The PDF contains no meaningful extractable text. "
                            + "Scanned/image-only PDFs are not supported by this ingestion pipeline.",
                    422, "NO_EXTRACTABLE_TEXT", Map.of("page_count", pageCount));
        }

        stage = System.nanoTime();
        String documentId = Fingerprint.sha256Hex(fileBytes);
        timings.put("fingerprint_ms", Logs.ms(stage));

        List<String> existingIds = indexer.store().findIdsByDocument(documentId);
        if (!existingIds.isEmpty()) {
            timings.put("total_ms", Logs.ms(started));
            Logs.info(LOG, "document_already_indexed", "filename", validation.filename(),
                    "size_bytes", fileBytes.length, "page_count", pageCount,
                    "empty_page_count", emptyPageCount, "chunk_count", existingIds.size(),
                    "indexed_count", existingIds.size(), "timings_ms", timings);
            return new IngestionResult(documentId, validation.filename(), pageCount, emptyPageCount,
                    existingIds.size(), existingIds.size(), "already_indexed",
                    "This document is already indexed. No duplicate embedding or indexing was performed.");
        }

        stage = System.nanoTime();
        List<Chunk> chunks = chunker.chunkPages(normalized, documentId, validation.filename());
        timings.put("chunking_ms", Logs.ms(stage));

        stage = System.nanoTime();
        List<float[]> embeddings = embedder.embedTexts(chunks.stream().map(Chunk::text).toList());
        timings.put("embedding_ms", Logs.ms(stage));

        stage = System.nanoTime();
        Indexer.IndexResult indexResult = indexer.indexDocument(chunks, embeddings);
        timings.put("indexing_ms", Logs.ms(stage));
        timings.put("total_ms", Logs.ms(started));

        int batch = Math.max(1, embedder.batchSize());
        Logs.info(LOG, "document_ingestion_completed", "filename", validation.filename(),
                "size_bytes", fileBytes.length, "page_count", pageCount,
                "empty_page_count", emptyPageCount, "chunk_count", chunks.size(),
                "embedding_batch_count", (chunks.size() + batch - 1) / batch,
                "vector_count", indexResult.indexedCount(), "timings_ms", timings);

        return new IngestionResult(documentId, validation.filename(), pageCount, emptyPageCount,
                chunks.size(), indexResult.indexedCount(), "indexed",
                "Document ingestion completed successfully. " + pageCount + " pages processed, "
                        + chunks.size() + " chunks created, " + indexResult.indexedCount() + " vectors indexed.");
    }
}
