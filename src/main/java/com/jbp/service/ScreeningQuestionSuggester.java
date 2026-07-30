package com.jbp.service;

import com.jbp.dto.SuggestedScreeningQuestions;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.SeniorityLevel;

import java.util.Set;

/**
 * Proposes screening questions for a role the recruiter is authoring.
 *
 * <p>Its own interface rather than a method on {@link JobDescriptionGenerator}: a caller that wants
 * questions should not acquire a dependency on description writing, and a later capability arrives
 * as a sibling instead of an edit here. Same shape as {@link ResumeParser} and {@link MatchScorer}.
 *
 * <p>Suggesting only. Nothing is added to the job — the recruiter accepts questions one at a time in
 * the editor, and the job is not touched until they save it themselves.
 */
public interface ScreeningQuestionSuggester {

    /**
     * Suggests questions, or throws when the model could not produce any.
     *
     * <p>Fails loudly for the same reason as {@link JobDescriptionGenerator#generate}: there is no
     * non-AI way to invent questions, so an empty list would leave the editor unable to tell "the
     * model is down" from "this role needs no screening". The recruiter is never blocked either way
     * — adding questions by hand works exactly as it always has.
     *
     * @throws LlmUnavailableException when AI is switched off, the provider is unreachable, or the
     *                                reply could not be used
     */
    SuggestedScreeningQuestions suggest(ScreeningQuestionBrief brief);

    /**
     * What the model is told about the role. Nested here because it exists only as this interface's
     * input, matching {@code JobDescriptionGenerator.JobDescriptionBrief}.
     *
     * <p>Deliberately not a reuse of that brief: this needs three of its eight fields, and passing
     * nulls for location, remote, type and company would tell a reader those facts were considered
     * and found absent rather than never asked for.
     */
    record ScreeningQuestionBrief(String title, Set<String> skills, SeniorityLevel seniority) {
    }
}
