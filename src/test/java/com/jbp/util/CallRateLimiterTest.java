package com.jbp.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sliding window itself, tested once. The transport decorators that use it are then only
 * responsible for translating a refusal into the failure their callers expect, which is what their
 * own tests check.
 */
class CallRateLimiterTest {

    private static final int LIMIT = 3;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ControllableClock clock = new ControllableClock();
    private final CallRateLimiter rateLimiter = new CallRateLimiter(LIMIT, WINDOW, clock);

    @Test
    void allowsCallsUpToTheConfiguredLimit() {
        for (int call = 0; call < LIMIT; call++) {
            assertThat(rateLimiter.tryReserveCallSlot()).isTrue();
        }
    }

    @Test
    void refusesTheCallThatWouldExceedTheLimit() {
        for (int call = 0; call < LIMIT; call++) {
            rateLimiter.tryReserveCallSlot();
        }

        assertThat(rateLimiter.tryReserveCallSlot()).isFalse();
    }

    @Test
    void allowsCallsAgainOnceTheOldestOnesFallOutsideTheWindow() {
        for (int call = 0; call < LIMIT; call++) {
            rateLimiter.tryReserveCallSlot();
        }

        clock.advanceMillis(60_000);

        assertThat(rateLimiter.tryReserveCallSlot()).isTrue();
    }

    @Test
    void keepsRefusingWhileTheWindowHasNotFullyElapsed() {
        for (int call = 0; call < LIMIT; call++) {
            rateLimiter.tryReserveCallSlot();
        }

        clock.advanceMillis(59_999);

        assertThat(rateLimiter.tryReserveCallSlot()).isFalse();
    }

    @Test
    void slidesRatherThanResettingSoABurstAcrossTwoWindowsCannotSendDoubleTheLimit() {
        rateLimiter.tryReserveCallSlot();
        rateLimiter.tryReserveCallSlot();

        // Far enough that a fixed-window implementation would have reset, but the two calls above
        // are still inside the last minute, so only one slot may remain.
        clock.advanceMillis(30_000);

        assertThat(rateLimiter.tryReserveCallSlot()).isTrue();
        assertThat(rateLimiter.tryReserveCallSlot()).isFalse();
    }

    @Test
    void reportsTheConfiguredLimitSoARefusalCanNameIt() {
        assertThat(rateLimiter.maxCallsPerWindow()).isEqualTo(LIMIT);
    }

    /**
     * Story 14.2 needs a window measured in hours rather than the minute the provider quotas use.
     * Asserted against a window this test chooses, so the class cannot quietly go back to a constant.
     */
    @Test
    void honoursAWindowLongerThanAMinute() {
        CallRateLimiter dailyLimiter = new CallRateLimiter(LIMIT, Duration.ofHours(24), clock);
        for (int call = 0; call < LIMIT; call++) {
            dailyLimiter.tryReserveCallSlot();
        }

        clock.advanceMillis(Duration.ofHours(23).toMillis());
        assertThat(dailyLimiter.tryReserveCallSlot())
                .as("still inside the 24-hour window")
                .isFalse();

        clock.advanceMillis(Duration.ofHours(1).toMillis());
        assertThat(dailyLimiter.tryReserveCallSlot()).isTrue();
    }

    @Test
    void reportsHowManySlotsAreLeft() {
        assertThat(rateLimiter.remainingCallSlots()).isEqualTo(LIMIT);

        rateLimiter.tryReserveCallSlot();

        assertThat(rateLimiter.remainingCallSlots()).isEqualTo(LIMIT - 1);
    }

    @Test
    void reportsNoSlotsLeftRatherThanANegativeNumber() {
        for (int call = 0; call < LIMIT + 2; call++) {
            rateLimiter.tryReserveCallSlot();
        }

        assertThat(rateLimiter.remainingCallSlots()).isZero();
    }

    /**
     * Story 14.2's rule that a failed draft costs a candidate nothing. Without this a provider
     * outage would spend a daily allowance on drafts that were never received.
     */
    @Test
    void givesBackASlotForACallThatMustNotBeCharged() {
        for (int call = 0; call < LIMIT; call++) {
            rateLimiter.tryReserveCallSlot();
        }
        assertThat(rateLimiter.tryReserveCallSlot()).isFalse();

        rateLimiter.releaseMostRecentCallSlot();

        assertThat(rateLimiter.tryReserveCallSlot()).isTrue();
    }

    @Test
    void releasingWithNothingReservedIsHarmless() {
        rateLimiter.releaseMostRecentCallSlot();

        assertThat(rateLimiter.remainingCallSlots()).isEqualTo(LIMIT);
    }
}
