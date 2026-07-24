package com.jbp.controller;

import com.jbp.dto.AdminRejectRequest;
import com.jbp.dto.AdminUserResponse;
import com.jbp.dto.AnalyticsResponse;
import com.jbp.dto.CompanyResponse;
import com.jbp.dto.JobResponse;
import com.jbp.service.AdminAnalyticsService;
import com.jbp.service.AdminCompanyService;
import com.jbp.service.AdminJobService;
import com.jbp.service.AdminUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminController {

    private final AdminCompanyService adminCompanyService;
    private final AdminJobService adminJobService;
    private final AdminUserService adminUserService;
    private final AdminAnalyticsService adminAnalyticsService;

    // ---- 9.1 Company verification ----

    @GetMapping("/companies/pending")
    public ResponseEntity<List<CompanyResponse>> getPendingCompanies() {
        return ResponseEntity.ok(adminCompanyService.getPendingCompanies());
    }

    @PostMapping("/companies/{id}/approve")
    public ResponseEntity<CompanyResponse> approveCompany(@PathVariable Long id) {
        return ResponseEntity.ok(adminCompanyService.approveCompany(id));
    }

    @PostMapping("/companies/{id}/reject")
    public ResponseEntity<CompanyResponse> rejectCompany(
            @PathVariable Long id,
            @RequestBody(required = false) AdminRejectRequest request) {
        String reason = request == null ? null : request.getReason();
        return ResponseEntity.ok(adminCompanyService.rejectCompany(id, reason));
    }

    // ---- 9.2 Job moderation ----

    @GetMapping("/jobs/pending")
    public ResponseEntity<List<JobResponse>> getPendingJobs() {
        return ResponseEntity.ok(adminJobService.getPendingJobs());
    }

    @PostMapping("/jobs/{id}/approve")
    public ResponseEntity<JobResponse> approveJob(@PathVariable Long id) {
        return ResponseEntity.ok(adminJobService.approveJob(id));
    }

    @PostMapping("/jobs/{id}/reject")
    public ResponseEntity<JobResponse> rejectJob(
            @PathVariable Long id,
            @RequestBody(required = false) AdminRejectRequest request) {
        String reason = request == null ? null : request.getReason();
        return ResponseEntity.ok(adminJobService.rejectJob(id, reason));
    }

    // ---- 9.3 User management ----

    @GetMapping("/users")
    public ResponseEntity<List<AdminUserResponse>> getUsers(@RequestParam(required = false) String q) {
        return ResponseEntity.ok(adminUserService.getUsers(q));
    }

    @PostMapping("/users/{id}/ban")
    public ResponseEntity<AdminUserResponse> banUser(@PathVariable Long id) {
        return ResponseEntity.ok(adminUserService.banUser(id));
    }

    @PostMapping("/users/{id}/reactivate")
    public ResponseEntity<AdminUserResponse> reactivateUser(@PathVariable Long id) {
        return ResponseEntity.ok(adminUserService.reactivateUser(id));
    }

    // ---- 9.4 Analytics ----

    @GetMapping("/analytics")
    public ResponseEntity<AnalyticsResponse> getAnalytics() {
        return ResponseEntity.ok(adminAnalyticsService.getAnalytics());
    }
}
