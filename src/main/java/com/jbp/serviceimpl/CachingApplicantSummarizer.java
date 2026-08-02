package com.jbp.serviceimpl;

import com.jbp.dto.ApplicantSummary;
import com.jbp.service.ApplicantSummarizer;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;

/**
 * One model call per applicant per version of their match — the acceptance criterion's
 * "cached per (application, profileVersion)".
 *
 * <p><strong>Keyed on {@code scoreVersion}, which is more than the criterion asks for.</strong> A
 * bare profile version would leave a summary standing after the <em>job</em> was edited, and the read
 * is written against the job as much as the profile — "the core of what this role does day to day" is
 * a claim about the posting. {@code ScoreVersion} already fingerprints the profile, the job, the score
 * and the full factor breakdown, so it invalidates on every input this summary has, and it is the
 * mechanism Story 13.5 already uses rather than a second one that could disagree with it.
 *
 * <p>A decorator rather than an annotation on the service, for the reason Story 13.5 established:
 * Spring's caching proxy is bypassed by self-invocation, so a {@code @Cacheable} method a service
 * calls on itself caches nothing, silently, and the only symptom is a provider bill.
 *
 * <p><strong>Failures are not cached, and the mechanism is the throw rather than a filter.</strong>
 * {@code sync = true} does not support {@code unless}, so a failure returned as a <em>value</em>
 * would be stored and design 24 B2's "Try again" could never succeed. {@link ApplicantSummarizer}
 * therefore throws instead, and Caffeine's atomic loader stores nothing when the loader throws —
 * the same arrangement {@link CachingInterviewQuestionGenerator} relies on.
 *
 * <p>That is also what settles the designer's open conditional: a negative cache would make retrying
 * futile inside its window, at which point the silent server-side bound would become a lie and 21b
 * D2's countdown would be owed. There is no negative cache, so nothing is owed.
 *
 * <p><strong>Declines are cached, and failures are not, because they are not the same thing.</strong>
 * A decline is a fact about the profile: it keeps being true until the candidate edits it, at which
 * point {@code scoreVersion} changes and the key moves anyway. Re-asking the model to decline again
 * would spend a call to be told what we already know.
 */
@RequiredArgsConstructor
public class CachingApplicantSummarizer implements ApplicantSummarizer {

    public static final String CACHE_NAME = "applicantSummaries";
    public static final String CACHE_MANAGER = "applicantSummaryCacheManager";

    private final ApplicantSummarizer delegate;

    /*
     * sync = true because "one call per applicant" has to hold for a recruiter who double-clicks and
     * for two recruiters on the same pipeline, not only for arrivals after the first call finished.
     * Without it Spring does get-miss-invoke-put and every concurrent miss reaches the model.
     *
     * Method-call syntax because SpEL resolves properties through getter conventions, which records
     * do not follow.
     */
    @Override
    @Cacheable(cacheNames = CACHE_NAME, cacheManager = CACHE_MANAGER, sync = true,
            key = "#brief.applicationId() + ':' + #brief.scoreVersion()")
    public ApplicantSummary summarise(ApplicantBrief brief) {
        return delegate.summarise(brief);
    }
}
