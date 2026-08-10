package com.jbp.event;

import com.jbp.dto.JobRiskAssessment;
import com.jbp.model.Company;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.model.ModerationCategory;
import com.jbp.model.ModerationFlag;
import com.jbp.model.ModerationRisk;
import com.jbp.model.User;
import com.jbp.repository.JobRepository;
import com.jbp.service.JobModerationAssistant;
import com.jbp.service.JobModerationAssistant.JobModerationBrief;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.5 — what actually reaches the job row after a submission commits.
 *
 * <p>The listener is where "advisory" stops being a promise: it writes two fields and stops. Several of
 * these exist to keep it that way.
 */
class JobModerationListenerTest {

    private static final Long JOB_ID = 3L;

    private final JobModerationAssistant assistant = Mockito.mock(JobModerationAssistant.class);
    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);

    private final JobModerationListener listener = new JobModerationListener(assistant, jobRepository);

    @Test
    void storesTheRiskAndFlagsOnTheJobItAssessed() {
        Job job = pendingJob();
        givenTheRepositoryHolds(job);
        givenTheAssistantReturns(assessment(ModerationRisk.HIGH));

        listener.onJobModerationRequested(new JobModerationRequestedEvent(JOB_ID));

        assertThat(savedJob().getModerationRisk()).isEqualTo(ModerationRisk.HIGH);
        assertThat(savedJob().getModerationFlags())
                .singleElement()
                .satisfies(flag -> assertThat(flag.getCategory()).isEqualTo(ModerationCategory.SCAM));
    }

    /**
     * The acceptance criterion, enforced rather than promised. Nothing on this path may move a job out
     * of the queue — only an admin does that.
     */
    @Test
    void neverChangesTheJobsStatus() {
        Job job = pendingJob();
        givenTheRepositoryHolds(job);
        givenTheAssistantReturns(assessment(ModerationRisk.HIGH));

        listener.onJobModerationRequested(new JobModerationRequestedEvent(JOB_ID));

        assertThat(savedJob().getStatus()).isEqualTo(JobStatus.PENDING_MODERATION);
    }

    @Test
    void leavesTheJobUntouchedWhenTheAssistantHasNoAnswer() {
        givenTheRepositoryHolds(pendingJob());
        Mockito.when(assistant.assess(Mockito.any())).thenReturn(Optional.empty());

        listener.onJobModerationRequested(new JobModerationRequestedEvent(JOB_ID));

        Mockito.verify(jobRepository, Mockito.never()).save(Mockito.any());
    }

    /**
     * An admin can approve or reject between the commit and this thread running. Assessing then would
     * spend a provider call on a job that has already left the queue.
     */
    @Test
    void doesNotAssessAJobThatHasAlreadyLeftModeration() {
        Job approved = pendingJob();
        approved.setStatus(JobStatus.PUBLISHED);
        givenTheRepositoryHolds(approved);

        listener.onJobModerationRequested(new JobModerationRequestedEvent(JOB_ID));

        Mockito.verify(assistant, Mockito.never()).assess(Mockito.any());
    }

    @Test
    void doesNothingWhenTheJobHasBeenDeleted() {
        Mockito.when(jobRepository.findById(JOB_ID)).thenReturn(Optional.empty());

        listener.onJobModerationRequested(new JobModerationRequestedEvent(JOB_ID));

        Mockito.verify(assistant, Mockito.never()).assess(Mockito.any());
    }

    /**
     * This runs on a pool thread with no caller, so an escaping exception would be lost rather than
     * handled — and the submission it followed has already committed.
     */
    @Test
    void swallowsAnUnexpectedFailureRatherThanLosingItOnAPoolThread() {
        givenTheRepositoryHolds(pendingJob());
        Mockito.when(assistant.assess(Mockito.any())).thenThrow(new IllegalStateException("boom"));

        listener.onJobModerationRequested(new JobModerationRequestedEvent(JOB_ID));

        Mockito.verify(jobRepository, Mockito.never()).save(Mockito.any());
    }

    /** The employer is absent from the brief by construction — see {@code JobModerationBrief}. */
    @Test
    void sendsThePostingsOwnContentAndNothingAboutTheCompany() {
        givenTheRepositoryHolds(pendingJob());
        givenTheAssistantReturns(assessment(ModerationRisk.MEDIUM));

        listener.onJobModerationRequested(new JobModerationRequestedEvent(JOB_ID));

        ArgumentCaptor<JobModerationBrief> brief = ArgumentCaptor.forClass(JobModerationBrief.class);
        Mockito.verify(assistant).assess(brief.capture());
        assertThat(brief.getValue().title()).isEqualTo("Warehouse Associate");
        assertThat(brief.getValue().salaryMin()).isEqualTo(200_000);
    }

    private void givenTheRepositoryHolds(Job job) {
        Mockito.when(jobRepository.findById(JOB_ID)).thenReturn(Optional.of(job));
    }

    private void givenTheAssistantReturns(JobRiskAssessment assessment) {
        Mockito.when(assistant.assess(Mockito.any())).thenReturn(Optional.of(assessment));
    }

    private Job savedJob() {
        ArgumentCaptor<Job> saved = ArgumentCaptor.forClass(Job.class);
        Mockito.verify(jobRepository).save(saved.capture());
        return saved.getValue();
    }

    private JobRiskAssessment assessment(ModerationRisk risk) {
        return new JobRiskAssessment(risk, List.of(ModerationFlag.builder()
                .category(ModerationCategory.SCAM)
                .rationale("Steers applicants off-platform to hand over documents.")
                .build()));
    }

    private Job pendingJob() {
        return Job.builder()
                .id(JOB_ID)
                .title("Warehouse Associate")
                .description("Immediate start, no experience needed.")
                .status(JobStatus.PENDING_MODERATION)
                .skills(new HashSet<>())
                .screeningQuestions(new ArrayList<>())
                .moderationFlags(new ArrayList<>())
                .salaryMin(200_000)
                .salaryMax(400_000)
                .company(Company.builder()
                        .id(11L)
                        .name("Acme")
                        .owner(User.builder().id(7L).build())
                        .build())
                .build();
    }
}
