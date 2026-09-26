"""HAMMER_ANVIL__v2 flavour variant (see author_hammer_anvil.py): 2 cycles, blows at 0.45 / 1.45 s."""
import os
import runpy

os.environ["HS_VARIANT"] = "v2"
try:
    runpy.run_path(os.path.join(os.path.dirname(os.path.abspath(__file__)), "author_hammer_anvil.py"),
                   run_name="__main__")
finally:
    os.environ.pop("HS_VARIANT", None)
