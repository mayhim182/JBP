package com.jbp.mapper;

import com.jbp.dto.ApplicationResponse;
import com.jbp.dto.ScreeningAnswerDto;
import com.jbp.model.Application;
import com.jbp.model.Job;
import com.jbp.model.ScreeningAnswer;
import com.jbp.model.User;
import org.springframework.stereotype.Component;

/**
 * Maps an {@link Application} to the response shape. Two views:
 * the candidate view omits recruiter-internal fields (notes, rating);
 * the recruiter view includes them.
 */
@Component
public class ApplicationMapper {

    public ApplicationResponse toCandidateResponse(Application application) {
        return baseBuilder(application).build();
    }

    public ApplicationResponse toRecruiterResponse(Application application) {
        return baseBuilder(application)
                .recruiterNotes(application.getRecruiterNotes())
                .rating(application.getRating())
                .build();
    }

    private ApplicationResponse.ApplicationResponseBuilder baseBuilder(Application application) {
        Job job = application.getJob();
        User candidate = application.getCandidate();
        return ApplicationResponse.builder()
                .id(application.getId())
                .jobId(job.getId())
                .jobTitle(job.getTitle())
                .companyName(job.getCompany().getName())
                .candidateId(candidate.getId())
                .candidateName(candidate.getName())
                .candidateEmail(candidate.getEmail())
                .status(application.getStatus())
                .rejectionReason(application.getRejectionReason())
                .screeningAnswers(application.getScreeningAnswers().stream().map(this::toDto).toList());
    }

    private ScreeningAnswerDto toDto(ScreeningAnswer answer) {
        return new ScreeningAnswerDto(answer.getQuestion(), answer.getAnswer());
    }
}
