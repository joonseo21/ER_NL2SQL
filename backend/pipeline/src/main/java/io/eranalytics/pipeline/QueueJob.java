package io.eranalytics.pipeline;

record QueueJob(long id, String jobType, String targetKey, int attempts) {
}
