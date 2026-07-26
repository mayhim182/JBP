package com.jbp.serviceimpl;

import com.jbp.dto.JobRequest;
import com.jbp.dto.JobResponse;
import com.jbp.exception.ConflictException;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.mapper.JobMapper;
import com.jbp.model.Company;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.repository.JobRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CompanyService;
import com.jbp.service.JobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobServiceImpl implements JobService {

    private final JobRepository jobRepository;
    private final CompanyService companyService;
    private final CurrentUserProvider currentUserProvider;
    private final JobMapper jobMapper;

    @Override
    @Transactional
    public JobResponse createJob(JobRequest request) {
        Long recruiterId = currentUserProvider.getCurrentUserId();
        Company company = companyService.getCompanyEntityForRecruiter(recruiterId);

        Job job = Job.builder()
                .company(company)
                .status(JobStatus.DRAFT)
                .build();
        applyRequestToJob(job, request);

        Job saved = jobRepository.save(job);
        log.info("Job created with id={} under company {} by recruiter {}",
                saved.getId(), company.getId(), recruiterId);
        return jobMapper.toResponse(saved);
    }

    @Override
    @Transactional
    public JobResponse updateJob(Long id, JobRequest request) {
        Job job = findJobOrThrow(id);
        ensureCurrentUserOwnsJob(job);
        ensureStatus(job, JobStatus.DRAFT, "Only draft jobs can be edited");

        applyRequestToJob(job, request);

        Job updated = jobRepository.save(job);
        log.info("Job updated with id={}", updated.getId());
        return jobMapper.toResponse(updated);
    }

    @Override
    @Transactional
    public void deleteJob(Long id) {
        Job job = findJobOrThrow(id);
        ensureCurrentUserOwnsJob(job);
        ensureStatus(job, JobStatus.DRAFT, "Only draft jobs can be deleted");

        jobRepository.delete(job);
        log.info("Job deleted with id={}", id);
    }

    @Override
    @Transactional
    public JobResponse publishJob(Long id) {
        Job job = findJobOrThrow(id);
        ensureCurrentUserOwnsJob(job);
        ensureStatus(job, JobStatus.DRAFT, "Only draft jobs can be published");

        Long recruiterId = currentUserProvider.getCurrentUserId();
        if (!companyService.isRecruiterVerified(recruiterId)) {
            // Not an authorization failure — the recruiter may publish jobs, but this one
            // conflicts with the company's current (unverified) state. ConflictException
            // keeps the reason in the response so the UI can tell them what to do next;
            // AccessDeniedException is deliberately masked to a generic "Access denied".
            throw new ConflictException("Your company must be verified before publishing jobs");
        }

        // Submit for admin moderation; becomes PUBLISHED only after admin approval (Epic 9).
        job.setStatus(JobStatus.PENDING_MODERATION);
        Job submitted = jobRepository.save(job);
        log.info("Job id={} submitted for moderation by recruiter {}", submitted.getId(), recruiterId);
        return jobMapper.toResponse(submitted);
    }

    @Override
    @Transactional
    public JobResponse closeJob(Long id) {
        Job job = findJobOrThrow(id);
        ensureCurrentUserOwnsJob(job);
        ensureStatus(job, JobStatus.PUBLISHED, "Only published jobs can be closed");

        job.setStatus(JobStatus.CLOSED);
        Job closed = jobRepository.save(job);
        log.info("Job closed with id={}", closed.getId());
        return jobMapper.toResponse(closed);
    }

    @Override
    @Transactional
    public JobResponse cloneJob(Long id) {
        Job source = findJobOrThrow(id);
        ensureCurrentUserOwnsJob(source);

        Job copy = Job.builder()
                .company(source.getCompany())
                .status(JobStatus.DRAFT)
                .title(source.getTitle())
                .description(source.getDescription())
                .skills(new HashSet<>(source.getSkills()))
                .location(source.getLocation())
                .remote(source.isRemote())
                .type(source.getType())
                .seniority(source.getSeniority())
                .salaryMin(source.getSalaryMin())
                .salaryMax(source.getSalaryMax())
                .screeningQuestions(new ArrayList<>(source.getScreeningQuestions()))
                .build();

        Job saved = jobRepository.save(copy);
        log.info("Job cloned from id={} into new draft id={}", id, saved.getId());
        return jobMapper.toResponse(saved);
    }

    @Override
    public JobResponse getPublishedJobById(Long id) {
        Job job = findJobOrThrow(id);
        if (job.getStatus() != JobStatus.PUBLISHED) {
            // Hide non-published jobs from the public entirely.
            throw new ResourceNotFoundException("Job not found with id: " + id);
        }
        return jobMapper.toResponse(job);
    }

    @Override
    public List<JobResponse> getMyJobs() {
        Long recruiterId = currentUserProvider.getCurrentUserId();
        return jobRepository.findByCompany_Owner_Id(recruiterId).stream()
                .map(jobMapper::toResponse)
                .toList();
    }

    private void applyRequestToJob(Job job, JobRequest request) {
        job.setTitle(request.getTitle());
        job.setDescription(request.getDescription());
        job.setSkills(request.getSkills() != null ? request.getSkills() : new HashSet<>());
        job.setLocation(request.getLocation());
        job.setRemote(request.isRemote());
        job.setType(request.getType());
        job.setSeniority(request.getSeniority());
        job.setSalaryMin(request.getSalaryMin());
        job.setSalaryMax(request.getSalaryMax());
        job.setScreeningQuestions(
                request.getScreeningQuestions() != null ? request.getScreeningQuestions() : new ArrayList<>());
    }

    private void ensureCurrentUserOwnsJob(Job job) {
        Long currentUserId = currentUserProvider.getCurrentUserId();
        Long ownerId = job.getCompany().getOwner().getId();
        if (!ownerId.equals(currentUserId)) {
            log.warn("User {} attempted to manage job {} owned by recruiter {}",
                    currentUserId, job.getId(), ownerId);
            throw new AccessDeniedException("You can only manage your own jobs");
        }
    }

    private void ensureStatus(Job job, JobStatus expected, String message) {
        if (job.getStatus() != expected) {
            throw new ConflictException(message);
        }
    }

    private Job findJobOrThrow(Long id) {
        return jobRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + id));
    }
}
