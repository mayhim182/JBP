package com.jbp.serviceimpl;

import com.jbp.config.DuplicateDetectionSettings;
import com.jbp.dto.DuplicateCheck;
import com.jbp.dto.DuplicateJob;
import com.jbp.model.Company;
import com.jbp.model.EmbeddingOwnerType;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.repository.JobRepository;
import com.jbp.service.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.6 — near-duplicate detection over stored vectors. No model, no network.
 *
 * <p>Vectors are 2-D unit vectors chosen so the dot product is the cosine to two decimal places;
 * {@code CosineSimilarity} is a bare dot product precisely because the client guarantees unit length.
 */
class JobDuplicateDetectorImplTest {

    private static final Long COMPANY_ID = 11L;
    private static final Long OTHER_COMPANY_ID = 22L;
    private static final Long SUBJECT_ID = 1L;

    private static final float[] SUBJECT = {1f, 0f};
    /** ≈0.98 against SUBJECT. */
    private static final float[] ALMOST_IDENTICAL = {0.98f, 0.199f};
    /** ≈0.95 — above the 0.92 threshold. */
    private static final float[] NEAR_DUPLICATE = {0.95f, 0.3122f};
    /** ≈0.50 — a different role at the same company. */
    private static final float[] UNRELATED = {0.5f, 0.866f};

    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
    private final EmbeddingStore embeddingStore = Mockito.mock(EmbeddingStore.class);

    private final JobDuplicateDetectorImpl detector = new JobDuplicateDetectorImpl(
            jobRepository, embeddingStore, new DuplicateDetectionSettings(0.92, 3));

    @Test
    void reportsACandidateAboveTheThreshold() {
        Job subject = job(SUBJECT_ID, COMPANY_ID, "Backend Engineer", JobStatus.PENDING_MODERATION);
        Job sibling = job(2L, COMPANY_ID, "Backend Engineer (Platform)", JobStatus.PUBLISHED);
        given(List.of(subject, sibling), vectors(SUBJECT_ID, SUBJECT, 2L, NEAR_DUPLICATE));

        DuplicateCheck answer = detector.check(subject);

        assertThat(answer.assessable()).isTrue();
        assertThat(answer.duplicates())
                .singleElement()
                .satisfies(duplicate -> assertThat(duplicate.id()).isEqualTo(2L));
    }

    @Test
    void ignoresACandidateBelowTheThreshold() {
        Job subject = job(SUBJECT_ID, COMPANY_ID, "Backend Engineer", JobStatus.PENDING_MODERATION);
        Job unrelated = job(2L, COMPANY_ID, "Office Manager", JobStatus.PUBLISHED);
        given(List.of(subject, unrelated), vectors(SUBJECT_ID, SUBJECT, 2L, UNRELATED));

        DuplicateCheck answer = detector.check(subject);

        assertThat(answer.assessable()).isTrue();
        assertThat(answer.duplicates()).isEmpty();
    }

    /**
     * The distinction the whole feature rests on. A job embedded after this check would report the same
     * empty list, and the client would have no way to know a retry would help.
     */
    @Test
    void reportsNotAssessableWhenTheSubjectHasNoVector() {
        Job subject = job(SUBJECT_ID, COMPANY_ID, "Backend Engineer", JobStatus.PENDING_MODERATION);
        Job sibling = job(2L, COMPANY_ID, "Backend Engineer II", JobStatus.PUBLISHED);
        given(List.of(subject, sibling), vectors(2L, NEAR_DUPLICATE));

        DuplicateCheck answer = detector.check(subject);

        assertThat(answer.assessable()).isFalse();
        assertThat(answer.duplicates()).isEmpty();
    }

    @Test
    void skipsACandidateWithNoCurrentVector() {
        Job subject = job(SUBJECT_ID, COMPANY_ID, "Backend Engineer", JobStatus.PENDING_MODERATION);
        Job withVector = job(2L, COMPANY_ID, "Backend Engineer II", JobStatus.PUBLISHED);
        Job withoutVector = job(3L, COMPANY_ID, "Backend Engineer III", JobStatus.PUBLISHED);
        given(List.of(subject, withVector, withoutVector), vectors(SUBJECT_ID, SUBJECT, 2L, NEAR_DUPLICATE));

        assertThat(detector.check(subject).duplicates())
                .extracting(DuplicateJob::id)
                .containsExactly(2L);
    }

    @Test
    void ordersTheMostSimilarFirst() {
        Job subject = job(SUBJECT_ID, COMPANY_ID, "Backend Engineer", JobStatus.PENDING_MODERATION);
        Job further = job(2L, COMPANY_ID, "Backend Engineer (Contract)", JobStatus.PUBLISHED);
        Job closer = job(3L, COMPANY_ID, "Backend Engineer — Platform", JobStatus.PUBLISHED);
        given(List.of(subject, further, closer),
                vectors(SUBJECT_ID, SUBJECT, 2L, NEAR_DUPLICATE, 3L, ALMOST_IDENTICAL));

        assertThat(detector.check(subject).duplicates())
                .extracting(DuplicateJob::id)
                .containsExactly(3L, 2L);
    }

