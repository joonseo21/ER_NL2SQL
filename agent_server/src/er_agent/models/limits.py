"""Shared result contract; independent of SQL parsing and execution."""

MAX_ROWS = 200
# Per-statement limit for analytics SQL (spec D-15, raised from 5 seconds on 2026-10-17).
SQL_TIMEOUT_SECONDS = 20
