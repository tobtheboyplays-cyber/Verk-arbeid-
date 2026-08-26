package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * ACCEPT-JOBS audit (2026-08-26): the lumberer's own goal ({@code
 * LumbererWorkGoal}, exercised by {@code
 * HearthsteadGameTests#lumbererLimbsThenHaulsAfterFelling}) had real
 * coverage, but that test hands the trade out with {@code
 * settler.assignProfession(Profession.LUMBERER)} directly and builds no
 * LUMBER_CAMP at all — it proves the goal, never the front door. Every
 * other test that goes anywhere near a lumber camp ({@code
 * EmploymentGameTests}, {@code ChainsGameTests}) either only checks the
 * hire mechanic (no felling) or feeds a chest by hand and says outright
 * "a lumber camp makes nothing through Production — it is a source, not a
 * refiner", which is true of {@code Production} but says nothing about
 * whether a settler can be HIRED into the camp and sent out to fell trees.
 * This is the gap: {@link Employment#hire} at a real LUMBER_CAMP, a real
 * tree, felled through the settler's own goal, logs banked in the
 * settlement's own hearth (the lumberer's actual deposit target — see
 * {@code LumbererWorkGoal#tickDeposit} — never a building chest, since a
 * lumberjack's camp is a base, not a workplace, {@link
 * Employment#worksAtTheBuilding}).
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class LumbererGameTests {

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean rim = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 2 ? Blocks.STONE_BRICKS.defaultBlockState()
                                      : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    /**
     * A hired lumberer, given a real oak tree standing inside the
     * settlement's own radius and nothing else, actually fells it through
     * {@code LumbererWorkGoal} — never touched or armed by this test — and
     * banks real logs in the hearth. {@link Employment#hire} is what grants
     * the trade, not a direct {@code assignProfession} call, and a real
     * LUMBER_CAMP stands in the world the whole time (the building
     * dissolving mid-test would end the trade with it — D-011).
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "lumberer_hire")
    public void aHiredLumbererFellsARealTreeIntoTheHearth(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);

        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Tommerholm", hearthAbs);
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }

        // The building the trade is actually hired into -- off to one side
        // so it never overlaps the tree or the hearth.
        Building camp = GameTestFixtures.register(helper, s, BuildingType.LUMBER_CAMP, 11, 11);

        // A real oak tree, four logs tall with a leaf canopy, well inside
        // the settlement's 12-block radius of the hearth.
        BlockPos dirtRel = new BlockPos(8, 1, 8);
        helper.setBlock(dirtRel, Blocks.DIRT);
        BlockPos baseRel = dirtRel.above();
        for (int i = 0; i < 4; i++) {
            helper.setBlock(baseRel.above(i), Blocks.OAK_LOG);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.setBlock(baseRel.above(4).offset(dx, 0, dz), Blocks.OAK_LEAVES);
            }
        }

        SettlerEntity ulf = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        ulf.setSettlerName("Ulf");
        ulf.bindTo(s.id, s.center);
        s.putRecord(ulf.getUUID(), "Ulf", Profession.NONE);

        Employment.Hired hired = Employment.hire(helper.getLevel(), s, camp, ulf);
        helper.assertTrue(hired.ok(),
            "a lumber camp with a real TRADES entry must hire a lumberer, "
                + "refused with " + hired.refusal());
        helper.assertTrue(ulf.getProfession() == Profession.LUMBERER,
            "hired into the camp, they take up the trade, got " + ulf.getProfession());

        final boolean[] sawChopping = {false};
        final boolean[] sawHauling = {false};
        helper.succeedWhen(() -> {
            helper.assertTrue(camp.valid, "fixture: the lumber camp must still stand");
            if (ulf.getActivity() == SettlerActivity.WORK_CHOP) {
                sawChopping[0] = true;
            }
            if (ulf.getActivity() == SettlerActivity.HAULING_LOG) {
                sawHauling[0] = true;
            }
            int logs = 0;
            if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
                for (int i = 0; i < hearth.getInventory().getSlots(); i++) {
                    if (hearth.getInventory().getStackInSlot(i).is(Items.OAK_LOG)) {
                        logs += hearth.getInventory().getStackInSlot(i).getCount();
                    }
                }
            }
            helper.assertTrue(logs >= 1,
                "a lumberer hired through Employment.hire must fell a real tree and "
                    + "bank real logs in the hearth (act=" + ulf.getActivity()
                    + " chopped=" + sawChopping[0] + " hauled=" + sawHauling[0]
                    + " logs=" + logs + ")");
            helper.assertTrue(sawChopping[0],
                "the hired lumberer must actually be seen performing WORK_CHOP, "
                    + "not just have logs appear while idle");
            helper.assertTrue(sawHauling[0],
                "the hired lumberer must carry the felled logs home under "
                    + "HAULING_LOG, not teleport them");
        });
    }
}
