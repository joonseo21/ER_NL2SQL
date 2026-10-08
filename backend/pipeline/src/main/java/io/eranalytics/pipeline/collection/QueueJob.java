package io.eranalytics.pipeline.collection;

record QueueJob(long id, String jobType, String targetKey, int attempts) {
}
