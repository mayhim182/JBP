package com.jbp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.InterviewQuestionGenerator;
import com.jbp.serviceimpl.AiInterviewQuestionGenerator;
import com.jbp.serviceimpl.CachingInterviewQuestionGenerator;
import com.jbp.serviceimpl.DisabledInterviewQuestionGenerator;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Assembles Story 14.1's generator and the cache in front of it. Every
 * {@code app.interview-prep.*} key is read here and nowhere else.
 *
 * <p><strong>Its own {@link CacheManager}, not Story 13.5's.</strong> That one uses a variable expiry
 * that inspects the value's <em>type</em> to choose between a long and a short lifetime; anything
 * else dropped into it silently inherits the 60-second fallback, and the only symptom would be a
 * provider bill. A cache whose policy is chosen by what it holds cannot be shared with something it
 * has never heard of.
 *
 * <p>A plain {@code expireAfterWrite} is right here, because there is only one kind of value: a
 * generated question set. Failures are not cached at all — see
 * {@link CachingInterviewQuestionGenerator}.
 */
@Slf4j
@Configuration
public class InterviewPrepCacheConfig {

    /**
     * The capability flag decides which implementation exists, so the switch is resolved once at
     * startup rather than on every request. With it off, a direct API call still answers honestly
     * through {@link DisabledInterviewQuestionGenerator}.
     */
    @Bean
    public InterviewQuestionGenerator interviewQuestionGenerator(AiCapabilities aiCapabilities,
                                                                 ChatCompletionClient chatCompletionClient,
                                                                 ObjectMapper objectMapper,
                                                                 Validator validator,
                                                                 AiTaskBudget aiTaskBudget) {
        if (!aiCapabilities.interviewPrep()) {
            log.info("Interview prep is off — the section will not be offered");
            return new DisabledInterviewQuestionGenerator();
        }
        return new CachingInterviewQuestionGenerator(new AiInterviewQuestionGenerator(
                chatCompletionClient, objectMapper, validator, aiTaskBudget));
    }

    /**
     * @param maximumEntries bound on distinct job briefs held at once
     * @param ttlHours       how long a generated set stays usable. Long, because the key already
     *                       changes whenever the posting changes — this bound exists to stop a cache
     *                       of long-dead postings growing forever, not to catch edits.
     */
    @Bean(CachingInterviewQuestionGenerator.CACHE_MANAGER)
    public CacheManager interviewPrepCacheManager(
            @Value("${app.interview-prep.cache-max-entries:5000}") long maximumEntries,
            @Value("${app.interview-prep.cache-ttl-hours:24}") long ttlHours) {

        log.info("Interview prep cache: max {} entries, kept {}", maximumEntries, Duration.ofHours(ttlHours));
        CaffeineCacheManager cacheManager =
                new CaffeineCacheManager(CachingInterviewQuestionGenerator.CACHE_NAME);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(maximumEntries)
                .expireAfterWrite(ttlHours, TimeUnit.HOURS));
        return cacheManager;
    }
}
