# Known Issues — what you might actually run into

Ranked by how likely you are to hit it in a normal first session, not by
how it looks in the code. Everything below was checked against the exact
source this jar was built from; it is not a dump of the project's whole
bug tracker (`docs/project/KNOWN_FAILURES.md`, 35 entries, mostly
internal test-harness findings you will never see).

For each item: what you'll see, whether it's cosmetic or functional, and
whether there's a workaround.

---

## 1. Sugar cane farming looks borrowed, because it is

**What you'll see:** a farmer planting or harvesting sugar cane uses the
exact same animation as planting/harvesting wheat — pressing a seed into
soil, pulling a wheat head. It doesn't match the action.

**Cosmetic or functional:** cosmetic only. The farmer genuinely plants and
harvests cane, the item lands in storage, nothing freezes or skips.

**Workaround:** none needed — it works, it just doesn't look bespoke yet.
This is the project's own top-priority animation fix, already queued.

---

## 2. A few buildings validate "complete" and then do nothing

**What you'll see:** the School, the Market, the Well and the Infirmary
can each be built to spec — the Plaque will scan the room, find
everything present, and glow green. Nothing happens after that. No trade
can be hired for any of the four, and nothing in the game reacts to any
of them existing. The Infirmary is the one most likely to catch you out:
it was made easily reachable earlier tonight (a plain cauldron instead
of a Nether-only brewing stand), which makes it look finished — it
isn't. Building it will not heal anyone yet.

**Cosmetic or functional:** functional gap — these are unfinished
systems wearing a finished building shell. The Plaque is not lying about
the room being correct; it's just that "correct room" is all four
currently do.

**Workaround:** none. Don't invest heavily in these four yet; everything
else you can build (25 of 33 building types have a hireable trade) is
real, including the newer Pasture, Fishery and Hunter's Lodge.

---

## 3. Research materials are a manual errand, forever

**What you'll see:** the Architect's Study never gets a courier delivery.
Every research project's paper and domain items (4 paper + 12-24 items
per project) has to be carried there by hand, every time, no matter how
built-out your logistics are everywhere else.

**Cosmetic or functional:** functional — a real, permanent gap, not a
transient bug. The courier system's restock route only services
buildings with a production recipe table, and research was deliberately
built without one (a project is a multi-day undertaking, not a batch).

**Workaround:** budget the walk. It's a real chore on a spread-out
village, not a bug you're imagining.

---

## 4. The Brewery needs a Nether trip, with no warning

**What you'll see:** the Brewery's Build Plan looks cheap (sugar + a
glass bottle) and reads as an easy early building. It isn't — the room
requires a real vanilla brewing stand, which needs a blaze rod. Nothing
in the plan or the in-game text says so before you're standing in an
otherwise-finished room wondering why it won't validate.

**Cosmetic or functional:** functional wall, and it's a deliberate design
choice (a brewery wanting a brewing stand is a fair ask) — but
undocumented anywhere in the game itself.

**Workaround:** treat the Brewery as post-Nether content. Everything else
tagged "early tier" in the game is genuinely reachable without leaving
the Overworld — the Infirmary was moved off this same wall earlier
tonight (it now wants a plain cauldron instead).

---

## 5. Two screens can't be closed with the mouse at GUI Scale 3+

**What you'll see:** the **Research** screen and the **Handbook** are
taller than the screen area at GUI Scale 3 or 4 on a modest window. The
panel is centred, so it's clipped at both edges at once: the title goes
off the top and the footer buttons — Close included — go off the bottom,
with nothing on screen saying they exist.

**Cosmetic or functional:** functional, but fully recoverable — see the
workaround. Measured, not estimated: at GUI Scale 3 the viewport is 240px
tall, and the Research panel needs 338 (98px too tall) while the Handbook
needs 264 (24px too tall). At GUI Scale 4 it's 158px and 84px
respectively. The Settler sheet had the same defect and it **is fixed** —
it anchors to the top and takes the mouse wheel now. Research and the
Handbook were not given the same fix because both already use the wheel
to scroll their own inner lists, so it's a real design decision about
which scroll wins when, and that wasn't something to decide blind at the
last minute.

