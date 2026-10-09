package com.pdfrag;

import com.pdfrag.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class PdfRagApplication {

    public static void main(String[] args) {
        SpringApplication.run(PdfRagApplication.class, args);
    }
}
