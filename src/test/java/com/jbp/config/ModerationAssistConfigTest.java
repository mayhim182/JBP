package com.jbp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.model.JobType;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.JobModerationAssistant;
import com.jbp.service.JobModerationAssistant.JobModerationBrief;
import com.jbp.serviceimpl.AiJobModerationAssistant;
import com.jbp.serviceimpl.DisabledChatClient;
import com.jbp.serviceimpl.DisabledJobModerationAssistant;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.5 — the capability flag decides which assistant exists, once, at startup.
 *
 * <p>Resolving it here rather than at each call site is what makes "no chips with AI off" structural:
 * with the disabled bean in play nothing downstream can write a risk level, whatever the queue is later
 * edited to render.
 */
class ModerationAssistConfigTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    private final ModerationAssistConfig config = new ModerationAssistConfig();

    @AfterAll
    static void releaseValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void buildsTheRealAssistantWhenTheCapabilityIsOn() {
        assertThat(assistantWith(true)).isInstanceOf(AiJobModerationAssistant.class);
    }

    @Test
    void buildsTheDisabledAssistantWhenTheCapabilityIsOff() {
        assertThat(assistantWith(false)).isInstanceOf(DisabledJobModerationAssistant.class);
    }

    /**
     * The switched-off bean returns empty rather than throwing, unlike Story 14.4's drafter. Nothing is
     * waiting on this answer — it runs from a listener, after the recruiter's submit has returned.
     */
    @Test
    void theDisabledAssistantLeavesEveryJobUnassessed() {
        assertThat(assistantWith(false).assess(brief())).isEmpty();
    }

    /**
     * A capability can never be on while AI as a whole is off, so this pairing is unreachable through
     * {@link AiClientConfig} — asserted anyway, because it is the one combination that would put the
     * real assistant behind a client that cannot answer.
     */
    @Test
    void theRealAssistantStillLeavesJobsUnassessedWhenTheProviderIsSwitchedOff() {
        assertThat(assistantWith(true).assess(brief())).isEmpty();
    }

    private JobModerationAssistant assistantWith(boolean moderationAssist) {
        return config.jobModerationAssistant(
                capabilitiesWithModerationAssist(moderationAssist),
                new DisabledChatClient(),
                new ObjectMapper(),
                VALIDATOR,
                new AiTaskBudget(1_000));
    }

    /** Every capability on except the one under test, named rather than positional. */
    private AiCapabilities capabilitiesWithModerationAssist(boolean enabled) {
        return new AiCapabilities(true, true, true, true, true, true, enabled);
    }

    private JobModerationBrief brief() {
        return new JobModerationBrief(
                "Warehouse Associate",
                "Immediate start, no experience needed.",
                Set.of("Packing"),
                SeniorityLevel.JUNIOR,
                JobType.FULL_TIME,
                null,
                null);
    }
}
