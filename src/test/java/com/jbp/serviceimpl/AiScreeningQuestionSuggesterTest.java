package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.SuggestedScreeningQuestions;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.ScreeningQuestionType;
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
              {"question": "How many years have you worked with Java in production?",
               "answerType": "SHORT_ANSWER"},
              {"question": "Describe a distributed-systems failure you diagnosed and fixed.",
               "answerType": "LONG_ANSWER"},
              {"question": "Are you eligible to work in India without sponsorship?",
               "answerType": "YES_NO"},
              {"question": "Which event-streaming tools have you operated at scale?",
               "answerType": "SHORT_ANSWER"}
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
        assertThat(suggestions.getQuestions().get(0).getQuestion()).startsWith("How many years");
    }

    /**
     * The type is the whole point of the field: a suggestion the recruiter can accept as-is has to
     * arrive with one, and the three constants have to survive round-tripping through the reply.
     */
    @Test
    void carriesTheAnswerTypeTheModelChoseForEachQuestion() {
        AiScreeningQuestionSuggester suggester =
                suggesterBacked(FakeChatCompletionClient.replyingWith(USABLE_REPLY));

        SuggestedScreeningQuestions suggestions = suggester.suggest(fullBrief());

        assertThat(suggestions.getQuestions())
                .extracting(SuggestedScreeningQuestions.SuggestedScreeningQuestion::getAnswerType)
                .containsExactly(
                        ScreeningQuestionType.SHORT_ANSWER,
                        ScreeningQuestionType.LONG_ANSWER,
                        ScreeningQuestionType.YES_NO,
                        ScreeningQuestionType.SHORT_ANSWER);
    }

    /**
     * A suggestion with no type would reach design 18's panel as a blank segmented control, which is
     * exactly the "pick one" state that panel is specified never to show. Rejecting the reply gives
     * the recruiter the disabled trigger instead, which is a state they can read.
     */
    @Test
    void refusesWhenAQuestionArrivesWithNoAnswerType() {
        AiScreeningQuestionSuggester suggester =
                suggesterBacked(FakeChatCompletionClient.replyingWith("""
                        {"questions": [{"question": "Are you eligible to work in India?"}]}
                        """));

        assertThatThrownBy(() -> suggester.suggest(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void refusesWhenTheModelInventsAnAnswerTypeThatDoesNotExist() {
        AiScreeningQuestionSuggester suggester =
                suggesterBacked(FakeChatCompletionClient.replyingWith("""
                        {"questions": [{"question": "Pick your strongest language.",
                                        "answerType": "MULTIPLE_CHOICE"}]}
                        """));

        assertThatThrownBy(() -> suggester.suggest(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
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
     * The prompt used to dictate opening words, because the editor read the answer type out of them.
     * It must not any more: a question worded to trip a heuristic reads worse than one worded to ask
     * the thing, and there is no heuristic left to trip. What the prompt has to name instead is the
     * three constants, spelled exactly as the enum spells them — a reply naming a fourth is discarded
     * whole, so a drifted prompt costs the recruiter every suggestion rather than one.
     */
    @Test
    void namesTheThreeAnswerTypesAndNoLongerDictatesOpeningWords() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        suggesterBacked(provider).suggest(fullBrief());

        // Asserted constant by constant rather than as one phrase: the prompt is a text block that
        // wraps, so any assertion spanning a line break passes or fails on where the wrap lands
        // rather than on whether the constant is present.
        assertThat(provider.lastSystemPrompt())
                .contains(ScreeningQuestionType.SHORT_ANSWER.name())
                .contains(ScreeningQuestionType.LONG_ANSWER.name())
                .contains(ScreeningQuestionType.YES_NO.name())
                .contains("answerType")
                .doesNotContain("MUST begin with");
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
                        {"questions": [{"question": "Are you available to start in June?",
                                        "answerType": "YES_NO"}],
                         "notes": "hi"}
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
                        {"questions": [
                          {"question": "Are you eligible to work in India?", "answerType": "YES_NO"},
                          {"question": "Describe your last outage.", "answerType": "LONG_ANSWER"}
                        ]}
                        """));

        assertThat(suggester.suggest(fullBrief()).getQuestions()).hasSize(2);
    }

    @Test
    void discardsAnAnswerThatHasClearlyRunAway() {
        String one = "{\"question\": \"Are you sure?\", \"answerType\": \"YES_NO\"}";
        String forty = (one + ", ").repeat(39) + one;
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
