package com.jbp.service;

import com.jbp.dto.DraftedScreeningAnswer;
import com.jbp.model.CandidateProfile;
import com.jbp.model.ScreeningQuestionType;

/**
 * Writes a first draft of one screening answer from the candidate's own profile.
 *
 * <p>One capability, one interface — the same shape as {@link JobDescriptionGenerator},
 * {@link ScreeningQuestionSuggester} and {@link InterviewQuestionGenerator}.
 *
 * <p><strong>The job posting is never an input.</strong> Only the question and the candidate's own
 * profile. Given the description as well, the model starts writing what the posting wants to hear
 * rather than what is true about the candidate — which is precisely the failure this feature exists
 * to avoid, so the omission is a choice and not an oversight.
 *
 * <p><strong>It declines rather than invents.</strong> A profile that cannot ground the answer comes
 * back as a decline, which the caller turns into a 422 and the dialog renders as design 22b's state
 * G. That matters more here than in any other AI feature in this system: every other one puts words
 * in front of the person who asked for them, and this one puts words in front of a recruiter under
 * the candidate's name.
 */
public interface ScreeningAnswerAssistant {

    /**
     * Never throws. A provider failure and a decline are both reported through the returned value —
     * see {@link DraftedScreeningAnswer}, which distinguishes them — because the two lead to
     * different HTTP statuses and opposite advice to the candidate.
     */
    DraftedScreeningAnswer draft(AnswerBrief brief);

    /**
     * Everything the model is told. A record because it is assembled per call and never mutated, and
     * nested here because it exists only as this interface's input.
     *
     * <p>{@code answerType} is carried because it decides the length of what comes back: without it a
     * one-line question is answered with a paragraph that cannot fit the control it has to go into
     * (design 22's own note, and design 22b state E).
     */
    record AnswerBrief(String question, ScreeningQuestionType answerType, CandidateProfile profile) {
    }
}
