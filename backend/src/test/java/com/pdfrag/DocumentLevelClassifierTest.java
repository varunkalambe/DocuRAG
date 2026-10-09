package com.pdfrag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.pdfrag.query.DocumentLevelClassifier;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class DocumentLevelClassifierTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "What is this document about?|true",
            "summarize|true",
            "Summarize this pdf in 3 bullets|true",
            "waht is thsi doc abuot|true",
            "how many pages?|true",
            "Who wrote this paper?|true",
            "What are the main points of this document?|true",
            "What are the main points of chapter 2?|false",
            "explain the methodology section|false",
            "What does the document say about revenue growth in 2023?|false",
            "What is the capital of France?|false",
            "summarise the introduction|false",
            "what is the interest rate on the loan agreement|false",
            "hello|false"
    })
    void classifiesQuestions(String question, boolean expected) {
        assertEquals(expected, DocumentLevelClassifier.isDocumentLevelQuestion(question));
    }
}
