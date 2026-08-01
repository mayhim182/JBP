package com.jbp.controller;

import com.jbp.dto.GeneratedJobDescription;
import com.jbp.dto.JobDescriptionRequest;
import com.jbp.dto.JobQualityFinding;
import com.jbp.dto.JobRequest;
import com.jbp.dto.JobResponse;
import com.jbp.dto.ScreeningQuestionAnswerCount;
import com.jbp.dto.ScreeningQuestionsRequest;
import com.jbp.dto.SuggestedScreeningQuestions;
import com.jbp.service.JobService;
import com.jbp.service.ScreeningQuestionSuggester;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    /**
     * Injected directly rather than through {@link JobService}, unlike description generation.
     * Suggesting needs no job-domain context at all — no company to resolve, nothing read or
     * written — so routing it through the job service would add a pass-through method that decides
     * nothing. {@link ScreeningQuestionSuggester} is itself a service, so the "call a feature
     * through its service" rule is satisfied.
     */
    private final ScreeningQuestionSuggester screeningQuestionSuggester;

    @PostMapping
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<JobResponse> createJob(@Valid @RequestBody JobRequest request) {
        log.info("Creating draft job for current recruiter");
        JobResponse response = jobService.createJob(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Writes a first-draft description and returns it. Generates only — nothing is created or
     * updated, so this stays outside the job's lifecycle entirely.
     *
     * <p>Answers 503 when AI is switched off or the provider cannot be reached, which the editor
     * renders as its disabled-trigger state rather than as an error.
     */
    @PostMapping("/generate-description")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<GeneratedJobDescription> generateDescription(
            @Valid @RequestBody JobDescriptionRequest request) {
        log.info("Generating a job description draft for the current recruiter");
        return ResponseEntity.ok(jobService.generateDescription(request));
    }

    /**
     * Suggests screening questions and returns them. Suggests only — the recruiter accepts each one
     * individually in the editor, and nothing reaches the job until they save it.
     *
     * <p>Answers 503 when AI is switched off or unreachable, which the editor renders as a disabled
     * trigger beside an unaffected "+ Add question".
     */
    @PostMapping("/suggest-screening-questions")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<SuggestedScreeningQuestions> suggestScreeningQuestions(
            @Valid @RequestBody ScreeningQuestionsRequest request) {
        log.info("Suggesting screening questions for the current recruiter");
        return ResponseEntity.ok(screeningQuestionSuggester.suggest(
                new ScreeningQuestionSuggester.ScreeningQuestionBrief(
                        request.getTitle(), request.getSkills(), request.getSeniority())));
    }

    /**
     * The deterministic quality check. Instant, owner-only, and works identically with AI switched
     * off — which is why the editor calls this one first and paints its findings straight away.
     *
     * <p>Two endpoints rather than the one the story sketched: a single synchronous response cannot
     * deliver "rules land first, AI arrives after", and folding them together would make an AI outage
     * delay findings that never needed a model. Advisory only — neither call gates publishing.
     */
    @PostMapping("/{id}/quality-check")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<List<JobQualityFinding>> checkQuality(@PathVariable Long id) {
        log.info("Running deterministic quality rules for job id={}", id);
        return ResponseEntity.ok(jobService.checkQualityWithRules(id));
    }

    /**
     * The wording half. Returns an empty list when AI is unavailable rather than 503: the recruiter
     * already has the rule findings, so an outage means fewer findings, never a failed check.
     */
    @PostMapping("/{id}/quality-check/ai")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<List<JobQualityFinding>> checkQualityWithAi(@PathVariable Long id) {
        log.info("Running AI quality review for job id={}", id);
        return ResponseEntity.ok(jobService.checkQualityWithAi(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<JobResponse> updateJob(
            @PathVariable Long id,
            @Valid @RequestBody JobRequest request) {
        log.info("Updating job id={}", id);
        return ResponseEntity.ok(jobService.updateJob(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<Void> deleteJob(@PathVariable Long id) {
        log.info("Deleting job id={}", id);
        jobService.deleteJob(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<JobResponse> publishJob(@PathVariable Long id) {
        log.info("Publishing job id={}", id);
        return ResponseEntity.ok(jobService.publishJob(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<JobResponse> closeJob(@PathVariable Long id) {
        log.info("Closing job id={}", id);
        return ResponseEntity.ok(jobService.closeJob(id));
    }

    @PostMapping("/{id}/clone")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<JobResponse> cloneJob(@PathVariable Long id) {
        log.info("Cloning job id={}", id);
        return ResponseEntity.status(HttpStatus.CREATED).body(jobService.cloneJob(id));
    }

    /**
     * How many candidates have already answered each screening question on this job.
     *
     * <p>Its own endpoint rather than a field on the job: the editor is the only thing that wants these
     * numbers, and {@code JobResponse} is served to guests on every published job and every search hit.
     */
    @GetMapping("/{id}/screening-answer-counts")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<List<ScreeningQuestionAnswerCount>> getScreeningAnswerCounts(@PathVariable Long id) {
        log.debug("Fetching screening answer counts for job id={}", id);
        return ResponseEntity.ok(jobService.getScreeningAnswerCounts(id));
    }

    @GetMapping("/mine")
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<List<JobResponse>> getMyJobs() {
        log.debug("Fetching jobs of current recruiter");
        return ResponseEntity.ok(jobService.getMyJobs());
    }

    // Public: no authentication required. Only PUBLISHED jobs are retrievable here.
    @GetMapping("/{id}")
    public ResponseEntity<JobResponse> getPublishedJob(@PathVariable Long id) {
        log.debug("Fetching published job by id={}", id);
        return ResponseEntity.ok(jobService.getPublishedJobById(id));
    }
}
