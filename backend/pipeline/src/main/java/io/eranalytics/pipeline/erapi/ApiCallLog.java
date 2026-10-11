package io.eranalytics.pipeline.erapi;

/** Persists a sanitized API call outcome without request parameters or response data. */
@FunctionalInterface
public interface ApiCallLog {
    void logApiCall(String endpoint, int statusCode, long latencyMs);
}
