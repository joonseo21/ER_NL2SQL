"""Prompt construction and decoding for normal generation and SQL repair."""

from __future__ import annotations

import json
from dataclasses import asdict

from ..models.context import ContextSnapshot, ResolvedContext
from ..models.generation import Generation
from ..models.query import QueryRequest
from ..query.ports import QueryRecorder
from .decoder import parse_generation
from .ports import ModelCaller
from .prompts import build_generation_prompt, build_repair_prompt
from .schema import GENERATION_SCHEMA


class SqlGenerationAgent:
    def __init__(self, caller: ModelCaller, recorder: QueryRecorder) -> None:
        self.caller = caller
        self.recorder = recorder

    def generate(self, request: QueryRequest, snapshot: ContextSnapshot) -> Generation:
        text = self.caller.generate(
            build_generation_prompt(request, snapshot), "generate", GENERATION_SCHEMA
        )
        generation = parse_generation(text)
        self.recorder.append(
            "generation_result",
            intent=json.loads(text)["intent"],
            generated_sql=generation.sql,
            sample=asdict(generation.sample) if generation.sample else None,
        )
        return generation

    def repair(self, request: QueryRequest, context: ResolvedContext, sql: str, error: str) -> str:
        return self.caller.generate(build_repair_prompt(request, context, sql, error), "repair")
