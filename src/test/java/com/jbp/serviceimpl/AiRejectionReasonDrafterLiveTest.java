package com.jbp.serviceimpl;

import com.jbp.dto.DraftedRejectionReason;
import com.jbp.model.Job;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.RejectionReasonDrafter;
import com.jbp.service.RejectionReasonDrafter.RejectionBrief;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.4 AC · <em>"Tested with a weak-match applicant: output is honest without being cruel."</em>
 *
 * <p>Drafts a real rejection for a deliberately weak match and holds the half of that sentence a
 * machine can hold. <strong>Honest</strong> is checkable: the draft has to name a requirement the
 * application did not evidence, stay within two or three sentences, and contain none of the comfort
 * phrases that make a rejection say nothing. <strong>Not cruel</strong> is not checkable, so the draft
 * is printed — reading it is the point, and this test exists to make that a command rather than a
 * chore.
 *
 * <p>Skipped unless {@code JBP_AI_LIVE_TEST=true}, on the same reasoning as {@link GeminiLiveSmokeTest}:
 * the normal build stays offline and never spends provider quota.
 */
@SpringBootTest(properties = {
        "app.ai.enabled=true",
        "app.ai.features.rejection-drafting=true",
        "app.ai.base-url=https://generativelanguage.googleapis.com/v1beta/openai",
        // Mirrors app.ai.model in src/main/resources/application.properties — keep the two in step.
        "app.ai.model=gemini-3.1-flash-lite",
        "app.ai.api-key=${GEMINI_API_KEY:}"
})
@EnabledIfEnvironmentVariable(named = "JBP_AI_LIVE_TEST", matches = "true")
class AiRejectionReasonDrafterLiveTest {

    /**
     * The comfort phrases design 26 names. Each one is a sentence that costs the writer nothing and
     * tells the reader nothing, and a generator drifts toward them precisely because they are safe.
     */
    private static final List<String> FALSE_ENCOURAGEMENT = List.of(
            "apply again",
            "keep your cv",
            "keep your resume",
            "on file",
            "very impressed",
            "bright future",
            "best of luck in your job search");

    @Autowired
    private RejectionReasonDrafter drafter;

    @Test
    void writesAnHonestRejectionForAWeakMatch() {
        DraftedRejectionReason drafted = drafter.draft(weakMatch());
        String reason = drafted.getReason();

        // Printed on purpose: the "without being cruel" half is a human judgement and this is where
        // the evidence for it goes.
        System.out.println("\n--- drafted rejection (weak match) ---\n" + reason + "\n---\n");

        assertThat(drafted.wasUnavailable()).isFalse();

        String lowered = reason.toLowerCase(Locale.ROOT);
        assertThat(lowered)
                .as("must name a requirement the application did not evidence, not just 'not a fit'")
                .satisfiesAnyOf(
                        text -> assertThat(text).contains("kafka"),
                        text -> assertThat(text).contains("terraform"));

        for (String phrase : FALSE_ENCOURAGEMENT) {
            assertThat(lowered).as("false encouragement: '%s'", phrase).doesNotContain(phrase);
        }

        assertThat(sentencesIn(reason))
                .as("two or three sentences, per the acceptance criteria")
                .isBetween(2, 3);
    }

    /**
     * The other half of the same guarantee: nothing about the person. The drafter is never given a
     * profile, so anything of this shape would be invented — which is the failure this criterion is
     * actually about.
     */
    @Test
    void saysNothingAboutTheCandidateAsAPerson() {
        String lowered = drafter.draft(weakMatch()).getReason().toLowerCase(Locale.ROOT);

        assertThat(lowered).doesNotContain("attitude");
        assertThat(lowered).doesNotContain("ambition");
        assertThat(lowered).doesNotContain("motivat");
        assertThat(lowered).doesNotContain("potential");
        assertThat(lowered).doesNotContain("junior");
        assertThat(lowered).doesNotContain("other candidates");
        assertThat(lowered).doesNotContain("other applicants");
    }

    private int sentencesIn(String reason) {
        return (int) reason.chars().filter(c -> c == '.' || c == '!' || c == '?').count();
    }

    /** Evidences Go and nothing else, against a role that leans on two things it does not show. */
    private RejectionBrief weakMatch() {
        Job job = Job.builder()
                .id(1L)
                .title("Senior Streaming Engineer")
                .description("Owns the event pipeline: ingest, replay and exactly-once delivery, "
                        + "with the infrastructure managed as code.")
                .skills(new LinkedHashSet<>(List.of("Kafka", "Terraform", "Go")))
                .seniority(SeniorityLevel.SENIOR)
                .build();
        return new RejectionBrief(job, List.of("Kafka", "Terraform"));
    }
}
