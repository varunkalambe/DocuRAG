package com.pdfrag.store;

import com.pdfrag.error.ApplicationException;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Persistent, thread-safe vector store (replaces ChromaDB). Records live in memory, are searched with
 * exact brute-force distance and are persisted to a single binary file after every mutation, so data
 * survives restarts. Supported metrics: cosine, l2 (squared L2), ip (1 - inner product), like Chroma.
 */
public class VectorStore {

    private static final int MAGIC = 0x50524147; // "PRAG"
    private static final int VERSION = 1;

    private final Path file;
    private final String metric;
    private final Map<String, StoredChunk> records = new LinkedHashMap<>();

    public VectorStore(Path directory, String metric) {
        if (!metric.equals("cosine") && !metric.equals("l2") && !metric.equals("ip")) {
            throw new IllegalArgumentException("Distance metric must be cosine, l2, or ip.");
        }
        this.metric = metric;
        try {
            Files.createDirectories(directory);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot create vector store directory " + directory, e);
        }
        this.file = directory.resolve("vectors.bin");
        load();
    }

    public synchronized int count() {
        return records.size();
    }

    public synchronized List<DocumentSummary> listDocuments() {
        record Acc(String filename, int chunks, int pages) {}
        Map<String, Acc> docs = new LinkedHashMap<>();
        for (StoredChunk c : records.values()) {
            if (c.documentFingerprint().isEmpty()) {
                continue;
            }
            Acc prev = docs.get(c.documentFingerprint());
            int pages = Math.max(c.endPage(), c.documentPageCount());
            if (prev == null) {
                docs.put(c.documentFingerprint(), new Acc(c.filename(), 1, pages));
            } else {
                docs.put(c.documentFingerprint(),
                        new Acc(prev.filename(), prev.chunks() + 1, Math.max(prev.pages(), pages)));
            }
        }
        List<DocumentSummary> result = new ArrayList<>();
        docs.forEach((id, a) -> result.add(new DocumentSummary(id, a.filename(), a.chunks(), a.pages())));
        result.sort(Comparator
                .comparing((DocumentSummary d) -> d.filename().toLowerCase())
                .thenComparing(DocumentSummary::documentId));
        return result;
    }

    /** Every chunk of one document in reading order. */
    public synchronized List<StoredChunk> getDocumentChunks(String fingerprint) {
        List<StoredChunk> chunks = new ArrayList<>();
        for (StoredChunk c : records.values()) {
            if (c.documentFingerprint().equals(fingerprint)) {
                chunks.add(c);
            }
        }
        chunks.sort(Comparator.comparingInt(StoredChunk::sequence));
        return chunks;
    }

    /** Dimensionality of stored vectors, or null when empty. */
    public synchronized Integer storedDimension() {
        for (StoredChunk c : records.values()) {
            return c.embedding().length;
        }
        return null;
    }

    public synchronized void addRecords(List<StoredChunk> newRecords) {
        if (newRecords.isEmpty()) {
            return;
        }
        Map<String, StoredChunk> backup = new LinkedHashMap<>(records);
        try {
            for (StoredChunk r : newRecords) {
                records.putIfAbsent(r.chunkId(), r);
            }
            persist();
        } catch (RuntimeException e) {
            records.clear();
            records.putAll(backup);
            throw e;
        }
    }

    public synchronized List<String> findIdsByDocument(String fingerprint) {
        List<String> ids = new ArrayList<>();
        for (StoredChunk c : records.values()) {
            if (c.documentFingerprint().equals(fingerprint)) {
                ids.add(c.chunkId());
            }
        }
        return ids;
    }

    public synchronized void deleteByDocument(String fingerprint) {
        if (records.values().removeIf(c -> c.documentFingerprint().equals(fingerprint))) {
            persist();
        }
    }

    /** Nearest neighbours by the configured metric, closest first. */
    public synchronized List<ScoredChunk> queryByEmbedding(float[] query, int nResults) {
        List<ScoredChunk> scored = new ArrayList<>(records.size());
        for (StoredChunk c : records.values()) {
            if (c.embedding().length != query.length) {
                throw new ApplicationException(
                        "Query embedding dimensionality (" + query.length
                                + ") does not match stored vectors (" + c.embedding().length
                                + "). Use 'Clear memory' and upload again.",
                        409,
                        "INDEX_EMBEDDING_DIMENSION_CONFLICT");
            }
            scored.add(new ScoredChunk(c, distance(query, c.embedding())));
        }
        scored.sort(Comparator.comparingDouble(ScoredChunk::distance));
        return new ArrayList<>(scored.subList(0, Math.min(nResults, scored.size())));
    }

    public synchronized void resetCollection() {
        records.clear();
        persist();
    }

    // ------------------------------------------------------------------

    double distance(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        double l2 = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
            double d = (double) a[i] - b[i];
            l2 += d * d;
        }
        return switch (metric) {
            case "l2" -> l2;
            case "ip" -> 1.0 - dot;
            default -> {
                double denom = Math.sqrt(na) * Math.sqrt(nb);
                yield denom == 0 ? 1.0 : 1.0 - dot / denom;
            }
        };
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC || in.readInt() != VERSION) {
                throw new IOException("Unrecognised vector store file format");
            }
            int n = in.readInt();
            for (int i = 0; i < n; i++) {
                String id = readString(in);
                String text = readString(in);
                String filename = readString(in);
                String fingerprint = readString(in);
                int startPage = in.readInt();
                int endPage = in.readInt();
                int sequence = in.readInt();
                int wordCount = in.readInt();
                String boundaries = readString(in);
                int docPages = in.readInt();
                float[] emb = new float[in.readInt()];
                for (int k = 0; k < emb.length; k++) {
                    emb[k] = in.readFloat();
                }
                records.put(id, new StoredChunk(id, text, filename, fingerprint, startPage, endPage,
                        sequence, wordCount, boundaries, docPages, emb));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load vector store " + file, e);
        }
    }

    private void persist() {
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(records.size());
            for (StoredChunk c : records.values()) {
                writeString(out, c.chunkId());
                writeString(out, c.text());
                writeString(out, c.filename());
                writeString(out, c.documentFingerprint());
                out.writeInt(c.startPage());
                out.writeInt(c.endPage());
                out.writeInt(c.sequence());
                out.writeInt(c.wordCount());
                writeString(out, c.sourceBoundaries());
                out.writeInt(c.documentPageCount());
                out.writeInt(c.embedding().length);
                for (float v : c.embedding()) {
                    out.writeFloat(v);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to write vector store", e);
        }
        try {
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to replace vector store file", e);
        }
    }

    private static void writeString(DataOutputStream out, String s) throws IOException {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        byte[] bytes = new byte[in.readInt()];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
