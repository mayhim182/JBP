package com.jbp.controller;

import com.jbp.dto.JobMatchResponse;
import com.jbp.service.MatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;

    @GetMapping("/candidate/job-matches")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<List<JobMatchResponse>> getMyJobMatches() {
        log.debug("Computing job matches for current candidate");
        return ResponseEntity.ok(matchService.getJobMatchesForCurrentCandidate());
    }

    @GetMapping("/jobs/{jobId}/match")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<JobMatchResponse> getJobMatch(@PathVariable Long jobId) {
        log.debug("Computing match for job {} for current candidate", jobId);
        return ResponseEntity.ok(matchService.getJobMatchForCurrentCandidate(jobId));
    }
}
