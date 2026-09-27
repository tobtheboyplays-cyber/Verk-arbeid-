package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.SettlerDoorGoal;
import com.hearthstead.entity.path.RoadNavigation;
import com.hearthstead.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.gametest.framework.StructureUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.control.MoveControl;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.StructureBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Archived partial House departure, not a full build or an injected historical path. */
@GameTestHolder(Hearthstead.MODID)
public final class HouseFenceExitGameTests {
    private static final String TEMPLATE = "qa_house_exit_0658";
    private static final BlockPos SOURCE_MIN = new BlockPos(0, 60, -24);
    private static final BlockPos SOURCE_TARGET = new BlockPos(14, 66, 7);
    private static final int TIMEOUT = 1200;
    private static final double SPEED = .8D; // frozen BuilderWorkGoal:94
    private static final int REPLAN_INTERVAL = 20; // frozen BuilderWorkGoal:100

    @GameTestGenerator
    public static Collection<TestFunction> cases() {
        return List.of(test("ground", new Vec3(18.402967716532263D, 66D, -6.925000011920929D)),
            test("shelf", new Vec3(20.782022794331315D, 67D, -7.2609634055183045D)),
            // Unchanged-geometry west end-gap approach: grass y65, air y66..67.
            // This is a control position, not a claimed historical actor position.
            test("west_control", new Vec3(13.5D, 66D, -8.5D)));
    }

    private static TestFunction test(String name, Vec3 start) {
        return new TestFunction("house_exit", "house_exit_" + name, Hearthstead.MODID + ":" + TEMPLATE,
            Rotation.NONE, TIMEOUT, 0L, true, h -> run(h, name, start));
    }

