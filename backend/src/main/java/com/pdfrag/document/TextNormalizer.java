package com.pdfrag.document;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public final class TextNormalizer {

    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]");
    private static final Pattern MULTIPLE_SPACES = Pattern.compile("[ \\t]+");
    private static final Pattern EXCESSIVE_NEWLINES = Pattern.compile("\\n{3,}");
    private static final Pattern LINE_BREAK_HYPHEN = Pattern.compile("(?<=[a-z])-\\n(?=[a-z])");

    private TextNormalizer() {}

    public static String normalizeText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String normalized = text.replace("\r\n", "\n").replace("\r", "\n");
        normalized = CONTROL_CHARACTERS.matcher(normalized).replaceAll("");
        normalized = normalized.replace("\t", " ");
        normalized = LINE_BREAK_HYPHEN.matcher(normalized).replaceAll("");

        List<String> lines = new ArrayList<>();
        for (String line : normalized.split("\n", -1)) {
            lines.add(MULTIPLE_SPACES.matcher(line).replaceAll(" ").strip());
        }

        normalized = String.join("\n", lines);
        normalized = EXCESSIVE_NEWLINES.matcher(normalized).replaceAll("\n\n");
        return normalized.strip();
    }

    public static List<NormalizedPage> normalizePages(List<ExtractedPage> pages) {
        List<NormalizedPage> result = new ArrayList<>(pages.size());
        for (ExtractedPage page : pages) {
            String text = normalizeText(page.text());
            result.add(new NormalizedPage(page.pageNumber(), text, text.isBlank()));
        }
        return result;
    }
}
