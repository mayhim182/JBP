package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.JobQualityFinding;
import com.jbp.model.JobQualityField;
import com.jbp.model.QualityFindingSource;
import com.jbp.model.QualitySeverity;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.JobQualityChecker;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * Asks the model for the wording problems the rules cannot see.
 *
 * <p>Only a prompt, a response type and a fallback; everything else comes from
 * {@link AbstractStructuredAiTask}. Unlike the other two Epic 12 tasks this one keeps the base
 * class's silent fallback rather than converting it into a failure — an empty list genuinely means
 * "nothing further to flag", and the rules have already produced whatever the panel shows.
 *
 * <p>Severity and field arrive as free text and are mapped here, with anything unrecognised dropped.
 * The alternative — declaring them as enums on the response record — would let one bad value make
 * Jackson discard every finding in the reply, which trades four good findings for none.
 */
@Service
public class AiJobQualityChecker
        extends AbstractStructuredAiTask<JobQualityChecker.JobQualityBrief, AiJobQualityChecker.QualityReview>
        implements JobQualityChecker {

    private static final Logger log = LoggerFactory.getLogger(AiJobQualityChecker.class);

    private static final String SYSTEM_PROMPT = """
            You review job postings for a hiring platform and report only problems of WORDING.

            Reply with only a JSON object, no markdown and no commentary, using exactly these keys:
            {
              "findings": [
                {"severity": "HIGH" or "MEDIUM" or "LOW",
                 "field": "TITLE" or "DESCRIPTION" or "PHRASING" or "SKILLS" or "SENIORITY",
                 "message": string,
                 "suggestion": string}
              ]
            }

            Report ONLY these three kinds of problem:
            - Vague responsibilities: duties that name no system and no outcome, such as "help with
              various tasks" or "assist the team".
            - Coded language: wording that signals a preference for an age, gender, nationality or
              background, such as "young", "energetic team", "recent graduate", "he/she", "native
              speaker".
            - Unrealistic expectations: demands no candidate could satisfy, such as more years of a
              technology than it has existed, or a list of duties belonging to several distinct jobs.

            Never report these — they are already checked elsewhere, and repeating them wastes the
            recruiter's attention:
            - A missing or short description, a missing salary range, a missing seniority, or too few
              skills. Say nothing about the LENGTH or PRESENCE of any field.

            Rules:
            - Use no keys other than those listed. Extra keys cause the whole answer to be discarded.
            - Use "PHRASING" as the field for coded language and vague wording; use "DESCRIPTION" only
              when the problem is the content rather than how it is written.
            - message states what you found, quoting the offending words. suggestion states what to
              write instead. Both are one sentence.
            - Report at most 5 findings, the most damaging first.
            - Return an empty findings array if the wording is fine. Never invent a problem to fill
              the list.
            """;

    public AiJobQualityChecker(ChatCompletionClient chatCompletionClient,
                               ObjectMapper objectMapper,
                               Validator validator,
                               AiTaskBudget budget) {
        super(chatCompletionClient, objectMapper, validator, budget);
    }

    @Override
    public List<JobQualityFinding> check(JobQualityBrief brief) {
        return execute(brief).findings().stream()
                .map(this::toFindingOrNull)
                .filter(Objects::nonNull)
                .toList();
    }

    @Override
    protected String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected Class<QualityReview> responseType() {
        return QualityReview.class;
    }

    @Override
    protected QualityReview fallback() {
        return QualityReview.nothingToAdd();
    }

    /**
     * Sends the posting's content. Salary is deliberately absent: the model has been told not to
     * comment on missing fields, and sending a number it must ignore only invites it to.
     */
    @Override
    protected String renderUserMessage(JobQualityBrief brief) {
        if (brief == null) {
            return "";
        }
        StringBuilder message = new StringBuilder();
        appendIfPresent(message, "Job title", brief.title());
        appendIfPresent(message, "Seniority", brief.seniority() == null ? null : brief.seniority().name());
        appendIfPresent(message, "Employment type", brief.type() == null ? null : brief.type().name());
        appendIfPresent(message, "Required skills", joined(brief.skills()));
        appendIfPresent(message, "Description", brief.description());
        return message.toString();
    }

    /**
     * Maps one raw finding, or returns null when it cannot be trusted. A finding with an unknown
     * severity or field is dropped alone rather than taking the rest of the reply with it.
     */
    private JobQualityFinding toFindingOrNull(RawFinding raw) {
        QualitySeverity severity = severityOrNull(raw.severity());
        JobQualityField field = fieldOrNull(raw.field());
        if (severity == null || field == null || isBlank(raw.message())) {
            log.debug("Discarding one AI quality finding with an unusable severity or field");
            return null;
        }
        return JobQualityFinding.builder()
                .severity(severity)
                .field(field)
                .message(raw.message().trim())
                .suggestion(raw.suggestion() == null ? "" : raw.suggestion().trim())
                .source(QualityFindingSource.AI)
                .build();
    }

    private QualitySeverity severityOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return QualitySeverity.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unrecognised) {
            return null;
        }
    }

    private JobQualityField fieldOrNull(String value) {
        if (value == null) {
            return null;
        }
        try {
            return JobQualityField.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unrecognised) {
            return null;
        }
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
     * The model's answer, with severity and field left as text so one unrecognised value costs a
     * single finding rather than the whole reply.
     */
    public record QualityReview(@Size(max = 10) List<RawFinding> findings) {

        public static QualityReview nothingToAdd() {
            return new QualityReview(List.of());
        }

        /** Never null, so callers can stream it without a guard. */
        @Override
        public List<RawFinding> findings() {
            return findings == null ? List.of() : findings;
        }
    }

    public record RawFinding(String severity,
                             String field,
                             @Size(max = 300) String message,
                             @Size(max = 300) String suggestion) {
    }
}
