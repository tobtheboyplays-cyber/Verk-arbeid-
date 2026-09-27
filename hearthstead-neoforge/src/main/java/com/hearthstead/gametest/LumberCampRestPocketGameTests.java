package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.RestAtNightGoal;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.builder.Blueprint;
import com.hearthstead.settlement.builder.BlueprintLibrary;
import com.hearthstead.settlement.builder.BlueprintTransform;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestGenerator;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * QA-REST-01: the actual small-camp resource, preplaced on level ground.
 * Native P1's cell (11,1,0) sealed two air cells between the lean-to and log pile.
 * These tests use live homeless-rest AI and ordinary physical movement, not a forced
 * navigation request. They isolate the blueprint obstruction; they do not reproduce
 * the native hill, energy history or construction process.
 */
@GameTestHolder(Hearthstead.MODID)
public class LumberCampRestPocketGameTests {
    private static final BlockPos ORIGIN = new BlockPos(10, 1, 10);
    private static final BlockPos EXIT = new BlockPos(11, 1, 0);
    private static final BlockPos POCKET = new BlockPos(11, 1, 1);

    @GameTestGenerator
    public static Collection<TestFunction> restPocket() {
        List<TestFunction> tests = new ArrayList<>();
        for (int rotation = 0; rotation < 4; rotation++) {
            int r = rotation;
            tests.add(new TestFunction("rest_pocket_small", "rest_pocket_small_exit_r" + r,
                Hearthstead.MODID + ":empty32", Rotation.NONE, 800, 0L, true, h -> exits(h, r)));
        }
        tests.add(new TestFunction("rest_pocket_small", "rest_pocket_small_original_fence_blocks_exit",
            Hearthstead.MODID + ":empty32", Rotation.NONE, 260, 0L, true, LumberCampRestPocketGameTests::closedControl));
        return tests;
    }

    private static void exits(GameTestHelper h, int rotation) {
        Fixture f = fixture(h, rotation, false);
        h.succeedWhen(() -> {
            h.assertTrue(f.probe.restRan && f.probe.routeSeen, "real rest AI must own an actual walking route");
            h.assertTrue(f.probe.crossedExit, "the resident physically passes through the one removed fence cell");
            h.assertTrue(f.resident.position().distanceToSqr(Vec3.atBottomCenterOf(f.hearth)) <= 20.0D
                && f.resident.getActivity() == SettlerActivity.RESTING,
                "the homeless resident reaches the Hearth and rests: " + f.resident.position()
                    + " route=" + f.resident.routeFailureNote());
            h.assertTrue(h.getLevel().getBlockState(f.exit).isAir(), "the authored opening remains open");
            h.assertTrue(f.resident.getClaimedBed() == null, "this is the homeless Hearth-rest route");
        });
    }

    private static void closedControl(GameTestHelper h) {
        Fixture f = fixture(h, 0, true);
        h.onEachTick(() -> h.assertTrue(inPocket(f), "original closed pocket must not permit physical escape"));
        // Vanilla processes sequences after all due tick callbacks. A scheduled
        // h.succeed() could discard this resident before the same-tick probe runs.
        h.startSequence().thenIdle(220).thenExecute(() -> {
            f.probe.sample();
            h.assertTrue(f.probe.restRan, "negative control must actually attempt the same rest goal");
            h.assertTrue(!f.probe.crossedExit && inPocket(f), "original fence traps the resident throughout the observation");
            h.assertTrue(f.resident.position().distanceToSqr(Vec3.atBottomCenterOf(f.hearth)) > 49.0D,
                "closed pocket remains outside even the existing seven-block rough-rest ring");
            h.assertTrue(h.getLevel().getBlockState(f.exit).is(Blocks.OAK_FENCE), "the original obstacle remains physical");
        }).thenSucceed();
    }

