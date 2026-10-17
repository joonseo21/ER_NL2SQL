package io.eranalytics.pipeline.collection.route;

import io.eranalytics.pipeline.collection.runtime.CollectionInterruptedException;
import io.eranalytics.pipeline.erapi.ErApiClient;
import io.eranalytics.pipeline.erapi.ErApiException;
import java.time.Clock;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Each observed season/number is requested once; terminal failures never return to the pool. */
@Component
public class RouteCollectionProcessor {
    private final RouteStore store;
    private final ErApiClient api;
    private final RouteMapper mapper;
    private final Clock clock;
    private final int perGame;

    public RouteCollectionProcessor(
            RouteStore store,
            ErApiClient api,
            RouteMapper mapper,
            Clock clock,
            @Value("${er.collection.routes-per-game:1}") int perGame) {
        if (perGame < 1) throw new IllegalArgumentException("routes-per-game must be positive");
        this.store = store;
        this.api = api;
        this.mapper = mapper;
        this.clock = clock;
        this.perGame = perGame;
    }

    public void initializePool() {
        store.initializePool();
    }

    public void processBatch() {
        for (int index = 0; index < perGame; index++) {
            Optional<RouteKey> next = store.claimNext(clock.instant());
            if (next.isEmpty()) return;
            fetch(next.get());
        }
    }

    private void fetch(RouteKey key) {
        RouteSnapshot snapshot;
        try {
            snapshot = mapper.map(key, api.recommendRoute(key.routeId()));
        } catch (CollectionInterruptedException error) {
            throw error;
        } catch (ErApiException error) {
            store.fail(
                    key,
                    error.getStatusCode() == 404 ? "NOT_FOUND" : "ERROR",
                    error.getStatusCode(),
                    clock.instant());
            return;
        } catch (IllegalArgumentException error) {
            store.fail(key, "INVALID_RESPONSE", null, clock.instant());
            return;
        } catch (RuntimeException error) {
            if (Thread.currentThread().isInterrupted()) throw new CollectionInterruptedException();
            store.fail(key, "ERROR", null, clock.instant());
            return;
        }
        // DB errors propagate: never misclassify a failed transaction as an API failure.
        store.save(key, snapshot, clock.instant());
    }
}
