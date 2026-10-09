package com.pdfrag.error;

/** Application error carrying the HTTP status and stable error code used in the JSON contract. */
public class ApplicationException extends RuntimeException {

    private final int statusCode;
    private final String errorCode;
    private final transient Object details;
    private static final long serialVersionUID = 1L;

    public ApplicationException(String message, int statusCode, String errorCode) {
        this(message, statusCode, errorCode, null, null);
    }

    public ApplicationException(String message, int statusCode, String errorCode, Object details) {
        this(message, statusCode, errorCode, details, null);
    }

    public ApplicationException(
            String message, int statusCode, String errorCode, Object details, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
        this.errorCode = errorCode;
        this.details = details;
    }

    public int getStatusCode() {
        return statusCode;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public Object getDetails() {
        return details;
    }
}
