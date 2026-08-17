package com.jbp.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles Story 14.6's thresholds. Every {@code app.duplicate-detection.*} key is read here and
 * nowhere else.
 *
 * <p><strong>No capability flag</strong>, unlike every other Epic 14 story. Nothing on this path calls
 * a model — it is arithmetic over vectors Story 13.2 already stores — so there is no AI to switch off.
 * The feature disables itself wherever the vectors are missing, which is the same condition that
 * already turns semantic matching back into rule-based matching.
 */
@Slf4j
@Configuration
public class DuplicateDetectionConfig {

    /**
     * The default is a starting point, not a measured one.
     *
     * <p>{@code CosineSimilarity} records that this model returns roughly 0.5–1.0 in practice, so the
     * scale is compressed and a duplicate threshold has to sit high to mean anything — unrelated
     * postings in the same industry already score well above zero. 0.92 is deliberately cautious: a
     * missed duplicate costs nothing (they are permitted anyway), while a false one trains recruiters
     * to ignore the flag. Tune it from the similarity figures the detector logs.
     */
    @Bean
    public DuplicateDetectionSettings duplicateDetectionSettings(
            @Value("${app.duplicate-detection.similarity-threshold:0.92}") double similarityThreshold,
            @Value("${app.duplicate-detection.max-reported:3}") int maxReported) {

        log.info("Duplicate detection: reporting at most {} postings above a cosine of {}",
                maxReported, similarityThreshold);
        return new DuplicateDetectionSettings(similarityThreshold, maxReported);
    }
}
