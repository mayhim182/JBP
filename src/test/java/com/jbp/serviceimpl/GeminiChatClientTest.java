package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiChatClientTest {

    private static final String BASE_URL = "https://ai.test/v1";
    private static final String COMPLETIONS_URL = BASE_URL + "/chat/completions";
    private static final String API_KEY = "test-key";
    private static final String MODEL = "test-model";

    private static final String SUCCESS_BODY = """
            {
              "choices": [ { "message": { "role": "assistant", "content": "Shortlisted" } } ],
              "usage": { "prompt_tokens": 120, "completion_tokens": 8 }
            }
            """;

    private MockRestServiceServer provider;
    private GeminiChatClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        provider = MockRestServiceServer.createServer(restTemplate);
        client = new GeminiChatClient(restTemplate, BASE_URL, API_KEY, MODEL);
    }

    @Test
    void sendsAnAuthenticatedPromptAndReturnsTheReplyText() {
        provider.expect(once(), requestTo(COMPLETIONS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.messages[0].role").value("system"))
                .andExpect(jsonPath("$.messages[0].content").value("You rank candidates"))
                .andExpect(jsonPath("$.messages[1].role").value("user"))
                .andExpect(jsonPath("$.messages[1].content").value("Candidate summary"))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        String reply = client.complete("You rank candidates", "Candidate summary");

        assertThat(reply).isEqualTo("Shortlisted");
        provider.verify();
    }

    @Test
    void doesNotRetryAfterA4xxBecauseTheRequestItselfIsWrong() {
        provider.expect(once(), requestTo(COMPLETIONS_URL)).andRespond(withBadRequest());

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("rejected the request");

        provider.verify();
    }

    @Test
    void reportsA4xxAsNotWorthRetrying() {
        provider.expect(once(), requestTo(COMPLETIONS_URL)).andRespond(withBadRequest());

        assertThat(captureFailure().isRetryable()).isFalse();
        provider.verify();
    }

    @Test
    void retriesOnceAfterA5xxAndReturnsTheSecondReply() {
        provider.expect(once(), requestTo(COMPLETIONS_URL)).andRespond(withServerError());
        provider.expect(once(), requestTo(COMPLETIONS_URL))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        assertThat(client.complete("system", "user")).isEqualTo("Shortlisted");

        provider.verify();
    }

    @Test
    void givesUpAfterTheSingleRetryAlsoFails() {
        provider.expect(once(), requestTo(COMPLETIONS_URL)).andRespond(withServerError());
        provider.expect(once(), requestTo(COMPLETIONS_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("server error");

        provider.verify();
    }

    @Test
    void retriesOnceAfterATimeout() {
        provider.expect(once(), requestTo(COMPLETIONS_URL))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });
        provider.expect(once(), requestTo(COMPLETIONS_URL))
                .andRespond(withSuccess(SUCCESS_BODY, MediaType.APPLICATION_JSON));

        assertThat(client.complete("system", "user")).isEqualTo("Shortlisted");

        provider.verify();
    }

    @Test
    void reportsAnExhaustedTimeoutAsUnavailable() {
        provider.expect(once(), requestTo(COMPLETIONS_URL))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });
        provider.expect(once(), requestTo(COMPLETIONS_URL))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("did not respond in time");

        provider.verify();
    }

    @Test
    void rejectsAResponseThatCarriesNoCompletion() {
        provider.expect(once(), requestTo(COMPLETIONS_URL))
                .andRespond(withSuccess("{\"choices\": []}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.complete("system", "user"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("no completion");

        provider.verify();
    }

    @Test
    void ignoresUnknownProviderFieldsSoNewApiFieldsCannotBreakParsing() {
        provider.expect(once(), requestTo(COMPLETIONS_URL))
                .andRespond(withSuccess("""
                        {
                          "id": "abc",
                          "model": "test-model",
                          "choices": [ { "index": 0, "finish_reason": "stop",
                                         "message": { "role": "assistant", "content": "Shortlisted" } } ],
                          "usage": { "prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3 }
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThat(client.complete("system", "user")).isEqualTo("Shortlisted");
        provider.verify();
    }

    private LlmUnavailableException captureFailure() {
        try {
            client.complete("system", "user");
            throw new AssertionError("Expected LlmUnavailableException");
        } catch (LlmUnavailableException expected) {
            return expected;
        }
    }
}
