package com.jbp.controller;

import com.jbp.dto.ApplicationResponse;
import com.jbp.dto.ApplicationReviewRequest;
import com.jbp.dto.ApplicationStatusUpdateRequest;
import com.jbp.dto.ApplyRequest;
import com.jbp.service.CandidateApplicationService;
import com.jbp.service.RecruiterApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class ApplicationController {

    private final CandidateApplicationService candidateApplicationService;
    private final RecruiterApplicationService recruiterApplicationService;

    // ---- Candidate ----

    @PostMapping("/jobs/{jobId}/apply")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApplicationResponse> apply(
            @PathVariable Long jobId,
            @RequestBody(required = false) ApplyRequest request) {
        log.info("Candidate applying to job {}", jobId);
        return ResponseEntity.status(HttpStatus.CREATED).body(candidateApplicationService.apply(jobId, request));
    }

    @GetMapping("/applications/mine")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<List<ApplicationResponse>> getMyApplications() {
        log.debug("Fetching current candidate's applications");
        return ResponseEntity.ok(candidateApplicationService.getMyApplications());
    }

    // ---- Recruiter ----

    @GetMapping("/jobs/{jobId}/applications")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<List<ApplicationResponse>> getApplicants(@PathVariable Long jobId) {
        log.debug("Fetching applicants for job {}", jobId);
        return ResponseEntity.ok(recruiterApplicationService.getApplicantsForJob(jobId));
    }

    @GetMapping("/applications/{id}")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<ApplicationResponse> openApplication(@PathVariable Long id) {
        log.info("Recruiter opening application {}", id);
        return ResponseEntity.ok(recruiterApplicationService.openApplication(id));
    }

    @PostMapping("/applications/{id}/status")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<ApplicationResponse> updateStatus(
            @PathVariable Long id,
            @Valid @RequestBody ApplicationStatusUpdateRequest request) {
        log.info("Recruiter moving application {} to {}", id, request.getStatus());
        return ResponseEntity.ok(recruiterApplicationService.updateStatus(id, request));
    }

    @PostMapping("/applications/{id}/notes")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<ApplicationResponse> updateReview(
            @PathVariable Long id,
            @RequestBody ApplicationReviewRequest request) {
        log.info("Recruiter updating review for application {}", id);
        return ResponseEntity.ok(recruiterApplicationService.updateReview(id, request));
    }
}
