package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.MineShaft;
import com.hearthstead.entity.ai.MinerEscapeGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Reliability soak 2026-09-25: a Miner cut a vertical 5x5 shaft under its
 * own mine, could not climb out, and starved at the bottom. These pin both
 * halves of the fix: a working Miner only cuts staircases (MineShaft), and a
 * Miner already at the bottom of an old shaft carves steps out
 * (MinerEscapeGoal).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class MinerPitGameTests {
    /** Top of the solid rock block; the mine anchor stands one above it. */
    private static final int ROCK_TOP = 6;

    private static Settlement settlement(GameTestHelper helper) {
        var data = com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Djupgruva",
            helper.absolutePos(new BlockPos(8, ROCK_TOP + 1, 8)));
        s.radius = 8;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static void rock(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = 0; y <= ROCK_TOP; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                }
                for (int y = ROCK_TOP + 1; y < 12; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static Building mine(GameTestHelper helper, Settlement s) {
        BlockPos anchorRel = new BlockPos(8, ROCK_TOP + 1, 8);
        Building mine = GameTestFixtures.registerWithBounds(helper, s, BuildingType.MINE,
            anchorRel, new BlockPos(1, ROCK_TOP + 2, 1),
            BoundingBox.fromCorners(helper.absolutePos(new BlockPos(1, ROCK_TOP + 1, 1)),
                helper.absolutePos(new BlockPos(15, ROCK_TOP + 3, 15))));
        helper.setBlock(new BlockPos(14, ROCK_TOP + 1, 14), Blocks.CHEST);
        return mine;
    }

    private static SettlerEntity miner(GameTestHelper helper, Settlement s, Building mine, BlockPos at) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), at);
        settler.setSettlerName("Grube");
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), "Grube", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, mine, settler).ok(), "hire miner");
        // QA-JOBS J-10: a Miner only digs with a real pickaxe in hand.
        settler.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
            new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_PICKAXE));
        settler.setHunger(100.0F);
        settler.setEnergy(100.0F);
        return settler;
    }

    /** A Miner dropped at the bottom of an old 1x1, 4-deep shaft climbs out by carving steps. */
    @GameTest(batch = "miner_pit", template = "empty16", timeoutTicks = 2400)
    public void aMinerAtTheBottomOfAnOldShaftCarvesItsWayOut(GameTestHelper helper) {
        rock(helper);
        for (int y = ROCK_TOP - 3; y <= ROCK_TOP; y++) {
            helper.setBlock(new BlockPos(8, y, 8), Blocks.AIR);
        }
        Settlement s = settlement(helper);
        Building mine = mine(helper, s);
        SettlerEntity grube = miner(helper, s, mine, new BlockPos(8, ROCK_TOP - 3, 8));
        helper.getLevel().setDayTime(13000); // night: only the escape may move it
        int surface = helper.absolutePos(new BlockPos(0, ROCK_TOP + 1, 0)).getY();
        helper.succeedWhen(() -> helper.assertTrue(grube.blockPosition().getY() >= surface,
            "trapped miner must reach the surface, at " + helper.relativePos(grube.blockPosition())
                + " (surface y=" + (ROCK_TOP + 1) + ") onGround=" + grube.onGround()
                + " note=" + grube.routeFailureNote() + " goals=" + runningGoals(grube)));
    }

    /**
     * The REAL legacy pit from the captain1 soak world (Thyra, 627,68,573,
     * trapped 2.7 h). A 7x7 plank mine room with its floor layer cut out,
     * AND the layer below it cut in a wide 10x13 sheet reaching under the
     * walls and out beyond the room under the grass: a flat two-deep pit
     * whose only walls are 2-3 cells away, with the Miner standing in the
     * corner under the room's own wall. Geometry copied cell for cell,
     * relative to the mine anchor (630,70,570): surface layer y=69 (here
     * ROCK_TOP), sheet y=68.
     */
    @GameTest(batch = "miner_pit", template = "empty32", timeoutTicks = 3000)
    public void aMinerInTheRealWideLegacyPitWalksToAWallAndClimbsOut(GameTestHelper helper) {
        int top = 3; // surface layer; the sheet is top-1
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                for (int y = 0; y < top; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.STONE);
                helper.setBlock(new BlockPos(x, top, z), Blocks.GRASS_BLOCK);
                for (int y = top + 1; y < 8; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
            }
        }
        int ax = 16, az = 16; // anchor column
        // room walls, planks, anchor +-3, three high, door gap on the south wall
        for (int d = -3; d <= 3; d++) {
            for (int y = top + 1; y <= top + 3; y++) {
                helper.setBlock(new BlockPos(ax + d, y, az - 3), Blocks.OAK_PLANKS);
                helper.setBlock(new BlockPos(ax + d, y, az + 3), Blocks.OAK_PLANKS);
                helper.setBlock(new BlockPos(ax - 3, y, az + d), Blocks.OAK_PLANKS);
                helper.setBlock(new BlockPos(ax + 3, y, az + d), Blocks.OAK_PLANKS);
            }
        }
        // captain1 had also cut the corner plank at (627,70,573) = (-3, top+1, +3)
        helper.setBlock(new BlockPos(ax - 3, top + 1, az + 3), Blocks.AIR);
        // the floor layer, cut inside the room AND under its walls (dx,dz -3..+3)
        for (int dx = -3; dx <= 3; dx++) for (int dz = -3; dz <= 3; dz++)
            helper.setBlock(new BlockPos(ax + dx, top, az + dz), Blocks.AIR);
        // the wide sheet one below: dx -6..+3, dz -6..+6 (x 624..633, z 564..576)
        for (int dx = -6; dx <= 3; dx++) for (int dz = -6; dz <= 6; dz++)
            helper.setBlock(new BlockPos(ax + dx, top - 1, az + dz), Blocks.AIR);
        Settlement s = new Settlement(java.util.UUID.randomUUID(), "Thyrasgruva",
            helper.absolutePos(new BlockPos(ax, top + 1, az)));
        s.radius = 12;
        var data = com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        data.settlements.put(s.id, s);
        data.setDirty();
        Building mine = GameTestFixtures.registerWithBounds(helper, s, BuildingType.MINE,
            new BlockPos(ax, top + 1, az), new BlockPos(ax, top + 2, az - 4),
            net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                helper.absolutePos(new BlockPos(ax - 3, top, az - 3)),
                helper.absolutePos(new BlockPos(ax + 3, top + 3, az + 3))));
        // Thyra's exact spot: under the south-west wall corner, on the sheet.
        SettlerEntity thyra = miner(helper, s, mine, new BlockPos(ax - 3, top - 1, az + 3));
        helper.getLevel().setDayTime(13000);
        int surface = helper.absolutePos(new BlockPos(0, top + 1, 0)).getY();
        helper.succeedWhen(() -> helper.assertTrue(thyra.blockPosition().getY() >= surface,
            "miner in the real wide pit must reach the surface, at " + helper.relativePos(thyra.blockPosition())
                + " note=" + thyra.routeFailureNote() + " goals=" + runningGoals(thyra)));
    }

    /** Which goals hold the Miner, and the escape goal's own state, for the failure line. */
    private static String runningGoals(SettlerEntity settler) {
        StringBuilder out = new StringBuilder();
        for (var wrapped : settler.goalSelector.getAvailableGoals()) {
            if (wrapped.getGoal() instanceof MinerEscapeGoal escape) {
                out.append("[escape running=").append(wrapped.isRunning()).append(' ')
                    .append(escape.debugState()).append(']');
            } else if (wrapped.isRunning()) {
                out.append('[').append(wrapped.getGoal().getClass().getSimpleName()).append(']');
            }
        }
        return out.toString();
    }

    /** A working Miner never leaves any cut column without a staircase out. */
    @GameTest(batch = "miner_pit", template = "empty16", timeoutTicks = 3000)
    public void aWorkingMinerOnlyCutsStaircases(GameTestHelper helper) {
        rock(helper);
        Settlement s = settlement(helper);
        Building mine = mine(helper, s);
        SettlerEntity grube = miner(helper, s, mine, new BlockPos(8, ROCK_TOP + 1, 9));
        helper.getLevel().setDayTime(2000);
        GameTestTicks.at(helper, 2900, () -> {
            MineShaft.Model model = MineShaft.survey(helper.getLevel(), mine.anchor, 12);
            boolean[] reach = MineShaft.reachable(model);
            int cut = 0;
            int deepest = 0;
            for (int i = 0; i < reach.length; i++) {
                if (!MineShaft.inDigArea(i) || model.depth[i] == 0) {
                    continue;
                }
                cut++;
                deepest = Math.max(deepest, model.depth[i]);
                helper.assertTrue(reach[i], "column " + MineShaft.dx(i) + "," + MineShaft.dz(i)
                    + " depth " + model.depth[i] + " has no way out");
            }
            helper.assertTrue(cut >= 5, "the miner must still dig; cut columns=" + cut);
            helper.assertTrue(grube.getHunger() > 0.0F, "sanity");
            helper.succeed();
        });
    }
}
