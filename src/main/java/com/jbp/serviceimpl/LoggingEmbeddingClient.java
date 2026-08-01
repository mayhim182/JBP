package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Records how long each embedding call took and how much text it moved, without changing the
 * result. The embedding twin of {@link LoggingChatClient}.
 *
 * <p>Neither the text nor the vector is ever logged — only sizes. The text is candidate and job
 * content, and application logs are not a safe home for it. The vector is that same content in
 * another form: an embedding is invertible enough to leak what it was made from, so logging vector
 * components would defeat the point of not logging the text.
 *
 * <p>Batch size is logged because it is the number that explains throughput. A backfill running one
 * text per call rather than batching is a rate-limit problem that shows up here first, as a long
 * run of size-1 calls.
 */
public class LoggingEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(LoggingEmbeddingClient.class);

    private final EmbeddingClient delegate;

    public LoggingEmbeddingClient(EmbeddingClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public float[] embed(String text) {
        long startedAt = System.nanoTime();
        try {
            float[] vector = delegate.embed(text);
            log.debug("Embedding call succeeded in {} ms, sent 1 text of {} characters, "
                            + "received {} dimensions",
                    elapsedMillisSince(startedAt), characterCount(text), vector.length);
            return vector;
        } catch (LlmUnavailableException failure) {
            log.warn("Embedding call failed after {} ms: {}",
                    elapsedMillisSince(startedAt), failure.getMessage());
            throw failure;
        }
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        long startedAt = System.nanoTime();
        try {
            List<float[]> vectors = delegate.embedAll(texts);
            log.debug("Embedding call succeeded in {} ms, sent {} texts of {} characters, "
                            + "received {} vectors",
                    elapsedMillisSince(startedAt), textCount(texts), totalCharacterCount(texts),
                    vectors.size());
            return vectors;
        } catch (LlmUnavailableException failure) {
            log.warn("Embedding call for {} texts failed after {} ms: {}",
                    textCount(texts), elapsedMillisSince(startedAt), failure.getMessage());
            throw failure;
        }
    }

    private long elapsedMillisSince(long startedAt) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    }

    private int textCount(List<String> texts) {
        return texts == null ? 0 : texts.size();
    }

    private int totalCharacterCount(List<String> texts) {
        if (texts == null) {
            return 0;
        }
        return texts.stream().mapToInt(this::characterCount).sum();
    }

    private int characterCount(String text) {
        return text == null ? 0 : text.length();
    }
}
