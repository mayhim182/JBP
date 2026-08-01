package com.jbp.model;

/**
 * Which scorer produced a result.
 *
 * <p>Reported on every {@code MatchResult} because design 20 shows a "hybrid · rules 70 / meaning 30"
 * chip, and that is a function of server configuration. If the frontend inferred it, it would duplicate
 * the config and drift the first time the ratio is retuned — and a wrong weight beside a right bar is
 * worse than no weight at all.
 *
 * <p>Also the honest answer to "why did this score change?" — a mode switch explains it instantly.
 */
public enum ScorerMode {
    RULE,
    EMBEDDING,

    /** Story 13.4. Selectable in configuration only once that scorer exists. */
    HYBRID
}
