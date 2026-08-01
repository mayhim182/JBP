package com.jbp.serviceimpl;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.jbp.exception.LlmUnavailableException;
import com.jbp.service.EmbeddingClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Talks to Google Gemini through its OpenAI-compatible embeddings endpoint.
 *
 * <p>The embedding twin of {@link GeminiChatClient}, and the only other class aware that a
 * specific provider exists. It shares that class's retry policy for the same reason: a timeout or
 * a 5xx is worth one more attempt, a 4xx never is because a malformed request or an invalid key
 * will fail identically and a wasted call costs free-tier quota.
 *
 * <p>Three properties of this adapter were established by measuring the live endpoint on
 * 2026-07-31 rather than read from documentation, and each one is a guard below:
 *
 * <ul>
 *   <li>{@code gemini-embedding-001} returns 3072 dimensions by default and honours a
 *       {@code dimensions} request, so the configured size is sent on every call and the response
 *       is rejected if it comes back a different length. Silently storing 3072-dimension vectors
 *       in a 768-dimension column would corrupt every comparison that followed.</li>
 *   <li>A truncated vector is <strong>not</strong> unit length — 768 dimensions measured a norm of
 *       0.583 against 0.99999998 for the full 3072. So normalising here is required for the
 *       {@link EmbeddingClient} contract to hold, not an optimisation.</li>
 *   <li>Batch input works and returns one vector per input.</li>
 * </ul>
 *
 * <p>Both public methods take the same path: a single embed is a batch of one. That keeps the wire
 * format, the retry, the ordering and the normalisation in one place instead of two that could
 * drift apart.
 */
public class GeminiEmbeddingClient implements EmbeddingClient {

    private static final Logger log = LoggerFactory.getLogger(GeminiEmbeddingClient.class);

    private static final String EMBEDDINGS_PATH = "/embeddings";

    private final RestTemplate restTemplate;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final int dimensions;

