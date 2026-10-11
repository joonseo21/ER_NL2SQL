package io.eranalytics.pipeline.collection.metadata;

import static io.eranalytics.pipeline.collection.CollectionTestSettings.settings;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.eranalytics.pipeline.collection.retry.CollectionRetryExecutor;
import io.eranalytics.pipeline.collection.runtime.CollectionSleeper;
import io.eranalytics.pipeline.erapi.ErApiClient;
import io.eranalytics.pipeline.erapi.ErApiException;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class NameMetadataRefresherTest {
    private final ErApiClient api = mock(ErApiClient.class);
    private final NameMetadataStore names = mock(NameMetadataStore.class);
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-10-09T00:00:00Z");
    private final NameMetadataRefresher metadata =
            new NameMetadataRefresher(
                    api,
                    new CollectionRetryExecutor(mock(CollectionSleeper.class)),
                    settings(30, false),
                    clock,
                    names);

    @Test
    void refreshesOnStartupThenAt24HoursAndKeepsEnglishOnLocalizationFailure() throws Exception {
        when(clock.instant()).thenReturn(now, now.plusSeconds(60), now.plusSeconds(86400));
        when(api.get("/v2/data/Character"))
                .thenReturn(
                        new ObjectMapper()
                                .readTree("{\"data\":[{\"code\":1,\"name\":\"Synthetic\"}]}"));
        when(api.get("/v1/l10n/Korean")).thenThrow(new ErApiException(404, "missing"));
        metadata.refreshIfDue();
        metadata.refreshIfDue();
        metadata.refreshIfDue();
        verify(api, times(2)).get("/v2/data/Character");
        verify(names, times(2)).saveCharacterNames(1, null, "Synthetic");
    }

    @Test
    void metadataFailureDoesNotRefreshOnEveryGame() {
        when(clock.instant()).thenReturn(now, now.plusSeconds(60));
        when(api.get("/v2/data/Character")).thenThrow(new ErApiException(503, "unavailable"));
        metadata.refreshIfDue();
        metadata.refreshIfDue();
        verify(api, times(5)).get("/v2/data/Character");
        verifyNoInteractions(names);
    }

    @Test
    void resolvesKoreanNamesFromLocalizationText() throws Exception {
        when(clock.instant()).thenReturn(now);
        ObjectMapper mapper = new ObjectMapper();
        when(api.get("/v2/data/Character"))
                .thenReturn(mapper.readTree("{\"data\":[{\"code\":1,\"name\":\"Synthetic\"}]}"));
        when(api.get("/v1/l10n/Korean"))
                .thenReturn(
                        mapper.readTree(
                                "{\"data\":{\"l10Path\":\"https://example.invalid/names\"}}"));
        MetadataFixture.tables()
                .forEach(
                        (table, payload) -> when(api.get("/v2/data/" + table)).thenReturn(payload));
        when(api.getPublicText("https://example.invalid/names", "l10n/Korean/download"))
                .thenReturn(MetadataFixture.localization());
        metadata.refreshIfDue();
        verify(names)
                .save(
                        argThat(
                                batch ->
                                        batch.checkedAt().equals(now)
                                                && batch.names()
                                                        .get(
                                                                io.eranalytics.pipeline.collection
                                                                        .metadata.NameKind
                                                                        .CHARACTER)
                                                        .getFirst()
                                                        .nameKo()
                                                        .equals("가상 실험체")));
        verify(names, never()).saveCharacterNames(anyInt(), any(), any());
    }

    @Test
    void laterTableFailureKeepsPreviousSnapshotAndWaitsBeforeRetry() throws Exception {
        when(clock.instant()).thenReturn(now, now.plusSeconds(60));
        MetadataFixture.tables()
                .forEach(
                        (table, payload) -> when(api.get("/v2/data/" + table)).thenReturn(payload));
        when(api.get("/v1/l10n/Korean"))
                .thenReturn(
                        new ObjectMapper()
                                .readTree(
                                        "{\"data\":{\"l10Path\":\"https://example.invalid/names\"}}"));
        when(api.getPublicText("https://example.invalid/names", "l10n/Korean/download"))
                .thenReturn(MetadataFixture.localization());
        when(api.get("/v2/data/Area")).thenThrow(new ErApiException(404, "missing"));
        metadata.refreshIfDue();
        metadata.refreshIfDue();
        verify(api).getPublicText("https://example.invalid/names", "l10n/Korean/download");
        verify(api).get("/v2/data/Character");
        verifyNoInteractions(names);
    }
}
