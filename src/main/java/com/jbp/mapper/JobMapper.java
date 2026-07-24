package com.jbp.mapper;

import com.jbp.dto.JobResponse;
import com.jbp.model.Company;
import com.jbp.model.Job;
import com.jbp.model.VerificationStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * Single place that turns a {@link Job} into a {@link JobResponse}, shared by the
 * job service, the search service, and saved-jobs (DRY).
 */
@Component
public class JobMapper {

    public JobResponse toResponse(Job job) {
        Company company = job.getCompany();
        return JobResponse.builder()
                .id(job.getId())
                .title(job.getTitle())
                .description(job.getDescription())
                .skills(new HashSet<>(job.getSkills()))
                .location(job.getLocation())
                .remote(job.isRemote())
                .type(job.getType())
                .seniority(job.getSeniority())
                .salaryMin(job.getSalaryMin())
                .salaryMax(job.getSalaryMax())
                .screeningQuestions(new ArrayList<>(job.getScreeningQuestions()))
                .status(job.getStatus())
                .companyId(company.getId())
                .companyName(company.getName())
                .companyVerified(company.getStatus() == VerificationStatus.VERIFIED)
                .build();
    }
}
