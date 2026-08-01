package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.MatchExplainer;
import com.jbp.service.MatchScorer.MatchFactor;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Writes design 20's "in plain language" paragraph from an already-computed match.
 *
 * <p><strong>The model writes prose and nothing else.</strong> The "one thing that would help" line is
 * composed here from data — which skill this job names that the profile does not, and how many of the
 * candidate's strongest matches also name it. Both are factual claims about the candidate's own records,
 * and a model asked to produce them would invent plausible numbers. Splitting it this way is what lets
 * design 20 state "6 of your 10 strongest matches" as fact rather than as a guess.
 *
 * <p>Falls back to the rule scorer's own {@code matchReason} whenever the model is unavailable or its
 * reply is unusable, so the panel never renders empty. The suggestion survives the fallback because it
 * was never generated in the first place.
 */
public class AiMatchExplainer extends AbstractStructuredAiTask<MatchExplainer.MatchExplanationInput,
        AiMatchExplainer.ExplanationReply> implements MatchExplainer {

    /**
     * Design 20 sizes this box for two lines at 796px and notes the same string wraps to four on
     * mobile. 200 characters is that budget; a longer reply is a reply that will overflow the card, so
     * it is rejected rather than truncated mid-word.
     */
    private static final int MAX_SUMMARY_CHARACTERS = 200;

    /** Distinguishes "the model could not be used" from a real reply, without a nullable flag. */
    private static final ExplanationReply UNAVAILABLE = new ExplanationReply(null);

    public AiMatchExplainer(ChatCompletionClient chatCompletionClient,
                            ObjectMapper objectMapper,
                            Validator validator,
                            AiTaskBudget budget) {
        super(chatCompletionClient, objectMapper, validator, budget);
    }

    @Override
    public MatchExplanation explain(MatchExplanationInput input) {
        String actionText = actionTextFor(input);
        ExplanationReply reply = execute(input);
        if (reply == UNAVAILABLE || reply.summary() == null || reply.summary().isBlank()) {
            return MatchExplanation.fromRules(input.ruleReason(), actionText, input.missingSkill());
        }
        return new MatchExplanation(reply.summary(), actionText, input.missingSkill(), true);
    }

    /**
     * Composed, never generated. Null when the profile already lists everything this job asks for —
     * design 20's card is simply absent then, rather than showing an invented improvement.
     */
    private String actionTextFor(MatchExplanationInput input) {
        String missingSkill = input.missingSkill();
        if (missingSkill == null || missingSkill.isBlank()) {
            return null;
        }
        SkillDemand demand = input.strongMatchesNaming() == null
                ? null
                : input.strongMatchesNaming().get();
        if (demand != null && demand.isWorthStating()) {
            return "Add " + missingSkill + " to your skills — it's named here and in "
                    + demand.namingCount() + " of your " + demand.consideredCount()
                    + " strongest matches.";
        }
        return "Add " + missingSkill + " to your skills — this role names it and your profile doesn't.";
    }

    @Override
    protected String systemPrompt() {
        return """
                You explain to a job candidate why they matched a role, in plain language.

                Rules:
                - Exactly two sentences, at most 200 characters in total.
                - Describe what lines up and what does not, using the factors given.
                - Never state, restate or imply the numeric score, any weight, or any percentage.
                - Never invent facts. Use only what is in the input.
                - Address the candidate as "you" or "your". Warm and factual, never salesy.
                - Do not suggest an improvement; that sentence is written separately.

                Reply with JSON only, exactly: {"summary": "..."}
                """;
    }

    @Override
    protected Class<ExplanationReply> responseType() {
        return ExplanationReply.class;
    }

    @Override
    protected ExplanationReply fallback() {
        return UNAVAILABLE;
    }

    @Override
    protected String renderUserMessage(MatchExplanationInput input) {
        if (input == null || input.factors() == null || input.factors().isEmpty()) {
            // Nothing to reason over, so spend no request on it.
            return "";
        }
        return "Role: " + input.jobTitle()
                + "\nThe role asks for: " + joined(input.jobSkills())
                + "\nYour profile lists: " + joined(input.candidateSkills())
                + "\nHow each dimension scored:\n" + factorLinesOf(input.factors());
    }

    /**
     * Dimensions and their own wording, not the joined reason string. Weights and contributions are
     * deliberately withheld: the model is told not to mention numbers, and the surest way to stop it
     * quoting one is not to send it.
     */
    private String factorLinesOf(List<MatchFactor> factors) {
        return factors.stream()
                .map(factor -> "- " + factor.kind().getLabel() + ": " + factor.detail())
                .collect(Collectors.joining("\n"));
    }

    private String joined(java.util.Set<String> values) {
        return values == null || values.isEmpty() ? "nothing listed" : String.join(", ", values);
    }

    /**
     * The model's reply. A single-field record rather than a bare string because
     * {@link AbstractStructuredAiTask} parses into a type, and a named shape is also clearer for the
     * model to aim at.
     */
    public record ExplanationReply(
            @NotBlank @Size(max = MAX_SUMMARY_CHARACTERS) String summary) {
    }
}
