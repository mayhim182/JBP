package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingEmbeddingClientTest {

    @Test
    void returnsTheVectorUnchanged() {
        FakeEmbeddingClient provider = FakeEmbeddingClient.returning(1.0f, 0.0f);

        float[] vector = new LoggingEmbeddingClient(provider).embed("frontend engineer");

        assertThat(vector).containsExactly(1.0f, 0.0f);
        assertThat(provider.callCount()).isEqualTo(1);
    }

    @Test
    void returnsBatchVectorsUnchangedAndInOrder() {
        FakeEmbeddingClient provider = FakeEmbeddingClient.returning(0.0f, 1.0f);

        List<float[]> vectors = new LoggingEmbeddingClient(provider)
                .embedAll(List.of("react developer", "frontend engineer"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)).containsExactly(0.0f, 1.0f);
        assertThat(provider.callCount())
                .as("logging must not turn one batch into several provider calls")
                .isEqualTo(1);
    }

    @Test
    void rethrowsTheOriginalFailureSoCallersSeeTheRealCause() {
        LlmUnavailableException providerFailure =
                new LlmUnavailableException("embedding model exploded", true);
        LoggingEmbeddingClient client =
                new LoggingEmbeddingClient(FakeEmbeddingClient.failingWith(providerFailure));

        assertThatThrownBy(() -> client.embed("frontend engineer"))
                .isSameAs(providerFailure);
    }

    @Test
    void rethrowsTheOriginalFailureFromABatchToo() {
        LlmUnavailableException providerFailure =
                new LlmUnavailableException("embedding model exploded", true);
        LoggingEmbeddingClient client =
                new LoggingEmbeddingClient(FakeEmbeddingClient.failingWith(providerFailure));

        assertThatThrownBy(() -> client.embedAll(List.of("react developer")))
                .isSameAs(providerFailure);
    }
}
