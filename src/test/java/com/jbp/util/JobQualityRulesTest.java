package com.jbp.util;

import com.jbp.dto.JobQualityFinding;
import com.jbp.model.Job;
import com.jbp.model.JobQualityField;
import com.jbp.model.QualityFindingSource;
import com.jbp.model.QualitySeverity;
import com.jbp.model.SeniorityLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 12.3 — the rules that must work with AI switched off.
 */
class JobQualityRulesTest {

    private static final String LONG_ENOUGH_DESCRIPTION = "x".repeat(600);

    private final JobQualityRules rules = new JobQualityRules();

    @Test
    void findsNothingWrongWithACompletePosting() {
        assertThat(rules.check(completeJob())).isEmpty();
    }

    @Test
    void flagsAMissingSalaryRange() {
        Job job = completeJob();
        job.setSalaryMax(null);

        assertThat(rules.check(job))
                .extracting(JobQualityFinding::getField)
                .containsExactly(JobQualityField.SALARY);
    }

    @Test
    void treatsAHalfEnteredSalaryRangeAsMissing() {
        Job job = completeJob();
        job.setSalaryMin(null);

        // A minimum with no maximum is not a range, and a candidate cannot act on it.
        assertThat(rules.check(job)).hasSize(1);
    }

    @Test
    void flagsAThinDescriptionAndSaysHowLongItIs() {
        Job job = completeJob();
        job.setDescription("Too short.");

        List<JobQualityFinding> findings = rules.check(job);

        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getSeverity()).isEqualTo(QualitySeverity.HIGH);
        assertThat(findings.get(0).getMessage()).contains("10 characters");
        // The number the recruiter is told to aim for must be the one that triggered the finding.
        assertThat(findings.get(0).getSuggestion()).contains("600");
    }

    @Test
    void reportsAnAbsentDescriptionDifferentlyFromAShortOne() {
        Job job = completeJob();
        job.setDescription(null);

        assertThat(rules.check(job))
                .singleElement()
                .satisfies(finding -> assertThat(finding.getMessage()).isEqualTo("No description written."));
    }

    @Test
    void flagsAMissingSeniorityAsWorthConsideringRatherThanUrgent() {
        Job job = completeJob();
        job.setSeniority(null);

        assertThat(rules.check(job))
                .singleElement()
                .satisfies(finding -> {
                    assertThat(finding.getField()).isEqualTo(JobQualityField.SENIORITY);
                    assertThat(finding.getSeverity()).isEqualTo(QualitySeverity.MEDIUM);
                });
    }

    @Test
    void flagsNoSkillsAsHighAndTooFewSkillsAsPolish() {
        Job noSkills = completeJob();
        noSkills.setSkills(Set.of());
        Job fewSkills = completeJob();
        fewSkills.setSkills(Set.of("Java", "Kafka"));

        assertThat(rules.check(noSkills).get(0).getSeverity()).isEqualTo(QualitySeverity.HIGH);
        assertThat(rules.check(fewSkills).get(0).getSeverity()).isEqualTo(QualitySeverity.LOW);
    }

    @Test
    void doesNotReportBothNoSkillsAndTooFewSkillsForTheSameJob() {
        Job job = completeJob();
        job.setSkills(Set.of());

        // Two findings about one empty field would read as two separate problems.
        assertThat(rules.check(job)).hasSize(1);
    }

    @Test
    void saysSkillRatherThanSkillsWhenOnlyOneIsListed() {
        Job job = completeJob();
        job.setSkills(Set.of("Java"));

        assertThat(rules.check(job).get(0).getMessage()).isEqualTo("Only 1 skill listed.");
    }

    @Test
    void marksEveryRuleFindingAsComingFromARule() {
        Job job = Job.builder().build();

        assertThat(rules.check(job))
                .isNotEmpty()
                .allSatisfy(finding ->
                        assertThat(finding.getSource()).isEqualTo(QualityFindingSource.RULE));
    }

    @Test
    void reportsEveryProblemOfAnEmptyPostingAtOnce() {
        // Nothing entered at all: salary, description, seniority and skills should each be named.
        assertThat(rules.check(Job.builder().build()))
                .extracting(JobQualityFinding::getField)
                .containsExactlyInAnyOrder(
                        JobQualityField.SALARY,
                        JobQualityField.DESCRIPTION,
                        JobQualityField.SENIORITY,
                        JobQualityField.SKILLS);
    }

    @Test
    void neverReportsAWordingProblem() {
        Job job = completeJob();
        job.setDescription("We want a young, energetic person to help with various tasks. "
                + LONG_ENOUGH_DESCRIPTION);

        // Coded language and vagueness belong to the model; counting characters cannot judge them,
        // and a rule pretending to would produce false positives with no way to be right.
        assertThat(rules.check(job)).isEmpty();
    }

    private Job completeJob() {
        return Job.builder()
                .title("Senior Backend Engineer")
                .description(LONG_ENOUGH_DESCRIPTION)
                .skills(Set.of("Java", "Kafka", "PostgreSQL", "Spring", "Docker"))
                .seniority(SeniorityLevel.SENIOR)
                .salaryMin(140_000)
                .salaryMax(180_000)
                .build();
    }
}
