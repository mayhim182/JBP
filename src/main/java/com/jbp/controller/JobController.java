package com.jbp.controller;

import com.jbp.dto.JobRequest;
import com.jbp.dto.JobResponse;
import com.jbp.service.JobService;
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

    @PostMapping
    @PreAuthorize("hasRole('RECRUITER')")
    public ResponseEntity<JobResponse> createJob(@Valid @RequestBody JobRequest request) {
        log.info("Creating draft job for current recruiter");
        JobResponse response = jobService.createJob(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
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