    private static void run(GameTestHelper h, String name, Vec3 sourceStart) {
        CompoundTag archived = readFixture();
        BlockPos templateOrigin = templateOrigin(h);
        restoreAndVerifyGeometry(h, archived, templateOrigin);
        SettlerEntity mob = ModEntities.SETTLER.get().create(h.getLevel());
        h.assertTrue(mob != null, "House departure actor constructs");
        initializeProfile(h, mob, archived);
        Vec3 offset = Vec3.atLowerCornerOf(templateOrigin).subtract(Vec3.atLowerCornerOf(SOURCE_MIN));
        Vec3 start = sourceStart.add(offset);
        // The only actor positioning. Historical momentum/controller state is
        // intentionally absent: ordinary gravity and steering start naturally.
        mob.moveTo(start.x, start.y, start.z, 249.53121948242188F, 0F);
        for (var goal : List.copyOf(mob.goalSelector.getAvailableGoals())) {
            if (!(goal.getGoal() instanceof SettlerDoorGoal)) mob.goalSelector.removeGoal(goal.getGoal());
        }
        for (var goal : List.copyOf(mob.targetSelector.getAvailableGoals())) mob.targetSelector.removeGoal(goal.getGoal());
        h.assertTrue(h.getLevel().noCollision(mob, mob.getBoundingBox()), "House departure initial whole body is collision-free");
        h.assertTrue(!h.getLevel().noCollision(mob, mob.getBoundingBox().move(0, -.01D, 0)),
            "House departure initial feet have archived physical support");
        h.assertTrue(h.getLevel().addFreshEntity(mob), "House departure actor enters fixture");
        BlockPos target = sourcePos(h, SOURCE_TARGET);
        Probe probe = new Probe(h, mob, name, offset);
        long started = h.getTick();
        long[] lastRequest = {Long.MIN_VALUE};
        boolean[] finished = {false};
        boolean[] crossed = {false};
        h.onEachTick(() -> {
            if (finished[0]) return;
            probe.sample();
            probe.require(mob.isAlive(), "House departure actor remains alive");
            probe.require(mob.getHealth() == probe.initialHealth, "House departure preserves exact health");
            probe.require(h.getLevel().noCollision(mob, mob.getBoundingBox().deflate(1.0E-6D)),
                "House departure respects whole-body block collision");
            probe.require(probe.applied.lengthSqr() <= 1D, "House departure has no discontinuous actor movement");
            Vec3 local = mob.position().subtract(offset).subtract(Vec3.atLowerCornerOf(SOURCE_MIN));
            probe.require(local.x > .3D && local.x < 31.7D && local.z > .3D && local.z < 39.7D
                && local.y >= 0D && local.y + mob.getBbHeight() < 20D,
                "House departure remains inside the archived geometry crop");
            probe.assertBag();
            if (!crossed[0] && mob.getBoundingBox().minZ > offset.z - 4D) {
                crossed[0] = true;
                Hearthstead.LOGGER.info("HOUSE_EXIT body-cleared-house {}", probe.describe());
            }
            long elapsed = h.getTick() - started;
            // Ordinary needs/carry updates derive their own modifiers first.
            // Fixed destination, ordinary exact path requests; no waypoint or
            // injected node/controller state. Replan cadence matches Builder.
            if (elapsed >= 21 && (lastRequest[0] == Long.MIN_VALUE || h.getTick() - lastRequest[0] >= REPLAN_INTERVAL)) {
                lastRequest[0] = h.getTick();
                var path = mob.getNavigation().createPath(target, 0);
                if (path != null) for (int i = 0; i < path.getNodeCount(); i++) {
                    Vec3 cell = Vec3.atLowerCornerOf(path.getNodePos(i)).subtract(offset)
                        .subtract(Vec3.atLowerCornerOf(SOURCE_MIN));
                    if (cell.x < 0 || cell.x >= 32 || cell.y < 0 || cell.y >= 20 || cell.z < 0 || cell.z >= 40) {
                        Hearthstead.LOGGER.error("HOUSE_EXIT out-of-crop route name={} nodes={}", name, probe.nodes(path));
                        probe.require(false, "House route cannot depend on geometry outside the archived crop");
                    }
                }
                boolean accepted = path != null && mob.getNavigation().moveTo(path, SPEED);
                probe.requests++;
                Hearthstead.LOGGER.info("HOUSE_EXIT request name={} elapsed={} accepted={} reachable={} nodes={}",
                    name, elapsed, accepted, path != null && path.canReach(), probe.nodes(path));
            }
        });
        // Short assertions keep the failure lectern below its encoding limit.
        // Full bounded evidence lives only in the logger, including timeout.
        GameTestTicks.at(h, TIMEOUT - 20, () -> {
            if (!finished[0]) {
                finished[0] = true;
                probe.dump("deadline");
                h.assertTrue(false, "House departure did not physically reach the Hut within 1180 ticks");
            }
        });
        h.startSequence().thenWaitUntil(() -> {
            Vec3 delta = mob.position().subtract(Vec3.atBottomCenterOf(target));
            h.assertTrue(crossed[0] && delta.x * delta.x + delta.z * delta.z < .25D && Math.abs(delta.y) < .6D,
                "House departure must physically clear the House and arrive at the archived Hut target");
            h.assertTrue(mob.getHealth() == probe.initialHealth, "House departure arrival preserves exact health");
            probe.assertBag();
        }).thenExecute(() -> {
            finished[0] = true;
            Hearthstead.LOGGER.info("HOUSE_EXIT success {}", probe.describe());
        }).thenSucceed();
    }

    private static BlockPos sourcePos(GameTestHelper h, BlockPos source) {
        return templateOrigin(h).offset(source.subtract(SOURCE_MIN));
    }

    private static BlockPos templateOrigin(GameTestHelper h) {
        // GameTestHelper.absolutePos is relative to the controller block.
        // Structure cells start at its structurePos (normally one block above).
        var marker = h.getLevel().getBlockEntity(h.absolutePos(BlockPos.ZERO));
        h.assertTrue(marker instanceof StructureBlockEntity, "House fixture controller is a structure block entity");
        return StructureUtils.getStructureOrigin((StructureBlockEntity) marker);
    }

    private static CompoundTag readFixture() {
        try (var in = HouseFenceExitGameTests.class.getResourceAsStream(
                "/data/hearthstead/structure/" + TEMPLATE + ".nbt")) {
            if (in == null) throw new AssertionError("House archive fixture resource is missing");
            return NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        } catch (IOException failure) {
            throw new AssertionError("House archive fixture cannot be read", failure);
        }
    }

