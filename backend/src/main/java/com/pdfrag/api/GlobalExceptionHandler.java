package com.pdfrag.api;

import com.pdfrag.error.ApplicationException;
import com.pdfrag.obs.Logs;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Maps every failure onto {"success": false, "error": {code, message, details}}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger("pdf_rag.errors");

    @ExceptionHandler(ApplicationException.class)
    public ResponseEntity<Map<String, Object>> application(ApplicationException e) {
        Logs.warn(LOG, "application_error", "error_code", e.getErrorCode(), "status_code", e.getStatusCode());
        return body(e.getStatusCode(), e.getErrorCode(), e.getMessage(), e.getDetails());
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, Object>> tooLarge(MaxUploadSizeExceededException e) {
        return body(413, "FILE_TOO_LARGE", "The uploaded file exceeds the configured maximum size.", null);
    }

    @ExceptionHandler({MissingServletRequestPartException.class, MissingServletRequestParameterException.class,
            MultipartException.class})
    public ResponseEntity<Map<String, Object>> missingFile(Exception e) {
        Object details = List.of(Map.of("type", "missing", "loc", List.of("body", "file"), "msg", "Field required"));
        return body(422, "VALIDATION_ERROR", "Request validation failed.", details);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, HttpMediaTypeNotSupportedException.class})
    public ResponseEntity<Map<String, Object>> unreadable(Exception e) {
        Object details = List.of(Map.of(
                "type", "model_attributes_type", "loc", List.of("body"),
                "msg", "Input should be a valid object with a string 'question'"));
        return body(422, "VALIDATION_ERROR", "Request validation failed.", details);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        return body(404, "HTTP_ERROR", "Not Found", null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> methodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return body(405, "HTTP_ERROR", "Method Not Allowed", null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e, HttpServletRequest request) {
        Logs.error(LOG, "unhandled_exception", e, "method", request.getMethod(), "path", request.getRequestURI());
        return body(500, "INTERNAL_SERVER_ERROR", "An unexpected internal error occurred.", null);
    }

    private static ResponseEntity<Map<String, Object>> body(int status, String code, String message, Object details) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        error.put("details", details);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("success", false);
        payload.put("error", error);
        return ResponseEntity.status(HttpStatus.valueOf(status)).body(payload);
    }
}
