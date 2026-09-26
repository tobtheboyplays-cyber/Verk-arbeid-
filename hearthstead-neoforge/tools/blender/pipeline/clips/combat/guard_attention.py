"""GUARD_ATTENTION -- see ceremony.py."""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ceremony  # noqa: E402

ceremony.run("GUARD_ATTENTION")
