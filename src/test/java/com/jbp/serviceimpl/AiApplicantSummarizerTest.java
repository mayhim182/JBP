package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.ApplicantSummary;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.CandidateProfile;
import com.jbp.model.CandidateProject;
import com.jbp.model.Education;
import com.jbp.model.Experience;
import com.jbp.model.Job;
import com.jbp.model.MatchFactorKind;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.ApplicantSummarizer.ApplicantBrief;
import com.jbp.service.ApplicantSummarizer.FactorSignal;
import com.jbp.service.ApplicantSummarizer.FactorStrength;
import com.jbp.service.ChatCompletionClient;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 14.3 — the summarizer, exercised with no network access. */
class AiApplicantSummarizerTest {

    private static final ValidatorFactory VALIDATOR_FACTORY = Validation.buildDefaultValidatorFactory();
    private static final Validator VALIDATOR = VALIDATOR_FACTORY.getValidator();
    private static final AiTaskBudget GENEROUS_BUDGET = new AiTaskBudget(2_000);

    private static final String USABLE_REPLY = """
            {"strongestFit": "Has run payment-ledger services in production.",
             "mainGap": "No Kafka in the profile, though there is adjacent event-streaming work.",
             "worthProbing": "Ask how they got exactly-once delivery without Kafka."}
            """;

    @AfterAll
    static void releaseValidatorFactory() {
        VALIDATOR_FACTORY.close();
    }

    @Test
    void returnsTheThreeLinesWhenTheModelAnswersUsably() {
        ApplicantSummary summary = summarizerBacked(
                FakeChatCompletionClient.replyingWith(USABLE_REPLY)).summarise(fullBrief());

        assertThat(summary.getStrongestFit()).startsWith("Has run payment-ledger");
        assertThat(summary.getMainGap()).contains("No Kafka");
        assertThat(summary.getWorthProbing()).startsWith("Ask how");
        assertThat(summary.hasAllThreeLines()).isTrue();
    }

    /**
     * Design 24 B4. The decline is a value rather than a throw because it is a fact about the
     * profile — worth caching, and worth rendering differently from an outage, since retrying an
     * outage can succeed and retrying this cannot.
     */
    @Test
    void reportsAnEmptyReadAsADeclineRatherThanAsAFailure() {
        ApplicantSummary summary = summarizerBacked(FakeChatCompletionClient.replyingWith(
                "{\"strongestFit\": \"\", \"mainGap\": \"\", \"worthProbing\": \"\"}"))
                .summarise(fullBrief());

        assertThat(summary.wasDeclined()).isTrue();
        assertThat(summary.hasAllThreeLines()).isFalse();
    }