    @Test
    void reportsAtMostTheConfiguredNumber() {
        JobDuplicateDetectorImpl capped = new JobDuplicateDetectorImpl(
                jobRepository, embeddingStore, new DuplicateDetectionSettings(0.92, 1));
        Job subject = job(SUBJECT_ID, COMPANY_ID, "Backend Engineer", JobStatus.PENDING_MODERATION);
        given(List.of(subject,
                        job(2L, COMPANY_ID, "A", JobStatus.PUBLISHED),
                        job(3L, COMPANY_ID, "B", JobStatus.PUBLISHED)),
                vectors(SUBJECT_ID, SUBJECT, 2L, NEAR_DUPLICATE, 3L, ALMOST_IDENTICAL));

        assertThat(capped.check(subject).duplicates()).hasSize(1);
    }

    /** Two employers advertising the same role is a market, not a duplicate. */
    @Test
    void neverMatchesAcrossCompanies() {
        Job subject = job(SUBJECT_ID, COMPANY_ID, "Backend Engineer", JobStatus.PENDING_MODERATION);
        Job otherCompany = job(2L, OTHER_COMPANY_ID, "Backend Engineer", JobStatus.PUBLISHED);
        given(List.of(subject, otherCompany), vectors(SUBJECT_ID, SUBJECT, 2L, ALMOST_IDENTICAL));

        assertThat(detector.check(subject).duplicates()).isEmpty();
    }

    /** Re-posting a closed role is legitimate, so the status has to reach whoever reads the flag. */
    @Test
    void carriesTheStatusOfThePostingItResembles() {
        Job subject = job(SUBJECT_ID, COMPANY_ID, "Backend Engineer", JobStatus.PENDING_MODERATION);
        Job closed = job(2L, COMPANY_ID, "Backend Engineer", JobStatus.CLOSED);
        given(List.of(subject, closed), vectors(SUBJECT_ID, SUBJECT, 2L, NEAR_DUPLICATE));

        assertThat(detector.check(subject).duplicates())
                .singleElement()
                .satisfies(duplicate -> assertThat(duplicate.status()).isEqualTo(JobStatus.CLOSED));
    }

    /** A whole queue costs the same two queries as one row. */
    @Test
    void checksAWholeQueueWithoutAskingPerRow() {
        Job first = job(SUBJECT_ID, COMPANY_ID, "Backend Engineer", JobStatus.PENDING_MODERATION);
        Job second = job(4L, OTHER_COMPANY_ID, "Data Engineer", JobStatus.PENDING_MODERATION);
        Job firstSibling = job(2L, COMPANY_ID, "Backend Engineer II", JobStatus.PUBLISHED);
        given(List.of(first, second, firstSibling),
                vectors(SUBJECT_ID, SUBJECT, 4L, SUBJECT, 2L, NEAR_DUPLICATE));

        Map<Long, DuplicateCheck> answers = detector.checkAll(List.of(first, second));

        assertThat(answers.get(SUBJECT_ID).duplicates()).hasSize(1);
        assertThat(answers.get(4L).duplicates())
                .as("its only same-company sibling is itself")
                .isEmpty();
        Mockito.verify(jobRepository, Mockito.times(1)).findByCompanyIdIn(Mockito.any());
        Mockito.verify(embeddingStore, Mockito.times(1)).findVectors(Mockito.any(), Mockito.any());
    }

    @Test
    void reportsNotAssessableForAJobThatHasNotBeenSavedYet() {
        Job unsaved = Job.builder().title("Backend Engineer").company(company(COMPANY_ID)).build();

        assertThat(detector.check(unsaved).assessable()).isFalse();
        Mockito.verifyNoInteractions(embeddingStore, jobRepository);
    }

    /** Every posting the involved companies hold, subject included — the detector excludes itself. */
    private void given(List<Job> everyJobInThoseCompanies, Map<Long, float[]> vectors) {
        Mockito.when(jobRepository.findByCompanyIdIn(Mockito.any())).thenReturn(everyJobInThoseCompanies);
        Mockito.when(embeddingStore.findVectors(Mockito.eq(EmbeddingOwnerType.JOB), Mockito.any()))
                .thenReturn(vectors);
    }

    /** Alternating id/vector pairs, so a test's setup reads as one line. */
    private Map<Long, float[]> vectors(Object... idsAndVectors) {
        Map<Long, float[]> byId = new HashMap<>();
        for (int pair = 0; pair < idsAndVectors.length; pair += 2) {
            byId.put((Long) idsAndVectors[pair], (float[]) idsAndVectors[pair + 1]);
        }
        return byId;
    }

    private Job job(Long id, Long companyId, String title, JobStatus status) {
        return Job.builder().id(id).title(title).status(status).company(company(companyId)).build();
    }

    private Company company(Long id) {
        return Company.builder().id(id).name("Acme").build();
    }
}
