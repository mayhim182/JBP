package com.jbp.mapper;

import com.jbp.dto.AdminJobResponse;
import com.jbp.dto.DuplicateJob;
import com.jbp.dto.JobResponse;
import com.jbp.dto.JobRiskAssessment;
import com.jbp.dto.ScreeningQuestionDto;
import com.jbp.model.Company;
import com.jbp.model.Job;
import com.jbp.model.ScreeningQuestion;
import com.jbp.model.VerificationStatus;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;

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
                .screeningQuestions(job.getScreeningQuestions().stream().map(this::toDto).toList())
                .status(job.getStatus())
                .companyId(company.getId())
                .companyName(company.getName())
                .companyVerified(company.getStatus() == VerificationStatus.VERIFIED)
                .build();
    }

    /**
     * The admin view of a job: the same posting payload, plus the two judgements only an admin is
     * shown. Built on {@link #toResponse} rather than restating it, so the two cannot drift.
     *
     * <p>Duplicates are passed in rather than looked up here. A mapper that reached for a detector
     * would make every caller pay for a comparison whether or not it shows one, and would put a second
     * reason to touch the database inside a class whose whole job is translation.
     */
    public AdminJobResponse toAdminResponse(Job job, List<DuplicateJob> duplicates) {
        return new AdminJobResponse(
                job.getId(), toResponse(job), assessmentOf(job), job.getSubmittedAt(), duplicates);
    }

    /**
     * Null when the job has never been assessed. The queue draws that differently from a clean
     * assessment, so the absence has to survive the mapping rather than become an empty object.
     */
    private JobRiskAssessment assessmentOf(Job job) {
        return job.getModerationRisk() == null
                ? null
                : new JobRiskAssessment(job.getModerationRisk(), List.copyOf(job.getModerationFlags()));
    }

    private ScreeningQuestionDto toDto(ScreeningQuestion question) {
        return new ScreeningQuestionDto(question.getQuestion(), question.getAnswerType());
    }
}
