package com.jbp.serviceimpl;

import com.jbp.config.EmbeddingSettings;
import com.jbp.model.EmbeddingOwnerType;
import com.jbp.model.EmbeddingVector;
import com.jbp.repository.EmbeddingVectorRepository;
import com.jbp.service.EmbeddingClient;
import com.jbp.util.TextHash;
import com.jbp.util.VectorCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Story 13.2 — the quota rule. Every assertion here is really the same question: did we make a provider
 * call we did not have to?
 */
class EmbeddingStoreImplTest {

    private static final String MODEL = "gemini-embedding-001";
    private static final int DIMENSION = 3;
    private static final float[] VECTOR = {0.6f, 0.0f, 0.8f};
    private static final String TEXT = "frontend engineer";

    private final EmbeddingVectorRepository repository = Mockito.mock(EmbeddingVectorRepository.class);
    private final EmbeddingClient embeddingClient = Mockito.mock(EmbeddingClient.class);

    private final EmbeddingStoreImpl store = new EmbeddingStoreImpl(
            repository, embeddingClient, new EmbeddingSettings(MODEL, DIMENSION));

    @BeforeEach
    void providerReturnsOneVectorPerText() {
        Mockito.when(embeddingClient.embedAll(Mockito.anyList()))
                .thenAnswer(call -> ((List<String>) call.getArgument(0)).stream()
                        .map(text -> VECTOR.clone())
                        .toList());
    }

    @Test
    void embedsAndStoresWhenNothingIsStoredYet() {
        store.refresh(EmbeddingOwnerType.JOB, 1L, TEXT);

        ArgumentCaptor<EmbeddingVector> saved = ArgumentCaptor.forClass(EmbeddingVector.class);
        Mockito.verify(repository).save(saved.capture());
        assertThat(saved.getValue().getOwnerType()).isEqualTo(EmbeddingOwnerType.JOB);
        assertThat(saved.getValue().getOwnerId()).isEqualTo(1L);
        assertThat(saved.getValue().getModel()).isEqualTo(MODEL);
        assertThat(saved.getValue().getDimension()).isEqualTo(DIMENSION);
        assertThat(saved.getValue().getSourceHash()).hasSize(64);
        assertThat(VectorCodec.toFloats(saved.getValue().getVector())).containsExactly(VECTOR);
    }

    @Test
    void makesNoProviderCallWhenTheTextIsUnchanged() {
        givenStored(storedRow(1L, MODEL, DIMENSION, hashOf(TEXT)));

        store.refresh(EmbeddingOwnerType.JOB, 1L, TEXT);

        Mockito.verify(embeddingClient, Mockito.never()).embedAll(Mockito.anyList());
        Mockito.verify(repository, Mockito.never()).save(Mockito.any());
    }

    @Test
    void reEmbedsWhenTheTextChanged() {
        givenStored(storedRow(1L, MODEL, DIMENSION, hashOf("something else entirely")));

        store.refresh(EmbeddingOwnerType.JOB, 1L, TEXT);

        Mockito.verify(embeddingClient).embedAll(List.of(TEXT));
    }

    @Test
    void reEmbedsWhenTheConfiguredModelChanged() {
        givenStored(storedRow(1L, "some-older-model", DIMENSION, hashOf(TEXT)));

        store.refresh(EmbeddingOwnerType.JOB, 1L, TEXT);

        Mockito.verify(embeddingClient).embedAll(List.of(TEXT));
    }

    @Test
    void reEmbedsWhenTheConfiguredDimensionChanged() {
        givenStored(storedRow(1L, MODEL, 768, hashOf(TEXT)));

        store.refresh(EmbeddingOwnerType.JOB, 1L, TEXT);

        Mockito.verify(embeddingClient).embedAll(List.of(TEXT));
    }

    @Test
    void sendsAWholeBatchAsOneProviderCall() {
        store.refreshAll(EmbeddingOwnerType.JOB, Map.of(1L, "one", 2L, "two", 3L, "three"));

        Mockito.verify(embeddingClient, Mockito.times(1)).embedAll(Mockito.anyList());
        Mockito.verify(repository, Mockito.times(3)).save(Mockito.any());
    }

