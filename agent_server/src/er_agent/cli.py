from __future__ import annotations

import argparse
import json
import sys

from google.genai import errors as genai_errors

from .database import execute_select
from .guard import guard_sql
from .nl2sql import run_question
from .settings import Settings


MANUAL_QUERIES = {
    "game-count": "SELECT count(*) AS game_count FROM games",
    "character-picks": """
        SELECT character_num, count(*) AS picks
        FROM participants GROUP BY character_num ORDER BY picks DESC LIMIT 20
    """,
    "ranker-average-rank": """
        SELECT is_ranker, avg(game_rank) AS average_rank, count(*) AS samples
        FROM participants GROUP BY is_ranker ORDER BY is_ranker DESC
    """,
}


def main() -> None:
    parser = argparse.ArgumentParser(description="ER analytics read-only SQL runner")
    subparsers = parser.add_subparsers(dest="command", required=True)

    ask_parser = subparsers.add_parser("ask", help="Generate and execute SQL with Gemini")
    ask_parser.add_argument("question")

    manual_parser = subparsers.add_parser("manual", help="Run a predefined read-only smoke query")
    manual_parser.add_argument("query", choices=MANUAL_QUERIES)

    guard_parser = subparsers.add_parser("guard", help="Validate SQL without a database")
    guard_parser.add_argument("sql")

    arguments = parser.parse_args()
    settings = Settings.from_env()

    if arguments.command == "ask":
        try:
            result = run_question(arguments.question, settings)
        except genai_errors.APIError as exception:
            print(
                f"Gemini API error ({exception.code} {exception.status}): {exception.message}",
                file=sys.stderr,
            )
            raise SystemExit(1) from None
    elif arguments.command == "manual":
        settings.require_database()
        result = execute_select(settings.database_url, guard_sql(MANUAL_QUERIES[arguments.query]).sql)
    else:
        result = {"sql": guard_sql(arguments.sql).sql}
    print(json.dumps(result, ensure_ascii=False, default=str, indent=2))


if __name__ == "__main__":
    main()
