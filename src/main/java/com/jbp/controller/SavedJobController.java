package com.jbp.controller;

import com.jbp.dto.JobResponse;
import com.jbp.service.SavedJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/candidate/saved-jobs")
@RequiredArgsConstructor
public class SavedJobController {

    private final SavedJobService savedJobService;

    @PostMapping("/{jobId}")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<Void> saveJob(@PathVariable Long jobId) {
        log.info("Saving job {} for current candidate", jobId);
        savedJobService.saveJob(jobId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{jobId}")
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<Void> unsaveJob(@PathVariable Long jobId) {
        log.info("Unsaving job {} for current candidate", jobId);
        savedJobService.unsaveJob(jobId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    @PreAuthorize("hasRole('CANDIDATE')")
    public ResponseEntity<List<JobResponse>> getSavedJobs() {
        log.debug("Listing saved jobs for current candidate");
        return ResponseEntity.ok(savedJobService.getSavedJobs());
    }
}
