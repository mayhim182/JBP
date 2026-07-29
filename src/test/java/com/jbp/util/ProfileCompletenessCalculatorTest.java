package com.jbp.util;

import com.jbp.model.CandidateProfile;
import com.jbp.model.CandidateProject;
import com.jbp.model.Education;
import com.jbp.model.Experience;
import com.jbp.model.SeniorityLevel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the scoring contract against the change Story 11.4 could easily have broken: adding a
 * phone number to the profile must not turn eight sections into nine.
 */
class ProfileCompletenessCalculatorTest {

    private final ProfileCompletenessCalculator calculator = new ProfileCompletenessCalculator();

    @Test
    void scoresAFullyFilledProfileWithNoPhoneAt100Percent() {
        assertThat(calculator.calculate(fullyFilledProfile().build()))
                .as("phone is stored, not scored — a complete profile without one is still complete")
                .isEqualTo(100);
    }

    @Test
    void addingAPhoneLeavesTheScoreUnchanged() {
        int withoutPhone = calculator.calculate(fullyFilledProfile().build());
        int withPhone = calculator.calculate(fullyFilledProfile().phone("+91 98765 43210").build());

        assertThat(withPhone).isEqualTo(withoutPhone).isEqualTo(100);
    }

    @Test
    void scoresSevenOfEightSectionsAt88Percent() {
        CandidateProfile missingResume = fullyFilledProfile().resumeKey(null).build();

        assertThat(calculator.calculate(missingResume))
                .as("the design labels this state '7 / 8 SECTIONS'")
                .isEqualTo(88);
    }

    @Test
    void scoresAnEmptyProfileAtZero() {
        assertThat(calculator.calculate(CandidateProfile.builder().build())).isZero();
    }

    @Test
    void ignoresSeniorityBecauseItIsNotOneOfTheEightSections() {
        CandidateProfile seniorityOnly = CandidateProfile.builder()
                .seniority(SeniorityLevel.SENIOR)
                .build();

        assertThat(calculator.calculate(seniorityOnly)).isZero();
    }

    @Test
    void treatsBlankTextAsUnfilled() {
        CandidateProfile blankText = CandidateProfile.builder()
                .headline("   ")
                .location("")
                .resumeKey("  ")
                .build();

        assertThat(calculator.calculate(blankText)).isZero();
    }

    /** All eight scored sections filled, and no phone. */
    private CandidateProfile.CandidateProfileBuilder fullyFilledProfile() {
        return CandidateProfile.builder()
                .headline("Senior Software Engineer")
                .location("Bengaluru, India")
                .skills(Set.of("Java"))
                .links(Set.of("https://github.com/example"))
                .experiences(List.of(Experience.builder().title("Senior Software Engineer").build()))
                .educations(List.of(Education.builder().institution("Anna University").build()))
                .projects(List.of(CandidateProject.builder().name("Toolpath viewer").build()))
                .resumeKey("resumes/abc.pdf");
    }
}
