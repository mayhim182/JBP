package com.jbp.config;

import com.jbp.service.ChatCompletionClient;
import com.jbp.serviceimpl.DisabledChatClient;
import com.jbp.serviceimpl.LoggingChatClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves the AI layer wires exactly one client in every configuration, and that switching AI
 * off leaves an application that still starts.
 */
class AiClientConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withUserConfiguration(AiClientConfig.class);

    @Test
    void wiresNoProviderAndStartsCleanlyWhenAiIsDisabled() {
        contextRunner.withPropertyValues("app.ai.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ChatCompletionClient.class);
            assertThat(context.getBean(ChatCompletionClient.class))
                    .isInstanceOf(DisabledChatClient.class);
        });
    }

    @Test
    void treatsAiAsDisabledWhenTheSwitchIsAbsentEntirely() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChatCompletionClient.class))
                    .isInstanceOf(DisabledChatClient.class);
        });
    }

    @Test
    void wiresTheDecoratedProviderChainWhenAiIsEnabled() {
        contextRunner.withPropertyValues(
                "app.ai.enabled=true",
                "app.ai.base-url=https://ai.test/v1",
                "app.ai.api-key=test-key",
                "app.ai.model=test-model").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ChatCompletionClient.class);
            assertThat(context.getBean(ChatCompletionClient.class))
                    .as("logging must be outermost so throttled calls are recorded too")
                    .isInstanceOf(LoggingChatClient.class);
        });
    }

    @Test
    void refusesToStartWhenAiIsEnabledWithoutAnApiKey() {
        contextRunner.withPropertyValues(
                "app.ai.enabled=true",
                "app.ai.base-url=https://ai.test/v1",
                "app.ai.model=test-model").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure())
                    .hasRootCauseMessage("app.ai.enabled is true but app.ai.api-key is not set. "
                            + "Set the key, or set app.ai.enabled=false to run without AI features.");
        });
    }
}
