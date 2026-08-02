package com.jbp.dto;

import com.jbp.model.ScreeningQuestionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Body of {@code POST /api/applications/draft-answer}.
 *
 * <p><strong>No job id.</strong> The draft is grounded in the candidate's profile and the question,
 * and nothing else — see {@link com.jbp.service.ScreeningAnswerAssistant}. Sending a job id would
 * invite someone to use it, and the point of this feature is that the answer is about the candidate
 * rather than about the posting.
 *
 * <p>The question therefore arrives from the client rather than being looked up server-side, which
 * is what makes the per-user allowance the real control here: any signed-in candidate can put any
 * text in this field. The allowance bounds that, and the prompt keeps the output grounded in their
 * own profile whatever the question says.
 *
 * <p>1,000 characters matches the column screening questions are stored in, so a question that could
 * have been asked is never rejected and one that could not is never accepted.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DraftAnswerRequest {

    @NotBlank(message = "A question is required")
    @Size(max = 1000, message = "That question is longer than a screening question can be")
    private String question;

    /**
     * Required, and validated by the service to be one of the two the trigger exists for. It decides
     * how long the draft may be: without it a one-line question comes back as a paragraph that cannot
     * fit its own control.
     */
    @NotNull(message = "An answer type is required")
    private ScreeningQuestionType answerType;
}
