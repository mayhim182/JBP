package com.jbp.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.MatchExplainer;
import com.jbp.service.MatchExplainer.MatchExplanation;
import com.jbp.serviceimpl.AiMatchExplainer;
import com.jbp.serviceimpl.CachingMatchExplainer;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Assembles the match explainer and the cache in front of it. Every {@code app.match.explanation.*} key
 * is read here and nowhere else, matching how {@code AiClientConfig} owns {@code app.ai.*}.
 *
 * <p><strong>Why Caffeine rather than a map.</strong> Caffeine gives per-key atomic loading: a hundred
 * concurrent requests for the same uncached match produce <em>one</em> model call and ninety-nine
 * waiters. {@code ConcurrentMapCacheManager} has no such lock, so the same burst produces a hundred
 * calls against a rate-limited provider — a stampede on the very call this is meant to protect. It is
 * also bounded, where an unbounded map keyed by candidate × job × version is an OOM on a delay fuse.
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

    @Bean
    public MatchExplainer matchExplainer(ChatCompletionClient chatCompletionClient,
                                         ObjectMapper objectMapper,
                                         Validator validator,
                                         AiTaskBudget aiTaskBudget) {
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
    @Bean
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
