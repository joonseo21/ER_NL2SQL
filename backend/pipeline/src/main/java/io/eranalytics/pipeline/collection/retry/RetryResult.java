package io.eranalytics.pipeline.collection.retry;

/** Terminal action failure is a result; failure of attempt bookkeeping still propagates. */
public record RetryResult<T>(T value, RuntimeException failure) {}
