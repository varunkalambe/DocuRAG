package com.pdfrag.indexing;

import com.pdfrag.chunking.Chunk;
import com.pdfrag.error.ApplicationException;
import com.pdfrag.obs.Logs;
import com.pdfrag.store.StoredChunk;
import com.pdfrag.store.VectorStore;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class Indexer {

    private static final Logger LOG = LoggerFactory.getLogger("pdf_rag.indexing");

    public record IndexResult(String documentFingerprint, int chunkCount, int indexedCount) {}

    private final VectorStore store;

    public Indexer(VectorStore store) {
        this.store = store;
    }

    public VectorStore store() {
        return store;
    }

    public void ensureDocumentNotIndexed(String fingerprint) {
        List<String> existing = store.findIdsByDocument(fingerprint);
        if (!existing.isEmpty()) {
            throw new ApplicationException(
                    "This document is already indexed.", 409, "DOCUMENT_ALREADY_INDEXED",
                    Map.of("document_fingerprint", fingerprint, "existing_records", existing.size()));
        }
    }

    public IndexResult indexDocument(List<Chunk> chunks, List<float[]> embeddings) {
        if (chunks.isEmpty()) {
            throw new ApplicationException("Cannot index an empty chunk list.", 422, "NO_CHUNKS_TO_INDEX");
        }
        if (chunks.size() != embeddings.size()) {
            throw new ApplicationException(
                    "Chunk count and embedding count do not match.", 500, "CHUNK_EMBEDDING_COUNT_MISMATCH",
                    Map.of("chunks", chunks.size(), "embeddings", embeddings.size()));
        }

        String fingerprint = chunks.get(0).documentFingerprint();
        for (Chunk chunk : chunks) {
            if (!chunk.documentFingerprint().equals(fingerprint)) {
                throw new ApplicationException(
                        "Chunks from multiple documents cannot be indexed atomically.", 500,
                        "MIXED_DOCUMENT_CHUNKS");
            }
        }

        ensureDocumentNotIndexed(fingerprint);

        Set<Integer> dimensions = new HashSet<>();
        for (float[] vector : embeddings) {
            dimensions.add(vector.length);
        }
        if (dimensions.size() != 1) {
            throw new ApplicationException(
                    "Embedding vectors do not have consistent dimensionality.", 502,
                    "INDEX_EMBEDDING_DIMENSION_MISMATCH");
        }

        int newDimension = dimensions.iterator().next();
        Integer storedDimension = store.storedDimension();
        if (storedDimension != null && storedDimension != newDimension) {
            throw new ApplicationException(
                    "The stored vectors have a different dimensionality (" + storedDimension
                            + ") than the new embeddings (" + newDimension
                            + "). Use 'Clear memory' and upload again.",
                    409, "INDEX_EMBEDDING_DIMENSION_CONFLICT",
                    Map.of("stored_dimension", storedDimension, "new_dimension", newDimension));
        }

        List<StoredChunk> records = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            Chunk c = chunks.get(i);
            records.add(new StoredChunk(
                    c.chunkId(), c.text(), c.filename(), c.documentFingerprint(), c.startPage(), c.endPage(),
                    c.sequence(), c.wordCount(), String.join("|", c.sourceBoundaries()), c.documentPageCount(),
                    embeddings.get(i)));
        }

        try {
            store.addRecords(records);
            List<String> indexedIds = store.findIdsByDocument(fingerprint);

            if (indexedIds.size() != records.size()) {
                throw new ApplicationException(
                        "The vector store indexed an unexpected number of document records.", 502,
                        "INDEX_COUNT_MISMATCH",
                        Map.of("expected", records.size(), "actual", indexedIds.size()));
            }
            return new IndexResult(fingerprint, chunks.size(), indexedIds.size());

        } catch (ApplicationException e) {
            rollback(fingerprint);
            throw e;
        } catch (RuntimeException e) {
            Logs.error(LOG, "indexing_failed", e, "exception_type", e.getClass().getSimpleName(),
                    "document_fingerprint", fingerprint);
            rollback(fingerprint);
            throw new ApplicationException(
                    "Document indexing failed.", 502, "INDEXING_FAILED",
                    Map.of("exception_type", e.getClass().getSimpleName()), e);
        }
    }

    private void rollback(String fingerprint) {
        try {
            store.deleteByDocument(fingerprint);
        } catch (RuntimeException e) {
            throw new ApplicationException(
                    "Indexing failed and rollback could not be completed.", 500, "INDEX_ROLLBACK_FAILED",
                    Map.of("document_fingerprint", fingerprint), e);
        }
    }
}
