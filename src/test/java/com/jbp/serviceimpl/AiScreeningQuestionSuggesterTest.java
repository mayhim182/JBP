package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.SuggestedScreeningQuestions;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.ScreeningQuestionSuggester.ScreeningQuestionBrief;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Story 12.2 — the suggester, exercised with no network access.
 */
class AiScreeningQuestionSuggesterTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();
    private static final AiTaskBudget GENEROUS_BUDGET = new AiTaskBudget(1_000);

    private static final String USABLE_REPLY = """
            {"questions": [
              "How many years have you worked with Java in production?",
              "Describe a distributed-systems failure you diagnosed and fixed.",
              "Are you eligible to work in India without sponsorship?",
              "Which event-streaming tools have you operated at scale?"
            ]}
            """;

    @AfterAll
    static void releaseValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void returnsTheSuggestedQuestionsWhenTheModelAnswersUsably() {
        AiScreeningQuestionSuggester suggester =
                suggesterBacked(FakeChatCompletionClient.replyingWith(USABLE_REPLY));

        SuggestedScreeningQuestions suggestions = suggester.suggest(fullBrief());

        assertThat(suggestions.getQuestions()).hasSize(4);
        assertThat(suggestions.getQuestions().get(0)).startsWith("How many years");
    }

    @Test
    void sendsOnlyTheRoleFactsAndNoCandidateData() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        suggesterBacked(provider).suggest(fullBrief());

        assertThat(provider.lastUserMessage())
                .contains("Senior Backend Engineer")
                .contains("Java")
                .contains("Kafka")
                .contains("SENIOR");
    }

    @Test
    void leavesOutFactsTheRecruiterDidNotGive() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        suggesterBacked(provider).suggest(new ScreeningQuestionBrief("Backend Engineer", Set.of(), null));

        assertThat(provider.lastUserMessage())
                .contains("Job title: Backend Engineer")
                .doesNotContain("Seniority")
                .doesNotContain("Required skills")
                .doesNotContain("null");
    }

    /**
     * The opening words decide which control the candidate is shown, because the editor derives the
     * answer type from wording rather than storing one. If the prompt stops naming those openers,
     * the derivation silently drifts — so the instruction itself is asserted.
     */
    @Test
    void tellsTheModelExactlyWhichOpeningWordsDecideTheAnswerType() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        suggesterBacked(provider).suggest(fullBrief());

        // Asserted opener by opener rather than as one phrase: the prompt is a text block that
        // wraps, so any assertion spanning a line break passes or fails on where the wrap lands
        // rather than on whether the opener is present.
        assertThat(provider.lastSystemPrompt())
                .contains("Are you", "Do you", "Did you", "Have you", "Can you", "Will you", "Is your")
                .contains("Describe", "Explain", "Tell us", "Walk us", "Why", "How would", "What would");
    }

    @Test
    void forbidsTheQuestionsThatWouldMakeScreeningDiscriminatory() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        suggesterBacked(provider).suggest(fullBrief());

        assertThat(provider.lastSystemPrompt())
                .contains("age, gender, marital status, nationality, religion or health");
    }

    @Test
    void refusesRatherThanReturningAnEmptyListWhenAiIsDisabled() {
        AiScreeningQuestionSuggester suggester = suggesterBacked(new DisabledChatClient());

        assertThatThrownBy(() -> suggester.suggest(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void refusesWhenTheProviderCannotBeReached() {
        AiScreeningQuestionSuggester suggester = suggesterBacked(FakeChatCompletionClient.failingWith(
                new LlmUnavailableException("Model did not respond in time", true)));

        assertThatThrownBy(() -> suggester.suggest(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void refusesWhenTheReplyIsNotUsableJson() {
        AiScreeningQuestionSuggester suggester =
                suggesterBacked(FakeChatCompletionClient.replyingWith("Sure! Here are some ideas."));

        assertThatThrownBy(() -> suggester.suggest(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void refusesWhenTheModelReturnsNoQuestions() {
        AiScreeningQuestionSuggester suggester =
                suggesterBacked(FakeChatCompletionClient.replyingWith("""
                        {"questions": []}
                        """));

        assertThatThrownBy(() -> suggester.suggest(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void discardsAReplyCarryingKeysTheResponseDoesNotDeclare() {
        AiScreeningQuestionSuggester suggester =
                suggesterBacked(FakeChatCompletionClient.replyingWith("""
                        {"questions": ["Are you available to start in June?"], "notes": "hi"}
                        """));

        assertThatThrownBy(() -> suggester.suggest(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    /**
     * Two questions is fewer than the prompt asks for but still useful, so it is kept — the size
     * bound exists to reject a runaway answer, not to enforce the prompt's target.
     */
    @Test
    void keepsAnAnswerShorterThanTheThreeToFiveTheTargetAsksFor() {
        AiScreeningQuestionSuggester suggester =
                suggesterBacked(FakeChatCompletionClient.replyingWith("""
                        {"questions": ["Are you eligible to work in India?", "Describe your last outage."]}
                        """));

        assertThat(suggester.suggest(fullBrief()).getQuestions()).hasSize(2);
    }

    @Test
    void discardsAnAnswerThatHasClearlyRunAway() {
        String forty = "\"Are you sure?\", ".repeat(39) + "\"Are you sure?\"";
        AiScreeningQuestionSuggester suggester =
                suggesterBacked(FakeChatCompletionClient.replyingWith("{\"questions\": [" + forty + "]}"));

        assertThatThrownBy(() -> suggester.suggest(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void asksTheProviderAgainOnEveryCallRatherThanReplayingTheLastAnswer() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);
        AiScreeningQuestionSuggester suggester = suggesterBacked(provider);

        suggester.suggest(fullBrief());
        suggester.suggest(fullBrief());

        // "Suggest more" (18b state C) must reach the provider again; nothing may cache.
        assertThat(provider.callCount()).isEqualTo(2);
    }

    private AiScreeningQuestionSuggester suggesterBacked(ChatCompletionClient provider) {
        return new AiScreeningQuestionSuggester(provider, new ObjectMapper(), VALIDATOR, GENEROUS_BUDGET);
    }

    private ScreeningQuestionBrief fullBrief() {
        // LinkedHashSet so the asserted prompt order is stable.
        return new ScreeningQuestionBrief(
                "Senior Backend Engineer",
                new LinkedHashSet<>(List.of("Java", "Kafka")),
                SeniorityLevel.SENIOR);
    }
}
