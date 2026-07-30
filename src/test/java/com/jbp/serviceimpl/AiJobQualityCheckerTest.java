package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.JobQualityFinding;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.JobQualityField;
import com.jbp.model.JobType;
import com.jbp.model.QualityFindingSource;
import com.jbp.model.QualitySeverity;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.JobQualityChecker.JobQualityBrief;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 12.3 — the wording half, exercised with no network access.
 */
class AiJobQualityCheckerTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();
    private static final AiTaskBudget GENEROUS_BUDGET = new AiTaskBudget(1_000);

    private static final String USABLE_REPLY = """
            {"findings": [
              {"severity": "MEDIUM", "field": "PHRASING",
               "message": "\\"young, energetic team\\" can read as age-coded.",
               "suggestion": "Try \\"fast-moving team\\" — same energy, no age signal."},
              {"severity": "MEDIUM", "field": "DESCRIPTION",
               "message": "Three responsibilities are vague.",
               "suggestion": "Name the systems and the outcome you expect."}
            ]}
            """;

    @AfterAll
    static void releaseValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void returnsTheWordingFindingsWhenTheModelAnswersUsably() {
        List<JobQualityFinding> findings =
                checkerBacked(FakeChatCompletionClient.replyingWith(USABLE_REPLY)).check(fullBrief());

        assertThat(findings).hasSize(2);
        assertThat(findings.get(0).getField()).isEqualTo(JobQualityField.PHRASING);
        assertThat(findings.get(0).getSeverity()).isEqualTo(QualitySeverity.MEDIUM);
    }

    @Test
    void marksEveryModelFindingAsComingFromAi() {
        List<JobQualityFinding> findings =
                checkerBacked(FakeChatCompletionClient.replyingWith(USABLE_REPLY)).check(fullBrief());

        // The panel shows the source, so a recruiter can weigh a judgement differently from a fact.
        assertThat(findings).allSatisfy(finding ->
                assertThat(finding.getSource()).isEqualTo(QualityFindingSource.AI));
    }

    /**
     * The whole reason severity and field are text on the wire: one unrecognised value must cost one
     * finding, not the four good ones beside it.
     */
    @Test
    void dropsOnlyTheFindingWhoseSeverityItCannotUnderstand() {
        AiJobQualityChecker checker = checkerBacked(FakeChatCompletionClient.replyingWith("""
                {"findings": [
                  {"severity": "CATASTROPHIC", "field": "PHRASING", "message": "x", "suggestion": "y"},
                  {"severity": "LOW", "field": "PHRASING", "message": "kept", "suggestion": "y"}
                ]}
                """));

        assertThat(checker.check(fullBrief()))
                .singleElement()
                .satisfies(finding -> assertThat(finding.getMessage()).isEqualTo("kept"));
    }

    @Test
    void dropsAFindingNamingAFieldTheEditorCannotScrollTo() {
        AiJobQualityChecker checker = checkerBacked(FakeChatCompletionClient.replyingWith("""
                {"findings": [
                  {"severity": "LOW", "field": "VIBES", "message": "x", "suggestion": "y"},
                  {"severity": "LOW", "field": "TITLE", "message": "kept", "suggestion": "y"}
                ]}
                """));

        assertThat(checker.check(fullBrief())).hasSize(1);
    }

    @Test
    void acceptsSeverityAndFieldInAnyCasing() {
        AiJobQualityChecker checker = checkerBacked(FakeChatCompletionClient.replyingWith("""
                {"findings": [{"severity": "high", "field": "phrasing", "message": "x", "suggestion": "y"}]}
                """));

        assertThat(checker.check(fullBrief()))
                .singleElement()
                .satisfies(finding -> assertThat(finding.getSeverity()).isEqualTo(QualitySeverity.HIGH));
    }

    @Test
    void dropsAFindingWithNothingToSay() {
        AiJobQualityChecker checker = checkerBacked(FakeChatCompletionClient.replyingWith("""
                {"findings": [{"severity": "LOW", "field": "TITLE", "message": "  ", "suggestion": "y"}]}
                """));

        assertThat(checker.check(fullBrief())).isEmpty();
    }

    /**
     * Unlike the other two Epic 12 tasks this one degrades quietly: the rules have already produced
     * findings, so an outage costs the wording review and nothing else.
     */
    @Test
    void returnsAnEmptyListRatherThanFailingWhenAiIsDisabled() {
        assertThat(checkerBacked(new DisabledChatClient()).check(fullBrief())).isEmpty();
    }

    @Test
    void returnsAnEmptyListWhenTheProviderCannotBeReached() {
        AiJobQualityChecker checker = checkerBacked(FakeChatCompletionClient.failingWith(
                new LlmUnavailableException("Model did not respond in time", true)));

        assertThat(checker.check(fullBrief())).isEmpty();
    }

    @Test
    void returnsAnEmptyListWhenTheReplyIsNotUsableJson() {
        assertThat(checkerBacked(FakeChatCompletionClient.replyingWith("Looks fine to me!"))
                .check(fullBrief())).isEmpty();
    }

    @Test
    void sendsThePostingContentButNotTheSalary() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        checkerBacked(provider).check(fullBrief());

        assertThat(provider.lastUserMessage())
                .contains("Senior Backend Engineer")
                .contains("Java")
                .contains("young, energetic")
                .doesNotContain("Salary");
    }

    /**
     * The rules already report missing and short fields. If the prompt stops forbidding those, the
     * panel starts showing each of them twice — once as a fact and once as a judgement.
     */
    @Test
    void forbidsTheModelFromRepeatingWhatTheRulesAlreadyCheck() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        checkerBacked(provider).check(fullBrief());

        assertThat(provider.lastSystemPrompt())
                .contains("Never report these")
                .contains("LENGTH or PRESENCE of any field");
    }

    @Test
    void tellsTheModelWhichThreeKindsOfProblemToLookFor() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        checkerBacked(provider).check(fullBrief());

        assertThat(provider.lastSystemPrompt())
                .contains("Vague responsibilities")
                .contains("Coded language")
                .contains("Unrealistic expectations");
    }

    @Test
    void asksTheProviderAgainOnEveryCallRatherThanReplayingTheLastReview() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);
        AiJobQualityChecker checker = checkerBacked(provider);

        checker.check(fullBrief());
        checker.check(fullBrief());

        // "Re-check" must reach the provider again; findings are never persisted or cached.
        assertThat(provider.callCount()).isEqualTo(2);
    }

    private AiJobQualityChecker checkerBacked(ChatCompletionClient provider) {
        return new AiJobQualityChecker(provider, new ObjectMapper(), VALIDATOR, GENEROUS_BUDGET);
    }

    private JobQualityBrief fullBrief() {
        return new JobQualityBrief(
                "Senior Backend Engineer",
                "We need a backend engineer to help with various tasks in a young, energetic team.",
                Set.of("Java"),
                SeniorityLevel.SENIOR,
                JobType.FULL_TIME);
    }
}
