package com.pdfrag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pdfrag.config.AppProperties;
import com.pdfrag.error.ApplicationException;
import com.pdfrag.obs.Logs;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Remote embeddings through Hugging Face Inference Providers (feature-extraction pipeline). */
public class HuggingFaceEmbeddingAdapter implements EmbeddingProvider {

    private static final Logger LOG = LoggerFactory.getLogger("pdf_rag.embeddings.huggingface");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String model;
    private final String provider;
    private final String token;
    private final int batchSize;
    private final int maxRetries;
    private final double retryBaseSeconds;
    private final Duration timeout;
    private final HttpClient client;
    private final String baseUrl;
    private Integer dimension;

    public HuggingFaceEmbeddingAdapter(AppProperties props) {
        this(props, "https://router.huggingface.co");
    }

    public HuggingFaceEmbeddingAdapter(AppProperties props, String routerBaseUrl) {
        this.model = props.hfEmbeddingModel();
        String p = props.hfProvider() == null ? "" : props.hfProvider().strip();
        this.provider = (p.isEmpty() || p.equalsIgnoreCase("auto")) ? "hf-inference" : p;
        this.token = props.huggingfaceApiToken();
        this.batchSize = props.hfBatchSize();
        this.maxRetries = props.hfMaxRetries();
        this.retryBaseSeconds = props.hfRetryBaseSeconds();
        this.timeout = Duration.ofMillis((long) (props.httpTimeoutSeconds() * 1000));
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
        this.baseUrl = routerBaseUrl;
    }

    @Override
    public int batchSize() {
        return batchSize;
    }

    @Override
    public List<float[]> embedTexts(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = EmbeddingValidation.cleanInputs(texts);

        List<float[]> vectors = new ArrayList<>();
        for (int start = 0; start < cleaned.size(); start += batchSize) {
            List<String> batch = cleaned.subList(start, Math.min(cleaned.size(), start + batchSize));
            vectors.addAll(embedBatchWithRetry(batch));
        }

        if (vectors.size() != cleaned.size()) {
            throw new ApplicationException(
                    "Embedding provider returned an unexpected number of vectors.", 502,
                    "EMBEDDING_COUNT_MISMATCH", Map.of("expected", cleaned.size(), "received", vectors.size()));
        }
        return vectors;
    }

