package com.jbp.event;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.EmbeddingOwnerType;
import com.jbp.repository.CandidateProfileRepository;
import com.jbp.repository.JobRepository;
import com.jbp.service.EmbeddingStore;
import com.jbp.util.EmbeddingTexts;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * Refreshes an embedding after the save that triggered it has committed, on another thread.
 *
 * <p><strong>This is what keeps a slow provider out of the user's way.</strong> The embedding endpoint
 * has a 20-second timeout and {@code GeminiEmbeddingClient} retries once, so a bad moment — measured
 * twice in one afternoon during Story 13.1 — is a 40-second wait. Running it inline would mean a
 * candidate pressing Save and staring at a spinner for that long, for a vector they will never know
 * exists. The save commits; this follows.
 *
 * <p>{@code AFTER_COMMIT} rather than merely {@code @Async}: reading the entity before its transaction
 * committed could embed text that then rolled back, storing a vector for a state that never existed.
 * {@code fallbackExecution = true} keeps it working when something saves outside a transaction, matching
 * {@link ApplicationNotificationListener}.
 *
 * <p>Every failure is swallowed and logged. A missing vector is defined as harmless by Story 13.2 —
 * scoring falls back to rules — and there is no user on the other end of this thread to tell anyway.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class EmbeddingRefreshListener {

    private final EmbeddingStore embeddingStore;
    private final JobRepository jobRepository;
    private final CandidateProfileRepository candidateProfileRepository;

    /**
     * {@code @Transactional} is not optional here, and its absence was a real bug.
     *
     * <p>Both owners hold their skills and experience in {@code @ElementCollection}s, which are lazy.
     * Without a transaction on this thread, {@code findById} opens and closes its own, hands back a
     * detached entity, and the first {@code getSkills()} inside {@code EmbeddingTexts} throws
     * {@code LazyInitializationException}. It failed on the first real profile save, harmlessly —
     * swallowed and logged, save unaffected — but no embedding would ever have been written.
     *
     * <p>{@code REQUIRES_NEW} rather than the default: after {@code AFTER_COMMIT} the original
     * transaction is finished, and this runs on a pool thread that inherits nothing. The two behave
     * identically today; naming it explicitly means this still works if {@code @Async} is ever removed.
     */
    @Async
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onEmbeddingRefreshRequested(EmbeddingRefreshRequestedEvent event) {
        try {
            sourceTextFor(event).ifPresentOrElse(
                    sourceText -> embeddingStore.refresh(event.ownerType(), event.ownerId(), sourceText),
                    () -> log.debug("Nothing to embed for {} {} — record gone or has no text",
                            event.ownerType(), event.ownerId()));
        } catch (LlmUnavailableException providerUnavailable) {
            log.warn("Leaving {} {} without a fresh embedding: {}",
                    event.ownerType(), event.ownerId(), providerUnavailable.getMessage());
        } catch (RuntimeException unexpected) {
            // Nothing above this catches anything: this runs on a pool thread with no caller.
            log.error("Embedding refresh failed for {} {}",
                    event.ownerType(), event.ownerId(), unexpected);
        }
    }

    /**
     * Re-reads the record in this thread's own transaction. Empty when it has been deleted between the
     * commit and now, which is a normal race and not an error.
     */
    private Optional<String> sourceTextFor(EmbeddingRefreshRequestedEvent event) {
        // Blank is filtered out, not passed on: EmbeddingTexts returns "" for a record with nothing to
        // say, and Optional.of("") is present. Without this the store would be asked to embed an empty
        // string — harmless, since it skips blanks, but the "has no text" branch below could never fire
        // and this method would be lying about what it returns.
        if (event.ownerType() == EmbeddingOwnerType.JOB) {
            return jobRepository.findById(event.ownerId())
                    .map(EmbeddingTexts::forJob)
                    .filter(sourceText -> !sourceText.isBlank());
        }
        return candidateProfileRepository.findById(event.ownerId())
                .map(EmbeddingTexts::forCandidateProfile)
                .filter(sourceText -> !sourceText.isBlank());
    }
}
