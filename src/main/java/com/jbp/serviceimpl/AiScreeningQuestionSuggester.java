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
 * <p><b>The prompt asks for the answer type rather than dictating phrasing.</b> It used to do the
 * opposite: the editor guessed each question's type from its opening words, so the prompt had to force
 * wording that would make the guess land — and any question phrased naturally got the wrong control.
 * The type is now a field the recruiter can override before accepting, which frees the wording to be
 * whatever asks the question best.
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
              "questions": [
                { "question": string, "answerType": "SHORT_ANSWER" | "LONG_ANSWER" | "YES_NO" }
              ]
            }

            Rules:
            - Use no keys other than those listed. Extra keys cause the whole answer to be discarded.
            - Suggest between 3 and 5 questions. One question per entry, ending with a question mark.
            - Ask only about the role and the skills given. Never ask about salary expectations,
              notice period, age, gender, marital status, nationality, religion or health.
            - Ask nothing a resume already answers, and nothing that could be looked up.
            - Each question must be answerable in one sitting without preparation.

            Answer types — every question needs one, and it must match what the question really asks
            for. Word the question however asks it best; the type is what decides the control, not the
            opening words:
            - SHORT_ANSWER — one line, such as a count of years, a list of tools, or a place.
            - LONG_ANSWER — a few paragraphs, such as an account of how something was built or debugged.
            - YES_NO — a yes or a no and nothing else. Use it only when no further detail is wanted:
              a question that asks whether something is true AND to describe it is LONG_ANSWER.
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
