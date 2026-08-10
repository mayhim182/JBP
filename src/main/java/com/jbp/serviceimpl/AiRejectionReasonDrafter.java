package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.DraftedRejectionReason;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.Job;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.RejectionReasonDrafter;
import com.jbp.util.CandidateProfileText;
import jakarta.validation.Validator;

import java.util.List;

/**
 * Drafts the rejection with the model, reusing the pipeline every AI task shares.
 *
 * <p>Only a prompt, a response type and a fallback — timeout, single retry, rate limiting,
 * truncation, strict parsing, validation and degrading on failure all come from
 * {@link AbstractStructuredAiTask}, exactly as in {@link AiApplicantSummarizer}.
 *
 * <p>Not a {@code @Service}: {@code RejectionDraftConfig} decides which implementation exists, so the
 * capability switch is resolved once at startup rather than on every request.
 */
public class AiRejectionReasonDrafter
        extends AbstractStructuredAiTask<RejectionReasonDrafter.RejectionBrief, DraftedRejectionReason>
        implements RejectionReasonDrafter {

    /**
     * The constraint block Story 14.3 established, kept word-for-word rather than paraphrased so the
     * two cannot drift apart. The stakes are higher here: 14.3's output is read by the recruiter who
     * asked for it, and this one is read by the person it is about.
     */
    private static final String PROTECTED_CHARACTERISTICS = """
            Never mention or infer any of the following, about anyone, for any reason:
            - age, date of birth, or how long ago someone studied or started working
            - gender or the pronouns a name might suggest
            - race, ethnicity, caste, colour, or national origin
            - nationality, immigration status, or right to work
            - religion or belief
            - disability, health, or pregnancy
            - marital or family status
            - sexual orientation
            """;

    private static final String SYSTEM_PROMPT = """
            You are helping a recruiter write to someone whose application was not successful. You are
            given the role, and the requirements this application showed no evidence for. Write the
            message the candidate will read.

            Reply with only a JSON object, no markdown and no commentary, using exactly these keys:
            {
              "reason": string
            }

            Rules:
            - Use no keys other than the one listed. Extra keys cause the whole answer to be discarded.
            - Two or three sentences. Write to the candidate directly, as "you".
            - Name the gap concretely, in the role's own terms. "This role runs on Kafka day to day and
              your profile doesn't show that yet" tells someone something they can act on. "You weren't
              the right fit" tells them nothing, and is the reason rejections read as form letters.
            - Use ONLY the role and the unmet requirements listed below. Never invent a requirement, a
              shortcoming, an employer, a technology, a duration or a qualification.
            - If no unmet requirements are listed, say plainly that the decision was close and the role
              went to someone whose experience lined up more directly. Do not manufacture a
              shortcoming to explain the outcome.

            You have NOT been shown this person's profile, the recruiter's private notes, or any
            rating or score, and you must not write as though you have:
            - never describe their attitude, ambition, motivation, potential, or character
            - never say how experienced or senior they are, or what they are "ready for"
            - never claim they lack anything that is not in the list of unmet requirements
            - never compare them to other applicants, and never rank them

            %s
            You will sometimes be given a place or a job title. These are NOT evidence of any of the
            above. Do not reason from them, do not hint at them, and do not mention that you are
            avoiding them. Write only about the work.

            No false encouragement. Do not write "please apply again", "we'll keep your CV on file",
            "we were very impressed", or "you have a bright future" — nothing you have been given
            supports any of it, and a warm sentence that says nothing is crueller than a clear one,
            because it leaves the person guessing about what actually happened. Being kind here means
            being specific and being brief. Do not pad, and do not apologise twice.

            If you cannot write an honest message from what you have been given, return "" — an empty
            string. An empty answer is always better than an invented one. A person reads this.
            """.formatted(PROTECTED_CHARACTERISTICS);

    public AiRejectionReasonDrafter(ChatCompletionClient chatCompletionClient,
                                    ObjectMapper objectMapper,
                                    Validator validator,
                                    AiTaskBudget budget) {
        super(chatCompletionClient, objectMapper, validator, budget);
    }

    /**
     * Runs the shared pipeline, then turns its fallback into a failure — the same conversion
     * {@link AiApplicantSummarizer#summarise} performs, and for the same reason: a blank draft is not
     * something the panel can offer, and design 26 C4 is the state that tells the recruiter the
     * rejection has not been sent.
     */
    @Override
    public DraftedRejectionReason draft(RejectionBrief brief) {
        DraftedRejectionReason drafted = execute(brief);
        if (drafted.wasUnavailable()) {
            throw new LlmUnavailableException("No rejection reason could be drafted", true);
        }
        return drafted;
    }

    @Override
    protected String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected Class<DraftedRejectionReason> responseType() {
        return DraftedRejectionReason.class;
    }

    @Override
    protected DraftedRejectionReason fallback() {
        return DraftedRejectionReason.unavailable();
    }

    /**
     * The role first, then what it asked for and did not find.
     *
     * <p>Same order as Story 14.3, and for the same reason: the job is the question the message
     * answers, so it goes before the evidence. There is no third section here — the profile that
     * would have been one is deliberately absent from {@link RejectionBrief}.
     */
    @Override
    protected String renderUserMessage(RejectionBrief brief) {
        if (brief == null || brief.job() == null) {
            return "";
        }
        StringBuilder message = new StringBuilder();
        appendJob(message, brief.job());
        appendUnmetRequirements(message, brief.unmetRequirements());
        return message.toString();
    }

    private void appendJob(StringBuilder message, Job job) {
        CandidateProfileText.appendIfPresent(message, "Job title", job.getTitle());
        CandidateProfileText.appendIfPresent(message, "Job description", job.getDescription());
        CandidateProfileText.appendIfPresent(message, "Required skills",
                CandidateProfileText.joined(job.getSkills()));
        CandidateProfileText.appendIfPresent(message, "Job seniority",
                CandidateProfileText.nameOf(job.getSeniority()));
    }

    /**
     * Stated explicitly when empty rather than simply omitted. A missing section reads to the model as
     * an absence of information, which invites it to fill the space; a sentence saying the application
     * met the stated requirements is a fact, and the prompt has a rule for exactly that case.
     */
    private void appendUnmetRequirements(StringBuilder message, List<String> unmetRequirements) {
        message.append('\n');
        if (unmetRequirements == null || unmetRequirements.isEmpty()) {
            message.append("Unmet requirements: none — this application evidenced the stated requirements.");
            return;
        }
        CandidateProfileText.appendIfPresent(message, "Requirements with no evidence in this application",
                CandidateProfileText.joined(unmetRequirements));
    }
}
