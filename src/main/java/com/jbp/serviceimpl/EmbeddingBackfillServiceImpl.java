package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.CandidateProfile;
import com.jbp.model.EmbeddingOwnerType;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.repository.CandidateProfileRepository;
import com.jbp.repository.JobRepository;
import com.jbp.service.EmbeddingBackfillService;
import com.jbp.service.EmbeddingStore;
import com.jbp.util.EmbeddingTexts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingBackfillServiceImpl implements EmbeddingBackfillService {

    /**
     * Records per provider call.
     *
     * <p>Deliberately modest. Story 13.1 proved a batch of <em>three</em> against the live endpoint and
     * nothing larger — whether there is a per-request input cap is still unmeasured, and this is exactly
     * the kind of assumption that has already cost this epic two wrong turns. Twenty keeps each call
     * well inside anything plausible while still being one rate-limit slot per twenty rows instead of
     * per row. Raise it once a large batch has actually been sent.
     */
    private static final int RECORDS_PER_BATCH = 20;

    private final JobRepository jobRepository;
    private final CandidateProfileRepository candidateProfileRepository;
    private final EmbeddingStore embeddingStore;

    @Override
    public BackfillSummary backfillPublishedJobs() {
        return backfill(
                EmbeddingOwnerType.JOB,
                page -> jobRepository.findByStatus(JobStatus.PUBLISHED, page),
                Job::getId,
                EmbeddingTexts::forJob);
    }

    @Override
    public BackfillSummary backfillCandidateProfiles() {
        return backfill(
                EmbeddingOwnerType.CANDIDATE_PROFILE,
                candidateProfileRepository::findAll,
                CandidateProfile::getId,
                EmbeddingTexts::forCandidateProfile);
    }

    /**
     * One paging loop for both owner types.
     *
     * <p>The two differ only in which repository to read, how to get an id and how to build text — so
     * those three are parameters and the batching, the rate-limit handling and the counting are written
     * once. A third owner type would add a method here and nothing else.
     *
     * <p>Pages are read at {@link #RECORDS_PER_BATCH}, which makes each database page exactly one
     * provider call. Note this reads pages by their natural order while writing vectors as it goes;
     * that is safe because nothing in this loop changes what page a record falls on.
     */
    private <T> BackfillSummary backfill(
            EmbeddingOwnerType ownerType,
            Function<PageRequest, Page<T>> pageReader,
            Function<T, Long> idOf,
            Function<T, String> textOf) {

        int scanned = 0;
        int embedded = 0;
        int pageNumber = 0;
        boolean morePages = true;

        while (morePages) {
            Page<T> page = pageReader.apply(PageRequest.of(pageNumber, RECORDS_PER_BATCH));
            if (page.isEmpty()) {
                break;
            }
            Map<Long, String> textsByOwnerId = new LinkedHashMap<>();
            for (T record : page.getContent()) {
                textsByOwnerId.put(idOf.apply(record), textOf.apply(record));
            }
            scanned += textsByOwnerId.size();

            try {
                embedded += embeddingStore.refreshAll(ownerType, textsByOwnerId);
            } catch (LlmUnavailableException providerRefused) {
                log.warn("Stopping {} backfill after {} scanned, {} embedded: {}",
                        ownerType, scanned, embedded, providerRefused.getMessage());
                return new BackfillSummary(scanned, embedded, true);
            }

            morePages = page.hasNext();
            pageNumber++;
        }

        log.info("{} backfill complete: {} scanned, {} embedded", ownerType, scanned, embedded);
        return new BackfillSummary(scanned, embedded, false);
    }
}
