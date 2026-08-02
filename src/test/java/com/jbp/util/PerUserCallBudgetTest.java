package com.jbp.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The per-user allowance behind Story 14.2's draft budget and Story 14.3's summary ceiling. The window
 * arithmetic itself is {@link CallRateLimiterTest}'s subject; what matters here is that the allowance
 * is genuinely per user and that a refund gives one back.
 */
class PerUserCallBudgetTest {

    private static final int LIMIT = 3;
    private static final Long USER = 7L;
    private static final Long ANOTHER_USER = 8L;

    private final ControllableClock clock = new ControllableClock();
    private final PerUserCallBudget budget =
            new PerUserCallBudget(LIMIT, Duration.ofHours(24), 1_000, clock);

    @Test
    void allowsEachUserTheirOwnAllowance() {
        for (int call = 0; call < LIMIT; call++) {
            budget.tryReserveCall(USER);
        }

        assertThat(budget.tryReserveCall(USER)).isFalse();
        assertThat(budget.tryReserveCall(ANOTHER_USER))
                .as("one heavy user must not spend anybody else's allowance")
                .isTrue();
    }

    @Test
    void reportsWhatIsLeftSoABudgetCanBeSurfaced() {
        assertThat(budget.remainingCalls(USER)).isEqualTo(LIMIT);

        budget.tryReserveCall(USER);

        assertThat(budget.remainingCalls(USER)).isEqualTo(LIMIT - 1);
    }

    /**
     * The rule design 22b F's copy makes a promise about: "a failed attempt doesn't use up one of
     * your drafts". A candidate told "limit reached" after three provider outages would have been
     * lied to.
     */
    @Test
    void givesTheCallBackWhenTheAttemptIsNotCharged() {
        for (int call = 0; call < LIMIT; call++) {
            budget.tryReserveCall(USER);
        }
        assertThat(budget.remainingCalls(USER)).isZero();

        budget.refundCall(USER);

        assertThat(budget.remainingCalls(USER)).isEqualTo(1);
        assertThat(budget.tryReserveCall(USER)).isTrue();
    }

    @Test
    void refillsTheAllowanceOnceTheWindowHasPassed() {
        for (int call = 0; call < LIMIT; call++) {
            budget.tryReserveCall(USER);
        }

        clock.advanceMillis(Duration.ofHours(24).toMillis());

        assertThat(budget.remainingCalls(USER)).isEqualTo(LIMIT);
    }

    /**
     * A rolling window rather than a calendar day: a whole allowance spent at 23:59 must not be
     * spendable again a minute later.
     */
    @Test
    void doesNotRefillPartWayThroughTheWindow() {
        for (int call = 0; call < LIMIT; call++) {
            budget.tryReserveCall(USER);
        }

        clock.advanceMillis(Duration.ofHours(23).toMillis());

        assertThat(budget.tryReserveCall(USER)).isFalse();
    }

    @Test
    void reportsTheConfiguredAllowance() {
        assertThat(budget.maxCallsPerWindow()).isEqualTo(LIMIT);
    }
}
