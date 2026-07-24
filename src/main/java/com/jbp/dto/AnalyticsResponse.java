package com.jbp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

/**
 * Platform-wide counts for the admin dashboard.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AnalyticsResponse {

    private long totalUsers;
    private Map<String, Long> usersByRole;

    private long totalCompanies;
    private Map<String, Long> companiesByStatus;

    private long totalJobs;
    private Map<String, Long> jobsByStatus;

    private long totalApplications;
    private Map<String, Long> applicationsByStatus;
}