**Workaround:** **Escape closes both screens normally.** Or set GUI Scale
to 2 (Options → Video Settings), where every screen in the mod fits with
room to spare. The Plaque and Storage screens are fine at Scale 3; only
Storage is safe at every scale.

---

## 6. A winning fire or blood raid doesn't earn its captain a title

**What you'll see:** raid captains can earn epithets ("the Grain-Thief",
"Larder's Bane") — but only from a raid whose objective was stealing
grain. A captain who burns half your village or hurts your settlers in a
raid, even a raid your settlement loses, currently cannot earn a title
for it, though the in-game text describes titles for exactly that.

**Cosmetic or functional:** a real, if narrow, functional gap — flagged
and deliberately left open rather than rushed. It does not affect whether
a raid succeeds or fails, only whether the captain's own story tracks it.

**Workaround:** none. Purely flavor-layer.

---

## 7. The hire celebration puts an arm through the torso

**What you'll see:** hire a settler and they celebrate -- and on the first
frames an arm swings past vertical and clips through the chest.

**Cosmetic or functional:** purely cosmetic, and only on the celebration.
The cause is that the celebration clip was authored to sit on top of a light
idle breath layer, but the activity it expects is never actually set by any
code, so it lands on top of a full-body trade idle instead and the two sum.
Found today by an animation-reachability sweep; not fixed, because the fix
is a real decision about which layer wins and that was not something to
rush hours before you sat down with it.

**Workaround:** none needed. It passes in about a second.

---

## Already found and fixed tonight, mentioned so you don't rediscover them

These were real bugs during tonight's build and are confirmed fixed in
the exact jar you're running — listed only so a stray screenshot from an
earlier build, or a doc that hasn't caught up, doesn't confuse you:

- Herder, Fisher and Hunter output used to sit in their own chests
  forever with no courier ever collecting it. Fixed — all three are now
  on the same collection route as everything else.
- A courier could get stuck endlessly shuttling the same stack of stone,
  iron or wool back and forth between a warehouse and the building that
  both makes and consumes it. Fixed.
- A guard's armor used to vanish permanently on death instead of
  returning to the armoury. Fixed — it's recovered the same way a rank
  change already recovered it.
- Appointing a new mayor while the previous one was in an unloaded chunk
  used to skip the handover cost. Fixed.
- A "Ransom" raid objective existed in the UI and could earn a captain a
  title, but no code ever actually took a settler hostage — the game was
  reporting an event that never happened. Removed rather than left lying.
- The lumberjack froze on every tree. After felling his first log he stood
  motionless in the bare rig for the rest of the trunk -- about twelve
  seconds of a statue per oak, on the first worker anyone hires. Two causes
  in three lines: the stoop animation was started on the server copy no
  screen ever renders, and the same call parked him in an activity with no
  animation at all that nothing ever cleared. Fixed.
- A leaping guard pedalled through the air in the plain walk cycle. Same
  root cause as the two above -- the authored leap was never sent to the
  client. Fixed.
- Raiders never sprinted. A charging raider played its creeping stalk
  animation the whole way in, through the kill — filmed, then traced: the
  animation asked the raider for its combat target, and that is server-only
  information the client never receives, so the sprint could never trigger
  for anyone. Fixed — the server now tells the client whether a raider is
  charging, and skirmishers sprint. (This was listed here as an unconfirmed
  cosmetic maybe; it was real, and it is gone.)

---

## Not covered here

Two large, honest categories are deliberately out of scope for this
document because you are unlikely to reach them in a first session:

- **Late-tier grinds** — a Smithy's 31-ingot anvil, the Library's 81
  paper + 27 leather bill — are real costs, not bugs, and are called out
  in `DEMO_README.md`'s "What NOT to expect" section instead of here.
- **Raid pacing and balance** (how fast pressure builds, whether a fire
  raid's own attack behavior reads as aggressive enough) — plausible but
  not confirmed for tonight's build; if you get raided in your first
  session and something about it feels off, that's new information, not
  a known issue we're hiding from you.
