package com.jbp.serviceimpl;

import com.jbp.dto.JobMatchResponse;
import com.jbp.dto.JobMatchScoreResponse;
import com.jbp.dto.MatchExplanationResponse;
import com.jbp.dto.MatchFactorResponse;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.mapper.JobMapper;
import com.jbp.model.CandidateProfile;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.repository.JobRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CandidateProfileService;
import com.jbp.service.MatchExplainer;
import com.jbp.service.MatchExplainer.MatchExplanation;
import com.jbp.service.MatchExplainer.MatchExplanationInput;
import com.jbp.service.MatchExplainer.SkillDemand;
import com.jbp.service.MatchScorer;
import com.jbp.service.MatchService;
import com.jbp.util.EmbeddingTexts;
import com.jbp.util.ScoreVersion;
import com.jbp.util.TextHash;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Scoring for the candidate-facing lists.
 *
 * <p>Every method here resolves the candidate's profile <strong>once per request</strong> and then
 * scores against it. That is the whole point of Story 13.0: the previous shape had the frontend ask
 * for one score per job, so a ten-job page re-resolved the same profile ten times over ten HTTP
 * round trips — and once Story 13.3 stores embeddings it would have re-loaded the same vector ten
 * times as well. {@link MatchScorer} is untouched; what changed is how many times its inputs are
 * fetched.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MatchServiceImpl implements MatchService {

    private final JobRepository jobRepository;
    private final CandidateProfileService candidateProfileService;
    private final CurrentUserProvider currentUserProvider;
    private final MatchScorer matchScorer;
    private final MatchExplainer matchExplainer;
    private final JobMapper jobMapper;

    @Override
    public List<JobMatchResponse> getJobMatchesForCurrentCandidate() {
        CandidateProfile profile = currentCandidateProfile();
        return newestPublishedJobs().stream()
                .map(job -> toJobMatch(job, profile))
                .sorted(Comparator.comparingInt(JobMatchResponse::getMatchScore).reversed())
                .toList();
    }

    @Override
    public JobMatchResponse getJobMatchForCurrentCandidate(Long jobId) {
        return toJobMatch(publishedJob(jobId), currentCandidateProfile());
    }

    @Override
    public MatchExplanationResponse getJobMatchExplanationForCurrentCandidate(Long jobId) {
        Job job = publishedJob(jobId);
        CandidateProfile profile = currentCandidateProfile();
        MatchScorer.MatchResult result = matchScorer.score(profile, job);
        String scoreVersion = scoreVersionOf(profile, job, result);

        MatchExplanation explanation = matchExplainer.explain(new MatchExplanationInput(
                profile.getId(),
                job.getId(),
                scoreVersion,
                job.getTitle(),
                result.score(),
                result.reason(),
                result.factors(),
                job.getSkills(),
                profile.getSkills(),
                missingSkillFor(profile, job),
                // Deferred on purpose: this counts a skill across the candidate's strongest matches,
                // which costs a scoring pass. The cache decorator short-circuits before the delegate
                // runs, so on a hit — the common case — this supplier is never invoked at all.
                () -> demandFor(missingSkillFor(profile, job), profile)));

        return MatchExplanationResponse.builder()
                .summary(explanation.summary())
                .actionText(explanation.actionText())
                .actionSkill(explanation.actionSkill())
                .generated(explanation.generated())
                .scoreVersion(scoreVersion)
                .build();
    }

    @Override
    public List<JobMatchScoreResponse> getJobMatchScoresForCurrentCandidate(Collection<Long> jobIds) {
        requireWithinScoreRequestLimit(jobIds);
        if (jobIds.isEmpty()) {
            // Nothing asked for, so nothing to load — and no profile lookup either.
            return List.of();
        }
        CandidateProfile profile = currentCandidateProfile();
        List<Job> scorableJobs = jobRepository.findByIdInAndStatus(jobIds, JobStatus.PUBLISHED);
        log.debug("Scoring {} of {} requested jobs for the current candidate in one request",
                scorableJobs.size(), jobIds.size());
        return scorableJobs.stream()
                .map(job -> toJobMatchScore(job, profile))
                .toList();
    }

    /**
     * Enforced here rather than in the controller, so the bound holds for every caller of the service
     * and cannot be lost by a second endpoint being added later.
     */
    private void requireWithinScoreRequestLimit(Collection<Long> jobIds) {
        if (jobIds == null) {
            throw new IllegalArgumentException("At least one job id is required");
        }
        if (jobIds.size() > MAX_JOBS_PER_SCORE_REQUEST) {
            throw new IllegalArgumentException("At most " + MAX_JOBS_PER_SCORE_REQUEST
                    + " job ids may be scored in one request, but " + jobIds.size() + " were given");
        }
    }

    private JobMatchResponse toJobMatch(Job job, CandidateProfile profile) {
        MatchScorer.MatchResult result = matchScorer.score(profile, job);
        return JobMatchResponse.builder()
                .job(jobMapper.toResponse(job))
                .matchScore(result.score())
                .matchReason(result.reason())
                .factors(toFactorResponses(result.factors()))
                .scorerMode(String.valueOf(result.mode()))
                .surfacedByMeaning(result.surfacedByMeaning())
                .scoreVersion(scoreVersionOf(profile, job, result))
                .build();
    }

    private JobMatchScoreResponse toJobMatchScore(Job job, CandidateProfile profile) {
        MatchScorer.MatchResult result = matchScorer.score(profile, job);
        return JobMatchScoreResponse.builder()
                .jobId(job.getId())
                .matchScore(result.score())
                .matchReason(result.reason())
                .build();
    }

    private List<MatchFactorResponse> toFactorResponses(List<MatchScorer.MatchFactor> factors) {
        return factors.stream()
                .map(factor -> MatchFactorResponse.builder()
                        .kind(factor.kind().name())
                        .label(factor.kind().getLabel())
                        .detail(factor.detail())
                        .weight(factor.weight())
                        .score(factor.score())
                        .contribution(factor.contribution())
                        .build())
                .toList();
    }

    /**
     * The source hashes are computed rather than read back from {@code embedding_vectors}: it is the
     * same {@code sha256(EmbeddingTexts…)} value the store would hold, costs no query, and is available
     * even when AI is switched off and no embedding row exists at all.
     */
    private String scoreVersionOf(CandidateProfile profile, Job job, MatchScorer.MatchResult result) {
        return ScoreVersion.of(
                result.mode(),
                result.score(),
                result.factors(),
                TextHash.sha256Hex(EmbeddingTexts.forCandidateProfile(profile)),
                TextHash.sha256Hex(EmbeddingTexts.forJob(job)));
    }

    /**
     * The skill this job names that the profile does not, chosen deterministically. Alphabetical among
     * the missing ones — arbitrary, but stable, and stability is what stops the suggestion changing on
     * every refresh for no reason a candidate could perceive.
     */
    private String missingSkillFor(CandidateProfile profile, Job job) {
        Set<String> candidateSkills = lowercased(profile.getSkills());
        return job.getSkills() == null ? null : job.getSkills().stream()
                .filter(Objects::nonNull)
                .filter(skill -> !skill.isBlank())
                .filter(skill -> !candidateSkills.contains(skill.toLowerCase(Locale.ROOT)))
                .sorted()
                .findFirst()
                .orElse(null);
    }

    /**
     * How many of the candidate's strongest matches also ask for {@code skill}. This is the claim that
     * makes design 20's advice credible — "it's named here" is about one job and easy to dismiss.
     */
    private SkillDemand demandFor(String skill, CandidateProfile profile) {
        if (skill == null) {
            return new SkillDemand(0, 0);
        }
        List<Job> strongest = newestPublishedJobs().stream()
                .sorted(Comparator.comparingInt(
                        (Job job) -> matchScorer.score(profile, job).score()).reversed())
                .limit(STRONGEST_MATCHES_CONSIDERED_FOR_ADVICE)
                .toList();
        long naming = strongest.stream()
                .filter(job -> lowercased(job.getSkills()).contains(skill.toLowerCase(Locale.ROOT)))
                .count();
        return new SkillDemand((int) naming, strongest.size());
    }

    private List<Job> newestPublishedJobs() {
        return jobRepository.findByStatus(JobStatus.PUBLISHED, newestFirst()).getContent();
    }

    /**
     * The newest published jobs, capped.
     *
     * <p>Newest by id, because {@code jobs} has no created timestamp — the search query orders the
     * same way for the same reason. Worth replacing with a real column if job ordering ever matters
     * beyond "roughly recent".
     */
    private Pageable newestFirst() {
        return PageRequest.of(0, MAX_JOBS_SCANNED_FOR_MATCHES, Sort.by(Sort.Direction.DESC, "id"));
    }

    private Job publishedJob(Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + jobId));
        if (job.getStatus() != JobStatus.PUBLISHED) {
            // Only published jobs are matchable / visible.
            throw new ResourceNotFoundException("Job not found with id: " + jobId);
        }
        return job;
    }

    private Set<String> lowercased(Set<String> values) {
        return values == null ? Set.of() : values.stream()
                .filter(Objects::nonNull)
                .map(value -> value.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    // The current candidate's profile, or an empty profile if they haven't built one yet.
    private CandidateProfile currentCandidateProfile() {
        Long candidateId = currentUserProvider.getCurrentUserId();
        return candidateProfileService.findProfileForCandidate(candidateId)
                .orElseGet(() -> CandidateProfile.builder().build());
    }
}
