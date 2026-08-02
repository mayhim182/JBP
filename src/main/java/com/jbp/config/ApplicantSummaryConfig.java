package com.jbp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jbp.service.ApplicantSummarizer;
import com.jbp.service.ChatCompletionClient;
import com.jbp.serviceimpl.AiApplicantSummarizer;
import com.jbp.serviceimpl.CachingApplicantSummarizer;
import com.jbp.serviceimpl.DisabledApplicantSummarizer;
import com.jbp.util.PerUserCallBudget;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Assembles Story 14.3's summarizer, the cache in front of it and the ceiling around it. Every
 * {@code app.applicant-summary.*} key is read here and nowhere else.
 *
 * <p>Its own {@link CacheManager} for the reason Story 14.1's carries: Story 13.5's uses a variable
 * expiry that inspects the value's <em>type</em> to choose a lifetime, so anything else dropped into
 * it silently inherits a 60-second fallback and the only symptom is a provider bill.
 */
@Slf4j
@Configuration
public class ApplicantSummaryConfig {

    /**
     * The capability flag decides which implementation exists, so the switch is resolved once at
     * startup rather than on every request — and the client is told the same answer through
     * {@code GET /api/config}, which is what lets design 24 B3 make the panel absent before first
     * paint rather than after a failed fetch.
     */
    @Bean
    public ApplicantSummarizer applicantSummarizer(AiCapabilities aiCapabilities,
                                                   ChatCompletionClient chatCompletionClient,
                                                   ObjectMapper objectMapper,
                                                   Validator validator,
                                                   AiTaskBudget aiTaskBudget) {
        if (!aiCapabilities.applicantSummary()) {
            log.info("Applicant summaries are off — the panel will not be offered");
            return new DisabledApplicantSummarizer();
        }
        return new CachingApplicantSummarizer(new AiApplicantSummarizer(
                chatCompletionClient, objectMapper, validator, aiTaskBudget));
    }

    /**
     * @param maximumEntries bound on distinct (application, score version) pairs held at once
     * @param ttlHours       how long a written read stays usable. Long, because the key already
     *                       changes whenever the profile, the job or the score changes — this bound
     *                       exists to stop a cache of long-settled pipelines growing forever, not to
     *                       catch edits.
     */
    @Bean(CachingApplicantSummarizer.CACHE_MANAGER)
    public CacheManager applicantSummaryCacheManager(
            @Value("${app.applicant-summary.cache-max-entries:20000}") long maximumEntries,
            @Value("${app.applicant-summary.cache-ttl-hours:24}") long ttlHours) {

        log.info("Applicant summary cache: max {} entries, kept {}",
                maximumEntries, Duration.ofHours(ttlHours));
        CaffeineCacheManager cacheManager =
                new CaffeineCacheManager(CachingApplicantSummarizer.CACHE_NAME);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(maximumEntries)
                .expireAfterWrite(ttlHours, TimeUnit.HOURS));
        return cacheManager;
    }

    /**
     * A ceiling, not a budget — see {@link PerUserCallBudget} for why the distinction is kept.
     *
     * <p>Thirty a minute is roughly six times what a recruiter reading each applicant can manage, so
     * no honest use reaches it and it is never surfaced. It exists to stop a retry loop or a script,
     * and it is deliberately <em>not</em> a daily quota: any daily number low enough to control cost
     * would stop a recruiter triaging a large pipeline, and a cost control that blocks the work is a
     * worse bug than the cost it prevents.
     *
     * <p>It is also what keeps the silence honest. A lower ceiling would make retrying genuinely
     * futile inside its window, at which point saying nothing becomes a lie and design 21b D2's
     * countdown would be owed. At this height the condition cannot arise for a human.
     */
    @Bean
    public PerUserCallBudget applicantSummaryCeiling(
            @Value("${app.applicant-summary.max-per-recruiter-per-minute:30}") int maxPerMinute,
            @Value("${app.applicant-summary.max-tracked-recruiters:10000}") int maxTrackedRecruiters) {

        log.info("Applicant summary ceiling: {} per recruiter per minute, tracking at most {} recruiters",
                maxPerMinute, maxTrackedRecruiters);
        return new PerUserCallBudget(
                maxPerMinute, Duration.ofMinutes(1), maxTrackedRecruiters, Clock.systemUTC());
    }
}
