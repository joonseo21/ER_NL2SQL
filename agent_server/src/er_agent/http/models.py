"""Wire models validate HTTP inputs and document the existing domain response."""

from __future__ import annotations

from typing import Annotated, Any, Literal, Self

from pydantic import BaseModel, ConfigDict, Field, model_validator

from ..models.query import (
    PatchFilter,
    PatchMode,
    PatchVersion,
    QueryConditions,
    QueryRequest,
)

Text = Annotated[str, Field(min_length=1, pattern=r"\S")]
NonnegativeInt = Annotated[int, Field(ge=0)]
PositiveInt = Annotated[int, Field(ge=1)]


class WireModel(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class PatchVersionBody(WireModel):
    major: NonnegativeInt
    minor: NonnegativeInt


class PatchBody(WireModel):
    model_config = ConfigDict(
        json_schema_extra={
            "allOf": [
                {
                    "if": {"properties": {"mode": {"const": "exact"}}, "required": ["mode"]},
                    "then": {
                        "required": ["versions"],
                        "properties": {"versions": {"minItems": 1}},
                    },
                    "else": {"properties": {"versions": {"maxItems": 0}}},
                }
            ]
        }
    )
    mode: Literal["latest", "exact", "all"] = "latest"
    versions: list[PatchVersionBody] = Field(
        default_factory=list, json_schema_extra={"uniqueItems": True}
    )

    def to_domain(self) -> PatchFilter:
        return PatchFilter(
            PatchMode(self.mode), tuple(PatchVersion(v.major, v.minor) for v in self.versions)
        )

    @model_validator(mode="after")
    def validate_patch(self) -> Self:
        self.to_domain()
        return self


class ConditionsBody(WireModel):
    model_config = ConfigDict(
        json_schema_extra={
            "allOf": [
                {
                    "if": {
                        "required": ["tier_division"],
                        "properties": {"tier_division": {"type": "integer"}},
                    },
                    "then": {"required": ["tier"], "properties": {"tier": {"type": "string"}}},
                }
            ]
        }
    )
    patch: PatchBody = Field(default_factory=PatchBody)
    tier: Text | None = None
    tier_division: NonnegativeInt | None = None
    character_code: PositiveInt | None = None

    def to_domain(self) -> QueryConditions:
        return QueryConditions(
            patch=self.patch.to_domain(),
            tier=self.tier,
            tier_division=self.tier_division,
            character_code=self.character_code,
        )

    @model_validator(mode="after")
    def validate_conditions(self) -> Self:
        self.to_domain()
        return self


class QueryBody(WireModel):
    question: Text
    conditions: ConditionsBody = Field(default_factory=ConditionsBody)

    def to_domain(self) -> QueryRequest:
        return QueryRequest(self.question, self.conditions.to_domain())


class AppliedBody(WireModel):
    season_id: PositiveInt
    conditions: ConditionsBody

    @model_validator(mode="after")
    def resolved_patch(self) -> Self:
        if self.conditions.patch.mode == "latest":
            raise ValueError("Applied patch must resolve latest")
        return self


class CandidateBody(WireModel):
    category: Literal["character", "weapon", "item", "trait", "tactical"]
    code: PositiveInt
    name: Text


class ClarificationBody(WireModel):
    term: Text
    candidates: list[CandidateBody]


SampleUnit = Literal["participant_appearances", "games", "teams"]


class GroupEvidenceBody(WireModel):
    row_index: NonnegativeInt
    sample_size: NonnegativeInt | None
    sample_unit: SampleUnit | None
    warnings: list[Text]


class EvidenceBody(WireModel):
    sample_size: NonnegativeInt | None
    sample_unit: SampleUnit | None
    data_as_of: Annotated[str, Field(json_schema_extra={"format": "date-time"})] | None
    groups: list[GroupEvidenceBody]


class QueryReply(WireModel):
    model_config = ConfigDict(
        json_schema_extra={
            "allOf": [
                {
                    "if": {"properties": {"kind": {"const": "answer"}}},
                    "then": {
                        "properties": {
                            "sql": {"type": "string"},
                            "applied_conditions": {"type": "object"},
                            "clarifications": {"maxItems": 0},
                        }
                    },
                    "else": {
                        "properties": {
                            "sql": {"type": "null"},
                            "summary": {"type": "null"},
                            "columns": {"maxItems": 0},
                            "rows": {"maxItems": 0},
                            "message": {"type": "string"},
                        }
                    },
                },
                {
                    "if": {"properties": {"kind": {"const": "clarification"}}},
                    "then": {"properties": {"clarifications": {"minItems": 1}}},
                    "else": {"properties": {"clarifications": {"maxItems": 0}}},
                },
            ]
        }
    )
    kind: Literal["answer", "notice", "clarification"]
    question: Text
    log_id: Text
    elapsed_ms: NonnegativeInt
    applied_conditions: AppliedBody | None
    sql: Text | None
    columns: list[Text] = Field(json_schema_extra={"uniqueItems": True})
    rows: Annotated[list[dict[str, Any]], Field(max_length=200)]
    summary: Text | None
    message: Text | None
    examples: list[Text]
    clarifications: list[ClarificationBody]
    evidence: EvidenceBody | None
    warnings: list[Text]
    chart: None = None
    conversation_context: None = None

    @model_validator(mode="after")
    def validate_reply(self) -> Self:
        if len(set(self.columns)) != len(self.columns) or any(
            set(row) != set(self.columns) for row in self.rows
        ):
            raise ValueError("Result columns must be unique and match row keys")
        if self.kind == "answer":
            if self.sql is None or self.applied_conditions is None or self.clarifications:
                raise ValueError("Answer requires SQL, applied conditions and no unresolved names")
        elif (
            self.message is None
            or self.sql is not None
            or self.summary is not None
            or self.columns
            or self.rows
            or (self.kind == "clarification") != bool(self.clarifications)
        ):
            raise ValueError("Non-answer must contain a message and no query results")
        return self


class ValidationIssue(WireModel):
    path: list[str | int]
    message: Text


class ErrorDetail(WireModel):
    code: Literal[
        "unauthorized_internal_call", "invalid_request", "internal_error", "service_unavailable"
    ]
    message: Text
    issues: list[ValidationIssue]


class ErrorReply(WireModel):
    detail: ErrorDetail


class HealthReply(WireModel):
    status: Literal["ok"]
