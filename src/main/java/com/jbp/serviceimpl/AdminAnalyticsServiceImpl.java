package com.jbp.serviceimpl;

import com.jbp.dto.AnalyticsResponse;
import com.jbp.model.ApplicationStatus;
import com.jbp.model.JobStatus;
import com.jbp.model.RoleName;
import com.jbp.model.VerificationStatus;
import com.jbp.repository.ApplicationRepository;
import com.jbp.repository.CompanyRepository;
import com.jbp.repository.JobRepository;
import com.jbp.repository.UserRepository;
import com.jbp.service.AdminAnalyticsService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminAnalyticsServiceImpl implements AdminAnalyticsService {

    private final UserRepository userRepository;
    private final CompanyRepository companyRepository;
    private final JobRepository jobRepository;
    private final ApplicationRepository applicationRepository;

    @Override
    public AnalyticsResponse getAnalytics() {
        Map<String, Long> usersByRole = new LinkedHashMap<>();
        for (RoleName role : RoleName.values()) {
            usersByRole.put(role.name(), userRepository.countByRoles_Name(role));
        }

        Map<String, Long> companiesByStatus = new LinkedHashMap<>();
        for (VerificationStatus status : VerificationStatus.values()) {
            companiesByStatus.put(status.name(), companyRepository.countByStatus(status));
        }

        Map<String, Long> jobsByStatus = new LinkedHashMap<>();
        for (JobStatus status : JobStatus.values()) {
            jobsByStatus.put(status.name(), jobRepository.countByStatus(status));
        }

        Map<String, Long> applicationsByStatus = new LinkedHashMap<>();
        for (ApplicationStatus status : ApplicationStatus.values()) {
            applicationsByStatus.put(status.name(), applicationRepository.countByStatus(status));
        }

        return AnalyticsResponse.builder()
                .totalUsers(userRepository.count())
                .usersByRole(usersByRole)
                .totalCompanies(companyRepository.count())
                .companiesByStatus(companiesByStatus)
                .totalJobs(jobRepository.count())
                .jobsByStatus(jobsByStatus)
                .totalApplications(applicationRepository.count())
                .applicationsByStatus(applicationsByStatus)
                .build();
    }
}
