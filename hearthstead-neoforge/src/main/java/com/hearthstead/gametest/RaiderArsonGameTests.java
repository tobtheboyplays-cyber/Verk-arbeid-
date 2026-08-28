package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.ai.RaiderArsonGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * RAIDER-ARSON: the BRANN objective's own goal ({@link RaiderArsonGoal}) --
 * see its class doc for the gap this closes. Before it, {@code
 * RaidDirector#tickArson} only ever torched a block once a BRANN band was
 * ALREADY standing within {@code ARSON_REACH} of a building, and nothing in
 * {@code RaiderEntity#registerGoals} ever moved a raider there -- the exact
 * "objective silently does nothing" shape KF-031 found for the ransom raid
 * that never took anybody, found again here for the fire raid that never
 * burned anything.
 *
 * <p>Mirrors {@code RaiderHuntGameTests} exactly (same handoff, same
 * bounded-search proof, same behavioural close-the-distance proof), plus one
 * more this objective specifically needs: an end-to-end proof that a raider
 * spawned FAR from every building genuinely reaches one and gets torched
 * through the real {@link RaidDirector#tick} gate -- not {@link
 * RaidDirector#torchForArson} called directly as a proxy for it (the
 * shortcut {@code SagaGameTests}/{@code RaidPressureGameTests} already
 * document using for their own, different purposes), because the defect
 * this goal fixes is specifically about REACHING a building, and a test
 * that starts the raider already in range would never have caught it.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaiderArsonGameTests {

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

    private static RaiderEntity spawnBrannRaider(GameTestHelper helper, Settlement s, BlockPos rel) {
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), rel);
        raider.assign(UUID.randomUUID(), s.id, RaidObjective.BRANN, 1.0F, false);
        raider.setObjectivePos(s.center); // the same default RaidDirector#spawnBand seeds
        s.pendingRaid = new RaidPlan(UUID.randomUUID(), RaidObjective.BRANN, 0.0F, 1L);
        return raider;
    }

    /** {@code Mob#goalSelector} is public (vanilla) -- same reflection idiom
     * {@code RaiderHuntGameTests#huntGoalOf} already uses. */
    private static RaiderArsonGoal arsonGoalOf(RaiderEntity raider) {
        for (WrappedGoal wrapped : raider.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof RaiderArsonGoal arson) {
                return arson;
            }
        }
        return null;
    }

    // --------------------------------------------------------------- (a) ---

    /**
     * The scan always resolves to the NEAREST building of the raider's own
     * settlement -- a farther one must never win merely for existing.
     */
    @GameTest(template = "empty16", timeoutTicks = 100, batch = "raider_arson_the_scan_prefers_the_nearest_building")
    public void theScanPrefersTheNearestBuilding(GameTestHelper helper) {
        buildArena(helper, 16);
        BlockPos centerRel = new BlockPos(2, 1, 2);
        Settlement s = makeSettlement(helper, centerRel, 4, "Brannholm");
        RaiderEntity raider = spawnBrannRaider(helper, s, centerRel);

        // Near building: anchored close to the raider's own spawn.
        Building near = GameTestFixtures.register(helper, s, BuildingType.FARMHOUSE, 5, 2);
        // Far building: anchored well across the arena.
        Building far = GameTestFixtures.register(helper, s, BuildingType.WAREHOUSE, 11, 11);

        // Driven directly, the same reasoning RaiderHuntGameTests's own
        // "theScanIsBoundedAndPrefersTheNearestCandidate" documents: at close
        // range vanilla's own target selector could otherwise grab a live
        // target first (not applicable here -- there are no settlers in this
        // arena -- but ticking the goal directly still isolates exactly the
        // scan-and-pick logic this test is about from real pathfinding).
        RaiderArsonGoal arson = arsonGoalOf(raider);
        helper.assertTrue(arson != null, "RaiderArsonGoal must be registered on every raider");
        arson.tick();
        arson.tick();

        BlockPos objective = raider.objectivePos();
        BlockPos nearCenter = near.bounds.getCenter();
        BlockPos farCenter = far.bounds.getCenter();
        helper.assertTrue(objective != null, "the arson goal must have set an objective by now");
        helper.assertTrue(objective.equals(nearCenter),
            "the nearest building must win, got " + objective + " (near=" + nearCenter
                + " far=" + farCenter + ")");
        helper.succeed();
    }

    // --------------------------------------------------------------- (b) ---

    /**
     * The observable behaviour the fix actually asked for: a BRANN raider
     * with no live target must genuinely close distance on its own
     * settlement's buildings over real ticks -- not merely update a field.
     */
    @GameTest(template = "empty16", timeoutTicks = 500, batch = "raider_arson_a_brann_raider_with_nothing_to_fight_closes_on_a_building")
    public void aBrannRaiderWithNothingToFightClosesOnABuilding(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(8, 1, 8), 6, "Brannholm");
        RaiderEntity raider = spawnBrannRaider(helper, s, new BlockPos(2, 1, 2));
        Building target = GameTestFixtures.register(helper, s, BuildingType.FARMHOUSE, 12, 12);
        BlockPos targetCenter = target.bounds.getCenter();

        double startDist = raider.position().distanceTo(
            new net.minecraft.world.phys.Vec3(targetCenter.getX(), targetCenter.getY(),
                targetCenter.getZ()));
        helper.succeedWhen(() -> {
            double dist = raider.position().distanceTo(
                new net.minecraft.world.phys.Vec3(targetCenter.getX(), targetCenter.getY(),
                    targetCenter.getZ()));
            helper.assertTrue(dist < startDist - 5.0,
                "a BRANN raider with no live target must measurably close distance on its "
                    + "own settlement's buildings; started " + startDist + " now " + dist);
        });
    }

    // --------------------------------------------------------------- (c) ---

    /**
     * The exact handoff {@code RaiderHuntGameTests} pins for BLOD, mirrored
     * for BRANN: the instant vanilla's own target selector hands this
     * raider a live, visible target, the arson goal must step aside rather
     * than fight {@code MeleeAttackGoal} for the same MOVE/LOOK flags.
     */
    @GameTest(template = "empty16", timeoutTicks = 200, batch = "raider_arson_steps_aside_the_instant_a_live_target_exists")
    public void stepsAsideTheInstantALiveTargetExists(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(4, 1, 4), 6, "Brannholm");
        RaiderEntity raider = spawnBrannRaider(helper, s, new BlockPos(4, 1, 4));
        com.hearthstead.entity.SettlerEntity settler = helper.spawn(
            ModEntities.SETTLER.get(), new BlockPos(6, 1, 4));
        settler.setSettlerName("Bait");
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), "Bait", com.hearthstead.entity.Profession.NONE);

        helper.succeedWhen(() -> {
            helper.assertTrue(raider.getTarget() != null,
                "close range and open sight must give the raider a live target eventually");
            RaiderArsonGoal arson = arsonGoalOf(raider);
            helper.assertTrue(arson == null || !arson.canContinueToUse(),
                "RaiderArsonGoal must relinquish MOVE/LOOK the instant a live target exists");
        });
    }

    // --------------------------------------------------------------- (d) ---

    /**
     * The end-to-end proof: a raider that starts FAR from every building --
     * well outside {@link RaidDirector#ARSON_REACH} -- genuinely reaches one
     * and gets it torched through the real {@link RaidDirector#tick} gate.
     * Before {@link RaiderArsonGoal} existed this raider would have stood at
     * its spawn point for the entire raid and {@code tickArson} would never
     * have found a single candidate in reach: the objective would have
     * silently done nothing, exactly the KF-031-shaped defect this closes.
     *
     * <p>{@link RaidDirector#tick} is called directly from the assertion
     * loop below rather than through a live {@code HearthBlockEntity}'s own
     * once-a-second tick (no hearth exists in this fixture-only arena, the
     * same "no settlement plumbing beyond what the test needs" discipline
     * {@code ChainsGameTests#millGrindsSugarCaneIntoPaperChestTrue} already
     * documents for its own bare fixtures) -- calling the PUBLIC director
     * entry point itself, not {@code torchForArson} directly, is what keeps
     * this test on the real mechanism rather than a shortcut around it.
     */
    @GameTest(template = "empty16", timeoutTicks = 1000, batch = "raider_arson_a_brann_raid_actually_torches_a_building_it_started_far_from")
    public void aBrannRaidActuallyTorchesABuildingItStartedFarFrom(GameTestHelper helper) {
        buildArena(helper, 16);
        Settlement s = makeSettlement(helper, new BlockPos(12, 1, 12), 8, "Brannholm");
        Building house = GameTestFixtures.register(helper, s, BuildingType.FARMHOUSE, 11, 11);
        ServerLevel level = helper.getLevel();
        RaidCaptain captain = RaidDirector.pickCaptain(s, level.getRandom());
        s.pendingRaid = new RaidPlan(captain.id(), RaidObjective.BRANN, 0.0F, 1L);
        RaiderEntity raider = helper.spawn(ModEntities.RAIDER.get(), new BlockPos(1, 1, 1));
        raider.assign(captain.id(), s.id, RaidObjective.BRANN, 1.0F, false);
        raider.setObjectivePos(s.center); // the honest default RaidDirector#spawnBand seeds

        helper.succeedWhen(() -> {
            // Drives the arson gate every tick this test polls -- see the
            // class doc for why this is the real entry point, not a proxy.
            RaidDirector.tick(level, s);
            int scars = RaidDirector.scarsOf(level, s.id).size();
            helper.assertTrue(scars > 0,
                "a BRANN raider that started far from every building must still reach "
                    + "one and torch it -- the raid must not silently do nothing; "
                    + "raiderPos=" + raider.blockPosition() + " buildingCenter="
                    + house.bounds.getCenter());
        });
    }
}
