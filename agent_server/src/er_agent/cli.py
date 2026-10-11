from __future__ import annotations

import argparse
import json
import sys

from .bootstrap import MANUAL_QUERY_NAMES, QueryFailed, run_manual, run_question
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
        try:
            result = run_question(arguments.question, settings)
        except QueryFailed as exception:
            print(str(exception), file=sys.stderr)
            raise SystemExit(1) from None
    elif arguments.command == "manual":
        result = run_manual(arguments.query, settings)
    else:
        result = {"sql": guard_sql(arguments.sql).sql}
    print(json.dumps(result, ensure_ascii=False, default=str, indent=2))


if __name__ == "__main__":
    main()
