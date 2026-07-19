package com.jbp.util;

import com.jbp.model.CandidateProfile;
import org.springframework.stereotype.Component;

/**
 * Computes a 0-100 completeness score from the profile's filled sections.
 * Each of the eight sections contributes equally.
 */
@Component
public class ProfileCompletenessCalculator {

    private static final int TOTAL_SECTIONS = 8;

    public int calculate(CandidateProfile profile) {
        int filled = 0;
        if (hasText(profile.getHeadline())) filled++;
        if (hasText(profile.getLocation())) filled++;
        if (!profile.getSkills().isEmpty()) filled++;
        if (!profile.getExperiences().isEmpty()) filled++;
        if (!profile.getEducations().isEmpty()) filled++;
        if (!profile.getProjects().isEmpty()) filled++;
        if (!profile.getLinks().isEmpty()) filled++;
        if (hasText(profile.getResumeKey())) filled++;
        return (int) Math.round((filled * 100.0) / TOTAL_SECTIONS);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
