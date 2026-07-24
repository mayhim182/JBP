package com.jbp.dto;

import com.jbp.model.ApplicationStatus;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApplicationResponse {

    private Long id;

    private Long jobId;
    private String jobTitle;
    private String companyName;

    private Long candidateId;
    private String candidateName;
    private String candidateEmail;

    private ApplicationStatus status;
    private String rejectionReason;
    private List<ScreeningAnswerDto> screeningAnswers;

    // Populated only in the recruiter view (kept out of the candidate's tracker).
    private String recruiterNotes;
    private Integer rating;

    // Populated only in the ranked applicant list (recruiter surface of matching).
    private Integer matchScore;
    private String matchReason;
}
