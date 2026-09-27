package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.path.SealedGates;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.builder.BuildSiteSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.pathfinder.Path;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * Defense gates (coordinator, 26 Sep): settlers may path through a closed
 * palisade gate in peace, never while a raid or alarm is on -- they would
 * open the wall for the raiders. Batch {@code builder_gate}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class DefenseGateGameTests {

    @GameTest(template = "empty16", timeoutTicks = 200, batch = "builder_gate")
    public void aDefenseGateIsSealedForSettlersDuringAnAlarmOnly(GameTestHelper helper) {
        BuilderTestKit.Arena arena = BuilderTestKit.arena(helper, 16, 4);
        // A cobblestone wall across the arena at z = 8, with one fence gate at x = 8.
        for (int x = 0; x < 16; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 8), Blocks.COBBLESTONE_WALL);
            }
        }
        BlockPos gateRel = new BlockPos(8, 1, 8);
        helper.setBlock(gateRel, Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(FenceGateBlock.FACING, Direction.SOUTH).setValue(FenceGateBlock.OPEN, false));
        helper.setBlock(gateRel.above(), Blocks.AIR);
        helper.setBlock(gateRel.above(2), Blocks.AIR);
        BlockPos gate = helper.absolutePos(gateRel);
        BuildSiteSavedData.get(arena.level()).recordDefense(arena.settlement().id,
            new BuildSiteSavedData.DefenseWork(UUID.randomUUID(), "palisade", new long[]{gate.asLong()}, List.of(gate)));
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(8, 1, 4));
        settler.bindTo(arena.settlement().id, arena.settlement().center);
        BlockPos outside = helper.absolutePos(new BlockPos(8, 1, 13));

        // Let him land first: navigation plans only from the ground.
        helper.runAfterDelay(20, () -> {
            SealedGates.refresh(arena.level());
            Path peace = settler.getNavigation().createPath(outside, 0);
            helper.assertTrue(peace != null && peace.canReach() && passes(peace, gate),
                "in peace the route goes through the closed gate: " + describe(peace));

            arena.settlement().alertUntilGameTime = arena.level().getGameTime() + 2000L;
            SealedGates.refresh(arena.level());
            Path alarm = settler.getNavigation().createPath(outside, 0);
            helper.assertTrue(alarm == null || !alarm.canReach() || !passes(alarm, gate),
                "during an alarm the defense gate is never on the route: " + describe(alarm));

            arena.settlement().alertUntilGameTime = 0L;
            SealedGates.refresh(arena.level());
            helper.succeed();
        });
    }

    private static boolean passes(Path path, BlockPos gate) {
        for (int i = 0; i < path.getNodeCount(); i++) {
            if (path.getNode(i).asBlockPos().equals(gate)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(Path path) {
        if (path == null) {
            return "no path";
        }
        StringBuilder out = new StringBuilder("reach=" + path.canReach() + " nodes=");
        for (int i = 0; i < path.getNodeCount() && i < 24; i++) {
            out.append(path.getNode(i).asBlockPos().toShortString()).append(' ');
        }
        return out.toString();
    }
}