    @Test
    void skipsBlankTextInsteadOfFailingTheWholeBatch() {
        int embedded = store.refreshAll(EmbeddingOwnerType.JOB, Map.of(1L, "real text", 2L, "   "));

        assertThat(embedded)
                .as("a job with no title or description is a real database state, not a bug")
                .isEqualTo(1);
    }

    @Test
    void reportsHowManyWereActuallyEmbedded() {
        givenStored(storedRow(1L, MODEL, DIMENSION, hashOf("unchanged")));

        assertThat(store.refreshAll(EmbeddingOwnerType.JOB, Map.of(1L, "unchanged", 2L, "new")))
                .isEqualTo(1);
    }

    @Test
    void readsBackAStoredVector() {
        Mockito.when(repository.findByOwnerTypeAndOwnerId(EmbeddingOwnerType.JOB, 1L))
                .thenReturn(Optional.of(storedRow(1L, MODEL, DIMENSION, hashOf(TEXT))));

        assertThat(store.findVector(EmbeddingOwnerType.JOB, 1L)).contains(VECTOR);
    }

    @Test
    void treatsAVectorFromAnotherModelAsAbsent() {
        Mockito.when(repository.findByOwnerTypeAndOwnerId(EmbeddingOwnerType.JOB, 1L))
                .thenReturn(Optional.of(storedRow(1L, "some-older-model", DIMENSION, hashOf(TEXT))));

        assertThat(store.findVector(EmbeddingOwnerType.JOB, 1L))
                .as("falling back to rules beats a cosine between vectors from different models")
                .isEmpty();
    }

    @Test
    void treatsAVectorOfAnotherDimensionAsAbsent() {
        Mockito.when(repository.findByOwnerTypeAndOwnerId(EmbeddingOwnerType.JOB, 1L))
                .thenReturn(Optional.of(storedRow(1L, MODEL, 768, hashOf(TEXT))));

        assertThat(store.findVector(EmbeddingOwnerType.JOB, 1L)).isEmpty();
    }

    @Test
    void readsSeveralVectorsInOneQueryAndOmitsStaleOnes() {
        Mockito.when(repository.findByOwnerTypeAndOwnerIdIn(Mockito.eq(EmbeddingOwnerType.JOB), Mockito.any()))
                .thenReturn(List.of(
                        storedRow(1L, MODEL, DIMENSION, hashOf(TEXT)),
                        storedRow(2L, "some-older-model", DIMENSION, hashOf(TEXT))));

        Map<Long, float[]> vectors = store.findVectors(EmbeddingOwnerType.JOB, List.of(1L, 2L));

        assertThat(vectors).containsOnlyKeys(1L);
    }

    @Test
    void asksForNothingWhenGivenNothing() {
        assertThat(store.findVectors(EmbeddingOwnerType.JOB, List.of())).isEmpty();
        assertThat(store.refreshAll(EmbeddingOwnerType.JOB, Map.of())).isZero();
        Mockito.verifyNoInteractions(repository, embeddingClient);
    }

    private void givenStored(EmbeddingVector row) {
        Mockito.when(repository.findByOwnerTypeAndOwnerIdIn(Mockito.eq(EmbeddingOwnerType.JOB), Mockito.any()))
                .thenReturn(List.of(row));
    }

    private EmbeddingVector storedRow(Long ownerId, String model, int dimension, String sourceHash) {
        return EmbeddingVector.builder()
                .ownerType(EmbeddingOwnerType.JOB)
                .ownerId(ownerId)
                .model(model)
                .dimension(dimension)
                .sourceHash(sourceHash)
                .vector(VectorCodec.toBytes(VECTOR))
                .build();
    }

    /**
     * The same hash the store computes, via the same production code.
     *
     * <p>The first version of this drove a throwaway store through {@code refresh} and read back what it
     * saved — which called the shared {@code embeddingClient} mock, so it burned invocations the
     * assertions were counting and, inside a {@code when(...)}, produced Mockito's
     * {@code UnfinishedStubbing}. Seven tests failed for that one reason.
     *
     * <p>The fix was to extract the hashing to {@link TextHash} rather than reimplement SHA-256 here.
     * A second implementation could drift from the store's without any test noticing, and then the
     * "unchanged" tests would pass while proving nothing.
     */
    private String hashOf(String text) {
        return TextHash.sha256Hex(text);
    }
}
