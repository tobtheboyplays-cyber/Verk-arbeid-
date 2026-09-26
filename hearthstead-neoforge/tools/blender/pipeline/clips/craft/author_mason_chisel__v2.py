"""MASON_CHISEL__v2 flavour variant (see author_mason_chisel.py): 2 base cycle(s), same contact ticks every cycle."""
import os
import runpy

os.environ["HS_VARIANT"] = "v2"
try:
    runpy.run_path(os.path.join(os.path.dirname(os.path.abspath(__file__)), "author_mason_chisel.py"), run_name="__main__")
finally:
    os.environ.pop("HS_VARIANT", None)
