package com.jbp.serviceimpl;

import com.jbp.service.ChatCompletionClient;
import com.jbp.service.EmbeddingClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Sends one real prompt and one real embedding request to the configured provider, proving the API
 * key, base URL and model names actually work together — and that the whole Java path handles a
 * genuine response, not just the canned ones in {@link GeminiChatClientTest} and
 * {@link GeminiEmbeddingClientTest}.
 *
 * <p>Skipped unless {@code JBP_AI_LIVE_TEST=true}, because the normal build must stay offline:
 * a suite that calls a paid-quota API is slow, fails when the network does, and spends free-tier
 * requests on every run. Opting in explicitly keeps that cost deliberate.
 *
 * <p>Deliberately injects the bean rather than constructing a client, so a pass means the real
 * {@code AiClientConfig} wiring and the real {@code app.ai.*} values are correct — which is the
 * part curl cannot check.
 *
 * <p>The provider settings are declared here rather than in {@code src/test/resources}, because
 * that file is loaded by every test and the offline suite must not carry a provider URL at all.
 * The key is read from {@code GEMINI_API_KEY} so it never reaches a tracked file; with the
 * variable unset this test fails loudly on the missing key, which is the correct answer once
 * you have explicitly opted in to a live call.
 */
@SpringBootTest(properties = {
        "app.ai.enabled=true",
        "app.ai.base-url=https://generativelanguage.googleapis.com/v1beta/openai",
        // Mirrors app.ai.model in src/main/resources/application.properties — keep the two in step,
        // since a model that stops serving makes this test hang rather than fail quickly.
        "app.ai.model=gemini-3.1-flash-lite",
        // Likewise mirrors app.ai.embedding-* in src/main/resources/application.properties.
        "app.ai.embedding-model=gemini-embedding-001",
        "app.ai.embedding-dimensions=768",
        "app.ai.api-key=${GEMINI_API_KEY:}"
})
@EnabledIfEnvironmentVariable(named = "JBP_AI_LIVE_TEST", matches = "true")
class GeminiLiveSmokeTest {

    private static final int EXPECTED_DIMENSIONS = 768;

    @Autowired
    private ChatCompletionClient chatCompletionClient;

    @Autowired
    private EmbeddingClient embeddingClient;

    @Test
    void reachesTheConfiguredModelAndReadsItsReply() {
        String reply = chatCompletionClient.complete(
                "You are a connectivity probe. Reply with the single word OK and nothing else.",
                "Say OK");

        // Printed on purpose: the visible reply is the point of this test.
        System.out.println("Model replied: " + reply);
        assertThat(reply).isNotBlank();
    }

    /**
     * The one thing curl did not establish. Measurement on 2026-07-31 sent {@code "input": "text"}
     * for a single embed and {@code "input": ["a","b"]} for a batch, but this client always sends an
     * array so that both paths share one wire format — so a one-element array is the single request
     * shape never proven against the real endpoint.
     */
    @Test
    void embedsASingleTextAsAOneElementArrayAndGetsAUnitVectorOfTheConfiguredSize() {
        float[] vector = embeddingClient.embed("frontend engineer who builds single page apps");

        System.out.println("Embedding returned " + vector.length + " dimensions");
        assertThat(vector).hasSize(EXPECTED_DIMENSIONS);
        assertThat(magnitudeOf(vector))
                .as("the adapter must have renormalised the truncated vector")
                .isCloseTo(1.0, within(1.0e-5));
    }

    /**
     * Also confirms the batch limit is not lower than the backfill will need, and that similar texts
     * really do score higher than dissimilar ones — the premise the whole epic rests on.
     */
    @Test
    void embedsABatchAndPlacesRelatedTextsCloserTogetherThanUnrelatedOnes() {
        List<float[]> vectors = embeddingClient.embedAll(List.of(
                "frontend engineer who builds single page apps",
                "react developer",
                "diesel mechanic for heavy earthmoving equipment"));

        assertThat(vectors).hasSize(3);
        double relatedSimilarity = dotProductOf(vectors.get(0), vectors.get(1));
        double unrelatedSimilarity = dotProductOf(vectors.get(0), vectors.get(2));

        System.out.printf("frontend~react = %.4f, frontend~mechanic = %.4f%n",
                relatedSimilarity, unrelatedSimilarity);
        assertThat(relatedSimilarity).isGreaterThan(unrelatedSimilarity);
    }

    private double magnitudeOf(float[] vector) {
        return Math.sqrt(dotProductOf(vector, vector));
    }

    /**
     * Cosine similarity, valid as a bare dot product only because {@link EmbeddingClient} promises
     * unit-length vectors — which is exactly what the test above checks.
     */
    private double dotProductOf(float[] first, float[] second) {
        double total = 0;
        for (int axis = 0; axis < first.length; axis++) {
            total += (double) first[axis] * second[axis];
        }
        return total;
    }
}
