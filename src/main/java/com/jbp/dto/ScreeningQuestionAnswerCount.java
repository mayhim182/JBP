package com.jbp.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * How many candidates have already answered one screening question, so the editor can warn before an
 * edit changes a question people have replied to.
 *
 * <p>Deliberately <strong>not</strong> a field on {@link JobResponse}. That response is served to
 * guests on every published job and every search hit; per-question applicant counts are the
 * recruiter's business, and computing them there would put an applications read behind a page of
 * search results. This travels on its own owner-only endpoint instead, fetched once when the editor
 * opens.
 *
 * <p>Matched on question text, because a screening question has no stable id — see {@code
 * JobServiceImpl.getScreeningAnswerCounts}.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScreeningQuestionAnswerCount {

    private String question;

    /** Answers that are actually present. A submitted-but-blank answer does not count. */
    private long answeredCount;
}
