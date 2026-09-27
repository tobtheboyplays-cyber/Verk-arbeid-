package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/** QA-DOOR-EDGE-01: observed doorway/load, real steering, no production changes. */
@GameTestHolder(Hearthstead.MODID)
public final class DoorLeafEdgeGameTests {
    private static final String BATCH = "door_edge";
    private static final int TIMEOUT = 600;
    private static final double BUILDER_SPEED = .8D;
    private static final double OBSERVED_OFFSET = .014391169D;

    @GameTestGenerator
    public static Collection<TestFunction> cases() {
        List<TestFunction> tests = new ArrayList<>();
        for (Rotation rotation : Rotation.values()) {
            for (DoorHingeSide hinge : DoorHingeSide.values()) {
                for (boolean edge : new boolean[] {true, false}) {
                    String name = BATCH + "_" + rotation.rotate(Direction.NORTH).getName()
                        + "_" + hinge.name().toLowerCase(Locale.ROOT) + "_" + (edge ? "edge" : "center");
                    tests.add(new TestFunction(BATCH, name, Hearthstead.MODID + ":empty16",
                        Rotation.NONE, TIMEOUT, 0L, true, h -> run(h, rotation, hinge, edge, name)));
                }
            }
        }
        for (Rotation rotation : Rotation.values()) {
            for (DoorHingeSide hinge : DoorHingeSide.values()) {
                for (boolean edge : new boolean[] {true, false}) {
                    String name = BATCH + "_snapshot_" + rotation.rotate(Direction.NORTH).getName()
                        + "_" + hinge.name().toLowerCase(Locale.ROOT) + "_" + (edge ? "edge" : "center");
                    tests.add(new TestFunction(BATCH, name, Hearthstead.MODID + ":empty16",
                        Rotation.NONE, TIMEOUT, 0L, true, h -> run(h, rotation, hinge, edge, name, true)));
                }
            }
        }
        return tests;
    }

    private static void run(GameTestHelper h, Rotation rotation, DoorHingeSide hinge,
                            boolean edge, String name) {
        run(h, rotation, hinge, edge, name, false);
    }