    public GeminiEmbeddingClient(RestTemplate restTemplate, String baseUrl, String apiKey,
                                 String model, int dimensions) {
        this.restTemplate = restTemplate;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public float[] embed(String text) {
        requireContent(text);
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        texts.forEach(GeminiEmbeddingClient::requireContent);
        try {
            return sendRequest(texts);
        } catch (LlmUnavailableException firstAttemptFailure) {
            if (!firstAttemptFailure.isRetryable()) {
                throw firstAttemptFailure;
            }
            log.warn("Embedding call failed transiently, retrying once: {}",
                    firstAttemptFailure.getMessage());
            return sendRequest(texts);
        }
    }

    private List<float[]> sendRequest(List<String> texts) {
        HttpEntity<EmbeddingRequest> request = new HttpEntity<>(
                new EmbeddingRequest(model, texts, dimensions),
                buildHeaders());
        try {
            EmbeddingResponse response = restTemplate.postForObject(
                    baseUrl + EMBEDDINGS_PATH, request, EmbeddingResponse.class);
            return extractUnitVectorsInRequestOrder(response, texts.size());
        } catch (HttpClientErrorException rejectedByProvider) {
            throw new LlmUnavailableException(
                    "Embedding model rejected the request with status "
                            + rejectedByProvider.getStatusCode(),
                    false, rejectedByProvider);
        } catch (HttpServerErrorException providerFault) {
            throw new LlmUnavailableException(
                    "Embedding model returned server error " + providerFault.getStatusCode(),
                    true, providerFault);
        } catch (ResourceAccessException timeoutOrNetworkFailure) {
            throw new LlmUnavailableException(
                    "Embedding model did not respond in time", true, timeoutOrNetworkFailure);
        } catch (RestClientException unreadableResponse) {
            throw new LlmUnavailableException(
                    "Embedding model response could not be read", false, unreadableResponse);
        }
    }

    private HttpHeaders buildHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);
        return headers;
    }

    /**
     * Turns the provider envelope into vectors aligned with the request.
     *
     * <p>Sorted by the provider's own {@code index} rather than trusted to arrive in order. The
     * field exists in the wire format precisely because order is not promised, and getting this
     * wrong in a batch would attach one job's vector to a different job — a silent mismatch that
     * no test of a single embed could ever catch, and one that would look like the model being
     * bad at matching rather than like a bug.
     */
    private List<float[]> extractUnitVectorsInRequestOrder(EmbeddingResponse response,
                                                           int expectedVectorCount) {
        if (response == null || response.data() == null) {
            throw new LlmUnavailableException("Embedding model returned no vectors", false);
        }
        if (response.data().size() != expectedVectorCount) {
            throw new LlmUnavailableException(
                    "Embedding model returned " + response.data().size() + " vectors for "
                            + expectedVectorCount + " inputs", false);
        }
        logTokenUsage(response.usage(), expectedVectorCount);

        List<EmbeddingData> vectorsInRequestOrder = new ArrayList<>(response.data());
        alignWithRequestOrder(vectorsInRequestOrder);
        return vectorsInRequestOrder.stream()
                .map(this::toUnitVector)
                .toList();
    }

    /**
     * Reorders the response to match the request using the provider's {@code index}.
     *
     * <p><strong>An absent index means zero.</strong> Measured live on 2026-07-31: a three-input
     * batch came back with an index on exactly two of its three vectors, and a single embed with an
     * index on none. That is not a broken provider — it is proto3 JSON, which omits any field
     * sitting at its default value, and Google's APIs are protobuf-backed. The element at index 0 is
     * therefore always the one missing the field.
     *
     * <p>Reading the absence as zero keeps every protection intact rather than weakening them to
     * accommodate the provider. Two vectors both lacking an index would both resolve to 0, and the
     * position check below rejects that — which is correct, since proto3 can only ever omit one.
     *
     * <p>The single exception is a response where nothing at all is indexed and there is more than
     * one vector. Proto3 omission cannot produce that, so it means a provider that simply does not
     * label its output, and response order becomes the only ordering information in existence.
     * Gemini never takes this branch; it exists so that swapping provider stays the configuration
     * change this architecture promises.
     */
    private static void alignWithRequestOrder(List<EmbeddingData> vectors) {
        if (vectors.size() > 1 && vectors.stream().allMatch(GeminiEmbeddingClient::isUnindexed)) {
            return;
        }
        vectors.sort(Comparator.comparingInt(GeminiEmbeddingClient::indexOrZero));
        requireIndicesMatchRequestPositions(vectors);
    }

    private static boolean isUnindexed(EmbeddingData data) {
        return data == null || data.index() == null;
    }

    private static int indexOrZero(EmbeddingData data) {
        return isUnindexed(data) ? 0 : data.index();
    }

    /**
     * Sorting alone only fixes order. It cannot detect a response that repeats an index or skips
     * one, and either would quietly hand input <i>n</i> the wrong vector. Once sorted, the indices
     * must be exactly the request positions, so comparing each to its own position proves alignment
     * rather than assuming it.
     */
    private static void requireIndicesMatchRequestPositions(List<EmbeddingData> sortedVectors) {
        for (int position = 0; position < sortedVectors.size(); position++) {
            int reportedIndex = indexOrZero(sortedVectors.get(position));
            if (reportedIndex != position) {
                throw new LlmUnavailableException(
                        "Embedding model returned index " + reportedIndex + " where " + position
                                + " was expected, so inputs and vectors cannot be matched up", false);
            }
        }
    }

    private float[] toUnitVector(EmbeddingData data) {
        float[] vector = data == null ? null : data.embedding();
        if (vector == null || vector.length == 0) {
            throw new LlmUnavailableException("Embedding model returned an empty vector", false);
        }
        if (vector.length != dimensions) {
            throw new LlmUnavailableException(
                    "Embedding model returned " + vector.length + " dimensions but "
                            + dimensions + " were requested", false);
        }
        return normalised(vector);
    }

    /**
     * Scales a vector to length 1, which is what {@link EmbeddingClient} promises its callers.
     *
     * <p>Accumulated as a {@code double}: 3072 squared floats summed in {@code float} loses enough
     * precision to move the resulting norm off 1.
     */
    private float[] normalised(float[] vector) {
        double sumOfSquares = 0;
        for (float component : vector) {
            sumOfSquares += (double) component * component;
        }
        double magnitude = Math.sqrt(sumOfSquares);
        if (magnitude == 0 || !Double.isFinite(magnitude)) {
            throw new LlmUnavailableException(
                    "Embedding model returned a vector with no usable direction", false);
        }
        float[] unitVector = new float[vector.length];
        for (int axis = 0; axis < vector.length; axis++) {
            unitVector[axis] = (float) (vector[axis] / magnitude);
        }
        return unitVector;
    }

    private static void requireContent(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException(
                    "Cannot embed blank text. Callers decide what an empty job or profile means; "
                            + "this is not a provider failure and must not fall back silently.");
        }
    }

    private void logTokenUsage(TokenUsage usage, int vectorCount) {
        if (usage != null && log.isDebugEnabled()) {
            log.debug("Embedding model {} used {} prompt tokens for {} texts at {} dimensions",
                    model, usage.promptTokens(), vectorCount, dimensions);
        }
    }

    /*
     * Provider wire format, kept nested so no other class can depend on its shape.
     * Unknown fields are ignored so a provider adding response fields cannot break parsing.
     */

    record EmbeddingRequest(String model, List<String> input, Integer dimensions) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingResponse(List<EmbeddingData> data, TokenUsage usage) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbeddingData(Integer index, float[] embedding) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TokenUsage(
            @JsonProperty("prompt_tokens") Integer promptTokens,
            @JsonProperty("total_tokens") Integer totalTokens) {
    }
}
