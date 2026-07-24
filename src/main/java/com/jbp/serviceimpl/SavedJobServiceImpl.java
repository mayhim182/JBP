package com.jbp.serviceimpl;

import com.jbp.dto.JobResponse;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.mapper.JobMapper;
import com.jbp.model.Job;
import com.jbp.model.SavedJob;
import com.jbp.model.User;
import com.jbp.repository.JobRepository;
import com.jbp.repository.SavedJobRepository;
import com.jbp.repository.UserRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.SavedJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SavedJobServiceImpl implements SavedJobService {

    private final SavedJobRepository savedJobRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final JobMapper jobMapper;

    @Override
    @Transactional
    public void saveJob(Long jobId) {
        Long candidateId = currentUserProvider.getCurrentUserId();
        if (savedJobRepository.existsByCandidateIdAndJobId(candidateId, jobId)) {
            return; // already saved — idempotent
        }

        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + jobId));
        User candidate = userRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + candidateId));

        savedJobRepository.save(SavedJob.builder().candidate(candidate).job(job).build());
        log.info("Candidate {} saved job {}", candidateId, jobId);
    }

    @Override
    @Transactional
    public void unsaveJob(Long jobId) {
        Long candidateId = currentUserProvider.getCurrentUserId();
        savedJobRepository.findByCandidateIdAndJobId(candidateId, jobId)
                .ifPresent(savedJobRepository::delete);
        log.info("Candidate {} unsaved job {}", candidateId, jobId);
    }

    @Override
    public List<JobResponse> getSavedJobs() {
        Long candidateId = currentUserProvider.getCurrentUserId();
        return savedJobRepository.findByCandidateId(candidateId).stream()
                .map(savedJob -> jobMapper.toResponse(savedJob.getJob()))
                .toList();
    }
}
