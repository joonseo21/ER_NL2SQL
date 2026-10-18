package io.eranalytics.pipeline.config;

import static org.assertj.core.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class CollectionPropertiesTest {
    @Test
    void defaultsToFullAndBindsExplicitRoutesOnlyMode() {
        assertThat(bind(Map.of("er.collection.enabled", "true")).mode())
                .isEqualTo(CollectionMode.FULL);
        assertThat(bind(Map.of("er.collection.enabled", "true")).routesTop()).isZero();
        assertThat(
                        bind(Map.of(
                                        "er.collection.enabled",
                                        "true",
                                        "er.collection.mode",
                                        "ROUTES_ONLY"))
                                .mode())
                .isEqualTo(CollectionMode.ROUTES_ONLY);
    }

    @Test
    void bindsTopBoundaryAndRejectsNegativeBoundary() {
        assertThat(bind(Map.of("er.collection.routes-top", "995")).routesTop()).isEqualTo(995);
        assertThatThrownBy(
                        () ->
                                io.eranalytics.pipeline.collection.CollectionTestSettings.settings(
                                                30, false, CollectionMode.ROUTES_ONLY, -1)
                                        .validate())
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rejectsMisspelledModeRatherThanStartingFullCollection() {
        assertThatThrownBy(() -> bind(Map.of("er.collection.mode", "ROUTE_ONLY")))
                .isInstanceOf(BindException.class);
    }

    private static CollectionProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bind("er.collection", Bindable.of(CollectionProperties.class))
                .get();
    }
}
