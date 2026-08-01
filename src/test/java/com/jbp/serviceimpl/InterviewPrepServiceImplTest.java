package com.jbp.serviceimpl;

import com.jbp.dto.InterviewPrepResponse;
import com.jbp.dto.InterviewPrepResponse.InterviewQuestionGroupResponse;
import com.jbp.exception.ResourceNotFoundException;
import com.jbp.model.InterviewQuestionKind;
import com.jbp.model.Job;
import com.jbp.model.JobStatus;
import com.jbp.model.JobType;
import com.jbp.model.SeniorityLevel;
import com.jbp.repository.JobRepository;
import com.jbp.service.InterviewQuestionGenerator;
import com.jbp.service.InterviewQuestionGenerator.InterviewQuestions;
import com.jbp.service.InterviewQuestionGenerator.JobBrief;
import com.jbp.service.InterviewQuestionGenerator.QuestionGroup;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Story 14.1 — published jobs only, and a brief that carries nothing about the viewer. */
class InterviewPrepServiceImplTest {

    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
    private final InterviewQuestionGenerator generator = Mockito.mock(InterviewQuestionGenerator.class);

    private final InterviewPrepServiceImpl service =
            new InterviewPrepServiceImpl(jobRepository, generator);

    @Test
    void returnsGroupsWithTheirStableKeyAndTheirDisplayLabel() {
        givenAPublishedJob();
        Mockito.when(generator.generate(Mockito.any())).thenReturn(new InterviewQuestions(List.of(
                new QuestionGroup(InterviewQuestionKind.TECHNICAL, List.of("One.", "Two.", "Three.")),
                new QuestionGroup(InterviewQuestionKind.ROLE_SPECIFIC, List.of("Four.", "Five.")))));

        InterviewPrepResponse response = service.getInterviewPrepForJob(1L);

        assertThat(response.getGroups()).extracting(InterviewQuestionGroupResponse::getKind)
                .containsExactly("TECHNICAL", "ROLE_SPECIFIC");
        assertThat(response.getGroups()).extracting(InterviewQuestionGroupResponse::getLabel)
                .as("the client switches on the key and renders the label")
                .containsExactly("Technical", "Role-specific");
        assertThat(response.getGroups().get(0).getQuestions()).hasSize(3);
    }

    @Test
    void tellsTheGeneratorAboutTheJobAndNothingElse() {
        givenAPublishedJob();
        Mockito.when(generator.generate(Mockito.any())).thenReturn(someQuestions());

        service.getInterviewPrepForJob(1L);

        ArgumentCaptor<JobBrief> brief = ArgumentCaptor.forClass(JobBrief.class);
        Mockito.verify(generator).generate(brief.capture());
        assertThat(brief.getValue().title()).isEqualTo("Senior Backend Engineer");
        assertThat(brief.getValue().skills()).containsExactlyInAnyOrder("java", "kafka");
        assertThat(brief.getValue().seniority()).isEqualTo(SeniorityLevel.SENIOR);
    }

    @Test
    void hidesAnUnpublishedJobRatherThanForbiddingIt() {
        Mockito.when(jobRepository.findById(9L)).thenReturn(Optional.of(
                Job.builder().id(9L).status(JobStatus.DRAFT).build()));

        assertThatThrownBy(() -> service.getInterviewPrepForJob(9L))
                .as("a draft's existence is not a candidate's business")
                .isInstanceOf(ResourceNotFoundException.class);
        Mockito.verifyNoInteractions(generator);
    }

    @Test
    void reportsAnUnknownJobAsMissingWithoutAskingTheModel() {
        Mockito.when(jobRepository.findById(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getInterviewPrepForJob(404L))
                .isInstanceOf(ResourceNotFoundException.class);
        Mockito.verifyNoInteractions(generator);
    }

    private void givenAPublishedJob() {
        Mockito.when(jobRepository.findById(1L)).thenReturn(Optional.of(Job.builder()
                .id(1L)
                .status(JobStatus.PUBLISHED)
                .title("Senior Backend Engineer")
                .description("Own the payment ledger.")
                .skills(Set.of("java", "kafka"))
                .seniority(SeniorityLevel.SENIOR)
                .type(JobType.FULL_TIME)
                .build()));
    }

    private InterviewQuestions someQuestions() {
        return new InterviewQuestions(List.of(new QuestionGroup(
                InterviewQuestionKind.TECHNICAL, List.of("One.", "Two.", "Three.", "Four.", "Five."))));
    }
}
