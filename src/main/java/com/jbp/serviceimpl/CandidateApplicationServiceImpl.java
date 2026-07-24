package com.jbp.serviceimpl;

import com.jbp.dto.ApplicationResponse;
import com.jbp.dto.ApplyRequest;
import com.jbp.event.ApplicationStatusChangePublisher;
import com.jbp.exception.ConflictException;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.mapper.ApplicationMapper;
import com.jbp.model.Application;
import com.jbp.model.ApplicationStatus;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.model.ScreeningAnswer;
import com.jbp.model.User;
import com.jbp.repository.ApplicationRepository;
import com.jbp.repository.JobRepository;
import com.jbp.repository.UserRepository;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CandidateApplicationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CandidateApplicationServiceImpl implements CandidateApplicationService {

    private final ApplicationRepository applicationRepository;
    private final JobRepository jobRepository;
    private final UserRepository userRepository;
    private final CurrentUserProvider currentUserProvider;
    private final ApplicationMapper applicationMapper;
    private final ApplicationStatusChangePublisher statusChangePublisher;

    @Override
    @Transactional
    public ApplicationResponse apply(Long jobId, ApplyRequest request) {
        Long candidateId = currentUserProvider.getCurrentUserId();

        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + jobId));
        if (job.getStatus() != JobStatus.PUBLISHED) {
            throw new ConflictException("You can only apply to published jobs");
        }
        if (applicationRepository.existsByCandidateIdAndJobId(candidateId, jobId)) {
            throw new ConflictException("You have already applied to this job");
        }

        User candidate = userRepository.findById(candidateId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with id: " + candidateId));

        Application application = Application.builder()
                .job(job)
                .candidate(candidate)
                .status(ApplicationStatus.APPLIED)
                .build();
        addScreeningAnswers(application, request);

        Application saved = applicationRepository.save(application);
        log.info("Candidate {} applied to job {} (application {})", candidateId, jobId, saved.getId());
        statusChangePublisher.publish(saved, null, ApplicationStatus.APPLIED);
        return applicationMapper.toCandidateResponse(saved);
    }

    @Override
    public List<ApplicationResponse> getMyApplications() {
        Long candidateId = currentUserProvider.getCurrentUserId();
        return applicationRepository.findByCandidateId(candidateId).stream()
                .map(applicationMapper::toCandidateResponse)
                .toList();
    }

    private void addScreeningAnswers(Application application, ApplyRequest request) {
        if (request == null || request.getAnswers() == null) {
            return;
        }
        request.getAnswers().forEach(dto ->
                application.getScreeningAnswers().add(new ScreeningAnswer(dto.getQuestion(), dto.getAnswer())));
    }
}
