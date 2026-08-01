package com.jbp.dto;

import com.jbp.model.ScreeningQuestionType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One screening question as it crosses the wire, in both directions: the recruiter sends these when
 * saving a job, and everyone who reads a job gets them back.
 *
 * <p>{@code answerType} may be null on the way in and on the way out — see {@link
 * com.jbp.model.ScreeningQuestion}. A null on the way out means the recruiter has not chosen one yet;
 * a null on the way in means they still have not.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScreeningQuestionDto {

    private String question;
    private ScreeningQuestionType answerType;
}
