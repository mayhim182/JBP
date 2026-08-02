package com.jbp.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;

/**
 * How many screening answers one candidate may have drafted inside a rolling window — Story 14.2's
 * per-user allowance, and the only thing standing between this endpoint and free text generation for
 * anyone with an account.
 *
 * <p>Per candidate, each with their own {@link CallRateLimiter}, so one heavy user cannot spend
 * anybody else's allowance. The window slides rather than resetting at midnight: a calendar day would
 * allow ten drafts at 23:59 and ten more a minute later, and would need a timezone chosen for every
 * user on a platform whose candidates are not all in one.
 *
 * <p><strong>Counted in memory, and therefore per application node.</strong> Two consequences,
 * accepted deliberately at this size and stated here rather than discovered later: a restart refills
 * everyone's allowance, and a second node would double the effective limit. The same caveat
 * {@link CallRateLimiter} already carries for provider quotas. A database table for this is premature
 * — it would add a write to every draft to protect a cost that a restart already forgives.
 *
 * <p>The limiters are held in a Caffeine cache expiring after the window rather than a map that grows
 * forever. Eviction is <strong>free of consequence by construction</strong>: a limiter untouched for
 * a whole window has had every one of its timestamps age out already, so discarding it and creating a
 * fresh one are the same thing. Size-based eviction under pressure can refill an active user early,
 * which is the same class of caveat as the restart above.
 */
public class DraftAnswerBudget {

    private final int maxDraftsPerWindow;
    private final Duration window;
    private final Clock clock;
    private final Cache<Long, CallRateLimiter> limitersByCandidateId;

    public DraftAnswerBudget(int maxDraftsPerWindow, Duration window, int maxTrackedCandidates, Clock clock) {
        this.maxDraftsPerWindow = maxDraftsPerWindow;
        this.window = window;
        this.clock = clock;
        this.limitersByCandidateId = Caffeine.newBuilder()
                .maximumSize(maxTrackedCandidates)
                .expireAfterAccess(window)
                .build();
    }

    /**
     * Takes one draft from this candidate's allowance, or reports that it is spent.
     *
     * <p>Reserved before the model is called, not after it succeeds, so two requests in flight at
     * once cannot both take the last slot. A call that then fails gives the slot back through
     * {@link #refundDraft} — see Story 14.2's rule that a failed attempt costs nothing.
     */
    public boolean tryReserveDraft(Long candidateId) {
        return limiterFor(candidateId).tryReserveCallSlot();
    }

    /** Returns a reserved slot after an attempt that must not be charged for. */
    public void refundDraft(Long candidateId) {
        limiterFor(candidateId).releaseMostRecentCallSlot();
    }

    /**
     * Drafts this candidate has left. Reported to the client on every successful draft, because
     * design 22b's D2 banner shows the number at two remaining and below and the client has no other
     * way to know it.
     */
    public int remainingDrafts(Long candidateId) {
        return limiterFor(candidateId).remainingCallSlots();
    }

    public int maxDraftsPerWindow() {
        return maxDraftsPerWindow;
    }

    private CallRateLimiter limiterFor(Long candidateId) {
        return limitersByCandidateId.get(candidateId,
                id -> new CallRateLimiter(maxDraftsPerWindow, window, clock));
    }
}
