package io.eranalytics.pipeline.collection.game;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import io.eranalytics.pipeline.collection.retry.CollectionRetryExecutor;
import io.eranalytics.pipeline.collection.runtime.CollectionSleeper;
import io.eranalytics.pipeline.erapi.ErApiClient;
import io.eranalytics.pipeline.erapi.ErApiException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.web.client.ResourceAccessException;

class GameCollectionProcessorTest {
    private final ErApiClient api = mock(ErApiClient.class);
    private final GameQueueRepository repository = mock(GameQueueRepository.class);
    private final CollectedGameStore games = mock(CollectedGameStore.class);
    private final CollectionSleeper sleeper = mock(CollectionSleeper.class);
    private final GameCollectionProcessor processor =
            new GameCollectionProcessor(
                    api, repository, games, new CollectionRetryExecutor(sleeper));
    private final GameCollectionJob job = new GameCollectionJob(7, "GAME", "200", 1);
    private final List<JsonNode> participants = List.of(mock(JsonNode.class));

    @ParameterizedTest
    @ValueSource(ints = {403, 429, 500, 503})
    void retriesFiveTimesInPlaceWithExponentialBackoffAndFinalFailure(int code) {
        when(api.gameParticipants("200")).thenThrow(new ErApiException(code, "private message"));
        processor.process(job);
        verify(api, times(5)).gameParticipants("200");
        ArgumentCaptor<Duration> delays = ArgumentCaptor.forClass(Duration.class);
        verify(sleeper, times(4)).sleep(delays.capture());
        assertThat(delays.getAllValues())
                .containsExactly(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(4),
                        Duration.ofSeconds(8));
        verify(repository)
                .markFailure(
                        new GameCollectionJob(7, "GAME", "200", 5), "ER API code " + code, true);
        verify(repository, never()).claimNextGame();
        verify(games, never()).completeGame(any(), any());
    }

    @Test
    void doesNotRetry404() {
        when(api.gameParticipants("200")).thenThrow(new ErApiException(404, "not found"));
        processor.process(job);
        verify(api).gameParticipants("200");
        verify(repository).markFailure(job, "ER API code 404", false);
        verifyNoInteractions(sleeper);
    }

    @Test
    void retriesNetworkAndTransientPersistenceThenCompletes() {
        when(api.gameParticipants("200"))
                .thenThrow(new ResourceAccessException("secret URL"))
                .thenReturn(participants);
        doThrow(
                        new IllegalStateException(
                                "Game persistence failed",
                                new TransientDataAccessResourceException("private DB row")))
                .doNothing()
                .when(games)
                .completeGame(job, participants);
        processor.process(job);
        verify(api, times(3)).gameParticipants("200");
        verify(games, times(2)).completeGame(job, participants);
        verify(repository).recordAttempt(job, 3);
        verify(repository).markFailure(job, "ResourceAccessException", true);
        verify(repository)
                .markFailure(
                        new GameCollectionJob(7, "GAME", "200", 2), "IllegalStateException", true);
    }

    @Test
    void doesNotRetryMalformedGameOrPermanentDatabaseFailure() {
        when(api.gameParticipants("200"))
                .thenThrow(new IllegalArgumentException("private response"));
        processor.process(job);
        verify(api).gameParticipants("200");
        verify(repository).markFailure(job, "IllegalArgumentException", false);
        verifyNoInteractions(sleeper);
    }

    @Test
    void restartRespectsAlreadyConsumedAttemptBudget() {
        when(api.gameParticipants("200")).thenThrow(new ErApiException(503, "failed"));
        processor.process(new GameCollectionJob(7, "GAME", "200", 4));
        verify(api, times(2)).gameParticipants("200");
        verify(sleeper).sleep(Duration.ofSeconds(8));
        verify(repository).recordAttempt(new GameCollectionJob(7, "GAME", "200", 4), 5);
    }

    @Test
    void attemptRecordingFailurePropagatesInsteadOfSpinningQueue() {
        doThrow(new IllegalStateException("DB unavailable")).when(repository).recordAttempt(job, 1);
        assertThatThrownBy(() -> processor.process(job)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(api);
    }

    @Test
    void failureRecordingFailurePropagates() {
        when(api.gameParticipants("200")).thenThrow(new ErApiException(503, "failed"));
        doThrow(new IllegalStateException("DB unavailable"))
                .when(repository)
                .markFailure(job, "ER API code 503", true);
        assertThatThrownBy(() -> processor.process(job)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(sleeper);
    }

    @Test
    void emptyQueueDoesNotFetchOrStoreGames() {
        when(repository.claimNextGame()).thenReturn(Optional.empty());
        assertThat(processor.processNext()).isFalse();
        verifyNoInteractions(api, games, sleeper);
    }

    @Test
    void exhaustedJobDoesNotPreventProcessingTheNextQueuedJob() {
        GameCollectionJob next = new GameCollectionJob(8, "GAME", "300", 1);
        when(repository.claimNextGame()).thenReturn(Optional.of(job), Optional.of(next));
        when(api.gameParticipants("200")).thenThrow(new ErApiException(404, "missing"));
        when(api.gameParticipants("300")).thenReturn(participants);
        assertThat(processor.processNext()).isTrue();
        assertThat(processor.processNext()).isTrue();
        verify(repository).markFailure(job, "ER API code 404", false);
        verify(games).completeGame(next, participants);
    }
}
