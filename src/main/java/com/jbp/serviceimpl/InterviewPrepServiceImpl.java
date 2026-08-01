package com.jbp.serviceimpl;

import com.jbp.dto.InterviewPrepResponse;
import com.jbp.dto.InterviewPrepResponse.InterviewQuestionGroupResponse;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.repository.JobRepository;
import com.jbp.service.InterviewPrepService;
import com.jbp.service.InterviewQuestionGenerator;
import com.jbp.service.InterviewQuestionGenerator.JobBrief;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the posting, hands the generator a brief, and maps the result.
 *
 * <p>Assembling the brief here rather than in the generator keeps that unit free of persistence and
 * of any security context — the same split {@code JobDescriptionGenerator} already uses.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InterviewPrepServiceImpl implements InterviewPrepService {

    private final JobRepository jobRepository;
    private final InterviewQuestionGenerator interviewQuestionGenerator;

    @Override
    public InterviewPrepResponse getInterviewPrepForJob(Long jobId) {
        Job job = publishedJob(jobId);
        InterviewQuestionGenerator.InterviewQuestions questions =
                interviewQuestionGenerator.generate(briefFor(job));

        log.debug("Interview prep for job {}: {} questions across {} groups",
                jobId, questions.total(), questions.groups().size());

        return InterviewPrepResponse.builder()
                .groups(questions.groups().stream()
                        .map(group -> InterviewQuestionGroupResponse.builder()
                                .kind(group.kind().name())
                                .label(group.kind().getLabel())
                                .questions(group.questions())
                                .build())
                        .toList())
                .build();
    }

    /** Nothing about the caller — see {@link InterviewQuestionGenerator}'s note on personalisation. */
    private JobBrief briefFor(Job job) {
        return new JobBrief(
                job.getTitle(),
                job.getDescription(),
                job.getSkills(),
                job.getSeniority(),
                job.getType());
    }

    /**
     * An unpublished job is reported as absent rather than forbidden, matching how the match endpoints
     * treat one — a draft's existence is not a candidate's business.
     */
    private Job publishedJob(Long jobId) {
        Job job = jobRepository.findById(jobId)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + jobId));
        if (job.getStatus() != JobStatus.PUBLISHED) {
            throw new ResourceNotFoundException("Job not found with id: " + jobId);
        }
        return job;
    }
}
