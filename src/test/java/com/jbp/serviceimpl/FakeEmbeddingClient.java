package com.jbp.serviceimpl;

import com.jbp.service.EmbeddingClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Stand-in {@link EmbeddingClient} for testing the decorators, and from Story 13.2 the storage and
 * scoring layers, without any network access.
 *
 * <p>Counts calls so a test can prove a decorator stopped one reaching the provider, and records
 * what was sent so a test can prove a batch was sent as one call rather than as many.
 *
 * <p>Returns unit-length vectors, because that is what the real contract promises and a fake that
 * breaks the contract lets a caller depend on something production will not give it.
 */
class FakeEmbeddingClient implements EmbeddingClient {

    private final float[] vector;
    private final RuntimeException failure;
    private int callCount;
    private final List<String> lastTexts = new ArrayList<>();

    private FakeEmbeddingClient(float[] vector, RuntimeException failure) {
        this.vector = vector;
        this.failure = failure;
    }

    /** A fake returning the given unit vector for every input. */
    static FakeEmbeddingClient returning(float... unitVector) {
        return new FakeEmbeddingClient(unitVector, null);
    }

    static FakeEmbeddingClient failingWith(RuntimeException failure) {
        return new FakeEmbeddingClient(null, failure);
    }

    @Override
    public float[] embed(String text) {
        callCount++;
        lastTexts.clear();
        lastTexts.add(text);
        if (failure != null) {
            throw failure;
        }
        return vector.clone();
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        callCount++;
        lastTexts.clear();
        lastTexts.addAll(texts);
        if (failure != null) {
            throw failure;
        }
        return texts.stream().map(text -> vector.clone()).toList();
    }

    int callCount() {
        return callCount;
    }

    List<String> lastTexts() {
        return List.copyOf(lastTexts);
    }
}
