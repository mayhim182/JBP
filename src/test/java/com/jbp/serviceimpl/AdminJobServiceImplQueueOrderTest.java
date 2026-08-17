package com.jbp.serviceimpl;

import com.jbp.dto.AdminJobResponse;
import com.jbp.event.EmbeddingRefreshPublisher;
import com.jbp.mapper.JobMapper;
import com.jbp.model.Company;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.model.ModerationCategory;
import com.jbp.model.ModerationFlag;
import com.jbp.model.ModerationRisk;
import com.jbp.model.User;
import com.jbp.model.VerificationStatus;
import com.jbp.repository.JobRepository;
import com.jbp.service.JobDuplicateDetector;
import com.jbp.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.5 — the order the admin review queue arrives in.
 *
 * <p>The ranking is a design decision (28 C) rather than an implementation detail, which is why it is
 * pinned here: HIGH → MEDIUM → not assessed → NO FLAGS.
 */
class AdminJobServiceImplQueueOrderTest {

    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);

    private final JobDuplicateDetector jobDuplicateDetector = Mockito.mock(JobDuplicateDetector.class);

    private final AdminJobServiceImpl service = new AdminJobServiceImpl(
            jobRepository,
            new JobMapper(),
            Mockito.mock(NotificationService.class),
            Mockito.mock(EmbeddingRefreshPublisher.class),
            jobDuplicateDetector);

    @BeforeEach
    void givenNoDuplicatesAnywhere() {
        // Story 14.6's own behaviour has its own test; here it must not disturb the ranking.
        Mockito.when(jobDuplicateDetector.checkAll(Mockito.any())).thenReturn(Map.of());
    }

    @Test
    void ranksTheQueueByRiskWithCheckedAndCleanLast() {
        givenPending(
                job(4L, ModerationRisk.NONE),
                job(3L, null),
                job(2L, ModerationRisk.MEDIUM),
                job(1L, ModerationRisk.HIGH));

        assertThat(service.getPendingJobs())
                .extracting(AdminJobResponse::id)
                .containsExactly(1L, 2L, 3L, 4L);
    }

    /**
     * Sorting an unknown above a positive finding would put ignorance ahead of evidence — the admin is
     * asking "what should I look at next", and a MEDIUM flag is something we actually know.
     */
    @Test
    void sortsAnUnassessedJobBelowARealMediumFinding() {
        givenPending(job(1L, null), job(2L, ModerationRisk.MEDIUM));

        assertThat(service.getPendingJobs())
                .extracting(AdminJobResponse::id)
                .containsExactly(2L, 1L);
    }

    /** But above one that was checked and found clean, which is the row nobody needs to reach for. */
    @Test
    void sortsAnUnassessedJobAboveOneCheckedAndFoundClean() {
        givenPending(job(1L, ModerationRisk.NONE), job(2L, null));

        assertThat(service.getPendingJobs())
                .extracting(AdminJobResponse::id)
                .containsExactly(2L, 1L);
    }

    /**
     * The switched-off case, and the reason there is only one comparator: with moderation assist off
     * nothing is ever assessed, every row ranks equal, and a stable sort hands back what the query
     * produced. "Unsorted" needs no second code path.
     */
    @Test
    void leavesTheQueueUntouchedWhenNothingHasBeenAssessed() {
        givenPending(job(5L, null), job(9L, null), job(2L, null));

        assertThat(service.getPendingJobs())
                .extracting(AdminJobResponse::id)
                .containsExactly(5L, 9L, 2L);
    }

    /**
     * The absence has to survive the mapping. An empty assessment object would tell the queue this job
     * was checked and found clean, which is the opposite of what is true.
     */
    @Test
    void carriesNoAssessmentAtAllForAJobNobodyHasAssessed() {
        givenPending(job(1L, null));

        assertThat(service.getPendingJobs())
                .singleElement()
                .satisfies(entry -> assertThat(entry.moderation()).isNull());
    }

    @Test
    void carriesTheFlagsBesideTheRiskForAnAssessedJob() {
        givenPending(job(1L, ModerationRisk.HIGH));

        assertThat(service.getPendingJobs())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.moderation().risk()).isEqualTo(ModerationRisk.HIGH);
                    assertThat(entry.moderation().flags())
                            .singleElement()
                            .satisfies(flag ->
                                    assertThat(flag.getCategory()).isEqualTo(ModerationCategory.SCAM));
                });
    }

    /** The posting itself still travels, so the row can link to it without a second request. */
    @Test
    void carriesThePostingAlongsideTheAssessment() {
        givenPending(job(1L, ModerationRisk.HIGH));

        assertThat(service.getPendingJobs())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.job().getTitle()).isEqualTo("Backend Engineer");
                    assertThat(entry.job().getCompanyName()).isEqualTo("Acme");
                });
    }

    private void givenPending(Job... jobs) {
        Mockito.when(jobRepository.findByStatus(JobStatus.PENDING_MODERATION)).thenReturn(List.of(jobs));
    }

    private Job job(Long id, ModerationRisk risk) {
        return Job.builder()
                .id(id)
                .title("Backend Engineer")
                .status(JobStatus.PENDING_MODERATION)
                .skills(new HashSet<>())
                .screeningQuestions(new ArrayList<>())
                .moderationRisk(risk)
                .moderationFlags(flagsFor(risk))
                .company(company())
                .build();
    }

    /** Flags exist exactly when something was found, matching what the assistant can produce. */
    private List<ModerationFlag> flagsFor(ModerationRisk risk) {
        if (risk == null || risk == ModerationRisk.NONE) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(ModerationFlag.builder()
                .category(ModerationCategory.SCAM)
                .rationale("Steers applicants off-platform to hand over documents.")
                .build()));
    }

    private Company company() {
        return Company.builder()
                .id(11L)
                .name("Acme")
                .status(VerificationStatus.VERIFIED)
                .owner(User.builder().id(7L).build())
                .build();
    }
}
