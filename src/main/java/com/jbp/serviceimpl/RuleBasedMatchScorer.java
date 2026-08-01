package com.jbp.serviceimpl;

import com.jbp.model.CandidateProfile;
import com.jbp.model.Job;
import com.jbp.model.MatchFactorKind;
import com.jbp.model.ScorerMode;
import com.jbp.service.MatchScorer;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rule-based, explainable match scorer. Combines four weighted dimensions into a
 * 0-100 score and builds a short reason describing each contribution.
 *
 * <p>Story 13.3 added the structured breakdown. Each dimension now returns a {@code MatchFactor}
 * carrying its own weight and its own 0-100 result, instead of a bare double plus a side-effecting list,
 * and the total is assembled from those. That gives one source of truth per dimension: previously the
 * weight lived in the total and the wording lived in a mutable list, with nothing tying them together.
 *
 * <p><strong>One consequence, stated rather than glossed:</strong> a factor's result is now rounded to a
 * whole percent before being weighted, where the old code summed raw fractions and rounded once. Every
 * whole-numbered case is identical — a real observed result, {@code skills 2/2; seniority slightly
 * below; remote; 3 roles}, is 92 under both — but a fractional skills ratio can land a single point
 * either way. Accepted deliberately: the alternative is a factor whose displayed bar disagrees with the
 * points it contributed, and a bar that does not add up is worse than a score that moves by one.
 *
 * <p>No longer a {@code @Component}: {@code MatchScorerConfig} decides which scorer the application
 * injects, and this one is also constructed directly as the embedding scorer's fallback.
 */
public class RuleBasedMatchScorer implements MatchScorer {

    static final int SKILLS_WEIGHT = 50;
    static final int SENIORITY_WEIGHT = 20;
    static final int LOCATION_WEIGHT = 15;
    static final int EXPERIENCE_WEIGHT = 15;

    @Override
    public MatchResult score(CandidateProfile profile, Job job) {
        List<MatchFactor> factors = factorsFor(profile, job);
        return new MatchResult(
                totalOf(factors),
                reasonFrom(factors),
                factors,
                ScorerMode.RULE,
                // The rule scorer has no semantic factor, so nothing can have been surfaced by meaning.
                false);
    }

    /**
     * The four rule factors, in the order design 20 draws them.
     *
     * <p>Package-private so the embedding and hybrid scorers can reuse the rule breakdown rather than
     * re-deriving it — the point of Story 13.4 is combining these signals, not recomputing them.
     */
    List<MatchFactor> factorsFor(CandidateProfile profile, Job job) {
        return List.of(
                skillsFactor(profile, job),
                seniorityFactor(profile, job),
                locationFactor(profile, job),
                experienceFactor(profile));
    }

    /** Sum of weighted contributions, clamped — the same arithmetic as before the breakdown existed. */
    static int totalOf(List<MatchFactor> factors) {
        int total = factors.stream().mapToInt(MatchFactor::contribution).sum();
        return Math.max(0, Math.min(100, total));
    }

    static String reasonFrom(List<MatchFactor> factors) {
        return factors.stream().map(MatchFactor::detail).collect(Collectors.joining("; "));
    }

    private MatchFactor skillsFactor(CandidateProfile profile, Job job) {
        Set<String> jobSkills = toLowerSet(job.getSkills());
        if (jobSkills.isEmpty()) {
            return factor(MatchFactorKind.SKILLS, SKILLS_WEIGHT, 100, "no specific skills required");
        }
        Set<String> candidateSkills = toLowerSet(profile.getSkills());
        long matched = jobSkills.stream().filter(candidateSkills::contains).count();
        return factor(MatchFactorKind.SKILLS, SKILLS_WEIGHT,
                percent((double) matched / jobSkills.size()),
                "skills " + matched + "/" + jobSkills.size());
    }

    private MatchFactor seniorityFactor(CandidateProfile profile, Job job) {
        if (profile.getSeniority() == null || job.getSeniority() == null) {
            return factor(MatchFactorKind.SENIORITY, SENIORITY_WEIGHT, 50, "seniority n/a");
        }
        int diff = profile.getSeniority().ordinal() - job.getSeniority().ordinal();
        if (diff == 0) {
            return factor(MatchFactorKind.SENIORITY, SENIORITY_WEIGHT, 100, "seniority match");
        }
        if (diff > 0) {
            return factor(MatchFactorKind.SENIORITY, SENIORITY_WEIGHT, 80, "seniority above");
        }
        if (diff == -1) {
            return factor(MatchFactorKind.SENIORITY, SENIORITY_WEIGHT, 60, "seniority slightly below");
        }
        return factor(MatchFactorKind.SENIORITY, SENIORITY_WEIGHT, 30, "seniority gap");
    }

    private MatchFactor locationFactor(CandidateProfile profile, Job job) {
        if (job.isRemote()) {
            return factor(MatchFactorKind.LOCATION, LOCATION_WEIGHT, 100, "remote");
        }
        String jobLocation = normalize(job.getLocation());
        String candidateLocation = normalize(profile.getLocation());
        if (jobLocation == null || candidateLocation == null) {
            return factor(MatchFactorKind.LOCATION, LOCATION_WEIGHT, 50, "location n/a");
        }
        if (jobLocation.equals(candidateLocation)
                || jobLocation.contains(candidateLocation)
                || candidateLocation.contains(jobLocation)) {
            return factor(MatchFactorKind.LOCATION, LOCATION_WEIGHT, 100, "location match");
        }
        return factor(MatchFactorKind.LOCATION, LOCATION_WEIGHT, 20, "location gap");
    }

    private MatchFactor experienceFactor(CandidateProfile profile) {
        int count = profile.getExperiences() == null ? 0 : profile.getExperiences().size();
        String detail = count + (count == 1 ? " role" : " roles");
        int score = switch (count) {
            case 0 -> 0;
            case 1 -> 40;
            case 2 -> 70;
            default -> 100;
        };
        return factor(MatchFactorKind.EXPERIENCE, EXPERIENCE_WEIGHT, score, detail);
    }

    private MatchFactor factor(MatchFactorKind kind, int weight, int score, String detail) {
        return new MatchFactor(kind, weight, score, detail);
    }

    private int percent(double fraction) {
        return (int) Math.round(fraction * 100);
    }

    private Set<String> toLowerSet(Set<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    private String normalize(String value) {
        return (value == null || value.isBlank()) ? null : value.trim().toLowerCase(Locale.ROOT);
    }
}
