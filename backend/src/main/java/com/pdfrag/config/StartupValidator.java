package com.pdfrag.config;

import com.pdfrag.obs.Logs;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Fails fast at startup on invalid configuration (same checks as the Python settings.validate()). */
@Component
public class StartupValidator {

    private static final Logger LOG = LoggerFactory.getLogger("pdf_rag.application");

    private final AppProperties props;

    public StartupValidator(AppProperties props) {
        this.props = props;
    }

    @PostConstruct
    void validate() {
        try {
            props.validate();
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Invalid application configuration: " + e.getMessage(), e);
        }
        Logs.info(LOG, "application_started", "environment", props.env());
    }
}
