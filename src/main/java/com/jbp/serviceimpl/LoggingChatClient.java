package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.ChatCompletionClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * Records how long each model call took and how much text it moved, without changing the
 * result. Wraps any {@link ChatCompletionClient}, so timing is written once rather than in
 * every AI feature.
 *
 * <p>Prompt and reply content is never logged, only its size — prompts carry candidate and job
 * data, and application logs are not a safe home for it. Provider token counts are logged by
 * {@link GeminiChatClient}, the only layer that can see them, because the
 * {@link ChatCompletionClient} contract returns reply text alone.
 *
 * <p>Failures are logged at WARN with the elapsed time, because a slow failure and an instant
 * one point at different causes: a timeout against the provider versus a locally throttled call.
 */
public class LoggingChatClient implements ChatCompletionClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingChatClient.class);

    private final ChatCompletionClient delegate;

    public LoggingChatClient(ChatCompletionClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public String complete(String systemPrompt, String userMessage) {
        long startedAt = System.nanoTime();
        try {
            String reply = delegate.complete(systemPrompt, userMessage);
            log.debug("Model call succeeded in {} ms, sent {} characters, received {} characters",
                    elapsedMillisSince(startedAt),
                    characterCount(systemPrompt) + characterCount(userMessage),
                    characterCount(reply));
            return reply;
        } catch (LlmUnavailableException failure) {
            log.warn("Model call failed after {} ms: {}",
                    elapsedMillisSince(startedAt), failure.getMessage());
            throw failure;
        }
    }

    private long elapsedMillisSince(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private int characterCount(String text) {
        return text == null ? 0 : text.length();
    }
}
