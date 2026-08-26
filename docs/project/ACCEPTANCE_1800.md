# Acceptance criteria — the owner's 18:00 test

*Named by the owner, 11:40 Oslo: "Alle start jobbene skal funke. Vakt tårn
skal fungere og alle animasjoner. Lager og courier skal funke og UI på alle
settlers."*

These five are the test. Everything else in the backlog is now secondary,
including work already in flight. **New feature work freezes immediately** —
the remaining hours go to proving these, and to fixing whatever proving them
uncovers.

| # | criterion | what "works" means, concretely | proof required |
|---|---|---|---|
| 1 | **Every starting job works** | Each profession a player can reach early can be HIRED through `Employment.hire`, walks to its post, performs its work, and puts real output in a real chest — through the normal goal path, not by driving `Production` directly | chest-true GameTest per profession |
| 2 | **The watchtower works** | An archer can be hired at a watchtower, takes real arrows from a real chest, and actually looses them at a raider — Power Shot and Triple Shot included, on their real cadence | chest-true GameTest + seen in the client |
| 3 | **All animations work** | Every clip that exists plays when its activity is active, on the right body, without summing into another clip. 72 clips: 64 settler, 8 raider | seen in a running client — no static check substitutes |
| 4 | **Warehouse and courier work** | All four routes move real items end to end: restock, food delivery, output collection, consolidation. Nothing strands, nothing shuttles, the hearth gets fed | chest-true GameTest per route + one live watch |
| 5 | **Settler UI works for every profession** | Right-click any settler of any of the 26 professions and the sheet opens, correct, complete — portrait, name, profession, needs, traits, no missing key, no blank field | seen in a running client, every profession |

## The rule that decides what ships

**Three of these five cannot be proven headless.** Animations, the archer's
loose, and the settler sheet all need a client with eyes on it. That makes
the harness worker's input-decay fix the gate on criteria 2, 3 and 5 — not a
convenience.

If the client cannot be driven reliably in time, the honest outcome is to
tell the owner which criteria were proven headless and which were not, and
let him judge them himself in the first ten minutes. **What must not happen
is reporting all five as met when three were only read.** That is the failure
mode this whole project has spent the night learning to refuse.

## Order of work

1. Criteria 1 and 4 headless, now — they are provable without a client and
   they are the deepest systems.
2. Criteria 2, 3, 5 the moment the client is drivable.
3. Anything found broken gets fixed ahead of anything not on this list.
