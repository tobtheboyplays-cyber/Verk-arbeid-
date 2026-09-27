package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.List;

/** Physical QA-LADDER-EXIT-01 regressions; no goal/controller-state injection. */
@GameTestHolder(Hearthstead.MODID)
public final class LadderExitGameTests {
    private static final String BATCH = "ladder_exit";
    private static final String BASELINE = "hearthstead.gametest.ladderExitVanillaControl";

    @GameTestGenerator
    public static Collection<TestFunction> cases() {
        return List.of(
            test("ladder_exit_bottom_slab", 1200, LadderExitGameTests::sideExit),
            test("ladder_exit_existing_ascent_return", 600,
                h -> new LadderRouteGameTests().workerPhysicallyClimbsAndReturns(h)),
            test("ladder_exit_ordinary_stairs", 600, h -> groundControl(h, true)),
            test("ladder_exit_ordinary_block_jump", 600, h -> groundControl(h, false)));
    }

    private static TestFunction test(String name, int ticks, java.util.function.Consumer<GameTestHelper> run) {
        return new TestFunction(BATCH, name, Hearthstead.MODID + ":empty16", Rotation.NONE, ticks, 0L, true, run);
    }

    private static void sideExit(GameTestHelper h) {
        floor(h);
        for (int y = 1; y <= 9; y++) {
            h.setBlock(new BlockPos(6, y, 7), Blocks.COBBLESTONE);
            h.setBlock(new BlockPos(6, y, 6), Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, Direction.NORTH));
        }
        for (int x = 4; x <= 5; x++) for (int z = 5; z <= 7; z++) {
            h.setBlock(new BlockPos(x, 3, z), Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM));
        }
        // Same local wall/deck relationship as the saved Tavern: backing to the
        // south, east wall and clear supported west exit with a half-block drop.
        for (int y = 1; y <= 9; y++) for (int z = 5; z <= 8; z++) h.setBlock(new BlockPos(7, y, z), Blocks.COBBLESTONE);
        SettlerEntity mob = spawn(h, new BlockPos(6, 1, 5));
        boolean vanilla = Boolean.getBoolean(BASELINE);
        if (vanilla) setControl(mob, new MoveControl(mob)); // baseline replaces the whole controller, not its state
        h.assertTrue(vanilla || mob.getMoveControl() instanceof com.hearthstead.entity.path.SettlerMoveControl,
            "fixed fixture exercises production SettlerMoveControl wiring");
        BlockPos top = h.absolutePos(new BlockPos(6, 7, 6));
        BlockPos exit = h.absolutePos(new BlockPos(5, 4, 6));
        BlockPos ground = h.absolutePos(new BlockPos(3, 1, 8));
        Probe probe = new Probe(h, mob);
        boolean[] redirected = {false};
        boolean[] leftLadder = {false};
        GameTestTicks.at(h, 5, () -> route(h, mob, top));
        h.onEachTick(() -> {
            probe.sample();
            if (!redirected[0] && mob.onClimbable() && mob.getY() >= top.getY() - 1.0D) {
                redirected[0] = true;
                route(h, mob, exit);
                var path = mob.getNavigation().getPath();
                boolean lateral = false;
                for (int i = 1; i < path.getNodeCount(); i++) {
                    BlockPos before = path.getNodePos(i - 1);
                    if (path.getNodePos(i).equals(exit) && before.equals(exit.east())) lateral = true;
                }
                h.assertTrue(lateral, "fixture must route from the actual rung sideways onto the bottom slab");
                Hearthstead.LOGGER.info("LADDER_EXIT_DIAGNOSTIC redirect vanilla={} {}", vanilla, probe.describe());
            }
            if (redirected[0] && !leftLadder[0] && mob.getX() + mob.getBbWidth() / 2.0D <= exit.getX() + 1.0D
                && Math.abs(mob.getY() - (exit.getY() - .5D)) < .2D
                && Math.abs(mob.getZ() - exit.getZ() - .5D) < .5D) {
                leftLadder[0] = true;
                route(h, mob, ground);
                Hearthstead.LOGGER.info("LADDER_EXIT_DIAGNOSTIC physical-exit vanilla={} {}", vanilla, probe.describe());
            }
            if (mob.tickCount == 1100) probe.dump();
        });
        h.succeedWhen(() -> {
            h.assertTrue(redirected[0] && leftLadder[0]
                && mob.position().distanceToSqr(Vec3.atBottomCenterOf(ground)) < 1.0D,
                "physically ascend, leave the ladder onto the slab and reach the floor; vanilla=" + vanilla
                    + " redirected=" + redirected[0] + " exited=" + leftLadder[0] + " " + probe.describe());
            h.assertTrue(mob.getHealth() == probe.initialHealth, "supported dismount loses no health");
            h.assertTrue(h.getLevel().getBlockState(exit.below()).is(Blocks.SPRUCE_SLAB), "physical slab remains");
        });
    }

    private static void groundControl(GameTestHelper h, boolean stairs) {
        floor(h);
        // Side walls prevent a route around the obstacle from satisfying a jump/stair test.
        for (int x = 1; x <= 11; x++) for (int y = 1; y <= 6; y++) {
            h.setBlock(new BlockPos(x, y, 4), Blocks.STONE);
            h.setBlock(new BlockPos(x, y, 6), Blocks.STONE);
        }
        for (int y = 1; y <= 6; y++) {
            h.setBlock(new BlockPos(1, y, 5), Blocks.STONE);
            h.setBlock(new BlockPos(11, y, 5), Blocks.STONE);
        }
        BlockPos start = new BlockPos(2, 1, 5);
        BlockPos finish;
        if (stairs) {
            for (int x = 4; x <= 6; x++) {
                int height = x - 3;
                for (int y = 1; y < height; y++) h.setBlock(new BlockPos(x, y, 5), Blocks.STONE);
                h.setBlock(new BlockPos(x, height, 5), Blocks.STONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
            }
            for (int x = 7; x <= 9; x++) for (int y = 1; y <= 3; y++) h.setBlock(new BlockPos(x, y, 5), Blocks.STONE);
            finish = new BlockPos(8, 4, 5);
        } else {
            h.setBlock(new BlockPos(5, 1, 5), Blocks.STONE);
            finish = new BlockPos(8, 1, 5);
        }
        SettlerEntity mob = spawn(h, start);
        Probe probe = new Probe(h, mob);
        BlockPos destination = h.absolutePos(finish);
        BlockPos back = h.absolutePos(start);
        boolean[] reached = {false};
        boolean[] jumped = {false};
        GameTestTicks.at(h, 5, () -> route(h, mob, destination));
        h.onEachTick(() -> {
            probe.sample();
            jumped[0] |= mob.getDeltaMovement().y > .25D && !mob.onGround();
            if (!reached[0] && mob.position().distanceToSqr(Vec3.atBottomCenterOf(destination)) < 1.0D) {
                reached[0] = true;
                route(h, mob, back);
            }
        });
        h.succeedWhen(() -> {
            h.assertTrue(reached[0] && (stairs || jumped[0])
                && mob.position().distanceToSqr(Vec3.atBottomCenterOf(back)) < 1.0D,
                "ordinary " + (stairs ? "stairs" : "real block jump") + " and return remain physical: " + probe.describe());
            h.assertTrue(mob.getHealth() == probe.initialHealth, "ordinary route retains health");
        });
    }

    private static void floor(GameTestHelper h) {
        for (int x = 0; x < 13; x++) for (int z = 0; z < 13; z++) {
            h.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
            for (int y = 1; y <= 12; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
    }

    private static SettlerEntity spawn(GameTestHelper h, BlockPos relative) {
        SettlerEntity mob = ModEntities.SETTLER.get().create(h.getLevel());
        h.assertTrue(mob != null, "actual settler constructs");
        BlockPos at = h.absolutePos(relative);
        mob.moveTo(at.getX() + .5D, at.getY(), at.getZ() + .5D, 0, 0);
        mob.setOnGround(true);
        for (var goal : List.copyOf(mob.goalSelector.getAvailableGoals())) mob.goalSelector.removeGoal(goal.getGoal());
        h.assertTrue(h.getLevel().addFreshEntity(mob), "actual settler enters world");
        return mob;
    }

    private static void route(GameTestHelper h, SettlerEntity mob, BlockPos target) {
        var path = mob.getNavigation().createPath(target, 0);
        h.assertTrue(path != null && path.canReach(), "exact physical route exists to " + target);
        h.assertTrue(mob.getNavigation().moveTo(path, 1.0D), "physical navigation accepts route");
    }

    private static void setControl(SettlerEntity mob, MoveControl control) {
        try { field(Mob.class, "moveControl").set(mob, control); }
        catch (IllegalAccessException failure) { throw new AssertionError("Cannot install baseline controller", failure); }
    }

    private static Field field(Class<?> type, String name) {
        try { Field f = type.getDeclaredField(name); f.setAccessible(true); return f; }
        catch (ReflectiveOperationException failure) { throw new AssertionError("Cannot inspect " + name, failure); }
    }

    private static final class Probe {
        private final GameTestHelper h;
        private final SettlerEntity mob;
        private final float initialHealth;
        private final ArrayDeque<String> ring = new ArrayDeque<>();
        private final Field operation = field(MoveControl.class, "operation");
        private final Field jumping = field(LivingEntity.class, "jumping");
        private Vec3 previous;

        private Probe(GameTestHelper h, SettlerEntity mob) {
            this.h = h; this.mob = mob; initialHealth = mob.getHealth(); previous = mob.position();
        }
        private void sample() {
            h.assertTrue(mob.isAlive(), "route actor remains alive");
            h.assertTrue(mob.position().distanceToSqr(previous) <= 1.0D, "route never teleports");
            previous = mob.position();
            if (ring.size() == 64) ring.removeFirst();
            ring.addLast(describe());
        }
        private String describe() {
            try {
                var path = mob.getNavigation().getPath();
                return "tick=" + h.getLevel().getGameTime() + " feet=" + mob.position() + " velocity=" + mob.getDeltaMovement()
                    + " ground=" + mob.onGround() + " climb=" + mob.onClimbable() + " collision=" + mob.horizontalCollision
                    + " jumping=" + jumping.get(mob) + " operation=" + operation.get(mob.getMoveControl()) + " yaw=" + mob.getYRot()
                    + " wanted=" + mob.getMoveControl().getWantedX() + "," + mob.getMoveControl().getWantedY() + "," + mob.getMoveControl().getWantedZ()
                    + " path=" + (path == null ? "none" : path.getNextNodeIndex() + "/" + path.getNodeCount()
                        + " next=" + (path.isDone() ? "done" : path.getNextNodePos()) + " reachable=" + path.canReach());
            } catch (IllegalAccessException failure) { throw new AssertionError("Cannot read motor trace", failure); }
        }
        private void dump() { ring.forEach(s -> Hearthstead.LOGGER.info("LADDER_EXIT_DIAGNOSTIC tail {}", s)); }
    }
}
