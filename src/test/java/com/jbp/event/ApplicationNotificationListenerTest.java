package com.jbp.event;

import com.jbp.model.ApplicationStatus;
import com.jbp.model.Job;
import com.jbp.model.NotificationType;
import com.jbp.repository.JobRepository;
import com.jbp.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What the candidate is actually told. Design 26 flagged the enum leak — the message read
 * <em>"is now REJECTED"</em>, shouting a Java constant at the person it was about.
 */
class ApplicationNotificationListenerTest {

    private static final long CANDIDATE_ID = 5L;

    private NotificationService notificationService;
    private ApplicationNotificationListener listener;

    @BeforeEach
    void setUp() {
        notificationService = mock(NotificationService.class);
        JobRepository jobRepository = mock(JobRepository.class);
        when(jobRepository.findById(anyLong()))
                .thenReturn(Optional.of(Job.builder().id(1L).title("Streaming Engineer").build()));
        listener = new ApplicationNotificationListener(notificationService, jobRepository);
    }

    @Test
    void neverPutsTheRawEnumInFrontOfACandidate() {
        listener.onApplicationStatusChanged(
                event(ApplicationStatus.SHORTLISTED, null));

        assertThat(sentMessage())
                .isEqualTo("Your application for 'Streaming Engineer' has been shortlisted.")
                .doesNotContain("SHORTLISTED");
    }

    @Test
    void describesEveryStageInWordsACandidateWouldUse() {
        for (ApplicationStatus status : ApplicationStatus.values()) {
            NotificationService perStatus = mock(NotificationService.class);
            JobRepository jobs = mock(JobRepository.class);
            when(jobs.findById(anyLong()))
                    .thenReturn(Optional.of(Job.builder().id(1L).title("A role").build()));

            new ApplicationNotificationListener(perStatus, jobs)
                    .onApplicationStatusChanged(event(status, null));

            ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
            verify(perStatus).createNotification(
                    eq(CANDIDATE_ID), any(NotificationType.class), message.capture());
            assertThat(message.getValue()).doesNotContain(status.name());
        }
    }

    /** Story 14.4's point: composing first means this is the only message the candidate gets. */
    @Test
    void carriesTheReasonWhenTheRejectionWasComposedBeforeItWasSent() {
        listener.onApplicationStatusChanged(event(
                ApplicationStatus.REJECTED,
                "This role runs on Kafka day to day, and that isn't in your profile yet."));

        assertThat(sentMessage())
                .startsWith("Your application for 'Streaming Engineer' was not successful:")
                .contains("Kafka");
    }

    @Test
    void announcesTheApplicationItselfWhenThereIsNoPreviousStage() {
        listener.onApplicationStatusChanged(new ApplicationStatusChangedEvent(
                1L, CANDIDATE_ID, 1L, null, ApplicationStatus.APPLIED, null));

        assertThat(sentMessage()).isEqualTo("Your application for 'Streaming Engineer' has been submitted.");
    }

    private ApplicationStatusChangedEvent event(ApplicationStatus newStatus, String rejectionReason) {
        return new ApplicationStatusChangedEvent(
                1L, CANDIDATE_ID, 1L, ApplicationStatus.APPLIED, newStatus, rejectionReason);
    }

    private String sentMessage() {
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(notificationService).createNotification(
                eq(CANDIDATE_ID), any(NotificationType.class), message.capture());
        return message.getValue();
    }
}
