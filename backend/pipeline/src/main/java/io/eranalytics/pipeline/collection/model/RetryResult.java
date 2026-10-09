package io.eranalytics.pipeline.collection.model;

/** Terminal action failure is a result; failure of attempt bookkeeping still propagates. */
public record RetryResult<T>(T value, RuntimeException failure) {}
