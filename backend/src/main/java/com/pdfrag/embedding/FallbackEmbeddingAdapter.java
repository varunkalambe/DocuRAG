package com.pdfrag.embedding;

import com.pdfrag.error.ApplicationException;
import com.pdfrag.obs.Logs;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Remote Hugging Face first, local model when the remote provider is unavailable. After a failure the
 * remote provider is skipped for a cooldown so each request does not pay the failed round-trip again.
 * One embedTexts() call is served by exactly one provider, so a document's vectors are never mixed.
 */
public class FallbackEmbeddingAdapter implements EmbeddingProvider {

    private static final Logger LOG = LoggerFactory.getLogger("pdf_rag.embeddings.factory");

    /** Remote failures for which the local model is a safe stand-in. */
    static final Set<String> FALLBACK_ERROR_CODES = Set.of(
            "EMBEDDING_CREDITS_EXHAUSTED",
            "EMBEDDING_RATE_LIMIT",
            "EMBEDDING_INVALID_CREDENTIALS",
            "EMBEDDING_MODEL_UNAVAILABLE",
            "EMBEDDING_PROVIDER_ERROR",
            "EMBEDDING_TIMEOUT",
            "EMBEDDING_RETRY_EXHAUSTED");

    private final EmbeddingProvider primary;
    private final EmbeddingProvider fallback;
    private final long cooldownNanos;
    private final int cooldownSeconds;
    private volatile long skipPrimaryUntil = 0L;
    private volatile ApplicationException lastPrimaryError;

    public FallbackEmbeddingAdapter(EmbeddingProvider primary, EmbeddingProvider fallback, int cooldownSeconds) {
        this.primary = primary;
        this.fallback = fallback;
        this.cooldownSeconds = cooldownSeconds;
        this.cooldownNanos = cooldownSeconds * 1_000_000_000L;
    }

    @Override
    public int batchSize() {
        return primary.batchSize();
    }

    @Override
    public List<float[]> embedTexts(List<String> texts) {
        if (System.nanoTime() >= skipPrimaryUntil) {
            try {
                return primary.embedTexts(texts);
            } catch (ApplicationException e) {
                if (!FALLBACK_ERROR_CODES.contains(e.getErrorCode())) {
                    throw e;
                }
                lastPrimaryError = e;
                skipPrimaryUntil = System.nanoTime() + cooldownNanos;
                Logs.warn(LOG, "embedding_primary_failed_using_local_fallback",
                        "error_code", e.getErrorCode(), "cooldown_seconds", cooldownSeconds);
            }
        }

        try {
            return fallback.embedTexts(texts);
        } catch (ApplicationException fallbackError) {
            if (fallbackError.getErrorCode().equals("EMBEDDING_LOCAL_UNAVAILABLE")) {
                // Fallback unusable: retry the primary next call and report the real cause.
                skipPrimaryUntil = 0L;
                if (lastPrimaryError != null) {
                    throw lastPrimaryError;
                }
            }
            throw fallbackError;
        }
    }
}
