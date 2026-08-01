package com.jbp.config;

import com.jbp.service.EmbeddingStore;
import com.jbp.service.MatchScorer;
import com.jbp.serviceimpl.EmbeddingMatchScorer;
import com.jbp.serviceimpl.HybridMatchScorer;
import com.jbp.serviceimpl.RuleBasedMatchScorer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.PropertyPlaceholderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** Story 13.3 AC — the scorer is selected by {@code app.match.scorer}, defaulting to rule. */
class MatchScorerConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PropertyPlaceholderAutoConfiguration.class))
            .withBean(EmbeddingStore.class, () -> Mockito.mock(EmbeddingStore.class))
            .withUserConfiguration(MatchScorerConfig.class);

    @Test
    void defaultsToTheRuleScorerWhenNothingIsConfigured() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(MatchScorer.class);
            assertThat(context.getBean(MatchScorer.class))
                    .as("semantic scoring needs stored vectors, so rules are the safe default")
                    .isInstanceOf(RuleBasedMatchScorer.class);
        });
    }

    @Test
    void selectsTheRuleScorerExplicitly() {
        contextRunner.withPropertyValues("app.match.scorer=rule").run(context ->
                assertThat(context.getBean(MatchScorer.class)).isInstanceOf(RuleBasedMatchScorer.class));
    }

    @Test
    void selectsTheEmbeddingScorer() {
        contextRunner.withPropertyValues("app.match.scorer=embedding").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MatchScorer.class)).isInstanceOf(EmbeddingMatchScorer.class);
        });
    }

    @Test
    void acceptsTheModeCaseInsensitively() {
        contextRunner.withPropertyValues("app.match.scorer=EMBEDDING").run(context ->
                assertThat(context.getBean(MatchScorer.class)).isInstanceOf(EmbeddingMatchScorer.class));
    }

    @Test
    void selectsTheHybridScorer() {
        contextRunner.withPropertyValues("app.match.scorer=hybrid").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(MatchScorer.class)).isInstanceOf(HybridMatchScorer.class);
        });
    }

    @Test
    void startsHybridWithoutBeingToldAWeight() {
        contextRunner.withPropertyValues("app.match.scorer=hybrid").run(context ->
                assertThat(context)
                        .as("the 70/30 default comes from design 20, so hybrid must not need configuring")
                        .hasNotFailed());
    }

    @Test
    void acceptsAConfiguredHybridWeight() {
        contextRunner.withPropertyValues("app.match.scorer=hybrid", "app.match.rule-weight=50")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesToStartOnAHybridWeightThatWouldMakeItNotHybrid() {
        contextRunner.withPropertyValues("app.match.scorer=hybrid", "app.match.rule-weight=100")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause()
                            .hasMessageContaining("app.match.rule-weight must be between 1 and 99");
                });
    }

    @Test
    void ignoresTheHybridWeightInTheOtherModes() {
        contextRunner.withPropertyValues("app.match.scorer=rule", "app.match.rule-weight=100")
                .run(context -> assertThat(context)
                        .as("a leftover property for an unused mode must not stop the application")
                        .hasNotFailed());
    }

    @Test
    void refusesToStartOnAnUnrecognisedMode() {
        contextRunner.withPropertyValues("app.match.scorer=magic").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .hasMessageContaining("app.match.scorer=magic is not recognised");
        });
    }

    @Test
    void exposesTheCalibrationBandAsConfiguration() {
        contextRunner.withPropertyValues(
                "app.match.semantic-floor=0.60",
                "app.match.semantic-ceiling=0.95").run(context -> {
            assertThat(context.getBean(com.jbp.util.SemanticScoreCalibration.class).floor()).isEqualTo(0.60);
            assertThat(context.getBean(com.jbp.util.SemanticScoreCalibration.class).ceiling()).isEqualTo(0.95);
        });
    }

    @Test
    void refusesToStartOnAnInvertedCalibrationBand() {
        contextRunner.withPropertyValues(
                "app.match.semantic-floor=0.90",
                "app.match.semantic-ceiling=0.55").run(context ->
                assertThat(context).hasFailed());
    }
}
