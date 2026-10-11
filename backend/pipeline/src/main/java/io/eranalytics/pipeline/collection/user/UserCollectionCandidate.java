package io.eranalytics.pipeline.collection.user;

/** Internal frontier snapshot. Never used as an API/log response. */
public record UserCollectionCandidate(
        long userId, int seasonId, String nickname, Long crawledNewestGameId) {}
