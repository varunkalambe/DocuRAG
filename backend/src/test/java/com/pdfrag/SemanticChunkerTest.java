package com.pdfrag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.pdfrag.chunking.Chunk;
import com.pdfrag.chunking.SemanticChunker;
import com.pdfrag.document.NormalizedPage;
import com.pdfrag.error.ApplicationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class SemanticChunkerTest {

    private static String sentences(int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append("This is sentence number ").append(i).append(" about the topic. ");
        }
        return sb.toString().strip();
    }

    @Test
    void chunksRespectTargetAndRecordPageCount() {
        var pages = List.of(
                new NormalizedPage(1, sentences(40), false),
                new NormalizedPage(2, "", true),
                new NormalizedPage(3, sentences(20), false));
        List<Chunk> chunks = new SemanticChunker(60, 10).chunkPages(pages, "fp", "a.pdf");

        assertTrue(chunks.size() > 3);
        for (Chunk c : chunks) {
            assertEquals(3, c.documentPageCount());
            assertTrue(c.wordCount() <= 70);
            assertTrue(c.chunkId().startsWith("chunk_"));
        }
        for (int i = 0; i < chunks.size(); i++) {
            assertEquals(i + 1, chunks.get(i).sequence());
        }
    }

    @Test
    void chunkIdsAreDeterministic() {
        var pages = List.of(new NormalizedPage(1, sentences(30), false));
        var a = new SemanticChunker(50, 5).chunkPages(pages, "fp", "a.pdf");
        var b = new SemanticChunker(50, 5).chunkPages(pages, "fp", "a.pdf");
        assertEquals(a.stream().map(Chunk::chunkId).toList(), b.stream().map(Chunk::chunkId).toList());
    }

    @Test
    void noTextFails() {
        var pages = List.of(new NormalizedPage(1, "", true));
        var ex = assertThrows(ApplicationException.class,
                () -> new SemanticChunker(50, 5).chunkPages(pages, "fp", "a.pdf"));
        assertEquals("NO_EXTRACTABLE_TEXT", ex.getErrorCode());
    }
}
