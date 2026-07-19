package com.jbp.dto;

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
    private Set<String> skills;
    private Set<String> links;
    private List<ExperienceDto> experiences;
    private List<EducationDto> educations;
    private List<ProjectDto> projects;

    private boolean hasResume;
    private String resumeFileName;

    // 0-100, driven by how many profile sections are filled.
    private int completenessPercent;
}
