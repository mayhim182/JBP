package com.jbp.serviceimpl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.ChatCompletionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;

/**
 * Talks to Google Gemini through its OpenAI-compatible chat-completions endpoint.
 *
 * <p>The only class in the platform aware that a specific provider exists. Everything above it
 * depends on {@link ChatCompletionClient}, so moving to Groq, OpenAI or a local Ollama server
 * needs no change here at all — those speak the same wire format, so only
 * {@code app.ai.base-url}, {@code app.ai.model} and {@code app.ai.api-key} change. A provider
 * with a different wire format means one new {@code ChatCompletionClient} implementation
 * beside this one.
 *
 * <p>Timeouts are enforced by the injected {@link RestTemplate}, configured in
 * {@code AiClientConfig}. A request that fails transiently — a timeout or a 5xx — is retried
 * exactly once. A 4xx is never retried: a malformed prompt or an invalid key will fail again,
 * and on the Gemini free tier a wasted call costs quota. Provider quota rejections (429) are
 * likewise not retried; {@link RateLimitedChatClient} is what keeps us under the limit.
 */
public class GeminiChatClient implements ChatCompletionClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiChatClient.class);

    private static final String CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String SYSTEM_ROLE = "system";
    private static final String USER_ROLE = "user";

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public GeminiChatClient(RestTemplate restTemplate, String baseUrl, String apiKey, String model) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        try {
            return sendRequest(systemPrompt, userMessage);
        } catch (LlmUnavailableException firstAttemptFailure) {
            if (!firstAttemptFailure.isRetryable()) {
                throw firstAttemptFailure;
            }
            log.warn("Model call failed transiently, retrying once: {}", firstAttemptFailure.getMessage());
            return sendRequest(systemPrompt, userMessage);
        }
    }

    private String sendRequest(String systemPrompt, String userMessage) {
        HttpEntity<ChatRequest> request = new HttpEntity<>(
                buildRequestBody(systemPrompt, userMessage),
                buildHeaders());
        try {
            ChatResponse response = restTemplate.postForObject(
                    baseUrl + CHAT_COMPLETIONS_PATH, request, ChatResponse.class);
            return extractReplyText(response);
        } catch (HttpClientErrorException rejectedByProvider) {
            throw new LlmUnavailableException(
                    "Model rejected the request with status " + rejectedByProvider.getStatusCode(),
                    false, rejectedByProvider);
        } catch (HttpServerErrorException providerFault) {
            throw new LlmUnavailableException(
                    "Model returned server error " + providerFault.getStatusCode(),
                    true, providerFault);
        } catch (ResourceAccessException timeoutOrNetworkFailure) {
            throw new LlmUnavailableException(
                    "Model did not respond in time", true, timeoutOrNetworkFailure);
        } catch (RestClientException unreadableResponse) {
            throw new LlmUnavailableException(
                    "Model response could not be read", false, unreadableResponse);
        }
    }

    private ChatRequest buildRequestBody(String systemPrompt, String userMessage) {
        return new ChatRequest(model, List.of(
                new ChatMessage(SYSTEM_ROLE, systemPrompt),
                new ChatMessage(USER_ROLE, userMessage)));
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        return headers;
    }

    /**
     * Pulls the reply text out of the provider envelope, logging the token usage the provider
     * reported. Token counts are only visible here — the {@link ChatCompletionClient} contract
     * returns text alone — so this is where they are recorded.
     */
    private String extractReplyText(ChatResponse response) {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new LlmUnavailableException("Model returned no completion", false);
        }
        ChatMessage reply = response.choices().get(0).message();
        if (reply == null || reply.content() == null) {
            throw new LlmUnavailableException("Model returned an empty completion", false);
        }
        logTokenUsage(response.usage());
        return reply.content();
    }

    private void logTokenUsage(TokenUsage usage) {
        if (usage != null && log.isDebugEnabled()) {
            log.debug("Model {} used {} prompt tokens and {} completion tokens",
                    model, usage.promptTokens(), usage.completionTokens());
        }
    }

    /*
     * Provider wire format, kept nested so no other class can depend on its shape.
     * Unknown fields are ignored so a provider adding response fields cannot break parsing.
     */

    record ChatRequest(String model, List<ChatMessage> messages) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatMessage(String role, String content) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(List<ChatChoice> choices, TokenUsage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatChoice(ChatMessage message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenUsage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("completion_tokens") Integer completionTokens) {
    }
}
