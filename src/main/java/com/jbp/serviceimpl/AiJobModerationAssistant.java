package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.JobRiskAssessment;
import com.jbp.model.ModerationCategory;
import com.jbp.model.ModerationFlag;
import com.jbp.model.ModerationRisk;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.JobModerationAssistant;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Asks the model which postings look dangerous, so the queue can put them first.
 *
 * <p>Only a prompt, a response type and a fallback; the rest comes from
 * {@link AbstractStructuredAiTask}. Like {@link AiJobQualityChecker} and unlike
 * {@link AiRejectionReasonDrafter}, it degrades quietly — an unassessed job is a state the queue
 * already draws, and there is no one waiting on the answer to show a failure to.
 *
 * <p>Risk and category arrive as free text and are mapped here, so one unrecognised value costs a
 * single flag rather than the whole reply. The reported level is then reconciled against the flags
 * actually understood — see {@link #reconcile} — because a model that claims a risk and then names
 * nothing would otherwise produce a chip with nothing behind it.
 */
public class AiJobModerationAssistant
        extends AbstractStructuredAiTask<JobModerationAssistant.JobModerationBrief,
                AiJobModerationAssistant.RiskReview>
        implements JobModerationAssistant {

    private static final Logger log = LoggerFactory.getLogger(AiJobModerationAssistant.class);

    private static final String SYSTEM_PROMPT = """
            You screen job postings for a hiring platform and report signs that a posting is
            dangerous or dishonest. An admin reads your answer before deciding; you never decide.

            Reply with only a JSON object, no markdown and no commentary, using exactly these keys:
            {
              "risk": "HIGH" or "MEDIUM" or "NONE",
              "flags": [
                {"category": "SCAM" or "MLM" or "PAY_TO_APPLY" or "DISCRIMINATORY_LANGUAGE"
                             or "UNREALISTIC_CLAIMS",
                 "rationale": string}
              ]
            }

            Report ONLY these five kinds of problem:
            - SCAM: the role appears not to exist, the employer is impersonated, or the posting steers
              the applicant off-platform to hand over money or identity documents.
            - MLM: the work is recruiting other people into a downline, or pay depends on doing so,
              however it is dressed up.
            - PAY_TO_APPLY: the applicant is asked to pay for training, equipment, a background check,
              a starter kit or placement itself.
            - DISCRIMINATORY_LANGUAGE: wording that excludes on age, gender, race, nationality,
              religion or disability.
            - UNREALISTIC_CLAIMS: earnings or progression no honest employer could promise, such as a
              salary far beyond the role's market rate for no stated reason.

            Judge only the text given to you. You have not been told the employer's name, history or
            past reports, and you must not guess at them or reason about who posted this.

            Rules:
            - Use no keys other than those listed. Extra keys cause the whole answer to be discarded.
            - "HIGH" means you would expect an admin to reject this posting. "MEDIUM" means it should
              be read carefully before approving. "NONE" means you found nothing, and then flags must
              be an empty array.
            - rationale is ONE sentence naming what you found, quoting the offending words where there
              are any.
            - Report at most 5 flags, the most serious first. One flag per distinct problem; do not
              repeat the same finding under two categories.
            - A poorly written or vague posting is NOT a risk. Wording quality is reported elsewhere.
              Return "NONE" unless you found one of the five problems above.
            - Never invent a problem to fill the list. Most postings are legitimate.
            """;

    public AiJobModerationAssistant(ChatCompletionClient chatCompletionClient,
                                    ObjectMapper objectMapper,
                                    Validator validator,
                                    AiTaskBudget budget) {
        super(chatCompletionClient, objectMapper, validator, budget);
    }

    @Override
    public Optional<JobRiskAssessment> assess(JobModerationBrief brief) {
        RiskReview review = execute(brief);
        if (!review.isAssessed()) {
            return Optional.empty();
        }
        List<ModerationFlag> flags = review.flags().stream()
                .map(this::toFlagOrNull)
                .filter(Objects::nonNull)
                .toList();
        return Optional.of(new JobRiskAssessment(reconcile(review.risk(), flags), flags));
    }

    @Override
    protected String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected Class<RiskReview> responseType() {
        return RiskReview.class;
    }

    @Override
    protected RiskReview fallback() {
        return RiskReview.notAssessed();
    }

    @Override
    protected String renderUserMessage(JobModerationBrief brief) {
        if (brief == null) {
            return "";
        }
        StringBuilder message = new StringBuilder();
        appendIfPresent(message, "Job title", brief.title());
        appendIfPresent(message, "Seniority", brief.seniority() == null ? null : brief.seniority().name());
        appendIfPresent(message, "Employment type", brief.type() == null ? null : brief.type().name());
        appendIfPresent(message, "Required skills", joined(brief.skills()));
        appendIfPresent(message, "Salary range", salaryRange(brief));
        appendIfPresent(message, "Description", brief.description());
        return message.toString();
    }

    /**
     * Settles the level actually stored against the flags actually understood, so the two can never
     * contradict each other in the queue.
     *
     * <p>Both directions matter. A level with no flags behind it draws a chip an admin can expand to
     * find nothing, which reads as a bug in the assessment rather than a judgement. Flags under a
     * {@code NONE} level would hide real findings behind the one chip nobody opens.
     */
    private ModerationRisk reconcile(String reportedRisk, List<ModerationFlag> flags) {
        if (flags.isEmpty()) {
            return ModerationRisk.NONE;
        }
        ModerationRisk risk = riskOrNull(reportedRisk);
        // Something was found, so NONE contradicts the same reply that carried it. MEDIUM is the
        // floor: worth reading, even where the model would not commit to how much.
        return risk == null || risk == ModerationRisk.NONE ? ModerationRisk.MEDIUM : risk;
    }

    /**
     * Maps one raw flag, or returns null when it cannot be trusted. An unrecognised category is
     * dropped alone rather than taking the rest of the reply with it.
     */
    private ModerationFlag toFlagOrNull(RawFlag raw) {
        ModerationCategory category = categoryOrNull(raw.category());
        if (category == null || isBlank(raw.rationale())) {
            log.debug("Discarding one AI moderation flag with an unusable category or rationale");
            return null;
        }
        return ModerationFlag.builder()
                .category(category)
                .rationale(raw.rationale().trim())
                .build();
    }

    private ModerationRisk riskOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ModerationRisk.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unrecognised) {
            return null;
        }
    }

    private ModerationCategory categoryOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return ModerationCategory.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unrecognised) {
            return null;
        }
    }

    /** Null when neither bound is set, so the label is omitted rather than sent empty. */
    private String salaryRange(JobModerationBrief brief) {
        if (brief.salaryMin() == null && brief.salaryMax() == null) {
            return null;
        }
        return brief.salaryMin() + " to " + brief.salaryMax();
    }

    private void appendIfPresent(StringBuilder message, String label, String value) {
        if (!isBlank(value)) {
            message.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    private String joined(Set<String> skills) {
        if (skills == null) {
            return null;
        }
        return String.join(", ", skills.stream()
                .filter(skill -> !isBlank(skill))
                .map(String::trim)
                .toList());
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * The model's answer, with risk and category left as text so one unrecognised value costs a single
     * flag rather than the whole reply.
     *
     * <p>{@code risk} is {@link NotBlank} on purpose: it makes a null level impossible in any reply
     * that survives validation, which is what lets the fallback use null to mean "never assessed"
     * without a legitimate answer ever colliding with it.
     */
    public record RiskReview(@NotBlank String risk, @Size(max = 10) List<RawFlag> flags) {

        /** What the task returns when the model could not be used or its answer was unusable. */
        public static RiskReview notAssessed() {
            return new RiskReview(null, List.of());
        }

        /** False only for {@link #notAssessed()} — see the note on {@code risk}. */
        public boolean isAssessed() {
            return risk != null;
        }

        /** Never null, so callers can stream it without a guard. */
        @Override
        public List<RawFlag> flags() {
            return flags == null ? List.of() : flags;
        }
    }

    public record RawFlag(String category, @Size(max = 300) String rationale) {
    }
}
