"""Research: compare 4 prompt styles x 2 takes on two sounds (anvil hit, axe chop)."""
import os, sys, json
from concurrent.futures import ThreadPoolExecutor
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen
SOUNDS = {
 "anvil": ("a blacksmith's hammer striking a steel anvil once", "hammer hit on steel anvil"),
 "chop":  ("an axe chopping into a log once", "axe chop into a wooden log"),
}
def styles(desc, short):
    return {
     "A_plain":  (desc[0].upper() + desc[1:], 0.5),
     "B_game":   (f"Single {short}, short video game sound effect, dry, close, no reverb, no music", 0.5),
     "C_foley":  (f"Foley, {short}, one single hit, close mic, dry, mono, tight fast decay, no room, no music", 0.7),
     "D_mc":     (f"Simple blocky video game sound like Minecraft: {short}, one hit, slightly lo-fi, crunchy, short and punchy, no reverb", 0.4),
    }
led = gen._load_ledger(); s = gen._session()
jobs = []
for k, (desc, short) in SOUNDS.items():
    for st, (p, infl) in styles(desc, short).items():
        for i in range(2):
            jobs.append((f"_test/{k}_{st}", i, p, infl))
def run(j):
    ev, i, p, infl = j
    path, c = gen.generate(s, led, ev, i, p, 0.6, infl)
    print(ev, i, c, flush=True)
with ThreadPoolExecutor(4) as ex: list(ex.map(run, jobs))
print("total", led["total"])
