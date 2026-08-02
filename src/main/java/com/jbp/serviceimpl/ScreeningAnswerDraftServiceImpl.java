package com.jbp.serviceimpl;

import com.jbp.config.AiCapabilities;
import com.jbp.dto.DraftAnswerRequest;
import com.jbp.dto.DraftAnswerResponse;
import com.jbp.dto.DraftedScreeningAnswer;
import com.jbp.exception.InsufficientProfileException;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.exception.RateLimitExceededException;
import com.jbp.model.CandidateProfile;
import com.jbp.model.ScreeningQuestionType;
import com.jbp.security.CurrentUserProvider;
import com.jbp.service.CandidateProfileService;
import com.jbp.service.ScreeningAnswerAssistant;
import com.jbp.service.ScreeningAnswerDraftService;
import com.jbp.util.AnswerDraftEligibility;
import com.jbp.util.PerUserCallBudget;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class ScreeningAnswerDraftServiceImpl implements ScreeningAnswerDraftService {

    private static final String NOTHING_TO_DRAFT_FROM =
            "There's nothing in your profile to draft from yet. Add a role or a project first.";

    private final CurrentUserProvider currentUserProvider;
    private final CandidateProfileService candidateProfileService;
    private final ScreeningAnswerAssistant screeningAnswerAssistant;
    /**
     * Two {@link PerUserCallBudget} beans exist — this one and Story 14.3's summary ceiling — so the
     * field name is what picks between them: Spring falls back to matching the constructor parameter
     * against the bean name. <strong>Renaming this field silently swaps in the other allowance.</strong>
     */
    private final PerUserCallBudget draftAnswerBudget;
    private final AiCapabilities aiCapabilities;

    /**
     * The order matters, and each step is here rather than later for a reason.
     *
     * <p>The capability, the answer type and the profile are all checked <em>before</em> the
     * allowance is touched, so a request that was never going to produce a draft cannot cost the
     * candidate one. The allowance is then reserved <em>before</em> the model is called, so two
     * requests in flight together cannot both take the last slot — and refunded if the call fails,
     * which is the whole reason it is a reserve rather than a record-on-success.
     */
    @Override
    public DraftAnswerResponse draftAnswer(DraftAnswerRequest request) {
        if (!aiCapabilities.screeningAnswerAssist()) {
            throw new LlmUnavailableException("Screening-answer assist is switched off", false);
        }
        ensureTypeCanBeDrafted(request.getAnswerType());

        Long candidateId = currentUserProvider.getCurrentUserId();
        CandidateProfile profile = candidateProfileService.findProfileForCandidate(candidateId)
                .orElse(null);
        if (!AnswerDraftEligibility.canGroundADraftedAnswer(profile)) {
            log.info("Declining a draft for candidate {} — the profile has no role or project to work from",
                    candidateId);
            throw new InsufficientProfileException(NOTHING_TO_DRAFT_FROM);
        }

        if (!draftAnswerBudget.tryReserveCall(candidateId)) {
            log.info("Candidate {} has used all {} of their drafts for the current window",
                    candidateId, draftAnswerBudget.maxCallsPerWindow());
            throw new RateLimitExceededException(
                    "You've used today's drafts. More tomorrow — you can still write your answers yourself.");
        }

        DraftedScreeningAnswer drafted = screeningAnswerAssistant.draft(
                new ScreeningAnswerAssistant.AnswerBrief(
                        request.getQuestion(), request.getAnswerType(), profile));

        if (drafted.wasUnavailable()) {
            draftAnswerBudget.refundCall(candidateId);
            log.info("Draft unavailable for candidate {} — allowance refunded", candidateId);
            throw new LlmUnavailableException("The model could not draft this answer", true);
        }
        if (drafted.wasDeclined()) {
            // The gate passed on structure and the assistant still found nothing to write from — an
            // experience entry that is only a job title, say. Refunded for the same reason a failure
            // is: the candidate received no draft. It is 422 rather than 503 because nothing of ours
            // went wrong, and the dialog must send them to their profile rather than offer a retry.
            draftAnswerBudget.refundCall(candidateId);
            log.info("Assistant declined to draft for candidate {} — allowance refunded", candidateId);
            throw new InsufficientProfileException(NOTHING_TO_DRAFT_FROM);
        }

        int remaining = draftAnswerBudget.remainingCalls(candidateId);
        // Length only, never content: a draft is about to be shown to a recruiter under this
        // candidate's name and has no business in a log file.
        log.info("Drafted a {}-character answer for candidate {}, {} drafts remaining",
                drafted.getDraft().length(), candidateId, remaining);
        return DraftAnswerResponse.builder().draft(drafted.getDraft()).remaining(remaining).build();
    }

    /**
     * A Yes/No question has no draft trigger and an untyped one has none either (design 22, and
     * design 23's carve-out that untyped fails closed), so a request naming either came from a client
     * that ignored its own UI. Refused as a bad request rather than answered, and refused before the
     * allowance is touched.
     */
    private void ensureTypeCanBeDrafted(ScreeningQuestionType answerType) {
        if (answerType != ScreeningQuestionType.SHORT_ANSWER && answerType != ScreeningQuestionType.LONG_ANSWER) {
            throw new IllegalArgumentException("Only short and long answers can be drafted");
        }
    }
}
