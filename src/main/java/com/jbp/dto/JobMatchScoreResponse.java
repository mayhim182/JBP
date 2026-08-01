package com.jbp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One job's match score, identified by id and <strong>without the job itself</strong>.
 *
 * <p>Deliberately not {@link JobMatchResponse}. Every caller of the batch scoring endpoint already
 * holds the jobs — they came from the search, saved-jobs or related-jobs list a moment earlier — so
 * returning each job again would send its whole payload twice. That is the opposite of what Story
 * 13.0 exists to fix, and at a page of ten jobs it is most of the response body.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobMatchScoreResponse {

    private Long jobId;
    private int matchScore;
    private String matchReason;
}
