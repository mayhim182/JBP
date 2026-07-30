package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.SuggestedScreeningQuestions;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.ScreeningQuestionSuggester;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Set;

/**
 * Suggests screening questions with the model, reusing the pipeline every AI task shares.
 *
 * <p>Only a prompt, a response type and a fallback — timeout, single retry, rate limiting,
 * truncation, strict parsing, validation and degrading on failure all come from
 * {@link AbstractStructuredAiTask}, exactly as in {@link AiJobDescriptionGenerator}.
 *
 * <p><b>The phrasing rules in the prompt are load-bearing, not style.</b> The editor derives each
 * question's answer type from its wording rather than storing one, so the opening words decide which
 * control the candidate is given. The three lists below mirror the front end's own
 * {@code screeningInputKind} — keep them in step, or a question will be tagged one way and answered
 * another.
 *
 * <p>Only role content is sent: a title, some skills and a seniority. No candidate data.
 */
@Service
public class AiScreeningQuestionSuggester
        extends AbstractStructuredAiTask<ScreeningQuestionSuggester.ScreeningQuestionBrief, SuggestedScreeningQuestions>
        implements ScreeningQuestionSuggester {

    private static final Logger log = LoggerFactory.getLogger(AiScreeningQuestionSuggester.class);

    private static final String SYSTEM_PROMPT = """
            You write screening questions for a hiring platform. You are given the facts a recruiter
            has entered about one role. Suggest questions worth asking every applicant.

            Reply with only a JSON object, no markdown and no commentary, using exactly these keys:
            {
              "questions": array of strings
            }

            Rules:
            - Use no keys other than those listed. Extra keys cause the whole answer to be discarded.
            - Suggest between 3 and 5 questions. One question per string, ending with a question mark.
            - Ask only about the role and the skills given. Never ask about salary expectations,
              notice period, age, gender, marital status, nationality, religion or health.
            - Ask nothing a resume already answers, and nothing that could be looked up.
            - Each question must be answerable in one sitting without preparation.

            Phrasing rules — these decide which answer control the candidate is shown, so follow them
            exactly:
            - A yes/no question MUST begin with one of: Are you, Do you, Did you, Have you, Can you,
              Will you, Is your.
            - A question wanting a few paragraphs MUST begin with one of: Describe, Explain, Tell us,
              Walk us, Why, How would, What would.
            - Any other wording will be treated as a short one-line answer, so use it only for
              questions that genuinely need one line, such as a count of years or a list of tools.
            """;

    public AiScreeningQuestionSuggester(ChatCompletionClient chatCompletionClient,
                                        ObjectMapper objectMapper,
                                        Validator validator,
                                        AiTaskBudget budget) {
        super(chatCompletionClient, objectMapper, validator, budget);
    }

    /**
     * Runs the shared pipeline, then turns its fallback into a failure, for the reason given on
     * {@link ScreeningQuestionSuggester#suggest}: an empty list is not an answer here.
     */
    @Override
    public SuggestedScreeningQuestions suggest(ScreeningQuestionBrief brief) {
        SuggestedScreeningQuestions suggestions = execute(brief);
        if (!suggestions.hasContent()) {
            log.info("No screening questions suggested for title='{}'", brief.title());
            throw new LlmUnavailableException("No screening questions could be suggested", true);
        }
        return suggestions;
    }

    @Override
    protected String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected Class<SuggestedScreeningQuestions> responseType() {
        return SuggestedScreeningQuestions.class;
    }

    @Override
    protected SuggestedScreeningQuestions fallback() {
        return SuggestedScreeningQuestions.noSuggestionsAvailable();
    }

    /**
     * Renders the brief as labelled lines, omitting what the recruiter has not entered so the model
     * is never invited to ask about a fact it was not given.
     */
    @Override
    protected String renderUserMessage(ScreeningQuestionBrief brief) {
        if (brief == null) {
            return "";
        }
        StringBuilder message = new StringBuilder();
        appendIfPresent(message, "Job title", brief.title());
        appendIfPresent(message, "Required skills", joined(brief.skills()));
        appendIfPresent(message, "Seniority", brief.seniority() == null ? null : brief.seniority().name());
        return message.toString();
    }

    private void appendIfPresent(StringBuilder message, String label, String value) {
        if (value != null && !value.isBlank()) {
            message.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    private String joined(Set<String> skills) {
        if (skills == null) {
            return null;
        }
        return String.join(", ", skills.stream()
                .filter(skill -> skill != null && !skill.isBlank())
                .map(String::trim)
                .toList());
    }
}
