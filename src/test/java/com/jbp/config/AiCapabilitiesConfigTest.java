package com.jbp.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 14.1 — the flags the client gates its UI on, before first paint.
 *
 * <p>The property is what makes design 21b's state C an <em>absence</em> rather than a disabled
 * control: the answer has to be knowable without asking the model anything.
 */
class AiCapabilitiesConfigTest {

    /*
     * The provider settings are here because this loads the whole AiClientConfig: with
     * app.ai.enabled=true the real chat client becomes eligible and demands a URL, a model and a key.
     * They are irrelevant to what these tests assert — no call is ever made — but without them the
     * context fails to start for exactly the cases that matter.
     */
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withPropertyValues(
                    "app.ai.base-url=http://localhost/unused",
                    "app.ai.model=test-model",
                    "app.ai.api-key=test-key")
            .withBean(AiClientConfig.class);

    @Test
    void reportsEverythingOffWhenAiIsOffEntirely() {
        contextRunner.withPropertyValues("app.ai.enabled=false").run(context ->
                assertThat(context.getBean(AiCapabilities.class))
                        .as("a capability must never claim to be on while AI as a whole is off")
                        .isEqualTo(AiCapabilities.none()));
    }

    @Test
    void ignoresAnEnabledCapabilityWhenTheMasterSwitchIsOff() {
        contextRunner.withPropertyValues(
                "app.ai.enabled=false",
                "app.ai.features.interview-prep=true").run(context ->
                assertThat(context.getBean(AiCapabilities.class).interviewPrep())
                        .as("the master switch refines downward, never upward")
                        .isFalse());
    }

    @Test
    void defaultsEveryCapabilityOnSoTheFlagsAreAdditive() {
        contextRunner.withPropertyValues("app.ai.enabled=true").run(context -> {
            AiCapabilities capabilities = context.getBean(AiCapabilities.class);
            assertThat(capabilities.interviewPrep()).isTrue();
            assertThat(capabilities.matchExplanation())
                    .as("unset must behave exactly as before these flags existed")
                    .isTrue();
            assertThat(capabilities.jobDescription()).isTrue();
            assertThat(capabilities.screeningAnswerAssist()).isTrue();
        });
    }

    @Test
    void switchesOffOneCapabilityWithoutTouchingTheOthers() {
        contextRunner.withPropertyValues(
                "app.ai.enabled=true",
                "app.ai.features.interview-prep=false").run(context -> {
            AiCapabilities capabilities = context.getBean(AiCapabilities.class);
            assertThat(capabilities.interviewPrep()).isFalse();
            assertThat(capabilities.matchExplanation())
                    .as("\"the prep questions were poor\" must not take the rest of AI down with it")
                    .isTrue();
            assertThat(capabilities.jobDescription()).isTrue();
            assertThat(capabilities.screeningAnswerAssist()).isTrue();
        });
    }

    /**
     * Story 14.2's flag decides whether the apply dialog draws an action row at all, so it has to be
     * answerable before first paint for the same reason interview prep's is.
     */
    @Test
    void switchesOffScreeningAnswerAssistOnItsOwn() {
        contextRunner.withPropertyValues(
                "app.ai.enabled=true",
                "app.ai.features.screening-answer-assist=false").run(context -> {
            AiCapabilities capabilities = context.getBean(AiCapabilities.class);
            assertThat(capabilities.screeningAnswerAssist()).isFalse();
            assertThat(capabilities.interviewPrep()).isTrue();
            assertThat(capabilities.jobDescription()).isTrue();
        });
    }
}
