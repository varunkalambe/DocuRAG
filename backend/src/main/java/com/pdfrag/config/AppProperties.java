package com.pdfrag.config;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All runtime settings. Values come from environment variables / .env via application.yml. */
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        String name,
        String env,
        String apiPrefix,
        String embeddingProvider,
        boolean embeddingFallbackToLocal,
        int embeddingPrimaryCooldownSeconds,
        int localEmbeddingBatchSize,
        String huggingfaceApiToken,
        String hfEmbeddingModel,
        String hfProvider,
        int hfBatchSize,
        int hfMaxRetries,
        double hfRetryBaseSeconds,
        String groqApiKey,
        String groqModel,
        String groqBaseUrl,
        int groqMaxCompletionTokens,
        double groqTemperature,
        int groqMaxRetries,
        String chromaDistanceMetric,
        String chromaPersistDir,
        List<String> corsAllowedOrigins,
        String corsAllowOriginRegex,
        long maxUploadSizeBytes,
        int chunkSize,
        int chunkOverlap,
        int maxQuestionLength,
        int topK,
        double relevanceThreshold,
        int maxContextTokens,
        int overviewMaxChunksPerDocument,
        int overviewExcerptWords,
        int overviewMaxDocuments,
        boolean enableOverviewFallback,
        double httpTimeoutSeconds) {

    /** Origins with trailing slashes and blanks removed. */
    public List<String> allowedOrigins() {
        List<String> origins = new ArrayList<>();
        if (corsAllowedOrigins != null) {
            for (String origin : corsAllowedOrigins) {
                String cleaned = origin == null ? "" : origin.strip();
                while (cleaned.endsWith("/")) {
                    cleaned = cleaned.substring(0, cleaned.length() - 1);
                }
                if (!cleaned.isEmpty()) {
                    origins.add(cleaned);
                }
            }
        }
        return origins;
    }

    public void validate() {
        String provider = embeddingProvider == null ? "" : embeddingProvider.strip().toLowerCase();
        if (!provider.equals("huggingface") && !provider.equals("local") && !provider.equals("gemini")) {
            throw new IllegalStateException("EMBEDDING_PROVIDER must be 'huggingface', 'local' or 'gemini'.");
        }

        requireText("GROQ_API_KEY", groqApiKey);
        requireText("GROQ_MODEL", groqModel);
        if (provider.equals("huggingface")) {
            requireText("HUGGINGFACE_API_TOKEN", huggingfaceApiToken);
            requireText("HF_EMBEDDING_MODEL", hfEmbeddingModel);
        }

        requireText("CHROMA_PERSIST_DIR", chromaPersistDir);

        List<String> origins = allowedOrigins();
        if (origins.isEmpty()) {
            throw new IllegalStateException("CORS_ALLOWED_ORIGINS must contain at least one origin.");
        }
        if (origins.contains("*")) {
            throw new IllegalStateException("CORS_ALLOWED_ORIGINS cannot contain '*'.");
        }
        if (corsAllowOriginRegex != null && !corsAllowOriginRegex.isBlank()) {
            try {
                Pattern.compile(corsAllowOriginRegex);
            } catch (PatternSyntaxException e) {
                throw new IllegalStateException("CORS_ALLOW_ORIGIN_REGEX is not a valid regex: " + e.getMessage());
            }
        }

        if (!List.of("cosine", "l2", "ip").contains(chromaDistanceMetric)) {
            throw new IllegalStateException("CHROMA_DISTANCE_METRIC must be cosine, l2, or ip.");
        }
        if (chunkSize <= 0) {
            throw new IllegalStateException("CHUNK_SIZE must be greater than zero.");
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw new IllegalStateException("CHUNK_OVERLAP must be >= 0 and smaller than CHUNK_SIZE.");
        }
        if (topK <= 0) {
            throw new IllegalStateException("TOP_K must be greater than zero.");
        }
        if (!(relevanceThreshold > 0 && relevanceThreshold <= 2)) {
            throw new IllegalStateException("RELEVANCE_THRESHOLD must be > 0 and <= 2 for cosine distance.");
        }
        if (groqTemperature < 0 || groqTemperature > 2) {
            throw new IllegalStateException("GROQ_TEMPERATURE must be between 0 and 2.");
        }
        if (maxUploadSizeBytes <= 0 || maxQuestionLength <= 0 || maxContextTokens <= 0) {
            throw new IllegalStateException(
                    "MAX_UPLOAD_SIZE_BYTES, MAX_QUESTION_LENGTH and MAX_CONTEXT_TOKENS must be greater than zero.");
        }
        if (groqMaxRetries < 0 || hfMaxRetries <= 0 || hfBatchSize <= 0 || localEmbeddingBatchSize <= 0
                || groqMaxCompletionTokens <= 0 || httpTimeoutSeconds <= 0) {
            throw new IllegalStateException("Retry, batch size, token and timeout settings must be valid numbers.");
        }
    }

    private static void requireText(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Missing mandatory configuration value: " + name);
        }
    }
}
