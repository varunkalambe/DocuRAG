package com.pdfrag.embedding;

import com.pdfrag.config.AppProperties;
import com.pdfrag.obs.Logs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;

@Configuration
public class EmbeddingConfig {

    private static final Logger LOG = LoggerFactory.getLogger("pdf_rag.embeddings.factory");

    @Bean
    public EmbeddingProvider embeddingProvider(AppProperties props) {
        String provider = props.embeddingProvider().strip().toLowerCase();
        EmbeddingProvider embedder;

        if (provider.equals("local")) {
            embedder = new LocalEmbeddingAdapter(props.localEmbeddingBatchSize());
        } else if (provider.equals("gemini")) {
            embedder = new GeminiEmbeddingAdapter();
        } else {
            HuggingFaceEmbeddingAdapter primary = new HuggingFaceEmbeddingAdapter(props);
            if (props.embeddingFallbackToLocal()) {
                embedder = new FallbackEmbeddingAdapter(
                        primary,
                        new LocalEmbeddingAdapter(props.localEmbeddingBatchSize()),
                        props.embeddingPrimaryCooldownSeconds());
            } else {
                embedder = primary;
            }
        }

        Logs.info(LOG, "embedder_configured", "provider", provider,
                "fallback_to_local", props.embeddingFallbackToLocal());
        return embedder;
    }

    @Bean
    public ModelWarmup modelWarmup(EmbeddingProvider embeddingProvider) {
        return new ModelWarmup(embeddingProvider);
    }

    public static class ModelWarmup {
        private final EmbeddingProvider provider;

        ModelWarmup(EmbeddingProvider provider) {
            this.provider = provider;
        }

        @EventListener(ApplicationReadyEvent.class)
        public void warm() {
            if (provider instanceof LocalEmbeddingAdapter local) {
                Thread t = new Thread(() -> {
                    try {
                        local.warmUp();
                    } catch (RuntimeException | LinkageError e) {
                        Logs.error(LOG, "local_embedding_warmup_failed", e);
                    }
                }, "embedding-warmup");
                t.setDaemon(true);
                t.start();
            }
        }
    }
}