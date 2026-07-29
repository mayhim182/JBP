package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RateLimitedChatClientTest {

    private static final int LIMIT = 3;

    private final ControllableClock clock = new ControllableClock();

    @Test
    void allowsCallsUpToTheConfiguredLimit() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith("reply");
        RateLimitedChatClient client = new RateLimitedChatClient(provider, LIMIT, clock);

        for (int call = 0; call < LIMIT; call++) {
            assertThat(client.complete("system", "user")).isEqualTo("reply");
        }

        assertThat(provider.callCount()).isEqualTo(LIMIT);
    }

    @Test
    void rejectsTheCallThatWouldExceedTheLimitWithoutContactingTheProvider() {
        FakeChatCompletionClient provider = FakeChatCompletionClient.replyingWith("reply");
        RateLimitedChatClient client = new RateLimitedChatClient(provider, LIMIT, clock);
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
        RateLimitedChatClient client = new RateLimitedChatClient(provider, LIMIT, clock);
        for (int call = 0; call < LIMIT; call++) {
            client.complete("system", "user");
        }

        clock.advanceMillis(60_000);

        assertThat(client.complete("system", "user")).isEqualTo("reply");
        assertThat(provider.callCount()).isEqualTo(LIMIT + 1);
    }

    @Test
    void keepsThrottlingWhileTheWindowHasNotFullyElapsed() {
        RateLimitedChatClient client = new RateLimitedChatClient(
                FakeChatCompletionClient.replyingWith("reply"), LIMIT, clock);
        for (int call = 0; call < LIMIT; call++) {
            client.complete("system", "user");
        }

        clock.advanceMillis(59_999);

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(LlmUnavailableException.class);
    }

    /**
     * Lets a test move time forward deliberately, so window behaviour is verified without the
     * suite pausing for a real minute.
     */
    private static final class ControllableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
