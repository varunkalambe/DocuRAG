package com.pdfrag.query;

import java.util.ArrayList;
import java.util.List;

public record DocumentOverview(List<DocumentProfile> profiles, int totalDocuments, int estimatedTokens) {

    public record OverviewExcerpt(RetrievedChunk chunk, String text, int position, int totalChunks) {}

    public record DocumentProfile(
            String documentId, String filename, int pageCount, int chunkCount, List<OverviewExcerpt> excerpts) {}

    public List<RetrievedChunk> sources() {
        List<RetrievedChunk> result = new ArrayList<>();
        int index = 1;
        for (DocumentProfile profile : profiles) {
            for (OverviewExcerpt excerpt : profile.excerpts()) {
                result.add(excerpt.chunk().withRank(index++));
            }
        }
        return result;
    }
}
