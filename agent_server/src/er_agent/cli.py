from __future__ import annotations

import argparse
import json

from .bootstrap import MANUAL_QUERY_NAMES, run_manual, run_query
from .models.query import QueryRequest, ResponseKind
from .settings import Settings
from .sql.guard import guard_sql


def main() -> None:
    parser = argparse.ArgumentParser(description="ER analytics read-only SQL runner")
    subparsers = parser.add_subparsers(dest="command", required=True)

    ask_parser = subparsers.add_parser("ask", help="Generate and execute SQL with Gemini")
    ask_parser.add_argument("question")

    manual_parser = subparsers.add_parser("manual", help="Run a predefined read-only smoke query")
    manual_parser.add_argument("query", choices=MANUAL_QUERY_NAMES)

    guard_parser = subparsers.add_parser("guard", help="Validate SQL without a database")
    guard_parser.add_argument("sql")

    arguments = parser.parse_args()
    settings = Settings.from_env()

    if arguments.command == "ask":
        response = run_query(QueryRequest(arguments.question), settings)
        print(json.dumps(response.to_dict(), ensure_ascii=False, indent=2))
        if response.kind != ResponseKind.ANSWER:
            raise SystemExit(1)
        return
    elif arguments.command == "manual":
        result = run_manual(arguments.query, settings)
    else:
        result = {"sql": guard_sql(arguments.sql).sql}
    print(json.dumps(result, ensure_ascii=False, default=str, indent=2))


if __name__ == "__main__":
    main()
