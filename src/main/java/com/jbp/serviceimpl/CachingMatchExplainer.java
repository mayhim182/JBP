package com.jbp.serviceimpl;

import com.jbp.service.MatchExplainer;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;

/**
 * Caches explanations per (candidate, job, scoreVersion) — the highest-volume model call in the app.
 *
 * <p>A decorator rather than an annotation on the service, for two reasons. It matches how the AI
 * clients are already assembled ({@code LoggingChatClient(RateLimitedChatClient(…))}), and more
 * importantly Spring's caching proxy is bypassed by self-invocation: a {@code @Cacheable} method that a
 * service calls on itself simply does not cache, silently, and the only symptom is a provider bill.
 * Making the cache its own bean makes that mistake unavailable.
 *
 * <p><strong>Failures are cached too, briefly.</strong> Not by this class — {@code MatchExplainer}
 * never throws, it degrades — but by the cache's expiry rule, which gives an entry that fell back to
 * rules a short life and a genuinely generated one a long one. Without that, a provider outage means
 * every request retries and burns the rate limit, turning degradation into an outage. See
 * {@code MatchExplanationCacheConfig}.
 */
@RequiredArgsConstructor
public class CachingMatchExplainer implements MatchExplainer {

    public static final String CACHE_NAME = "matchExplanations";

    private final MatchExplainer delegate;

    /**
     * Method-call syntax on the record accessors rather than property syntax: SpEL resolves
     * {@code #input.candidateId} through getter conventions, which a record does not follow.
     */
    @Override
    @Cacheable(cacheNames = CACHE_NAME,
            key = "#input.candidateId() + ':' + #input.jobId() + ':' + #input.scoreVersion()")
    public MatchExplanation explain(MatchExplanationInput input) {
        return delegate.explain(input);
    }
}
