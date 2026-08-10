package com.jbp.service;

import com.jbp.dto.DraftedRejectionReason;
import com.jbp.model.Job;

import java.util.List;

/**
 * Drafts the two or three sentences a recruiter sends when they turn someone down.
 *
 * <p>One capability, one interface — the same shape as {@link ApplicantSummarizer},
 * {@link JobDescriptionGenerator} and {@link ScreeningAnswerAssistant}.
 *
 * <p><strong>This is the most consequential text the product generates.</strong> It reaches a named
 * person, about their livelihood, and no one downstream will check it against the evidence. Two of
 * the acceptance criteria are therefore enforced by what this interface refuses to accept rather than
 * by what the prompt asks for — see {@link RejectionBrief}.
 */
public interface RejectionReasonDrafter {

    /**
     * A failure is <em>thrown</em> rather than returned blank, so the caller can draw design 26 C4 —
     * whose copy promises the rejection has not been sent and offers a retry that can actually work.
     *
     * @throws com.jbp.exception.LlmUnavailableException when AI is off, this capability is off, the
     *                                                   provider is unreachable, or the reply could
     *                                                   not be used
     */
    DraftedRejectionReason draft(RejectionBrief brief);

    /**
     * What the drafter is told — and, more importantly, what it is structurally incapable of being
     * told.
     *
     * <p><strong>No private notes and no rating.</strong> The acceptance criterion forbids passing
     * them, and this record has nowhere to put them, so the guarantee holds however the caller is
     * later rewritten. A prompt instruction would not: prompts are edited by people who cannot see
     * every caller. This is the same technique Story 14.3 uses to keep the score out of a summary —
     * withhold the input rather than forbid the output.
     *
     * <p><strong>No candidate profile either</strong>, which is what makes "never states anything
     * unverifiable about the candidate as a person" structural rather than aspirational. A model that
     * has never seen the person cannot characterise them; it can only write about the role and about
     * {@code unmetRequirements}, every entry of which is a requirement of the job that the match
     * found no evidence for. That set difference is a fact about the application, not a judgement
     * about the applicant, and it is what lets the draft name the gap — design 26's copy rule, where
     * <em>"runs on Kafka"</em> beats <em>"wasn't the right fit"</em>.
     *
     * @param job                the role being filled; the drafter writes only about this and the gap
     * @param unmetRequirements  requirements this application showed no evidence for, in the job's own
     *                           words. Empty is legitimate — a strong candidate can still be turned
     *                           down — and the prompt handles it without inventing a shortcoming.
     */
    record RejectionBrief(Job job, List<String> unmetRequirements) {
    }
}
