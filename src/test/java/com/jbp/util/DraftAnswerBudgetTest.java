package com.jbp.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.2 — the per-candidate allowance. The window arithmetic itself is
 * {@link CallRateLimiterTest}'s subject; what matters here is that the allowance is genuinely
 * per candidate and that a refund gives one back.
 */
class DraftAnswerBudgetTest {

    private static final int LIMIT = 3;
    private static final Long CANDIDATE = 7L;
    private static final Long ANOTHER_CANDIDATE = 8L;

    private final ControllableClock clock = new ControllableClock();
    private final DraftAnswerBudget budget =
            new DraftAnswerBudget(LIMIT, Duration.ofHours(24), 1_000, clock);

    @Test
    void allowsEachCandidateTheirOwnAllowance() {
        for (int draft = 0; draft < LIMIT; draft++) {
            budget.tryReserveDraft(CANDIDATE);
        }

        assertThat(budget.tryReserveDraft(CANDIDATE)).isFalse();
        assertThat(budget.tryReserveDraft(ANOTHER_CANDIDATE))
                .as("one heavy user must not spend anybody else's drafts")
                .isTrue();
    }

    @Test
    void reportsWhatIsLeftSoTheBannerCanNameIt() {
        assertThat(budget.remainingDrafts(CANDIDATE)).isEqualTo(LIMIT);

        budget.tryReserveDraft(CANDIDATE);

        assertThat(budget.remainingDrafts(CANDIDATE)).isEqualTo(LIMIT - 1);
    }

    /**
     * The rule design 22b F's copy makes a promise about: "a failed attempt doesn't use up one of
     * your drafts". A candidate told "limit reached" after three provider outages would have been
     * lied to.
     */
    @Test
    void givesTheDraftBackWhenTheAttemptIsNotCharged() {
        for (int draft = 0; draft < LIMIT; draft++) {
            budget.tryReserveDraft(CANDIDATE);
        }
        assertThat(budget.remainingDrafts(CANDIDATE)).isZero();

        budget.refundDraft(CANDIDATE);

        assertThat(budget.remainingDrafts(CANDIDATE)).isEqualTo(1);
        assertThat(budget.tryReserveDraft(CANDIDATE)).isTrue();
    }

    @Test
    void refillsTheAllowanceOnceTheWindowHasPassed() {
        for (int draft = 0; draft < LIMIT; draft++) {
            budget.tryReserveDraft(CANDIDATE);
        }

        clock.advanceMillis(Duration.ofHours(24).toMillis());

        assertThat(budget.remainingDrafts(CANDIDATE)).isEqualTo(LIMIT);
    }

    /**
     * A rolling window rather than a calendar day: ten drafts at 23:59 must not be followed by ten
     * more a minute later.
     */
    @Test
    void doesNotRefillPartWayThroughTheWindow() {
        for (int draft = 0; draft < LIMIT; draft++) {
            budget.tryReserveDraft(CANDIDATE);
        }

        clock.advanceMillis(Duration.ofHours(23).toMillis());

        assertThat(budget.tryReserveDraft(CANDIDATE)).isFalse();
    }

    @Test
    void reportsTheConfiguredAllowance() {
        assertThat(budget.maxDraftsPerWindow()).isEqualTo(LIMIT);
    }
}