    @Test
    void failsRatherThanReturningAnEmptyValueWhenTheModelCannotBeReached() {
        AiApplicantSummarizer summarizer = summarizerBacked(FakeChatCompletionClient.failingWith(
                new LlmUnavailableException("Model did not respond in time", true)));

        assertThatThrownBy(() -> summarizer.summarise(fullBrief()))
                .as("a thrown failure is what keeps it out of the cache, so Try again can work")
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void failsWhenTheReplyIsNotUsableJson() {
        AiApplicantSummarizer summarizer =
                summarizerBacked(FakeChatCompletionClient.replyingWith("Here's my read on them!"));

        assertThatThrownBy(() -> summarizer.summarise(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void discardsAReplyCarryingKeysTheResponseDoesNotDeclare() {
        AiApplicantSummarizer summarizer = summarizerBacked(FakeChatCompletionClient.replyingWith(
                "{\"strongestFit\": \"a\", \"mainGap\": \"b\", \"worthProbing\": \"c\", \"score\": 82}"));

        assertThatThrownBy(() -> summarizer.summarise(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    /**
     * Half a read is neither a read nor a decline: the panel cannot draw it and a recruiter cannot
     * trust it, so it is discarded whole rather than shown with a gap in it.
     */
    @Test
    void discardsAPartlyWrittenRead() {
        AiApplicantSummarizer summarizer = summarizerBacked(FakeChatCompletionClient.replyingWith(
                "{\"strongestFit\": \"Ran ledgers.\", \"mainGap\": \"\", \"worthProbing\": \"\"}"));

        assertThatThrownBy(() -> summarizer.summarise(fullBrief()))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void sendsTheJobTheProfileAndTheBandedBreakdown() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        summarizerBacked(provider).summarise(fullBrief());

        assertThat(provider.lastUserMessage())
                .contains("Senior Backend Engineer")
                .contains("Acme")
                .contains("Ledger simulator")
                .contains(MatchFactorKind.SKILLS.name())
                .contains(FactorStrength.PARTIAL.name());
    }

    /**
     * The acceptance criterion's "complements the score — must not restate it", made structural.
     * Design 24 C's reject list is percentages, x-of-y ratios and ranking language; the surest way to
     * prevent them is for the model never to receive one. Asserted on the message rather than on the
     * output, because an assertion on the output would be testing the model's obedience.
     */
    @Test
    void neverSendsTheScoreOrAnyRatioTheReadCouldEcho() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        summarizerBacked(provider).summarise(fullBrief());

        assertThat(provider.lastUserMessage())
                .as("the breakdown arrives banded, so there is no arithmetic to repeat")
                .doesNotContain("82")
                .doesNotContain("4/5")
                .doesNotContain("skills 4/5");
    }

    /**
     * The acceptance criterion's hardest requirement, and the reason the constraint names the
     * *inference routes* rather than only the characteristics: the realistic failure is not a model
     * volunteering someone's religion, it is one reasoning from a name, a university or a graduation
     * year and presenting the conclusion as a fit.
     */
    @Test
    void forbidsEveryProtectedCharacteristicAndTheRoutesToInferringOne() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        summarizerBacked(provider).summarise(briefWithProtectedCharacteristicHints());

        assertThat(provider.lastSystemPrompt())
                .contains("age", "gender", "caste", "national origin", "nationality",
                        "religion", "disability", "marital", "sexual orientation")
                .contains("These are NOT")
                .contains("Do not reason from them");
    }

    /**
     * The other half of that criterion: a profile seeded with the hints a model might reason from
     * still produces a read, and the hints themselves are handed over as ordinary profile facts
     * rather than being stripped — stripping them would change what the candidate wrote about
     * themselves, and the constraint is on the reasoning, not on the record.
     */
    @Test
    void stillWritesAReadFromAProfileCarryingThoseHints() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        ApplicantSummary summary =
                summarizerBacked(provider).summarise(briefWithProtectedCharacteristicHints());

        assertThat(summary.hasAllThreeLines()).isTrue();
        assertThat(provider.lastUserMessage()).contains("Mother Teresa Women's College");
    }

    /**
     * An empty profile is a <strong>decline</strong>, not a failure, and it must not be left to the
     * shared pipeline to discover: an empty user message makes {@code execute} return the fallback,
     * which is indistinguishable from an unreachable model — so the recruiter would be shown design
     * 24 B2's "Try again" for a condition retrying cannot change.
     */
    @Test
    void treatsAProfileWithNothingInItAsADeclineRatherThanAFailure() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith(USABLE_REPLY);

        ApplicantSummary summary = summarizerBacked(provider).summarise(new ApplicantBrief(
                1L, "v1", CandidateProfile.builder().build(), job(), List.of()));

        assertThat(summary.wasDeclined()).isTrue();
        assertThat(summary.wasUnavailable())
                .as("B2 offers a retry, and there is nothing here for a retry to fix")
                .isFalse();
        assertThat(provider.callCount())
                .as("and it costs no request to know that")
                .isZero();
    }

    private AiApplicantSummarizer summarizerBacked(ChatCompletionClient provider) {
        return new AiApplicantSummarizer(provider, new ObjectMapper(), VALIDATOR, GENEROUS_BUDGET);
    }

    private ApplicantBrief fullBrief() {
        return new ApplicantBrief(1L, "abc123", fullProfile(), job(), List.of(
                new FactorSignal(MatchFactorKind.SKILLS, FactorStrength.PARTIAL),
                new FactorSignal(MatchFactorKind.SENIORITY, FactorStrength.STRONG)));
    }

    private ApplicantBrief briefWithProtectedCharacteristicHints() {
        CandidateProfile profile = fullProfile();
        // Every one of these is a legitimate thing a candidate may put on their own profile, and
        // every one is a route somebody might reason from. Both facts are the point of the test.
        profile.setEducations(new ArrayList<>(List.of(Education.builder()
                .degree("BSc")
                .fieldOfStudy("Computer Science")
                .institution("Mother Teresa Women's College")
                .startYear("1979")
                .endYear("1983")
                .build())));
        profile.setLocation("Chennai, India");
        return new ApplicantBrief(1L, "abc123", profile, job(), List.of());
    }

    private CandidateProfile fullProfile() {
        CandidateProfile profile = CandidateProfile.builder().build();
        profile.setHeadline("Backend engineer, payments");
        profile.setSeniority(SeniorityLevel.SENIOR);
        // LinkedHashSet so the asserted prompt order is stable.
        profile.setSkills(new LinkedHashSet<>(List.of("Java", "Postgres")));
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

    private Job job() {
        Job job = Job.builder().build();
        job.setTitle("Senior Backend Engineer");
        job.setDescription("Own the payment pipeline.");
        job.setSkills(new LinkedHashSet<>(List.of("Java", "Kafka")));
        job.setSeniority(SeniorityLevel.SENIOR);
        return job;
    }
}
