package com.jbp.dto;

import com.jbp.model.SeniorityLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Set;

/**
 * Result of uploading a resume: the stored file's metadata plus parsing SUGGESTIONS.
 * Suggestions are for the candidate to review and apply via the profile update; nothing is
 * written to the profile automatically.
 *
 * <p>Which sections are populated depends on {@code app.resume.parser}. The deterministic parser
 * fills email, phone and skills; the LLM parser adds the rest. Every field may be null or empty —
 * a resume that cannot be read still uploads successfully and simply suggests nothing.
 *
 * <p>Experience, education and project suggestions reuse the same DTOs the profile update accepts,
 * so the candidate's chosen suggestions can be sent straight back without translation.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ResumeUploadResponse {

    private String resumeFileName;
    private String contentType;

    private String suggestedEmail;
    private String suggestedPhone;
    private Set<String> suggestedSkills;

    private String suggestedHeadline;
    private String suggestedLocation;
    private SeniorityLevel suggestedSeniority;
    private List<ExperienceDto> suggestedExperiences;
    private List<EducationDto> suggestedEducations;
    private List<ProjectDto> suggestedProjects;
    private Set<String> suggestedLinks;
}
