package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.ApplicantSummary;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.Job;
import com.jbp.service.ApplicantSummarizer;
import com.jbp.service.ChatCompletionClient;
import com.jbp.util.CandidateProfileText;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Writes the three-line read with the model, reusing the pipeline every AI task shares.
 *
 * <p>Only a prompt, a response type and a fallback — timeout, single retry, rate limiting,
 * truncation, strict parsing, validation and degrading on failure all come from
 * {@link AbstractStructuredAiTask}, exactly as in {@link AiScreeningAnswerAssistant}.
 *
 * <p>Not a {@code @Service}: {@code ApplicantSummaryConfig} decides which implementation exists, so
 * the capability switch is resolved once at startup rather than on every request.
 */
public class AiApplicantSummarizer
        extends AbstractStructuredAiTask<ApplicantSummarizer.ApplicantBrief, ApplicantSummary>
        implements ApplicantSummarizer {

    private static final Logger log = LoggerFactory.getLogger(AiApplicantSummarizer.class);

    private static final String SYSTEM_PROMPT = """
            You help a recruiter read one applicant against one job. You are given the job, that
            applicant's profile, and which dimensions of the match are strong or thin. Write three
            short lines that help the recruiter decide what to do next.

            Reply with only a JSON object, no markdown and no commentary, using exactly these keys:
            {
              "strongestFit": string,
              "mainGap": string,
              "worthProbing": string
            }

            Rules:
            - Use no keys other than those listed. Extra keys cause the whole answer to be discarded.
            - One or two sentences per line. Plain, specific, and about this person and this job.
            - Use ONLY facts present in the profile below. Never invent an employer, a technology, a
              duration, a qualification, a metric or an outcome.
            - "strongestFit" — what in their history most closely matches what this role does.
            - "mainGap" — what the role asks for that the profile does not show. If the profile shows
              adjacent work, say so; a gap is not always a weakness.
            - "worthProbing" — one question to ask in an interview that would resolve the gap. Ask
              about something the profile cannot answer.

            Never mention or infer any of the following, about anyone, for any reason:
            - age, date of birth, or how long ago someone studied or started working
            - gender or the pronouns a name might suggest
            - race, ethnicity, caste, colour, or national origin
            - nationality, immigration status, or right to work
            - religion or belief
            - disability, health, or pregnancy
            - marital or family status
            - sexual orientation
            You will sometimes be given a name, a university, dates, or a place. These are NOT
            evidence of any of the above. Do not reason from them, do not hint at them, and do not
            mention that you are avoiding them. Write only about work.

            Never restate the match score. Do not write a percentage, a number out of a number, a
            count of matched skills, or any comparison to other applicants. Naming concrete things —
            what someone built, ran, or worked on — is what makes this useful; a score is not.

            If the profile does not contain enough to write an honest read, return all three keys as
            "" — empty strings. Returning an empty read is always better than returning an invented
            one. This is not a failure; it is the correct answer.
            """;

    public AiApplicantSummarizer(ChatCompletionClient chatCompletionClient,
                                 ObjectMapper objectMapper,
                                 Validator validator,
                                 AiTaskBudget budget) {
        super(chatCompletionClient, objectMapper, validator, budget);
    }

    /**
     * Runs the shared pipeline, then turns its fallback into a failure — the same conversion
     * {@link AiScreeningQuestionSuggester#suggest} performs, and for the same reason: an empty value
     * is not an answer here, and a thrown failure is what keeps it out of the cache.
     *
     * <p>A reply with some lines filled and others blank is neither a read nor a decline. The panel
     * cannot draw it and a recruiter cannot trust it, so it is discarded whole — the same rule
     * {@link AbstractStructuredAiTask} already applies to a reply that fails validation.
     */
    @Override
    public ApplicantSummary summarise(ApplicantBrief brief) {
        /*
         * A profile with nothing in it is a decline, not a failure, and it has to be decided here
         * rather than left to the pipeline: an empty user message makes `execute` return the
         * fallback, which is indistinguishable from an unreachable model — so the recruiter would be
         * shown "Try again" for a condition retrying cannot change. Design 24 B4 exists precisely
         * because B2's shape promises something it cannot deliver.
         *
         * It also spends no request, which is the reason to check before calling rather than after.
         */
        if (brief != null && CandidateProfileText.asLabelledLines(brief.profile()).isEmpty()) {
            return ApplicantSummary.declined();
        }

        ApplicantSummary summary = execute(brief);
        if (summary.hasAllThreeLines() || summary.wasDeclined()) {
            return summary;
        }
        if (!summary.wasUnavailable()) {
            log.debug("Discarding a partly-written applicant summary");
        }
        throw new LlmUnavailableException("No applicant summary could be written", true);
    }

    @Override
    protected String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected Class<ApplicantSummary> responseType() {
        return ApplicantSummary.class;
    }

    @Override
    protected ApplicantSummary fallback() {
        return ApplicantSummary.unavailable();
    }

    /**
     * The job first, then the applicant, then where the match is strong or thin.
     *
     * <p>That order is deliberate: the job is the question every line is answered against, so it goes
     * before the evidence rather than after it. The banded factors go last because they are a steer
     * rather than a source — they say which way to lean, and the profile says what to write.
     */
    @Override
    protected String renderUserMessage(ApplicantBrief brief) {
        if (brief == null || brief.job() == null) {
            return "";
        }
        String profile = CandidateProfileText.asLabelledLines(brief.profile());
        if (profile.isEmpty()) {
            // Unreachable in practice — summarise() turns an empty profile into a decline before it
            // gets here. Kept as a guard, and deliberately not treated as meaningful: an empty
            // message here would surface as an unreachable model, which is the wrong state entirely.
            return "";
        }
        StringBuilder message = new StringBuilder();
        appendJob(message, brief.job());
        message.append('\n').append(profile);
        appendFactors(message, brief.factors());
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

    private void appendFactors(StringBuilder message, List<FactorSignal> factors) {
        if (factors == null || factors.isEmpty()) {
            return;
        }
        message.append('\n');
        for (FactorSignal factor : factors) {
            if (factor != null && factor.kind() != null && factor.strength() != null) {
                CandidateProfileText.appendIfPresent(message, "Match on " + factor.kind().name(),
                        factor.strength().name());
            }
        }
    }
}
