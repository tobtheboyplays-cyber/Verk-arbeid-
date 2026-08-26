package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.LumbererWorkGoal;
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

    /**
     * Places a minimal natural-tree footprint {@code LumbererWorkGoal
     * #validateTree} accepts: a short trunk plus an 8-block leaf ring one
     * block above the top log (clears {@code MIN_LEAVES}=4 with margin
     * without needing the wide 5x5 canopy the single-tree fixture above
     * uses — flood-fill only ever visits leaves within one block of an
     * actual log, so a full 5x5 layer buys nothing this ring does not).
     * Returns the tree's base (absolute), the position {@code
     * LumbererWorkGoal}'s own claim ledger keys on.
     */
    private static BlockPos plantTree(GameTestHelper helper, BlockPos dirtRel) {
        helper.setBlock(dirtRel, Blocks.DIRT);
        BlockPos baseRel = dirtRel.above();
        for (int i = 0; i < 3; i++) {
            helper.setBlock(baseRel.above(i), Blocks.OAK_LOG);
        }
        BlockPos leafLayer = baseRel.above(3);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                helper.setBlock(leafLayer.offset(dx, 0, dz), Blocks.OAK_LEAVES);
            }
        }
        return helper.absolutePos(baseRel);
    }

    /**
     * THE FINDING (owner's filmed session, chop-burst/): with two
     * lumberjacks employed at one Lumber Camp, both worked the SAME tree at
     * once, clipping into each other at the trunk. This proves the fix at
     * the level the finding was actually reported at -- two real hired
     * lumberers, two real unclaimed trees in range, through {@code
     * LumbererWorkGoal} untouched and unarmed by this test, exactly like
     * {@link #aHiredLumbererFellsARealTreeIntoTheHearth} above.
     *
     * <p>What "different trees" means from the outside: {@code
     * LumbererWorkGoal#treeClaimant} is the same test-only ledger window
     * {@code CourierWorkGoal#restockJobIsHeld} and {@code
     * RepairWorkGoal#scarIsClaimed} already use for exactly this reason --
     * "she chose a different tree" and "she has not decided yet" look
     * identical from outside the goal (both trees stand untouched either
     * way), so the claim table itself is asked directly, and the two
     * answers are cross-checked against the two hired settlers by UUID
     * rather than merely asserted non-null.
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "lumberer_hire")
    public void twoLumberersInOneCampChopDifferentTrees(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);

        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Tommerholm", hearthAbs);
        s.radius = 14;
        data.settlements.put(s.id, s);
        data.setDirty();
        if (helper.getLevel().getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }

        // The building both lumberers are hired into -- off to one side, far
        // from both trees and the hearth, exactly like the single-lumberer
        // fixture above.
        Building camp = GameTestFixtures.register(helper, s, BuildingType.LUMBER_CAMP, 2, 10);

        // Two real, well-separated oak trees, both inside the settlement's
        // radius and both inside the scanner's own first-pass search window
        // (WorkScanner#scanColumns walks columns nearest-first; a budget of
        // 512 covers roughly a 12-block radius in one call), so neither
        // settler's very first scan misses either tree -- the test proves
        // tree SELECTION, not scan pacing.
        BlockPos treeABase = plantTree(helper, new BlockPos(7, 1, 4));
        BlockPos treeBBase = plantTree(helper, new BlockPos(11, 1, 9));

        SettlerEntity ulf1 = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 4));
        ulf1.setSettlerName("Ulf");
        ulf1.bindTo(s.id, s.center);
        s.putRecord(ulf1.getUUID(), "Ulf", Profession.NONE);

        SettlerEntity ulf2 = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(4, 1, 6));
        ulf2.setSettlerName("Bjorn");
        ulf2.bindTo(s.id, s.center);
        s.putRecord(ulf2.getUUID(), "Bjorn", Profession.NONE);

        Employment.Hired hired1 = Employment.hire(helper.getLevel(), s, camp, ulf1);
        helper.assertTrue(hired1.ok(),
            "the lumber camp must hire the first lumberer, refused with " + hired1.refusal());
        Employment.Hired hired2 = Employment.hire(helper.getLevel(), s, camp, ulf2);
        helper.assertTrue(hired2.ok(),
            "a LUMBER_CAMP (capacity 2) must hire a SECOND lumberer -- the whole "
                + "scenario the filmed finding happened in -- refused with "
                + hired2.refusal());

        helper.succeedWhen(() -> {
            helper.assertTrue(camp.valid, "fixture: the lumber camp must still stand");
            UUID claimA = LumbererWorkGoal.treeClaimant(treeABase);
            UUID claimB = LumbererWorkGoal.treeClaimant(treeBBase);
            helper.assertTrue(claimA != null && claimB != null,
                "both trees must be claimed by now -- one of the two hired "
                    + "lumberers should have picked each (A=" + claimA
                    + " B=" + claimB + ")");
            helper.assertTrue(!claimA.equals(claimB),
                "two lumberers in the same camp must chop DIFFERENT trees, not "
                    + "shadow each other onto the same one (both claims=" + claimA
                    + ") -- this is the filmed same-tree clipping finding");
            boolean matchedPair =
                (claimA.equals(ulf1.getUUID()) && claimB.equals(ulf2.getUUID()))
                || (claimA.equals(ulf2.getUUID()) && claimB.equals(ulf1.getUUID()));
            helper.assertTrue(matchedPair,
                "the two tree claims must belong to the two hired lumberers "
                    + "(ulf1=" + ulf1.getUUID() + " ulf2=" + ulf2.getUUID()
                    + " claimA=" + claimA + " claimB=" + claimB + ")");
        });
    }
}
