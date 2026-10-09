package com.pdfrag.chunking;

import com.pdfrag.document.Fingerprint;
import com.pdfrag.document.NormalizedPage;
import com.pdfrag.error.ApplicationException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/** Sentence/heading aware chunker with word overlap. Pure logic, no framework dependencies. */
public class SemanticChunker {

    private record TextUnit(String text, int pageNumber, String kind, List<String> words) {}

    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\s*\\d+(?:\\.\\d+)*[\\)\\].:\\-]?\\s+\\S+");
    private static final Pattern SENTENCE_BOUNDARY =
            Pattern.compile("(?<=[.!?])\\s+(?=(?:[\"\u201C\u201D'(\\[]?[A-Z0-9]))");
    private static final Pattern BLOCK_SPLIT = Pattern.compile("\\n\\s*\\n+");

    private final int targetWords;
    private final int overlapWords;

    public SemanticChunker(int targetWords, int overlapWords) {
        if (targetWords <= 0) {
            throw new IllegalArgumentException("target_words must be greater than zero.");
        }
        if (overlapWords < 0 || overlapWords >= targetWords) {
            throw new IllegalArgumentException("overlap_words must be >= 0 and smaller than target_words.");
        }
        this.targetWords = targetWords;
        this.overlapWords = overlapWords;
    }

    public List<Chunk> chunkPages(List<NormalizedPage> pages, String documentFingerprint, String filename) {
        if (documentFingerprint == null || documentFingerprint.isEmpty()) {
            throw new ApplicationException(
                    "Document fingerprint is required.", 500, "MISSING_DOCUMENT_FINGERPRINT");
        }
        if (filename == null || filename.isBlank()) {
            throw new ApplicationException("Filename is required.", 500, "MISSING_FILENAME");
        }

        List<TextUnit> units = new ArrayList<>();
        for (NormalizedPage page : pages) {
            if (!page.empty()) {
                units.addAll(buildPageUnits(page));
            }
        }

        if (units.isEmpty()) {
            throw new ApplicationException(
                    "No extractable text was available to create document chunks.",
                    422,
                    "NO_EXTRACTABLE_TEXT");
        }

        List<Chunk> chunks = new ArrayList<>();
        List<TextUnit> current = new ArrayList<>();
        int sequence = 1;

        for (TextUnit unit : units) {
            if (unit.kind().equals("section") && containsSourceContent(current)) {
                chunks.add(buildChunk(current, sequence, documentFingerprint, filename));
                sequence++;
                current = buildOverlapPrefix(current);
            }

            if (!current.isEmpty()
                    && containsSourceContent(current)
                    && countWords(current) + unit.words().size() > targetWords) {
                chunks.add(buildChunk(current, sequence, documentFingerprint, filename));
                sequence++;
                current = buildOverlapPrefix(current);
            }

            current.add(unit);

            if (countWords(current) >= targetWords) {
                chunks.add(buildChunk(current, sequence, documentFingerprint, filename));
                sequence++;
                current = buildOverlapPrefix(current);
            }
        }

        if (containsSourceContent(current)) {
            chunks.add(buildChunk(current, sequence, documentFingerprint, filename));
        }

        if (chunks.isEmpty()) {
            throw new ApplicationException("Chunking produced no usable chunks.", 422, "NO_CHUNKS_CREATED");
        }

        // True page count of the source PDF is stored on every chunk so document-level
        // questions ("how many pages?") are exact even when trailing pages have no text.
        int totalPages = pages.size();
        List<Chunk> result = new ArrayList<>(chunks.size());
        for (Chunk chunk : chunks) {
            result.add(chunk.withDocumentPageCount(totalPages));
        }
        return result;
    }

    private List<TextUnit> buildPageUnits(NormalizedPage page) {
        List<TextUnit> units = new ArrayList<>();

        List<String> blocks = new ArrayList<>();
        for (String block : BLOCK_SPLIT.split(page.text())) {
            if (!block.isBlank()) {
                blocks.add(block.strip());
            }
        }

        for (String originalBlock : blocks) {
            List<String> lines = new ArrayList<>();
            for (String line : originalBlock.split("\n")) {
                if (!line.isBlank()) {
                    lines.add(line.strip());
                }
            }
            if (lines.isEmpty()) {
                continue;
            }

            String block;
            if (lines.size() == 1 && isSectionHeading(lines.get(0))) {
                units.add(new TextUnit(lines.get(0), page.pageNumber(), "section", splitWords(lines.get(0))));
                continue;
            }

            if (lines.size() > 1 && isSectionHeading(lines.get(0))) {
                String heading = lines.get(0);
                units.add(new TextUnit(heading, page.pageNumber(), "section", splitWords(heading)));
                block = String.join(" ", lines.subList(1, lines.size()));
            } else {
                block = String.join(" ", lines);
            }

            for (String sentence : splitSentences(block)) {
                List<String> words = splitWords(sentence);
                if (words.isEmpty()) {
                    continue;
                }

                if (words.size() <= targetWords) {
                    units.add(new TextUnit(String.join(" ", words), page.pageNumber(), "sentence", words));
                    continue;
                }

                for (int start = 0; start < words.size(); start += targetWords) {
                    List<String> part = new ArrayList<>(
                            words.subList(start, Math.min(words.size(), start + targetWords)));
                    units.add(new TextUnit(String.join(" ", part), page.pageNumber(), "word", part));
                }
            }
        }

        return units;
    }

    static List<String> splitWords(String text) {
        String stripped = text.strip();
        if (stripped.isEmpty()) {
            return new ArrayList<>();
        }
        List<String> words = new ArrayList<>();
        Collections.addAll(words, stripped.split("\\s+"));
        return words;
    }

    private static List<String> splitSentences(String paragraph) {
        List<String> parts = new ArrayList<>();
        for (String part : SENTENCE_BOUNDARY.split(paragraph)) {
            if (!part.isBlank()) {
                parts.add(part.strip());
            }
        }
        if (parts.isEmpty()) {
            parts.add(paragraph.strip());
        }
        return parts;
    }

    static boolean isSectionHeading(String text) {
        String value = text.strip();
        if (value.isEmpty() || value.length() > 140) {
            return false;
        }
        if (value.endsWith(".") || value.endsWith("!") || value.endsWith("?")) {
            return false;
        }
        if (NUMBERED_HEADING.matcher(value).find()) {
            return true;
        }

        boolean hasLetter = value.chars().anyMatch(Character::isLetter);
        if (hasLetter && value.toUpperCase().equals(value)) {
            return true;
        }

        List<String> words = splitWords(value);
        if (words.size() > 12) {
            return false;
        }

        List<String> alphaWords = new ArrayList<>();
        for (String word : words) {
            if (word.chars().anyMatch(Character::isLetter)) {
                alphaWords.add(word);
            }
        }
        if (alphaWords.size() < 2) {
            return false;
        }

        long titleCase = alphaWords.stream()
                .filter(word -> Character.isUpperCase(word.codePointAt(0)))
                .count();
        return ((double) titleCase / alphaWords.size()) >= 0.75;
    }

    private List<TextUnit> buildOverlapPrefix(List<TextUnit> units) {
        if (overlapWords == 0) {
            return new ArrayList<>();
        }

        List<String> words = new ArrayList<>();
        int pageNumber = units.isEmpty() ? 1 : units.get(units.size() - 1).pageNumber();

        for (int i = units.size() - 1; i >= 0; i--) {
            if (words.size() >= overlapWords) {
                break;
            }

            int remaining = overlapWords - words.size();
            List<String> unitWords = units.get(i).words();
            List<String> selected = unitWords.subList(Math.max(0, unitWords.size() - remaining), unitWords.size());
            List<String> merged = new ArrayList<>(selected);
            merged.addAll(words);
            words = merged;
        }

        List<TextUnit> result = new ArrayList<>();
        if (!words.isEmpty()) {
            result.add(new TextUnit(String.join(" ", words), pageNumber, "overlap", words));
        }
        return result;
    }

    private static Chunk buildChunk(
            List<TextUnit> units, int sequence, String documentFingerprint, String filename) {
        boolean hasReal = units.stream().anyMatch(unit -> !unit.kind().equals("overlap"));
        if (!hasReal) {
            throw new ApplicationException(
                    "Internal chunking error: chunk contains no source content.", 500, "INVALID_CHUNK");
        }

        List<String> words = new ArrayList<>();
        int startPage = Integer.MAX_VALUE;
        int endPage = Integer.MIN_VALUE;
        List<String> boundaryTypes = new ArrayList<>();

        for (TextUnit unit : units) {
            words.addAll(unit.words());
            startPage = Math.min(startPage, unit.pageNumber());
            endPage = Math.max(endPage, unit.pageNumber());
            if (!boundaryTypes.contains(unit.kind())) {
                boundaryTypes.add(unit.kind());
            }
        }

        String text = String.join(" ", words).strip();
        if (text.isEmpty()) {
            throw new ApplicationException(
                    "Internal chunking error: empty chunk produced.", 500, "EMPTY_CHUNK");
        }

        String identity = documentFingerprint + "|" + sequence + "|" + startPage + "|" + endPage + "|" + text;
        String chunkHash = Fingerprint.sha256Hex(identity);
        String chunkId = "chunk_" + chunkHash.substring(0, 32);

        return new Chunk(
                chunkId, documentFingerprint, filename, text, startPage, endPage,
                sequence, words.size(), List.copyOf(boundaryTypes), 0);
    }

    private static boolean containsSourceContent(List<TextUnit> units) {
        for (TextUnit unit : units) {
            if (!unit.kind().equals("overlap")) {
                return true;
            }
        }
        return false;
    }

    private static int countWords(List<TextUnit> units) {
        int total = 0;
        for (TextUnit unit : units) {
            total += unit.words().size();
        }
        return total;
    }
}
