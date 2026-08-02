package com.jbp.util;

import com.jbp.model.CandidateProfile;
import com.jbp.model.CandidateProject;
import com.jbp.model.Education;
import com.jbp.model.Experience;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * A candidate's profile as labelled lines, for a model to read.
 *
 * <p>Extracted when Story 14.3 needed the same block Story 14.2 was already building. Both tasks put
 * a profile in front of a model and neither wants the other's framing around it, so what they share
 * is exactly this: which fields, in which order, under which labels.
 *
 * <p><strong>Not {@link EmbeddingTexts}, and the difference is not cosmetic.</strong> That class
 * flattens a profile into one comma-joined line for a vector, drops projects entirely, and its output
 * is hashed as {@code sourceHash} — so borrowing it would starve these prompts of the structure they
 * need <em>and</em> make every stored embedding look stale the first time a prompt's needs changed a
 * word of it. Different job, different renderer, deliberately.
 *
 * <p>Empty fields are omitted rather than sent as blanks, so a model is never invited to write about
 * a fact it was not given.
 */
public final class CandidateProfileText {

    private static final String PART_SEPARATOR = " · ";

    private CandidateProfileText() {
    }

    /** Returns "" for a null or entirely empty profile, which callers treat as nothing to work from. */
    public static String asLabelledLines(CandidateProfile profile) {
        if (profile == null) {
            return "";
        }
        StringBuilder lines = new StringBuilder();
        appendIfPresent(lines, "Headline", profile.getHeadline());
        appendIfPresent(lines, "Location", profile.getLocation());
        appendIfPresent(lines, "Seniority", nameOf(profile.getSeniority()));
        appendIfPresent(lines, "Skills", joined(profile.getSkills()));

        for (Experience experience : nonNull(profile.getExperiences())) {
            appendIfPresent(lines, "Experience", labelled(
                    experience.getTitle(),
                    experience.getCompany(),
                    dateRange(experience.getStartDate(), experience.getEndDate()),
                    experience.getDescription()));
        }
        for (CandidateProject project : nonNull(profile.getProjects())) {
            appendIfPresent(lines, "Project", labelled(project.getName(), project.getDescription()));
        }
        // Education is included even though it cannot pass Story 14.2's eligibility gate on its own:
        // a question about what someone studied has no other truthful source, and withholding it
        // would make a task decline something the profile can actually support.
        for (Education education : nonNull(profile.getEducations())) {
            appendIfPresent(lines, "Education", labelled(
                    education.getDegree(), education.getFieldOfStudy(), education.getInstitution()));
        }
        return lines.toString();
    }

    public static void appendIfPresent(StringBuilder lines, String label, String value) {
        if (value != null && !value.isBlank()) {
            lines.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    public static String joined(Collection<String> values) {
        if (values == null) {
            return null;
        }
        return String.join(", ", values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .sorted()
                .toList());
    }

    public static String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }

    private static String labelled(String... parts) {
        return String.join(PART_SEPARATOR, presentOnly(parts));
    }

    private static String dateRange(String from, String to) {
        List<String> present = presentOnly(from, to);
        return present.isEmpty() ? null : String.join(" to ", present);
    }

    private static List<String> presentOnly(String... parts) {
        return Arrays.stream(parts)
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .toList();
    }

    private static <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }
}
