import sys, os, glob
from audiolib import decode, stats
for p in sys.argv[1:]:
    for f in sorted(glob.glob(p)):
        print(f"{os.path.basename(os.path.dirname(f))+'/'+os.path.basename(f):48s}", stats(decode(f)))
