package com.jbp.serviceimpl;

import com.jbp.service.ChatCompletionClient;
import com.jbp.service.EmbeddingClient;
import com.jbp.service.MatchScorer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.4 AC — <strong>the score is deterministic and no model produces it.</strong>
 *
 * <p>The determinism half is asserted in each scorer's own tests by scoring twice. This is the other
 * half, and it is structural rather than behavioural on purpose: a test that calls a scorer and observes
 * no model call only proves it did not happen on that path, whereas a scorer that cannot hold a client
 * cannot call one on any path.
 *
 * <p><strong>Written now because Story 13.5 is where this gets tempting.</strong> That story adds
 * {@code MatchExplainer}, which does call a model, lives in this package, and takes the already-computed
 * score as input. The moment an explanation needs "just one more signal", the cheap move is to reach for
 * the chat client from inside a scorer — and the resulting bug is a match score that changes between two
 * identical requests, which is close to impossible to reproduce from a bug report.
 *
 * <p>{@link EmbeddingClient} is banned too, not only the chat client: Story 13.2 stores vectors precisely
 * so that rendering a page of matches costs no provider quota and cannot be delayed by provider latency.
 */
class ScoringNeverCallsAModelTest {

    private static final List<Class<? extends MatchScorer>> SCORERS = List.of(
            RuleBasedMatchScorer.class, EmbeddingMatchScorer.class, HybridMatchScorer.class);

    @Test
    void noScorerCanEvenHoldAModelClient() {
        for (Class<? extends MatchScorer> scorer : SCORERS) {
            assertThat(fieldTypesOf(scorer))
                    .as("%s must not be able to reach a model", scorer.getSimpleName())
                    .doesNotContain(ChatCompletionClient.class, EmbeddingClient.class);
        }
    }

    @Test
    void noScorerAcceptsAModelClientThroughAConstructorEither() {
        for (Class<? extends MatchScorer> scorer : SCORERS) {
            List<Class<?>> parameterTypes = new ArrayList<>();
            for (var constructor : scorer.getDeclaredConstructors()) {
                parameterTypes.addAll(List.of(constructor.getParameterTypes()));
            }
            assertThat(parameterTypes)
                    .as("%s must not be constructible with a model client", scorer.getSimpleName())
                    .doesNotContain(ChatCompletionClient.class, EmbeddingClient.class);
        }
    }

    /** Declared fields of the class and every superclass — the shared skeleton is where a client would hide. */
    private List<Class<?>> fieldTypesOf(Class<?> type) {
        List<Class<?>> fieldTypes = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class;
             current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                fieldTypes.add(field.getType());
            }
        }
        return fieldTypes;
    }
}
