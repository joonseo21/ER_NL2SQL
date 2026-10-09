package io.eranalytics.pipeline.collection.model;

import io.eranalytics.pipeline.erapi.dto.GameResultDto;

public record GameData(GameResultDto fields, int teamCount) {
}
