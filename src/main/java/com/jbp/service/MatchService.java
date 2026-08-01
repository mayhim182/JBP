package com.jbp.service;

import com.jbp.dto.JobMatchResponse;
import com.jbp.dto.JobMatchScoreResponse;
import com.jbp.dto.MatchExplanationResponse;

import java.util.Collection;
import java.util.List;

/** Candidate-facing matching surface: match scores for published jobs. */
public interface MatchService {

    /**
     * Most job ids one scoring request may carry.
     *
     * <p>Lives on the interface so the controller, the tests and the frontend's chunk size all agree
     * on one number. Without a cap this method would be the unbounded endpoint Story 13.0 exists to
     * remove, just reachable through a different URL — a caller could ask for every job in the table
     * in a single request and, once Story 13.3 lands, have every vector loaded to answer it.
     *
     * <p>Fifty rather than ten: a page of search results is ten, but saved jobs is an unpaged list,
     * so the client chunks and this bounds the chunk. Fifty scores is a few milliseconds of string
     * work today and fifty 3KB vectors after 13.3 — comfortable, and small enough that no single
     * request can be used to make the server do an unbounded amount of work.
     */
    int MAX_JOBS_PER_SCORE_REQUEST = 50;

    /**
     * How many of the newest published jobs the matches list considers.
     *
     * <p>This endpoint used to score <em>every</em> published job on every call, which is the defect
     * Story 13.0 exists to remove. Bounding it by the newest N rather than paginating is deliberate:
     * the two views that use it — the dashboard's top-matches panel and the matches page — want a
     * candidate's best matches, and page 3 of a score-within-page list is not a thing anyone wants to
     * read. A bounded scan keeps genuine best-first ordering across everything it looks at.
     */
    int MAX_JOBS_SCANNED_FOR_MATCHES = 50;

    /**
     * The current candidate's best matches, ranked best-first.
     *
     * <p>Ranked across the {@link #MAX_JOBS_SCANNED_FOR_MATCHES} newest published jobs rather than
     * the whole table. Ordering is therefore genuine within that window and blind outside it — a
     * strong match older than the window will not appear. Ranking globally needs the score in the
     * database, which is an Epic 15+ conversation, not something this method can fake.
     */
    List<JobMatchResponse> getJobMatchesForCurrentCandidate();

    /**
     * How many of a candidate's strongest matches are inspected when judging whether a missing skill is
     * worth suggesting — design 20's "in 6 of your 10 strongest matches".
     *
     * <p>Ten because the claim has to be checkable by eye: a candidate can recognise their own top ten,
     * where "in 34 of your 50" is a statistic about a window they never see.
     */
    int STRONGEST_MATCHES_CONSIDERED_FOR_ADVICE = 10;

    /** The current candidate's match for a single published job, with its structured breakdown. */
    JobMatchResponse getJobMatchForCurrentCandidate(Long jobId);

    /**
     * Call 2 of the two-call split: the same match, explained in plain language.
     *
     * <p>Separate from {@link #getJobMatchForCurrentCandidate} on purpose. The score, the bars and the
     * ring are computed and must render immediately; the prose needs a model, which is slow, rate
     * limited and sometimes absent. Behind one call, every candidate would wait on the model to see a
     * number that was ready in microseconds.
     *
     * <p>Never throws for AI reasons — an unavailable model degrades to the rule scorer's own wording.
     */
    MatchExplanationResponse getJobMatchExplanationForCurrentCandidate(Long jobId);

    /**
     * Scores several already-known jobs in one request.
     *
     * <p>This is what a list view should call. The candidate's profile is resolved once for the whole
     * batch — and from Story 13.3 their embedding is loaded once too — where asking per job re-loaded
     * both for every row on the page.
     *
     * <p>Ids that are unknown or not published are absent from the result rather than an error, so a
     * caller can hand over whatever it is showing without pre-filtering.
     *
     * @throws IllegalArgumentException if more than {@link #MAX_JOBS_PER_SCORE_REQUEST} ids are given
     */
    List<JobMatchScoreResponse> getJobMatchScoresForCurrentCandidate(Collection<Long> jobIds);
}
