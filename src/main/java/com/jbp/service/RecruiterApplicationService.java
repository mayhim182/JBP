package com.jbp.service;

import com.jbp.dto.ApplicationResponse;
import com.jbp.dto.ApplicationReviewRequest;
import com.jbp.dto.ApplicationStatusUpdateRequest;

import java.util.List;

/** Application operations available to the recruiter (hiring) side. */
public interface RecruiterApplicationService {

    List<ApplicationResponse> getApplicantsForJob(Long jobId);

    /** Opens an applicant; moves APPLIED -> VIEWED as a side effect. */
    ApplicationResponse openApplication(Long applicationId);

    ApplicationResponse updateStatus(Long applicationId, ApplicationStatusUpdateRequest request);

    ApplicationResponse updateReview(Long applicationId, ApplicationReviewRequest request);
}
