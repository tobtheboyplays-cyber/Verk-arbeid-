package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.SettlerStuckWatchdog;
import com.hearthstead.entity.path.RoadNavigation;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Anti-stuck lane (owner, 27 Sep): the universal {@link SettlerStuckWatchdog}.
 * Each fixture drives one settler the way a job goal does (a walk request
 * every second) with its own AI goals cleared, so only the watchdog can free
 * it. The watchdog's timings are per-settler test overrides, never global.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class StuckWatchdogGameTests {
    private static final String T = "empty32";
    private static final BlockPos HEARTH = new BlockPos(16, 1, 4);
    private static final BlockPos POCKET = new BlockPos(16, 1, 16);

    // ------------------------------------------------------------ fixture ---

    private record Arena(Settlement town, BlockPos hearth) {}

    private static Arena arena(GameTestHelper h, String name) {
        var level = h.getLevel();
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                for (int y = 2; y <= 8; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
        BlockPos hearth = h.absolutePos(HEARTH.above());
        level.setBlockAndUpdate(hearth, ModBlocks.HEARTH.get().defaultBlockState());
        Settlement town = new Settlement(UUID.randomUUID(), name, hearth);
        town.radius = 32;
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(town.id, town);
        data.setDirty();
        ((HearthBlockEntity) level.getBlockEntity(hearth)).bindSettlement(town.id);
        return new Arena(town, hearth);
    }

    /** A settler standing at relative {@code feet} whose own goals are cleared. */
    private static SettlerEntity settler(GameTestHelper h, Arena a, BlockPos feet, double y, String name,
                                         Profession profession) {
        var level = h.getLevel();
        SettlerEntity s = ModEntities.SETTLER.get().create(level);
        h.assertTrue(s != null, "settler constructs");
        BlockPos abs = h.absolutePos(feet);
        s.moveTo(abs.getX() + 0.5D, abs.getY() + y, abs.getZ() + 0.5D, 0.0F, 0.0F);
        s.setSettlerName(name);
        s.bindTo(a.town.id, a.hearth);
        s.assignProfession(profession);
        a.town.putRecord(s.getUUID(), name, profession);
        s.setEnergy(100.0F);
        s.setHunger(100.0F);
        h.assertTrue(level.addFreshEntity(s), "settler enters the world");
        s.goalSelector.removeAllGoals(g -> true);
        s.targetSelector.removeAllGoals(g -> true);
        return s;
    }

    /** The stand-in for a job goal: ask to walk to {@code target} once a second. */
    private static void wantsToWalk(GameTestHelper h, SettlerEntity s, BlockPos absTarget) {
        h.onEachTick(() -> {
            h.getLevel().setDayTime(6000L);
            if (s.isAlive() && s.tickCount % 20 == 3) {
                s.getNavigation().moveTo(absTarget.getX() + 0.5D, absTarget.getY(), absTarget.getZ() + 0.5D, 0.6D);
            }
        });
    }

    /** Four fences round a 1x1 cell at relative {@code cell} (the Lumber Camp trap). */
    private static void fencePocket(GameTestHelper h, BlockPos cell) {
        for (BlockPos side : new BlockPos[]{cell.north(), cell.south(), cell.east(), cell.west(),
                cell.north().east(), cell.north().west(), cell.south().east(), cell.south().west()}) {
            h.setBlock(side, Blocks.OAK_FENCE);
        }
    }

    private static Map<BlockPos, BlockState> snapshot(GameTestHelper h, BlockPos centre, int r) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(centre.offset(-r, -1, -r), centre.offset(r, 3, r))) {
            out.put(p.immutable(), h.getBlockState(p));
        }
        return out;
    }

    private static void assertUnchanged(GameTestHelper h, Map<BlockPos, BlockState> before) {
        for (var e : before.entrySet()) {
            h.assertTrue(h.getBlockState(e.getKey()) == e.getValue(),
                "no block may change: " + e.getKey() + " was " + e.getValue() + " now " + h.getBlockState(e.getKey()));
        }
    }

    private static boolean inCell(GameTestHelper h, SettlerEntity s, BlockPos rel) {
        BlockPos abs = h.absolutePos(rel);
        return s.blockPosition().getX() == abs.getX() && s.blockPosition().getZ() == abs.getZ();
    }

    private static boolean routeToBanner(GameTestHelper h, SettlerEntity s, BlockPos hearth) {
        RoadNavigation.resetExpandedBudgetForTests(h.getLevel());
        Path p = s.getNavigation().createPath(hearth, 2);
        return p != null && p.canReach();
    }

    // -------------------------------------------------------------- tests ---

    /** a) Walled into a 1x1x2 fence pocket: rescued to a cell with a route to the Banner; no block changes. */
    @GameTest(template = T, timeoutTicks = 900, batch = "stuck_watchdog_a")
    public void fencePocketIsRescued(GameTestHelper h) {
        Arena a = arena(h, "Fencewick");
        fencePocket(h, POCKET.above());
        Map<BlockPos, BlockState> before = snapshot(h, POCKET.above(), 3);
        SettlerEntity s = settler(h, a, POCKET.above(), 0.0D, "Aldric", Profession.LUMBERER);
        SettlerStuckWatchdog.overrideForTests(s, true, 300);
        h.assertTrue(SettlerStuckWatchdog.shutIn(s), "the pocket really has no way out");
        wantsToWalk(h, s, a.hearth);
        h.succeedWhen(() -> {
            h.assertTrue(SettlerStuckWatchdog.rescues(s) == 1, "rescued once; watchdog: " + SettlerStuckWatchdog.describe(s));
            h.assertTrue(!inCell(h, s, POCKET.above()), "out of the pocket: " + s.blockPosition());
            h.assertTrue(!SettlerStuckWatchdog.shutIn(s), "no longer shut in");
            h.assertTrue(routeToBanner(h, s, a.hearth), "a real route to the Banner from " + s.blockPosition());
            assertUnchanged(h, before);
        });
    }

    /** a2) The same pocket with the shipped default (90 s): rescued well inside two minutes. */
    @GameTest(template = T, timeoutTicks = 2600, batch = "stuck_watchdog_a")
    public void fencePocketIsRescuedAtTheDefaultTime(GameTestHelper h) {
        Arena a = arena(h, "Fencewick");
        fencePocket(h, POCKET.above());
        Map<BlockPos, BlockState> before = snapshot(h, POCKET.above(), 3);
        SettlerEntity s = settler(h, a, POCKET.above(), 0.0D, "Brenna", Profession.FARMER);
        SettlerStuckWatchdog.overrideForTests(s, true, null);
        wantsToWalk(h, s, a.hearth);
        long start = h.getTick();
        h.succeedWhen(() -> {
            h.assertTrue(SettlerStuckWatchdog.rescues(s) == 1, "rescued; watchdog: " + SettlerStuckWatchdog.describe(s));
            h.assertTrue(h.getTick() - start >= 1700, "not before the configured 90 s: " + (h.getTick() - start));
            h.assertTrue(routeToBanner(h, s, a.hearth), "a real route to the Banner");
            assertUnchanged(h, before);
        });
    }

    /** b) On a half slab under a low eave, wanting an unreachable spot: re-plans / steps off, never rescued. */
    @GameTest(template = T, timeoutTicks = 1000, batch = "stuck_watchdog_b")
    public void lowEaveRepathsOrUnwedgesWithoutRescue(GameTestHelper h) {
        Arena a = arena(h, "Eavesby");
        BlockPos cell = new BlockPos(10, 2, 16);
        h.setBlock(cell, Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        h.setBlock(cell.above(2), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        h.setBlock(cell.above(2).east(), Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
        // An unreachable destination straight overhead: the nearest reachable
        // spot to it is where the settler already stands, so nothing but the
        // watchdog moves it.
        h.setBlock(cell.above(5), Blocks.STONE);
        BlockPos high = cell.above(6);
        SettlerEntity s = settler(h, a, cell, 0.5D, "Cedric", Profession.COURIER);
        SettlerStuckWatchdog.overrideForTests(s, true, 500);
        wantsToWalk(h, s, h.absolutePos(high));
        h.runAtTickTime(800, () -> {
            String why = SettlerStuckWatchdog.describe(s);
            h.assertTrue(SettlerStuckWatchdog.rescues(s) == 0, "an open spot is never rescued: " + why);
            h.assertTrue(!inCell(h, s, cell) || SettlerStuckWatchdog.lastAction(s).startsWith("stuck_"),
                "re-planned or stepped off the half slab: " + why + " at " + s.blockPosition());
            h.assertTrue(!SettlerStuckWatchdog.lastAction(s).equals("none"), "the watchdog engaged: " + why);
            h.succeed();
        });
    }

    /** c) A worker at its station (stationary by design) is never flagged. */
    @GameTest(template = T, timeoutTicks = 800, batch = "stuck_watchdog_c")
    public void stationWorkerIsNeverFlagged(GameTestHelper h) {
        Arena a = arena(h, "Benchford");
        BlockPos stand = new BlockPos(8, 2, 8);
        h.setBlock(stand.east(), Blocks.CRAFTING_TABLE);
        SettlerEntity s = settler(h, a, stand, 0.0D, "Dagny", Profession.CARPENTER);
        SettlerStuckWatchdog.overrideForTests(s, true, 200);
        BlockPos station = h.absolutePos(stand.east());
        h.onEachTick(() -> {
            h.getLevel().setDayTime(6000L);
            s.setActivity(SettlerActivity.WORK_SAW);
            if (s.tickCount % 20 == 3) {
                // The goal keeps "arriving" at its bench while it works.
                s.getNavigation().moveTo(station.getX() + 0.5D, station.getY(), station.getZ() + 0.5D, 0.6D);
            }
            h.assertTrue(SettlerStuckWatchdog.stuckTicks(s) == 0, "never counted as stuck: "
                + SettlerStuckWatchdog.describe(s));
            h.assertTrue(SettlerStuckWatchdog.rescues(s) == 0, "never rescued");
        });
        h.runAtTickTime(700, () -> {
            h.assertTrue(s.position().distanceTo(Vec3.atBottomCenterOf(station)) <= 2.5D, "still at the bench: "
                + s.blockPosition());
            h.succeed();
        });
    }

    /** d) A courier with cargo stuck in a pocket: every item is still in the bag after the rescue. */
    @GameTest(template = T, timeoutTicks = 900, batch = "stuck_watchdog_d")
    public void courierCargoIsConservedThroughRescue(GameTestHelper h) {
        Arena a = arena(h, "Cartham");
        fencePocket(h, POCKET.above());
        SettlerEntity s = settler(h, a, POCKET.above(), 0.0D, "Eirik", Profession.COURIER);
        s.bag.addItem(new ItemStack(Items.OAK_LOG, 16));
        s.bag.addItem(new ItemStack(Items.BREAD, 5));
        SettlerStuckWatchdog.overrideForTests(s, true, 300);
        wantsToWalk(h, s, a.hearth);
        h.succeedWhen(() -> {
            h.assertTrue(SettlerStuckWatchdog.rescues(s) == 1, "rescued: " + SettlerStuckWatchdog.describe(s));
            h.assertTrue(s.bag.countItem(Items.OAK_LOG) == 16 && s.bag.countItem(Items.BREAD) == 5,
                "cargo conserved: logs=" + s.bag.countItem(Items.OAK_LOG) + " bread=" + s.bag.countItem(Items.BREAD));
            AABB area = new AABB(h.absolutePos(BlockPos.ZERO)).inflate(40.0D);
            h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, area).isEmpty(), "nothing was dropped");
        });
    }

    /** e) A settler inside a solid block is freed at once. */
    @GameTest(template = T, timeoutTicks = 200, batch = "stuck_watchdog_e")
    public void settlerInsideABlockIsFreed(GameTestHelper h) {
        Arena a = arena(h, "Stonebury");
        BlockPos cell = new BlockPos(20, 2, 20);
        SettlerEntity s = settler(h, a, cell, 0.0D, "Frida", Profession.NONE);
        SettlerStuckWatchdog.overrideForTests(s, true, null);
        h.setBlock(cell, Blocks.STONE);
        h.setBlock(cell.above(), Blocks.STONE);
        Map<BlockPos, BlockState> before = snapshot(h, cell, 2);
        long start = h.getTick();
        h.succeedWhen(() -> {
            h.assertTrue(SettlerStuckWatchdog.freedCount(s) >= 1, "freed: " + SettlerStuckWatchdog.describe(s));
            h.assertTrue(h.getTick() - start <= 40, "within two seconds: " + (h.getTick() - start));
            h.assertTrue(h.getLevel().noCollision(s, s.getBoundingBox().deflate(0.01D)), "in free space now");
            h.assertTrue(s.isAlive(), "alive");
            assertUnchanged(h, before);
        });
    }

    /** f) At most one rescue per settler per five minutes. */
    @GameTest(template = T, timeoutTicks = 1400, batch = "stuck_watchdog_f")
    public void rescueHasACooldown(GameTestHelper h) {
        Arena a = arena(h, "Twicehold");
        fencePocket(h, POCKET.above());
        SettlerEntity s = settler(h, a, POCKET.above(), 0.0D, "Gunnar", Profession.LUMBERER);
        SettlerStuckWatchdog.overrideForTests(s, true, 200);
        wantsToWalk(h, s, a.hearth);
        BlockPos pocket = h.absolutePos(POCKET.above());
        long[] putBack = {-1L};
        h.onEachTick(() -> {
            if (putBack[0] < 0 && SettlerStuckWatchdog.rescues(s) == 1) {
                putBack[0] = h.getTick();
                s.moveTo(pocket.getX() + 0.5D, pocket.getY(), pocket.getZ() + 0.5D, 0.0F, 0.0F);
                s.getNavigation().stop();
            }
        });
        h.runAtTickTime(1300, () -> {
            h.assertTrue(putBack[0] > 0, "the first rescue happened: " + SettlerStuckWatchdog.describe(s));
            h.assertTrue(SettlerStuckWatchdog.rescues(s) == 1, "no second rescue inside 5 minutes: "
                + SettlerStuckWatchdog.describe(s));
            h.assertTrue(inCell(h, s, POCKET.above()), "still in the pocket during the cooldown");
            h.assertTrue(SettlerStuckWatchdog.stuckTicks(s) >= 400, "and still known to be stuck");
            h.succeed();
        });
    }

    /** g) [pathing] stuckWatchdog=false: no action of any kind. */
    @GameTest(template = T, timeoutTicks = 900, batch = "stuck_watchdog_g")
    public void watchdogOffDoesNothing(GameTestHelper h) {
        Arena a = arena(h, "Quietmoor");
        fencePocket(h, POCKET.above());
        SettlerEntity s = settler(h, a, POCKET.above(), 0.0D, "Hilde", Profession.LUMBERER);
        SettlerStuckWatchdog.overrideForTests(s, false, 200);
        wantsToWalk(h, s, a.hearth);
        Vec3 start = s.position();
        h.runAtTickTime(800, () -> {
            h.assertTrue(SettlerStuckWatchdog.rescues(s) == 0 && SettlerStuckWatchdog.stage(s) == 0
                && SettlerStuckWatchdog.lastAction(s).equals("none"), "off means off: " + SettlerStuckWatchdog.describe(s));
            h.assertTrue(inCell(h, s, POCKET.above()) && s.position().distanceTo(start) < 1.0D, "left where it was");
            h.succeed();
        });
    }
}
