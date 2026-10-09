package com.pdfrag.generation;

import com.pdfrag.query.GroundedPrompt;

/** grounded prompt -> generated answer. Knows nothing about PDFs, chunks or the vector store. */
public interface AnswerGenerator {

    String generate(GroundedPrompt prompt);
}
