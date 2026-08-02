package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.DraftedScreeningAnswer;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.CandidateProfile;
import com.jbp.model.CandidateProject;
import com.jbp.model.Experience;
import com.jbp.model.ScreeningQuestionType;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.ScreeningAnswerAssistant.AnswerBrief;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 14.2 — the assistant, exercised with no network access. */
class AiScreeningAnswerAssistantTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();
    private static final AiTaskBudget GENEROUS_BUDGET = new AiTaskBudget(1_000);

    private static final String USABLE_REPLY = """
            {"draft": "I spent six years on payment and ledger services, mostly in Java."}
            """;

    @AfterAll
    static void releaseValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void returnsTheDraftWhenTheModelAnswersUsably() {
        DraftedScreeningAnswer drafted = assistantBacked(
                FakeChatCompletionClient.replyingWith(USABLE_REPLY)).draft(briefFor(fullProfile()));

        assertThat(drafted.getDraft()).startsWith("I spent six years");
        assertThat(drafted.wasUnavailable()).isFalse();
        assertThat(drafted.wasDeclined()).isFalse();
    }

    /**
     * The decline is a real answer, not a failure, and the caller turns it into a 422 that sends the
     * candidate to their profile. If it were indistinguishable from an outage they would be offered a
     * retry that could never succeed.
     */
    @Test
    void reportsAnEmptyDraftAsADeclineRatherThanAsAFailure() {
        DraftedScreeningAnswer drafted = assistantBacked(
                FakeChatCompletionClient.replyingWith("{\"draft\": \"\"}")).draft(briefFor(fullProfile()));

        assertThat(drafted.wasDeclined()).isTrue();
        assertThat(drafted.wasUnavailable())
                .as("the model answered — it just had nothing to write from")
                .isFalse();
    }

    @Test
    void reportsAnUnreachableModelAsUnavailableRatherThanAsADecline() {
        DraftedScreeningAnswer drafted = assistantBacked(FakeChatCompletionClient.failingWith(
                new LlmUnavailableException("Model did not respond in time", true)))
                .draft(briefFor(fullProfile()));

        assertThat(drafted.wasUnavailable()).isTrue();
        assertThat(drafted.wasDeclined()).isFalse();
    }

    @Test
    void reportsAnUnusableReplyAsUnavailable() {
        DraftedScreeningAnswer drafted = assistantBacked(
                FakeChatCompletionClient.replyingWith("Sure! Here's a draft.")).draft(briefFor(fullProfile()));

        assertThat(drafted.wasUnavailable()).isTrue();
    }

    @Test
    void discardsAReplyCarryingKeysTheResponseDoesNotDeclare() {
        DraftedScreeningAnswer drafted = assistantBacked(FakeChatCompletionClient.replyingWith(
                "{\"draft\": \"Six years.\", \"confidence\": 0.9}")).draft(briefFor(fullProfile()));

        assertThat(drafted.wasUnavailable()).isTrue();
    }

    /**
     * The schema limit on {@code ScreeningAnswer.answer}. A draft that overruns cannot be stored, so
     * it is discarded rather than handed to a field it will not fit — and never truncated, which
     * would put words in someone's mouth that they cannot see.
     */
    @Test
    void discardsADraftLongerThanTheColumnItHasToFit() {
        String tooLong = "a".repeat(2_001);
        DraftedScreeningAnswer drafted = assistantBacked(FakeChatCompletionClient.replyingWith(
                "{\"draft\": \"" + tooLong + "\"}")).draft(briefFor(fullProfile()));

        assertThat(drafted.wasUnavailable()).isTrue();
    }

    @Test
    void sendsTheCandidatesOwnHistoryIncludingProjects() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        assistantBacked(provider).draft(briefFor(fullProfile()));

        assertThat(provider.lastUserMessage())
                .contains("Backend Engineer")
                .contains("Acme")
                .contains("payment and ledger")
                .as("EmbeddingTexts drops projects entirely, which is one reason this is not reused")
                .contains("Ledger simulator");
    }

    @Test
    void tellsTheModelWhichAnswerTypeItIsWritingFor() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        assistantBacked(provider).draft(new AnswerBrief(
                "How many years of Java?", ScreeningQuestionType.SHORT_ANSWER, fullProfile()));

        // Without it a one-line question comes back as a paragraph that cannot fit its own control.
        assertThat(provider.lastUserMessage()).contains(ScreeningQuestionType.SHORT_ANSWER.name());
        assertThat(provider.lastSystemPrompt())
                .contains(ScreeningQuestionType.SHORT_ANSWER.name())
                .contains(ScreeningQuestionType.LONG_ANSWER.name());
    }

    /**
     * The instruction the whole feature rests on. A drafted answer goes to a recruiter under the
     * candidate's name, so an invented employer or qualification is the one output that must never
     * occur — and the model has to be told that an empty answer is the correct alternative.
     */
    @Test
    void tellsTheModelToDeclineRatherThanInvent() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        assistantBacked(provider).draft(briefFor(fullProfile()));

        assertThat(provider.lastSystemPrompt())
                .contains("Never invent")
                .contains("empty string")
                .contains("first person");
    }

    @Test
    void spendsNoCallOnAQuestionThatIsBlank() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        DraftedScreeningAnswer drafted = assistantBacked(provider)
                .draft(new AnswerBrief("   ", ScreeningQuestionType.LONG_ANSWER, fullProfile()));

        assertThat(provider.callCount()).isZero();
        assertThat(drafted.wasUnavailable()).isTrue();
    }

    private AiScreeningAnswerAssistant assistantBacked(ChatCompletionClient provider) {
        return new AiScreeningAnswerAssistant(provider, new ObjectMapper(), VALIDATOR, GENEROUS_BUDGET);
    }

    private AnswerBrief briefFor(CandidateProfile profile) {
        return new AnswerBrief(
                "Describe a distributed-systems failure you debugged.",
                ScreeningQuestionType.LONG_ANSWER,
                profile);
    }

    private CandidateProfile fullProfile() {
        CandidateProfile profile = CandidateProfile.builder().build();
        profile.setHeadline("Backend engineer, payments");
        profile.setSeniority(SeniorityLevel.SENIOR);
        // LinkedHashSet so the asserted prompt order is stable.
        profile.setSkills(new LinkedHashSet<>(List.of("Java", "Kafka")));
        profile.setExperiences(new ArrayList<>(List.of(Experience.builder()
                .title("Backend Engineer")
                .company("Acme")
                .description("Ran the payment and ledger services.")
                .build())));
        profile.setProjects(new ArrayList<>(List.of(CandidateProject.builder()
                .name("Ledger simulator")
                .description("Modelled double-entry postings under load.")
                .build())));
        return profile;
    }
}
