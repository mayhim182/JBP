package com.jbp.serviceimpl;

import com.jbp.model.CandidateProfile;
import com.jbp.model.Experience;
import com.jbp.model.Job;
import com.jbp.model.MatchFactorKind;
import com.jbp.model.ScorerMode;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.MatchScorer.MatchFactor;
import com.jbp.service.MatchScorer.MatchResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.3 — the rule scorer now emits a structured breakdown. Its first test class: the scorer
 * predates it and was only ever exercised indirectly through {@code MatchServiceImplTest}'s mocks.
 */
class RuleBasedMatchScorerTest {

    private final RuleBasedMatchScorer scorer = new RuleBasedMatchScorer();

    @Test
    void reproducesAScoreObservedInProduction() {
        // Exactly the case the running app returned on 2026-07-31: "skills 2/2; seniority slightly
        // below; remote; 3 roles" → 92. Rounding per factor must not have moved it.
        MatchResult result = scorer.score(
                profile(SeniorityLevel.MID, Set.of("c++", "rest"), 3, "New Delhi"),
                remoteJob(SeniorityLevel.SENIOR, Set.of("c++", "rest")));

        assertThat(result.score()).isEqualTo(92);
        assertThat(result.reason()).isEqualTo("skills 2/2; seniority slightly below; remote; 3 roles");
    }

    @Test
    void reportsOneFactorPerDimensionInDisplayOrder() {
        MatchResult result = scorer.score(
                profile(SeniorityLevel.MID, Set.of("java"), 2, "Pune"), job(SeniorityLevel.MID, Set.of("java"), "Pune"));

        assertThat(result.factors()).extracting(MatchFactor::kind)
                .containsExactly(MatchFactorKind.SKILLS, MatchFactorKind.SENIORITY,
                        MatchFactorKind.LOCATION, MatchFactorKind.EXPERIENCE);
    }

    @Test
    void weightsSumToOneHundredSoTheBarsAddUp() {
        MatchResult result = scorer.score(
                profile(SeniorityLevel.MID, Set.of("java"), 2, "Pune"), job(SeniorityLevel.MID, Set.of("java"), "Pune"));

        assertThat(result.factors().stream().mapToInt(MatchFactor::weight).sum())
                .as("design 20 shows a weight per row; if they do not total 100 the panel is lying")
                .isEqualTo(100);
    }

    @Test
    void totalEqualsTheSumOfContributions() {
        MatchResult result = scorer.score(
                profile(SeniorityLevel.JUNIOR, Set.of("java"), 1, "Pune"),
                job(SeniorityLevel.SENIOR, Set.of("java", "aws", "sql"), "Mumbai"));

        assertThat(result.score())
                .isEqualTo(result.factors().stream().mapToInt(MatchFactor::contribution).sum());
    }

    @Test
    void reportsRuleModeAndNeverClaimsMeaningSurfacedTheMatch() {
        MatchResult result = scorer.score(
                profile(SeniorityLevel.MID, Set.of("java"), 2, "Pune"), job(SeniorityLevel.MID, Set.of("java"), "Pune"));

        assertThat(result.mode()).isEqualTo(ScorerMode.RULE);
        assertThat(result.surfacedByMeaning())
                .as("there is no semantic factor here, so the claim would be unfounded")
                .isFalse();
    }

    @Test
    void eachFactorsDetailIsTheWordingAlreadyShownToUsers() {
        MatchResult result = scorer.score(
                profile(SeniorityLevel.MID, Set.of("java"), 0, "Pune"),
                job(SeniorityLevel.MID, Set.of("java", "aws"), "Mumbai"));

        assertThat(result.factors()).extracting(MatchFactor::detail)
                .containsExactly("skills 1/2", "seniority match", "location gap", "0 roles");
        assertThat(result.reason()).isEqualTo(String.join("; ",
                "skills 1/2", "seniority match", "location gap", "0 roles"));
    }

    @Test
    void producesTheSameScoreForTheSameInputsEveryTime() {
        CandidateProfile profile = profile(SeniorityLevel.MID, Set.of("java", "aws"), 2, "Pune");
        Job job = job(SeniorityLevel.SENIOR, Set.of("java", "sql"), "Pune");

        assertThat(scorer.score(profile, job).score()).isEqualTo(scorer.score(profile, job).score());
        assertThat(scorer.score(profile, job).reason()).isEqualTo(scorer.score(profile, job).reason());
    }

    @Test
    void scoresAnEmptyProfileWithoutFailing() {
        MatchResult result = scorer.score(CandidateProfile.builder().build(),
                job(SeniorityLevel.MID, Set.of("java"), "Pune"));

        assertThat(result.score()).isBetween(0, 100);
        assertThat(result.factors()).hasSize(4);
    }

    @Test
    void treatsAJobWithNoRequiredSkillsAsFullMarksOnSkills() {
        MatchResult result = scorer.score(profile(SeniorityLevel.MID, Set.of(), 2, "Pune"),
                job(SeniorityLevel.MID, Set.of(), "Pune"));

        assertThat(result.factors().get(0).score()).isEqualTo(100);
        assertThat(result.factors().get(0).detail()).isEqualTo("no specific skills required");
    }

    private CandidateProfile profile(SeniorityLevel seniority, Set<String> skills, int roles, String location) {
        return CandidateProfile.builder()
                .id(1L)
                .seniority(seniority)
                .skills(skills)
                .location(location)
                .experiences(roles == 0 ? List.of() : List.copyOf(
                        java.util.Collections.nCopies(roles, Experience.builder().title("Engineer").build())))
                .build();
    }

    private Job job(SeniorityLevel seniority, Set<String> skills, String location) {
        return Job.builder().id(2L).seniority(seniority).skills(skills).location(location).build();
    }

    private Job remoteJob(SeniorityLevel seniority, Set<String> skills) {
        return Job.builder().id(2L).seniority(seniority).skills(skills).remote(true).build();
    }
}
