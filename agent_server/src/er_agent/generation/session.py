from __future__ import annotations

from ..models.context import ContextSnapshot, ResolvedContext
from ..models.generation import Generation
from ..models.query import QueryRequest, QueryResponse
from ..models.summary import SummaryDraft
from .agent import SqlGenerationAgent
from .summary import ResultSummaryAgent


class QueryGenerationAgent:
    def __init__(self, sql: SqlGenerationAgent, summary: ResultSummaryAgent) -> None:
        self.sql = sql
        self.summary = summary

    def generate(self, request: QueryRequest, snapshot: ContextSnapshot) -> Generation:
        return self.sql.generate(request, snapshot)

    def repair(self, request: QueryRequest, context: ResolvedContext, sql: str, error: str) -> str:
        return self.sql.repair(request, context, sql, error)

    def summarize(self, response: QueryResponse) -> SummaryDraft:
        return self.summary.summarize(response)
