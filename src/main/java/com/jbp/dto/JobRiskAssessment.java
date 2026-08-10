package com.jbp.dto;

import com.jbp.model.ModerationFlag;
import com.jbp.model.ModerationRisk;

import java.util.List;

/**
 * What the moderation assistant made of one posting: how risky, and every reason it gave.
 *
 * <p>The existence of this object is itself the claim "this job was assessed". A job that was never
 * looked at has no assessment rather than an empty one — see {@link ModerationRisk} for why the two
 * must stay distinguishable.
 *
 * @param risk  never null; {@link ModerationRisk#NONE} means assessed and clean
 * @param flags empty exactly when the risk is {@code NONE}
 */
public record JobRiskAssessment(ModerationRisk risk, List<ModerationFlag> flags) {

    /** Never null, so callers can stream it without a guard. */
    @Override
    public List<ModerationFlag> flags() {
        return flags == null ? List.of() : flags;
    }
}
