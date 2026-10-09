package com.pdfrag.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

public class GeminiEmbeddingAdapter implements EmbeddingProvider {

    private static final Logger LOG = LoggerFactory.getLogger("pdf_rag.embeddings.gemini");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final int DIMENSION = 384;
    private static final int BATCH_SIZE = 20;
    private static final int MAX_RETRIES = 5;

    private final String apiKey;
    private final String model;
    private final HttpClient client;
    private final Duration timeout = Duration.ofSeconds(60);

    public GeminiEmbeddingAdapter() {
        this.apiKey = System.getenv("GEMINI_API_KEY");
        String m = System.getenv("GEMINI_EMBEDDING_MODEL");
        this.model = (m == null || m.isBlank()) ? "gemini-embedding-001" : m.strip();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY is required when EMBEDDING_PROVIDER=gemini.");
        }
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public int batchSize() {
        return BATCH_SIZE;
    }

    @Override
    public float[] embedOne(String text) {
        return embed(List.of(text), "RETRIEVAL_QUERY").get(0);
    }

    @Override
    public List<float[]> embedTexts(List<String> texts) {
        return embed(texts, "RETRIEVAL_DOCUMENT");
    }

    private List<float[]> embed(List<String> texts, String taskType) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = EmbeddingValidation.cleanInputs(texts);
        List<float[]> out = new ArrayList<>();
        for (int start = 0; start < cleaned.size(); start += BATCH_SIZE) {
            out.addAll(embedBatch(cleaned.subList(start, Math.min(cleaned.size(), start + BATCH_SIZE)), taskType));
        }
        if (out.size() != cleaned.size()) {
            throw new ApplicationException("Embedding provider returned an unexpected number of vectors.", 502,
                    "EMBEDDING_COUNT_MISMATCH", Map.of("expected", cleaned.size(), "received", out.size()));
        }
        return out;
    }

    private List<float[]> embedBatch(List<String> batch, String taskType) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                ObjectNode body = MAPPER.createObjectNode();
                ArrayNode requests = body.putArray("requests");
                for (String text : batch) {
                    ObjectNode r = requests.addObject();
                    r.put("model", "models/" + model);
                    r.putObject("content").putArray("parts").addObject().put("text", text);
                    r.put("taskType", taskType);
                    r.put("outputDimensionality", DIMENSION);
                }

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(BASE + model + ":batchEmbedContents"))
                        .timeout(timeout)
                        .header("x-goog-api-key", apiKey)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    return parse(MAPPER.readTree(response.body()), batch.size());
                }

                Logs.error(LOG, "gemini_embedding_http_error", null, "attempt", attempt,
                        "provider_status", status, "exception_message", safe(response.body()));

                if (status == 400 && response.body() != null && response.body().contains("API key")) {
                    throw new ApplicationException("Gemini API key was rejected. Check GEMINI_API_KEY.", 502,
                            "EMBEDDING_INVALID_CREDENTIALS", Map.of("provider_status", status));
                }
                if (status == 401 || status == 403) {
                    throw new ApplicationException("Gemini credentials were rejected. Check GEMINI_API_KEY.", 502,
                            "EMBEDDING_INVALID_CREDENTIALS", Map.of("provider_status", status));
                }
                if ((status == 429 || status >= 500) && attempt < MAX_RETRIES) {
                    sleep(Math.min(30_000L, 2_000L * (1L << attempt)));
                    continue;
                }
                if (status == 429) {
                    throw new ApplicationException("Gemini embedding rate limit was reached. Try again shortly.",
                            429, "EMBEDDING_RATE_LIMIT", Map.of("provider_status", status));
                }
                throw new ApplicationException("Gemini embedding request failed: " + safe(response.body()), 502,
                        "EMBEDDING_PROVIDER_ERROR", Map.of("provider_status", status));

            } catch (ApplicationException e) {
                throw e;
            } catch (HttpTimeoutException e) {
                if (attempt >= MAX_RETRIES) {
                    throw new ApplicationException("Gemini embedding request timed out.", 504,
                            "EMBEDDING_TIMEOUT", null, e);
                }
                sleep(2_000L * (1L << attempt));
            } catch (IOException e) {
                if (attempt >= MAX_RETRIES) {
                    throw new ApplicationException("Gemini could not be reached.", 502,
                            "EMBEDDING_PROVIDER_ERROR", Map.of("exception_type", e.getClass().getSimpleName()), e);
                }
                sleep(2_000L * (1L << attempt));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApplicationException("Gemini embedding was interrupted.", 502,
                        "EMBEDDING_PROVIDER_ERROR", null, e);
            }
        }
        throw new ApplicationException("Embedding provider failed after retries.", 502, "EMBEDDING_RETRY_EXHAUSTED");
    }

    private List<float[]> parse(JsonNode root, int expected) {
        JsonNode embeddings = root == null ? null : root.get("embeddings");
        if (embeddings == null || !embeddings.isArray() || embeddings.size() != expected) {
            throw new ApplicationException("Embedding provider returned an unexpected response.", 502,
                    "EMBEDDING_MALFORMED_RESPONSE");
        }
        List<float[]> vectors = new ArrayList<>(expected);
        for (JsonNode e : embeddings) {
            JsonNode values = e.get("values");
            if (values == null || !values.isArray() || values.size() != DIMENSION) {
                throw new ApplicationException("Embedding provider returned an invalid vector.", 502,
                        "EMBEDDING_MALFORMED_RESPONSE");
            }
            float[] v = new float[DIMENSION];
            double norm = 0;
            for (int i = 0; i < DIMENSION; i++) {
                v[i] = (float) values.get(i).asDouble();
                norm += (double) v[i] * v[i];
            }
            norm = Math.sqrt(norm);
            if (norm == 0 || Double.isNaN(norm)) {
                throw new ApplicationException("Embedding vector is invalid.", 502, "EMBEDDING_INVALID_VALUE");
            }
            for (int i = 0; i < DIMENSION; i++) {
                v[i] = (float) (v[i] / norm);
            }
            vectors.add(v);
        }
        return vectors;
    }

    private void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplicationException("Gemini embedding was interrupted.", 502,
                    "EMBEDDING_PROVIDER_ERROR", null, e);
        }
    }

    private String safe(String raw) {
        String text = raw == null || raw.isEmpty() ? "error" : raw;
        if (apiKey != null && !apiKey.isEmpty()) {
            text = text.replace(apiKey, "***");
        }
        text = String.join(" ", text.trim().split("\\s+"));
        return text.substring(0, Math.min(300, text.length()));
    }
}