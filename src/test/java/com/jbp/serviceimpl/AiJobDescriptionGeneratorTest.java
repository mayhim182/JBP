package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.GeneratedJobDescription;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.JobType;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.JobDescriptionGenerator.JobDescriptionBrief;
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
 * Story 12.1 — the generator, exercised with no network access.
 */
class AiJobDescriptionGeneratorTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();
    private static final AiTaskBudget GENEROUS_BUDGET = new AiTaskBudget(1_000);

    private static final String USABLE_REPLY = """
            {
              "summary": "Own the payment-ledger services behind millions of daily transactions.",
              "responsibilities": ["Design and operate ledger services", "Lead the reliability roadmap"],
              "requirements": ["Strong Java and Spring in production", "Event-driven architecture"],
              "niceToHave": ["Payments or fintech background"]
            }
            """;

    @AfterAll
    static void releaseValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void returnsTheFourSectionsWhenTheModelAnswersUsably() {
        AiJobDescriptionGenerator generator = generatorBacked(FakeChatCompletionClient.replyingWith(USABLE_REPLY));

        GeneratedJobDescription draft = generator.generate(fullBrief());

        assertThat(draft.getSummary()).contains("payment-ledger");
        assertThat(draft.getResponsibilities()).hasSize(2);
        assertThat(draft.getRequirements()).hasSize(2);
        assertThat(draft.getNiceToHave()).containsExactly("Payments or fintech background");
    }

    @Test
    void sendsEveryBriefFactToTheModel() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        generatorBacked(provider).generate(fullBrief());

        assertThat(provider.lastUserMessage())
                .contains("Senior Backend Engineer")
                .contains("Java")
                .contains("Kafka")
                .contains("SENIOR")
                .contains("FULL_TIME")
                .contains("Stripe")
                .contains("Payments infrastructure");
    }

    @Test
    void combinesRemoteWithTheLocationRatherThanReplacingIt() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        generatorBacked(provider).generate(fullBrief());

        // A remote role still has a hiring region worth stating.
        assertThat(provider.lastUserMessage()).contains("Remote (Bengaluru, India)");
    }

    @Test
    void leavesOutFactsTheRecruiterDidNotGive() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);
        JobDescriptionBrief titleOnly = new JobDescriptionBrief(
                "Backend Engineer", Set.of(), null, false, null, null, null, null);

        generatorBacked(provider).generate(titleOnly);

        // Absent facts must not appear as labels at all — "Location: none" would invite the model
        // to write about a location the recruiter never stated.
        assertThat(provider.lastUserMessage())
                .contains("Job title: Backend Engineer")
                .doesNotContain("Location")
                .doesNotContain("Seniority")
                .doesNotContain("Employment type")
                .doesNotContain("Company")
                .doesNotContain("null");
    }

    @Test
    void refusesRatherThanReturningAnEmptyDraftWhenAiIsDisabled() {
        AiJobDescriptionGenerator generator = generatorBacked(new DisabledChatClient());

        // Unlike resume autofill, there is no non-AI draft — so this must fail loudly enough for
        // the controller to answer 503 rather than hand back four empty sections.
        assertThatThrownBy(() -> generator.generate(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void refusesWhenTheProviderCannotBeReached() {
        AiJobDescriptionGenerator generator = generatorBacked(FakeChatCompletionClient.failingWith(
                new LlmUnavailableException("Model did not respond in time", true)));

        assertThatThrownBy(() -> generator.generate(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void refusesWhenTheReplyIsNotUsableJson() {
        AiJobDescriptionGenerator generator =
                generatorBacked(FakeChatCompletionClient.replyingWith("Here you go! (no JSON at all)"));

        assertThatThrownBy(() -> generator.generate(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void refusesWhenTheModelReturnsAllFourSectionsEmpty() {
        AiJobDescriptionGenerator generator = generatorBacked(FakeChatCompletionClient.replyingWith(
                """
                {"summary": "  ", "responsibilities": [], "requirements": [], "niceToHave": []}
                """));

        // Shaped correctly but says nothing, which is not a draft a recruiter can preview.
        assertThatThrownBy(() -> generator.generate(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void discardsAReplyCarryingKeysTheDraftDoesNotDeclare() {
        AiJobDescriptionGenerator generator = generatorBacked(FakeChatCompletionClient.replyingWith(
                """
                {"summary": "Fine", "responsibilities": [], "requirements": [], "niceToHave": [],
                 "salary": "$200k"}
                """));

        // Accepting a stray key would let an invented salary reach the editor.
        assertThatThrownBy(() -> generator.generate(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void asksTheProviderAgainOnEveryCallRatherThanReplayingTheLastDraft() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);
        AiJobDescriptionGenerator generator = generatorBacked(provider);

        generator.generate(fullBrief());
        generator.generate(fullBrief());

        // Regenerate must reach the provider a second time; nothing in the chain may cache.
        assertThat(provider.callCount()).isEqualTo(2);
    }

    @Test
    void tellsTheModelToInventNothingAndToAvoidCodedLanguage() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        generatorBacked(provider).generate(fullBrief());

        assertThat(provider.lastSystemPrompt())
                .contains("Never invent")
                .contains("age, gender or nationality");
    }

    private AiJobDescriptionGenerator generatorBacked(ChatCompletionClient provider) {
        return new AiJobDescriptionGenerator(provider, new ObjectMapper(), VALIDATOR, GENEROUS_BUDGET);
    }

    private JobDescriptionBrief fullBrief() {
        // LinkedHashSet so the asserted prompt order is stable.
        Set<String> skills = new LinkedHashSet<>(List.of("Java", "Kafka"));
        return new JobDescriptionBrief(
                "Senior Backend Engineer",
                skills,
                "Bengaluru, India",
                true,
                JobType.FULL_TIME,
                SeniorityLevel.SENIOR,
                "Stripe",
                "Payments infrastructure for the internet");
    }
}
