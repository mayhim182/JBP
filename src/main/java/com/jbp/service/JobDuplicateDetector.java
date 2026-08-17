package com.jbp.service;

import com.jbp.dto.DuplicateCheck;
import com.jbp.model.Job;

import java.util.Collection;
import java.util.Map;

/**
 * Finds postings that closely resemble one another within a single company.
 *
 * <p><strong>No model call.</strong> This compares vectors Story 13.2 already stores, so it costs two
 * queries and a few hundred multiply-adds however many postings are checked. That is also why it has
 * no capability flag: there is no AI here to switch off.
 *
 * <p><strong>Advisory, and more weakly than Story 14.5's assistant.</strong> Duplicates are permitted —
 * re-posting a closed role is ordinary and legitimate — so nothing on this path may block a save, a
 * submission or an approval. It reports a resemblance and stops.
 *
 * <p><strong>Scoped to one company by construction.</strong> Two employers advertising the same role is
 * a market, not a duplicate, and comparing across companies would turn this into a plagiarism detector
 * nobody asked for.
 *
 * <p><strong>Drafts are out of scope, deliberately.</strong> A job is embedded when it is submitted for
 * moderation, so a draft has no vector and can neither be found nor searched against. Embedding on
 * every draft save would spend a provider call per keystroke-save for the one case that is not on the
 * board at all. The blind spot closes itself: two identical drafts are caught the moment the second is
 * submitted, which is when it first tries to reach the board.
 */
public interface JobDuplicateDetector {

    /** One posting's answer. Never throws. */
    DuplicateCheck check(Job job);

    /**
     * The batch form, and the one a list must use — the whole moderation queue costs the same two
     * queries as a single row.
     *
     * @return job id → its answer. A job absent from the map was not assessable.
     */
    Map<Long, DuplicateCheck> checkAll(Collection<Job> jobs);
}
