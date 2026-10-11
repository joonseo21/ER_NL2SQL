package io.eranalytics.pipeline.erapi.dto;

/** Ranked user returned by the ER ranking endpoint. */
public record RankedUser(String nickname, int mmr) {}
