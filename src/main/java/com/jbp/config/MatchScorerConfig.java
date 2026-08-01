package com.jbp.config;

import com.jbp.model.ScorerMode;
import com.jbp.service.EmbeddingStore;
import com.jbp.service.MatchScorer;
import com.jbp.serviceimpl.EmbeddingMatchScorer;
import com.jbp.serviceimpl.HybridMatchScorer;
import com.jbp.serviceimpl.RuleBasedMatchScorer;
import com.jbp.util.SemanticScoreCalibration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Locale;

/**
 * Builds the single {@link MatchScorer} the application injects, chosen by {@code app.match.scorer}.
 *
 * <p>One bean rather than several conditional ones, so callers can never face an ambiguous dependency and
 * the selection lives in one readable switch. {@code RuleBasedMatchScorer} stopped being a
 * {@code @Component} for the same reason: it is now the rule scorer, the embedding scorer's fallback and
 * the hybrid scorer's factor source, and all three should be a deliberate choice made here.
 *
 * <p>Every {@code app.match.*} key is read in this class and nowhere else, matching how
 * {@code AiClientConfig} owns {@code app.ai.*}.
 */
@Slf4j
@Configuration
public class MatchScorerConfig {

    /**
     * Defaults mirror {@code application.properties}, which stays authoritative. Spring needs a literal
     * here for the case where the property is absent entirely, so the numbers appear twice —
     * unavoidable, and the harness that derives them names both places.
     */
    @Bean
    public SemanticScoreCalibration semanticScoreCalibration(
            @Value("${app.match.semantic-floor:0.580}") double semanticFloor,
            @Value("${app.match.semantic-ceiling:0.868}") double semanticCeiling) {
        return new SemanticScoreCalibration(semanticFloor, semanticCeiling);
    }

    /**
     * @param configuredMode {@code rule} (default), {@code embedding}, or {@code hybrid}
     * @param ruleWeight     the rule dimensions' share of a hybrid score; meaning gets the remainder.
     *                       Ignored by the other two modes, and validated by
     *                       {@link HybridMatchScorer} rather than here so an unused leftover property
     *                       cannot stop an application that never reads it.
     * @throws IllegalStateException on an unknown value. <strong>Failing startup is the point.</strong>
     *                              Quietly falling back to rules would leave an operator believing
     *                              semantic matching was live while every score came from keywords — and
     *                              the symptom would be "the AI matching isn't very good" rather than
     *                              "it is switched off".
     */
    @Bean
    public MatchScorer matchScorer(
            @Value("${app.match.scorer:rule}") String configuredMode,
            @Value("${app.match.rule-weight:70}") int ruleWeight,
            EmbeddingStore embeddingStore,
            SemanticScoreCalibration calibration) {

        RuleBasedMatchScorer ruleBased = new RuleBasedMatchScorer();
        ScorerMode mode = parse(configuredMode);
        log.info("Match scoring mode: {}", mode);

        return switch (mode) {
            case RULE -> ruleBased;
            case EMBEDDING -> new EmbeddingMatchScorer(embeddingStore, ruleBased, calibration);
            case HYBRID -> {
                log.info("Hybrid weighting: {} rules / {} meaning", ruleWeight, 100 - ruleWeight);
                yield new HybridMatchScorer(embeddingStore, ruleBased, calibration, ruleWeight);
            }
        };
    }

    private ScorerMode parse(String configuredMode) {
        try {
            return ScorerMode.valueOf(configuredMode.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknownMode) {
            // Deliberately not chained: "No enum constant ScorerMode.MAGIC" says nothing this message
            // does not, and Spring prints the deepest cause last — so chaining would make that the final
            // line an operator reads instead of the one naming the valid values.
            throw new IllegalStateException("app.match.scorer=" + configuredMode
                    + " is not recognised. Use rule, embedding or hybrid.");
        }
    }
}