    private static void restoreAndVerifyGeometry(GameTestHelper h, CompoundTag archived, BlockPos templateOrigin) {
        h.assertTrue(java.util.Arrays.equals(archived.getIntArray("house_exit_source_min"), new int[] {0, 60, -24}),
            "House archive origin is exact");
        ListTag palette = archived.getList("palette", Tag.TAG_COMPOUND);
        List<BlockState> states = new ArrayList<>();
        for (int i = 0; i < palette.size(); i++) states.add(NbtUtils.readBlockState(
            h.getLevel().holderLookup(Registries.BLOCK), palette.getCompound(i)));
        ListTag blocks = archived.getList("blocks", Tag.TAG_COMPOUND);
        h.assertTrue(blocks.size() == 25600, "House archive includes every cell, including air");
        // StructureTemplate normally recomputes neighbor-dependent shapes.
        // Replay saved states instead of those newly normalized connections.
        // UPDATE_KNOWN_SHAPE skips shape propagation; no UPDATE_NEIGHBORS.
        // This setup-only pass finishes before the actor exists. A separate
        // full comparison below still rejects any imperfect restoration.
        int restored = 0;
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompound(i);
            ListTag xyz = entry.getList("pos", Tag.TAG_INT);
            BlockPos relative = new BlockPos(xyz.getInt(0), xyz.getInt(1), xyz.getInt(2));
            BlockPos at = templateOrigin.offset(relative);
            BlockState expected = states.get(entry.getInt("state"));
            BlockState actual = h.getLevel().getBlockState(at);
            if (!actual.equals(expected)) {
                if (restored < 16) Hearthstead.LOGGER.info("HOUSE_EXIT restore source={} from={} archived={}",
                    relative.offset(SOURCE_MIN), actual, expected);
                h.assertTrue(h.getLevel().setBlock(at, expected, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE),
                    "House archived block state restoration must succeed");
                restored++;
            }
        }
        Hearthstead.LOGGER.info("HOUSE_EXIT restored-normalized-cells={} before actor initialization", restored);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag entry = blocks.getCompound(i);
            ListTag xyz = entry.getList("pos", Tag.TAG_INT);
            BlockPos relative = new BlockPos(xyz.getInt(0), xyz.getInt(1), xyz.getInt(2));
            BlockState expected = states.get(entry.getInt("state"));
            BlockState actual = h.getLevel().getBlockState(templateOrigin.offset(relative));
            if (!actual.equals(expected)) {
                Hearthstead.LOGGER.error("HOUSE_EXIT geometry mismatch source={} expected={} actual={}",
                    relative.offset(SOURCE_MIN), expected, actual);
                h.assertTrue(false, "House restored fixture must preserve every archived block state");
            }
        }
    }

    private static void initializeProfile(GameTestHelper h, SettlerEntity mob, CompoundTag archived) {
        CompoundTag profile = archived.getCompound("house_exit_profile");
        CompoundTag state = new CompoundTag();
        mob.addAdditionalSaveData(state);
        for (String key : profile.getAllKeys()) {
            if (!key.equals("CustomName")) state.put(key, profile.get(key).copy());
        }
        // Do not bind a copied actor to a live settlement or claimed bed.
        mob.readAdditionalSaveData(state);
        // Entity handles CustomName outside read/addAdditionalSaveData. Use
        // the same serializer and ordinary setter without loading identity/position.
        mob.setCustomName(Component.Serializer.fromJson(profile.getString("CustomName"), mob.registryAccess()));
        h.assertTrue(mob.getCustomName() != null
            && profile.getString("CustomName").equals(Component.Serializer.toJson(mob.getCustomName(), mob.registryAccess())),
            "House exact saved custom name round-trips");
        mob.bag.setItem(0, new ItemStack(Items.OAK_PLANKS, 2));
        mob.bag.setItem(1, new ItemStack(Items.WHITE_BED));
        mob.bag.setItem(2, new ItemStack(Items.OAK_DOOR));
        mob.bag.setItem(3, new ItemStack(Items.GLASS_PANE, 5));
        mob.bag.setItem(4, new ItemStack(Items.TORCH));
        mob.bag.setItem(5, new ItemStack(Items.OAK_STAIRS, 12));
        CompoundTag check = new CompoundTag();
        mob.addAdditionalSaveData(check);
        for (String key : new String[] {"Attributes", "Traits", "EffortLeft", "CarryCapacity", "Morale", "Hunger", "Energy",
                "Profession", "Traveler", "TradeSkills", "ResidentMeal", "Appearance"}) {
            h.assertTrue(profile.get(key).equals(check.get(key)), "House exact saved profile round-trips: " + key);
        }
        h.assertTrue(check.getList("Bag", Tag.TAG_COMPOUND).equals(archived.getList("house_exit_saved_bag", Tag.TAG_COMPOUND)),
            "House ordinary bag initialization equals the archived cargo");
        h.assertTrue(mob.getHealth() == 24F && mob.getMaxHealth() == 24F
            && mob.getAttribute(Attributes.MOVEMENT_SPEED).getBaseValue() == .3D,
            "House saved health and base movement attributes are preserved");
    }

    private static final class Probe {
        private final GameTestHelper h;
        private final SettlerEntity mob;
        private final String name;
        private final Vec3 offset;
        private final float initialHealth;
        private final List<ItemStack> bag = new ArrayList<>();
        private final ArrayDeque<String> ring = new ArrayDeque<>();
        private final Field operation;
        private String lastPath = "";
        private Vec3 previous;
        private Vec3 applied = Vec3.ZERO;
        private int requests;
        private int emittedPaths;
        private boolean dumped;

        private Probe(GameTestHelper h, SettlerEntity mob, String name, Vec3 offset) {
            this.h = h; this.mob = mob; this.name = name; this.offset = offset;
            initialHealth = mob.getHealth(); previous = mob.position();
            for (int i = 0; i < mob.bag.getContainerSize(); i++) bag.add(mob.bag.getItem(i).copy());
            try {
                operation = MoveControl.class.getDeclaredField("operation");
                operation.setAccessible(true);
            } catch (ReflectiveOperationException failure) {
                throw new AssertionError("House probe cannot inspect MoveControl", failure);
            }
        }

        private void require(boolean condition, String message) {
            if (!condition) dump(message);
            h.assertTrue(condition, message);
        }

        private void assertBag() {
            for (int i = 0; i < bag.size(); i++) require(ItemStack.matches(bag.get(i), mob.bag.getItem(i)),
                "House departure preserves exact carried inventory");
        }

        private void sample() {
            applied = mob.position().subtract(previous);
            previous = mob.position();
            if (ring.size() == 64) ring.removeFirst();
            ring.addLast(describe());
            String current = nodes(mob.getNavigation().getPath());
            if (!current.equals(lastPath)) {
                if (emittedPaths++ < 128) Hearthstead.LOGGER.info("HOUSE_EXIT path-change name={} tick={} nodes={}",
                    name, h.getTick(), current);
                lastPath = current;
            }
        }

        private String nodes(net.minecraft.world.level.pathfinder.Path path) {
            if (path == null) return "none";
            StringBuilder out = new StringBuilder("reachable=" + path.canReach() + " [");
            for (int i = 0; i < path.getNodeCount(); i++) {
                var node = path.getNode(i);
                Vec3 source = Vec3.atLowerCornerOf(path.getNodePos(i)).subtract(offset);
                out.append(i).append(':').append(source).append('/').append(node.type).append(';');
            }
            return out.append(']').toString();
        }

        private String describe() {
            try {
                var path = mob.getNavigation().getPath();
                AABB box = mob.getBoundingBox().move(-offset.x, -offset.y, -offset.z);
                return name + " tick=" + h.getTick() + " feet=" + mob.position().subtract(offset)
                    + " box=" + box + " applied=" + applied + " velocity=" + mob.getDeltaMovement()
                    + " ground=" + mob.onGround() + " collision=" + mob.horizontalCollision + "/" + mob.verticalCollision
                    + " control=" + operation.get(mob.getMoveControl()) + " yaw=" + mob.getYRot()
                    + " wanted=" + new Vec3(mob.getMoveControl().getWantedX(), mob.getMoveControl().getWantedY(),
                        mob.getMoveControl().getWantedZ()).subtract(offset)
                    + " speed=" + mob.getAttributeValue(Attributes.MOVEMENT_SPEED) + " energy=" + mob.getEnergy()
                    + " health=" + mob.getHealth() + " carry=" + mob.carryFraction() + " requests=" + requests
                    + " stalled=" + (mob.getNavigation() instanceof RoadNavigation road ? road.stalledTicks() : -1)
                    + " path=" + (path == null ? "none" : path.getNextNodeIndex() + "/" + path.getNodeCount()
                        + " next=" + (path.isDone() ? "done" : Vec3.atLowerCornerOf(path.getNextNodePos()).subtract(offset)));
            } catch (IllegalAccessException failure) {
                throw new AssertionError("House probe cannot inspect MoveControl", failure);
            }
        }

        private void dump(String cause) {
            if (dumped) return;
            dumped = true;
            Hearthstead.LOGGER.error("HOUSE_EXIT terminal cause={} {} nodes={}", cause, describe(), nodes(mob.getNavigation().getPath()));
            ring.forEach(row -> Hearthstead.LOGGER.info("HOUSE_EXIT tail {}", row));
        }
    }
}
