package com.jbp.event;

import com.jbp.exception.LlmUnavailableException;
import com.jbp.model.CandidateProfile;
import com.jbp.model.EmbeddingOwnerType;
import com.jbp.model.Job;
import com.jbp.repository.CandidateProfileRepository;
import com.jbp.repository.JobRepository;
import com.jbp.service.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.scheduling.annotation.Async;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Story 13.2 — "generating the embedding must not block the save".
 *
 * <p>That guarantee is declarative, so the first test checks the declaration. It is not decoration:
 * dropping either annotation would move a 40-second worst case onto the request thread, the tests below
 * would all still pass, and the only symptom would be users waiting after pressing Save.
 */
class EmbeddingRefreshListenerTest {

    private final EmbeddingStore embeddingStore = Mockito.mock(EmbeddingStore.class);
    private final JobRepository jobRepository = Mockito.mock(JobRepository.class);
    private final CandidateProfileRepository candidateProfileRepository =
            Mockito.mock(CandidateProfileRepository.class);

    private final EmbeddingRefreshListener listener = new EmbeddingRefreshListener(
            embeddingStore, jobRepository, candidateProfileRepository);

    @Test
    void runsAsynchronouslyAndOnlyAfterTheSaveHasCommitted() throws NoSuchMethodException {
        Method handler = EmbeddingRefreshListener.class.getMethod(
                "onEmbeddingRefreshRequested", EmbeddingRefreshRequestedEvent.class);

        assertThat(handler.getAnnotation(Async.class))
                .as("without @Async a slow provider blocks the request thread")
                .isNotNull();
        TransactionalEventListener listenerAnnotation =
                handler.getAnnotation(TransactionalEventListener.class);
        assertThat(listenerAnnotation).isNotNull();
        assertThat(listenerAnnotation.phase())
                .as("before commit, a rolled-back save would leave a vector for text that never existed")
                .isEqualTo(TransactionPhase.AFTER_COMMIT);
        assertThat(listenerAnnotation.fallbackExecution())
                .as("keeps working for saves made outside a transaction")
                .isTrue();

        Transactional transactional = handler.getAnnotation(Transactional.class);
        assertThat(transactional)
                .as("without a transaction here, the lazy @ElementCollections that EmbeddingTexts "
                        + "reads throw LazyInitializationException on a detached entity — which is "
                        + "exactly what happened on the first real profile save")
                .isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }

    @Test
    void embedsAJobsTextWhenAJobChanged() {
        Mockito.when(jobRepository.findById(5L))
                .thenReturn(Optional.of(Job.builder().id(5L).title("React Developer").build()));

        listener.onEmbeddingRefreshRequested(
                new EmbeddingRefreshRequestedEvent(EmbeddingOwnerType.JOB, 5L));

        Mockito.verify(embeddingStore).refresh(
                Mockito.eq(EmbeddingOwnerType.JOB), Mockito.eq(5L),
                Mockito.contains("React Developer"));
    }

    @Test
    void embedsAProfilesTextWhenAProfileChanged() {
        Mockito.when(candidateProfileRepository.findById(7L))
                .thenReturn(Optional.of(CandidateProfile.builder().headline("Frontend engineer").build()));

        listener.onEmbeddingRefreshRequested(
                new EmbeddingRefreshRequestedEvent(EmbeddingOwnerType.CANDIDATE_PROFILE, 7L));

        Mockito.verify(embeddingStore).refresh(
                Mockito.eq(EmbeddingOwnerType.CANDIDATE_PROFILE), Mockito.eq(7L),
                Mockito.contains("Frontend engineer"));
    }

    @Test
    void doesNothingWhenTheRecordWasDeletedBetweenCommitAndNow() {
        Mockito.when(jobRepository.findById(5L)).thenReturn(Optional.empty());

        listener.onEmbeddingRefreshRequested(
                new EmbeddingRefreshRequestedEvent(EmbeddingOwnerType.JOB, 5L));

        Mockito.verifyNoInteractions(embeddingStore);
    }

    @Test
    void doesNotEmbedAJobThatHasNoTextAtAll() {
        Mockito.when(jobRepository.findById(5L)).thenReturn(Optional.of(Job.builder().id(5L).build()));

        listener.onEmbeddingRefreshRequested(
                new EmbeddingRefreshRequestedEvent(EmbeddingOwnerType.JOB, 5L));

        Mockito.verify(embeddingStore, Mockito.never())
                .refresh(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    void swallowsAProviderOutageBecauseAMissingVectorIsHarmless() {
        Mockito.when(jobRepository.findById(5L))
                .thenReturn(Optional.of(Job.builder().id(5L).title("React Developer").build()));
        Mockito.doThrow(new LlmUnavailableException("rate limit reached", false))
                .when(embeddingStore).refresh(Mockito.any(), Mockito.any(), Mockito.any());

        assertThatCode(() -> listener.onEmbeddingRefreshRequested(
                new EmbeddingRefreshRequestedEvent(EmbeddingOwnerType.JOB, 5L)))
                .as("there is no caller on this thread to receive an exception")
                .doesNotThrowAnyException();
    }

    @Test
    void swallowsAnythingElseThatGoesWrongToo() {
        Mockito.when(jobRepository.findById(5L))
                .thenReturn(Optional.of(Job.builder().id(5L).title("React Developer").build()));
        Mockito.doThrow(new IllegalStateException("something nobody predicted"))
                .when(embeddingStore).refresh(Mockito.any(), Mockito.any(), Mockito.any());

        assertThatCode(() -> listener.onEmbeddingRefreshRequested(
                new EmbeddingRefreshRequestedEvent(EmbeddingOwnerType.JOB, 5L)))
                .doesNotThrowAnyException();
    }
}
