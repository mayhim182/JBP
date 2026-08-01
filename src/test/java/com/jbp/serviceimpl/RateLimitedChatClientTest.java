package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.util.CallRateLimiter;
import com.jbp.util.ControllableClock;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitedChatClientTest {

    private static final int LIMIT = 3;

    private final ControllableClock clock = new ControllableClock();

    @Test
    void allowsCallsUpToTheConfiguredLimit() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith("reply");
        RateLimitedChatClient client = clientLimitedTo(provider);

        for (int call = 0; call < LIMIT; call++) {
            assertThat(client.complete("system", "user")).isEqualTo("reply");
        }

        assertThat(provider.callCount()).isEqualTo(LIMIT);
    }

    @Test
    void rejectsTheCallThatWouldExceedTheLimitWithoutContactingTheProvider() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith("reply");
        RateLimitedChatClient client = clientLimitedTo(provider);
        for (int call = 0; call < LIMIT; call++) {
            client.complete("system", "user");
        }

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("limit of 3 per minute");

        assertThat(provider.callCount())
                .as("a throttled call must not consume provider quota")
                .isEqualTo(LIMIT);
    }

    @Test
    void allowsCallsAgainOnceTheOldestOnesFallOutsideTheWindow() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith("reply");
        RateLimitedChatClient client = clientLimitedTo(provider);
        for (int call = 0; call < LIMIT; call++) {
            client.complete("system", "user");
        }

        clock.advanceMillis(60_000);

        assertThat(client.complete("system", "user")).isEqualTo("reply");
        assertThat(provider.callCount()).isEqualTo(LIMIT + 1);
    }

    @Test
    void keepsThrottlingWhileTheWindowHasNotFullyElapsed() {
        RateLimitedChatClient client = clientLimitedTo(FakeChatCompletionClient.replyingWith("reply"));
        for (int call = 0; call < LIMIT; call++) {
            client.complete("system", "user");
        }

        clock.advanceMillis(59_999);

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(LlmUnavailableException.class);
    }

    private RateLimitedChatClient clientLimitedTo(FakeChatCompletionClient provider) {
        return new RateLimitedChatClient(provider, new CallRateLimiter(LIMIT, clock));
    }
}
