"""GUARD_WALK -- see loco.py."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import loco  # noqa: E402

loco.run("GUARD_WALK")
