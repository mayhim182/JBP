package com.jbp.model;

/**
 * The answer control a screening question asks the candidate for.
 *
 * <p>This replaces a client-side heuristic that guessed the control from the question's opening words
 * ("Are you…" → yes/no, "Describe…" → long answer). The guess was wrong often enough to matter — a
 * recruiter who typed "Notice period?" got a one-line box for a question wanting a date, and one who
 * typed "Are you able to describe your release process?" got a yes/no toggle for an essay. The
 * recruiter who wrote the question is the only one who knows, so they are now asked.
 *
 * <p>Unlike {@link InterviewQuestionKind} this carries <strong>no label</strong>. The three segment
 * captions are drawn in design 23 and rendered only by the editor, so naming them here as well would
 * put the same copy in two places and let it drift. The name is the contract; the wording is the
 * client's.
 *
 * <p>The type is <strong>nullable wherever it appears</strong>, and there is no default. Questions
 * written before this existed have no type and are never backfilled, and a newly added question starts
 * with nothing selected — a silent default would be a choice the recruiter never made. See {@link
 * ScreeningQuestion}.
 */
public enum ScreeningQuestionType {

    /** A single line — a count of years, a list of tools, a city. */
    SHORT_ANSWER,

    /** A few paragraphs. */
    LONG_ANSWER,

    /** A yes or a no, and nothing else. */
    YES_NO
}
