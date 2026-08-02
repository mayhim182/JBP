package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.dto.DraftedScreeningAnswer;
import com.jbp.model.CandidateProfile;
import com.jbp.model.CandidateProject;
import com.jbp.model.Education;
import com.jbp.model.Experience;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.ScreeningAnswerAssistant;
import jakarta.validation.Validator;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Drafts one screening answer with the model, reusing the pipeline every AI task shares.
 *
 * <p>Only a prompt, a response type and a fallback — timeout, single retry, rate limiting,
 * truncation, strict parsing, validation and degrading on failure all come from
 * {@link AbstractStructuredAiTask}, exactly as in {@link AiScreeningQuestionSuggester}.
 *
 * <p><strong>The user message is built here rather than by {@code EmbeddingTexts}.</strong> That
 * class flattens a profile into one comma-joined line for a vector, drops projects entirely, and its
 * output is hashed as {@code sourceHash} — so reusing it would both starve this prompt of the
 * structure it needs and make every stored embedding look stale the first time this feature's needs
 * changed a word of it. Different job, different renderer.
 *
 * <p>Only the candidate's own data is sent. No job title, no description, no employer — see
 * {@link ScreeningAnswerAssistant} for why that omission is load-bearing.
 */
@Service
public class AiScreeningAnswerAssistant
        extends AbstractStructuredAiTask<ScreeningAnswerAssistant.AnswerBrief, DraftedScreeningAnswer>
        implements ScreeningAnswerAssistant {

    private static final String SYSTEM_PROMPT = """
            You help a job candidate write a first draft of one screening-question answer. You are
            given the question and the facts on that candidate's own profile. The draft is shown to
            them to edit before they send it, and it goes to a recruiter under their name.

            Reply with only a JSON object, no markdown and no commentary, using exactly these keys:
            {
              "draft": string
            }

            Rules:
            - Use no keys other than the one listed. Extra keys cause the whole answer to be discarded.
            - Write in the first person, as the candidate. Plain sentences, no greeting and no sign-off.
            - Use ONLY facts present in the profile below. Never invent an employer, a project, a
              technology, a date, a metric or an outcome. Do not embellish what is there.
            - If the profile does not contain enough to answer this specific question truthfully,
              return "draft": "" — an empty string. Returning an empty draft is always better than
              returning an invented one. This is not a failure; it is the correct answer.
            - Never claim a qualification, a certification, a clearance or a right to work that the
              profile does not state.
            - Do not mention that the profile exists, and do not refer to yourself.

            Length, decided by the answer type given:
            - SHORT_ANSWER — one sentence, at most about 200 characters.
            - LONG_ANSWER — three to five sentences, at most about 1200 characters.
            """;

    public AiScreeningAnswerAssistant(ChatCompletionClient chatCompletionClient,
                                      ObjectMapper objectMapper,
                                      Validator validator,
                                      AiTaskBudget budget) {
        super(chatCompletionClient, objectMapper, validator, budget);
    }

    /**
     * Straight through to the shared pipeline, unlike {@link AiScreeningQuestionSuggester#suggest},
     * which turns its fallback into a failure. Here the caller has to tell a fallback from a decline
     * and answer 503 or 422 accordingly, so both are returned rather than thrown.
     */
    @Override
    public DraftedScreeningAnswer draft(AnswerBrief brief) {
        return execute(brief);
    }

    @Override
    protected String systemPrompt() {
        return SYSTEM_PROMPT;
    }

    @Override
    protected Class<DraftedScreeningAnswer> responseType() {
        return DraftedScreeningAnswer.class;
    }

    @Override
    protected DraftedScreeningAnswer fallback() {
        return DraftedScreeningAnswer.unavailable();
    }

    /**
     * Renders the brief as labelled lines, omitting what the candidate has not entered so the model
     * is never invited to write about a fact it was not given.
     *
     * <p>The question goes last. It is the instruction that everything above it is evidence for, and
     * a long profile sitting between it and the reply is the easiest way to have it half-followed.
     */
    @Override
    protected String renderUserMessage(AnswerBrief brief) {
        if (brief == null || brief.question() == null || brief.question().isBlank()) {
            return "";
        }
        StringBuilder message = new StringBuilder();
        appendProfile(message, brief.profile());
        appendIfPresent(message, "Answer type", nameOf(brief.answerType()));
        appendIfPresent(message, "Question", brief.question());
        return message.toString();
    }

    private void appendProfile(StringBuilder message, CandidateProfile profile) {
        if (profile == null) {
            return;
        }
        appendIfPresent(message, "Headline", profile.getHeadline());
        appendIfPresent(message, "Location", profile.getLocation());
        appendIfPresent(message, "Seniority", nameOf(profile.getSeniority()));
        appendIfPresent(message, "Skills", joined(profile.getSkills()));

        for (Experience experience : nonNull(profile.getExperiences())) {
            appendIfPresent(message, "Experience", labelled(
                    experience.getTitle(),
                    experience.getCompany(),
                    dateRange(experience.getStartDate(), experience.getEndDate()),
                    experience.getDescription()));
        }
        for (CandidateProject project : nonNull(profile.getProjects())) {
            appendIfPresent(message, "Project", labelled(project.getName(), project.getDescription()));
        }
        // Education is sent even though it cannot pass the eligibility gate on its own: a question
        // about what someone studied has no other truthful source, and withholding it would make the
        // assistant decline an answer the profile can actually support.
        for (Education education : nonNull(profile.getEducations())) {
            appendIfPresent(message, "Education", labelled(
                    education.getDegree(), education.getFieldOfStudy(), education.getInstitution()));
        }
    }

    private void appendIfPresent(StringBuilder message, String label, String value) {
        if (value != null && !value.isBlank()) {
            message.append(label).append(": ").append(value.trim()).append('\n');
        }
    }

    private String labelled(String... parts) {
        return String.join(" · ", presentOnly(parts));
    }

    private String dateRange(String from, String to) {
        List<String> present = presentOnly(from, to);
        return present.isEmpty() ? null : String.join(" to ", present);
    }

    private List<String> presentOnly(String... parts) {
        return Arrays.stream(parts)
                .filter(part -> part != null && !part.isBlank())
                .map(String::trim)
                .toList();
    }

    private String joined(Collection<String> values) {
        if (values == null) {
            return null;
        }
        return String.join(", ", values.stream()
                .filter(value -> value != null && !value.isBlank())
                .map(String::trim)
                .sorted()
                .toList());
    }

    private <T> List<T> nonNull(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    private String nameOf(Enum<?> value) {
        return value == null ? null : value.name();
    }
}
