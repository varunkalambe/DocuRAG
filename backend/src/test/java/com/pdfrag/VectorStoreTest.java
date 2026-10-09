package com.pdfrag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.pdfrag.store.ScoredChunk;
import com.pdfrag.store.StoredChunk;
import com.pdfrag.store.VectorStore;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VectorStoreTest {

    private static StoredChunk chunk(String id, String doc, int seq, float... v) {
        return new StoredChunk(id, "text " + id, doc + ".pdf", doc, 1, 2, seq, 2, "sentence", 5, v);
    }

    @Test
    void persistsAcrossRestartsAndSearches(@TempDir Path dir) {
        VectorStore store = new VectorStore(dir, "cosine");
        assertNull(store.storedDimension());
        store.addRecords(List.of(chunk("a", "d1", 1, 1, 0), chunk("b", "d1", 2, 0, 1), chunk("c", "d2", 1, 1, 1)));

        VectorStore reloaded = new VectorStore(dir, "cosine");
        assertEquals(3, reloaded.count());
        assertEquals(2, reloaded.storedDimension());

        List<ScoredChunk> hits = reloaded.queryByEmbedding(new float[] {1, 0}, 2);
        assertEquals("a", hits.get(0).chunk().chunkId());
        assertEquals(0.0, hits.get(0).distance(), 1e-9);
        assertEquals("c", hits.get(1).chunk().chunkId());
    }

    @Test
    void listsAndDeletesDocuments(@TempDir Path dir) {
        VectorStore store = new VectorStore(dir, "cosine");
        store.addRecords(List.of(chunk("a", "d1", 1, 1, 0), chunk("b", "d1", 2, 0, 1), chunk("c", "d2", 1, 1, 1)));

        var docs = store.listDocuments();
        assertEquals(2, docs.size());
        assertEquals(2, docs.get(0).chunkCount());
        assertEquals(5, docs.get(0).pageCount());

        store.deleteByDocument("d1");
        assertEquals(1, store.count());
        store.resetCollection();
        assertEquals(0, new VectorStore(dir, "cosine").count());
    }
}
