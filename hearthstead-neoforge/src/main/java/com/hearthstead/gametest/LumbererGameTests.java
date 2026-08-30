package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.WorkContainerKind;
import com.hearthstead.entity.ai.LumbererWorkGoal;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.work.ContainerApproach;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.IItemHandler;

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
 * tree, a physical axe supplied through the camp chest, and logs banked in
 * that same workplace storage for Courier collection.
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
     * banks real logs in the camp. {@link Employment#hire} is what grants
     * the trade, not a direct {@code assignProfession} call, and a real
     * LUMBER_CAMP stands in the world the whole time (the building
     * dissolving mid-test would end the trade with it — D-011).
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "lumberer_hire")
    public void aHiredLumbererFellsARealTreeIntoCampStorage(GameTestHelper helper) {
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
        BlockPos campChestRel = new BlockPos(12, 1, 12);
        helper.setBlock(campChestRel, Blocks.CHEST);
        Container campChest = (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(campChestRel));
        campChest.setItem(0, new ItemStack(Items.IRON_AXE));
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
        final boolean[] sawPhysicalDrop = {false};
        final boolean[] sawFixedSack = {false};
        final boolean[] sawOffhandCarry = {false};
        final boolean[] sawReturnToSack = {false};
        final BlockPos[] fixedSackPosition = {null};
        helper.succeedWhen(() -> {
            helper.assertTrue(camp.valid, "fixture: the lumber camp must still stand");
            if (ulf.getActivity() == SettlerActivity.WORK_CHOP) {
                sawChopping[0] = true;
            }
            if (ulf.getActivity() == SettlerActivity.HAULING_LOG) {
                sawHauling[0] = true;
            }
            int worldLogs = 0;
            StringBuilder worldLogPositions = new StringBuilder();
            for (ItemEntity item : helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                 new AABB(helper.absolutePos(new BlockPos(8, 1, 8))).inflate(8.0))) {
                if (item.isAlive() && item.getItem().is(ItemTags.LOGS)) {
                    worldLogs += item.getItem().getCount();
                    if (!worldLogPositions.isEmpty()) {
                        worldLogPositions.append(',');
                    }
                    worldLogPositions.append(item.getItem().getCount()).append('@')
                        .append(item.blockPosition());
                }
            }
            if (worldLogs > 0) {
                sawPhysicalDrop[0] = true;
            }
            BlockPos placedSack = ulf.placedWorkContainerPos();
            if (placedSack != null) {
                helper.assertTrue(ulf.placedWorkContainerKind() == WorkContainerKind.SACK,
                    "the fixed field container must be the lumber sack");
                if (fixedSackPosition[0] == null) {
                    fixedSackPosition[0] = placedSack;
                }
                helper.assertTrue(fixedSackPosition[0].equals(placedSack),
                    "the sack may not follow or teleport with the collecting worker");
                sawFixedSack[0] = true;
                if (ulf.getOffhandItem().is(ItemTags.LOGS)) {
                    sawOffhandCarry[0] = true;
                    if (ulf.blockPosition().distSqr(placedSack) <= 2.25) {
                        sawReturnToSack[0] = true;
                    }
                }
            }
            int logs = 0;
            for (int i = 0; i < campChest.getContainerSize(); i++) {
                if (campChest.getItem(i).is(Items.OAK_LOG)) {
                    logs += campChest.getItem(i).getCount();
                }
            }
            helper.assertTrue(logs == 4,
                "a lumberer hired through Employment.hire must fell a real tree and "
                    + "bank all four real logs in camp storage (act=" + ulf.getActivity()
                    + " chopped=" + sawChopping[0] + " hauled=" + sawHauling[0]
                    + " logs=" + logs + " physical=" + worldLogs + '@' + worldLogPositions
                    + " offhand=" + ulf.getOffhandItem()
                    + " carry=" + ulf.getCarryLoad() + '/' + ulf.getCarryCapacity()
                    + " sack=" + ulf.placedWorkContainerPos()
                    + " route=" + ulf.routeFailureNote() + ")");
            helper.assertTrue(sawChopping[0],
                "the hired lumberer must actually be seen performing WORK_CHOP, "
                    + "not just have logs appear while idle");
            helper.assertTrue(sawHauling[0],
                "the hired lumberer must carry the felled logs home under "
                    + "HAULING_LOG, not teleport them");
            helper.assertTrue(sawPhysicalDrop[0],
                "felling must create physical ItemEntities before collection");
            helper.assertTrue(sawFixedSack[0] && fixedSackPosition[0] != null,
                "the lumberer must place one fixed world sack after felling");
            helper.assertTrue(sawOffhandCarry[0],
                "each picked log must exist visibly in offhand on its return leg");
            helper.assertTrue(sawReturnToSack[0],
                "the offhand log must physically return to the fixed sack before stow");
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

    private static int oakLogs(Container container) {
        int logs = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(Items.OAK_LOG)) {
                logs += stack.getCount();
            }
        }
        return logs;
    }

    private static int oakLogs(IItemHandler inventory) {
        int logs = 0;
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.is(Items.OAK_LOG)) {
                logs += stack.getCount();
            }
        }
        return logs;
    }

    private static int physicalOakLogs(ServerLevel level, AABB bounds) {
        int logs = 0;
        for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class, bounds)) {
            if (item.isAlive() && item.getItem().is(Items.OAK_LOG)) {
                logs += item.getItem().getCount();
            }
        }
        return logs;
    }

    /**
     * THE FINDING (owner's filmed session, chop-burst/): with two
     * lumberjacks employed at one Lumber Camp, both worked the SAME tree at
     * once, clipping into each other at the trunk. This proves the fix at
     * the level the finding was actually reported at -- two real hired
     * lumberers, two real unclaimed trees in range, through {@code
     * LumbererWorkGoal} untouched and unarmed by this test, exactly like
     * {@link #aHiredLumbererFellsARealTreeIntoCampStorage} above.
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
        BlockPos campChestRel = new BlockPos(3, 1, 11);
        helper.setBlock(campChestRel, Blocks.CHEST);
        Container campChest = (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(campChestRel));
        campChest.setItem(0, new ItemStack(Items.IRON_AXE));
        campChest.setItem(1, new ItemStack(Items.IRON_AXE));

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
            UUID claimA = LumbererWorkGoal.treeClaimant(
                helper.getLevel(), treeABase);
            UUID claimB = LumbererWorkGoal.treeClaimant(
                helper.getLevel(), treeBBase);
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

    /**
     * Live regression 2026-08-28: after banking the first tree the worker
     * turned in place and then idled forever. A one-tree success test cannot
     * catch that broken terminal-to-next-job handoff, so this fixture requires
     * one hired worker to complete two distinct trees in sequence.
     */
    @GameTest(template = "empty16", timeoutTicks = 3600,
        batch = "lumberer_consecutive_trees")
    public void oneLumbererCompletesTwoTreesWithoutSpinningIdle(
        GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Twin Timber", hearthAbs);
        settlement.radius = 14;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        if (helper.getLevel().getBlockEntity(hearthAbs)
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }

        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 2, 11);
        BlockPos chestRel = new BlockPos(3, 1, 12);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(chestRel));
        chest.setItem(0, new ItemStack(Items.IRON_AXE));

        plantTree(helper, new BlockPos(7, 1, 5));
        plantTree(helper, new BlockPos(11, 1, 9));

        SettlerEntity worker = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(4, 1, 4));
        worker.setSettlerName("Torvald");
        worker.bindTo(settlement.id, settlement.center);
        settlement.putRecord(worker.getUUID(), "Torvald", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                camp, worker).ok(),
            "fixture: the two-tree lumberer must be hired");

        final boolean[] firstTreeBanked = {false};
        final boolean[] secondTreeStarted = {false};
        helper.succeedWhen(() -> {
            int deposited = oakLogs(chest);
            if (deposited >= 3) {
                firstTreeBanked[0] = true;
            }
            if (firstTreeBanked[0]
                && worker.getActivity() == SettlerActivity.WORK_CHOP) {
                secondTreeStarted[0] = true;
            }
            helper.assertTrue(deposited == 6,
                "one worker must return from the first deposit, acquire the "
                    + "second tree and bank all six logs (deposited="
                    + deposited + " firstBanked=" + firstTreeBanked[0]
                    + " secondStarted=" + secondTreeStarted[0]
                    + " activity=" + worker.getActivity()
                    + " route=" + worker.routeFailureNote() + ")");
            helper.assertTrue(secondTreeStarted[0],
                "the second tree must visibly enter WORK_CHOP after the first "
                    + "tree was banked");
        });
    }

    /**
     * Regression for the filmed "employed but Idling" worker. The only tree
     * sits outside the first 768-column survey batch, so this proves the
     * resumable search advances on the new sub-second cadence instead of
     * parking for the old four-to-six-second cooldown between every batch.
     * The claim must exist within four real-time seconds; the full physical
     * harvest/collection/deposit loop is covered by the first test above.
     */
    @GameTest(template = "empty16", timeoutTicks = 120,
        batch = "lumberer_hire")
    public void sparseOuterTreeIsClaimedWithinFourSeconds(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(1, 1, 1);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Ytterved", hearthAbs);
        settlement.radius = 20;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        if (helper.getLevel().getBlockEntity(hearthAbs)
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }

        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 2, 10);
        BlockPos chestRel = new BlockPos(3, 1, 11);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(chestRel));
        chest.setItem(0, new ItemStack(Items.IRON_AXE));

        BlockPos distantTree = plantTree(helper, new BlockPos(14, 1, 14));
        SettlerEntity lumberer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(4, 1, 4));
        lumberer.setSettlerName("Runa");
        lumberer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(lumberer.getUUID(), "Runa", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                camp, lumberer).ok(),
            "fixture: the distant-tree lumberer must be hired");
        // This is the scanner-latency contract, not the separate physical
        // equipment-delivery contract. Start this one focused fixture in the
        // explicitly "equipped idle" state named above; otherwise the real
        // walk-to-chest plus 28-tick pickup animation consumes most or all of
        // the four-second budget before tree scanning is allowed to begin.
        lumberer.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_AXE));

        helper.runAfterDelay(80, () -> {
            UUID claimant = LumbererWorkGoal.treeClaimant(
                helper.getLevel(), distantTree);
            helper.assertTrue(lumberer.getUUID().equals(claimant),
                "an equipped idle lumberer must find and claim a sparse outer "
                    + "tree within four seconds (claim=" + claimant
                    + ", activity=" + lumberer.getActivity()
                    + ", route=" + lumberer.routeFailureNote() + ")");
            helper.succeed();
        });
    }

    /** A coordinate in another world is not the same claim, and dead rows TTL-clean. */
    @GameTest(template = "empty16", timeoutTicks = 750,
        batch = "lumberer_tree_claim_scope")
    public void treeClaimIsWorldScopedAndExpiresWithoutHeartbeat(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Claim Scope", hearthAbs);
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        if (level.getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }

        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 2, 10);

        BlockPos treeBase = plantTree(helper, new BlockPos(4, 1, 4));
        SettlerEntity lumberer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(6, 1, 4));
        lumberer.setSettlerName("Claim Holder");
        lumberer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(lumberer.getUUID(), "Claim Holder",
            Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, camp, lumberer).ok(),
            "fixture: the orphaned claim must still originate from a real "
                + "Lumber Camp employment and confirmed Work Zone");
        lumberer.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_AXE));
        lumberer.setNoAi(true);

        // This goal is intentionally not registered/ticked/stopped after its
        // synchronous claim. It models a dead owner that cannot heartbeat or
        // run normal stop cleanup without a production-only test shortcut.
        LumbererWorkGoal orphaned = new LumbererWorkGoal(lumberer);
        helper.assertTrue(orphaned.canUse(),
            "fixture must synchronously select and claim the nearby real tree");
        helper.assertTrue(lumberer.getUUID().equals(
                LumbererWorkGoal.treeClaimant(level, treeBase)),
            "the overworld ledger must hold the exact lumberer UUID");
        ServerLevel nether = level.getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null,
            "fixture requires the ordinary server Nether level");
        helper.assertTrue(LumbererWorkGoal.treeClaimant(nether, treeBase) == null,
            "identical BlockPos coordinates in another dimension/world must "
                + "never contend with the overworld claim");

        helper.runAfterDelay(LumbererWorkGoal.TREE_CLAIM_TTL_TICKS + 1, () -> {
            helper.assertTrue(LumbererWorkGoal.treeClaimant(level, treeBase) == null
                    && !LumbererWorkGoal.treeIsClaimed(level, treeBase),
                "a claim without heartbeat must be removed by lookup at TTL, "
                    + "not remain forever in the static ledger");
            helper.succeed();
        });
    }

    /**
     * Capacity is a physical haul boundary, not permission to forget the
     * fourth log. This fixture forces a 3+1 split and observes two distinct
     * placements of the same field sack, a real leftover world entity after
     * the first deposit, and the first pickup's authored contact tick.
     */
    @GameTest(template = "empty16", timeoutTicks = 1800,
        batch = "lumberer_capacity_round_trip")
    public void capacityThreeMakesSecondPhysicalTripAndConservesFourLogs(
        GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Firelass", hearthAbs);
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        if (helper.getLevel().getBlockEntity(hearthAbs)
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }

        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 11, 11);
        BlockPos chestRel = new BlockPos(12, 1, 12);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(chestRel));
        chest.setItem(0, new ItemStack(Items.IRON_AXE));

        BlockPos dirtRel = new BlockPos(8, 1, 8);
        helper.setBlock(dirtRel, Blocks.DIRT);
        BlockPos baseRel = dirtRel.above();
        for (int i = 0; i < 4; i++) {
            helper.setBlock(baseRel.above(i), Blocks.OAK_LOG);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.setBlock(baseRel.above(4).offset(dx, 0, dz),
                    Blocks.OAK_LEAVES);
            }
        }

        SettlerEntity lumberer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(4, 1, 4));
        lumberer.setSettlerName("Eira");
        lumberer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(lumberer.getUUID(), "Eira", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                camp, lumberer).ok(),
            "fixture: a real camp must hire the capacity-three lumberer");
        lumberer.setCarryCapacity(3);

        final boolean[] sackWasVisible = {false};
        final int[] sackPlacements = {0};
        final BlockPos[] fixedSite = {null};
        final boolean[] sawThreeDepositedWithOnePhysical = {false};
        final SettlerActivity[] previousActivity = {lumberer.getActivity()};
        final long[] pickupStartTick = {Long.MIN_VALUE};
        final long[] firstTransferDelta = {Long.MIN_VALUE};
        final String[] timingViolation = {null};

        helper.succeedWhen(() -> {
            BlockPos sack = lumberer.placedWorkContainerPos();
            boolean sackVisible = sack != null
                && lumberer.placedWorkContainerKind() == WorkContainerKind.SACK;
            if (sackVisible && !sackWasVisible[0]) {
                sackPlacements[0]++;
                if (fixedSite[0] == null) {
                    fixedSite[0] = sack;
                } else if (!fixedSite[0].equals(sack)) {
                    timingViolation[0] = "the second collection trip moved the fixed sack "
                        + "from " + fixedSite[0] + " to " + sack;
                }
            }
            sackWasVisible[0] = sackVisible;

            int physicalLogs = 0;
            for (ItemEntity item : helper.getLevel().getEntitiesOfClass(
                    ItemEntity.class,
                    new AABB(helper.absolutePos(baseRel)).inflate(10.0))) {
                if (item.isAlive() && item.getItem().is(Items.OAK_LOG)) {
                    physicalLogs += item.getItem().getCount();
                }
            }

            SettlerActivity activity = lumberer.getActivity();
            if (pickupStartTick[0] == Long.MIN_VALUE
                && previousActivity[0] == SettlerActivity.COLLECTING_ITEMS
                && activity == SettlerActivity.GATHERING_LOG
                && lumberer.getOffhandItem().isEmpty()
                && sackVisible && physicalLogs > 0) {
                pickupStartTick[0] = helper.getLevel().getGameTime();
            }
            if (pickupStartTick[0] != Long.MIN_VALUE
                && firstTransferDelta[0] == Long.MIN_VALUE) {
                long delta = helper.getLevel().getGameTime() - pickupStartTick[0];
                if (!lumberer.getOffhandItem().isEmpty()) {
                    firstTransferDelta[0] = delta;
                    if (delta < LumbererWorkGoal.GROUND_PICKUP_CONTACT_TICK) {
                        timingViolation[0] = "world-to-offhand transfer happened at +"
                            + delta + " ticks, before authored contact +"
                            + LumbererWorkGoal.GROUND_PICKUP_CONTACT_TICK;
                    }
                }
            }
            previousActivity[0] = activity;

            int deposited = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) {
                if (chest.getItem(i).is(Items.OAK_LOG)) {
                    deposited += chest.getItem(i).getCount();
                }
            }
            if (deposited == 3 && physicalLogs == 1
                && lumberer.getCarryLoad() == 0) {
                sawThreeDepositedWithOnePhysical[0] = true;
            }

            helper.assertTrue(deposited == 4,
                "capacity-three worker must eventually deposit all four logs "
                    + "(deposited=" + deposited + " physical=" + physicalLogs
                    + " carry=" + lumberer.getCarryLoad() + "/"
                    + lumberer.getCarryCapacity() + " placements="
                    + sackPlacements[0] + " activity=" + activity
                    + " route=" + lumberer.routeFailureNote() + ")");
            helper.assertTrue(timingViolation[0] == null, timingViolation[0]);
            helper.assertTrue(pickupStartTick[0] != Long.MIN_VALUE
                    && firstTransferDelta[0]
                        == LumbererWorkGoal.GROUND_PICKUP_CONTACT_TICK,
                "first world-to-offhand transfer must land exactly at contact +"
                    + LumbererWorkGoal.GROUND_PICKUP_CONTACT_TICK
                    + " (observed=" + firstTransferDelta[0] + ")");
            helper.assertTrue(sawThreeDepositedWithOnePhysical[0],
                "after the first capacity-three haul, exactly one real log "
                    + "must remain at the collection site");
            helper.assertTrue(sackPlacements[0] >= 2,
                "the final log must require a second physical return and sack "
                    + "placement, observed " + sackPlacements[0]);
            helper.assertTrue(physicalLogs == 0
                    && lumberer.getOffhandItem().isEmpty()
                    && lumberer.getCarryLoad() == 0,
                "final conservation boundary must be world=0, hand=0, bag=0, "
                    + "storage=4");
        });
    }

    /**
     * The hardest 3+1 boundary: replace the settler with a real full-NBT
     * entity load after the first three logs are banked and while the fourth
     * remains a physical owned ItemEntity at the now-invisible collection
     * site. The new goal instance must recover the exact UUID and site, make
     * the second sack trip, and finish with one conserved total.
     */
    @GameTest(template = "empty16", timeoutTicks = 2200,
        batch = "lumberer_capacity_reload")
    public void capacitySecondTripSurvivesFullEntityReload(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Reload Grove", hearthAbs);
        settlement.radius = 12;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        if (level.getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }

        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 11, 11);
        BlockPos chestRel = new BlockPos(12, 1, 12);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) level.getBlockEntity(
            helper.absolutePos(chestRel));
        chest.setItem(0, new ItemStack(Items.IRON_AXE));

        BlockPos dirtRel = new BlockPos(8, 1, 8);
        helper.setBlock(dirtRel, Blocks.DIRT);
        BlockPos baseRel = dirtRel.above();
        for (int index = 0; index < 4; index++) {
            helper.setBlock(baseRel.above(index), Blocks.OAK_LOG);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.setBlock(baseRel.above(4).offset(dx, 0, dz),
                    Blocks.OAK_LEAVES);
            }
        }
        AABB collectionBounds = new AABB(helper.absolutePos(baseRel)).inflate(10.0);

        SettlerEntity original = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(4, 1, 4));
        original.setSettlerName("Eira Reload");
        original.bindTo(settlement.id, settlement.center);
        settlement.putRecord(original.getUUID(), "Eira Reload", Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, camp, original).ok(),
            "fixture: a real camp must hire the reload lumberer");
        original.setCarryCapacity(3);

        final SettlerEntity[] active = {original};
        final UUID originalId = original.getUUID();
        final UUID[] remainingPhysicalId = {null};
        final boolean[] reloaded = {false};
        final boolean[] sawPostReloadSack = {false};
        helper.onEachTick(() -> {
            SettlerEntity worker = active[0];
            int deposited = oakLogs(chest);
            int physical = physicalOakLogs(level, collectionBounds);
            if (!reloaded[0] && deposited == 3 && physical == 1
                && worker.getCarryLoad() == 0
                && worker.getOffhandItem().isEmpty()
                && worker.placedWorkContainerPos() == null) {
                for (ItemEntity item : level.getEntitiesOfClass(
                        ItemEntity.class, collectionBounds)) {
                    if (item.isAlive() && item.getItem().is(Items.OAK_LOG)) {
                        remainingPhysicalId[0] = item.getUUID();
                        break;
                    }
                }
                helper.assertTrue(remainingPhysicalId[0] != null,
                    "reload boundary must identify the exact fourth ItemEntity");
                CompoundTag saved = worker.saveWithoutId(new CompoundTag());
                worker.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                SettlerEntity replacement = ModEntities.SETTLER.get().create(level);
                helper.assertTrue(replacement != null,
                    "fixture must construct a replacement settler entity");
                replacement.load(saved);
                helper.assertTrue(originalId.equals(replacement.getUUID()),
                    "full NBT load must retain the ownership UUID");
                helper.assertTrue(level.addFreshEntity(replacement),
                    "the reloaded settler must re-enter the real ServerLevel");
                active[0] = replacement;
                reloaded[0] = true;
            }
            if (reloaded[0] && active[0].placedWorkContainerPos() != null) {
                sawPostReloadSack[0] = true;
            }
        });

        helper.succeedWhen(() -> {
            SettlerEntity worker = active[0];
            int deposited = oakLogs(chest);
            int physical = physicalOakLogs(level, collectionBounds);
            helper.assertTrue(reloaded[0],
                "fixture must cross the full entity reload boundary");
            helper.assertTrue(sawPostReloadSack[0],
                "the new goal instance must physically replace the sack at "
                    + "the remembered collection site");
            helper.assertTrue(deposited == 4 && physical == 0
                    && worker.getOffhandItem().isEmpty()
                    && worker.getCarryLoad() == 0
                    && worker.placedWorkContainerPos() == null,
                "reload conservation must be storage=4, world=0, hand=0, bag=0 "
                    + "(storage=" + deposited + " world=" + physical
                    + " hand=" + worker.getOffhandItem() + " bag="
                    + worker.getCarryLoad() + " route="
                    + worker.routeFailureNote() + ")");
            helper.assertTrue(remainingPhysicalId[0] != null
                    && level.getEntity(remainingPhysicalId[0]) == null,
                "the exact pre-reload fourth UUID must be consumed once, never "
                    + "copied or replaced by synthetic inventory");
        });
    }

    /**
     * A sealed workplace chest may not let TO_CAMP own MOVE/LOOK forever.
     * The worker must stop after a bounded no-progress budget with the real
     * log still in their bag, then resume and deposit exactly once after the
     * route becomes reachable.
     */
    @GameTest(template = "empty16", timeoutTicks = 900,
        batch = "lumberer_camp_route_recovery")
    public void sealedCampRouteYieldsThenRecoversWithoutCargoLoss(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Sealed Camp", hearthAbs);
        settlement.radius = 14;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        if (level.getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }

        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 11, 11);
        BlockPos chestRel = new BlockPos(13, 1, 13);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) level.getBlockEntity(
            helper.absolutePos(chestRel));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int y = 1; y <= 3; y++) {
                    if (dx == 0 && dz == 0 && y == 1) {
                        continue; // keep the real storage block
                    }
                    helper.setBlock(chestRel.offset(dx, y - 1, dz),
                        Blocks.STONE_BRICKS);
                }
            }
        }

        SettlerEntity lumberer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(4, 1, 4));
        lumberer.setSettlerName("Route Keeper");
        lumberer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(lumberer.getUUID(), "Route Keeper", Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, camp, lumberer).ok(),
            "fixture: the sealed-camp lumberer must be genuinely employed");
        helper.assertTrue(lumberer.bag.addItem(
                new ItemStack(Items.OAK_LOG)).isEmpty(),
            "fixture: exactly one real bag log must seed recovery routing");

        final boolean[] sawBoundedYield = {false};
        final long[] unsealedAt = {Long.MIN_VALUE};
        helper.onEachTick(() -> {
            if (!sawBoundedYield[0]
                && lumberer.routeFailureNote().startsWith(
                    "lumber_camp_unreachable@")
                && lumberer.getActivity() == SettlerActivity.IDLE
                && lumberer.getNavigation().isDone()) {
                helper.assertTrue(lumberer.getCarryLoad() == 1
                        && oakLogs(chest) == 0,
                    "bounded route failure must preserve the cargo in its real bag");
                sawBoundedYield[0] = true;
                unsealedAt[0] = level.getGameTime();
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        for (int y = 1; y <= 3; y++) {
                            if (dx == 0 && dz == 0 && y == 1) {
                                continue;
                            }
                            helper.setBlock(chestRel.offset(dx, y - 1, dz),
                                Blocks.AIR);
                        }
                    }
                }
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(sawBoundedYield[0],
                "sealed TO_CAMP must exhaust a bounded progress budget and "
                    + "release MOVE/LOOK");
            helper.assertTrue(unsealedAt[0] != Long.MIN_VALUE
                    && level.getGameTime() > unsealedAt[0],
                "delivery must occur on a later retry, not on the sealed route");
            helper.assertTrue(oakLogs(chest) == 1
                    && lumberer.getCarryLoad() == 0
                    && lumberer.getOffhandItem().isEmpty(),
                "reopened route must conserve exactly one cargo item "
                    + "(storage=" + oakLogs(chest) + " bag="
                    + lumberer.getCarryLoad() + ")");
        });
    }

    /**
     * A normal closed door is a route, not a sealed-camp failure. Recovery
     * cargo already in the real bag must cross the only doorway and reach a
     * corner chest once, with physical contact on the mutation tick.
     */
    @GameTest(template = "empty16", timeoutTicks = 600,
        batch = "lumberer_camp_route_recovery")
    public void carriedLogCrossesClosedDoorToCornerCampChest(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Door Camp", hearthAbs);
        settlement.radius = 14;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        if (level.getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }

        for (int x = 11; x <= 14; x++) {
            for (int z = 11; z <= 14; z++) {
                if (x != 11 && x != 14 && z != 11 && z != 14) {
                    continue;
                }
                for (int y = 1; y <= 2; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.STONE_BRICKS);
                }
            }
        }
        var lower = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.WEST)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(DoorBlock.OPEN, false);
        helper.setBlock(new BlockPos(11, 1, 13), lower);
        helper.setBlock(new BlockPos(11, 2, 13), lower
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));

        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 11, 11);
        BlockPos chestRel = new BlockPos(13, 1, 13);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) level.getBlockEntity(
            helper.absolutePos(chestRel));

        SettlerEntity lumberer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(8, 1, 13));
        lumberer.setSettlerName("Door Keeper");
        lumberer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(lumberer.getUUID(), "Door Keeper", Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, camp, lumberer).ok(),
            "fixture: the closed-door lumberer must be genuinely employed");
        helper.assertTrue(lumberer.bag.addItem(
                new ItemStack(Items.OAK_LOG)).isEmpty(),
            "fixture: one exact log must seed recovery routing");

        BlockPos door = helper.absolutePos(new BlockPos(11, 1, 13));
        BlockPos chestPos = helper.absolutePos(chestRel);
        boolean[] sawOpen = {false};
        boolean[] firstInsertHadContact = {false};
        helper.onEachTick(() -> {
            BlockState doorState = level.getBlockState(door);
            if (doorState.is(Blocks.OAK_DOOR)
                && doorState.getValue(DoorBlock.OPEN)) {
                sawOpen[0] = true;
            }
            if (!firstInsertHadContact[0] && oakLogs(chest) > 0) {
                firstInsertHadContact[0] = ContainerApproach.inspect(level,
                    lumberer, chestPos).canInteract();
            }
        });

        helper.succeedWhen(() -> {
            helper.assertTrue(sawOpen[0],
                "the lumberer must visibly open the only camp door");
            helper.assertTrue(firstInsertHadContact[0],
                "the first camp insert must occur at a visible chest face");
            helper.assertTrue(oakLogs(chest) == 1
                    && lumberer.getCarryLoad() == 0,
                "the door route must conserve exactly one log (storage="
                    + oakLogs(chest) + ", bag=" + lumberer.getCarryLoad()
                    + ", route=" + lumberer.routeFailureNote() + ")");
        });
    }

    /**
     * A real dropped log changes world blocks repeatedly while the worker is
     * routing to it. Every new exact feet node is a new progress series; the
     * old node's best-distance record must not burn the moving target's stuck
     * budget. Once motion stops, the same UUID must transfer and bank once.
     */
    @GameTest(template = "empty16", timeoutTicks = 1000,
        batch = "lumberer_moving_drop_route")
    public void movingPhysicalDropRebasesRouteAndTransfersSameUuid(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Moving Timber", hearthAbs);
        settlement.radius = 13;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        if (level.getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }

        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 2, 11);
        BlockPos chestRel = new BlockPos(3, 1, 12);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) level.getBlockEntity(
            helper.absolutePos(chestRel));
        chest.setItem(0, new ItemStack(Items.IRON_AXE));

        BlockPos dirtRel = new BlockPos(10, 1, 8);
        helper.setBlock(dirtRel, Blocks.DIRT);
        BlockPos baseRel = dirtRel.above();
        helper.setBlock(baseRel, Blocks.OAK_LOG);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    helper.setBlock(baseRel.above().offset(dx, 0, dz),
                        Blocks.OAK_LEAVES);
                }
            }
        }

        SettlerEntity lumberer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(4, 1, 4));
        lumberer.setSettlerName("Moving Target");
        lumberer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(lumberer.getUUID(), "Moving Target",
            Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, camp, lumberer).ok(),
            "fixture: moving-drop routing must run under real camp authority");

        BlockPos[] route = {
            new BlockPos(8, 1, 8), new BlockPos(7, 1, 6),
            new BlockPos(5, 1, 6), new BlockPos(5, 1, 9),
            new BlockPos(8, 1, 10), new BlockPos(10, 1, 9),
            new BlockPos(9, 1, 6), new BlockPos(6, 1, 7),
            new BlockPos(6, 1, 10), new BlockPos(9, 1, 9),
            new BlockPos(8, 1, 8), new BlockPos(5, 1, 9),
            new BlockPos(10, 1, 9), new BlockPos(6, 1, 7),
            new BlockPos(7, 1, 6), new BlockPos(8, 1, 10),
            new BlockPos(9, 1, 6), new BlockPos(6, 1, 10),
            new BlockPos(5, 1, 6), new BlockPos(9, 1, 9),
            new BlockPos(8, 1, 8), new BlockPos(10, 1, 9),
            new BlockPos(5, 1, 9), new BlockPos(9, 1, 6)
        };
        final ItemEntity[] moving = {null};
        final UUID[] movingId = {null};
        final boolean[] movingWasAlive = {false};
        final boolean[] exactTransfer = {false};
        final int[] moves = {0};
        final long[] nextMoveAt = {Long.MIN_VALUE};
        AABB arena = new AABB(hearthAbs).inflate(20.0);
        helper.onEachTick(() -> {
            if (moving[0] == null) {
                for (ItemEntity item : level.getEntitiesOfClass(
                        ItemEntity.class, arena)) {
                    if (item.isAlive() && item.getItem().is(Items.OAK_LOG)) {
                        moving[0] = item;
                        movingId[0] = item.getUUID();
                        item.setNoGravity(true);
                        item.setDeltaMovement(Vec3.ZERO);
                        movingWasAlive[0] = true;
                        nextMoveAt[0] = level.getGameTime() + 1;
                        break;
                    }
                }
            }
            if (moving[0] != null && moving[0].isAlive()
                && moves[0] < route.length
                && level.getGameTime() >= nextMoveAt[0]) {
                BlockPos destination = helper.absolutePos(route[moves[0]++]);
                moving[0].setPos(destination.getX() + 0.5,
                    destination.getY() + 0.25, destination.getZ() + 0.5);
                moving[0].setDeltaMovement(Vec3.ZERO);
                // Move again before the 12-tick contact can complete. Twenty-
                // four changes keep the same UUID mobile beyond the old
                // eight-check stuck budget; only the final position is held.
                nextMoveAt[0] = level.getGameTime() + 1;
            }
            if (moving[0] != null) {
                boolean aliveNow = moving[0].isAlive();
                if (movingWasAlive[0] && !aliveNow
                    && movingId[0] != null
                    && level.getEntity(movingId[0]) == null
                    && lumberer.getOffhandItem().is(Items.OAK_LOG)) {
                    exactTransfer[0] = true;
                }
                movingWasAlive[0] = aliveNow;
            }
        });

        helper.succeedWhen(() -> {
            int stored = oakLogs(chest);
            int physical = physicalOakLogs(level, arena);
            helper.assertTrue(moves[0] == route.length,
                "fixture must move the same physical UUID through all "
                    + route.length + " route targets");
            helper.assertTrue(movingId[0] != null && exactTransfer[0]
                    && level.getEntity(movingId[0]) == null,
                "the exact moving ItemEntity UUID must perform the contact transfer");
            helper.assertTrue(stored == 1 && physical == 0
                    && lumberer.getOffhandItem().isEmpty()
                    && lumberer.getCarryLoad() == 0,
                "moving-route conservation must be storage=1, world=0, hand=0, "
                    + "bag=0 (storage=" + stored + " world=" + physical
                    + " route=" + lumberer.routeFailureNote() + ")");
            helper.assertTrue(!"felled_item_unreachable".equals(
                    lumberer.routeFailureNote()),
                "a moving but eventually stationary reachable UUID may not "
                    + "consume a historical approach's stuck budget");
        });
    }

    /**
     * Adversarial reproduction for the intermittent 3/4-log production loss.
     * One real, owned output is allowed to fall, then pinned on a solid stump.
     * The geometrically nearest standing cell on the tree-facing side is a
     * sealed pocket, while the north/west/south sides and the top remain
     * physically reachable. A distance-only approach selector abandons the
     * item with {@code felled_item_unreachable}; an exact multi-target path
     * must walk around the stump, perform the normal contact animation, and
     * conserve all four logs into the Lumber Camp storage.
     */
    @GameTest(template = "empty16", timeoutTicks = 1800,
        batch = "lumberer_stump_route")
    public void reachableSideOfStumpCollectsOwnedDropWhenNearestSideIsSealed(
        GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Stubbesti", hearthAbs);
        settlement.radius = 14;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        if (helper.getLevel().getBlockEntity(hearthAbs)
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }

        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 2, 10);
        BlockPos campChestRel = new BlockPos(3, 1, 11);
        helper.setBlock(campChestRel, Blocks.CHEST);
        Container campChest = (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(campChestRel));
        campChest.setItem(0, new ItemStack(Items.IRON_AXE));

        BlockPos dirtRel = new BlockPos(12, 1, 8);
        helper.setBlock(dirtRel, Blocks.DIRT);
        BlockPos baseRel = dirtRel.above();
        for (int i = 0; i < 4; i++) {
            helper.setBlock(baseRel.above(i), Blocks.OAK_LOG);
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                helper.setBlock(baseRel.above(4).offset(dx, 0, dz),
                    Blocks.OAK_LEAVES);
            }
        }

        // The pinned item will sit one block above this permanent stump.
        // Its east-side standing cell is open but sealed on all four sides;
        // therefore it is geometrically nearest from the felled tree but has
        // no exact path. Other sides remain ordinary walkable ground.
        BlockPos stumpRel = new BlockPos(6, 1, 8);
        helper.setBlock(stumpRel, Blocks.OAK_LOG);
        for (BlockPos wall : new BlockPos[]{
            new BlockPos(8, 1, 8),
            new BlockPos(7, 1, 7),
            new BlockPos(7, 1, 9)
        }) {
            helper.setBlock(wall, Blocks.STONE_BRICKS);
            helper.setBlock(wall.above(), Blocks.STONE_BRICKS);
        }

        SettlerEntity lumberer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(10, 1, 8));
        lumberer.setSettlerName("Runa");
        lumberer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(lumberer.getUUID(), "Runa", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                camp, lumberer).ok(),
            "fixture: stump routing must run under real camp authority");
        lumberer.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_AXE));

        BlockPos stumpAbs = helper.absolutePos(stumpRel);
        final ItemEntity[] pinned = {null};
        final UUID[] pinnedId = {null};
        final boolean[] pinnedWasAlive = {false};
        final boolean[] sawPinnedPhysicalOutput = {false};
        final boolean[] sawReachableApproach = {false};
        final boolean[] sawPinnedExactTransfer = {false};
        helper.onEachTick(() -> {
            if (pinned[0] == null) {
                for (ItemEntity item : helper.getLevel().getEntitiesOfClass(
                        ItemEntity.class,
                        new AABB(helper.absolutePos(baseRel)).inflate(8.0))) {
                    if (item.isAlive() && item.getItem().is(Items.OAK_LOG)) {
                        pinned[0] = item;
                        item.setPos(stumpAbs.getX() + 0.5,
                            stumpAbs.getY() + 1.05,
                            stumpAbs.getZ() + 0.5);
                        item.setDeltaMovement(Vec3.ZERO);
                        item.setNoGravity(true);
                        pinnedId[0] = item.getUUID();
                        pinnedWasAlive[0] = true;
                        sawPinnedPhysicalOutput[0] = true;
                        break;
                    }
                }
            }
            if (pinned[0] != null && pinned[0].isAlive()
                && (lumberer.getActivity() == SettlerActivity.COLLECTING_ITEMS
                    || lumberer.getActivity() == SettlerActivity.GATHERING_LOG)) {
                BlockPos feet = lumberer.blockPosition();
                BlockPos sealedEast = helper.absolutePos(stumpRel.east());
                if (!feet.equals(sealedEast)
                    && lumberer.distanceToSqr(pinned[0]) <= 6.25) {
                    sawReachableApproach[0] = true;
                }
            }
            if (pinned[0] != null) {
                boolean aliveNow = pinned[0].isAlive();
                if (pinnedWasAlive[0] && !aliveNow
                    && pinnedId[0] != null
                    && helper.getLevel().getEntity(pinnedId[0]) == null
                    && lumberer.getOffhandItem().is(Items.OAK_LOG)) {
                    BlockPos feet = lumberer.blockPosition();
                    BlockPos sealedEast = helper.absolutePos(stumpRel.east());
                    sawPinnedExactTransfer[0] = !feet.equals(sealedEast)
                        && lumberer.distanceToSqr(pinned[0]) <= 6.25;
                }
                pinnedWasAlive[0] = aliveNow;
            }
        });

        helper.succeedWhen(() -> {
            int stored = oakLogs(campChest);
            int physicalLogs = 0;
            for (ItemEntity item : helper.getLevel().getEntitiesOfClass(
                    ItemEntity.class, new AABB(hearthAbs).inflate(20.0))) {
                if (item.isAlive() && item.getItem().is(Items.OAK_LOG)) {
                    physicalLogs += item.getItem().getCount();
                }
            }
            helper.assertTrue(stored == 4,
                "reachable alternate side of the stump must bank all four "
                    + "physical logs (stored=" + stored + " activity="
                    + lumberer.getActivity() + " route="
                    + lumberer.routeFailureNote() + ")");
            helper.assertTrue(sawPinnedPhysicalOutput[0],
                "fixture must relocate one real goal-produced ItemEntity, not "
                    + "inject inventory output");
            helper.assertTrue(sawReachableApproach[0],
                "lumberer must physically reach a non-sealed side/top of the stump");
            helper.assertTrue(pinnedId[0] != null && sawPinnedExactTransfer[0],
                "the exact pinned ItemEntity UUID must leave the world only on "
                    + "a reachable physical world-to-offhand contact");
            helper.assertTrue(physicalLogs == 0
                    && lumberer.getOffhandItem().isEmpty()
                    && lumberer.getCarryLoad() == 0
                    && lumberer.placedWorkContainerPos() == null,
                "final stump conservation must be world=0, hand=0, bag=0, "
                    + "storage=4 (world=" + physicalLogs + " hand="
                    + lumberer.getOffhandItem() + " bag="
                    + lumberer.getCarryLoad() + ")");
            helper.assertTrue(!"felled_item_unreachable".equals(
                    lumberer.routeFailureNote()),
                "a reachable drop may never be labelled unreachable");
        });
    }
}
