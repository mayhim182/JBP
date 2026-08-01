package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.GeneratedJobDescription;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.JobDescriptionGenerator;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Set;

/**
 * Writes job descriptions with the model, reusing the pipeline every AI task shares.
 *
 * <p>Like {@link ResumeExtractionTask}, this is only a prompt, a response type and a fallback —
 * timeout, single retry, rate limiting, truncation, strict parsing, Bean Validation and degrading
 * on failure all come from {@link AbstractStructuredAiTask}.
 *
 * <p>Nothing in the chain caches, so pressing Regenerate issues a genuinely new request rather than
 * replaying the previous answer. The variation between drafts is the provider's own sampling; the
 * guarantee this class makes is only that no layer here will short-circuit the call.
 *
 * <p>Only job and company content is sent. No candidate data reaches the provider from this task,
 * which is what lets Epic 12 ship regardless of the data-retention question that governs Epic 11.
 */
/*
 * Paired by condition with DisabledJobDescriptionGenerator so exactly one bean exists. Defaults to
 * present: with the property absent this behaves precisely as it did before the flag existed, which
 * is what makes the capability switch additive rather than a behaviour change.
 */
@Service
@ConditionalOnProperty(name = "app.ai.features.job-description", havingValue = "true",
        matchIfMissing = true)
public class AiJobDescriptionGenerator
        extends AbstractStructuredAiTask<JobDescriptionGenerator.JobDescriptionBrief, GeneratedJobDescription>
        implements JobDescriptionGenerator {

    private static final Logger log = LoggerFactory.getLogger(AiJobDescriptionGenerator.class);

    private static final String SYSTEM_PROMPT = """
            You write job descriptions for a hiring platform. You are given the facts a recruiter
            has entered about one role. Write a first draft they will edit.

            Reply with only a JSON object, no markdown and no commentary, using exactly these keys:
            {
              "summary": string,
              "responsibilities": array of strings,
              "requirements": array of strings,
              "niceToHave": array of strings
            }

            Rules:
            - Use no keys other than those listed. Extra keys cause the whole answer to be discarded.
            - summary is two or three sentences of prose describing the role and its impact.
            - responsibilities, requirements and niceToHave are 3 to 6 short items each, one clause
              per item, with no leading bullet characters or numbering.
            - Build only on the facts given. Never invent a salary, a benefit, a team size, a
              technology that was not listed, or a company claim that was not provided.
            - Omit what you were not told rather than guessing at it.
            - Write plainly. No superlatives, no "rockstar" or "ninja", and nothing that signals an
              age, gender or nationality preference.
            - Use an empty array for a section the given facts cannot support.
            """;

    public AiJobDescriptionGenerator(ChatCompletionClient chatCompletionClient,
                                     ObjectMapper objectMapper,
                                     Validator validator,
                                     AiTaskBudget budget) {
        super(chatCompletionClient, objectMapper, validator, budget);
    }

    /**
     * Runs the shared pipeline, then turns its fallback into a failure.
     *
     * <p>{@code execute} cannot throw by design, which is right for a feature with a non-AI answer
     * and wrong for this one — four empty sections are not a draft. Converting here keeps that
     * decision in the class that knows what an unusable result means, and leaves the base class's
     * guarantee intact for every other task.
     */
    @Override
    public GeneratedJobDescription generate(JobDescriptionBrief brief) {
        GeneratedJobDescription draft = execute(brief);
        if (!draft.hasContent()) {
            // Why it failed is already logged by the pipeline and the transport; this only records
            // that the recruiter's request ended without a draft.
            log.info("No description draft produced for title='{}'", brief.title());
            throw new LlmUnavailableException("No description draft could be generated", true);
        }
        return draft;
    }

    @Override
    protected String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected Class<GeneratedJobDescription> responseType() {
        return GeneratedJobDescription.class;
    }

    @Override
    protected GeneratedJobDescription fallback() {
        return GeneratedJobDescription.noDraftAvailable();
    }

    /**
     * Renders the brief as labelled lines. Absent facts are left out entirely rather than sent as
     * "none", so the model is never nudged into writing about something the recruiter did not say.
     */
    @Override
    protected String renderUserMessage(JobDescriptionBrief brief) {
        if (brief == null) {
            return "";
        }
        StringBuilder message = new StringBuilder();
        appendIfPresent(message, "Job title", brief.title());
        appendIfPresent(message, "Required skills", joined(brief.skills()));
        appendIfPresent(message, "Seniority", nameOf(brief.seniority()));
        appendIfPresent(message, "Employment type", nameOf(brief.type()));
        appendIfPresent(message, "Location", brief.remote() ? remoteLocation(brief.location()) : brief.location());
        appendIfPresent(message, "Company", brief.companyName());
        appendIfPresent(message, "About the company", brief.companyDescription());
        return message.toString();
    }

    private void appendIfPresent(StringBuilder message, String label, String value) {
        if (value != null && !value.isBlank()) {
            message.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    /**
     * A remote role still has a location worth stating — the timezone or hiring region — so the two
     * are combined rather than one replacing the other.
     */
    private String remoteLocation(String location) {
        return location == null || location.isBlank() ? "Remote" : "Remote (" + location.trim() + ")";
    }

    private String joined(Set<String> skills) {
        return skills == null ? null : String.join(", ", withoutBlanks(skills));
    }

    private Collection<String> withoutBlanks(Set<String> skills) {
        return skills.stream().filter(skill -> skill != null && !skill.isBlank()).map(String::trim).toList();
    }

    private String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
