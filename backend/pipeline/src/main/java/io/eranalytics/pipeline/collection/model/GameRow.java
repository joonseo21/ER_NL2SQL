package io.eranalytics.pipeline.collection.model;

import java.time.OffsetDateTime;

public record GameRow(long gameId, Integer seasonId, Integer matchingMode, Integer matchingTeamMode,
               Integer versionSeason, Integer versionMajor, Integer versionMinor,
               OffsetDateTime startDtm, String serverName) {
}
