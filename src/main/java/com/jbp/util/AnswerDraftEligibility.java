package com.jbp.util;

import com.jbp.model.CandidateProfile;
import com.jbp.model.CandidateProject;
import com.jbp.model.Experience;

import java.util.List;
import java.util.Objects;

/**
 * Whether a candidate's profile carries enough of their own history to ground a drafted screening
 * answer — Story 14.2's gate, confirmed with the PO on 2026-08-01.
 *
 * <p><strong>At least one experience entry OR one project.</strong> Skills and seniority are labels
 * rather than evidence: "Java, Kafka, Senior" cannot produce "describe a failure you debugged"
 * without inventing the failure, and education alone cannot either. The {@code OR} is what lets a new
 * graduate with projects and no jobs through, which is the case it exists for.
 *
 * <p>Stated here once and read in two places — the service refuses the draft outright, and
 * {@code CandidateProfileResponse} carries the same answer out to the apply dialog so it can disable
 * its triggers without a speculative call. The client is deliberately <em>told</em> rather than left
 * to re-derive the rule in TypeScript, because two copies of a threshold drift.
 *
 * <p>An entry with no substance does not count. A row holding only a job title cannot ground an
 * answer any better than a skill can, and counting it would put the candidate past the gate only to
 * have the model decline — a worse outcome than being told plainly that the profile is thin.
 */
public final class AnswerDraftEligibility {

    private AnswerDraftEligibility() {
    }

    public static boolean canGroundADraftedAnswer(CandidateProfile profile) {
        if (profile == null) {
            return false;
        }
        return hasUsableExperience(profile.getExperiences()) || hasUsableProject(profile.getProjects());
    }

    /**
     * A role counts once it names where it was, not merely what it was called. Title alone is the
     * "thin content" case design 22b G describes: it passes a structural check and still leaves the
     * model with nothing to write from.
     */
    private static boolean hasUsableExperience(List<Experience> experiences) {
        return nonNull(experiences).stream().anyMatch(experience ->
                hasText(experience.getCompany()) || hasText(experience.getDescription()));
    }

    private static boolean hasUsableProject(List<CandidateProject> projects) {
        return nonNull(projects).stream().anyMatch(project ->
                hasText(project.getName()) && hasText(project.getDescription()));
    }

    private static <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
