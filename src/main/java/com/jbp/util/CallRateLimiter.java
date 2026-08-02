package com.jbp.util;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Counts calls inside a sliding window and reports whether one more is allowed.
 *
 * <p>Extracted from {@code RateLimitedChatClient} when Story 13.1 added a second transport that
 * needs the identical rule. The window algorithm now exists once; each transport decorator is
 * left with nothing but "which interface am I limiting", which is the only part that genuinely
 * differs between them.
 *
 * <p>Deliberately reports rather than throws. The decorators translate a refusal into the
 * {@code LlmUnavailableException} their callers already handle, and that keeps this class free of
 * any knowledge of what a model call is — it counts events, so it is equally usable for any future
 * quota-bearing dependency.
 *
 * <p>The window slides rather than resetting on a fixed boundary, which stops a burst spanning
 * two adjacent windows from sending twice the limit. That property is why Story 14.2 reuses this
 * class for a per-candidate daily draft budget rather than writing a calendar-day counter: a
 * calendar day would let ten drafts at 23:59 be followed by ten more a minute later, and would need
 * a timezone chosen for every user. The window is a constructor parameter for exactly that reason —
 * one minute for a provider quota, twenty-four hours for a person's daily allowance.
 *
 * <p>Whether two transports share one instance or hold one each is decided entirely by the
 * wiring in {@code AiClientConfig}, and that is the point of taking the limiter as a
 * collaborator: a shared provider quota and a per-model quota are the same code and different
 * wiring. Counting is per instance and therefore per application node, which matches the
 * single-node MVP deployment; a multi-node deployment would need the timestamps held somewhere
 * shared.
 */
public class CallRateLimiter {

    private final int maxCallsPerWindow;
    private final Duration window;
    private final Clock clock;
    private final Deque<Long> callTimestamps = new ArrayDeque<>();

    public CallRateLimiter(int maxCallsPerWindow, Duration window, Clock clock) {
        this.maxCallsPerWindow = maxCallsPerWindow;
        this.window = window;
        this.clock = clock;
    }

    /**
     * Records a call against the current window, or reports that the window is full.
     * Synchronized because concurrent requests share the timestamp history.
     *
     * @return true if the caller may proceed, false if the limit is already reached
     */
    public synchronized boolean tryReserveCallSlot() {
        long now = clock.millis();
        discardCallsOlderThanWindow(now);
        if (callTimestamps.size() >= maxCallsPerWindow) {
            return false;
        }
        callTimestamps.addLast(now);
        return true;
    }

    /**
     * Hands back the slot this caller most recently reserved, for a call that did not happen or
     * failed in a way the caller must not be charged for.
     *
     * <p>Story 14.2's rule that a failed draft must not consume a candidate's daily allowance: a
     * provider outage would otherwise spend the allowance on nothing, with no way for them to tell
     * that is what happened. Reserve-then-refund rather than check-then-record, because a bare check
     * lets two concurrent calls both see the last free slot and both take it.
     *
     * <p>Removes the newest timestamp, which is this caller's own unless the same limiter took
     * another reservation in between. It counts events rather than identifying them, so returning
     * one of two near-simultaneous timestamps leaves the count correct either way.
     */
    public synchronized void releaseMostRecentCallSlot() {
        callTimestamps.pollLast();
    }

    /**
     * How many more calls the window has room for right now. Reported rather than derived by the
     * caller, which would mean a second copy of the window arithmetic.
     */
    public synchronized int remainingCallSlots() {
        discardCallsOlderThanWindow(clock.millis());
        return Math.max(0, maxCallsPerWindow - callTimestamps.size());
    }

    /** Exposed so a refusal message can name the limit it hit without restating the number. */
    public int maxCallsPerWindow() {
        return maxCallsPerWindow;
    }

    private void discardCallsOlderThanWindow(long now) {
        long windowStart = now - window.toMillis();
        while (!callTimestamps.isEmpty() && callTimestamps.peekFirst() <= windowStart) {
            callTimestamps.pollFirst();
        }
    }
}
