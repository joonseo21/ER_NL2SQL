package io.eranalytics.pipeline.webapi;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.eranalytics.pipeline.webapi.repository.CollectionSummaryRepository;
import io.eranalytics.pipeline.webapi.service.CollectionSummaryService;
import io.eranalytics.pipeline.webapi.service.WebApiClock;
import io.eranalytics.pipeline.webapi.service.WebApiException;
import java.time.Clock;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class CollectionSummaryServiceTest {
    private final CollectionSummaryRepository repository = mock(CollectionSummaryRepository.class);
    private final TestClock clock = new TestClock();
    private final CollectionSummaryService service =
            new CollectionSummaryService(repository, clock);

    @Test
    void keepsSuccessfulSnapshotForFiveMinutesAndIsolatesCallers() {
        when(repository.snapshot())
                .thenReturn(new ObjectMapper().createObjectNode().put("total_games", 2));
        ObjectNode first = service.summary();
        first.put("total_games", 999);
        clock.now = clock.now.plusSeconds(299);
        assertThat(service.summary().path("total_games").asInt()).isEqualTo(2);
        verify(repository, times(1)).snapshot();
        clock.now = clock.now.plusSeconds(1);
        service.summary();
        verify(repository, times(2)).snapshot();
    }

    @Test
    void doesNotReplaceUnavailableDataWithZerosOrLeakDatabaseError() {
        when(repository.snapshot()).thenThrow(new IllegalStateException("PRIVATE_DB_ERROR"));
        assertThatThrownBy(service::summary)
                .isInstanceOfSatisfying(
                        WebApiException.class,
                        error -> {
                            assertThat(error.getCode()).isEqualTo("COLLECTION_UNAVAILABLE");
                            assertThat(error.getMessage()).doesNotContain("PRIVATE_DB_ERROR");
                        });
    }

    static class TestClock extends WebApiClock {
        Instant now = Instant.parse("2026-10-13T00:00:00Z");

        TestClock() {
            super(Clock.systemUTC());
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
