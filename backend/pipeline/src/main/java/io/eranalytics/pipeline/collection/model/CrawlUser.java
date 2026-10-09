package io.eranalytics.pipeline.collection.model;

/** Internal frontier snapshot. Never used as an API/log response. */
public record CrawlUser(long userId, int seasonId, String nickname, Long crawledNewestGameId) {}
