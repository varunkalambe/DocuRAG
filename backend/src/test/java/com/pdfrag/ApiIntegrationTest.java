package com.pdfrag;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.pdfrag.support.FakeBeans;
import java.io.ByteArrayOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "app.groq-api-key=test-key",
        "app.groq-model=test-model",
        "app.embedding-provider=local",
        "app.relevance-threshold=0.95",
        "app.chroma-persist-dir=target/test-data/${random.uuid}"
})
@AutoConfigureMockMvc
@Import(FakeBeans.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {

    @Autowired
    MockMvc mvc;

    private static byte[] samplePdf() throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            String[] lines = {
                "Quarterly Revenue Report",
                "The company reported revenue growth of twelve percent in the third quarter.",
                "Operating costs decreased because of lower logistics spending.",
                "The board approved a new dividend policy for shareholders."
            };
            for (int p = 0; p < 2; p++) {
                PDPage page = new PDPage();
                doc.addPage(page);
                try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                    cs.beginText();
                    cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    cs.setLeading(16f);
                    cs.newLineAtOffset(50, 700);
                    for (String l : lines) {
                        cs.showText(l);
                        cs.newLine();
                    }
                    cs.endText();
                }
            }
            doc.save(out);
            return out.toByteArray();
        }
    }

    @Test
    @Order(1)
    void healthAndRoot() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("healthy"));
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(jsonPath("$.success").value(true));
    }

    @Test
    @Order(2)
    void queryBeforeUploadIs409() throws Exception {
        mvc.perform(post("/api/query").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"revenue?\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("NO_DOCUMENTS_INDEXED"));
    }

    @Test
    @Order(3)
    void uploadIndexesThenDuplicateIsDetected() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "report.pdf", "application/pdf", samplePdf());

        mvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("indexed"))
                .andExpect(jsonPath("$.data.page_count").value(2))
                .andExpect(jsonPath("$.data.chunk_count").value(greaterThan(0)));

        mvc.perform(multipart("/api/documents/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("already_indexed"));

        mvc.perform(get("/api/documents"))
                .andExpect(jsonPath("$.data.documents[0].filename").value("report.pdf"))
                .andExpect(jsonPath("$.data.documents[0].page_count").value(2));
    }

    @Test
    @Order(4)
    void contentQuestionIsAnsweredFromRetrieval() throws Exception {
        mvc.perform(post("/api/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"revenue growth third quarter\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("answered"))
                .andExpect(jsonPath("$.data.retrieval.mode").value("retrieval"))
                .andExpect(jsonPath("$.data.answer").value(containsString("FAKE-ANSWER")))
                .andExpect(jsonPath("$.data.sources[0].filename").value("report.pdf"));
    }

    @Test
    @Order(5)
    void documentLevelQuestionUsesOverview() throws Exception {
        mvc.perform(post("/api/query").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"What is this document about?\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.retrieval.mode").value("document_overview"));
    }

    @Test
    @Order(6)
    void validationErrorsUseTheStandardEnvelope() throws Exception {
        mvc.perform(post("/api/query").contentType(MediaType.APPLICATION_JSON).content("{\"question\":123}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(post("/api/query").contentType(MediaType.APPLICATION_JSON).content("{\"question\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("EMPTY_QUESTION"));
        mvc.perform(multipart("/api/documents/upload"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        mvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile("file", "x.txt", "text/plain", "hello".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_FILENAME"));
        mvc.perform(multipart("/api/documents/upload")
                        .file(new MockMultipartFile("file", "x.pdf", "application/pdf", "not a pdf".getBytes())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_PDF_SIGNATURE"));
        mvc.perform(get("/api/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("HTTP_ERROR"));
    }

    @Test
    @Order(7)
    void corsPreflightAndHeaders() throws Exception {
        mvc.perform(options("/api/query")
                        .header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
        mvc.perform(get("/api/health").header("Origin", "http://localhost:4200"))
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
        mvc.perform(options("/api/query")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(8)
    void clearMemory() throws Exception {
        mvc.perform(delete("/api/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("cleared"));
        mvc.perform(get("/api/documents")).andExpect(jsonPath("$.data.total_chunks").value(0));
    }
}
