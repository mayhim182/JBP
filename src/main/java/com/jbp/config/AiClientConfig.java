package com.jbp.config;

import com.jbp.service.ChatCompletionClient;
import com.jbp.service.EmbeddingClient;
import com.jbp.serviceimpl.DisabledChatClient;
import com.jbp.serviceimpl.DisabledEmbeddingClient;
import com.jbp.serviceimpl.GeminiChatClient;
import com.jbp.serviceimpl.GeminiEmbeddingClient;
import com.jbp.serviceimpl.LoggingChatClient;
import com.jbp.serviceimpl.LoggingEmbeddingClient;
import com.jbp.serviceimpl.RateLimitedChatClient;
import com.jbp.serviceimpl.RateLimitedEmbeddingClient;
import com.jbp.util.CallRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

import java.time.Clock;
import java.time.Duration;

/**
 * Builds the two transports the rest of the application injects: the {@link ChatCompletionClient}
 * every text feature goes through, and the {@link EmbeddingClient} semantic matching goes through.
 *
 * <p>Every {@code app.ai.*} key is read here and nowhere else, so the provider URL, key and
 * model names exist in exactly one place in the codebase. The clients themselves take plain
 * values, which keeps them free of Spring annotations and directly unit-testable.
 *
 * <p>Each chain is assembled outermost-first: logging wraps rate limiting wraps the provider.
 * Ordering it this way means a call rejected by the rate limiter is still timed and logged,
 * which is precisely the case worth seeing in the logs. Adding a behaviour later — caching,
 * metrics, a circuit breaker — means one more decorator here and no change anywhere else.
 *
 * <p>Exactly one implementation of each interface exists at any time, so callers never face an
 * ambiguous dependency. With AI switched off no provider is constructed, so no HTTP client, key or
 * model is needed and the application starts normally.
 */
@Configuration
public class AiClientConfig {

    /**
     * Which AI features are on, for the client to gate its UI before first paint.
     *
     * <p>Story 14.1's interview-prep section is <strong>absent</strong> when its capability is off —
     * no header, no placeholder, no "unavailable" line. That is only implementable if the answer is
     * knowable before the first render; deriving it from a failed fetch would show a header and then
     * remove it, and that flash is worse than either end state.
     *
     * <p>Each capability is ANDed with the master switch, so a capability can never report on while
     * AI as a whole is off.
     */
    @Bean
    public AiCapabilities aiCapabilities(
            @Value("${app.ai.enabled:false}") boolean aiEnabled,
            @Value("${app.ai.features.interview-prep:true}") boolean interviewPrep,
            @Value("${app.ai.features.match-explanation:true}") boolean matchExplanation,
            @Value("${app.ai.features.job-description:true}") boolean jobDescription,
            @Value("${app.ai.features.screening-answer-assist:true}") boolean screeningAnswerAssist,
            @Value("${app.ai.features.applicant-summary:true}") boolean applicantSummary) {

        if (!aiEnabled) {
            return AiCapabilities.none();
        }
        return new AiCapabilities(
                interviewPrep, matchExplanation, jobDescription, screeningAnswerAssist, applicantSummary);
    }

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
        return new LoggingChatClient(new RateLimitedChatClient(
                provider, new CallRateLimiter(rateLimitPerMinute, Duration.ofMinutes(1), Clock.systemUTC())));
    }

    @Bean
    @ConditionalOnProperty(name = "app.ai.enabled", havingValue = "false", matchIfMissing = true)
    public ChatCompletionClient disabledChatCompletionClient() {
        return new DisabledChatClient();
    }

    /**
     * The embedding transport, assembled in the same order and for the same reason as the chat
     * chain above: logging outermost so a throttled call is still recorded.
     *
     * <p>It gets its <strong>own</strong> {@link CallRateLimiter} rather than sharing the chat one,
     * because the free tier documents quota per model and chat and embeddings are different models —
     * one shared window would leave half of each allowance unused. If that turns out to be wrong and
     * the quota is per project, passing one limiter instance to both decorators is the entire fix,
     * which is why the limiter is a constructor argument rather than something either decorator
     * builds for itself.
     *
     * <p>Embedding size is configuration, not a constant, because it is the one number that changes
     * the storage footprint and the comparison cost together. 768 is the measured default; see the
     * note on {@code GeminiEmbeddingClient} for why the response is checked against it.
     */
    @Bean
    @ConditionalOnProperty(name = "app.ai.enabled", havingValue = "true")
    public EmbeddingClient geminiBackedEmbeddingClient(
            @Value("${app.ai.base-url}") String baseUrl,
            @Value("${app.ai.api-key:}") String apiKey,
            @Value("${app.ai.embedding-model:gemini-embedding-001}") String embeddingModel,
            @Value("${app.ai.embedding-dimensions:768}") int embeddingDimensions,
            @Value("${app.ai.timeout-millis:20000}") int timeoutMillis,
            @Value("${app.ai.embedding-rate-limit-per-minute:12}") int rateLimitPerMinute) {

        requireApiKey(apiKey);
        EmbeddingClient provider = new GeminiEmbeddingClient(
                buildRestTemplate(timeoutMillis), baseUrl, apiKey, embeddingModel, embeddingDimensions);
        return new LoggingEmbeddingClient(new RateLimitedEmbeddingClient(
                provider, new CallRateLimiter(rateLimitPerMinute, Duration.ofMinutes(1), Clock.systemUTC())));
    }

    @Bean
    @ConditionalOnProperty(name = "app.ai.enabled", havingValue = "false", matchIfMissing = true)
    public EmbeddingClient disabledEmbeddingClient() {
        return new DisabledEmbeddingClient();
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
     * Also unconditional, and for a sharper reason than the budget above: <em>reading</em> stored
     * vectors needs no provider at all. With AI switched off, existing embeddings are still comparable
     * and Story 13.3 can still use them — it is only refreshing them that requires a live client.
     */
    @Bean
    public EmbeddingSettings embeddingSettings(
            @Value("${app.ai.embedding-model:gemini-embedding-001}") String embeddingModel,
            @Value("${app.ai.embedding-dimensions:768}") int embeddingDimensions) {
        return new EmbeddingSettings(embeddingModel, embeddingDimensions);
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
