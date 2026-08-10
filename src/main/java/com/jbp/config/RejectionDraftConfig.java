package com.jbp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.RejectionReasonDrafter;
import com.jbp.serviceimpl.AiRejectionReasonDrafter;
import com.jbp.serviceimpl.DisabledRejectionReasonDrafter;
import com.jbp.util.PerUserCallBudget;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Assembles Story 14.4's drafter and the ceiling around it. Every {@code app.rejection-draft.*} key is
 * read here and nowhere else, the rule Story 11.1 set.
 *
 * <p><strong>No cache manager</strong>, unlike {@link ApplicantSummaryConfig} — see
 * {@link com.jbp.dto.DraftedRejectionReason} for why a rejection is drafted once rather than read
 * repeatedly, and why serving a second request from a cache would defeat the only reason to make one.
 */
@Slf4j
@Configuration
public class RejectionDraftConfig {

    /**
     * The capability flag decides which implementation exists, so the switch is resolved once at
     * startup and reported identically through {@code GET /api/config} — which is what lets design 26
     * C5 omit the trigger before first paint instead of removing it after a failed call.
     */
    @Bean
    public RejectionReasonDrafter rejectionReasonDrafter(AiCapabilities aiCapabilities,
                                                         ChatCompletionClient chatCompletionClient,
                                                         ObjectMapper objectMapper,
                                                         Validator validator,
                                                         AiTaskBudget aiTaskBudget) {
        if (!aiCapabilities.rejectionDrafting()) {
            log.info("Rejection drafting is off — the compose panel ships without its trigger");
            return new DisabledRejectionReasonDrafter();
        }
        return new AiRejectionReasonDrafter(chatCompletionClient, objectMapper, validator, aiTaskBudget);
    }

    /**
     * A ceiling, not a budget — see {@link PerUserCallBudget} for why the distinction is kept, and
     * {@link ApplicantSummaryConfig#applicantSummaryCeiling} for the fuller argument.
     *
     * <p>Lower than the summary's thirty, because the shapes differ. A recruiter reads many summaries
     * while triaging a pipeline; they write one rejection at a time, and each one is a decision they
     * have already made. Ten a minute is far above any honest pace and still bounds a retry loop.
     */
    @Bean
    public PerUserCallBudget rejectionDraftCeiling(
            @Value("${app.rejection-draft.max-per-recruiter-per-minute:10}") int maxPerMinute,
            @Value("${app.rejection-draft.max-tracked-recruiters:10000}") int maxTrackedRecruiters) {

        log.info("Rejection draft ceiling: {} per recruiter per minute, tracking at most {} recruiters",
                maxPerMinute, maxTrackedRecruiters);
        return new PerUserCallBudget(
                maxPerMinute, Duration.ofMinutes(1), maxTrackedRecruiters, Clock.systemUTC());
    }
}