    private static void run(GameTestHelper h, Rotation rotation, DoorHingeSide hinge,
                            boolean edge, String name, boolean snapshot) {
        fixture(h, rotation, hinge);
        BlockPos door = h.absolutePos(new BlockPos(7, 1, 7));
        Direction inward = rotation.rotate(Direction.NORTH);
        Vec3 sideways = turn(1.0D, 0.0D, rotation);
        double offset = edge ? (hinge == DoorHingeSide.LEFT ? -OBSERVED_OFFSET : OBSERVED_OFFSET) : 0.0D;
        Vec3 startOffset = turn(offset, .800000012D, rotation);
        Vec3 start = new Vec3(door.getX() + .5D + startOffset.x,
            door.getY() - .0625D, door.getZ() + .5D + startOffset.z);
        SettlerEntity mob = ModEntities.SETTLER.get().create(h.getLevel());
        h.assertTrue(mob != null, "actual settler constructs");
        mob.moveTo(start.x, start.y, start.z,
            snapshot ? inward.toYRot() + (hinge == DoorHingeSide.LEFT ? 1.03073120117188F : -1.03073120117188F)
                : inward.toYRot(),
            snapshot ? .6033605337142944F : 0.0F); // sole initial positioning
        for (var goal : List.copyOf(mob.goalSelector.getAvailableGoals())) mob.goalSelector.removeGoal(goal.getGoal());
        if (snapshot) initializeSnapshot(h, mob);
        // The original trace carried these actual items. Empty actors can have
        // enough lateral speed to escape the small-correction dead zone.
        mob.bag.setItem(0, new ItemStack(Items.GLASS_PANE, 23));
        mob.bag.setItem(1, new ItemStack(Items.LANTERN, 4));
        mob.bag.setItem(2, new ItemStack(Items.WHITE_BANNER));
        mob.bag.setItem(3, new ItemStack(Items.WHITE_BED));
        mob.bag.setItem(4, new ItemStack(Items.WHITE_BED));
        mob.bag.setItem(5, new ItemStack(Items.WHITE_BED));
        mob.bag.setItem(6, new ItemStack(Items.TORCH));
        h.assertTrue(h.getLevel().noCollision(mob, mob.getBoundingBox()), "observed start is collision-free before entry");
        h.assertTrue(h.getLevel().addFreshEntity(mob), "actual settler enters world");
        Probe probe = new Probe(h, mob, door, name);
        List<ItemStack> originalBag = new ArrayList<>();
        for (int i = 0; i < mob.bag.getContainerSize(); i++) originalBag.add(mob.bag.getItem(i).copy());
        BlockPos inside = door.relative(inward, 3);
        BlockPos outside = door.relative(inward.getOpposite(), 3);
        boolean[] crossed = {false};
        boolean[] arrivedInside = {false};
        boolean[] finished = {false};
        // Snapshot modifiers are transient and absent from NBT. Let the first
        // ordinary needs tick at age20 derive fatigue; never synthesize speed.
        // The existing fresh-actor controls retain their original five ticks.
        GameTestTicks.at(h, snapshot ? 21 : 5, () -> {
            double lateral = mob.position().subtract(Vec3.atBottomCenterOf(door)).dot(sideways);
            h.assertTrue(Math.abs(lateral - offset) < 1.0E-5D, "start offset survives ordinary settling");
            route(h, mob, inside, door);
            Hearthstead.LOGGER.info("DOOR_EDGE_DIAGNOSTIC start {}", probe.describe());
        });
        h.onEachTick(() -> {
            if (finished[0]) return; // completion removes actors before all callbacks necessarily finish
            probe.sample();
            h.assertTrue(h.getLevel().getBlockState(door).getValue(DoorBlock.OPEN), "isolated leaf remains open");
            double inwardDistance = (mob.getX() - door.getX() - .5D) * inward.getStepX()
                + (mob.getZ() - door.getZ() - .5D) * inward.getStepZ();
            if (!crossed[0] && inwardDistance > .5D + mob.getBbWidth() / 2.0D) {
                crossed[0] = true;
                Hearthstead.LOGGER.info("DOOR_EDGE_DIAGNOSTIC body-crossed {}", probe.describe());
            }
            if (crossed[0] && !arrivedInside[0]
                && mob.position().distanceToSqr(Vec3.atBottomCenterOf(inside)) < .25D) {
                arrivedInside[0] = true;
                route(h, mob, outside, door);
                Hearthstead.LOGGER.info("DOOR_EDGE_DIAGNOSTIC inside-and-return {}", probe.describe());
            }
        });
        GameTestTicks.at(h, TIMEOUT - 20, () -> { if (!finished[0]) probe.dump(); });
        h.startSequence().thenWaitUntil(() -> {
            h.assertTrue(crossed[0] && arrivedInside[0]
                && mob.position().distanceToSqr(Vec3.atBottomCenterOf(outside)) < .25D,
                "actual whole-body doorway traversal, indoor arrival and return; crossed=" + crossed[0]
                    + " inside=" + arrivedInside[0] + " " + probe.describe());
            h.assertTrue(mob.getHealth() == probe.initialHealth, "door traversal preserves health");
            for (int i = 0; i < originalBag.size(); i++) {
                h.assertTrue(ItemStack.matches(originalBag.get(i), mob.bag.getItem(i)), "observed carried inventory preserved at slot " + i);
            }
        }).thenExecute(() -> {
            finished[0] = true;
            Hearthstead.LOGGER.info("DOOR_EDGE_DIAGNOSTIC success {}", probe.describe());
        }).thenSucceed();
    }

