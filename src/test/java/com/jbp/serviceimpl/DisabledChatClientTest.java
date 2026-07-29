package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DisabledChatClientTest {

    private final DisabledChatClient client = new DisabledChatClient();

    @Test
    void reportsUnavailableWhenAiIsDisabled() {
        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessage("AI features are disabled");
    }

    @Test
    void doesNotAskCallersToRetryBecauseRetryingCannotHelp() {
        LlmUnavailableException failure = catchLlmFailure();

        assertThat(failure.isRetryable()).isFalse();
    }

    private LlmUnavailableException catchLlmFailure() {
        try {
            client.complete("system", "user");
            throw new AssertionError("Expected LlmUnavailableException");
        } catch (LlmUnavailableException expected) {
            return expected;
        }
    }
}
