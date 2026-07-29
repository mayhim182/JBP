package com.jbp.dto;

import com.jbp.model.SeniorityLevel;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
public class CandidateProfileRequest {

    private String headline;
    private String location;

    /**
     * Optional. Checked for length and permitted characters only, never for format: candidates
     * are in India and abroad, and every "valid phone number" regex ends up rejecting somebody's
     * real number.
     */
    @Size(max = 20, message = "Phone number must be 20 characters or fewer")
    @Pattern(regexp = "[\\d+()\\s-]*", message = "Phone number may contain only digits, spaces and + - ( )")
    private String phone;

    private SeniorityLevel seniority;
    private Set<String> skills;
    private Set<String> links;
    private List<ExperienceDto> experiences;
    private List<EducationDto> educations;
    private List<ProjectDto> projects;
}
