package com.pdfrag.generation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pdfrag.config.AppProperties;
import com.pdfrag.error.ApplicationException;
import com.pdfrag.obs.Logs;
import com.pdfrag.query.GroundedPrompt;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Isolated Groq (OpenAI-compatible chat completions) provider with explicit retry classification. */
@Component
public class GroqAdapter implements AnswerGenerator {

    private static final Logger LOG = LoggerFactory.getLogger("pdf_rag.generation.groq");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final AppProperties props;
    private final HttpClient client;
    private final Duration timeout;

    public GroqAdapter(AppProperties props) {
        this.props = props;
        this.timeout = Duration.ofMillis((long) (props.httpTimeoutSeconds() * 1000));
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public String generate(GroundedPrompt prompt) {
        Exception last = null;
        int maxRetries = props.groqMaxRetries();

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpResponse<String> response = client.send(buildRequest(prompt), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();

                if (status >= 200 && status < 300) {
                    return validateResponse(MAPPER.readTree(response.body()));
                }

                String message = truncate(response.body());
                switch (status) {
                    case 401 -> throw fail("groq_authentication_error", message, status,
                            "Groq authentication failed.", 502, "GENERATION_INVALID_CREDENTIALS", null);
                    case 403 -> throw fail("groq_permission_error", message, status,
                            "Groq denied access (403). Check that GROQ_API_KEY is valid, that your network/VPN/proxy is not blocking api.groq.com, and the key's project settings at console.groq.com.", 502, "GENERATION_PERMISSION_DENIED", null);
                    case 404 -> throw fail("groq_model_not_found", message, status,
                            "The configured Groq model was not found.", 502, "GENERATION_MODEL_NOT_FOUND", null);
                    case 400 -> throw fail("groq_bad_request", message, status,
                            "Groq rejected the generation request.", 502, "GENERATION_BAD_REQUEST", null);
                    case 429 -> {
                        Logs.error(LOG, "groq_rate_limit", null, "attempt", attempt, "max_retries", maxRetries,
                                "exception_message", message);
                        if (attempt >= maxRetries) {
                            throw new ApplicationException(
                                    "Groq rate limit was reached.", 429, "GENERATION_RATE_LIMIT");
                        }
                        backoff(attempt);
                        continue;
                    }
                    default -> {
                        if (status >= 500) {
                            Logs.error(LOG, "groq_internal_server_error", null, "attempt", attempt,
                                    "max_retries", maxRetries, "provider_status", status,
                                    "exception_message", message);
                            if (attempt >= maxRetries) {
                                throw new ApplicationException(
                                        "Groq returned an internal error.", 502, "GENERATION_PROVIDER_ERROR");
                            }
                            backoff(attempt);
                            continue;
                        }
                        throw fail("groq_api_status_error", message, status,
                                "Groq returned an API error.", 502, "GENERATION_PROVIDER_ERROR",
                                Map.of("provider_status", status));
                    }
                }
            } catch (ApplicationException e) {
                throw e;
            } catch (HttpTimeoutException e) {
                last = e;
                Logs.error(LOG, "groq_timeout", null, "attempt", attempt, "max_retries", maxRetries,
                        "exception_message", String.valueOf(e.getMessage()));
                if (attempt >= maxRetries) {
                    throw new ApplicationException(
                            "Groq generation timed out.", 504, "GENERATION_TIMEOUT", null, e);
                }
                backoff(attempt);
            } catch (IOException e) {
                last = e;
                Logs.error(LOG, "groq_connection_error", null, "attempt", attempt, "max_retries", maxRetries,
                        "exception_type", e.getClass().getSimpleName(),
                        "exception_message", String.valueOf(e.getMessage()));
                if (attempt >= maxRetries) {
                    throw new ApplicationException(
                            "Groq could not be reached.", 502, "GENERATION_CONNECTION_ERROR", null, e);
                }
                backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ApplicationException(
                        "Groq generation was interrupted.", 502, "GENERATION_PROVIDER_ERROR", null, e);
            } catch (RuntimeException e) {
                Logs.error(LOG, "groq_unexpected_exception", e, "exception_type", e.getClass().getSimpleName());
                throw new ApplicationException(
                        "Unexpected Groq generation failure.", 502, "GENERATION_PROVIDER_ERROR",
                        Map.of("exception_type", e.getClass().getSimpleName()), e);
            }
        }

        throw new ApplicationException(
                "Groq generation failed after retries.", 502, "GENERATION_RETRY_EXHAUSTED", null, last);
    }

    private HttpRequest buildRequest(GroundedPrompt prompt) throws IOException {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("model", props.groqModel());
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "system").put("content", prompt.systemMessage());
        messages.addObject().put("role", "user").put("content", prompt.userMessage());
        body.put("temperature", props.groqTemperature());
        body.put("max_completion_tokens", props.groqMaxCompletionTokens());
        body.put("stream", false);

        // Reasoning parameters are only accepted by reasoning models such as GPT-OSS.
        if (props.groqModel().toLowerCase().contains("gpt-oss")) {
            body.put("include_reasoning", false);
            body.put("reasoning_effort", "low");
        }

        String base = props.groqBaseUrl().endsWith("/")
                ? props.groqBaseUrl().substring(0, props.groqBaseUrl().length() - 1)
                : props.groqBaseUrl();

        return HttpRequest.newBuilder()
                .uri(URI.create(base + "/chat/completions"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + props.groqApiKey())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                // Groq's edge firewall rejects the default "Java-http-client" agent with 403.
                .header("User-Agent", "pdf-rag-backend/1.0 (Spring Boot; +https://console.groq.com)")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
    }

    private static ApplicationException fail(
            String event, String message, int status, String text, int httpStatus, String code, Object details) {
        Logs.error(LOG, event, null, "provider_status", status, "exception_message", message);
        return new ApplicationException(text, httpStatus, code, details);
    }

    static String validateResponse(JsonNode root) {
        if (root == null || root.isNull() || root.isMissingNode()) {
            throw new ApplicationException("Groq returned no response.", 502, "GENERATION_EMPTY_RESPONSE");
        }
        JsonNode choices = root.get("choices");
        if (choices == null || !choices.isArray() || choices.isEmpty()) {
            throw new ApplicationException(
                    "Groq response contained no choices.", 502, "GENERATION_INVALID_RESPONSE");
        }
        JsonNode content = choices.get(0).path("message").path("content");
        if (!content.isTextual() || content.asText().isBlank()) {
            throw new ApplicationException(
                    "Groq returned empty assistant content.", 502, "GENERATION_EMPTY_RESPONSE");
        }
        return content.asText().strip();
    }

    /** Exponential delay: 1s, 2s, 4s ... capped at 8s. */
    private static void backoff(int attempt) {
        try {
            Thread.sleep(Math.min((long) Math.pow(2, attempt), 8) * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ApplicationException(
                    "Groq generation was interrupted.", 502, "GENERATION_PROVIDER_ERROR", null, e);
        }
    }

    private static String truncate(String body) {
        String text = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        return text.substring(0, Math.min(300, text.length()));
    }
}
