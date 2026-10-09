package com.pdfrag.document;

import com.pdfrag.error.ApplicationException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

public final class PdfValidator {

    /** Browsers/OSes label PDFs inconsistently; the real check is signature + successful parse. */
    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "application/pdf", "application/x-pdf", "application/acrobat", "application/octet-stream");
    /** The PDF spec allows a few bytes of junk before the header. */
    private static final int PDF_SIGNATURE_WINDOW = 1024;

    public record Result(String filename, String contentType, int sizeBytes) {}

    private PdfValidator() {}

    public static Result validate(byte[] fileBytes, String filename, String contentType, long maxSizeBytes) {
        if (fileBytes == null) {
            throw new ApplicationException("No file data was supplied.", 400, "MISSING_FILE");
        }
        if (fileBytes.length == 0) {
            throw new ApplicationException("The uploaded file is empty.", 400, "EMPTY_FILE");
        }
        if (fileBytes.length > maxSizeBytes) {
            throw new ApplicationException(
                    "The uploaded file exceeds the configured maximum size.", 413, "FILE_TOO_LARGE",
                    Map.of("size_bytes", fileBytes.length, "max_size_bytes", maxSizeBytes));
        }
        if (filename == null || filename.isBlank()) {
            throw new ApplicationException("A filename is required.", 400, "MISSING_FILENAME");
        }

        // Some clients send a full client-side path; keep only the file name.
        String normalized = filename.strip().replace("\\", "/");
        String clean = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        if (clean.isEmpty()) {
            throw new ApplicationException("A filename is required.", 400, "MISSING_FILENAME");
        }
        if (!clean.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw new ApplicationException(
                    "The uploaded filename must end with .pdf.", 400, "INVALID_FILENAME");
        }

        String normalizedType = (contentType == null || contentType.isBlank())
                ? null : contentType.strip().toLowerCase(Locale.ROOT);
        if (normalizedType != null) {
            // Ignore parameters such as "; charset=binary".
            String bare = normalizedType.contains(";")
                    ? normalizedType.substring(0, normalizedType.indexOf(';')).strip() : normalizedType;
            if (!ALLOWED_CONTENT_TYPES.contains(bare)) {
                throw new ApplicationException(
                        "The declared file type is not acceptable.", 400, "INVALID_CONTENT_TYPE",
                        Map.of("content_type", normalizedType));
            }
        }

        int window = Math.min(PDF_SIGNATURE_WINDOW, fileBytes.length);
        String head = new String(fileBytes, 0, window, StandardCharsets.ISO_8859_1);
        if (!head.contains("%PDF-")) {
            throw new ApplicationException(
                    "The uploaded content does not have a valid PDF signature.", 400, "INVALID_PDF_SIGNATURE");
        }

        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            document.getNumberOfPages();
        } catch (InvalidPasswordException e) {
            throw new ApplicationException(
                    "The PDF is password-protected. Remove the password and upload it again.", 422,
                    "PDF_ENCRYPTED", null, e);
        } catch (IOException | RuntimeException e) {
            throw new ApplicationException(
                    "The uploaded file could not be opened as a valid PDF.", 400, "CORRUPTED_PDF", null, e);
        }

        return new Result(clean, normalizedType, fileBytes.length);
    }
}
