package com.jbp.serviceimpl;

import com.jbp.service.ChatCompletionClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sends one real prompt to the configured provider, proving the API key, base URL and model name
 * actually work together — and that the whole Java path handles a genuine response, not just the
 * canned ones in {@link GeminiChatClientTest}.
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
        "app.ai.model=gemini-3.5-flash-lite",
        "app.ai.api-key=${GEMINI_API_KEY:}"
})
@EnabledIfEnvironmentVariable(named = "JBP_AI_LIVE_TEST", matches = "true")
class GeminiLiveSmokeTest {

    @Autowired
    private ChatCompletionClient chatCompletionClient;

    @Test
    void reachesTheConfiguredModelAndReadsItsReply() {
        String reply = chatCompletionClient.complete(
                "You are a connectivity probe. Reply with the single word OK and nothing else.",
                "Say OK");

        // Printed on purpose: the visible reply is the point of this test.
        System.out.println("Model replied: " + reply);
        assertThat(reply).isNotBlank();
    }
}
