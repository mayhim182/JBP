package com.jbp.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.MatchExplainer;
import com.jbp.service.MatchExplainer.MatchExplanation;
import com.jbp.serviceimpl.AiMatchExplainer;
import com.jbp.serviceimpl.CachingMatchExplainer;
import com.jbp.serviceimpl.DisabledMatchExplainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Assembles the match explainer and the cache in front of it. Every {@code app.match.explanation.*} key
 * is read here and nowhere else, matching how {@code AiClientConfig} owns {@code app.ai.*}.
 *
 * <p><strong>Why Caffeine rather than a map.</strong> Caffeine offers per-key atomic loading, so a
 * hundred concurrent requests for the same uncached match can produce <em>one</em> model call and
 * ninety-nine waiters. {@code ConcurrentMapCacheManager} has no such lock. That protection is not
 * automatic — it applies only where {@code @Cacheable(sync = true)} opts into the loader, which is
 * why {@code CachingMatchExplainer} sets it. Caffeine is also bounded, where an unbounded map keyed
 * by candidate × job × version is an OOM on a delay fuse.
 *
 * <p><strong>Why Spring's {@link CacheManager} rather than our own interface.</strong> {@code
 * CacheManager} <em>is</em> the port. Swapping Caffeine for Redis later is a starter dependency and a
 * property, with zero code change. Hand-rolling an interface would satisfy dependency inversion while
 * violating DRY by reimplementing a framework abstraction we would then have to maintain.
 */
@Slf4j
@Configuration
@EnableCaching
public class MatchExplanationCacheConfig {

    /**
     * With the capability off this degrades to exactly what a model outage already produces — the
     * rule scorer's own wording on a plain surface — so the flag reports the truth rather than
     * describing a switch that does nothing. Resolved once at startup, not per request.
     */
    @Bean
    public MatchExplainer matchExplainer(AiCapabilities aiCapabilities,
                                         ChatCompletionClient chatCompletionClient,
                                         ObjectMapper objectMapper,
                                         Validator validator,
                                         AiTaskBudget aiTaskBudget) {
        if (!aiCapabilities.matchExplanation()) {
            log.info("Match explanation is off — matches will show the deterministic reason only");
            return new DisabledMatchExplainer();
        }
        return new CachingMatchExplainer(
                new AiMatchExplainer(chatCompletionClient, objectMapper, validator, aiTaskBudget));
    }

    /**
     * @param maximumEntries    bound on candidate × job × version keys held at once
     * @param generatedTtl      how long a real model-written explanation stays usable
     * @param fallbackTtl       how long a rule-based fallback is reused before trying the model again.
     *                          Short on purpose: this is the negative cache, and its whole job is to
     *                          stop an outage becoming a rate-limit exhaustion while still letting a
     *                          recovered provider be noticed within about a minute.
     */
    /**
     * Primary because Story 14.1 adds a second {@link CacheManager} with its own policy, and
     * {@code CachingMatchExplainer}'s {@code @Cacheable} names no manager. Marking this one rather
     * than qualifying that annotation keeps the older, more heavily tested class untouched.
     */
    @Bean
    @Primary
    public CacheManager cacheManager(
            @Value("${app.match.explanation.cache-max-entries:10000}") long maximumEntries,
            @Value("${app.match.explanation.cache-ttl-minutes:30}") long generatedTtlMinutes,
            @Value("${app.match.explanation.cache-fallback-ttl-seconds:60}") long fallbackTtlSeconds) {

        Duration generatedTtl = Duration.ofMinutes(generatedTtlMinutes);
        Duration fallbackTtl = Duration.ofSeconds(fallbackTtlSeconds);
        log.info("Match explanation cache: max {} entries, generated kept {}, fallbacks kept {}",
                maximumEntries, generatedTtl, fallbackTtl);

        CaffeineCacheManager cacheManager = new CaffeineCacheManager(CachingMatchExplainer.CACHE_NAME);
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(maximumEntries)
                .expireAfter(twoSpeedExpiry(generatedTtl, fallbackTtl)));
        return cacheManager;
    }

    /**
     * One cache, two lifetimes, chosen by what is being stored. A fixed {@code expireAfterWrite} could
     * not do this: it would either keep a fallback for half an hour — so a provider that recovered in
     * seconds still served rule wording to everyone — or expire real explanations every minute and pay
     * for them again.
     */
    private Expiry<Object, Object> twoSpeedExpiry(Duration generatedTtl, Duration fallbackTtl) {
        return new Expiry<>() {
            @Override
            public long expireAfterCreate(Object key, Object value, long currentTime) {
                return timeToLiveFor(value);
            }

            @Override
            public long expireAfterUpdate(Object key, Object value, long currentTime,
                                          long currentDuration) {
                return timeToLiveFor(value);
            }

            @Override
            public long expireAfterRead(Object key, Object value, long currentTime,
                                        long currentDuration) {
                // Deliberately not sliding: an explanation is stale relative to when it was written,
                // not to when it was last looked at.
                return currentDuration;
            }

            private long timeToLiveFor(Object value) {
                boolean generated = value instanceof MatchExplanation explanation && explanation.generated();
                return (generated ? generatedTtl : fallbackTtl).toNanos();
            }
        };
    }
}
