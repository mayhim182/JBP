package com.jbp.util;

import com.jbp.model.CandidateProfile;
import com.jbp.model.CandidateProject;
import com.jbp.model.Education;
import com.jbp.model.Experience;
import com.jbp.model.SeniorityLevel;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.2 — the gate the PO confirmed on 2026-08-01: at least one experience entry OR one project.
 *
 * <p>Every case here is a claim about what can honestly ground a written answer, not about profile
 * completeness. The two are different questions and this test exists to keep them apart.
 */
class AnswerDraftEligibilityTest {

    @Test
    void acceptsACandidateWithARoleTheyDescribed() {
        CandidateProfile profile = profileWith(
                List.of(experience("Backend Engineer", "Acme", "Ran the payments pipeline.")),
                List.of());

        assertThat(AnswerDraftEligibility.canGroundADraftedAnswer(profile)).isTrue();
    }

    @Test
    void acceptsACandidateWithARoleThatAtLeastNamesTheEmployer() {
        CandidateProfile profile = profileWith(
                List.of(experience("Backend Engineer", "Acme", null)),
                List.of());

        assertThat(AnswerDraftEligibility.canGroundADraftedAnswer(profile)).isTrue();
    }

    /**
     * The {@code OR} exists for exactly this candidate. A new graduate with real projects and no
     * employment has plenty to write from, and a rule requiring a job would lock them out of the
     * feature at the point in a career where it helps most.
     */
    @Test
    void acceptsANewGraduateWithProjectsAndNoJobs() {
        CandidateProfile profile = profileWith(
                List.of(),
                List.of(project("Ledger simulator", "Modelled double-entry postings under load.")));

        assertThat(AnswerDraftEligibility.canGroundADraftedAnswer(profile)).isTrue();
    }

    @Test
    void refusesAProfileWithNoRolesAndNoProjects() {
        assertThat(AnswerDraftEligibility.canGroundADraftedAnswer(profileWith(List.of(), List.of())))
                .isFalse();
    }

    /**
     * The whole point of the gate. Skills and seniority are labels a candidate picked from a list —
     * "Java, Kafka, Senior" cannot produce "describe a failure you debugged" without inventing the
     * failure, and inventing it is the one outcome this feature must never produce.
     */
    @Test
    void refusesAProfileCarryingOnlySkillsSeniorityAndEducation() {
        CandidateProfile profile = profileWith(List.of(), List.of());
        profile.setSkills(Set.of("Java", "Kafka"));
        profile.setSeniority(SeniorityLevel.SENIOR);
        profile.setEducations(new ArrayList<>(List.of(
                Education.builder().degree("BSc").fieldOfStudy("Computer Science")
                        .institution("A University").build())));

        assertThat(AnswerDraftEligibility.canGroundADraftedAnswer(profile))
                .as("a label is not evidence")
                .isFalse();
    }

    /**
     * Design 22b G's second failure: the structural check passes and there is still nothing to write
     * from. Counting a title-only row would send the candidate past the gate only to have the
     * assistant decline, which reads as a broken feature rather than an empty profile.
     */
    @Test
    void refusesARoleThatIsOnlyAJobTitle() {
        CandidateProfile profile = profileWith(
                List.of(experience("Backend Engineer", null, "  ")),
                List.of());

        assertThat(AnswerDraftEligibility.canGroundADraftedAnswer(profile)).isFalse();
    }

    @Test
    void refusesAProjectWithNoDescription() {
        CandidateProfile profile = profileWith(List.of(), List.of(project("Something", null)));

        assertThat(AnswerDraftEligibility.canGroundADraftedAnswer(profile)).isFalse();
    }

    @Test
    void refusesACandidateWithNoProfileAtAll() {
        assertThat(AnswerDraftEligibility.canGroundADraftedAnswer(null)).isFalse();
    }

    @Test
    void toleratesTheNullCollectionsAnUnsavedProfileCanCarry() {
        CandidateProfile profile = CandidateProfile.builder().build();
        profile.setExperiences(null);
        profile.setProjects(null);

        assertThat(AnswerDraftEligibility.canGroundADraftedAnswer(profile)).isFalse();
    }

    private CandidateProfile profileWith(List<Experience> experiences, List<CandidateProject> projects) {
        CandidateProfile profile = CandidateProfile.builder().build();
        profile.setExperiences(new ArrayList<>(experiences));
        profile.setProjects(new ArrayList<>(projects));
        return profile;
    }

    private Experience experience(String title, String company, String description) {
        return Experience.builder().title(title).company(company).description(description).build();
    }

    private CandidateProject project(String name, String description) {
        return CandidateProject.builder().name(name).description(description).build();
    }
}
