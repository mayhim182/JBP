package com.jbp.event;

import com.jbp.dto.JobRiskAssessment;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.repository.JobRepository;
import com.jbp.service.JobModerationAssistant;
import com.jbp.service.JobModerationAssistant.JobModerationBrief;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.ArrayList;

/**
 * Assesses a job for risk after the submission that triggered it has committed, on another thread.
 *
 * <p><strong>This is what keeps a slow provider out of the recruiter's way.</strong> Submitting for
 * moderation is a button press that should return immediately; the chat endpoint has a 20-second
 * timeout, and running the assessment inline would hand that wait to the one person who gains nothing
 * from it. The submission commits; this follows.
 *
 * <p>{@code AFTER_COMMIT} and {@code REQUIRES_NEW} for the reasons set out on
 * {@link EmbeddingRefreshListener}: reading the job before its transaction committed could assess a
 * state that then rolled back, and this runs on a pool thread that inherits no transaction of its own.
 *
 * <p><strong>Nothing here changes a job's status.</strong> The assistant is advisory, and this listener
 * writes two fields and stops — which is what makes "never auto-rejects" a property of the code rather
 * than a promise in a prompt.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobModerationListener {

    private final JobModerationAssistant jobModerationAssistant;
    private final JobRepository jobRepository;

    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onJobModerationRequested(JobModerationRequestedEvent event) {
        try {
            jobRepository.findById(event.jobId()).ifPresentOrElse(
                    this::assessIfStillPending,
                    () -> log.debug("Nothing to assess for job {} — record gone", event.jobId()));
        } catch (RuntimeException unexpected) {
            // Nothing above this catches anything: this runs on a pool thread with no caller.
            log.error("Moderation assessment failed for job {}", event.jobId(), unexpected);
        }
    }

    /**
     * An admin can approve or reject between the commit and this thread running. Spending a provider
     * call on a job that has already left the queue would buy an assessment nothing will ever show.
     */
    private void assessIfStillPending(Job job) {
        if (job.getStatus() != JobStatus.PENDING_MODERATION) {
            log.debug("Job {} left moderation before it was assessed", job.getId());
            return;
        }
        jobModerationAssistant.assess(briefFor(job)).ifPresentOrElse(
                assessment -> store(job, assessment),
                () -> log.debug("Job {} stays unassessed — the assistant had no usable answer", job.getId()));
    }

    private void store(Job job, JobRiskAssessment assessment) {
        job.setModerationRisk(assessment.risk());
        // Copied into a mutable list: the collection is Hibernate-managed, and handing it the
        // immutable one a record returns would fail the first time anything edited it.
        job.setModerationFlags(new ArrayList<>(assessment.flags()));
        jobRepository.save(job);
        log.info("Job {} assessed as {} with {} flag(s)",
                job.getId(), assessment.risk(), assessment.flags().size());
    }

    private JobModerationBrief briefFor(Job job) {
        return new JobModerationBrief(
                job.getTitle(),
                job.getDescription(),
                job.getSkills(),
                job.getSeniority(),
                job.getType(),
                job.getSalaryMin(),
                job.getSalaryMax());
    }
}
