package com.jbp.serviceimpl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.InterviewQuestionKind;
import com.jbp.service.ChatCompletionClient;
import com.jbp.service.InterviewQuestionGenerator;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Size;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Story 14.1's generator, on the shared {@link AbstractStructuredAiTask} pipeline.
 *
 * <p><strong>The pipeline's fallback and this feature's contract disagree, deliberately.</strong>
 * {@code AbstractStructuredAiTask.execute} never throws — it returns {@link #fallback()} for every
 * failure — but {@link InterviewQuestionGenerator} must throw so the endpoint can answer 503 and the
 * client can draw state D. So the fallback is a sentinel and this class converts it back into an
 * exception. Reaching for a change to the base class would have been the wrong move: Epic 14's own
 * done-condition is that no story needed to touch it, and the seam is fine — one feature simply wants
 * a louder failure than the default.
 */
public class AiInterviewQuestionGenerator
        extends AbstractStructuredAiTask<InterviewQuestionGenerator.JobBrief,
        AiInterviewQuestionGenerator.QuestionsReply>
        implements InterviewQuestionGenerator {

    /** The acceptance criterion's range. Outside it the reply is unusable rather than trimmed. */
    private static final int MIN_QUESTIONS = 5;
    private static final int MAX_QUESTIONS = 8;

    /** Long enough for a real interview question, short enough that a paragraph is rejected. */
    private static final int MAX_QUESTION_CHARACTERS = 160;

    private static final QuestionsReply UNAVAILABLE = new QuestionsReply(null, null, null);

    public AiInterviewQuestionGenerator(ChatCompletionClient chatCompletionClient,
                                        ObjectMapper objectMapper,
                                        Validator validator,
                                        AiTaskBudget budget) {
        super(chatCompletionClient, objectMapper, validator, budget);
    }

    @Override
    public InterviewQuestions generate(JobBrief brief) {
        QuestionsReply reply = execute(brief);
        if (reply == UNAVAILABLE) {
            throw new LlmUnavailableException("Interview questions could not be generated", true);
        }
        InterviewQuestions questions = toQuestions(reply);
        if (questions.total() < MIN_QUESTIONS || questions.total() > MAX_QUESTIONS) {
            // Caught here rather than by Bean Validation because the bound is on the *total* across
            // three lists, which a field constraint cannot express.
            throw new LlmUnavailableException(
                    "Interview questions came back outside the usable range", true);
        }
        return questions;
    }

    /**
     * Groups in enum declaration order with empty ones dropped, so the client never sorts and design
     * 21's "an empty group renders no overline" holds by construction.
     */
    private InterviewQuestions toQuestions(QuestionsReply reply) {
        List<QuestionGroup> groups = new ArrayList<>();
        addIfAny(groups, InterviewQuestionKind.TECHNICAL, reply.technical());
        addIfAny(groups, InterviewQuestionKind.BEHAVIOURAL, reply.behavioural());
        addIfAny(groups, InterviewQuestionKind.ROLE_SPECIFIC, reply.roleSpecific());
        return new InterviewQuestions(List.copyOf(groups));
    }

    private void addIfAny(List<QuestionGroup> groups, InterviewQuestionKind kind, List<String> questions) {
        if (questions == null) {
            return;
        }
        List<String> usable = questions.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(question -> !question.isEmpty())
                .toList();
        if (!usable.isEmpty()) {
            groups.add(new QuestionGroup(kind, usable));
        }
    }

    @Override
    protected String systemPrompt() {
        return """
                You write the questions a candidate is most likely to be asked when interviewing for
                a specific job, based only on that job's posting.

                Rules:
                - 5 to 8 questions in total, across the three groups. Never fewer than 5.
                - Aim for 7 or 8 when the posting is detailed; 5 only when it is thin.
                - Group them: technical, behavioural, role-specific. A group may be empty if the
                  posting gives you nothing for it — put each question in the group it belongs to
                  rather than spreading them evenly.
                - Each question is one sentence a real interviewer would say out loud, under 160
                  characters. No preamble, no numbering, no answers, no advice.
                - Base them only on the posting given. Never invent details about the company, the
                  team, the salary or the process.
                - Role-specific means particular to this posting's domain and responsibilities, not
                  generic questions that would suit any job.

                Reply with JSON only, exactly:
                {"technical":["..."],"behavioural":["..."],"roleSpecific":["..."]}
                """;
    }

    @Override
    protected Class<QuestionsReply> responseType() {
        return QuestionsReply.class;
    }

    @Override
    protected QuestionsReply fallback() {
        return UNAVAILABLE;
    }

    @Override
    protected String renderUserMessage(JobBrief brief) {
        if (brief == null || brief.title() == null || brief.title().isBlank()) {
            // A posting with no title has nothing to reason from, so spend no request on it.
            return "";
        }
        return "Job title: " + brief.title()
                + "\nSeniority: " + nameOrUnknown(brief.seniority())
                + "\nEmployment type: " + nameOrUnknown(brief.type())
                + "\nRequired skills: " + joined(brief.skills())
                + "\nDescription:\n" + (brief.description() == null ? "(none given)" : brief.description());
    }

    private String nameOrUnknown(Enum<?> value) {
        return value == null ? "not stated" : value.name();
    }

    private String joined(java.util.Set<String> values) {
        return values == null || values.isEmpty() ? "none listed" : String.join(", ", values);
    }

    /**
     * The model's reply. Per-list size bounds are the most a field constraint can express; the
     * 5-to-8 total is checked in {@link #generate}.
     */
    public record QuestionsReply(
            @Size(max = MAX_QUESTIONS) List<@Size(max = MAX_QUESTION_CHARACTERS) String> technical,
            @Size(max = MAX_QUESTIONS) List<@Size(max = MAX_QUESTION_CHARACTERS) String> behavioural,
            @Size(max = MAX_QUESTIONS) List<@Size(max = MAX_QUESTION_CHARACTERS) String> roleSpecific) {
    }
}