    private static void initializeSnapshot(GameTestHelper h, SettlerEntity mob) {
        // Exact persisted profile from UUID92307add-e9ea-4d9f-a984-f316db58bf54,
        // entities/r.-26351.-4665.mca SHA bfb75880bb833c6ad1db7db3171e8d8e9f8e1a96d2f176d602c6fa2be14e856d.
        // Preserve this fixture's identity, translated position and ordinary
        // brain/controller. Load only evidenced persistent gameplay fields,
        // before filling the bag so real carry slowdown uses this stamina.
        CompoundTag state = new CompoundTag();
        mob.addAdditionalSaveData(state);
        CompoundTag attributes = new CompoundTag();
        attributes.putInt("Schema", 2);
        attributes.putByte("Knack", (byte) 6);
        attributes.putIntArray("Values", new int[] {1, 1, 9, 17, 9, 4, 7, 1});
        attributes.putIntArray("ProgressBits", new int[] {0, 0, 0, 1063602967, 0, 0, 0, 0});
        state.put("Attributes", attributes);
        state.putFloat("Energy", 48.76271057128906F);
        state.putFloat("Hunger", 0.0F);
        state.putFloat("Morale", 20.0F);
        state.putFloat("EffortLeft", 5.0F);
        state.putInt("CarryCapacity", 8);
        ListTag traits = new ListTag();
        traits.add(StringTag.valueOf("night_owl"));
        state.put("Traits", traits);
        mob.readAdditionalSaveData(state);
        CompoundTag reloaded = new CompoundTag();
        mob.addAdditionalSaveData(reloaded);
        h.assertTrue(reloaded.getCompound("Attributes").equals(attributes), "exact saved attribute profile round-trips");
        for (String field : new String[] {"Energy", "Hunger", "Morale", "EffortLeft", "CarryCapacity", "Traits"}) {
            h.assertTrue(state.get(field).equals(reloaded.get(field)), "exact saved initialization field: " + field);
        }
        h.assertTrue(mob.getCarryCapacity() == 8 && mob.getEnergy() == 48.76271057128906F
            && mob.attribute(com.hearthstead.entity.Attribute.STAMINA) == 1,
            "snapshot starts with observed capacity, energy and stamina");
    }

