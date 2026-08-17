package com.jbp.serviceimpl;

import com.jbp.config.DuplicateDetectionSettings;
import com.jbp.dto.DuplicateCheck;
import com.jbp.dto.DuplicateJob;
import com.jbp.model.EmbeddingOwnerType;
import com.jbp.model.Job;
import com.jbp.repository.JobRepository;
import com.jbp.service.EmbeddingStore;
import com.jbp.service.JobDuplicateDetector;
import com.jbp.util.CosineSimilarity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JobDuplicateDetectorImpl implements JobDuplicateDetector {

    private final JobRepository jobRepository;
    private final EmbeddingStore embeddingStore;
    private final DuplicateDetectionSettings settings;

    @Override
    public DuplicateCheck check(Job job) {
        if (job == null || job.getId() == null) {
            return DuplicateCheck.notAssessable();
        }
        return checkAll(List.of(job)).getOrDefault(job.getId(), DuplicateCheck.notAssessable());
    }

    /**
     * Two queries regardless of how many postings are checked: one for every candidate in the companies
     * involved, one for every vector. Asking per row would put the N+1 that Story 13.0 removed from the
     * HTTP layer straight back into the moderation queue.
     */
    @Override
    public Map<Long, DuplicateCheck> checkAll(Collection<Job> jobs) {
        List<Job> subjects = jobs == null ? List.of() : jobs.stream()
                .filter(Objects::nonNull)
                .filter(job -> job.getId() != null && job.getCompany() != null)
                .toList();
        if (subjects.isEmpty()) {
            return Map.of();
        }

        Set<Long> companyIds = subjects.stream()
                .map(job -> job.getCompany().getId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<Long, List<Job>> candidatesByCompany = jobRepository.findByCompanyIdIn(companyIds).stream()
                .collect(Collectors.groupingBy(candidate -> candidate.getCompany().getId()));

        // One lookup covering subjects and candidates alike — they overlap, and a set collapses that.
        Set<Long> everyId = candidatesByCompany.values().stream()
                .flatMap(List::stream)
                .map(Job::getId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        subjects.forEach(subject -> everyId.add(subject.getId()));
        Map<Long, float[]> vectors = embeddingStore.findVectors(EmbeddingOwnerType.JOB, everyId);

        Map<Long, DuplicateCheck> answers = new HashMap<>();
        for (Job subject : subjects) {
            answers.put(subject.getId(), answerFor(subject, candidatesByCompany, vectors));
        }
        return answers;
    }

    /**
     * Absent rather than empty is the whole no-op condition: with no vector for this posting there is
     * nothing to compare, and reporting "no duplicates" would be a claim we cannot make.
     */
    private DuplicateCheck answerFor(Job subject,
                                     Map<Long, List<Job>> candidatesByCompany,
                                     Map<Long, float[]> vectors) {

        float[] subjectVector = vectors.get(subject.getId());
        if (subjectVector == null) {
            log.debug("Job {} has no current vector — not assessable for duplicates", subject.getId());
            return DuplicateCheck.notAssessable();
        }

        List<Job> candidates = candidatesByCompany
                .getOrDefault(subject.getCompany().getId(), List.of());

        return DuplicateCheck.of(candidates.stream()
                .filter(candidate -> !candidate.getId().equals(subject.getId()))
                .map(candidate -> scoreOrNull(subject.getId(), subjectVector, candidate,
                        vectors.get(candidate.getId())))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparingDouble(Scored::similarity).reversed())
                .limit(settings.maxReported())
                .map(Scored::toDuplicateJob)
                .toList());
    }

    /**
     * Null for anything below the threshold or missing a vector, so the caller keeps one filter.
     *
     * <p>No length guard before {@link CosineSimilarity#between}: {@code EmbeddingStore} returns a vector
     * only when it is current for the running model and dimension, so two vectors from different
     * configurations can never both reach here.
     */
    private Scored scoreOrNull(Long subjectId, float[] subject, Job candidate, float[] candidateVector) {
        if (candidateVector == null) {
            return null;
        }
        double similarity = CosineSimilarity.between(subject, candidateVector);
        if (similarity < settings.similarityThreshold()) {
            return null;
        }
        // The one place the number is recorded. Threshold tuning should read these, not the UI — see
        // DuplicateJob for why the figure is deliberately absent from what is returned.
        log.debug("Job {} resembles job {} at a cosine of {}", subjectId, candidate.getId(), similarity);
        return new Scored(candidate, similarity);
    }

    /** Carries the similarity only as far as the sort; it is deliberately absent from what is returned. */
    private record Scored(Job job, double similarity) {

        DuplicateJob toDuplicateJob() {
            return new DuplicateJob(job.getId(), job.getTitle(), job.getStatus());
        }
    }
}
