package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.RaiderHuntGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * RAIDER-HUNT: the BLOD objective's own goal ({@link RaiderHuntGoal}) --
 * see its class doc for the gap this closes (nothing previously moved a
 * BLOD raider that had not already, itself, visually acquired a target).
 *
 * <p>These pin the three things a bounded scan must prove: it finds the
 * nearest candidate inside its bound, it never reaches past that bound, and
 * it never picks up a settler that is not this raider's own war
 * ({@link RaiderEntity#isMyWar}) even when that settler is the closest
 * living thing around. A fourth pins the actually-observable behaviour: a
 * raider with nothing to fight measurably closes distance on its own
 * settlement's people over real ticks.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaiderHuntGameTests {

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static Settlement makeSettlement(GameTestHelper helper, BlockPos centerRel,
                                             int radius, String name) {
        var level = helper.getLevel();
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement s = new Settlement(UUID.randomUUID(), name, helper.absolutePos(centerRel));
        s.radius = radius;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static SettlerEntity spawnSettler(GameTestHelper helper, Settlement s, BlockPos rel,
                                              String name) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), rel);
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    private static RaiderEntity spawnBlodRaider(GameTestHelper helper, Settlement s, BlockPos rel) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), rel);
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BLOD, 1.0F, false);
        raider.setObjectivePos(s.center); // the same default RaidDirector#spawnBand seeds
        s.pendingRaid = new RaidPlan(UUID.randomUUID(), RaidObjective.BLOD, 0.0F, 1L);
        return raider;
    }

    /** {@code Mob#goalSelector} is public (vanilla) -- same reflection idiom
     * {@code RaidDamageGameTests#breachGoalOf} already uses. */
    private static RaiderHuntGoal huntGoalOf(RaiderEntity raider) {
        for (WrappedGoal wrapped : raider.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof RaiderHuntGoal hunt) {
                return hunt;
            }
        }
        return null;
    }

    /**
     * The scan is bounded ({@code settlement.radius + HUNT_SCAN_MARGIN}, 16
     * past a radius of 0 here) and, within that bound, always resolves to
     * the NEAREST candidate -- a settler well past the bound must never win
     * merely for existing, and must never leak into the target even though
     * it is a real, valid member of this raider's own war.
     */
    @GameTest(template = "empty16", timeoutTicks = 100, batch = "raider_hunt_the_scan_is_bounded_and_prefers_the_nearest_candidate")
    public void theScanIsBoundedAndPrefersTheNearestCandidate(GameTestHelper helper) {
        buildArena(helper, 16);
        BlockPos centerRel = new BlockPos(2, 1, 2);
        Settlement s = makeSettlement(helper, centerRel, 0, "Huntholm");
        RaiderEntity raider = spawnBlodRaider(helper, s, centerRel);

        // Within bound (distance 8, bound is radius(0)+16=16): must win.
        BlockPos nearRel = new BlockPos(2, 1, 10);
        spawnSettler(helper, s, nearRel, "Near");
        // Past bound (distance ~18.4, diagonal so it still fits the 16-wide
        // arena): must never be picked, however "nearest living entity
        // ignoring bounds" bookkeeping would have read.
        spawnSettler(helper, s, new BlockPos(15, 1, 15), "Far");

        // Driven directly rather than through the real goal selector and
        // real ticks: at this close range and open sight, vanilla's OWN
        // target acquisition (RaiderEntity's targetSelector, mustSee=true)
        // would legitimately grab the near settler as a live combat target
        // within the first few ticks -- correct behaviour, but it would
        // stop RaiderHuntGoal from ever starting at all (its canUse()
        // requires getTarget()==null), so objectivePos would silently stay
        // at its spawn-time default and this test would be proving nothing
        // about the scan. Calling tick() directly exercises exactly the
        // scan-and-bound logic this test is about, isolated from that race
        // -- the handoff itself is covered separately by
        // stepsAsideTheInstantALiveTargetExists.
        RaiderHuntGoal hunt = huntGoalOf(raider);
        helper.assertTrue(hunt != null, "RaiderHuntGoal must be registered on every raider");
        hunt.tick();
        hunt.tick();

        int bound = 0 + RaiderHuntGoal.HUNT_SCAN_MARGIN; // settlement radius 0 + the goal's own margin
        int boundSqr = bound * bound;
        BlockPos objective = raider.objectivePos();
        BlockPos nearAbs = helper.absolutePos(nearRel);
        helper.assertTrue(objective != null, "the hunt must have set an objective by now");
        helper.assertTrue(s.center.distSqr(objective) <= boundSqr,
            "whatever the scan picked must be inside its own bound, got " + objective
                + " (" + Math.sqrt(s.center.distSqr(objective)) + " blocks from centre)");
        // Specific, not just in-bound: it must be the NEAR settler, not the
        // settlement-centre fallback.
        helper.assertTrue(nearAbs.distSqr(objective) <= 4,
            "the nearest in-bound settler must win over the fallback centre, got "
                + objective + " (near settler spawned at " + nearAbs + ")");
        helper.succeed();
    }

    /**
     * A settler belonging to a DIFFERENT settlement, standing right next to
     * the raider, must never be treated as this raider's quarry -- the
     * exact rule {@link RaiderEntity#isMyWar} already enforces for the
     * target selector, reused here by the scan (KF-027's lesson: a raider
     * unscoped to its own settlement's war can wander into a neighbour's
     * business it was never sent against).
     */
    @GameTest(template = "empty16", timeoutTicks = 100, batch = "raider_hunt_never_targets_a_settler_from_another_settlement")
    public void neverTargetsASettlerFromAnotherSettlement(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement mine = makeSettlement(helper, new BlockPos(2, 1, 2), 4, "Huntholm");
        Settlement theirs = makeSettlement(helper, new BlockPos(8, 1, 8), 4, "Otherholm");
        RaiderEntity raider = spawnBlodRaider(helper, mine, new BlockPos(2, 1, 2));

        // Right next to the raider, but not its war -- must be ignored.
        SettlerEntity notMyWar = spawnSettler(helper, theirs, new BlockPos(3, 1, 2), "NotMyWar");

        helper.succeedWhen(() -> {
            BlockPos objective = raider.objectivePos();
            helper.assertTrue(objective != null, "the hunt must have set an objective by now");
            helper.assertTrue(notMyWar.blockPosition().distSqr(objective) > 4,
                "a settler from another settlement must never become the hunt target, got "
                    + objective + " (their settler is at " + notMyWar.blockPosition() + ")");
            // Nothing of its OWN settlement is in range, so the honest
            // fallback is the settlement centre -- see RaiderHuntGoal#tick.
            helper.assertTrue(objective.equals(mine.center),
                "with nobody of its own war in range the raider must fall back to the "
                    + "settlement centre, got " + objective);
        });
    }

    /**
     * The observable behaviour the audit actually asked for: a BLOD raider
     * with nothing to fight yet must genuinely close distance on its own
     * settlement's people over real ticks -- not merely update a field.
     */
    @GameTest(template = "empty16", timeoutTicks = 500, batch = "raider_hunt_a_blod_raider_with_nothing_to_fight_closes_on_its_own_settler")
    public void aBlodRaiderWithNothingToFightClosesOnItsOwnSettler(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8), 4, "Huntholm");
        RaiderEntity raider = spawnBlodRaider(helper, s, new BlockPos(2, 1, 2));
        SettlerEntity settler = spawnSettler(helper, s, new BlockPos(13, 1, 13), "Quarry");

        double startDist = raider.position().distanceTo(settler.position());
        helper.succeedWhen(() -> {
            double dist = raider.position().distanceTo(settler.position());
            helper.assertTrue(dist < startDist - 5.0,
                "a hunting raider with no live target must measurably close distance on its "
                    + "own settlement's people; started " + startDist + " now " + dist);
        });
    }

    /**
     * The moment vanilla's own target selector hands this raider a live,
     * visible target, {@link RaiderHuntGoal} must step aside at once rather
     * than fight {@code MeleeAttackGoal} for the same MOVE/LOOK flags --
     * the exact handoff {@code RaiderScoutGoal} already documents for its
     * own case.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_hunt_steps_aside_the_instant_a_live_target_exists")
    public void stepsAsideTheInstantALiveTargetExists(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(4, 1, 4), 6, "Huntholm");
        RaiderEntity raider = spawnBlodRaider(helper, s, new BlockPos(4, 1, 4));
        SettlerEntity settler = spawnSettler(helper, s, new BlockPos(6, 1, 4), "Bait");

        helper.succeedWhen(() -> {
            helper.assertTrue(raider.getTarget() != null,
                "close range and open sight must give the raider a live target eventually");
            RaiderHuntGoal hunt = huntGoalOf(raider);
            helper.assertTrue(hunt == null || !hunt.canContinueToUse(),
                "RaiderHuntGoal must relinquish MOVE/LOOK the instant a live target exists");
        });
    }
}
