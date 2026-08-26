#!/usr/bin/env python3
"""Static guard: a GameTest fixture that registers a live settlement must
also place a plaque block somewhere in the same file -- or BuildingManager's
sweep can reach every Building it constructs and will dissolve any of them
that has no real plaque at its plaquePos, silently, however many ticks later
the round-robin cursor happens to land on it (KF-014, KF-021).

Run directly: python3 qa/scripts/check_fixture_plaques.py
Wired into `tools/hearthstead-qa doctor` so this fails at check time -- a
few seconds into a suite run -- instead of 4880 ticks into some unrelated
test going unexplainedly IDLE, which is how KF-021 actually cost a night.

What it checks, per hearthstead-neoforge gametest/*.java file:
  - does it call `.settlements.put(` -- register a settlement into
    SettlementSavedData? That is the ONLY thing that makes anything in the
    file reachable by BuildingManager's sweep at all: a bare Settlement
    object nobody registers is invisible to it (RaidPressureGameTests and
    the chain tests in ChainsGameTests/WarehouseGameTests rely on exactly
    this today, deliberately, and are correctly excluded here as long as
    they stay that way).
  - does it call `new Building(` -- hand-build a Building?
  - does it place a plaque anywhere (`PLAQUE.get()`)?

A file that does the first two and not the third is one line away from
KF-021 happening again: registering the settlement is enough by itself to
put every Building the file already constructs on the sweep's list.

This is deliberately a SAME-FILE, proportionate check, not a call-graph
analysis: it does not trace which Building corresponds to which plaque
placement, only whether the file has a plaque placement at all. A fixture
that routes construction through GameTestFixtures.register() or
registerWithBounds() never needs a local `new Building(` at all -- those
methods place the plaque internally -- which is why GameTestFixtures.java
itself is excluded: it is the implementation of the guarantee, not a
fixture consuming it.
"""
import re
import sys
from pathlib import Path

DEFAULT_DIR = (
    Path(__file__).resolve().parents[2]
    / "hearthstead-neoforge" / "src" / "main" / "java" / "com" / "hearthstead" / "gametest"
)

REGISTERS_SETTLEMENT = re.compile(r"\.settlements\.put\(")
BUILDS_BUILDING = re.compile(r"\bnew Building\(|\bnew com\.hearthstead\.settlement\.Building\(")
# The "this file does hang a plaque" signal has to be narrow, because a
# false POSITIVE here is silent: the file is waved through and KF-021 comes
# back. The first version was `PLAQUE\.get\(\)`, which an adversarial review
# found matches two things it should not:
#   * `ModItems.PLAQUE.get()` -- the plaque ITEM, which is not a hung plaque
#     at all (real instance: AdvancementGameTests.java).
#   * any occurrence inside a comment, including a comment explaining that
#     this file deliberately does NOT hang one.
# So: require the BLOCK, or a call into the shared fixture helper that hangs
# one and asserts it -- and strip comments before matching, so a file can
# discuss the plaque without appearing to place it.
PLACES_PLAQUE = re.compile(
    r"ModBlocks\.PLAQUE\b"
    r"|GameTestFixtures\.(register|registerWithBounds|placePlaque)\s*\(")

BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
LINE_COMMENT = re.compile(r"//[^\n]*")


def code_only(text: str) -> str:
    """The file with comments removed, so a comment can never satisfy or
    trip any pattern above. Deliberately crude -- it does not understand
    string literals containing "//" -- because the only cost of over-
    stripping here is a file being flagged that did not need to be, and a
    false alarm is loud while a false pass is silent."""
    return LINE_COMMENT.sub("", BLOCK_COMMENT.sub("", text))

EXEMPT_FILES = {"GameTestFixtures.java"}


def offenders(directory: Path) -> list[str]:
    """Names of every fixture file that registers a live settlement,
    hand-builds a Building, and never places a plaque anywhere in it."""
    found = []
    for path in sorted(directory.glob("*.java")):
        if path.name in EXEMPT_FILES:
            continue
        text = code_only(path.read_text(encoding="utf-8"))
        if (REGISTERS_SETTLEMENT.search(text)
                and BUILDS_BUILDING.search(text)
                and not PLACES_PLAQUE.search(text)):
            found.append(path.name)
    return found


def main() -> int:
    directory = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_DIR
    if not directory.is_dir():
        print(f"check_fixture_plaques: no such directory: {directory}", file=sys.stderr)
        return 2

    scanned = sorted(directory.glob("*.java"))
    bad = offenders(directory)

    if not bad:
        print(f"check_fixture_plaques: PASS ({len(scanned)} files scanned)")
        return 0

    print(f"check_fixture_plaques: FAIL ({len(bad)} of {len(scanned)} files)")
    for name in bad:
        print(
            f"  {name}: registers a live settlement (`.settlements.put(`) "
            f"and hand-builds a Building (`new Building(`) but this file "
            f"places no plaque anywhere (`PLAQUE.get()`). Once the "
            f"settlement is registered, BuildingManager's sweep (one "
            f"building per 20 ticks, across every registered settlement) "
            f"can reach every Building this file constructs, and will "
            f"dissolve any of them that has no real PlaqueBlock at its "
            f"plaquePos -- correctly, because 'no plaque, no building' is "
            f"a permanent invariant (D-005), but SILENTLY: the symptom is "
            f"an unrelated-looking settler stuck IDLE, possibly thousands "
            f"of ticks later, in a DIFFERENT test's failure message. This "
            f"is exactly the KF-014/KF-021 bug. Fix it by routing the "
            f"Building through GameTestFixtures.register(helper, s, type, "
            f"x, z) or GameTestFixtures.registerWithBounds(...) -- both "
            f"place the plaque for you and assert it is really there -- "
            f"or, if this file's Building genuinely needs bespoke shape "
            f"GameTestFixtures cannot express, call "
            f"GameTestFixtures.placePlaque(helper, plaqueRel) yourself "
            f"right where the Building is constructed. See "
            f"GameTestFixtures.java's class doc and "
            f"docs/project/KNOWN_FAILURES.md KF-021 for the full story."
        )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
