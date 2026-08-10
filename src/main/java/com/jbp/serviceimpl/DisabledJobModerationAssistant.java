package com.jbp.serviceimpl;

import com.jbp.dto.JobRiskAssessment;
import com.jbp.service.JobModerationAssistant;

import java.util.Optional;

/**
 * What exists in place of the assistant when the capability is switched off.
 *
 * <p>Empty rather than thrown, unlike {@link DisabledRejectionReasonDrafter}. That sibling answers a
 * recruiter who pressed a button and deserves to be told; nothing here has a caller waiting. Returning
 * empty means the listener has one code path whether the capability is off, the provider is down or
 * the reply was unusable — in every case no assessment is stored, and the queue draws the job as
 * unassessed.
 *
 * <p>It is also what keeps the "no chips with AI off" criterion structural: with this bean in play
 * nothing can write a risk level, so no amount of later editing to the queue can make one appear.
 */
public class DisabledJobModerationAssistant implements JobModerationAssistant {

    @Override
    public Optional<JobRiskAssessment> assess(JobModerationBrief brief) {
        return Optional.empty();
    }
}
