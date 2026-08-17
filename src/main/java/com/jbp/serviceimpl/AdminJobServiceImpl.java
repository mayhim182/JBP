package com.jbp.serviceimpl;

import com.jbp.dto.AdminJobResponse;
import com.jbp.dto.DuplicateCheck;
import com.jbp.dto.DuplicateJob;
import com.jbp.dto.JobResponse;
import com.jbp.exception.ConflictException;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.mapper.JobMapper;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.model.ModerationRisk;
import com.jbp.model.NotificationType;
import com.jbp.repository.JobRepository;
import com.jbp.event.EmbeddingRefreshPublisher;
import com.jbp.service.AdminJobService;
import com.jbp.service.JobDuplicateDetector;
import com.jbp.service.NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminJobServiceImpl implements AdminJobService {

    private static final Comparator<AdminJobResponse> HIGHEST_RISK_FIRST =
            Comparator.comparingInt(AdminJobServiceImpl::queueRankOf);

    private final JobRepository jobRepository;
    private final JobMapper jobMapper;
    private final NotificationService notificationService;
    private final EmbeddingRefreshPublisher embeddingRefreshPublisher;
    private final JobDuplicateDetector jobDuplicateDetector;

    /** An unassessed job sorts between MEDIUM and NONE — see {@link ModerationRisk}. */
    private static int queueRankOf(AdminJobResponse entry) {
        return entry.moderation() == null
                ? ModerationRisk.UNASSESSED_QUEUE_RANK
                : entry.moderation().risk().queueRank();
    }

    /**
     * Highest risk first, so the dangerous postings are reviewed before the routine ones.
     *
     * <p>The one comparator covers the switched-off case too: with moderation assist off nothing is
     * ever assessed, every row ranks equal, and a stable sort hands back the order the query produced.
     * That is the "unsorted" the criterion asks for, with no second code path to keep in step with this
     * one.
     */
    @Override
    public List<AdminJobResponse> getPendingJobs() {
        List<Job> pending = jobRepository.findByStatus(JobStatus.PENDING_MODERATION);
        // One batch for the whole queue rather than a check per row — see JobDuplicateDetector.
        Map<Long, DuplicateCheck> duplicateChecks = jobDuplicateDetector.checkAll(pending);
        return pending.stream()
                .map(job -> jobMapper.toAdminResponse(job, duplicatesFor(duplicateChecks, job)))
                .sorted(HIGHEST_RISK_FIRST)
                .toList();
    }

    @Override
    public AdminJobResponse getJob(Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + jobId));
        if (job.getStatus() == JobStatus.DRAFT) {
            // Not found rather than forbidden: a draft's existence is itself the recruiter's business.
            log.debug("Admin asked for job {}, which is a draft — reporting it as absent", jobId);
            throw new ResourceNotFoundException("Job not found with id: " + jobId);
        }
        return jobMapper.toAdminResponse(job, jobDuplicateDetector.check(job).duplicates());
    }

    /** A row the detector could not assess reads the same as one with nothing to report. */
    private static List<DuplicateJob> duplicatesFor(Map<Long, DuplicateCheck> checks, Job job) {
        return checks.getOrDefault(job.getId(), DuplicateCheck.notAssessable()).duplicates();
    }

    @Override
    @Transactional
    public JobResponse approveJob(Long jobId) {
        Job job = findPendingJobOrThrow(jobId);
        job.setStatus(JobStatus.PUBLISHED);
        jobRepository.save(job);
        log.info("Job {} approved and published by admin", jobId);
        // Kept after Story 14.6 moved the first embedding to submission time, because it costs nothing
        // to keep: EmbeddingStore re-embeds only when the source text has actually changed, and a job
        // cannot be edited between submission and approval (updateJob is DRAFT-only). So this is a hash
        // comparison in the normal case, and the safety net for any job that reaches PUBLISHED without
        // having passed through publishJob.
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
