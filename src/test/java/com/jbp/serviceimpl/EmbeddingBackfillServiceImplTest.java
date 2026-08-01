package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.CandidateProfile;
import com.jbp.model.EmbeddingOwnerType;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.repository.CandidateProfileRepository;
import com.jbp.repository.JobRepository;
import com.jbp.service.EmbeddingBackfillService.BackfillSummary;
import com.jbp.service.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;

class EmbeddingBackfillServiceImplTest {

    private static final int RECORDS_PER_BATCH = 20;

    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
    private final CandidateProfileRepository candidateProfileRepository =
            Mockito.mock(CandidateProfileRepository.class);
    private final EmbeddingStore embeddingStore = Mockito.mock(EmbeddingStore.class);

    private final EmbeddingBackfillServiceImpl backfill = new EmbeddingBackfillServiceImpl(
            jobRepository, candidateProfileRepository, embeddingStore);

    @Test
    void scansEveryPageOfPublishedJobs() {
        givenPublishedJobPages(RECORDS_PER_BATCH, 5);
        Mockito.when(embeddingStore.refreshAll(Mockito.any(), Mockito.anyMap())).thenReturn(0);

        BackfillSummary summary = backfill.backfillPublishedJobs();

        assertThat(summary.scanned()).isEqualTo(25);
        assertThat(summary.stoppedEarly()).isFalse();
    }

    @Test
    void sendsOneBatchPerPageRatherThanOneCallPerRecord() {
        givenPublishedJobPages(RECORDS_PER_BATCH, 5);
        Mockito.when(embeddingStore.refreshAll(Mockito.any(), Mockito.anyMap())).thenReturn(0);

        backfill.backfillPublishedJobs();

        Mockito.verify(embeddingStore, Mockito.times(2))
                .refreshAll(Mockito.eq(EmbeddingOwnerType.JOB), Mockito.anyMap());
    }

    @Test
    void reportsOnlyTheRecordsThatActuallyNeededAProviderCall() {
        givenPublishedJobPages(3);
        // Two of the three were already current, so the store embedded one.
        Mockito.when(embeddingStore.refreshAll(Mockito.any(), Mockito.anyMap())).thenReturn(1);

        BackfillSummary summary = backfill.backfillPublishedJobs();

        assertThat(summary.scanned())
                .as("scanned and embedded differing is how a re-run proves it is idempotent")
                .isEqualTo(3);
        assertThat(summary.embedded()).isEqualTo(1);
    }

    @Test
    void makesNoProviderCallsAtAllOnASecondRunOverUnchangedData() {
        givenPublishedJobPages(3);
        Mockito.when(embeddingStore.refreshAll(Mockito.any(), Mockito.anyMap())).thenReturn(0);

        assertThat(backfill.backfillPublishedJobs().embedded()).isZero();
    }

    @Test
    void stopsAndSaysSoWhenTheRateLimitIsHit() {
        givenPublishedJobPages(RECORDS_PER_BATCH, RECORDS_PER_BATCH, 5);
        Mockito.when(embeddingStore.refreshAll(Mockito.any(), Mockito.anyMap()))
                .thenReturn(RECORDS_PER_BATCH)
                .thenThrow(new LlmUnavailableException("limit of 12 per minute reached", false));

        BackfillSummary summary = backfill.backfillPublishedJobs();

        assertThat(summary.stoppedEarly()).isTrue();
        assertThat(summary.embedded())
                .as("the work already done is kept, so the next run resumes without being told where")
                .isEqualTo(RECORDS_PER_BATCH);
        Mockito.verify(embeddingStore, Mockito.times(2))
                .refreshAll(Mockito.any(), Mockito.anyMap());
    }

    @Test
    void doesNothingWhenThereAreNoRecords() {
        Mockito.when(jobRepository.findByStatus(Mockito.eq(JobStatus.PUBLISHED), Mockito.any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        BackfillSummary summary = backfill.backfillPublishedJobs();

        assertThat(summary.scanned()).isZero();
        Mockito.verifyNoInteractions(embeddingStore);
    }

    @Test
    void backfillsCandidateProfilesThroughTheSameLoop() {
        Mockito.when(candidateProfileRepository.findAll(Mockito.any(Pageable.class)))
                .thenReturn(new PageImpl<>(
                        List.of(CandidateProfile.builder().id(1L).headline("Engineer").build()),
                        PageRequest.of(0, RECORDS_PER_BATCH), 1));
        Mockito.when(embeddingStore.refreshAll(Mockito.any(), Mockito.anyMap())).thenReturn(1);

        BackfillSummary summary = backfill.backfillCandidateProfiles();

        assertThat(summary.scanned()).isEqualTo(1);
        Mockito.verify(embeddingStore)
                .refreshAll(Mockito.eq(EmbeddingOwnerType.CANDIDATE_PROFILE), Mockito.anyMap());
    }

    @Test
    void keysEachBatchByOwnerIdSoTheStoreKnowsWhatItIsEmbedding() {
        givenPublishedJobPages(2);
        Mockito.when(embeddingStore.refreshAll(Mockito.any(), Mockito.anyMap())).thenReturn(2);

        backfill.backfillPublishedJobs();

        Mockito.verify(embeddingStore).refreshAll(
                Mockito.eq(EmbeddingOwnerType.JOB),
                Mockito.argThat((Map<Long, String> texts) -> texts.keySet().containsAll(List.of(1L, 2L))));
    }

    /** Stubs consecutive pages of published jobs with the given sizes, the last one having no next. */
    private void givenPublishedJobPages(int... pageSizes) {
        Page<Job>[] pages = new Page[pageSizes.length];
        long nextId = 1;
        long totalElements = 0;
        for (int size : pageSizes) {
            totalElements += size;
        }
        for (int pageIndex = 0; pageIndex < pageSizes.length; pageIndex++) {
            final long firstId = nextId;
            List<Job> jobs = LongStream.range(firstId, firstId + pageSizes[pageIndex])
                    .mapToObj(id -> Job.builder().id(id).title("Job " + id).build())
                    .toList();
            nextId += pageSizes[pageIndex];
            pages[pageIndex] = new PageImpl<>(
                    jobs, PageRequest.of(pageIndex, RECORDS_PER_BATCH), totalElements);
        }

        Mockito.when(jobRepository.findByStatus(Mockito.eq(JobStatus.PUBLISHED), Mockito.any(Pageable.class)))
                .thenAnswer(call -> {
                    Pageable requested = call.getArgument(1);
                    int pageNumber = requested.getPageNumber();
                    return pageNumber < pages.length
                            ? pages[pageNumber]
                            : new PageImpl<>(List.of(), requested, pages.length);
                });
    }
}
