package com.jbp.util;

import com.jbp.model.CandidateProfile;
import org.springframework.stereotype.Component;

/**
 * Computes a 0-100 completeness score from the profile's filled sections.
 * Each of the eight sections contributes equally.
 */
@Component
public class ProfileCompletenessCalculator {

    /**
     * Eight, and it must stay eight. Phone was added to the profile without becoming a scored
     * section on purpose: making it a ninth would silently drop every currently-100% profile to
     * 89%, and the design's profile screen prints "7 / 8 SECTIONS" as fixed text. Anything new
     * that the profile stores but the candidate is not asked to complete belongs outside this
     * count.
     */
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
