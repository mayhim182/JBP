package com.jbp.serviceimpl;

import com.jbp.dto.JobMatchResponse;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.mapper.JobMapper;
import com.jbp.model.CandidateProfile;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.repository.JobRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CandidateProfileService;
import com.jbp.service.MatchScorer;
import com.jbp.service.MatchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MatchServiceImpl implements MatchService {

    private final JobRepository jobRepository;
    private final CandidateProfileService candidateProfileService;
    private final CurrentUserProvider currentUserProvider;
    private final MatchScorer matchScorer;
    private final JobMapper jobMapper;

    @Override
    public List<JobMatchResponse> getJobMatchesForCurrentCandidate() {
        CandidateProfile profile = currentCandidateProfile();
        return jobRepository.findByStatus(JobStatus.PUBLISHED).stream()
                .map(job -> toJobMatch(job, profile))
                .sorted(Comparator.comparingInt(JobMatchResponse::getMatchScore).reversed())
                .toList();
    }

    @Override
    public JobMatchResponse getJobMatchForCurrentCandidate(Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + jobId));
        if (job.getStatus() != JobStatus.PUBLISHED) {
            // Only published jobs are matchable / visible.
            throw new ResourceNotFoundException("Job not found with id: " + jobId);
        }
        return toJobMatch(job, currentCandidateProfile());
    }

    private JobMatchResponse toJobMatch(Job job, CandidateProfile profile) {
        MatchScorer.MatchResult result = matchScorer.score(profile, job);
        return JobMatchResponse.builder()
                .job(jobMapper.toResponse(job))
                .matchScore(result.score())
                .matchReason(result.reason())
                .build();
    }

    // The current candidate's profile, or an empty profile if they haven't built one yet.
    private CandidateProfile currentCandidateProfile() {
        Long candidateId = currentUserProvider.getCurrentUserId();
        return candidateProfileService.findProfileForCandidate(candidateId)
                .orElseGet(() -> CandidateProfile.builder().build());
    }
}
