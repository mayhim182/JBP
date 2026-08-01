package com.jbp.controller;

import com.jbp.dto.JobMatchResponse;
import com.jbp.dto.JobMatchScoreResponse;
import com.jbp.dto.MatchExplanationResponse;
import com.jbp.service.MatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class MatchController {

    private final MatchService matchService;

    /**
     * The current candidate's best matches, ranked best-first.
     *
     * <p>Bounded since Story 13.0 — it previously scored every published job in the table on every
     * call. The response shape is unchanged, deliberately: the dashboard's top-matches panel and the
     * matches page both read it as a plain array, and neither has any compile-time link to this
     * signature, so changing the shape would have failed silently in a browser rather than in a build.
     */
    @GetMapping("/candidate/job-matches")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<List<JobMatchResponse>> getMyJobMatches() {
        log.debug("Computing job matches for current candidate");
        return ResponseEntity.ok(matchService.getJobMatchesForCurrentCandidate());
    }

    /**
     * Scores a list view's jobs in one request.
     *
     * <p>Added by Story 13.0 to replace one request per row. The path deliberately sits under
     * {@code /candidate/} rather than reading as {@code /jobs/matches}: the latter would be a literal
     * segment competing with {@code GET /api/jobs/{id}} for the same position, and while Spring
     * resolves that in the literal's favour, a routing rule is a poor thing to depend on when a
     * different path costs nothing.
     *
     * <p>Ids that are unknown or unpublished come back absent rather than as an error, which keeps the
     * per-row tolerance the client already had.
     */
    @GetMapping("/candidate/job-match-scores")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<List<JobMatchScoreResponse>> getMyJobMatchScores(
            @RequestParam List<Long> ids) {
        log.debug("Scoring {} jobs for the current candidate in one request", ids.size());
        return ResponseEntity.ok(matchService.getJobMatchScoresForCurrentCandidate(ids));
    }

    /**
     * Call 1 of Story 13.5's two-call split: the score, its structured breakdown and the
     * {@code scoreVersion}. Everything here is computed, so it renders immediately.
     */
    @GetMapping("/jobs/{jobId}/match")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<JobMatchResponse> getJobMatch(@PathVariable Long jobId) {
        log.debug("Computing match for job {} for current candidate", jobId);
        return ResponseEntity.ok(matchService.getJobMatchForCurrentCandidate(jobId));
    }

    /**
     * Call 2: the same match in plain language.
     *
     * <p>A separate request rather than a field on the one above, because this one needs a model — slow,
     * rate limited and sometimes unavailable — and the number must not wait on it. The response repeats
     * {@code scoreVersion} so the client can discard prose that was generated for a score it is no
     * longer showing.
     */
    @GetMapping("/jobs/{jobId}/match/explanation")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<MatchExplanationResponse> getJobMatchExplanation(@PathVariable Long jobId) {
        log.debug("Explaining match for job {} for current candidate", jobId);
        return ResponseEntity.ok(matchService.getJobMatchExplanationForCurrentCandidate(jobId));
    }
}
