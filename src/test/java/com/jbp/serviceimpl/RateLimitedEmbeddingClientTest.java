package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.util.CallRateLimiter;
import com.jbp.util.ControllableClock;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitedEmbeddingClientTest {

    private static final int LIMIT = 3;

    private final ControllableClock clock = new ControllableClock();

    @Test
    void allowsCallsUpToTheConfiguredLimit() {
        FakeEmbeddingClient provider = FakeEmbeddingClient.returning(1.0f, 0.0f);
        RateLimitedEmbeddingClient client = clientLimitedTo(provider);

        for (int call = 0; call < LIMIT; call++) {
            assertThat(client.embed("frontend engineer")).hasSize(2);
        }

        assertThat(provider.callCount()).isEqualTo(LIMIT);
    }

    @Test
    void rejectsTheCallThatWouldExceedTheLimitWithoutContactingTheProvider() {
        FakeEmbeddingClient provider = FakeEmbeddingClient.returning(1.0f, 0.0f);
        RateLimitedEmbeddingClient client = clientLimitedTo(provider);
        for (int call = 0; call < LIMIT; call++) {
            client.embed("frontend engineer");
        }

        assertThatThrownBy(() -> client.embed("frontend engineer"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("Embedding call limit of 3 per minute");

        assertThat(provider.callCount())
                .as("a throttled call must not consume provider quota")
                .isEqualTo(LIMIT);
    }

    @Test
    void countsOneBatchAsOneCallWhichIsWhatMakesTheBackfillFitTheFreeTier() {
        FakeEmbeddingClient provider = FakeEmbeddingClient.returning(1.0f, 0.0f);
        RateLimitedEmbeddingClient client = clientLimitedTo(provider);

        List<String> tenTexts = List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j");
        client.embedAll(tenTexts);
        client.embedAll(tenTexts);
        client.embedAll(tenTexts);

        assertThat(provider.callCount())
                .as("thirty texts in three batches must cost three slots, not thirty")
                .isEqualTo(3);
        assertThatThrownBy(() -> client.embedAll(tenTexts))
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void allowsCallsAgainOnceTheOldestOnesFallOutsideTheWindow() {
        FakeEmbeddingClient provider = FakeEmbeddingClient.returning(1.0f, 0.0f);
        RateLimitedEmbeddingClient client = clientLimitedTo(provider);
        for (int call = 0; call < LIMIT; call++) {
            client.embed("frontend engineer");
        }

        clock.advanceMillis(60_000);

        assertThat(client.embed("frontend engineer")).hasSize(2);
        assertThat(provider.callCount()).isEqualTo(LIMIT + 1);
    }

    private RateLimitedEmbeddingClient clientLimitedTo(FakeEmbeddingClient provider) {
        return new RateLimitedEmbeddingClient(provider, new CallRateLimiter(LIMIT, clock));
    }
}
