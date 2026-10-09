package com.pdfrag.embedding;

import com.pdfrag.error.ApplicationException;
import com.pdfrag.obs.Logs;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * In-process embeddings (ONNX Runtime, sentence-transformers/all-MiniLM-L6-v2, 384 dimensions).
 * No API key, no network call at request time, no credits. The model ships inside the application
 * jar and is loaded lazily on first use (or at startup warm-up).
 */
public class LocalEmbeddingAdapter implements EmbeddingProvider {

    private static final Logger LOG = LoggerFactory.getLogger("pdf_rag.embeddings.local");
    private static final String MODEL_NAME = "sentence-transformers/all-MiniLM-L6-v2";

    private final int batchSize;
    private final Object lock = new Object();
    private volatile EmbeddingModel model;
    private Integer dimension;

    public LocalEmbeddingAdapter(int batchSize) {
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public int batchSize() {
        return batchSize;
    }

    /** Loads the model now so the first user request does not pay the start-up cost. */
    public void warmUp() {
        loadModel();
    }

    @Override
    public List<float[]> embedTexts(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = EmbeddingValidation.cleanInputs(texts);

        List<float[]> vectors;
        try {
            vectors = embedSync(cleaned);
        } catch (ApplicationException e) {
            throw e;
        } catch (LinkageError e) {
            throw new ApplicationException(
                    "Local embeddings are unavailable: the embedding runtime could not be loaded ("
                            + e.getClass().getSimpleName() + ").",
                    502, "EMBEDDING_LOCAL_UNAVAILABLE", null, e);
        } catch (Exception e) {
            String msg = String.valueOf(e.getMessage());
            msg = msg.substring(0, Math.min(200, msg.length()));
            Logs.error(LOG, "local_embedding_failed", e, "model", MODEL_NAME,
                    "exception_type", e.getClass().getSimpleName());
            throw new ApplicationException(
                    "Local embedding failed (" + e.getClass().getSimpleName() + "): " + msg,
                    502, "EMBEDDING_LOCAL_FAILED",
                    Map.of("exception_type", e.getClass().getSimpleName()), e);
        }
        return validate(vectors, cleaned.size());
    }

    private EmbeddingModel loadModel() {
        EmbeddingModel local = model;
        if (local != null) {
            return local;
        }
        synchronized (lock) {
            if (model == null) {
                Logs.info(LOG, "local_embedding_model_loading", "model", MODEL_NAME);
                model = new AllMiniLmL6V2EmbeddingModel();
                Logs.info(LOG, "local_embedding_model_loaded", "model", MODEL_NAME);
            }
            return model;
        }
    }

    private List<float[]> embedSync(List<String> texts) {
        // One inference at a time keeps peak memory predictable.
        synchronized (lock) {
            EmbeddingModel engine = loadModel();
            List<float[]> out = new ArrayList<>(texts.size());
            for (int start = 0; start < texts.size(); start += batchSize) {
                List<TextSegment> segments = new ArrayList<>();
                for (String t : texts.subList(start, Math.min(texts.size(), start + batchSize))) {
                    segments.add(TextSegment.from(t));
                }
                for (Embedding e : engine.embedAll(segments).content()) {
                    out.add(e.vector());
                }
            }
            return out;
        }
    }

    private List<float[]> validate(List<float[]> vectors, int expected) {
        if (vectors.size() != expected) {
            throw new ApplicationException(
                    "Local embedding returned an unexpected vector count.", 502, "EMBEDDING_COUNT_MISMATCH",
                    Map.of("expected", expected, "received", vectors.size()));
        }
        for (int index = 0; index < vectors.size(); index++) {
            float[] vector = vectors.get(index);
            if (vector == null || vector.length == 0 || !EmbeddingValidation.finite(vector)) {
                throw new ApplicationException(
                        "Local embedding produced an invalid vector.", 502, "EMBEDDING_INVALID_VALUE",
                        Map.of("index", index));
            }
            if (dimension == null) {
                dimension = vector.length;
            } else if (vector.length != dimension) {
                throw new ApplicationException(
                        "Embedding dimensionality changed unexpectedly.", 502, "EMBEDDING_DIMENSION_MISMATCH",
                        Map.of("expected", dimension, "received", vector.length));
            }
        }
        return vectors;
    }
}
