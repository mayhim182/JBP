package com.jbp.controller;

import com.jbp.service.EmbeddingBackfillService;
import com.jbp.service.EmbeddingBackfillService.BackfillSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lets an admin actually run the embedding backfill.
 *
 * <p>Added because Story 13.2's "backfill runner" was, without it, a service method nothing could
 * invoke — there was no endpoint, no command and no startup hook, so existing rows could never have
 * been filled in. A method with no caller is not a runner.
 *
 * <p>Admin-only and deliberately manual. These calls spend free-tier quota, and running on startup
 * would mean every restart of a development machine racing through the table; running on a schedule
 * would mean it happening at times nobody chose. An operator asking for it is the honest trigger.
 *
 * <p>Safe to call repeatedly: the store skips anything already current, so a second run makes no
 * provider calls. The response says how many rows were examined against how many actually needed one,
 * which is also how you tell a completed run from one the rate limit cut short.
 */
@Slf4j
@RestController
@RequestMapping("/api/admin/embeddings")
@RequiredArgsConstructor
public class AdminEmbeddingController {

    private final EmbeddingBackfillService embeddingBackfillService;

    @PostMapping("/backfill/jobs")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BackfillSummary> backfillJobs() {
        log.info("Admin requested an embedding backfill for published jobs");
        return ResponseEntity.ok(embeddingBackfillService.backfillPublishedJobs());
    }

    @PostMapping("/backfill/candidate-profiles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<BackfillSummary> backfillCandidateProfiles() {
        log.info("Admin requested an embedding backfill for candidate profiles");
        return ResponseEntity.ok(embeddingBackfillService.backfillCandidateProfiles());
    }
}
