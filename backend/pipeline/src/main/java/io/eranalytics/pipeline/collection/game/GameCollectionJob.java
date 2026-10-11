package io.eranalytics.pipeline.collection.game;

public record GameCollectionJob(long id, String jobType, String targetKey, int attempts) {}
