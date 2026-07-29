package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoggingChatClientTest {

    @Test
    void returnsTheReplyUnchanged() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith("model reply");

        String reply = new LoggingChatClient(provider).complete("system", "user");

        assertThat(reply).isEqualTo("model reply");
        assertThat(provider.callCount()).isEqualTo(1);
    }

    @Test
    void rethrowsTheOriginalFailureSoCallersSeeTheRealCause() {
        LlmUnavailableException providerFailure =
                new LlmUnavailableException("model exploded", true);
        LoggingChatClient client =
                new LoggingChatClient(FakeChatCompletionClient.failingWith(providerFailure));

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isSameAs(providerFailure);
    }

    @Test
    void toleratesNullPromptsWhileMeasuringSize() {
        LoggingChatClient client =
                new LoggingChatClient(FakeChatCompletionClient.replyingWith(null));

        assertThat(client.complete(null, null)).isNull();
    }
}
