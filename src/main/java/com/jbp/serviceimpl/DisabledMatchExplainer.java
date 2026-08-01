package com.jbp.serviceimpl;

import com.jbp.service.MatchExplainer;

/**
 * What serves when the match-explanation capability is switched off.
 *
 * <p>Returns precisely what a model outage already returns — the rule scorer's own wording, marked
 * as not generated — so design 20b's state B renders unchanged and no caller needs a second branch.
 * The suggestion is dropped with it, because that card belongs to the generated panel.
 *
 * <p>No cache in front of it: there is nothing to reuse, and wrapping a pure function in a cache
 * would only make the flag look more expensive than it is.
 */
public class DisabledMatchExplainer implements MatchExplainer {

    @Override
    public MatchExplanation explain(MatchExplanationInput input) {
        return MatchExplanation.fromRules(input.ruleReason(), null, null);
    }
}
