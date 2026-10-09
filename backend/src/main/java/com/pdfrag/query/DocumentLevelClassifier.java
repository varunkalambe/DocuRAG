package com.pdfrag.query;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Fast, typo-tolerant, rule-based classifier for whole-document questions
 * ("what is this about?", "summarize", "how many pages?"). Direct port of the Python module.
 */
public final class DocumentLevelClassifier {

    private DocumentLevelClassifier() {}

    // ------------------------------------------------------------------
    // Question normalisation
    // ------------------------------------------------------------------

    private static final Map<String, String> TYPO_MAP = Map.ofEntries(
            Map.entry("waht", "what"), Map.entry("wat", "what"), Map.entry("wht", "what"),
            Map.entry("whta", "what"), Map.entry("whats", "what's"), Map.entry("wats", "what's"),
            Map.entry("whts", "what's"), Map.entry("thsi", "this"), Map.entry("ths", "this"),
            Map.entry("tihs", "this"), Map.entry("taht", "that"), Map.entry("abuot", "about"),
            Map.entry("abotu", "about"), Map.entry("abt", "about"), Map.entry("aboutt", "about"),
            Map.entry("hwo", "how"), Map.entry("hw", "how"), Map.entry("wht's", "what's"),
            Map.entry("doc", "document"), Map.entry("docs", "documents"), Map.entry("docu", "document"),
            Map.entry("documnet", "document"), Map.entry("documet", "document"),
            Map.entry("doucment", "document"), Map.entry("docuemnt", "document"),
            Map.entry("documnt", "document"), Map.entry("sumary", "summary"),
            Map.entry("summry", "summary"), Map.entry("sumarize", "summarize"),
            Map.entry("sumarise", "summarise"), Map.entry("summerize", "summarize"),
            Map.entry("summerise", "summarise"), Map.entry("summery", "summary"),
            Map.entry("tldr", "tl;dr"), Map.entry("pls", "please"), Map.entry("plz", "please"),
            Map.entry("u", "you"), Map.entry("ur", "your"));

    private static final List<String> FUZZY_VOCABULARY = List.of(
            "document", "documents", "summary", "summaries", "summarize",
            "summarise", "summarization", "overview", "explain", "describe",
            "contents", "structure", "outline", "purpose", "paper", "papers",
            "report", "reports", "article", "articles", "uploaded", "about",
            "pages", "author", "authors", "title", "topic", "topics", "theme",
            "takeaways", "highlights", "synopsis", "abstract", "chapters",
            "sections", "attached", "analyze", "analyse", "mention");
    private static final Set<String> FUZZY_SET = Set.copyOf(FUZZY_VOCABULARY);
    private static final Pattern LONG_WORD = Pattern.compile("[a-z]{6,}");
    private static final Pattern WORD = Pattern.compile("[a-z']+");
    private static final Pattern MARKDOWN_NOISE = Pattern.compile("[*_#>~|]+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static String fixLongWord(String word) {
        if (FUZZY_SET.contains(word)) {
            return word;
        }
        String close = closestMatch(word, FUZZY_VOCABULARY, 0.84);
        if (close == null) {
            return word;
        }
        // Leave plain inflections alone; only genuine misspellings are corrected.
        if (close.startsWith(word) || word.startsWith(close)) {
            return word;
        }
        return close;
    }