    private static void fixture(GameTestHelper h, Rotation rotation, DoorHingeSide hinge) {
        for (int x = 1; x <= 13; x++) for (int z = 1; z <= 13; z++) {
            put(h, rotation, x, 0, z, (z > 7 ? Blocks.GRASS_BLOCK : z == 7 ? Blocks.STONE_BRICKS : Blocks.DARK_OAK_PLANKS).defaultBlockState());
            for (int y = 1; y <= 5; y++) put(h, rotation, x, y, z, Blocks.AIR.defaultBlockState());
        }
        // Outer walls only bound the test route; the doorway's immediate
        // geometry matches the saved Tavern. The route must use its opening.
        for (int n = 1; n <= 13; n++) for (int y = 1; y <= 4; y++) {
            put(h, rotation, 1, y, n, Blocks.COBBLESTONE.defaultBlockState());
            put(h, rotation, 13, y, n, Blocks.COBBLESTONE.defaultBlockState());
            put(h, rotation, n, y, 1, Blocks.COBBLESTONE.defaultBlockState());
            put(h, rotation, n, y, 13, Blocks.COBBLESTONE.defaultBlockState());
        }
        for (int x = 1; x <= 13; x++) for (int y = 1; y <= 3; y++) {
            if (x != 7 || y == 3) put(h, rotation, x, y, 7,
                (x == 7 ? Blocks.DARK_OAK_LOG : Blocks.COBBLESTONE).defaultBlockState());
        }
        put(h, rotation, 7, 0, 8, Blocks.DIRT_PATH.defaultBlockState());
        put(h, rotation, 7, 0, 9, Blocks.DIRT_PATH.defaultBlockState());
        BlockState lower = Blocks.DARK_OAK_DOOR.defaultBlockState().setValue(DoorBlock.FACING, Direction.NORTH)
            .setValue(DoorBlock.HINGE, hinge).setValue(DoorBlock.OPEN, true).setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
        put(h, rotation, 7, 1, 7, lower);
        put(h, rotation, 7, 2, 7, lower.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void put(GameTestHelper h, Rotation rotation, int x, int y, int z, BlockState state) {
        BlockPos relative = new BlockPos(x - 7, y, z - 7).rotate(rotation).offset(7, 0, 7);
        h.getLevel().setBlock(h.absolutePos(relative), state.rotate(rotation), Block.UPDATE_CLIENTS);
    }

    private static Vec3 turn(double x, double z, Rotation rotation) {
        return switch (rotation) {
            case NONE -> new Vec3(x, 0.0D, z);
            case CLOCKWISE_90 -> new Vec3(-z, 0.0D, x);
            case CLOCKWISE_180 -> new Vec3(-x, 0.0D, -z);
            case COUNTERCLOCKWISE_90 -> new Vec3(z, 0.0D, -x);
        };
    }

    private static void route(GameTestHelper h, SettlerEntity mob, BlockPos target, BlockPos door) {
        var path = mob.getNavigation().createPath(target, 0);
        h.assertTrue(path != null && path.canReach(), "fixture has an exact reachable route to " + target);
        boolean entersDoor = false;
        for (int i = 0; i < path.getNodeCount(); i++) if (path.getNodePos(i).equals(door)) entersDoor = true;
        h.assertTrue(entersDoor, "actual route uses the evidenced doorway");
        h.assertTrue(mob.getNavigation().moveTo(path, BUILDER_SPEED), "ordinary Builder-speed route accepted");
    }

    private static final class Probe {
        private final GameTestHelper h;
        private final SettlerEntity mob;
        private final BlockPos door;
        private final String name;
        private final float initialHealth;
        private final ArrayDeque<String> ring = new ArrayDeque<>();
        private final Field operation;
        private Vec3 previous;
        private Vec3 applied = Vec3.ZERO;

        private Probe(GameTestHelper h, SettlerEntity mob, BlockPos door, String name) {
            this.h = h; this.mob = mob; this.door = door; this.name = name;
            initialHealth = mob.getHealth(); previous = mob.position();
            try { operation = MoveControl.class.getDeclaredField("operation"); operation.setAccessible(true); }
            catch (ReflectiveOperationException failure) { throw new AssertionError("cannot inspect motor operation", failure); }
        }
        private void sample() {
            h.assertTrue(mob.isAlive(), "route actor remains alive: " + name);
            applied = mob.position().subtract(previous);
            h.assertTrue(applied.lengthSqr() <= 1.0D, "route never teleports: " + name);
            h.assertTrue(h.getLevel().noCollision(mob, mob.getBoundingBox().deflate(1.0E-6D)),
                "route respects physical collision: " + name);
            previous = mob.position();
            if (ring.size() == 32) ring.removeFirst();
            ring.addLast(describe());
        }
        private String describe() {
            try {
                var path = mob.getNavigation().getPath();
                return name + " tick=" + h.getTick() + " feet=" + mob.position() + " box=" + mob.getBoundingBox()
                    + " applied=" + applied + " velocity=" + mob.getDeltaMovement() + " ground=" + mob.onGround()
                    + " collision=" + mob.horizontalCollision + " operation=" + operation.get(mob.getMoveControl())
                    + " yaw=" + mob.getYRot() + " speed=" + mob.getAttributeValue(Attributes.MOVEMENT_SPEED)
                    + " energy=" + mob.getEnergy() + " carryFraction=" + mob.carryFraction()
                    + " wanted=" + mob.getMoveControl().getWantedX() + "," + mob.getMoveControl().getWantedY() + "," + mob.getMoveControl().getWantedZ()
                    + " door=" + h.getLevel().getBlockState(door)
                    + " path=" + (path == null ? "none" : path.getNextNodeIndex() + "/" + path.getNodeCount()
                        + " next=" + (path.isDone() ? "done" : path.getNextNodePos()) + " reachable=" + path.canReach());
            } catch (IllegalAccessException failure) { throw new AssertionError("cannot inspect motor operation", failure); }
        }
        private void dump() { ring.forEach(s -> Hearthstead.LOGGER.info("DOOR_EDGE_DIAGNOSTIC tail {}", s)); }
    }
}
