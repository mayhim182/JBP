package com.jbp.serviceimpl;

import com.jbp.dto.JobResponse;
import com.jbp.exception.ConflictException;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.mapper.JobMapper;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.model.NotificationType;
import com.jbp.repository.JobRepository;
import com.jbp.event.EmbeddingRefreshPublisher;
import com.jbp.service.AdminJobService;
import com.jbp.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminJobServiceImpl implements AdminJobService {

    private final JobRepository jobRepository;
    private final JobMapper jobMapper;
    private final NotificationService notificationService;
    private final EmbeddingRefreshPublisher embeddingRefreshPublisher;

    @Override
    public List<JobResponse> getPendingJobs() {
        return jobRepository.findByStatus(JobStatus.PENDING_MODERATION).stream()
                .map(jobMapper::toResponse)
                .toList();
    }

    @Override
    @Transactional
    public JobResponse approveJob(Long jobId) {
        Job job = findPendingJobOrThrow(jobId);
        job.setStatus(JobStatus.PUBLISHED);
        jobRepository.save(job);
        log.info("Job {} approved and published by admin", jobId);
        // Here rather than in JobServiceImpl.publishJob, which only submits for moderation: a job that
        // is never approved is never searchable, so embedding it there would spend free-tier quota on
        // vectors nothing can ever match against.
        embeddingRefreshPublisher.jobChanged(jobId);
        notificationService.createNotification(job.getCompany().getOwner().getId(), NotificationType.JOB_MODERATION,
                "Your job '" + job.getTitle() + "' has been approved and published.");
        return jobMapper.toResponse(job);
    }

    @Override
    @Transactional
    public JobResponse rejectJob(Long jobId, String reason) {
        Job job = findPendingJobOrThrow(jobId);
        job.setStatus(JobStatus.REJECTED);
        jobRepository.save(job);
        log.info("Job {} rejected by admin", jobId);
        String suffix = (reason == null || reason.isBlank()) ? "." : ": " + reason;
        notificationService.createNotification(job.getCompany().getOwner().getId(), NotificationType.JOB_MODERATION,
                "Your job '" + job.getTitle() + "' was rejected" + suffix);
        return jobMapper.toResponse(job);
    }

    private Job findPendingJobOrThrow(Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + jobId));
        if (job.getStatus() != JobStatus.PENDING_MODERATION) {
            throw new ConflictException("Job is not pending moderation");
        }
        return job;
    }
}