    public static String normalizeQuestion(String question) {
        String text = Normalizer.normalize(question == null ? "" : question, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT);
        text = text.replace("\u2019", "'").replace("`", "'").replace("\u2018", "'");
        text = MARKDOWN_NOISE.matcher(text).replaceAll(" ");
        text = WHITESPACE.matcher(text).replaceAll(" ").strip();

        Matcher wordMatcher = WORD.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (wordMatcher.find()) {
            String word = wordMatcher.group();
            wordMatcher.appendReplacement(sb, Matcher.quoteReplacement(TYPO_MAP.getOrDefault(word, word)));
        }
        wordMatcher.appendTail(sb);
        text = sb.toString();

        Matcher longMatcher = LONG_WORD.matcher(text);
        sb = new StringBuilder();
        while (longMatcher.find()) {
            longMatcher.appendReplacement(sb, Matcher.quoteReplacement(fixLongWord(longMatcher.group())));
        }
        longMatcher.appendTail(sb);
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // difflib.get_close_matches(word, possibilities, n=1, cutoff) equivalent
    // ------------------------------------------------------------------

    /** Ratcliff/Obershelp ratio identical to difflib.SequenceMatcher(None, a, b).ratio() (no junk). */
    static double ratio(String a, String b) {
        int total = a.length() + b.length();
        if (total == 0) {
            return 1.0;
        }
        return 2.0 * matchingChars(a, 0, a.length(), b, 0, b.length()) / total;
    }

    private static int matchingChars(String a, int alo, int ahi, String b, int blo, int bhi) {
        if (alo >= ahi || blo >= bhi) {
            return 0;
        }
        int width = bhi - blo;
        int bestI = alo;
        int bestJ = blo;
        int bestSize = 0;
        int[] prev = new int[width + 1];
        for (int i = alo; i < ahi; i++) {
            int[] cur = new int[width + 1];
            for (int j = 0; j < width; j++) {
                if (a.charAt(i) == b.charAt(blo + j)) {
                    int k = prev[j] + 1;
                    cur[j + 1] = k;
                    if (k > bestSize) {
                        bestI = i - k + 1;
                        bestJ = blo + j - k + 1;
                        bestSize = k;
                    }
                }
            }
            prev = cur;
        }
        if (bestSize == 0) {
            return 0;
        }
        return bestSize
                + matchingChars(a, alo, bestI, b, blo, bestJ)
                + matchingChars(a, bestI + bestSize, ahi, b, bestJ + bestSize, bhi);
    }

    private static String closestMatch(String word, List<String> possibilities, double cutoff) {
        String best = null;
        double bestScore = -1;
        for (String candidate : possibilities) {
            double score = ratio(candidate, word);
            if (score < cutoff) {
                continue;
            }
            // heapq.nlargest on (score, candidate): higher score wins, ties -> larger string.
            if (score > bestScore || (score == bestScore && candidate.compareTo(best) > 0)) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Rule-based classifier
    // ------------------------------------------------------------------

    private static final String DOC =
            "(?:documents?|files?|pdfs?|papers?|reports?|articles?|texts?|uploads?|"
                    + "materials?|books?|resumes?|cvs?|thesis|theses|manuals?|contracts?|"
                    + "studies|study|policy|policies|presentations?|decks?|essays?|notes|"
                    + "handouts?|brochures?|pages?|docs?)";
    private static final String DOC_ADJ =
            "(?:uploaded|attached|whole|entire|full|complete|given|provided|"
                    + "current|indexed|pdf)";
    private static final String REF_BODY =
            "(?:(?:this|that|the|my|our|these|those|your)\\s+)?(?:" + DOC_ADJ + "\\s+)*" + DOC;
    private static final String PRON = "(?:" + REF_BODY + "|this|it|that)";
    private static final String END = "\\s*[?!.]*\\s*$";

    private static final Pattern SUMMARY_WORD = Pattern.compile(
            "\\b(?:summar(?:y|ies|ize|ise|ization|isation|ized|ised)|synopsis|"
                    + "tl;?dr|recap|gist|rundown|run\\s?down|abstract|overview|digest)\\b");

    private static final Pattern BARE = Pattern.compile(
            "^(?:please\\s+)?(?:summary|summaries|summarize|summarise|overview|"
                    + "synopsis|tl;?dr|gist|recap|abstract|outline|about|title|author|authors|"
                    + "toc|contents|table of contents|structure|topic|topics|pages|length|"
                    + "key points|main points|highlights|takeaways|key takeaways)"
                    + "\\s*[?!.]*$");

    private static final String ABOUT_TAIL =
            "\\babout(?:\\s+(?:all|exactly|really|actually|overall|in\\s+(?:short|brief|"
                    + "general|a\\s+nutshell|simple\\s+terms|a\\s+(?:line|sentence|word|nutshell)|"
                    + "one\\s+(?:line|sentence)|\\d+\\s+(?:lines?|sentences?|words?|points?|"
                    + "bullets?)))|\\s*,?\\s*(?:briefly|in\\s+short))?\\s*[?!.]*\\s*$";

    private static final Pattern FOCUS = Pattern.compile(
            "\\b(?:of|for|in|on|about|regarding|concerning|related\\s+to|from|during)\\s+"
                    + "(?!(?:" + REF_BODY + "|this|it|that|everything|all|these|those|my|our|"
                    + "the\\s+(?:whole|entire|full|complete|overall)|\\d+|one|two|three|four|"
                    + "five|six|a\\s+(?:few|couple|short|brief|single|nutshell|sentence|"
                    + "paragraph|line|word|bullet)|few|short|brief|simple|plain|bullets?|"
                    + "points?|lines?|sentences?|words?|paragraphs?|detail|depth|english|"
                    + "hindi|marathi|tamil|telugu|bengali|gujarati|urdu|spanish|french|"
                    + "german|arabic|chinese|japanese|portuguese|russian|italian|"
                    + "simple\\s+terms)\\b)\\w");

    private static final Pattern NUMBERED_SCOPE = Pattern.compile(
            "\\b(?:sections?|chapters?|parts?|appendix|appendices|clauses?|articles?|"
                    + "slides?|figures?|tables?)\\s*(?:\\d+(?:\\.\\d+)*|[ivxlc]+)\\b|\\bpages?\\s*\\d+\\b");

    private static final Pattern SCOPE_NOUN = Pattern.compile(
            "\\b(?:section|chapter|paragraph|clause|appendix|table|figure|slide|"
                    + "introduction|conclusion|methodology|methods|results|discussion|"
                    + "abstract|foreword|preface|footnote)s?\\b");

    private static final Pattern STRUCTURE_WORD = Pattern.compile(
            "\\b(?:outline|structure|structured|organi[sz]ation|organi[sz]ed|layout|"
                    + "table\\s+of\\s+contents|toc|headings?|chapters?|sections?|sub-?sections?|"
                    + "topics\\s+covered|agenda)\\b");

    private static final Pattern PRON_PATTERN = Pattern.compile("\\b" + PRON + "\\b");
    private static final Pattern STRUCTURE_START =
            Pattern.compile("^(?:what|which|list|show|give|how many|tell)\\b");
    private static final Pattern IN_OF_THIS = Pattern.compile("\\b(?:in|of)\\s+(?:this|the|it)\\b");

    private static boolean isScoped(String text) {
        return FOCUS.matcher(text).find()
                || NUMBERED_SCOPE.matcher(text).find()
                || SCOPE_NOUN.matcher(text).find();
    }

    private static final String POLITE =
            "^(?:(?:please|pls|can you|could you|would you|kindly|just|now|hey|hi|ok|okay)\\s+)*";

    // Patterns that are document-level on their own (unless scoped).
    private static final List<Pattern> SCOPE_SENSITIVE = List.of(
            Pattern.compile(
                    "\\b(?:main|key|central|core|primary|major|important|principal|overall|"
                            + "big|general|top)\\s+(?:points?|ideas?|topics?|themes?|takeaways?|"
                            + "messages?|purposes?|objectives?|goals?|aims?|focus|subjects?|"
                            + "arguments?|findings?|highlights?|contents?|claims?|conclusions?|"
                            + "insights?|lessons?|concepts?|contributions?|outcomes?)\\b"),
            Pattern.compile("\\bhighlights\\b"),
            Pattern.compile("\\btakeaways?\\b"),
            Pattern.compile(
                    POLITE + "(?:explain|describe|analy[sz]e|review|read|examine|"
                            + "break\\s+down|walk\\s+me\\s+through|go\\s+through|go\\s+over|brief\\s+me\\s+on|"
                            + "help\\s+me\\s+understand|digest|decode)\\b.{0,30}?\\b" + PRON + "\\b"),
            Pattern.compile(
                    POLITE + "give\\s+me\\s+(?:an?\\s+|the\\s+)?"
                            + "(?:quick\\s+|short\\s+|brief\\s+|simple\\s+|detailed\\s+|high[- ]level\\s+|"
                            + "general\\s+|basic\\s+|rough\\s+)*(?:idea|picture|sense|gist|rundown|"
                            + "understanding|introduction|intro|description|explanation|brief|info|"
                            + "information|details)\\b"));

    // Patterns that are always document-level (identity / metadata / listing).
    private static final List<Pattern> ALWAYS = buildAlways();

    private static List<Pattern> buildAlways() {
        List<String> p = new ArrayList<>();
        // what is this document / what's this / what is it
        p.add("^(?:so\\s+|ok\\s+|okay\\s+)?(?:what|which)(?:\\s+(?:is|are|was)|'s)\\s+"
                + "(?:this|that|the|it)(?:\\s+(?:uploaded|attached|given|provided|indexed))*"
                + "(?:\\s+" + DOC + ")?" + END);
        // what is this document about
        p.add("\\b(?:what|which|who|tell|explain|describe|say|state|is|are|does|do)\\b"
                + ".*\\b" + PRON + "\\b.*" + ABOUT_TAIL);
        // tell me about this document
        p.add("\\b(?:tell|talk|explain|say|speak|describe|brief|inform|teach)\\s+"
                + "(?:me\\s+)?(?:something\\s+|more\\s+|everything\\s+|a\\s+(?:little|bit)\\s+)?"
                + "(?:about|of)\\s+" + PRON + "(?:\\s+in\\s+\\w+(?:\\s+\\w+){0,3})?" + END);
        // what does this document contain / cover / say (no object)
        p.add("\\b(?:what|which)\\b.{0,40}?\\b" + PRON + "\\s+(?:contains?|covers?|includes?|"
                + "discuss(?:es)?|talks?|describes?|says?|deals?|focus(?:es)?|presents?|"
                + "explains?|mentions?|is\\s+(?:all\\s+)?about|is\\s+(?:for|regarding|"
                + "concerned|related))(?:\\s+(?:with|on|about))?" + END);
        // what's in this document
        p.add("\\bwhat(?:'s|\\s+is|\\s+are)\\s+(?:\\w+\\s+){0,2}?(?:in|inside|within)\\s+"
                + PRON + END);
        // what kind/type of document
        p.add("\\b(?:what|which)\\s+(?:kind|type|sort|category|genre|format)\\s+of\\s+"
                + "(?:" + DOC + "|content|text|material)\\b");
        // is this a resume / is this about X
        p.add("\\bis\\s+(?:this|it|that|" + REF_BODY + ")\\s+(?:an?\\s+)\\w+(?:\\s+\\w+){0,2}" + END);
        p.add("\\bis\\s+(?:this|it|that|" + REF_BODY + ")\\s+(?:about|related\\s+to|regarding|"
                + "concerning)\\b");
        // topic / purpose / theme of the document
        p.add("\\b(?:purpose|goal|aim|objective|intent|scope|context|background|"
                + "subject|topic|theme|gist|idea|essence|message|focus|summary|overview|"
                + "outline|structure|contents?)\\s+(?:of|behind|for)\\s+" + PRON + "\\b");
        p.add("\\bwhat(?:'s|\\s+is|\\s+are)\\s+(?:the\\s+)?(?:main\\s+|primary\\s+|general\\s+|"
                + "overall\\s+|core\\s+|central\\s+)?(?:topic|subject|theme|purpose|gist|idea|"
                + "focus|scope|context|background|contents?|message)s?" + END);
        p.add("\\bwhy\\s+(?:was|is)\\s+" + PRON + "\\s+(?:written|made|created|prepared|"
                + "published|needed|important)\\b");
        // audience
        p.add("\\b(?:target|intended|primary)\\s+(?:audience|readers?|users?)\\b");
        p.add("\\bwho\\s+(?:is|are)\\s+" + PRON + "\\s+(?:for|aimed|intended|meant|written\\s+for)\\b");
        p.add("\\bwho\\s+should\\s+read\\s+" + PRON + "\\b");
        // what should I know / learn from this document
        p.add("\\bwhat\\s+(?:do|should|can|could|would)\\s+(?:i|you|we)\\s+(?:really\\s+|"
                + "actually\\s+)?(?:need\\s+to\\s+|have\\s+to\\s+|want\\s+to\\s+)?(?:know|learn|"
                + "understand|get|take|gather|infer)\\b.{0,30}?\\b" + PRON + "\\b");
        // title / name
        p.add("\\b(?:title|name|heading|headline)\\s+(?:of|for)\\s+" + PRON + "\\b");
        p.add("\\bwhat(?:'s|\\s+is)\\s+(?:the\\s+)?(?:title|name)" + END);
        p.add("\\b(?:called|titled|named)\\b.{0,20}\\b" + PRON + "\\b|\\b" + PRON + "\\b.{0,30}"
                + "\\b(?:called|titled|named)\\b");
        p.add("\\bfile\\s*name\\b");
        // author / date
        p.add("\\bwho\\b.{0,20}\\b(?:wrote|written|authored|created|prepared|"
                + "published|compiled|produced|made|issued)\\b.{0,30}\\b" + PRON + "\\b");
        p.add("\\bwho\\s+(?:is|are|was|were)\\s+(?:the\\s+)?(?:authors?|writers?|"
                + "creators?|publishers?)\\b");
        p.add("\\b(?:authors?|writers?)\\s+(?:of|for)\\s+" + PRON + "\\b");
        p.add("\\b(?:when|what\\s+(?:date|year)|which\\s+(?:date|year))\\b.{0,40}?"
                + "\\b(?:written|published|created|made|issued|dated|prepared|released|"
                + "authored|submitted)\\b");
        // size / length
        p.add("\\bhow\\s+many\\s+(?:pages?|words?|chunks?|sections?|chapters?)\\b");
        p.add("\\bhow\\s+(?:long|big|large|lengthy|extensive)\\s+(?:is|was)\\s+" + PRON + "\\b");
        p.add("\\b(?:page\\s+count|number\\s+of\\s+pages|word\\s+count|total\\s+pages)\\b");
        // several documents
        p.add("\\b(?:which|what|list|show|how\\s+many)\\b.{0,20}\\b(?:documents?|files?|"
                + "pdfs?)\\b.{0,30}\\b(?:uploaded|indexed|available|have|loaded|stored|"
                + "there|do\\s+i|in\\s+memory)\\b");
        p.add("\\bcompare\\b.{0,30}\\b(?:documents|files|pdfs|papers|reports)\\b");
        p.add("\\bdifferences?\\s+between\\s+(?:the|these|both|my)\\s+(?:documents|files|"
                + "pdfs|papers|reports|two)\\b");

        List<Pattern> compiled = new ArrayList<>(p.size());
        for (String pattern : p) {
            compiled.add(Pattern.compile(pattern));
        }
        return List.copyOf(compiled);
    }

    /** Decide whether a question concerns the document as a whole. */
    public static boolean isDocumentLevelQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }

        String text = normalizeQuestion(question);

        // Very long questions carry specific context and are content questions.
        if (text.isEmpty() || text.length() > 400) {
            return false;
        }

        if (BARE.matcher(text).find()) {
            return true;
        }

        for (Pattern pattern : ALWAYS) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }

        // Summary words: document-level unless narrowed to a topic/section/page.
        if (SUMMARY_WORD.matcher(text).find() && !isScoped(text)) {
            return true;
        }

        // Structure words need an explicit document reference or list-style phrasing,
        // and must not point at a numbered section/page.
        if (STRUCTURE_WORD.matcher(text).find()
                && !NUMBERED_SCOPE.matcher(text).find()
                && (PRON_PATTERN.matcher(text).find() || STRUCTURE_START.matcher(text).find())
                && !FOCUS.matcher(IN_OF_THIS.matcher(text).replaceAll("")).find()) {
            return true;
        }

        for (Pattern pattern : SCOPE_SENSITIVE) {
            Matcher match = pattern.matcher(text);
            if (match.find() && !isScoped(text.substring(match.end()))) {
                return true;
            }
        }

        return false;
    }
}
