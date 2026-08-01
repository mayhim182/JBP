package com.jbp.dto;

import com.jbp.model.JobStatus;
import com.jbp.model.JobType;
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
public class JobResponse {

    private Long id;
    private String title;
    private String description;
    private Set<String> skills;
    private String location;
    private boolean remote;
    private JobType type;
    private SeniorityLevel seniority;
    private Integer salaryMin;
    private Integer salaryMax;
    private List<ScreeningQuestionDto> screeningQuestions;
    private JobStatus status;

    // Employer info shown alongside the job (includes the "Verified Employer" flag).
    private Long companyId;
    private String companyName;
    private boolean companyVerified;
}
