"""Resource roots stay stable when responsibility packages move."""

from pathlib import Path

AGENT_ROOT = Path(__file__).resolve().parents[2]
REPOSITORY_ROOT = AGENT_ROOT.parent
