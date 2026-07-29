package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.ChatCompletionClient;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AbstractStructuredAiTaskTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();

    private static final Assessment FALLBACK = new Assessment("unrated", 0);
    private static final AiTaskBudget GENEROUS_BUDGET = new AiTaskBudget(1_000);

    @AfterAll
    static void releaseValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void returnsTheParsedRecordWhenTheModelAnswersCorrectly() {
        Assessment result = taskReplying("{\"verdict\":\"strong\",\"confidence\":87}").execute("input");

        assertThat(result).isEqualTo(new Assessment("strong", 87));
    }

    @Test
    void toleratesJsonWrappedInMarkdownFencesAndCommentary() {
        String realisticReply = """
                Sure, here is the assessment:
                ```json
                {"verdict":"strong","confidence":72}
                ```
                """;

        assertThat(taskReplying(realisticReply).execute("input"))
                .isEqualTo(new Assessment("strong", 72));
    }

    @Test
    void fallsBackWhenTheReplyIsMalformedJson() {
        assertThat(taskReplying("{\"verdict\":\"strong\",\"confidence\":").execute("input"))
                .isEqualTo(FALLBACK);
    }

    @Test
    void fallsBackWhenTheReplyContainsNoJsonAtAll() {
        assertThat(taskReplying("I'm afraid I can't help with that.").execute("input"))
                .isEqualTo(FALLBACK);
    }

    @Test
    void fallsBackWhenTheReplyCarriesFieldsTheRecordDoesNotDeclare() {
        assertThat(taskReplying("{\"verdict\":\"strong\",\"confidence\":80,\"salary\":99}").execute("input"))
                .as("a reply of the wrong shape must be discarded whole, not partially applied")
                .isEqualTo(FALLBACK);
    }

    @Test
    void fallsBackWhenTheReplyIsWellFormedButFailsValidation() {
        assertThat(taskReplying("{\"verdict\":\"\",\"confidence\":250}").execute("input"))
                .isEqualTo(FALLBACK);
    }

    @Test
    void fallsBackWhenTheModelTimesOut() {
        ChatCompletionClient timingOut = FakeChatCompletionClient.failingWith(
                new LlmUnavailableException("Model did not respond in time", true));

        assertThat(new AssessmentTask(timingOut, GENEROUS_BUDGET).execute("input")).isEqualTo(FALLBACK);
    }

    @Test
    void fallsBackWhenAiIsDisabled() {
        assertThat(new AssessmentTask(new DisabledChatClient(), GENEROUS_BUDGET).execute("input"))
                .isEqualTo(FALLBACK);
    }

    @Test
    void fallsBackRatherThanPropagatingAnUnexpectedFailure() {
        ChatCompletionClient misbehaving = FakeChatCompletionClient.failingWith(
                new IllegalStateException("something nobody predicted"));

        assertThat(new AssessmentTask(misbehaving, GENEROUS_BUDGET).execute("input")).isEqualTo(FALLBACK);
    }

    @Test
    void sendsTheSystemPromptAndTheRenderedInput() {
        FakeChatCompletionClient provider =
                FakeChatCompletionClient.replyingWith("{\"verdict\":\"strong\",\"confidence\":50}");

        new AssessmentTask(provider, GENEROUS_BUDGET).execute("a candidate summary");

        assertThat(provider.lastSystemPrompt()).contains("JSON");
        assertThat(provider.lastUserMessage()).isEqualTo("a candidate summary");
    }

    @Test
    void truncatesInputThatExceedsTheTokenBudget() {
        FakeChatCompletionClient provider =
                FakeChatCompletionClient.replyingWith("{\"verdict\":\"strong\",\"confidence\":50}");
        AiTaskBudget tightBudget = new AiTaskBudget(10);
        String oversizedInput = "x".repeat(500);

        new AssessmentTask(provider, tightBudget).execute(oversizedInput);

        assertThat(provider.lastUserMessage())
                .as("10 tokens at the estimated 4 characters each")
                .hasSize(40);
    }

    @Test
    void fallsBackWithoutSpendingARequestWhenThereIsNoInput() {
        FakeChatCompletionClient provider =
                FakeChatCompletionClient.replyingWith("{\"verdict\":\"strong\",\"confidence\":50}");
        AssessmentTask task = new AssessmentTask(provider, GENEROUS_BUDGET);

        assertThat(task.execute(null)).isEqualTo(FALLBACK);
        assertThat(task.execute("   ")).isEqualTo(FALLBACK);

        assertThat(provider.callCount())
                .as("empty input must not consume free-tier quota")
                .isZero();
    }

    @Test
    void leavesInputInsideTheBudgetUntouched() {
        FakeChatCompletionClient provider =
                FakeChatCompletionClient.replyingWith("{\"verdict\":\"strong\",\"confidence\":50}");

        new AssessmentTask(provider, new AiTaskBudget(10)).execute("short enough");

        assertThat(provider.lastUserMessage()).isEqualTo("short enough");
    }

    private AssessmentTask taskReplying(String reply) {
        return new AssessmentTask(FakeChatCompletionClient.replyingWith(reply), GENEROUS_BUDGET);
    }

    /**
     * A minimal subclass proving the story's claim: a new AI feature supplies only a system
     * prompt, a response type and a fallback.
     */
    private static final class AssessmentTask extends AbstractStructuredAiTask<String, Assessment> {

        private AssessmentTask(ChatCompletionClient chatCompletionClient, AiTaskBudget budget) {
            super(chatCompletionClient, new ObjectMapper(), VALIDATOR, budget);
        }

        @Override
        protected String systemPrompt() {
            return "Assess the candidate. Reply with JSON: {\"verdict\":string,\"confidence\":0-100}";
        }

        @Override
        protected Class<Assessment> responseType() {
            return Assessment.class;
        }

        @Override
        protected Assessment fallback() {
            return FALLBACK;
        }
    }

    record Assessment(@NotBlank String verdict, @Min(0) @Max(100) int confidence) {
    }
}
