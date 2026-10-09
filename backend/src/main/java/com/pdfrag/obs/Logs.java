package com.pdfrag.obs;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;

/** Structured one-line JSON logging, same shape as the Python JsonFormatter. */
public final class Logs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private Logs() {}

    public static void info(Logger logger, String event, Object... kv) {
        if (logger.isInfoEnabled()) {
            logger.info(line(logger, "INFO", event, null, kv));
        }
    }

    public static void warn(Logger logger, String event, Object... kv) {
        if (logger.isWarnEnabled()) {
            logger.warn(line(logger, "WARNING", event, null, kv));
        }
    }

    public static void error(Logger logger, String event, Throwable error, Object... kv) {
        if (logger.isErrorEnabled()) {
            logger.error(line(logger, "ERROR", event, error, kv));
        }
    }

    private static String line(Logger logger, String level, String event, Throwable error, Object[] kv) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("timestamp", Instant.now().toString());
        payload.put("level", level);
        payload.put("logger", logger.getName());
        payload.put("event", event);
        payload.put("message", event);

        Map<String, Object> fields = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            fields.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        if (!fields.isEmpty()) {
            payload.put("fields", fields);
        }
        if (error != null) {
            java.io.StringWriter sw = new java.io.StringWriter();
            error.printStackTrace(new java.io.PrintWriter(sw));
            payload.put("exception", sw.toString());
        }
        try {
            return MAPPER.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            return "{\"event\":\"" + event + "\",\"message\":\"log serialization failed\"}";
        }
    }

    /** Elapsed milliseconds since a System.nanoTime() start, rounded to 2 decimals. */
    public static double ms(long startNanos) {
        return Math.round((System.nanoTime() - startNanos) / 1_000_000.0 * 100.0) / 100.0;
    }
}
