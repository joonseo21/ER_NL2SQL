package io.eranalytics.pipeline.config;

/** FULL discovers games; ROUTES_ONLY drains observed route IDs without changing match data. */
public enum CollectionMode {
    FULL,
    ROUTES_ONLY
}
