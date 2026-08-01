package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.InterviewQuestionKind;
import com.jbp.model.JobType;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.InterviewQuestionGenerator.InterviewQuestions;
import com.jbp.service.InterviewQuestionGenerator.JobBrief;
import com.jbp.service.InterviewQuestionGenerator.QuestionGroup;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 14.1 — the generator, and the failures it must make loud rather than quiet. */
class AiInterviewQuestionGeneratorTest {

    private final ChatCompletionClient chatCompletionClient = Mockito.mock(ChatCompletionClient.class);
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    private final AiInterviewQuestionGenerator generator = new AiInterviewQuestionGenerator(
            chatCompletionClient, new ObjectMapper(), validator, new AiTaskBudget(3000));

    @Test
    void groupsQuestionsInDisplayOrderRegardlessOfTheOrderTheModelAnswersIn() {
        givenTheModelReplies("""
                {"roleSpecific":["How would you onboard onto a settlement system?"],
                 "behavioural":["Tell me about a correctness bug you found.",
                                "Describe a disagreement about scope."],
                 "technical":["Make a ledger write idempotent.","Handle Kafka consumer lag.",
                              "Migrate a large Postgres table under load."]}
                """);

        InterviewQuestions questions = generator.generate(brief());

        assertThat(questions.groups()).extracting(QuestionGroup::kind)
                .as("design 21 fixes the order and requires the DOM to match, so the server sorts")
                .containsExactly(InterviewQuestionKind.TECHNICAL, InterviewQuestionKind.BEHAVIOURAL,
                        InterviewQuestionKind.ROLE_SPECIFIC);
        assertThat(questions.total()).isEqualTo(6);
    }

    @Test
    void dropsAnEmptyGroupEntirelyRatherThanSendingAnEmptyOne() {
        givenTheModelReplies("""
                {"technical":["Make a ledger write idempotent.","Handle Kafka consumer lag.",
                              "Migrate a large Postgres table under load."],
                 "behavioural":["Tell me about a correctness bug.","Describe a disagreement."],
                 "roleSpecific":[]}
                """);

        InterviewQuestions questions = generator.generate(brief());

        assertThat(questions.groups()).extracting(QuestionGroup::kind)
                .as("an overline with nothing under it is the one thing design 21 forbids")
                .containsExactly(InterviewQuestionKind.TECHNICAL, InterviewQuestionKind.BEHAVIOURAL);
        assertThat(questions.total()).isEqualTo(5);
    }

    @Test
    void acceptsTheSmallestAllowedSet() {
        givenTheModelReplies("""
                {"technical":["One.","Two.","Three."],"behavioural":["Four.","Five."],"roleSpecific":[]}
                """);

        assertThat(generator.generate(brief()).total()).isEqualTo(5);
    }

    @Test
    void rejectsFewerThanFiveRatherThanShowingAThinSection() {
        givenTheModelReplies("""
                {"technical":["One.","Two."],"behavioural":["Three."],"roleSpecific":[]}
                """);

        assertThatThrownBy(() -> generator.generate(brief()))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("usable range");
    }

    @Test
    void rejectsMoreThanEightRatherThanTruncating() {
        givenTheModelReplies("""
                {"technical":["1.","2.","3.","4.","5."],"behavioural":["6.","7.","8."],
                 "roleSpecific":["9."]}
                """);

        assertThatThrownBy(() -> generator.generate(brief()))
                .as("truncating would silently discard whichever questions the model thought best")
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void throwsRatherThanReturningNothingWhenTheModelIsUnavailable() {
        Mockito.when(chatCompletionClient.complete(Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new LlmUnavailableException("AI features are disabled", false));

        assertThatThrownBy(() -> generator.generate(brief()))
                .as("there is no rule-based question set worth showing, so this cannot degrade quietly")
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void throwsOnAReplyThatIsNotTheShapeAsked() {
        givenTheModelReplies("{\"questions\":[\"wrong shape entirely\"]}");

        assertThatThrownBy(() -> generator.generate(brief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void throwsOnAQuestionLongerThanAnInterviewerWouldSayOutLoud() {
        givenTheModelReplies("{\"technical\":[\"" + "x".repeat(161)
                + "\",\"Two.\",\"Three.\"],\"behavioural\":[\"Four.\",\"Five.\"],\"roleSpecific\":[]}");

        assertThatThrownBy(() -> generator.generate(brief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void sendsOnlyTheJobAndNothingAboutAnyCandidate() {
        givenTheModelReplies(validReply());

        generator.generate(brief());

        ArgumentCaptor<String> userMessage = ArgumentCaptor.forClass(String.class);
        Mockito.verify(chatCompletionClient).complete(Mockito.anyString(), userMessage.capture());
        assertThat(userMessage.getValue())
                .as("one cached answer is only correct for everyone if it saw nobody in particular")
                .contains("Senior Backend Engineer")
                .contains("java")
                .doesNotContainIgnoringCase("candidate")
                .doesNotContainIgnoringCase("profile");
    }

    @Test
    void spendsNoRequestOnAPostingWithNoTitle() {
        assertThatThrownBy(() -> generator.generate(
                new JobBrief(null, "some description", Set.of("java"), SeniorityLevel.SENIOR,
                        JobType.FULL_TIME)))
                .isInstanceOf(LlmUnavailableException.class);

        Mockito.verifyNoInteractions(chatCompletionClient);
    }

    @Test
    void producesTheSameCacheKeyForTheSameBriefAndADifferentOneAfterAnEdit() {
        JobBrief original = brief();
        JobBrief reordered = new JobBrief("Senior Backend Engineer", "Own the payment ledger.",
                Set.of("kafka", "java"), SeniorityLevel.SENIOR, JobType.FULL_TIME);
        JobBrief edited = new JobBrief("Senior Backend Engineer", "Own the payment ledger and more.",
                Set.of("java", "kafka"), SeniorityLevel.SENIOR, JobType.FULL_TIME);

        assertThat(reordered.cacheKey())
                .as("skills arrive in a HashSet, so an unsorted key would miss on every restart")
                .isEqualTo(original.cacheKey());
        assertThat(edited.cacheKey())
                .as("an edited posting must not serve the old questions forever")
                .isNotEqualTo(original.cacheKey());
    }

    private void givenTheModelReplies(String json) {
        Mockito.when(chatCompletionClient.complete(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(json);
    }

    private String validReply() {
        return """
                {"technical":["Make a ledger write idempotent.","Handle Kafka consumer lag.",
                              "Migrate a large Postgres table under load."],
                 "behavioural":["Tell me about a correctness bug.","Describe a disagreement."],
                 "roleSpecific":["How would you instrument a new service?"]}
                """;
    }

    private JobBrief brief() {
        return new JobBrief("Senior Backend Engineer", "Own the payment ledger.",
                Set.of("java", "kafka"), SeniorityLevel.SENIOR, JobType.FULL_TIME);
    }
}
