package com.pdfrag.support;

import com.pdfrag.embedding.EmbeddingProvider;
import com.pdfrag.generation.AnswerGenerator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Deterministic offline stand-ins for the embedding model and for Groq. */
@TestConfiguration
public class FakeBeans {

    public static final int DIM = 128;

    @Bean
    @Primary
    public EmbeddingProvider fakeEmbedder() {
        return new EmbeddingProvider() {
            @Override
            public List<float[]> embedTexts(List<String> texts) {
                List<float[]> out = new ArrayList<>();
                for (String t : texts) {
                    float[] v = new float[DIM];
                    for (String w : t.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
                        if (!w.isEmpty()) {
                            v[Math.floorMod(w.hashCode(), DIM)] += 1f;
                        }
                    }
                    double norm = 0;
                    for (float x : v) {
                        norm += x * x;
                    }
                    norm = Math.sqrt(norm);
                    for (int i = 0; i < DIM; i++) {
                        v[i] = norm == 0 ? 0f : (float) (v[i] / norm);
                    }
                    out.add(v);
                }
                return out;
            }

            @Override
            public int batchSize() {
                return 8;
            }
        };
    }

    @Bean
    @Primary
    public AnswerGenerator fakeGenerator() {
        return prompt -> "FAKE-ANSWER for: " + prompt.userMessage().lines().skip(1).findFirst().orElse("");
    }
}
