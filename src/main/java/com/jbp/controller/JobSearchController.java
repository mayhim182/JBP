package com.jbp.controller;

import com.jbp.dto.JobResponse;
import com.jbp.dto.JobSearchCriteria;
import com.jbp.dto.PageResponse;
import com.jbp.model.JobType;
import com.jbp.model.SeniorityLevel;
import com.jbp.service.JobSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobSearchController {

    private final JobSearchService jobSearchService;

    // Public search over PUBLISHED jobs.
    @GetMapping
    public ResponseEntity<PageResponse<JobResponse>> searchJobs(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String location,
            @RequestParam(required = false) Boolean remote,
            @RequestParam(required = false) JobType type,
            @RequestParam(required = false) SeniorityLevel seniority,
            @RequestParam(required = false) Integer salaryMin,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        log.debug("Searching jobs q='{}' location='{}' remote={} type={} seniority={} salaryMin={} page={} size={}",
                q, location, remote, type, seniority, salaryMin, page, size);

        JobSearchCriteria criteria = JobSearchCriteria.builder()
                .q(q)
                .location(location)
                .remote(remote)
                .type(type)
                .seniority(seniority)
                .salaryMin(salaryMin)
                .page(page)
                .size(size)
                .build();

        return ResponseEntity.ok(jobSearchService.search(criteria));
    }
}
