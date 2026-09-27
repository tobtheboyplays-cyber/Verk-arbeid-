from pathlib import Path
import shutil, subprocess
root = Path(r"C:\Users\tobia\OneDrive\Documents\ChatGPT\MINECRAFT MOD\Verk-arbeid-\hearthstead-neoforge")
stage = root / "PROJECT_STATE" / "rest-panic-recovery-20260912"
proposed = stage / "proposed"
files = [
  "src/main/java/com/hearthstead/entity/ai/RestAtNightGoal.java",
  "src/main/java/com/hearthstead/gametest/HearthsteadGameTests.java",
]
for rel in files:
    out = proposed / rel
    out.parent.mkdir(parents=True, exist_ok=True)
    shutil.copy2(root / rel, out)

def replace(rel, old, new):
    p = proposed / rel
    text = p.read_text(encoding="utf-8")
    if old not in text:
        raise RuntimeError(f"Missing anchor in {rel}: {old[:100]!r}")
    p.write_text(text.replace(old, new, 1), encoding="utf-8", newline="\n")

replace("src/main/java/com/hearthstead/entity/ai/RestAtNightGoal.java",
"""    private boolean roughNightCharged;
    private int repathTimer;
    private int claimRetryTimer;""",
"""    private boolean roughNightCharged;
    private int repathTimer;
    private int claimRetryTimer;
    /** A valid bed can still have no physical approach. Do not reclaim that
     * same blocked bed every rest tick; rough rest remains available while a
     * player repairs the route. */
    private long rejectedBedRetryAt;""")

replace("src/main/java/com/hearthstead/entity/ai/RestAtNightGoal.java",
"""    private void claimBedIfNeeded() {
        if (settler.getClaimedBed() != null
            || !(settler.level() instanceof ServerLevel serverLevel)) {
            return;
        }
        Settlement settlement = settler.settlement();
        if (settlement != null) {
            BlockPos free = BuildingManager.findFreeBed(serverLevel, settlement);
            if (free != null) {
                settler.claimBed(free);
            }
        }
    }""",
"""    private void claimBedIfNeeded() {
        if (settler.getClaimedBed() != null
            || !(settler.level() instanceof ServerLevel serverLevel)
            || serverLevel.getGameTime() < rejectedBedRetryAt) {
            return;
        }
        Settlement settlement = settler.settlement();
        if (settlement != null) {
            BlockPos free = BuildingManager.findFreeBed(serverLevel, settlement);
            if (free != null) {
                settler.claimBed(free);
            }
        }
    }""")

replace("src/main/java/com/hearthstead/entity/ai/RestAtNightGoal.java",
"""    private void path() {
        BlockPos target = restTarget();
        if (target != null) {
            settler.getNavigation().moveTo(target.getX() + 0.5, target.getY() + 1,
                target.getZ() + 0.5, 0.9);
        }
    }""",
"""    private void path() {
        BlockPos target = restTarget();
        if (target == null) {
            return;
        }
        BlockPos claimed = settler.getClaimedBed();
        // Sleeping is handled by tick's close-contact branch. Avoid proving a
        // path from the bed itself, where vanilla navigation may already be
        // marked done because the sleeper is correctly in place.
        if (claimed != null && claimed.equals(target)
            && settler.blockPosition().distSqr(claimed) > 4.5
            && !pathToRestTarget(target)) {
            // A real BedBlock alone is not a route contract. Free this
            // resident claim before falling back to the existing hearth rest;
            // otherwise critical exhaustion owns MOVE forever while energy
            // cannot recover. The cooldown permits a later repair/re-survey
            // without re-claiming an unchanged blocked staircase every tick.
            settler.releaseBed();
            rejectedBedRetryAt = settler.level().getGameTime() + 600L;
            claimRetryTimer = 40;
            resting = false;
            settler.recordRouteFailure("rest_bed_unreachable");
            path();
            return;
        }
        pathToRestTarget(target);
    }

    /** Path to the same feet cell the old moveTo call addressed, but expose
     * a failed route so an unreachable claimed bed can yield safely. */
    private boolean pathToRestTarget(BlockPos target) {
        BlockPos stand = target.above();
        var path = settler.getNavigation().createPath(stand, 0);
        if (path == null || !path.canReach()) {
            return false;
        }
        settler.getNavigation().moveTo(path, 0.9);
        return true;
    }""")

