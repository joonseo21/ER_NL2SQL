package io.eranalytics.pipeline.collection;

import static io.eranalytics.pipeline.collection.CollectionTestSettings.settings;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.eranalytics.pipeline.erapi.ApiException;
import io.eranalytics.pipeline.erapi.ErApiClient;
import java.time.Clock;
import java.time.Instant;
import io.eranalytics.pipeline.collection.persistence.NameMetadataStore;
import io.eranalytics.pipeline.collection.metadata.MetadataFixture;
import org.junit.jupiter.api.Test;

class CharacterMetadataCollectorTest {
    private final ErApiClient api = mock(ErApiClient.class);
    private final CollectorRepository repository = mock(CollectorRepository.class);
    private final NameMetadataStore names = mock(NameMetadataStore.class);
    private final Clock clock = mock(Clock.class);
    private final Instant now = Instant.parse("2026-10-09T00:00:00Z");
    private final CharacterMetadataCollector metadata = new CharacterMetadataCollector(api, repository,
            new CollectionRetry(mock(CollectionSleeper.class)), settings(30, false), clock, names);

    @Test
    void refreshesOnStartupThenAt24HoursAndKeepsEnglishOnLocalizationFailure() throws Exception {
        when(clock.instant()).thenReturn(now, now.plusSeconds(60), now.plusSeconds(86400));
        when(api.get("/v2/data/Character")).thenReturn(new ObjectMapper().readTree(
                "{\"data\":[{\"code\":1,\"name\":\"Synthetic\"}]}"));
        when(api.get("/v1/l10n/Korean")).thenThrow(new ApiException(404, "missing"));
        metadata.refreshIfDue();
        metadata.refreshIfDue();
        metadata.refreshIfDue();
        verify(api, times(2)).get("/v2/data/Character");
        verify(repository, times(2)).upsertCharacter(1, null, "Synthetic");
    }

    @Test
    void metadataFailureDoesNotRefreshOnEveryGame() {
        when(clock.instant()).thenReturn(now, now.plusSeconds(60));
        when(api.get("/v2/data/Character")).thenThrow(new ApiException(503, "unavailable"));
        metadata.refreshIfDue();
        metadata.refreshIfDue();
        verify(api, times(5)).get("/v2/data/Character");
        verifyNoInteractions(repository);
    }

    @Test
    void resolvesKoreanNamesFromLocalizationText() throws Exception {
        when(clock.instant()).thenReturn(now);
        var mapper = new ObjectMapper();
        when(api.get("/v2/data/Character")).thenReturn(mapper.readTree(
                "{\"data\":[{\"code\":1,\"name\":\"Synthetic\"}]}"));
        when(api.get("/v1/l10n/Korean")).thenReturn(mapper.readTree(
                "{\"data\":{\"l10Path\":\"https://example.invalid/names\"}}"));
        MetadataFixture.tables().forEach((table,payload) -> when(api.get("/v2/data/"+table)).thenReturn(payload));
        when(api.getPublicText("https://example.invalid/names", "l10n/Korean/download"))
                .thenReturn(MetadataFixture.localization());
        metadata.refreshIfDue();
        verify(names).save(argThat(batch -> batch.checkedAt().equals(now)
                && batch.names().get(io.eranalytics.pipeline.collection.metadata.NameKind.CHARACTER)
                .getFirst().nameKo().equals("가상 실험체")));
        verifyNoInteractions(repository);
    }

    @Test
    void laterTableFailureKeepsPreviousSnapshotAndWaitsBeforeRetry() throws Exception {
        when(clock.instant()).thenReturn(now,now.plusSeconds(60));
        MetadataFixture.tables().forEach((table,payload) -> when(api.get("/v2/data/"+table)).thenReturn(payload));
        when(api.get("/v1/l10n/Korean")).thenReturn(new ObjectMapper().readTree(
                "{\"data\":{\"l10Path\":\"https://example.invalid/names\"}}"));
        when(api.getPublicText("https://example.invalid/names","l10n/Korean/download")).thenReturn(MetadataFixture.localization());
        when(api.get("/v2/data/Area")).thenThrow(new ApiException(404,"missing"));
        metadata.refreshIfDue();
        metadata.refreshIfDue();
        verify(api).getPublicText("https://example.invalid/names","l10n/Korean/download");
        verify(api).get("/v2/data/Character");
        verifyNoInteractions(names,repository);
    }
}
