package com.pdfrag.query;

import java.util.List;

public record BuiltContext(List<ContextBlock> blocks, int estimatedTokens) {}
