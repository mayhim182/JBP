package com.jbp.util;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Counts outbound calls inside a sliding one-minute window and reports whether one more is
 * allowed.
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
 * two adjacent windows from sending twice the limit.
 *
 * <p>Whether two transports share one instance or hold one each is decided entirely by the
 * wiring in {@code AiClientConfig}, and that is the point of taking the limiter as a
 * collaborator: a shared provider quota and a per-model quota are the same code and different
 * wiring. Counting is per instance and therefore per application node, which matches the
 * single-node MVP deployment; a multi-node deployment would need the timestamps held somewhere
 * shared.
 */
public class CallRateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final int maxCallsPerWindow;
    private final Clock clock;
    private final Deque<Long> callTimestamps = new ArrayDeque<>();

    public CallRateLimiter(int maxCallsPerWindow, Clock clock) {
        this.maxCallsPerWindow = maxCallsPerWindow;
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

    /** Exposed so a refusal message can name the limit it hit without restating the number. */
    public int maxCallsPerWindow() {
        return maxCallsPerWindow;
    }

    private void discardCallsOlderThanWindow(long now) {
        long windowStart = now - WINDOW.toMillis();
        while (!callTimestamps.isEmpty() && callTimestamps.peekFirst() <= windowStart) {
            callTimestamps.pollFirst();
        }
    }
}
