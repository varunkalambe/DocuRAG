package com.pdfrag.api;

/** Success envelope: {"success": true, "data": ...}. */
public record ApiResponse<T>(boolean success, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data);
    }
}
