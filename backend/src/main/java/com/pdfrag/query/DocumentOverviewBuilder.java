package com.pdfrag.query;

import com.pdfrag.error.ApplicationException;
import com.pdfrag.query.DocumentOverview.DocumentProfile;
import com.pdfrag.query.DocumentOverview.OverviewExcerpt;
import com.pdfrag.store.DocumentSummary;
import com.pdfrag.store.StoredChunk;
import com.pdfrag.store.VectorStore;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

/** Builds a representative whole-document view (profile + sampled excerpts) from the vector store. */
public class DocumentOverviewBuilder {

    private final VectorStore store;
    private final int maxChunksPerDocument;
    private final int excerptWords;
    private final int maxDocuments;
    private final int maxTokens;

    public DocumentOverviewBuilder(
            VectorStore store, int maxChunksPerDocument, int excerptWords, int maxDocuments, int maxTokens) {
        this.store = store;
        this.maxChunksPerDocument = maxChunksPerDocument;
        this.excerptWords = excerptWords;
        this.maxDocuments = maxDocuments;
        this.maxTokens = maxTokens;
    }

    public DocumentOverview build() {
        List<DocumentSummary> documents = store.listDocuments();
        if (documents.isEmpty()) {
            throw noDocuments();
        }

        List<DocumentSummary> selected = documents.subList(0, Math.min(maxDocuments, documents.size()));
        int perDocumentBudget = Math.max(400, maxTokens / selected.size());
        int chunkLimit = Math.max(3,
                Math.min(maxChunksPerDocument, maxChunksPerDocument * 2 / Math.max(1, selected.size())));

        List<DocumentProfile> profiles = new ArrayList<>();
        int totalTokens = 0;

        for (DocumentSummary document : selected) {
            List<StoredChunk> chunks = store.getDocumentChunks(document.documentId());
            if (chunks.isEmpty()) {
                continue;
            }
            DocumentProfile profile = buildProfile(document, chunks, chunkLimit, perDocumentBudget);
            profiles.add(profile);
            for (OverviewExcerpt e : profile.excerpts()) {
                totalTokens += estimateTokens(e.text());
            }
        }

        if (profiles.isEmpty()) {
            throw noDocuments();
        }
        return new DocumentOverview(List.copyOf(profiles), documents.size(), totalTokens);
    }

    private static ApplicationException noDocuments() {
        return new ApplicationException(
                "No indexed document is available for querying.", 409, "NO_DOCUMENTS_INDEXED");
    }

    private DocumentProfile buildProfile(
            DocumentSummary document, List<StoredChunk> chunks, int chunkLimit, int budget) {
        int total = chunks.size();
        List<Integer> positions = selectPositions(total, chunkLimit);

        List<OverviewExcerpt> excerpts = new ArrayList<>();
        for (int position : positions) {
            StoredChunk chunk = chunks.get(position);
            // The opening chunk usually holds the title/abstract/intro: larger excerpt.
            int limit = excerptWords * (position == 0 ? 2 : 1);
            RetrievedChunk retrieved = new RetrievedChunk(
                    chunk.chunkId(), chunk.text(), chunk.filename(), chunk.documentFingerprint(),
                    chunk.startPage(), chunk.endPage(), chunk.sequence(), chunk.wordCount(), 0.0, 0);
            excerpts.add(new OverviewExcerpt(retrieved, truncateWords(chunk.text(), limit), position + 1, total));
        }

        // Enforce the token budget by removing interior excerpts first.
        while (excerpts.size() > 2 && sumTokens(excerpts) > budget) {
            excerpts.remove(excerpts.size() / 2);
        }

        return new DocumentProfile(
                document.documentId(), document.filename(), document.pageCount(), document.chunkCount(),
                List.copyOf(excerpts));
    }

    private static int sumTokens(List<OverviewExcerpt> excerpts) {
        int sum = 0;
        for (OverviewExcerpt e : excerpts) {
            sum += estimateTokens(e.text());
        }
        return sum;
    }

    static int estimateTokens(String text) {
        return Math.max(1, (int) Math.ceil(text.length() / 4.0));
    }

    /** Evenly spaced positions that always include the first and last chunk. */
    static List<Integer> selectPositions(int total, int limit) {
        if (total <= limit) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < total; i++) {
                all.add(i);
            }
            return all;
        }
        if (limit == 1) {
            return new ArrayList<>(List.of(0));
        }

        TreeSet<Integer> set = new TreeSet<>();
        for (int index = 0; index < limit; index++) {
            // Python round() is banker's rounding; Math.rint matches it.
            set.add((int) Math.rint((double) index * (total - 1) / (limit - 1)));
        }
        set.add(0);
        set.add(total - 1);
        List<Integer> ordered = new ArrayList<>(set);

        while (ordered.size() > limit) {
            int drop = 1;
            int best = Integer.MAX_VALUE;
            for (int i = 1; i < ordered.size() - 1; i++) {
                int gap = ordered.get(i + 1) - ordered.get(i - 1);
                if (gap < best) {
                    best = gap;
                    drop = i;
                }
            }
            ordered.remove(drop);
        }
        return ordered;
    }

    static String truncateWords(String text, int limit) {
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return "";
        }
        String[] words = stripped.split("\\s+");
        if (words.length <= limit) {
            return String.join(" ", words);
        }
        return String.join(" ", java.util.Arrays.copyOf(words, limit)) + " \u2026";
    }

    public static String render(DocumentOverview overview) {
        List<String> parts = new ArrayList<>();

        List<String> profileLines = new ArrayList<>();
        profileLines.add("<document_profile>");
        profileLines.add("indexed_documents_total: " + overview.totalDocuments());
        if (overview.totalDocuments() > overview.profiles().size()) {
            profileLines.add("documents_shown_below: " + overview.profiles().size()
                    + " (the rest are omitted for length)");
        }
        int index = 1;
        for (DocumentProfile profile : overview.profiles()) {
            profileLines.add("document " + index + ": filename=" + profile.filename()
                    + " | total_pages=" + (profile.pageCount() != 0 ? String.valueOf(profile.pageCount()) : "unknown")
                    + " | indexed_sections=" + profile.chunkCount()
                    + " | excerpts_shown=" + profile.excerpts().size());
            index++;
        }
        profileLines.add("</document_profile>");
        parts.add(String.join("\n", profileLines));

        int docIndex = 1;
        for (DocumentProfile profile : overview.profiles()) {
            for (OverviewExcerpt excerpt : profile.excerpts()) {
                RetrievedChunk chunk = excerpt.chunk();
                parts.add(String.join("\n",
                        "[EXCERPT document " + docIndex + ": " + profile.filename() + "]",
                        "pages: " + chunk.startPage() + "-" + chunk.endPage(),
                        "position: section " + excerpt.position() + " of " + excerpt.totalChunks(),
                        "<document_chunk>",
                        excerpt.text(),
                        "</document_chunk>"));
            }
            docIndex++;
        }
        return String.join("\n\n", parts);
    }
}
