package com.jbp.config;

import com.jbp.util.PerUserCallBudget;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Story 14.2's per-candidate draft allowance. Every {@code app.draft-answer.*} key is read here and
 * nowhere else, the rule Story 11.1 set.
 *
 * <p>Its own class rather than a bean on {@link AiClientConfig}: this is not an {@code app.ai.*} key
 * and it is not a provider control. The provider quota bounds what <em>we</em> spend per minute
 * across everyone; this bounds what <em>one candidate</em> may ask for in a day, and the two would
 * be tuned by different people for different reasons.
 */
@Slf4j
@Configuration
public class DraftAnswerBudgetConfig {

    /**
     * @param maxDrafts        drafts per candidate per window. Ten, decided 2026-08-01: one call per
     *                         question, so a job with three free-text questions costs three — roughly
     *                         three fully drafted applications a day.
     * @param windowHours      how long a spent draft counts against the allowance. Rolling, so there
     *                         is no midnight boundary to burst across and no timezone to choose.
     * @param maxTrackedUsers  how many candidates are counted at once. A bound on memory, not on
     *                         behaviour: a candidate evicted under pressure gets a fresh allowance,
     *                         the same forgiveness a restart already grants everyone.
     */
    @Bean
    public PerUserCallBudget draftAnswerBudget(
            @Value("${app.draft-answer.max-per-candidate:10}") int maxDrafts,
            @Value("${app.draft-answer.window-hours:24}") long windowHours,
            @Value("${app.draft-answer.max-tracked-candidates:50000}") int maxTrackedUsers) {

        Duration window = Duration.ofHours(windowHours);
        log.info("Screening-answer drafts: {} per candidate per {}, tracking at most {} candidates",
                maxDrafts, window, maxTrackedUsers);
        return new PerUserCallBudget(maxDrafts, window, maxTrackedUsers, Clock.systemUTC());
    }
}
