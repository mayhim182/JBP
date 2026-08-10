package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.DraftedRejectionReason;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.Job;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.RejectionReasonDrafter.RejectionBrief;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 14.4 — the drafter, exercised with no network access.
 *
 * <p>⚠️ <strong>The qualitative half of "honest without being cruel" is not testable here.</strong>
 * What a real model writes from this prompt can only be judged by reading it. What these tests can
 * hold is everything that determines whether it <em>could</em> be honest: that the gap reaches the
 * model by name, that the constraints forbidding invention and false comfort are actually in the
 * prompt, and that anything unusable is discarded rather than shown to a recruiter mid-rejection.
 */
class AiRejectionReasonDrafterTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();
    private static final AiTaskBudget GENEROUS_BUDGET = new AiTaskBudget(2_000);

    /** One line on purpose: a raw newline inside a JSON string value is not valid JSON. */
    private static final String USABLE_REPLY =
            "{\"reason\": \"Thank you for applying. This role runs on Kafka and event-streaming "
                    + "operations day to day, and that isn't in your profile yet. Your ledger work is "
                    + "a real strength; it just isn't the gap this role needed closed.\"}";

    @AfterAll
    static void releaseValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void returnsTheDraftWhenTheModelAnswersUsably() {
        DraftedRejectionReason drafted =
                drafterBacked(FakeChatCompletionClient.replyingWith(USABLE_REPLY)).draft(weakMatch());

        assertThat(drafted.wasUnavailable()).isFalse();
        assertThat(drafted.getReason()).contains("Kafka");
    }

    /**
     * Design 26 C4. A blank draft is a failure rather than a decline — unlike Story 14.3, whose input
     * can genuinely be empty. Here the input is the job, so there is always something to write from.
     */
    @Test
    void treatsABlankDraftAsAFailureRatherThanAnAnswer() {
        assertThatThrownBy(() -> drafterBacked(
                FakeChatCompletionClient.replyingWith("{\"reason\": \"\"}")).draft(weakMatch()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void discardsAReplyCarryingKeysItWasNotAskedFor() {
        assertThatThrownBy(() -> drafterBacked(FakeChatCompletionClient.replyingWith(
                "{\"reason\": \"Not this time.\", \"candidateRating\": 2}")).draft(weakMatch()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    /** A model that starts writing a letter is discarded, not pruned into the recruiter's textarea. */
    @Test
    void discardsADraftLongerThanTheTwoOrThreeSentencesAsked() {
        String tooLong = "{\"reason\": \"" + "word ".repeat(200) + "\"}";

        assertThatThrownBy(() -> drafterBacked(
                FakeChatCompletionClient.replyingWith(tooLong)).draft(weakMatch()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void failsWhenTheProviderCannotBeReached() {
        assertThatThrownBy(() -> drafterBacked(
                FakeChatCompletionClient.failingWith(new RuntimeException("connection reset")))
                .draft(weakMatch()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    /** The gap has to arrive by name, or "name the gap" is not something the model could obey. */
    @Test
    void sendsTheRoleAndTheUnevidencedRequirementsByName() {
        String sent = drafterBacked(FakeChatCompletionClient.replyingWith(USABLE_REPLY))
                .renderUserMessage(weakMatch());

        assertThat(sent).contains("Streaming Engineer");
        assertThat(sent).contains("Runs the event pipeline.");
        assertThat(sent).contains("Kafka");
        assertThat(sent).contains("Terraform");
    }

    /**
     * Said out loud rather than left as a missing section. An absent heading reads as missing
     * information and invites the model to fill it; a stated "none" is a fact it has a rule for.
     */
    @Test
    void statesExplicitlyWhenNothingWentUnevidenced() {
        String sent = drafterBacked(FakeChatCompletionClient.replyingWith(USABLE_REPLY))
                .renderUserMessage(new RejectionBrief(job(), List.of()));

        assertThat(sent).contains("Unmet requirements: none");
    }

    /**
     * The constraints the acceptance criteria rest on, asserted as present rather than as effective —
     * a prompt edit that quietly drops one is the failure mode worth catching, and the only one a test
     * without a model can catch.
     */
    @Test
    void carriesTheConstraintsTheAcceptanceCriteriaDependOn() {
        String prompt = drafterBacked(FakeChatCompletionClient.replyingWith(USABLE_REPLY)).systemPrompt();

        assertThat(prompt).contains("No false encouragement");
        assertThat(prompt).contains("never describe their attitude, ambition, motivation, potential");
        assertThat(prompt).contains("private notes");
        assertThat(prompt).contains("Two or three sentences");
        assertThat(prompt).contains("sexual orientation");
        assertThat(prompt).contains("never compare them to other applicants");
    }

    private AiRejectionReasonDrafter drafterBacked(ChatCompletionClient client) {
        return new AiRejectionReasonDrafter(client, new ObjectMapper(), VALIDATOR, GENEROUS_BUDGET);
    }

    private RejectionBrief weakMatch() {
        return new RejectionBrief(job(), List.of("Kafka", "Terraform"));
    }

    private Job job() {
        return Job.builder()
                .id(11L)
                .title("Streaming Engineer")
                .description("Runs the event pipeline.")
                .skills(new LinkedHashSet<>(List.of("Kafka", "Terraform", "Go")))
                .seniority(SeniorityLevel.SENIOR)
                .build();
    }
}