    private static Fixture fixture(GameTestHelper h, int rotation, boolean closed) {
        var level = h.getLevel();
        Blueprint bp = BlueprintLibrary.get(level.getServer(), "lumber_camp_small");
        h.assertTrue(bp != null && bp.sizeX() == 13 && bp.sizeY() == 6 && bp.sizeZ() == 11,
            "actual patched small-camp resource retains its footprint");
        h.assertTrue(bp.cells().stream().noneMatch(c -> c.x() == EXIT.getX() && c.y() == EXIT.getY()
            && c.z() == EXIT.getZ() && !c.state().isAir()), "the shipped resource itself omits the trapping fence");
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                h.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
                for (int y = 2; y <= 9; y++) h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        BlueprintTransform transform = new BlueprintTransform(rotation, false, bp.sizeX(), bp.sizeZ());
        Rotation turn = Rotation.values()[rotation];
        for (Blueprint.Cell cell : bp.cells()) {
            BlockPos at = transformed(h, transform, new BlockPos(cell.x(), cell.y(), cell.z()));
            level.setBlock(at, cell.state().rotate(turn), Block.UPDATE_CLIENTS);
        }
        BlockPos exit = transformed(h, transform, EXIT);
        BlockPos start = transformed(h, transform, POCKET);
        BlockPos other = transformed(h, transform, new BlockPos(10, 1, 1));
        BlockPos hearth = transformed(h, transform, new BlockPos(11, 1, -8));
        h.assertTrue(level.getBlockState(start).isAir() && level.getBlockState(start.above()).isAir()
            && level.getBlockState(other).isAir(), "the original two-cell rest pocket remains");
        h.assertTrue(level.getBlockState(transformed(h, transform, new BlockPos(10, 1, 0))).is(Blocks.OAK_FENCE)
            && level.getBlockState(transformed(h, transform, new BlockPos(12, 1, 1))).is(Blocks.OAK_FENCE),
            "adjacent original fences are preserved");
        for (int y = 1; y <= 2; y++) {
            h.assertTrue(level.getBlockState(transformed(h, transform, new BlockPos(9, y, 1))).is(Blocks.OAK_PLANKS)
                && level.getBlockState(transformed(h, transform, new BlockPos(10, y, 2))).is(Blocks.OAK_LOG)
                && level.getBlockState(transformed(h, transform, new BlockPos(11, y, 2))).is(Blocks.OAK_LOG),
                "lean-to wall and stacked logs preserve the native enclosure");
        }
        if (closed) {
            level.setBlock(exit, Blocks.OAK_FENCE.defaultBlockState()
                .setValue(FenceBlock.EAST, true).setValue(FenceBlock.WEST, true).rotate(turn), Block.UPDATE_ALL);
        }
        level.setBlockAndUpdate(hearth, ModBlocks.HEARTH.get().defaultBlockState());
        Settlement town = new Settlement(UUID.randomUUID(), "Rest pocket", hearth);
        town.radius = 24;
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(town.id, town);
        data.setDirty();
        ((HearthBlockEntity) level.getBlockEntity(hearth)).bindSettlement(town.id);
        SettlerEntity resident = ModEntities.SETTLER.get().create(level);
        h.assertTrue(resident != null, "resident constructs");
        resident.moveTo(start.getX() + 0.5D, start.getY(), start.getZ() + 0.5D, 0.0F, 0.0F);
        resident.setSettlerName("Aldric");
        resident.bindTo(town.id, hearth);
        resident.assignProfession(Profession.LUMBERER);
        town.putRecord(resident.getUUID(), "Aldric", Profession.LUMBERER);
        resident.setEnergy(5.0F);
        resident.setHunger(100.0F);
        h.assertTrue(level.noCollision(resident, resident.getBoundingBox()), "resident begins in genuine clear space");
        h.assertTrue(level.addFreshEntity(resident), "resident enters the live world");
        level.setDayTime(18_000L);
        Probe probe = new Probe(h, resident, exit, hearth);
        h.onEachTick(() -> {
            level.setDayTime(18_000L);
            probe.sample();
        });
        return new Fixture(resident, hearth, exit, start, other, probe);
    }

    private static BlockPos transformed(GameTestHelper h, BlueprintTransform transform, BlockPos local) {
        return h.absolutePos(ORIGIN.offset(transform.x(local.getX(), local.getZ()), local.getY(),
            transform.z(local.getX(), local.getZ())));
    }

    private static boolean inPocket(Fixture f) {
        // The negative control is unrotated. A mob pressing against a thin fence can
        // have its centre in the fence's block cell without having crossed the fence.
        Vec3 feet = f.resident.position();
        return feet.x >= f.other.getX() && feet.x < f.start.getX() + 1.25D
            && feet.z >= f.exit.getZ() + 0.5D && feet.z < f.start.getZ() + 1.0D;
    }

    private record Fixture(SettlerEntity resident, BlockPos hearth, BlockPos exit,
                           BlockPos start, BlockPos other, Probe probe) {}

    private static final class Probe {
        private final GameTestHelper h;
        private final SettlerEntity resident;
        private final BlockPos exit;
        private final Vec3 outward;
        private Vec3 previous;
        private boolean restRan;
        private boolean routeSeen;
        private boolean crossedExit;

        private Probe(GameTestHelper h, SettlerEntity resident, BlockPos exit, BlockPos hearth) {
            this.h = h;
            this.resident = resident;
            this.exit = exit;
            outward = Vec3.atBottomCenterOf(hearth).subtract(Vec3.atBottomCenterOf(exit)).normalize();
            previous = resident.position();
        }

        private void sample() {
            h.assertTrue(resident.isAlive(), "resident remains alive: health=" + resident.getHealth()
                + " removed=" + resident.isRemoved() + " reason=" + resident.getRemovalReason()
                + " feet=" + resident.position() + " entityTicks=" + resident.tickCount
                + " gameTick=" + h.getLevel().getGameTime());
            h.assertTrue(resident.position().distanceToSqr(previous) <= 1.0D,
                "physical exit uses ordinary movement, not a position jump");
            previous = resident.position();
            restRan |= resident.goalSelector.getAvailableGoals().stream()
                .anyMatch(wrapped -> wrapped.isRunning() && wrapped.getGoal() instanceof RestAtNightGoal);
            var path = resident.getNavigation().getPath();
            routeSeen |= path != null && path.getNodeCount() > 1;
            Vec3 offset = new Vec3(resident.getX() - exit.getX() - 0.5D, 0.0D,
                resident.getZ() - exit.getZ() - 0.5D);
            double beyond = offset.dot(outward);
            double sideways = offset.x * outward.z - offset.z * outward.x;
            crossedExit |= beyond > 0.5D + resident.getBbWidth() / 2.0D && Math.abs(sideways) < 0.5D
                && Math.abs(resident.getY() - exit.getY()) < 1.0D;
        }
    }
}
