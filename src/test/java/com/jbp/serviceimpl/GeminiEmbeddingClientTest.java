package com.jbp.serviceimpl;

import com.jbp.exception.LlmUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.net.SocketTimeoutException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GeminiEmbeddingClientTest {

    private static final String BASE_URL = "https://ai.test/v1";
    private static final String EMBEDDINGS_URL = BASE_URL + "/embeddings";
    private static final String API_KEY = "test-key";
    private static final String MODEL = "test-embedding-model";
    private static final int DIMENSIONS = 3;

    /**
     * (3, 0, 4) has magnitude 5, so a correctly normalised result is (0.6, 0, 0.8).
     *
     * <p>Deliberately carries <strong>no {@code index}</strong>, because that is what Gemini
     * actually returns — confirmed live on 2026-07-31. The first version of this fixture copied the
     * OpenAI schema, included an index, and so every test passed against a response shape the
     * provider never sends. The default fixture now mirrors production; the ordering tests below
     * add indices explicitly, because those are about what to do when a provider does supply them.
     */
    private static final String SINGLE_VECTOR_BODY = """
            {
              "object": "list",
              "data": [ { "object": "embedding", "embedding": [3.0, 0.0, 4.0] } ],
              "model": "test-embedding-model",
              "usage": { "prompt_tokens": 8, "total_tokens": 8 }
            }
            """;

    private MockRestServiceServer provider;
    private GeminiEmbeddingClient client;

    @BeforeEach
    void setUp() {
        RestTemplate restTemplate = new RestTemplate();
        provider = MockRestServiceServer.createServer(restTemplate);
        client = new GeminiEmbeddingClient(restTemplate, BASE_URL, API_KEY, MODEL, DIMENSIONS);
    }

    @Test
    void sendsAnAuthenticatedRequestCarryingTheModelTheInputAndTheRequestedDimensions() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer " + API_KEY))
                .andExpect(jsonPath("$.model").value(MODEL))
                .andExpect(jsonPath("$.input[0]").value("frontend engineer"))
                .andExpect(jsonPath("$.dimensions").value(DIMENSIONS))
                .andRespond(withSuccess(SINGLE_VECTOR_BODY, MediaType.APPLICATION_JSON));

        float[] vector = client.embed("frontend engineer");

        assertThat(vector[0]).isCloseTo(0.6f, within(1.0e-6f));
        assertThat(vector[1]).isCloseTo(0.0f, within(1.0e-6f));
        assertThat(vector[2]).isCloseTo(0.8f, within(1.0e-6f));
        provider.verify();
    }

    @Test
    void sendsASingleEmbedAsABatchOfOneSoBothPathsShareOneWireFormat() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL))
                .andExpect(jsonPath("$.input.length()").value(1))
                .andRespond(withSuccess(SINGLE_VECTOR_BODY, MediaType.APPLICATION_JSON));

        client.embed("frontend engineer");

        provider.verify();
    }

    @Test
    void returnsAUnitLengthVectorEvenThoughTheProviderDidNot() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL))
                .andRespond(withSuccess(SINGLE_VECTOR_BODY, MediaType.APPLICATION_JSON));

        float[] vector = client.embed("frontend engineer");

        assertThat(magnitudeOf(vector))
                .as("callers compare with a dot product, which is only cosine if length is 1")
                .isCloseTo(1.0, within(1.0e-6));
    }

    @Test
    void returnsAVectorOfExactlyTheConfiguredDimension() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL))
                .andRespond(withSuccess(SINGLE_VECTOR_BODY, MediaType.APPLICATION_JSON));

        assertThat(client.embed("frontend engineer")).hasSize(DIMENSIONS);
    }

    @Test
    void rejectsAVectorWhoseLengthIsNotTheDimensionThatWasRequested() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withSuccess("""
                { "data": [ { "index": 0, "embedding": [1.0, 0.0] } ] }
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed("frontend engineer"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("2 dimensions but 3 were requested");
    }

    @Test
    void alignsBatchVectorsWithInputOrderEvenWhenTheProviderAnswersOutOfOrder() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withSuccess("""
                {
                  "data": [
                    { "index": 1, "embedding": [0.0, 5.0, 0.0] },
                    { "index": 0, "embedding": [3.0, 0.0, 4.0] }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embedAll(List.of("react developer", "frontend engineer"));

        assertThat(vectors).hasSize(2);
        assertThat(vectors.get(0)[0])
                .as("input 0 must get the vector the provider labelled index 0")
                .isCloseTo(0.6f, within(1.0e-6f));
        assertThat(vectors.get(1)[1])
                .as("input 1 must get the vector the provider labelled index 1")
                .isCloseTo(1.0f, within(1.0e-6f));
    }

    @Test
    void rejectsAResponseCarryingFewerVectorsThanThereWereInputs() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withSuccess("""
                { "data": [ { "index": 0, "embedding": [3.0, 0.0, 4.0] } ] }
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embedAll(List.of("react developer", "frontend engineer")))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("returned 1 vectors for 2 inputs");
    }

    @Test
    void fallsBackToResponseOrderWhenTheProviderIndexesNothing() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withSuccess("""
                {
                  "data": [
                    { "embedding": [3.0, 0.0, 4.0] },
                    { "embedding": [0.0, 5.0, 0.0] }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embedAll(List.of("react developer", "frontend engineer"));

        assertThat(vectors.get(0)[0])
                .as("Gemini sends no index, so response order is the only ordering there is")
                .isCloseTo(0.6f, within(1.0e-6f));
        assertThat(vectors.get(1)[1]).isCloseTo(1.0f, within(1.0e-6f));
    }

    /**
     * The real Gemini batch shape, confirmed live on 2026-07-31: the vector at index 0 arrives with
     * no {@code index} field at all, because proto3 JSON omits fields holding their default value.
     * Here it also arrives second, so the test proves absence-means-zero and reordering together.
     */
    @Test
    void treatsAnAbsentIndexAsZeroBecauseTheProviderOmitsDefaultValues() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withSuccess("""
                {
                  "data": [
                    { "index": 1, "embedding": [0.0, 5.0, 0.0] },
                    { "embedding": [3.0, 0.0, 4.0] }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        List<float[]> vectors = client.embedAll(List.of("react developer", "frontend engineer"));

        assertThat(vectors.get(0)[0])
                .as("the unindexed vector is index 0 and belongs to the first input")
                .isCloseTo(0.6f, within(1.0e-6f));
        assertThat(vectors.get(1)[1]).isCloseTo(1.0f, within(1.0e-6f));
    }

    @Test
    void rejectsABatchWhereMoreThanOneVectorLacksAnIndex() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withSuccess("""
                {
                  "data": [
                    { "embedding": [3.0, 0.0, 4.0] },
                    { "embedding": [0.0, 5.0, 0.0] },
                    { "index": 2, "embedding": [0.0, 0.0, 7.0] }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embedAll(List.of("react", "frontend", "mechanic")))
                .as("proto3 can omit only the single zero index, so two absences is a real fault")
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("index 0 where 1 was expected");
    }

    @Test
    void rejectsABatchWhoseIndicesSkipARequestPosition() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withSuccess("""
                {
                  "data": [
                    { "index": 0, "embedding": [3.0, 0.0, 4.0] },
                    { "index": 2, "embedding": [0.0, 5.0, 0.0] }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embedAll(List.of("react developer", "frontend engineer")))
                .as("sorting fixes order but cannot notice that input 1 was never answered")
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("index 2 where 1 was expected");
    }

    @Test
    void rejectsABatchThatRepeatsAnIndex() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withSuccess("""
                {
                  "data": [
                    { "index": 0, "embedding": [3.0, 0.0, 4.0] },
                    { "index": 0, "embedding": [0.0, 5.0, 0.0] }
                  ]
                }
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embedAll(List.of("react developer", "frontend engineer")))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("index 0 where 1 was expected");
    }

    @Test
    void rejectsAZeroVectorBecauseItHasNoDirectionToCompare() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withSuccess("""
                { "data": [ { "index": 0, "embedding": [0.0, 0.0, 0.0] } ] }
                """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed("frontend engineer"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("no usable direction");
    }

    @Test
    void rejectsAnEmptyResponseEnvelope() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.embed("frontend engineer"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("no vectors");
    }

    @Test
    void refusesBlankTextAsACallerBugRatherThanAProviderFailure() {
        assertThatThrownBy(() -> client.embed("   "))
                .as("falling back here would hide an empty profile behind an apparent model outage")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Cannot embed blank text");

        provider.verify();
    }

    @Test
    void refusesABatchContainingBlankTextWithoutSpendingTheCall() {
        assertThatThrownBy(() -> client.embedAll(List.of("react developer", "")))
                .isInstanceOf(IllegalArgumentException.class);

        provider.verify();
    }

    @Test
    void returnsNothingForAnEmptyBatchWithoutContactingTheProvider() {
        assertThat(client.embedAll(List.of())).isEmpty();

        provider.verify();
    }

    @Test
    void doesNotRetryAfterA4xxBecauseTheRequestItselfIsWrong() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withBadRequest());

        assertThatThrownBy(() -> client.embed("frontend engineer"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("rejected the request");

        provider.verify();
    }

    @Test
    void reportsA4xxAsNotWorthRetrying() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withBadRequest());

        assertThat(captureFailure().isRetryable()).isFalse();
        provider.verify();
    }

    @Test
    void retriesOnceAfterA5xxAndReturnsTheSecondResponse() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withServerError());
        provider.expect(once(), requestTo(EMBEDDINGS_URL))
                .andRespond(withSuccess(SINGLE_VECTOR_BODY, MediaType.APPLICATION_JSON));

        assertThat(client.embed("frontend engineer")).hasSize(DIMENSIONS);

        provider.verify();
    }

    @Test
    void givesUpAfterTheSingleRetryAlsoFails() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withServerError());
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withServerError());

        assertThatThrownBy(() -> client.embed("frontend engineer"))
                .isInstanceOf(LlmUnavailableException.class)
                .hasMessageContaining("server error");

        provider.verify();
    }

    @Test
    void retriesOnceAfterATimeout() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL))
                .andRespond(request -> {
                    throw new SocketTimeoutException("Read timed out");
                });
        provider.expect(once(), requestTo(EMBEDDINGS_URL))
                .andRespond(withSuccess(SINGLE_VECTOR_BODY, MediaType.APPLICATION_JSON));

        assertThat(client.embed("frontend engineer")).hasSize(DIMENSIONS);

        provider.verify();
    }

    @Test
    void ignoresUnknownProviderFieldsSoNewApiFieldsCannotBreakParsing() {
        provider.expect(once(), requestTo(EMBEDDINGS_URL)).andRespond(withSuccess("""
                {
                  "object": "list",
                  "some_new_field": "ignored",
                  "data": [ { "object": "embedding", "index": 0, "another_new_field": 7,
                              "embedding": [3.0, 0.0, 4.0] } ],
                  "usage": { "prompt_tokens": 1, "total_tokens": 1, "billed_units": 1 }
                }
                """, MediaType.APPLICATION_JSON));

        assertThat(client.embed("frontend engineer")).hasSize(DIMENSIONS);
        provider.verify();
    }

    private double magnitudeOf(float[] vector) {
        double sumOfSquares = 0;
        for (float component : vector) {
            sumOfSquares += (double) component * component;
        }
        return Math.sqrt(sumOfSquares);
    }

    private LlmUnavailableException captureFailure() {
        try {
            client.embed("frontend engineer");
            throw new AssertionError("Expected LlmUnavailableException");
        } catch (LlmUnavailableException expected) {
            return expected;
        }
    }
}
