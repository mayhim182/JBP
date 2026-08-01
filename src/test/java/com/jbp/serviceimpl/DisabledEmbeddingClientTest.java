package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisabledEmbeddingClientTest {

    private final DisabledEmbeddingClient client = new DisabledEmbeddingClient();

    @Test
    void reportsTheAiLayerAsUnavailableSoCallersFallBack() {
        assertThatThrownBy(() -> client.embed("frontend engineer"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessage("AI features are disabled");
    }

    @Test
    void reportsTheAiLayerAsUnavailableForBatchesToo() {
        assertThatThrownBy(() -> client.embedAll(List.of("react developer")))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessage("AI features are disabled");
    }

    @Test
    void reportsUnavailableEvenForAnEmptyBatchRatherThanLookingLikeACompletedRun() {
        assertThatThrownBy(() -> client.embedAll(List.of()))
                .as("returning empty would let a backfill mistake AI-off for nothing-to-do")
                .isInstanceOf(LlmUnavailableException.class);
    }

    @Test
    void doesNotTreatAnAbsentProviderAsRetryable() {
        assertThatThrownBy(() -> client.embed("frontend engineer"))
                .isInstanceOf(LlmUnavailableException.class)
                .matches(failure -> !((LlmUnavailableException) failure).isRetryable(),
                        "a switched-off layer will not become available by trying again");
    }
}
