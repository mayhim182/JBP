package com.jbp.serviceimpl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jbp.config.AiTaskBudget;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.ChatCompletionClient;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Runs the prompt → call → parse → validate → fallback pipeline that every AI feature needs, so
 * a new feature is only a system prompt, a response record and a fallback value.
 *
 * <p>{@code execute} is final on purpose. The pipeline's guarantee — that an AI failure returns
 * the fallback and never propagates — only holds if no subclass can reshape it. Subclasses
 * contribute the parts that genuinely differ and nothing else.
 *
 * <p>Model output is accepted whole or discarded whole. A reply that is malformed, carries fields
 * the response type does not declare, or fails Bean Validation is thrown away in favour of the
 * fallback; half-parsed output is never returned. That matters most for Epic 11's resume
 * autofill, where a partially understood reply would put wrong data on a candidate's profile.
 *
 * <p>Nothing here knows which provider is configured, and switching AI off changes no behaviour
 * beyond which branch runs: {@link DisabledChatClient} raises the same
 * {@link LlmUnavailableException} a real outage would, so the fallback path is exercised
 * identically either way.
 *
 * @param <I> the feature's input, rendered into the user message
 * @param <O> the response record parsed out of the model's reply
 */
public abstract class AbstractStructuredAiTask<I, O> {

    private static final Logger log = LoggerFactory.getLogger(AbstractStructuredAiTask.class);

    /**
     * Rough characters-per-token ratio for English prose. An exact count would need a
     * provider-specific tokeniser, which is a dependency this project does not carry; over-
     * estimating slightly is the safe direction, since the cost of trimming a little too much is
     * a shorter prompt rather than a rejected request.
     */
    private static final int ESTIMATED_CHARACTERS_PER_TOKEN = 4;

    private final ChatCompletionClient chatCompletionClient;
    private final ObjectMapper strictObjectMapper;
    private final Validator validator;
    private final AiTaskBudget budget;

    protected AbstractStructuredAiTask(ChatCompletionClient chatCompletionClient,
                                       ObjectMapper objectMapper,
                                       Validator validator,
                                       AiTaskBudget budget) {
        this.chatCompletionClient = chatCompletionClient;
        this.strictObjectMapper = strictCopyOf(objectMapper);
        this.validator = validator;
        this.budget = budget;
    }

    /**
     * Asks the model to perform this task, returning {@link #fallback()} if anything at all goes
     * wrong. Never throws.
     */
    public final O execute(I input) {
        try {
            String renderedInput = renderUserMessage(input);
            if (renderedInput == null || renderedInput.isBlank()) {
                // Nothing to reason about, so spend no request on it.
                log.debug("{} falling back, no input to send", taskName());
                return fallback();
            }
            String reply = chatCompletionClient.complete(systemPrompt(), truncateToBudget(renderedInput));
            return parseAndValidate(reply);
        } catch (LlmUnavailableException modelUnavailable) {
            log.debug("{} falling back, model unavailable: {}", taskName(), modelUnavailable.getMessage());
            return fallback();
        } catch (RuntimeException unexpectedFailure) {
            // Broad on purpose: an AI extra must never break the user's action. Logged at WARN
            // because, unlike an unavailable model, this means a defect worth fixing.
            log.warn("{} falling back after unexpected failure", taskName(), unexpectedFailure);
            return fallback();
        }
    }

    /**
     * Instructions describing the model's role and the exact JSON shape expected back.
     */
    protected abstract String systemPrompt();

    /**
     * The record the reply is parsed into. Annotate its components with Bean Validation
     * constraints to have implausible replies rejected rather than stored.
     *
     * <p>A task expecting several items should wrap them in a record holding a list rather than
     * returning a collection directly — a {@code Class} cannot express a generic element type,
     * and a named wrapper also gives the model a clearer shape to aim at.
     */
    protected abstract Class<O> responseType();

    /**
     * What this task returns whenever the model cannot be used or its answer is unusable.
     * Must be a value the feature can proceed with — this is the non-AI behaviour.
     */
    protected abstract O fallback();

    /**
     * Renders the input into the user message. The default suits tasks whose input is already
     * text; override when a task needs to assemble several fields into a prompt.
     */
    protected String renderUserMessage(I input) {
        return input == null ? "" : String.valueOf(input);
    }

    private O parseAndValidate(String reply) {
        O candidate;
        try {
            candidate = strictObjectMapper.readValue(extractJsonObject(reply), responseType());
        } catch (JsonProcessingException unusableReply) {
            // Length only, never content: replies carry candidate and job data.
            log.debug("{} discarding unusable reply of {} characters", taskName(), lengthOf(reply));
            return fallback();
        }
        Set<ConstraintViolation<O>> violations = validator.validate(candidate);
        if (!violations.isEmpty()) {
            log.debug("{} discarding reply failing validation on: {}", taskName(), propertyPathsOf(violations));
            return fallback();
        }
        return candidate;
    }

    /**
     * Isolates the JSON object within a reply. Models routinely wrap JSON in markdown fences or
     * add a sentence of commentary despite being told not to, and discarding an otherwise good
     * answer over that would waste a request. Returns an empty string when there is no object at
     * all, which the caller then treats as an unusable reply.
     */
    private String extractJsonObject(String reply) {
        if (reply == null) {
            return "";
        }
        int firstBrace = reply.indexOf('{');
        int lastBrace = reply.lastIndexOf('}');
        return firstBrace >= 0 && lastBrace > firstBrace ? reply.substring(firstBrace, lastBrace + 1) : "";
    }

    private String truncateToBudget(String userMessage) {
        int maxCharacters = budget.maxInputTokens() * ESTIMATED_CHARACTERS_PER_TOKEN;
        if (userMessage.length() <= maxCharacters) {
            return userMessage;
        }
        log.debug("{} truncating input from {} to {} characters", taskName(), userMessage.length(), maxCharacters);
        return userMessage.substring(0, maxCharacters);
    }

    /**
     * A private copy with unknown properties rejected, so a reply carrying fields the response
     * type does not declare is treated as the wrong shape rather than silently accepted. Copied
     * rather than reconfigured because the {@link ObjectMapper} bean is shared with the web layer,
     * where rejecting unknown fields would break request handling.
     */
    private ObjectMapper strictCopyOf(ObjectMapper objectMapper) {
        return objectMapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private String propertyPathsOf(Set<ConstraintViolation<O>> violations) {
        return violations.stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.joining(", "));
    }

    private int lengthOf(String reply) {
        return reply == null ? 0 : reply.length();
    }

    private String taskName() {
        return getClass().getSimpleName();
    }
}