    private List<float[]> embedBatchWithRetry(List<String> batch) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                ObjectNode body = MAPPER.createObjectNode();
                ArrayNode inputs = body.putArray("inputs");
                batch.forEach(inputs::add);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/" + provider + "/models/" + model
                                + "/pipeline/feature-extraction"))
                        .timeout(timeout)
                        .header("Authorization", "Bearer " + token)
                        .header("Content-Type", "application/json")
                        .header("User-Agent", "pdf-rag-backend/1.0")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    return validateResponse(MAPPER.readTree(response.body()), batch.size());
                }

                String detail = safeText(response.body());
                Logs.error(LOG, "hf_embedding_http_error", null, "attempt", attempt, "provider_status", status,
                        "model", model, "provider", provider, "exception_message", detail);

                if (status == 401 || status == 403) {
                    throw new ApplicationException(
                            "Hugging Face credentials were rejected. Check HUGGINGFACE_API_TOKEN and that it "
                                    + "has permission to call Inference Providers.",
                            502, "EMBEDDING_INVALID_CREDENTIALS", Map.of("provider_status", status));
                }
                if (status == 402) {
                    throw new ApplicationException(
                            "Hugging Face Inference credits are exhausted (402 Payment Required). Add credits "
                                    + "to the Hugging Face account, or set EMBEDDING_PROVIDER=local on the backend.",
                            502, "EMBEDDING_CREDITS_EXHAUSTED", Map.of("provider_status", status));
                }

                boolean retryable = status == 429 || status >= 500;
                if (retryable && attempt < maxRetries) {
                    sleepBackoff(attempt);
                    continue;
                }
                if (status == 429) {
                    throw new ApplicationException(
                            "Hugging Face embedding rate limit was reached.", 429, "EMBEDDING_RATE_LIMIT",
                            Map.of("provider_status", status));
                }
                if (status == 400 || status == 404 || status == 422) {
                    throw new ApplicationException(
                            "Hugging Face rejected the embedding request (model '" + model + "', provider '"
                                    + provider + "'): " + detail,
                            502, "EMBEDDING_MODEL_UNAVAILABLE", Map.of("provider_status", status));
                }
                throw new ApplicationException(
                        "Hugging Face embedding provider request failed: " + detail, 502,
                        "EMBEDDING_PROVIDER_ERROR", Map.of("provider_status", status));

            } catch (ApplicationException e) {
                throw e;
            } catch (HttpTimeoutException e) {
                Logs.error(LOG, "hf_embedding_timeout", null, "attempt", attempt,
                        "exception_message", safeText(e.getMessage()));
                if (attempt >= maxRetries) {
                    throw new ApplicationException(
                            "Hugging Face embedding request timed out.", 504, "EMBEDDING_TIMEOUT", null, e);
                }
                sleepBackoff(attempt);
            } catch (IOException e) {
                Logs.error(LOG, "hf_embedding_network_error", null, "attempt", attempt,
                        "exception_type", e.getClass().getSimpleName(),
                        "exception_message", safeText(e.getMessage()));
                if (attempt >= maxRetries) {
                    throw new ApplicationException(
                            "Hugging Face could not be reached.", 502, "EMBEDDING_PROVIDER_ERROR",
                            Map.of("exception_type", e.getClass().getSimpleName()), e);
                }
                sleepBackoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApplicationException(
                        "Hugging Face embedding was interrupted.", 502, "EMBEDDING_PROVIDER_ERROR", null, e);
            } catch (RuntimeException e) {
                String detail = safeText(e.getMessage());
                Logs.error(LOG, "hf_embedding_unexpected_exception", e, "attempt", attempt, "model", model);
                throw new ApplicationException(
                        "Hugging Face embedding failed (" + e.getClass().getSimpleName() + "): " + detail, 502,
                        "EMBEDDING_PROVIDER_ERROR", Map.of("exception_type", e.getClass().getSimpleName()), e);
            }
        }
        throw new ApplicationException(
                "Embedding provider failed after retries.", 502, "EMBEDDING_RETRY_EXHAUSTED");
    }

    private List<float[]> validateResponse(JsonNode root, int expectedCount) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            throw new ApplicationException(
                    "Embedding provider returned no data.", 502, "EMBEDDING_EMPTY_RESPONSE");
        }
        if (!root.isArray()) {
            throw new ApplicationException(
                    "Embedding provider returned an invalid response shape.", 502, "EMBEDDING_MALFORMED_RESPONSE");
        }

        JsonNode data = root;
        // A single flat vector for a single input.
        if (data.size() > 0 && data.get(0).isNumber()) {
            ArrayNode wrapper = MAPPER.createArrayNode();
            wrapper.add(data);
            data = wrapper;
        }

        if (data.size() != expectedCount) {
            throw new ApplicationException(
                    "Embedding provider returned an unexpected vector count.", 502, "EMBEDDING_COUNT_MISMATCH",
                    Map.of("expected", expectedCount, "received", data.size()));
        }

        List<float[]> validated = new ArrayList<>();
        for (int index = 0; index < data.size(); index++) {
            JsonNode vector = data.get(index);
            if (!vector.isArray() || vector.isEmpty()) {
                throw new ApplicationException(
                        "Embedding provider returned an invalid vector.", 502, "EMBEDDING_MALFORMED_RESPONSE",
                        Map.of("index", index));
            }
            float[] converted = new float[vector.size()];
            for (int k = 0; k < vector.size(); k++) {
                JsonNode value = vector.get(k);
                if (!value.isNumber()) {
                    throw new ApplicationException(
                            "Embedding vector contains a non-numeric value.", 502, "EMBEDDING_NON_NUMERIC_VALUE",
                            Map.of("index", index));
                }
                converted[k] = (float) value.asDouble();
            }
            if (!EmbeddingValidation.finite(converted)) {
                throw new ApplicationException(
                        "Embedding vector contains a non-finite value.", 502, "EMBEDDING_INVALID_VALUE",
                        Map.of("index", index));
            }
            if (dimension == null) {
                dimension = converted.length;
            } else if (converted.length != dimension) {
                throw new ApplicationException(
                        "Embedding dimensionality changed unexpectedly.", 502, "EMBEDDING_DIMENSION_MISMATCH",
                        Map.of("expected", dimension, "received", converted.length));
            }
            validated.add(converted);
        }
        return validated;
    }

    private void sleepBackoff(int attempt) {
        try {
            Thread.sleep((long) (retryBaseSeconds * Math.pow(2, attempt) * 1000));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplicationException(
                    "Hugging Face embedding was interrupted.", 502, "EMBEDDING_PROVIDER_ERROR", null, e);
        }
    }

    /** Provider error text with the API token scrubbed and length-capped. */
    private String safeText(String raw) {
        String text = raw == null || raw.isEmpty() ? "error" : raw;
        if (token != null && !token.isEmpty()) {
            text = text.replace(token, "***");
        }
        text = String.join(" ", text.trim().split("\\s+"));
        return text.substring(0, Math.min(300, text.length()));
    }
}
