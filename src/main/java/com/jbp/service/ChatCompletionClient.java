package com.jbp.service;

import com.jbp.exception.LlmUnavailableException;

/**
 * Sends a prompt to a language model and returns its raw text reply.
 *
 * <p>Deliberately the smallest useful contract: every AI feature in the platform goes
 * through this one method, so HTTP, authentication, timeouts and retries are written once.
 * Implementations know about a provider; callers never do.
 *
 * <p>Swapping Gemini for Groq, OpenAI or a local Ollama instance means either configuration
 * alone (they share an OpenAI-compatible wire format) or one new implementation — no caller
 * changes either way.
 */
public interface ChatCompletionClient {

    /**
     * @param systemPrompt instructions describing the model's role and required output shape
     * @param userMessage  the content to act on
     * @return the model's reply text, never null
     * @throws LlmUnavailableException if the model could not be reached, timed out, was rate
     *                                limited, or the AI layer is disabled
     */
    String complete(String systemPrompt, String userMessage);
}
