package com.jbp.serviceimpl;

import com.jbp.dto.DraftedRejectionReason;
import com.jbp.service.RejectionReasonDrafter;
import com.jbp.exception.LlmUnavailableException;

/**
 * What exists in place of the drafter when the capability is switched off.
 *
 * <p>The client is told before it renders — {@code GET /api/config} — and design 26 C5 removes the
 * trigger rather than disabling it, so in normal operation this is never reached. It exists because a
 * direct API call still has to be answered honestly.
 *
 * <p>Sibling of {@link DisabledApplicantSummarizer}, and the same shape. Note what stays behind when
 * this is the bean in play: the compose panel, the textarea, the counter and the explicit Send are a
 * flow fix the acceptance criteria demand, not an AI feature, so they ship whether or not the model
 * is reachable. Only the trigger goes.
 */
public class DisabledRejectionReasonDrafter implements RejectionReasonDrafter {

    @Override
    public DraftedRejectionReason draft(RejectionBrief brief) {
        throw new LlmUnavailableException("Rejection drafting is switched off", false);
    }
}
