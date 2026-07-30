package com.jbp.model;

/**
 * Whether a finding came from a deterministic rule or from the model.
 *
 * <p>Shown to the recruiter, not just recorded: a rule finding is a fact about the posting and will
 * say the same thing every time, while an AI finding is a judgement that may read differently on the
 * next check. Telling the two apart is what lets a recruiter decide how much weight to give one.
 *
 * <p>It also makes the AI-unavailable state self-explanatory — the panel still lists every
 * {@code RULE} finding, and only the {@code AI} ones are missing.
 */
public enum QualityFindingSource {

    RULE,

    AI
}
