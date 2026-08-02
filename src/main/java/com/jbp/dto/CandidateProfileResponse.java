package com.jbp.dto;

import com.jbp.model.SeniorityLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CandidateProfileResponse {

    private Long id;
    private String headline;
    private String location;
    private String phone;
    private SeniorityLevel seniority;
    private Set<String> skills;
    private Set<String> links;
    private List<ExperienceDto> experiences;
    private List<EducationDto> educations;
    private List<ProjectDto> projects;

    private boolean hasResume;
    private String resumeFileName;

    // 0-100, driven by how many profile sections are filled.
    private int completenessPercent;

    /**
     * Whether this profile carries enough of the candidate's own history for Story 14.2 to draft a
     * screening answer from — at least one role or one project with real content.
     *
     * <p>Derived and sent rather than left for the client to work out, so the threshold exists once,
     * in {@link com.jbp.util.AnswerDraftEligibility}. The apply dialog uses it to disable its draft
     * triggers without making a call that was always going to be refused; the endpoint checks the
     * same rule itself, so a client ignoring this flag still cannot get an invented answer.
     *
     * <p>Not the same question as {@code completenessPercent}. A candidate can be most of the way to
     * a complete profile on headline, location, skills and education and still have nothing here to
     * ground an answer in.
     */
    private boolean canDraftAnswers;
}