test = """    /**
     * A valid BedBlock behind a sealed upstairs route is not a valid recovery
     * destination. Critical exhaustion must release only that unreachable
     * claim and use the existing rough-hearth rest, rather than owning MOVE
     * forever at zero energy.
     */
    @GameTest(template = "empty16", timeoutTicks = 80, batch = "night_sleep")
    public void unreachableClaimedBedReleasesToRoughHearthRest(GameTestHelper helper) {
        helper.getLevel().setDayTime(16000);
        buildArena(helper, 16, 16);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement settlement = makeSettlement(helper, hearthRel, 12);
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }
        // The bed survives as a real block, but every contact cell and its
        // above-cell are sealed: it reproduces an upstairs route with no
        // traversable approach, not a broken-bed branch.
        BlockPos bedRel = new BlockPos(10, 1, 10);
        helper.setBlock(bedRel, Blocks.RED_BED);
        helper.setBlock(bedRel.above(), Blocks.STONE_BRICKS);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                helper.setBlock(bedRel.offset(dx, 0, dz), Blocks.STONE_BRICKS);
                helper.setBlock(bedRel.offset(dx, 1, dz), Blocks.STONE_BRICKS);
            }
        }
        SettlerEntity settler = boundSettler(helper, settlement, new BlockPos(4, 1, 3));
        settler.setNoAi(true);
        settler.claimBed(helper.absolutePos(bedRel));
        settler.setEnergy(0.0F);

        RestAtNightGoal goal = new RestAtNightGoal(settler);
        helper.assertTrue(goal.canUse(), "zero-energy settler with a Hearth must begin real rest");
        goal.start();
        goal.tick();

        helper.assertTrue(settler.getClaimedBed() == null
                && settler.getActivity() == SettlerActivity.RESTING
                && !settler.isSleeping()
                && settler.routeFailureNote().startsWith("rest_bed_unreachable@"),
            "an unreachable intact bed must release its claim and recover at the Hearth"
                + " [claim=" + settler.getClaimedBed()
                + " activity=" + settler.getActivity()
                + " sleep=" + settler.isSleeping()
                + " route=" + settler.routeFailureNote() + "]");
        goal.stop();
        helper.succeed();
    }

"""
replace("src/main/java/com/hearthstead/gametest/HearthsteadGameTests.java",
"""    @GameTest(template = "empty16", timeoutTicks = 900, batch = "night_sleep")
    public void settlerSleepsInClaimedBed(GameTestHelper helper) {""",
test + """    @GameTest(template = "empty16", timeoutTicks = 900, batch = "night_sleep")
    public void settlerSleepsInClaimedBed(GameTestHelper helper) {""")

chunks=[]
for rel in files:
    proc=subprocess.run(["git","diff","--no-index","--binary","--src-prefix=a/","--dst-prefix=b/",
                         "--",str(root/rel),str(proposed/rel)],cwd=root,text=True,
                        encoding="utf-8",stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    if proc.returncode not in (0,1):
        raise RuntimeError(proc.stderr)
    lines=[]
    for line in proc.stdout.splitlines():
        if line.startswith("diff --git "):
            lines.append(f"diff --git a/{rel} b/{rel}")
        elif line.startswith("--- "):
            lines.append(f"--- a/{rel}")
        elif line.startswith("+++ "):
            lines.append(f"+++ b/{rel}")
        else:
            lines.append(line)
    chunks.append("\n".join(lines))
(stage/"rest-at-night-unreachable-bed.patch").write_text("\n".join(chunks)+"\n",
    encoding="utf-8",newline="\n")

