package com.jbp.controller;

import com.jbp.dto.InterviewPrepResponse;
import com.jbp.service.InterviewPrepService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Story 14.1's endpoint.
 *
 * <p>Its own controller rather than another method on {@code JobController}: that one serves the
 * public job surface, and this is candidate-only. Keeping them apart means the public controller's
 * methods stay uniformly public, which is easier to audit than a mixed one.
 *
 * <p><strong>Guests are excluded by the role check, not by a special case.</strong>
 * {@code SecurityConfig} opens {@code GET /api/jobs/*} — a single path segment — so nothing beneath
 * a job id is public, and this path sits beneath one.
 *
 * <p>A 503 from here is a normal outcome, not an incident: it is how design 21b's state D is reached
 * when the model cannot produce questions.
 */
@Slf4j
@RestController
@RequestMapping("/api/jobs/{jobId}/interview-prep")
@RequiredArgsConstructor
public class InterviewPrepController {

    private final InterviewPrepService interviewPrepService;

    @GetMapping
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<InterviewPrepResponse> getInterviewPrep(@PathVariable Long jobId) {
        log.debug("Preparing interview questions for job {}", jobId);
        return ResponseEntity.ok(interviewPrepService.getInterviewPrepForJob(jobId));
    }
}
