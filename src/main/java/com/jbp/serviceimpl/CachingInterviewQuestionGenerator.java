package com.jbp.serviceimpl;

import com.jbp.service.InterviewQuestionGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;

/**
 * One model call per job posting, not one per viewer — the acceptance criterion, and the claim the UI
 * makes out loud: <em>"Same questions for every candidate — cached per job."</em>
 *
 * <p>A decorator rather than an annotation on the service, for the reason Story 13.5 established:
 * Spring's caching proxy is bypassed by self-invocation, so a {@code @Cacheable} method a service
 * calls on itself caches nothing, silently, and the only symptom is a provider bill.
 *
 * <p><strong>Failures are not cached, and that is deliberate.</strong> The obvious addition — a short
 * negative window like Story 13.5's — would have to be bypassed by design 21b's "Try again", and a
 * bypass reopens exactly the stampede the window was meant to close. The protection the designer
 * specified is instead a bound on the human: one retry per viewer, then a countdown. That is client
 * state, so it belongs in the client, and a thrown failure here simply is not cached.
 *
 * <p>Named cache manager because Story 13.5's has a two-speed expiry keyed on <em>its</em> value
 * type; anything else placed in it would silently inherit the 60-second fallback lifetime.
 */
@RequiredArgsConstructor
public class CachingInterviewQuestionGenerator implements InterviewQuestionGenerator {

    public static final String CACHE_NAME = "interviewQuestions";
    public static final String CACHE_MANAGER = "interviewPrepCacheManager";

    private final InterviewQuestionGenerator delegate;

    /**
     * Keyed on the brief's own fingerprint, so an edited posting misses the cache and a re-published
     * identical one hits it. Method-call syntax because SpEL resolves properties through getter
     * conventions, which records do not follow.
     */
    @Override
    /*
     * sync = true because "one call per job" has to hold for viewers who arrive together, not just
     * for ones who arrive after the first has finished. Without it Spring does get-miss-invoke-put
     * and every concurrent miss reaches the model — which is exactly when a brand-new posting is
     * most likely to be opened by several people at once.
     *
     * Failures remain uncached: Caffeine's atomic loader stores nothing when the loader throws, and
     * Spring rethrows the original exception rather than a wrapper, so 21b's state D still works.
     */
    @Cacheable(cacheNames = CACHE_NAME, cacheManager = CACHE_MANAGER, sync = true,
            key = "#brief.cacheKey()")
    public InterviewQuestions generate(JobBrief brief) {
        return delegate.generate(brief);
    }
}
