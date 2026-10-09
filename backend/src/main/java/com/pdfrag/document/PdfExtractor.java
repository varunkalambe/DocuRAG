package com.pdfrag.document;

import com.pdfrag.error.ApplicationException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

public final class PdfExtractor {

    private PdfExtractor() {}

    public static List<ExtractedPage> extractPages(byte[] fileBytes) {
        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            // Owner-password-only PDFs open with an empty user password; drop their restrictions.
            if (document.isEncrypted()) {
                document.setAllSecurityToBeRemoved(true);
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setLineSeparator("\n");

            List<ExtractedPage> pages = new ArrayList<>();
            int total = document.getNumberOfPages();
            for (int index = 1; index <= total; index++) {
                String text;
                try {
                    stripper.setStartPage(index);
                    stripper.setEndPage(index);
                    text = stripper.getText(document);
                } catch (IOException | RuntimeException e) {
                    throw new ApplicationException(
                            "Text extraction failed for PDF page " + index + ".", 422,
                            "PAGE_EXTRACTION_FAILED", Map.of("page_number", index), e);
                }
                if (text == null) {
                    text = "";
                }
                pages.add(new ExtractedPage(index, text, text.isBlank()));
            }
            return pages;

        } catch (InvalidPasswordException e) {
            throw new ApplicationException(
                    "The PDF is password-protected. Remove the password and upload it again.", 422,
                    "PDF_ENCRYPTED", null, e);
        } catch (ApplicationException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ApplicationException(
                    "The PDF could not be processed for text extraction.", 422, "PDF_EXTRACTION_FAILED", null, e);
        }
    }
}
