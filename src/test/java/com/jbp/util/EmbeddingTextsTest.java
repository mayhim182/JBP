package com.jbp.util;

import com.jbp.model.CandidateProfile;
import com.jbp.model.Experience;
import com.jbp.model.Job;
import com.jbp.model.SeniorityLevel;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingTextsTest {

    /**
     * The one that protects the quota. {@code sourceHash} is a hash of this output, so if collection
     * order leaked into it, identical data would hash differently between runs and every record would
     * be re-embedded on every restart.
     */
    @Test
    void producesTheSameJobTextRegardlessOfSkillOrder() {
        String oneOrder = EmbeddingTexts.forJob(jobWithSkills(orderedSet("react", "java", "aws")));
        String anotherOrder = EmbeddingTexts.forJob(jobWithSkills(orderedSet("aws", "java", "react")));

        assertThat(oneOrder).isEqualTo(anotherOrder);
    }

    @Test
    void producesTheSameProfileTextRegardlessOfSkillOrder() {
        CandidateProfile first = CandidateProfile.builder()
                .headline("Frontend engineer").skills(orderedSet("react", "css")).build();
        CandidateProfile second = CandidateProfile.builder()
                .headline("Frontend engineer").skills(orderedSet("css", "react")).build();

        assertThat(EmbeddingTexts.forCandidateProfile(first))
                .isEqualTo(EmbeddingTexts.forCandidateProfile(second));
    }

    @Test
    void includesTheFieldsThatCarryMeaning() {
        String text = EmbeddingTexts.forJob(Job.builder()
                .title("Senior Backend Engineer")
                .description("Own our payments platform")
                .location("Pune")
                .seniority(SeniorityLevel.SENIOR)
                .skills(orderedSet("java", "aws"))
                .build());

        assertThat(text)
                .contains("Senior Backend Engineer")
                .contains("Own our payments platform")
                .contains("Pune")
                .contains("SENIOR")
                .contains("aws, java");
    }

    @Test
    void includesExperienceProseBecauseThatIsWhereTheSignalOftenIs() {
        CandidateProfile profile = CandidateProfile.builder()
                .headline("Engineer")
                .experiences(List.of(Experience.builder()
                        .title("Frontend Engineer")
                        .company("Acme")
                        .description("built single page apps")
                        .build()))
                .build();

        assertThat(EmbeddingTexts.forCandidateProfile(profile))
                .as("this phrase is the exact case Epic 13 exists to match against a React job")
                .contains("built single page apps");
    }

    @Test
    void returnsEmptyForARecordWithNothingToSay() {
        assertThat(EmbeddingTexts.forJob(Job.builder().build())).isEmpty();
        assertThat(EmbeddingTexts.forCandidateProfile(CandidateProfile.builder().build())).isEmpty();
    }

    private Job jobWithSkills(Set<String> skills) {
        return Job.builder().title("Engineer").skills(skills).build();
    }

    /** Insertion-ordered on purpose, so a missing sort would show up as different text. */
    private Set<String> orderedSet(String... values) {
        return new LinkedHashSet<>(List.of(values));
    }
}
