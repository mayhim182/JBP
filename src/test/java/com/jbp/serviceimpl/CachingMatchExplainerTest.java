package com.jbp.serviceimpl;

import com.jbp.config.MatchExplanationCacheConfig;
import com.jbp.service.MatchExplainer;
import com.jbp.service.MatchExplainer.MatchExplanation;
import com.jbp.service.MatchExplainer.MatchExplanationInput;
import com.jbp.service.MatchExplainer.SkillDemand;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.5 — the cache in front of the highest-volume model call in the app.
 *
 * <p>Run through a real Spring context rather than by calling the class directly, because
 * {@code @Cacheable} does nothing at all without the proxy. A unit test that constructed
 * {@code CachingMatchExplainer} with {@code new} would pass while caching nothing, and the only symptom
 * in production would be a provider bill.
 */
class CachingMatchExplainerTest {

    private static final long MAX_ENTRIES = 100;
    private static final long GENERATED_TTL_MINUTES = 30;
    private static final long FALLBACK_TTL_SECONDS = 60;

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(CachingTestConfig.class);

    @Test
    void asksTheModelOnceForRepeatedRequestsAtTheSameScoreVersion() {
        contextRunner.run(context -> {
            MatchExplainer explainer = context.getBean(MatchExplainer.class);
            CountingExplainer delegate = context.getBean(CountingExplainer.class);

            explainer.explain(input("v1"));
            explainer.explain(input("v1"));
            explainer.explain(input("v1"));

            assertThat(delegate.calls)
                    .as("a hundred concurrent viewers of one match must not be a hundred model calls")
                    .isEqualTo(1);
        });
    }

    @Test
    void asksAgainOnceTheScoreVersionMoves() {
        contextRunner.run(context -> {
            MatchExplainer explainer = context.getBean(MatchExplainer.class);
            CountingExplainer delegate = context.getBean(CountingExplainer.class);

            explainer.explain(input("v1"));
            explainer.explain(input("v2"));

            assertThat(delegate.calls)
                    .as("the version changed, so the cached prose describes a score no longer on screen")
                    .isEqualTo(2);
        });
    }

    @Test
    void keepsAFallbackOnlyBrieflySoARecoveredProviderIsNoticed() {
        contextRunner.run(context -> {
            context.getBean(CountingExplainer.class).generated = false;
            context.getBean(MatchExplainer.class).explain(input("v1"));

            assertThat(remainingLifeOf(context.getBean(CacheManager.class), "1:2:v1"))
                    .as("without a short negative TTL an outage becomes rate-limit exhaustion, and "
                            + "with a long one a provider that recovered in seconds still serves "
                            + "rule wording to everybody")
                    .isLessThanOrEqualTo(Duration.ofSeconds(FALLBACK_TTL_SECONDS))
                    .isGreaterThan(Duration.ofSeconds(FALLBACK_TTL_SECONDS - 10));
        });
    }

    @Test
    void keepsAGenuineExplanationForMuchLonger() {
        contextRunner.run(context -> {
            context.getBean(CountingExplainer.class).generated = true;
            context.getBean(MatchExplainer.class).explain(input("v1"));

            assertThat(remainingLifeOf(context.getBean(CacheManager.class), "1:2:v1"))
                    .isGreaterThan(Duration.ofMinutes(GENERATED_TTL_MINUTES - 1));
        });
    }

    private Duration remainingLifeOf(CacheManager cacheManager, String key) {
        CaffeineCache cache = (CaffeineCache) cacheManager.getCache(CachingMatchExplainer.CACHE_NAME);
        return cache.getNativeCache().policy().expireVariably()
                .orElseThrow(() -> new AssertionError("the cache is not using a variable expiry"))
                .getExpiresAfter(key)
                .orElseThrow(() -> new AssertionError("nothing was cached under " + key));
    }

    private MatchExplanationInput input(String scoreVersion) {
        return new MatchExplanationInput(1L, 2L, scoreVersion, "Senior Frontend Engineer", 70,
                "skills 1/2", List.of(), Set.of(), Set.of(), null, () -> new SkillDemand(0, 0));
    }

    @Configuration
    @EnableCaching
    static class CachingTestConfig {

        @Bean
        CacheManager cacheManager() {
            return new MatchExplanationCacheConfig()
                    .cacheManager(MAX_ENTRIES, GENERATED_TTL_MINUTES, FALLBACK_TTL_SECONDS);
        }

        @Bean
        CountingExplainer countingExplainer() {
            return new CountingExplainer();
        }

        /**
         * Primary because the counting delegate is also a {@code MatchExplainer}, so without it the
         * context has two candidates. Production has exactly one — the decorator — so this restores the
         * real arrangement rather than papering over an ambiguity that exists in the application.
         */
        @Bean
        @Primary
        MatchExplainer matchExplainer(CountingExplainer delegate) {
            return new CachingMatchExplainer(delegate);
        }
    }

    /** Counts what actually reached the model side of the decorator. */
    static class CountingExplainer implements MatchExplainer {

        private int calls;
        private boolean generated = true;

        @Override
        public MatchExplanation explain(MatchExplanationInput input) {
            calls++;
            return new MatchExplanation("Reads close to the role.", null, null, generated);
        }
    }
}
