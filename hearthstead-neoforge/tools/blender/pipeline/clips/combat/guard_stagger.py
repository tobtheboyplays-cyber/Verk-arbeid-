"""GUARD_STAGGER (guard moveset) -- see moves.py for the timing contract."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import strike_runner  # noqa: E402

strike_runner.run("GUARD_STAGGER")
