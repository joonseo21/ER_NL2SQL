package io.eranalytics.pipeline.collection;

public record QueueJob(long id, String jobType, String targetKey, int attempts) {
}
