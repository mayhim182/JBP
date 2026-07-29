package com.jbp.config;

import com.jbp.service.ChatCompletionClient;
import com.jbp.serviceimpl.DisabledChatClient;
import com.jbp.serviceimpl.GeminiChatClient;
import com.jbp.serviceimpl.LoggingChatClient;
import com.jbp.serviceimpl.RateLimitedChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;

/**
 * Builds the single {@link ChatCompletionClient} the rest of the application injects.
 *
 * <p>Every {@code app.ai.*} key is read here and nowhere else, so the provider URL, key and
 * model name exist in exactly one place in the codebase. The clients themselves take plain
 * values, which keeps them free of Spring annotations and directly unit-testable.
 *
 * <p>The chain is assembled outermost-first: logging wraps rate limiting wraps the provider.
 * Ordering it this way means a call rejected by the rate limiter is still timed and logged,
 * which is precisely the case worth seeing in the logs. Adding a behaviour later — caching,
 * metrics, a circuit breaker — means one more decorator here and no change anywhere else.
 *
 * <p>Exactly one of the two beans below exists at any time, so callers never face an ambiguous
 * dependency. With AI switched off the provider is never constructed, so no HTTP client, key or
 * model is needed and the application starts normally.
 */
@Configuration
public class AiClientConfig {

    @Bean
    @ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
    public ChatCompletionClient geminiBackedChatCompletionClient(
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.model}") String model,
            @Value("${app.ai.timeout-millis:20000}") int timeoutMillis,
            @Value("${app.ai.rate-limit-per-minute:12}") int rateLimitPerMinute) {

        requireApiKey(apiKey);
        ChatCompletionClient provider = new GeminiChatClient(
                buildRestTemplate(timeoutMillis), baseUrl, apiKey, model);
        return new LoggingChatClient(
                new RateLimitedChatClient(provider, rateLimitPerMinute, Clock.systemUTC()));
    }

    @Bean
    @ConditionalOnProperty(name = "app.ai.enabled", havingValue = "false", matchIfMissing = true)
    public ChatCompletionClient disabledChatCompletionClient() {
        return new DisabledChatClient();
    }

    /**
     * Deliberately unconditional: AI tasks are constructed whether or not a provider is wired,
     * because with AI off they still run and still return their fallback.
     */
    @Bean
    public AiTaskBudget aiTaskBudget(@Value("${app.ai.max-input-tokens:3000}") int maxInputTokens) {
        return new AiTaskBudget(maxInputTokens);
    }

    /**
     * A dedicated {@link RestTemplate} rather than a shared one, so the AI timeout cannot be
     * changed by, or leak into, any other outbound call. Both timeouts are set: a connect
     * timeout alone would let an accepted-but-silent connection hang a request thread.
     */
    private RestTemplate buildRestTemplate(int timeoutMillis) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(timeoutMillis);
        requestFactory.setReadTimeout(timeoutMillis);
        return new RestTemplate(requestFactory);
    }

    /**
     * Fails startup when AI is switched on without a key, rather than letting every feature
     * silently fall back after a rejected call. The message says exactly what to fix.
     */
    private void requireApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "app.ai.enabled is true but app.ai.api-key is not set. "
                            + "Set the key, or set app.ai.enabled=false to run without AI features.");
        }
    }
}
