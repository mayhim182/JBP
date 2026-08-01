package com.jbp.serviceimpl;

import com.jbp.config.EmbeddingSettings;
import com.jbp.model.EmbeddingOwnerType;
import com.jbp.model.EmbeddingVector;
import com.jbp.repository.EmbeddingVectorRepository;
import com.jbp.service.EmbeddingClient;
import com.jbp.service.EmbeddingStore;
import com.jbp.util.TextHash;
import com.jbp.util.VectorCodec;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingStoreImpl implements EmbeddingStore {

    private final EmbeddingVectorRepository embeddingVectorRepository;
    private final EmbeddingClient embeddingClient;
    private final EmbeddingSettings embeddingSettings;

    @Override
    @Transactional
    public void refresh(EmbeddingOwnerType ownerType, Long ownerId, String sourceText) {
        // One is a batch of one, so the skip rule, the ordering and the save exist in a single place.
        refreshAll(ownerType, Map.of(ownerId, sourceText == null ? "" : sourceText));
    }

    @Override
    @Transactional
    public int refreshAll(EmbeddingOwnerType ownerType, Map<Long, String> sourceTextsByOwnerId) {
        if (sourceTextsByOwnerId == null || sourceTextsByOwnerId.isEmpty()) {
            return 0;
        }
        Map<Long, EmbeddingVector> storedByOwnerId = storedByOwnerId(ownerType, sourceTextsByOwnerId.keySet());

        // Parallel lists, because embedAll answers positionally and the owner each vector belongs to has
        // to survive the round trip.
        List<Long> ownerIdsToEmbed = new ArrayList<>();
        List<String> textsToEmbed = new ArrayList<>();
        List<String> hashesToStore = new ArrayList<>();

        sourceTextsByOwnerId.forEach((ownerId, sourceText) -> {
            if (sourceText == null || sourceText.isBlank()) {
                log.debug("Skipping {} {} — nothing to embed", ownerType, ownerId);
                return;
            }
            String sourceHash = TextHash.sha256Hex(sourceText);
            if (isCurrent(storedByOwnerId.get(ownerId), sourceHash)) {
                return;
            }
            ownerIdsToEmbed.add(ownerId);
            textsToEmbed.add(sourceText);
            hashesToStore.add(sourceHash);
        });

        if (ownerIdsToEmbed.isEmpty()) {
            log.debug("No {} embeddings need refreshing out of {} checked",
                    ownerType, sourceTextsByOwnerId.size());
            return 0;
        }

        List<float[]> vectors = embeddingClient.embedAll(textsToEmbed);
        for (int position = 0; position < ownerIdsToEmbed.size(); position++) {
            store(ownerType, ownerIdsToEmbed.get(position), hashesToStore.get(position),
                    vectors.get(position), storedByOwnerId.get(ownerIdsToEmbed.get(position)));
        }
        log.info("Embedded {} of {} {} records in one provider call",
                ownerIdsToEmbed.size(), sourceTextsByOwnerId.size(), ownerType);
        return ownerIdsToEmbed.size();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<float[]> findVector(EmbeddingOwnerType ownerType, Long ownerId) {
        return embeddingVectorRepository.findByOwnerTypeAndOwnerId(ownerType, ownerId)
                .filter(this::matchesCurrentConfiguration)
                .map(stored -> VectorCodec.toFloats(stored.getVector()));
    }

    @Override
    @Transactional(readOnly = true)
    public Map<Long, float[]> findVectors(EmbeddingOwnerType ownerType, Collection<Long> ownerIds) {
        if (ownerIds == null || ownerIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, float[]> vectorsByOwnerId = new LinkedHashMap<>();
        for (EmbeddingVector stored : embeddingVectorRepository
                .findByOwnerTypeAndOwnerIdIn(ownerType, new HashSet<>(ownerIds))) {
            if (matchesCurrentConfiguration(stored)) {
                vectorsByOwnerId.put(stored.getOwnerId(), VectorCodec.toFloats(stored.getVector()));
            }
        }
        return vectorsByOwnerId;
    }

    private Map<Long, EmbeddingVector> storedByOwnerId(
            EmbeddingOwnerType ownerType, Collection<Long> ownerIds) {
        Map<Long, EmbeddingVector> storedByOwnerId = new HashMap<>();
        for (EmbeddingVector stored : embeddingVectorRepository
                .findByOwnerTypeAndOwnerIdIn(ownerType, new HashSet<>(ownerIds))) {
            storedByOwnerId.put(stored.getOwnerId(), stored);
        }
        return storedByOwnerId;
    }

    /**
     * A stored vector counts as current only if the text, the model and the size all still match. Any
     * one of the three differing makes it unusable, and treating a model or dimension change as "stale"
     * rather than "fine" is what stops a silently meaningless cosine later.
     */
    private boolean isCurrent(EmbeddingVector stored, String sourceHash) {
        return stored != null
                && sourceHash.equals(stored.getSourceHash())
                && matchesCurrentConfiguration(stored);
    }

    private boolean matchesCurrentConfiguration(EmbeddingVector stored) {
        return embeddingSettings.model().equals(stored.getModel())
                && embeddingSettings.dimension() == stored.getDimension();
    }

    /**
     * Updates the existing row rather than inserting a second one — the unique constraint on
     * (owner type, owner id) means one owner has one vector, and a re-embed replaces it.
     */
    private void store(EmbeddingOwnerType ownerType, Long ownerId, String sourceHash,
                       float[] vector, EmbeddingVector existing) {
        EmbeddingVector row = existing != null ? existing : EmbeddingVector.builder()
                .ownerType(ownerType)
                .ownerId(ownerId)
                .build();
        row.setModel(embeddingSettings.model());
        row.setDimension(vector.length);
        row.setSourceHash(sourceHash);
        row.setVector(VectorCodec.toBytes(vector));
        embeddingVectorRepository.save(row);
    }

}
