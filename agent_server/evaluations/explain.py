"""EXPLAIN (never ANALYZE) the SQL attempts recorded in a result directory.

Read-only diagnostics: nothing is executed. Uses the agent's own agent_ro, read-only,
5 second connection and the same target selection as evaluations.run.
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path

from er_agent.database.connection import _analytics_connection

from .run import selected_settings


def sql_attempts(path: Path) -> list[dict]:
    events = (json.loads(line) for line in path.read_text(encoding="utf-8").splitlines())
    return [event for event in events if event.get("event") == "sql_attempt"]


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path, help="A result directory with N.jsonl files")
    parser.add_argument("--target", choices=("production", "snapshot"), required=True)
    parser.add_argument("--snapshot-id")
    parser.add_argument("--case", type=int, action="append", help="N of N.jsonl (default: all)")
    parser.add_argument("--max-lines", type=int, default=60, help="Plan lines to print per SQL")
    arguments = parser.parse_args()
    settings = selected_settings(arguments.target, arguments.snapshot_id)
    files = (
        [arguments.directory / f"{number}.jsonl" for number in arguments.case]
        if arguments.case
        else sorted(arguments.directory.glob("*.jsonl"), key=lambda path: int(path.stem))
    )
    for path in files:
        for event in sql_attempts(path):
            print(
                f"===== {path.name} attempt {event['attempt']} (recorded {event['elapsed_ms']} ms)"
            )
            try:
                with _analytics_connection(settings.database_url) as connection:
                    rows = connection.execute(
                        "EXPLAIN " + event["guarded_sql"], event["bindings"]
                    ).fetchall()
            except Exception as exception:
                print("EXPLAIN failed:", type(exception).__name__)
                continue
            plan = [str(next(iter(row.values()))) for row in rows]
            print("\n".join(plan[: arguments.max_lines]))
            if len(plan) > arguments.max_lines:
                print(f"... {len(plan) - arguments.max_lines} more lines")


if __name__ == "__main__":
    main()
