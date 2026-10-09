package com.pdfrag.query;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Keeps candidates whose distance is within the threshold (lower distance = more similar). */
public class RelevanceFilter {

    private final double threshold;

    public RelevanceFilter(double threshold) {
        this.threshold = threshold;
    }

    public double threshold() {
        return threshold;
    }

    public List<RetrievedChunk> filter(List<RetrievedChunk> candidates) {
        List<RetrievedChunk> accepted = new ArrayList<>();
        for (RetrievedChunk c : candidates) {
            if (c.distance() <= threshold) {
                accepted.add(c);
            }
        }
        accepted.sort(Comparator.comparingDouble(RetrievedChunk::distance)
                .thenComparingInt(RetrievedChunk::sequence));
        return accepted;
    }
}
