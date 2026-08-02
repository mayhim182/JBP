package com.jbp.controller;

import com.jbp.dto.ApplicantSummary;
import com.jbp.dto.ApplicationResponse;
import com.jbp.dto.ApplicationReviewRequest;
import com.jbp.dto.ApplicationStatusUpdateRequest;
import com.jbp.dto.ApplyRequest;
import com.jbp.dto.DraftAnswerRequest;
import com.jbp.dto.DraftAnswerResponse;
import com.jbp.service.ApplicantSummaryService;
import com.jbp.service.CandidateApplicationService;
import com.jbp.service.RecruiterApplicationService;
import com.jbp.service.ScreeningAnswerDraftService;
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
    private final ScreeningAnswerDraftService screeningAnswerDraftService;
    private final ApplicantSummaryService applicantSummaryService;

    // ---- Candidate ----

    @PostMapping("/jobs/{jobId}/apply")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<ApplicationResponse> apply(
            @PathVariable Long jobId,
            @RequestBody(required = false) ApplyRequest request) {
        log.info("Candidate applying to job {}", jobId);
        return ResponseEntity.status(HttpStatus.CREATED).body(candidateApplicationService.apply(jobId, request));
    }

    /**
     * Drafts one screening answer from the signed-in candidate's own profile (Story 14.2).
     *
     * <p>Nothing is created or stored — the draft goes into a field the candidate then edits and
     * submits, or does not. It sits under {@code /applications} rather than {@code /jobs/{id}} on
     * purpose: the draft never sees the posting, so there is no job in its path.
     *
     * <p>Four refusals, each meaning something different to the dialog: 400 for an answer type that
     * has no trigger, 422 when the profile cannot ground an answer (design 22b G), 429 when the
     * candidate's daily allowance is spent (22b D), 503 when the model could not be reached (22b F).
     */
    @PostMapping("/applications/draft-answer")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<DraftAnswerResponse> draftScreeningAnswer(
            @Valid @RequestBody DraftAnswerRequest request) {
        log.info("Drafting a screening answer for the current candidate");
        return ResponseEntity.ok(screeningAnswerDraftService.draftAnswer(request));
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

    /**
     * The three-line read on one applicant (Story 14.3) — strongest fit, main gap, one thing worth
     * probing. Complements "Why this rank" rather than restating it: the model is never told the
     * score, so it cannot echo one.
     *
     * <p>Its own endpoint rather than a field on {@link ApplicationResponse}, so the drawer paints
     * the deterministic half immediately and never waits on prose — design 24 B1's rule that triage
     * is not blocked by a model call.
     *
     * <p>Four refusals: 409 when the application is already decided (24 B3), 422 when the profile
     * cannot ground a read (24 B4), 429 when asked far faster than a human triages, 503 when the
     * model could not be reached (24 B2).
     */
    @GetMapping("/applications/{id}/summary")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<ApplicantSummary> summariseApplicant(@PathVariable Long id) {
        log.debug("Summarising applicant for application {}", id);
        return ResponseEntity.ok(applicantSummaryService.summariseApplicant(id));
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
