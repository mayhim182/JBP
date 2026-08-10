package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.JobRiskAssessment;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.JobType;
import com.jbp.model.ModerationCategory;
import com.jbp.model.ModerationRisk;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.JobModerationAssistant.JobModerationBrief;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.5 — the risk assessment, exercised with no network access.
 *
 * <p>Most of these turn on one distinction: an <em>absent</em> assessment and a <em>clean</em> one are
 * different answers, and the queue draws them differently. Empty means nobody looked; {@code NONE}
 * means somebody looked and found nothing.
 */
class AiJobModerationAssistantTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();
    private static final AiTaskBudget GENEROUS_BUDGET = new AiTaskBudget(1_000);

    private static final String RISKY_REPLY = """
            {"risk": "HIGH", "flags": [
              {"category": "PAY_TO_APPLY",
               "rationale": "Applicants are asked to buy a \\"starter kit\\" before their first shift."},
              {"category": "UNREALISTIC_CLAIMS",
               "rationale": "Promises earnings far beyond the market rate for the role."}
            ]}
            """;

    @AfterAll
    static void releaseValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void reportsTheRiskAndEveryFlagWhenTheModelAnswersUsably() {
        Optional<JobRiskAssessment> assessment =
                assistantBacked(FakeChatCompletionClient.replyingWith(RISKY_REPLY)).assess(fullBrief());

        assertThat(assessment).isPresent();
        assertThat(assessment.get().risk()).isEqualTo(ModerationRisk.HIGH);
        assertThat(assessment.get().flags())
                .extracting(flag -> flag.getCategory())
                .containsExactly(ModerationCategory.PAY_TO_APPLY, ModerationCategory.UNREALISTIC_CLAIMS);
    }

    /**
     * The distinction the whole queue rests on. A clean posting was assessed, so it holds a value —
     * design 28 C draws that as "NO FLAGS", which is a claim, not the absence of one.
     */
    @Test
    void reportsACleanPostingAsAssessedRatherThanAsUnassessed() {
        Optional<JobRiskAssessment> assessment = assistantBacked(
                FakeChatCompletionClient.replyingWith("""
                        {"risk": "NONE", "flags": []}
                        """)).assess(fullBrief());

        assertThat(assessment).isPresent();
        assertThat(assessment.get().risk()).isEqualTo(ModerationRisk.NONE);
        assertThat(assessment.get().flags()).isEmpty();
    }

    /**
     * The reason category is text on the wire: one unrecognised value must cost one flag, not the good
     * one beside it.
     */
    @Test
    void dropsOnlyTheFlagWhoseCategoryItCannotUnderstand() {
        Optional<JobRiskAssessment> assessment = assistantBacked(
                FakeChatCompletionClient.replyingWith("""
                        {"risk": "HIGH", "flags": [
                          {"category": "BAD_VIBES", "rationale": "dropped"},
                          {"category": "SCAM", "rationale": "kept"}
                        ]}
                        """)).assess(fullBrief());

        assertThat(assessment).isPresent();
        assertThat(assessment.get().flags())
                .singleElement()
                .satisfies(flag -> assertThat(flag.getRationale()).isEqualTo("kept"));
    }

    @Test
    void dropsAFlagThatExplainsNothing() {
        Optional<JobRiskAssessment> assessment = assistantBacked(
                FakeChatCompletionClient.replyingWith("""
                        {"risk": "MEDIUM", "flags": [{"category": "SCAM", "rationale": "   "}]}
                        """)).assess(fullBrief());

        assertThat(assessment).isPresent();
        assertThat(assessment.get().flags()).isEmpty();
    }

    @Test
    void acceptsRiskAndCategoryInAnyCasing() {
        Optional<JobRiskAssessment> assessment = assistantBacked(
                FakeChatCompletionClient.replyingWith("""
                        {"risk": "medium", "flags": [{"category": "mlm", "rationale": "Pay depends on recruiting."}]}
                        """)).assess(fullBrief());

        assertThat(assessment).isPresent();
        assertThat(assessment.get().risk()).isEqualTo(ModerationRisk.MEDIUM);
        assertThat(assessment.get().flags())
                .singleElement()
                .satisfies(flag -> assertThat(flag.getCategory()).isEqualTo(ModerationCategory.MLM));
    }

    /**
     * A level with nothing behind it draws a chip an admin can expand to find nothing, which reads as a
     * broken assessment rather than a judgement.
     */
    @Test
    void downgradesToNoFlagsWhenTheModelClaimsARiskButNamesNothing() {
        Optional<JobRiskAssessment> assessment = assistantBacked(
                FakeChatCompletionClient.replyingWith("""
                        {"risk": "HIGH", "flags": []}
                        """)).assess(fullBrief());

        assertThat(assessment).isPresent();
        assertThat(assessment.get().risk()).isEqualTo(ModerationRisk.NONE);
    }

    /** The other direction: real findings must not hide behind the one chip nobody opens. */
    @Test
    void raisesToMediumWhenFlagsArriveUnderARiskOfNone() {
        Optional<JobRiskAssessment> assessment = assistantBacked(
                FakeChatCompletionClient.replyingWith("""
                        {"risk": "NONE", "flags": [{"category": "SCAM", "rationale": "Steers applicants off-platform."}]}
                        """)).assess(fullBrief());

        assertThat(assessment).isPresent();
        assertThat(assessment.get().risk()).isEqualTo(ModerationRisk.MEDIUM);
    }

    /**
     * Everything below leaves the job unassessed rather than clean. The queue can say "nobody has
     * looked at this"; it must never say "this was checked" when it was not.
     */
    @Test
    void leavesTheJobUnassessedWhenTheCapabilityIsOff() {
        assertThat(assistantBacked(new DisabledChatClient()).assess(fullBrief())).isEmpty();
    }

    @Test
    void leavesTheJobUnassessedWhenTheProviderCannotBeReached() {
        AiJobModerationAssistant assistant = assistantBacked(FakeChatCompletionClient.failingWith(
                new LlmUnavailableException("Model did not respond in time", true)));

        assertThat(assistant.assess(fullBrief())).isEmpty();
    }

    @Test
    void leavesTheJobUnassessedWhenTheReplyIsNotUsableJson() {
        assertThat(assistantBacked(FakeChatCompletionClient.replyingWith("Looks fine to me!"))
                .assess(fullBrief())).isEmpty();
    }

    @Test
    void leavesTheJobUnassessedWhenTheReplyOmitsTheRiskLevel() {
        // @NotBlank on risk is what keeps a null level impossible in any reply that validates, so the
        // fallback can use null to mean "never assessed" without colliding with a real answer.
        assertThat(assistantBacked(FakeChatCompletionClient.replyingWith("""
                {"flags": []}
                """)).assess(fullBrief())).isEmpty();
    }

    /**
     * Salary is sent here although {@code AiJobQualityChecker} withholds it — unrealistic earnings are
     * a category this task exists to catch, and they are unrecognisable without the figure.
     */
    @Test
    void sendsThePostingContentIncludingTheSalary() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(RISKY_REPLY);

        assistantBacked(provider).assess(fullBrief());

        assertThat(provider.lastUserMessage())
                .contains("Warehouse Associate")
                .contains("starter kit")
                .contains("Salary range");
    }

    /**
     * The boundary the queue tells the admin about: the assessment read the post and nothing else. A
     * brief that cannot carry the employer is what makes that structural rather than a prompt promise.
     */
    @Test
    void neverSendsAnythingAboutTheEmployer() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(RISKY_REPLY);

        assistantBacked(provider).assess(fullBrief());

        // Matched a clause at a time: the prompt is a text block, and a phrase that happens to span
        // one of its line breaks would fail here for a reason that has nothing to do with the rule.
        assertThat(provider.lastSystemPrompt())
                .contains("Judge only the text given to you")
                .contains("You have not been told the employer's name")
                .contains("must not guess at them or reason about who posted this");
    }

    @Test
    void tellsTheModelWhichFiveProblemsToLookFor() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(RISKY_REPLY);

        assistantBacked(provider).assess(fullBrief());

        assertThat(provider.lastSystemPrompt())
                .contains("SCAM")
                .contains("MLM")
                .contains("PAY_TO_APPLY")
                .contains("DISCRIMINATORY_LANGUAGE")
                .contains("UNREALISTIC_CLAIMS");
    }

    /** Advisory is the acceptance criterion; the prompt says so as well as the code enforcing it. */
    @Test
    void tellsTheModelItAdvisesRatherThanDecides() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(RISKY_REPLY);

        assistantBacked(provider).assess(fullBrief());

        assertThat(provider.lastSystemPrompt()).contains("you never decide");
    }

    /**
     * Wording quality is Story 12.3's job. Without this the review queue and the editor would both
     * report the same vague sentence, once as a risk and once as a suggestion.
     */
    @Test
    void forbidsTheModelFromTreatingPoorWritingAsARisk() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(RISKY_REPLY);

        assistantBacked(provider).assess(fullBrief());

        assertThat(provider.lastSystemPrompt()).contains("poorly written or vague posting is NOT a risk");
    }

    private AiJobModerationAssistant assistantBacked(ChatCompletionClient provider) {
        return new AiJobModerationAssistant(provider, new ObjectMapper(), VALIDATOR, GENEROUS_BUDGET);
    }

    private JobModerationBrief fullBrief() {
        return new JobModerationBrief(
                "Warehouse Associate",
                "Immediate start. Buy your starter kit and earn up to 8000 a week from day one.",
                Set.of("Packing"),
                SeniorityLevel.JUNIOR,
                JobType.FULL_TIME,
                200_000,
                400_000);
    }
}
