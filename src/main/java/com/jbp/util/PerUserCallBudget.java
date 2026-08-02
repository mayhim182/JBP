package com.jbp.util;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Clock;
import java.time.Duration;

/**
 * How many calls one user may make inside a rolling window, counted per user.
 *
 * <p>Generalised out of Story 14.2's draft allowance when Story 14.3 needed the same mechanism for a
 * different reason, and the two reasons are worth keeping distinct even though the code is one:
 * <ul>
 *   <li><strong>A budget</strong> — 14.2's ten drafts a day. Low, reachable by honest use, and
 *       therefore surfaced to the candidate, who is told what they have left.</li>
 *   <li><strong>A ceiling</strong> — 14.3's thirty summaries a minute. Far above anything a human
 *       triaging applicants can reach, so it is never surfaced: it exists to stop a retry loop or a
 *       script, and a recruiter who cannot reach it should never be made to think about it.</li>
 * </ul>
 * Same counting, opposite relationships to the person being counted, which is why the numbers and the
 * windows are configuration rather than constants here.
 *
 * <p>Each user gets their own {@link CallRateLimiter}, so one heavy user cannot spend anybody else's
 * allowance. The window slides rather than resetting on a boundary — a calendar day would let a
 * whole allowance be spent at 23:59 and spent again a minute later, and would need a timezone chosen
 * for every user.
 *
 * <p><strong>Counted in memory, and therefore per application node.</strong> Two consequences,
 * accepted deliberately at this size and stated here rather than discovered later: a restart refills
 * everyone, and a second node would double the effective limit. The same caveat
 * {@link CallRateLimiter} already carries for provider quotas. A table would add a write to every
 * call to protect a cost a restart already forgives.
 *
 * <p>The limiters live in a Caffeine cache expiring after the window rather than a map that grows
 * forever. Eviction is <strong>free of consequence by construction</strong>: a limiter untouched for
 * a whole window has had every one of its timestamps age out already, so discarding it and creating a
 * fresh one are the same thing. Size-based eviction under pressure can refill an active user early,
 * which is the same class of caveat as the restart above.
 */
public class PerUserCallBudget {

    private final int maxCallsPerWindow;
    private final Duration window;
    private final Clock clock;
    private final Cache<Long, CallRateLimiter> limitersByUserId;

    public PerUserCallBudget(int maxCallsPerWindow, Duration window, int maxTrackedUsers, Clock clock) {
        this.maxCallsPerWindow = maxCallsPerWindow;
        this.window = window;
        this.clock = clock;
        this.limitersByUserId = Caffeine.newBuilder()
                .maximumSize(maxTrackedUsers)
                .expireAfterAccess(window)
                .build();
    }

    /**
     * Takes one call from this user's allowance, or reports that it is spent.
     *
     * <p>Reserved before the work is attempted rather than recorded after it succeeds, so two requests
     * in flight at once cannot both take the last slot. A caller whose attempt must not be charged for
     * gives the slot back through {@link #refundCall}.
     */
    public boolean tryReserveCall(Long userId) {
        return limiterFor(userId).tryReserveCallSlot();
    }

    /** Returns a reserved slot after an attempt that must not be charged — a failure, or a decline. */
    public void refundCall(Long userId) {
        limiterFor(userId).releaseMostRecentCallSlot();
    }

    /** Calls this user has left. Only meaningful to surface for a budget, never for a ceiling. */
    public int remainingCalls(Long userId) {
        return limiterFor(userId).remainingCallSlots();
    }

    public int maxCallsPerWindow() {
        return maxCallsPerWindow;
    }

    private CallRateLimiter limiterFor(Long userId) {
        return limitersByUserId.get(userId, id -> new CallRateLimiter(maxCallsPerWindow, window, clock));
    }
}
