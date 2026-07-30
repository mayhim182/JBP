package com.jbp.dto;

import com.jbp.model.JobQualityField;
import com.jbp.model.QualityFindingSource;
import com.jbp.model.QualitySeverity;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One thing worth improving about a posting.
 *
 * <p>Advisory throughout: nothing here prevents publishing, and nothing is stored. A check is
 * recomputed on demand, so a finding is only ever a statement about the job as it is right now.
 *
 * <p>{@code message} says what was found, {@code suggestion} says what to do about it. Keeping them
 * apart is what lets the editor render the two columns the designs draw, and it stops a finding
 * turning into one long sentence a recruiter has to parse to find the action.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobQualityFinding {

    private QualitySeverity severity;

    private JobQualityField field;

    /** What was found, in the recruiter's terms. For example "No salary range set." */
    private String message;

    /** What to do about it. For example "Add a min and max — ranges attract more applicants." */
    private String suggestion;

    private QualityFindingSource source;
}
