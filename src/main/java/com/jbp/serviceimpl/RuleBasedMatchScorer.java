package com.jbp.serviceimpl;

import com.jbp.model.CandidateProfile;
import com.jbp.model.Job;
import com.jbp.service.MatchScorer;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rule-based, explainable match scorer. Combines four weighted dimensions into a
 * 0-100 score and builds a short reason describing each contribution.
 */
@Component
public class RuleBasedMatchScorer implements MatchScorer {

    private static final int SKILLS_WEIGHT = 50;
    private static final int SENIORITY_WEIGHT = 20;
    private static final int LOCATION_WEIGHT = 15;
    private static final int EXPERIENCE_WEIGHT = 15;

    @Override
    public MatchResult score(CandidateProfile profile, Job job) {
        List<String> reasons = new ArrayList<>();

        double skills = scoreSkills(profile, job, reasons);
        double seniority = scoreSeniority(profile, job, reasons);
        double location = scoreLocation(profile, job, reasons);
        double experience = scoreExperience(profile, reasons);

        int total = (int) Math.round(
                skills * SKILLS_WEIGHT
                        + seniority * SENIORITY_WEIGHT
                        + location * LOCATION_WEIGHT
                        + experience * EXPERIENCE_WEIGHT);
        total = Math.max(0, Math.min(100, total));
        return new MatchResult(total, String.join("; ", reasons));
    }

    private double scoreSkills(CandidateProfile profile, Job job, List<String> reasons) {
        Set<String> jobSkills = toLowerSet(job.getSkills());
        if (jobSkills.isEmpty()) {
            reasons.add("no specific skills required");
            return 1.0;
        }
        Set<String> candidateSkills = toLowerSet(profile.getSkills());
        long matched = jobSkills.stream().filter(candidateSkills::contains).count();
        reasons.add("skills " + matched + "/" + jobSkills.size());
        return (double) matched / jobSkills.size();
    }

    private double scoreSeniority(CandidateProfile profile, Job job, List<String> reasons) {
        if (profile.getSeniority() == null || job.getSeniority() == null) {
            reasons.add("seniority n/a");
            return 0.5;
        }
        int diff = profile.getSeniority().ordinal() - job.getSeniority().ordinal();
        if (diff == 0) {
            reasons.add("seniority match");
            return 1.0;
        }
        if (diff > 0) {
            reasons.add("seniority above");
            return 0.8;
        }
        if (diff == -1) {
            reasons.add("seniority slightly below");
            return 0.6;
        }
        reasons.add("seniority gap");
        return 0.3;
    }

    private double scoreLocation(CandidateProfile profile, Job job, List<String> reasons) {
        if (job.isRemote()) {
            reasons.add("remote");
            return 1.0;
        }
        String jobLocation = normalize(job.getLocation());
        String candidateLocation = normalize(profile.getLocation());
        if (jobLocation == null || candidateLocation == null) {
            reasons.add("location n/a");
            return 0.5;
        }
        if (jobLocation.equals(candidateLocation)
                || jobLocation.contains(candidateLocation)
                || candidateLocation.contains(jobLocation)) {
            reasons.add("location match");
            return 1.0;
        }
        reasons.add("location gap");
        return 0.2;
    }

    private double scoreExperience(CandidateProfile profile, List<String> reasons) {
        int count = profile.getExperiences() == null ? 0 : profile.getExperiences().size();
        reasons.add(count + (count == 1 ? " role" : " roles"));
        if (count == 0) {
            return 0.0;
        }
        if (count == 1) {
            return 0.4;
        }
        if (count == 2) {
            return 0.7;
        }
        return 1.0;
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
