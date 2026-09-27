"""SHIELD_BLOCK -- see holds.py."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import holds  # noqa: E402

holds.run("SHIELD_BLOCK")
