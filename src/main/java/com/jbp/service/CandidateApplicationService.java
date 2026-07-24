package com.jbp.service;

import com.jbp.dto.ApplicationResponse;
import com.jbp.dto.ApplyRequest;

import java.util.List;

/** Application operations available to the candidate (applicant) side. */
public interface CandidateApplicationService {

    ApplicationResponse apply(Long jobId, ApplyRequest request);

    List<ApplicationResponse> getMyApplications();
}
