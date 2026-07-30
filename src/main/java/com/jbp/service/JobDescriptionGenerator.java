package com.jbp.service;

import com.jbp.dto.GeneratedJobDescription;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.JobType;
import com.jbp.model.SeniorityLevel;

import java.util.Set;

/**
 * Writes a first-draft job description from the facts a recruiter has already entered.
 *
 * <p>One capability, one interface — the same shape as {@link ResumeParser}, {@link MatchScorer}
 * and {@link EmailSender}. A caller that only needs a draft written does not gain a dependency on
 * resume parsing or quality checking, and adding a later capability adds a sibling interface rather
 * than editing this one.
 *
 * <p>Takes a {@link JobDescriptionBrief} rather than a request DTO so it knows nothing about who is
 * signed in or which company they own. Assembling the brief is the caller's job, which keeps this
 * unit testable with no security context.
 */
public interface JobDescriptionGenerator {

    /**
     * Writes a draft, or throws when the model could not produce one.
     *
     * <p>This is the one AI feature that cannot degrade quietly. Elsewhere — resume autofill, and
     * the quality rules in Story 12.3 — there is a non-AI answer worth returning, so an outage
     * shows up as fewer suggestions. Here the entire output is the model's, so a silent fallback
     * would return four empty sections and the editor would have to guess whether that meant "the
     * model is down" or "your role needs no requirements". Failing loudly lets the caller answer
     * 503 and the editor show the drawn unavailable state instead.
     *
     * <p>The recruiter's own work is never at risk: the description field is untouched until they
     * press Insert, so a failure here costs a click, not their typing.
     *
     * @throws LlmUnavailableException when AI is switched off, the provider is unreachable, or the
     *                                reply could not be used
     */
    GeneratedJobDescription generate(JobDescriptionBrief brief);

    /**
     * Everything the model is told about the role. A record because it is a value assembled per
     * call and never mutated; nested here because it exists only as this interface's input, the
     * same way {@code ResumeParser.ParsedResume} is nested as its output.
     *
     * <p>{@code companyName} and {@code companyDescription} come from the signed-in recruiter's
     * company, never from the client.
     */
    record JobDescriptionBrief(String title,
                               Set<String> skills,
                               String location,
                               boolean remote,
                               JobType type,
                               SeniorityLevel seniority,
                               String companyName,
                               String companyDescription) {
    }
}
