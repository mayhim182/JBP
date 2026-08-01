package com.jbp.util;

import com.jbp.model.CandidateProfile;
import com.jbp.model.Experience;
import com.jbp.model.Job;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Builds the text that gets embedded for a job or a candidate profile.
 *
 * <p>One place, because {@code sourceHash} is a hash of exactly this output. If two callers built the
 * text even slightly differently, the same record would look changed to one and unchanged to the
 * other, and the "only re-embed when the text changes" rule would quietly stop holding.
 *
 * <p><strong>Every collection is sorted.</strong> Skills are a {@code HashSet}, whose iteration order
 * is not stable across JVM runs, so hashing them unsorted would produce a different hash for identical
 * data and re-embed every record on every restart — burning free-tier quota to store a vector that was
 * already correct. This is the single most important line in the class.
 *
 * <p>Only fields that carry meaning are included. Salary, remote flag and job type are deliberately
 * left out: they are filters the rule scorer already handles exactly, and feeding numbers into a
 * semantic vector adds noise to the thing that is supposed to capture wording.
 */
public final class EmbeddingTexts {

    private static final String SEPARATOR = ". ";

    private EmbeddingTexts() {
    }

    public static String forJob(Job job) {
        return join(
                job.getTitle(),
                job.getDescription(),
                job.getLocation(),
                nameOf(job.getSeniority()),
                sorted(job.getSkills()));
    }

    public static String forCandidateProfile(CandidateProfile profile) {
        return join(
                profile.getHeadline(),
                profile.getLocation(),
                nameOf(profile.getSeniority()),
                sorted(profile.getSkills()),
                experienceText(profile.getExperiences()));
    }

    /**
     * Experience titles and companies, in the order the candidate listed them — a {@code List}, so its
     * order is theirs and is already stable. Descriptions are included because a role written out in
     * prose is often where the real signal is; "built single page apps" is exactly the case Epic 13
     * exists to catch.
     */
    private static String experienceText(List<Experience> experiences) {
        if (experiences == null) {
            return null;
        }
        return experiences.stream()
                .filter(Objects::nonNull)
                .map(experience -> join(
                        experience.getTitle(), experience.getCompany(), experience.getDescription()))
                .filter(text -> !text.isEmpty())
                .reduce((first, second) -> first + SEPARATOR + second)
                .orElse(null);
    }

    private static String sorted(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .sorted()
                .reduce((first, second) -> first + ", " + second)
                .orElse(null);
    }

    private static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String join(String... parts) {
        return Stream.of(parts)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .reduce((first, second) -> first + SEPARATOR + second)
                .orElse("");
    }
}
