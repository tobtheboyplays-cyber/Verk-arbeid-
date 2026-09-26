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
import com.hearthstead.settlement.work.WorkerStackProvenance;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData;
import com.hearthstead.settlement.workzone.WorkZone;
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
import net.minecraft.world.level.levelgen.structure.BoundingBox;
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
        runActualTreeReturn(helper, false);
    }

    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "lumberer_leaf_access")
    public void hiredLumbererClearsSouthLeafDoorwayAndReturnsEveryLog(GameTestHelper helper) {
        runActualTreeReturn(helper, true, Direction.SOUTH);
    }

    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "lumberer_leaf_access")
    public void hiredLumbererClearsWestLeafDoorwayAndReturnsEveryLog(GameTestHelper helper) {
        runActualTreeReturn(helper, true, Direction.WEST);
    }

    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "lumberer_leaf_access")
    public void hiredLumbererClearsNorthLeafDoorwayAndReturnsEveryLog(GameTestHelper helper) {
        runActualTreeReturn(helper, true, Direction.NORTH);
    }

    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "lumberer_leaf_access")
    public void hiredLumbererClearsEastLeafDoorwayAndReturnsEveryLog(GameTestHelper helper) {
        runActualTreeReturn(helper, true, Direction.EAST);
    }

    /**
     * A real claimed Lumberer starts 2.5 blocks before a natural leaf in the
     * only north approach. The first production goal tick is deliberately
     * held at that physical location, so this fails with the former 0.85
     * collision corridor; normal AI resumes only after the real leaf break.
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "lumberer_leaf_access")
    public void hiredLumbererClearsNaturalLeafInFinalThreeBlockApproachAndStartsWork(
        GameTestHelper helper) {
        runActualTreeReturn(helper, true, Direction.NORTH, true);
    }

    private void runActualTreeReturn(GameTestHelper helper, boolean leafDoorway) {
        runActualTreeReturn(helper, leafDoorway, null, false);
    }

    /** A leaf doorway fixes the goal's UUID-derived approach before the actor joins the level. */
    private void runActualTreeReturn(GameTestHelper helper, boolean leafDoorway,
                                     Direction forcedApproach) {
        runActualTreeReturn(helper, leafDoorway, forcedApproach, false);
    }

    private void runActualTreeReturn(GameTestHelper helper, boolean leafDoorway,
                                     Direction forcedApproach, boolean finalApproachLeaf) {
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

        BlockPos lumbererStartRel = finalApproachLeaf
            ? new BlockPos(8, 1, 4) : new BlockPos(4, 1, 4);
        SettlerEntity ulf;
        if (forcedApproach == null) {
            ulf = helper.spawn(ModEntities.SETTLER.get(), lumbererStartRel);
        } else {
            ulf = ModEntities.SETTLER.get().create(helper.getLevel());
            helper.assertTrue(ulf != null, "fixture: deterministic lumberer must construct");
            UUID uniqueId = UUID.randomUUID();
            UUID actorId = new UUID(uniqueId.getMostSignificantBits(),
                (uniqueId.getLeastSignificantBits() & ~3L) | forcedApproach.get2DDataValue());
            ulf.setUUID(actorId);
            BlockPos ulfAbs = helper.absolutePos(lumbererStartRel);
            ulf.setPos(ulfAbs.getX() + 0.5D, ulfAbs.getY(),
                ulfAbs.getZ() + (finalApproachLeaf ? 0.0D : 0.5D));
            helper.assertTrue(helper.getLevel().addFreshEntity(ulf),
                "fixture: deterministic lumberer must enter ServerLevel once");
            helper.assertTrue(actorId.equals(ulf.getUUID())
                    && helper.getLevel().getEntity(actorId) == ulf,
                "fixture: world UUID lookup must name the deterministic lumberer");
        }
        ulf.setSettlerName("Ulf");
        ulf.bindTo(s.id, s.center);
        s.putRecord(ulf.getUUID(), "Ulf", Profession.NONE);

        Employment.Hired hired = Employment.hire(helper.getLevel(), s, camp, ulf);
        helper.assertTrue(hired.ok(),
            "a lumber camp with a real TRADES entry must hire a lumberer, "
                + "refused with " + hired.refusal());
        helper.assertTrue(ulf.getProfession() == Profession.LUMBERER,
            "hired into the camp, they take up the trade, got " + ulf.getProfession());
        final int[] lastBanked = {0};
        final BlockPos[] campBagAnchor = {null};
        helper.onEachTick(() -> {
            int banked = oakLogs(campChest);
            if (banked > lastBanked[0]) {
                helper.assertTrue(banked - lastBanked[0] == 1,
                    "Lumberer must insert one visible log per contact, never its whole bag");
                var transfer = ulf.bagTransferPresentation();
                helper.assertTrue(transfer.active() && transfer.committed()
                    && transfer.clock() == com.hearthstead.entity.animation.BagToChestAnimationContract.DEPOSIT_COMMIT_TICK
                    && transfer.item().getCount() == 1,
                    "physical insertion must have the exact visible single-log contact receipt");
                if (campBagAnchor[0] == null) campBagAnchor[0] = transfer.bagAnchor();
                helper.assertTrue(campBagAnchor[0].equals(transfer.bagAnchor()),
                    "the same bag stays grounded through the whole unload batch");
            }
            lastBanked[0] = banked;
        });
        if (leafDoorway) {
            // This isolated route fixture starts with the real tool equipped;
            // the source chest is beyond the obstacle and cannot supply it first.
            ulf.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
            for (int x = 1; x <= 14; x++) for (int y = 1; y <= 4; y++) {
                helper.setBlock(new BlockPos(x, y, 6), Blocks.STONE_BRICKS);
            }
            if (finalApproachLeaf) {
                // Actor (8.5, 1.0, 4.0) to the blocking leaf centre
                // (8.5, 1.5, 6.5) is exactly 2.5 blocks horizontally.
                // The fixed north stand remains farther ahead.
                for (int y = 1; y <= 2; y++) {
                    helper.setBlock(new BlockPos(8, y, 6), Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 2));
                }
                // Natural but outside the three-block direct lane: leave it.
                for (int y = 1; y <= 2; y++) {
                    helper.setBlock(new BlockPos(4, y, 6), Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 2));
                }
            } else {
                for (int x = 7; x <= 9; x++) for (int y = 1; y <= 2; y++) {
                    helper.setBlock(new BlockPos(x, y, 6), Blocks.OAK_LEAVES.defaultBlockState()
                        .setValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE, 2));
                }
            }
            helper.setBlock(new BlockPos(5, 1, 4), Blocks.OAK_LEAVES.defaultBlockState()
                .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        }
        final boolean[] finalApproachClaimed = {!finalApproachLeaf};
        final boolean[] finalApproachLeafCleared = {!finalApproachLeaf};
        if (finalApproachLeaf) {
            // Exercise the exact production action/claim and physical break at
            // the stalled body position. No fixture block is removed directly.
            ulf.setNoAi(true);
            LumbererWorkGoal stalledGoal = new LumbererWorkGoal(ulf);
            helper.onEachTick(() -> {
                if (finalApproachClaimed[0] || !stalledGoal.canUse()) {
                    return;
                }
                finalApproachClaimed[0] = true;
                stalledGoal.start();
                ulf.getNavigation().stop();
                stalledGoal.tick();
                finalApproachLeafCleared[0] = helper.getLevel().getBlockState(
                    helper.absolutePos(new BlockPos(8, 1, 6))).isAir();
                // Let the ordinary registered Lumberer goal resume the
                // authenticated action and prove actual WORK_CHOP/haul.
                ulf.setNoAi(false);
            });
        }
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
            if (placedSack != null && !ulf.bagTransferPresentation().active()) {
                helper.assertTrue(ulf.placedWorkContainerKind() == WorkContainerKind.SACK,
                    "the fixed field container must be the lumber sack");
                if (fixedSackPosition[0] == null) {
                    fixedSackPosition[0] = placedSack;
                    helper.assertTrue(ulf.blockPosition().distSqr(placedSack) <= 2.25,
                        "first grounded sack requires actual worker arrival, never remote placement");
                    if (!leafDoorway) {
                        BlockPos tree = helper.absolutePos(baseRel);
                        int dx = placedSack.getX() - tree.getX(), dz = placedSack.getZ() - tree.getZ();
                        helper.assertTrue(dx * dx + dz * dz >= 9 && dx * dx + dz * dz <= 16,
                            "open fixture must place its first field sack three or four blocks from the tree");
                    }
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
            if (leafDoorway) {
                boolean openDoorway = false;
                int doorwayMinX = finalApproachLeaf ? 8 : 7;
                int doorwayMaxX = finalApproachLeaf ? 8 : 9;
                for (int x = doorwayMinX; x <= doorwayMaxX; x++) {
                    openDoorway |= helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, 1, 6))).isAir()
                        && helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(x, 2, 6))).isAir();
                }
                helper.assertTrue(openDoorway, finalApproachLeaf
                    ? "the 2.5-block natural final-approach leaf must open before real chopping and return"
                    : "an actual two-block leaf access must open before the complete real tree return");
                if (finalApproachLeaf) {
                    helper.assertTrue(finalApproachClaimed[0] && finalApproachLeafCleared[0],
                        "the claimed production goal must clear at the original stalled 2.5-block reach");
                    helper.assertTrue(helper.getLevel().getBlockState(
                            helper.absolutePos(new BlockPos(4, 1, 6))).is(Blocks.OAK_LEAVES),
                        "a natural leaf outside the three-block direct lane must remain");
                }
                helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(5, 1, 4)))
                    .getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT),
                    "player-placed persistent leaf must remain");
                helper.assertTrue(helper.getLevel().getBlockState(helper.absolutePos(new BlockPos(6, 1, 6))).is(Blocks.STONE_BRICKS),
                    "solid player-built partition must remain");
            }
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
     * Physical regression for a real playtest loss: a valid log produced by a
     * tree at the Work Zone edge can settle one block across the exact cuboid.
     * The immutable source/action stamp must keep that specific UUID
     * collectable, while an ordinary nearby log remains foreign.
     */
    @GameTest(template = "empty16", timeoutTicks = 2200,
        batch = "lumberer_zone_edge_output")
    public void authenticatedEdgeLogSettlesOutsideZoneAndStillBanksExactlyOnce(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Edge Timber", hearthAbs);
        settlement.radius = 14;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        if (level.getBlockEntity(hearthAbs) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }

        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 2, 10);
        WorkZone edgeZone = WorkZone.between(settlement.id, camp.id,
            WorkZone.Type.LUMBER, level.dimension().location(),
            helper.absolutePos(new BlockPos(9, 0, 5)),
            helper.absolutePos(new BlockPos(13, 7, 11)), 2);
        helper.assertTrue(camp.commitWorkZone(1, edgeZone),
            "fixture: replace the broad default zone with the exact edge zone");

        BlockPos chestRel = new BlockPos(3, 1, 11);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) level.getBlockEntity(
            helper.absolutePos(chestRel));

        BlockPos dirtRel = new BlockPos(12, 1, 8);
        helper.setBlock(dirtRel, Blocks.DIRT);
        BlockPos baseRel = dirtRel.above();
        for (int i = 0; i < 4; i++) {
            helper.setBlock(baseRel.above(i), Blocks.OAK_LOG);
        }
        BlockPos leafLayer = baseRel.above(4);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    helper.setBlock(leafLayer.offset(dx, 0, dz),
                        Blocks.OAK_LEAVES);
                }
            }
        }

        ItemEntity foreign = new ItemEntity(level,
            helper.absolutePos(new BlockPos(6, 1, 4)).getX() + 0.5,
            helper.absolutePos(new BlockPos(6, 1, 4)).getY() + 0.05,
            helper.absolutePos(new BlockPos(6, 1, 4)).getZ() + 0.5,
            new ItemStack(Items.OAK_LOG));
        foreign.setNoGravity(true);
        foreign.setDeltaMovement(Vec3.ZERO);
        foreign.setPickUpDelay(32_000);
        helper.assertTrue(level.addFreshEntity(foreign),
            "fixture: nearby foreign log must exist physically");

        SettlerEntity lumberer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(7, 1, 8));
        lumberer.setSettlerName("Runa");
        lumberer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(lumberer.getUUID(), "Runa", Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, camp, lumberer).ok(),
            "fixture: edge-zone lumberer must be hired normally");
        lumberer.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_AXE));

        BlockPos displacedAbs = helper.absolutePos(new BlockPos(14, 1, 8));
        final ItemEntity[] displaced = {null};
        final UUID[] displacedId = {null};
        final boolean[] exactContact = {false};
        helper.onEachTick(() -> {
            if (displaced[0] == null) {
                for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                        new AABB(helper.absolutePos(baseRel)).inflate(8.0))) {
                    if (!item.isAlive() || !item.getItem().is(Items.OAK_LOG)
                        || WorkerStackProvenance.readTransit(item.getItem())
                            .filter(transit -> transit.kind()
                                == WorkerStackProvenance.TransitKind.LUMBER_LOG)
                            .isEmpty()) {
                        continue;
                    }
                    displaced[0] = item;
                    displacedId[0] = item.getUUID();
                    item.setPos(displacedAbs.getX() + 0.5,
                        displacedAbs.getY() + 0.05,
                        displacedAbs.getZ() + 0.5);
                    item.setDeltaMovement(Vec3.ZERO);
                    item.setNoGravity(true);
                    break;
                }
            }
            if (displacedId[0] != null
                && level.getEntity(displacedId[0]) == null
                && lumberer.getOffhandItem().is(Items.OAK_LOG)) {
                exactContact[0] = true;
            }
        });

        helper.succeedWhen(() -> {
            int ownedPhysical = 0;
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(hearthAbs).inflate(20.0))) {
                if (item.isAlive() && item.getItem().is(Items.OAK_LOG)
                    && item != foreign) {
                    ownedPhysical += item.getItem().getCount();
                }
            }
            helper.assertTrue(displacedId[0] != null && exactContact[0]
                    && level.getEntity(displacedId[0]) == null,
                "the exact authenticated UUID outside the zone must transfer "
                    + "only on physical contact");
            helper.assertTrue(oakLogs(chest) == 4 && ownedPhysical == 0
                    && lumberer.getOffhandItem().isEmpty()
                    && lumberer.getCarryLoad() == 0
                    && lumberer.placedWorkContainerPos() == null,
                "edge output conservation must finish storage=4, world=0, "
                    + "hand=0, bag=0, sack=none (storage=" + oakLogs(chest)
                    + " world=" + ownedPhysical + " hand="
                    + lumberer.getOffhandItem() + " bag="
                    + lumberer.getCarryLoad() + ")");
            helper.assertTrue(foreign.isAlive()
                    && foreign.getItem().getCount() == 1,
                "an unstamped foreign log must remain outside worker authority");
        });
   }

    @GameTest(template = "empty16", timeoutTicks = 2200,
        batch = "lumberer_canopy_output")
    public void authenticatedCanopyOutputClearsOnlyReachableNaturalLeaf(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(2000);
        buildArena(helper, 16);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Canopy Timber", hearthAbs);
        settlement.radius = 14; data.settlements.put(settlement.id, settlement); data.setDirty();
        ((HearthBlockEntity) level.getBlockEntity(hearthAbs)).bindSettlement(settlement.id);
        Building camp = GameTestFixtures.register(helper, settlement, BuildingType.LUMBER_CAMP, 2, 10);
        WorkZone zone = WorkZone.between(settlement.id, camp.id, WorkZone.Type.LUMBER,
            level.dimension().location(), helper.absolutePos(new BlockPos(6, 0, 5)),
            helper.absolutePos(new BlockPos(13, 7, 11)), 2);
        helper.assertTrue(camp.commitWorkZone(1, zone), "fixture zone");
        BlockPos chestRel = new BlockPos(3, 1, 11); helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) level.getBlockEntity(helper.absolutePos(chestRel));
        chest.setItem(0, new ItemStack(Items.IRON_AXE));
        BlockPos dirt = new BlockPos(10, 1, 8), base = dirt.above();
        helper.setBlock(dirt, Blocks.DIRT);
        for (int i = 0; i < 4; i++) helper.setBlock(base.above(i), Blocks.OAK_LOG);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++)
            if (x != 0 || z != 0) helper.setBlock(base.above(4).offset(x, 0, z), Blocks.OAK_LEAVES);
        BlockPos leaf = new BlockPos(8, 3, 8), persistent = leaf.north(),
            wall = leaf.south(), outside = new BlockPos(5, 3, 8);
        // A neighboring branch keeps the natural leaf alive without leaving
        // a solid pedestal under the dropped item after clearance.
        helper.setBlock(leaf.west(), Blocks.OAK_LOG); helper.setBlock(leaf, Blocks.OAK_LEAVES);
        helper.setBlock(persistent.below(), Blocks.OAK_LOG);
        helper.setBlock(persistent, Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true));
        helper.setBlock(wall, Blocks.STONE_BRICKS);
        helper.setBlock(outside.below(), Blocks.OAK_LOG); helper.setBlock(outside, Blocks.OAK_LEAVES);
        SettlerEntity worker = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(6, 1, 8));
        worker.bindTo(settlement.id, settlement.center); settlement.putRecord(worker.getUUID(), "Solveig", Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, camp, worker).ok(), "fixture hire");
        worker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        BlockPos leafAbs = helper.absolutePos(leaf);
        final UUID[] id = {null};
        final WorkerStackProvenance.Transit[] originalTransit = {null};
        final boolean[] sawAxeLeaf = {false};
        final boolean[] pickedExactOutput = {false};
        final boolean[] conservationArmed = {false};
        AABB physicalBounds = new AABB(Vec3.atLowerCornerOf(helper.absolutePos(BlockPos.ZERO)),
            Vec3.atLowerCornerOf(helper.absolutePos(new BlockPos(16, 9, 16))));
        helper.onEachTick(() -> {
            for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                    new AABB(helper.absolutePos(base)).inflate(8))) {
                if (id[0] == null && item.isAlive() && item.getItem().is(Items.OAK_LOG)
                    && WorkerStackProvenance.readTransit(item.getItem()).isPresent()) {
                    originalTransit[0] = WorkerStackProvenance.readTransit(item.getItem()).orElseThrow();
                    id[0] = item.getUUID(); item.setPos(leafAbs.getX() + .5, leafAbs.getY() + 1.05, leafAbs.getZ() + .5);
                    item.setDeltaMovement(Vec3.ZERO);
                }
                if (id[0] != null && item.getUUID().equals(id[0]) && item.isAlive()
                    && level.getBlockState(leafAbs).isAir() && worker.getMainHandItem().is(ItemTags.AXES)) sawAxeLeaf[0] = true;
            }
            if (id[0] != null && level.getEntity(id[0]) == null
                && WorkerStackProvenance.readTransit(worker.getOffhandItem())
                    .filter(originalTransit[0]::equals).isPresent()) {
                pickedExactOutput[0] = true;
            }
            int offhandLogs = worker.getOffhandItem().is(Items.OAK_LOG)
                ? worker.getOffhandItem().getCount() : 0;
            int physicalTotal = physicalOakLogs(level, physicalBounds)
                + oakLogs(worker.bag) + offhandLogs + oakLogs(chest);
            helper.assertTrue(physicalTotal <= 4, "canopy recovery must never duplicate timber");
            boolean treeGone = true;
            for (int i = 0; i < 4; i++) {
                treeGone &= !level.getBlockState(helper.absolutePos(base.above(i))).is(Blocks.OAK_LOG);
            }
            if (treeGone && physicalTotal == 4) conservationArmed[0] = true;
            if (conservationArmed[0]) {
                helper.assertTrue(physicalTotal == 4,
                    "all produced timber must remain in ground, hand, bag or chest custody");
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(id[0] != null && sawAxeLeaf[0] && pickedExactOutput[0]
                    && conservationArmed[0] && level.getEntity(id[0]) == null,
                "stamped elevated UUID clears its natural support with axe then transfers"
                    + " [id=" + id[0] + "; leafCleared=" + sawAxeLeaf[0]
                    + "; picked=" + pickedExactOutput[0] + "; conserved=" + conservationArmed[0]
                    + "; leaf=" + level.getBlockState(leafAbs)
                    + "; item=" + (id[0] == null ? null : level.getEntity(id[0]))
                    + "; worker=" + worker.position() + "; activity=" + worker.getActivity()
                    + "; bag=" + worker.getCarryLoad() + "; hand=" + worker.getOffhandItem()
                    + "; chest=" + oakLogs(chest) + "; route=" + worker.routeFailureNote()
                    + "; nav=" + worker.getNavigation().getTargetPos() + "]");
            helper.assertTrue(oakLogs(chest) == 4 && worker.getCarryLoad() == 0
                    && worker.getOffhandItem().isEmpty() && worker.placedWorkContainerPos() == null
                    && physicalOakLogs(level, physicalBounds) == 0, "four logs conserved to camp");
            helper.assertTrue(level.getBlockState(helper.absolutePos(persistent)).getValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT), "persistent leaf protected");
            helper.assertTrue(level.getBlockState(helper.absolutePos(wall)).is(Blocks.STONE_BRICKS), "wall protected");
            helper.assertTrue(level.getBlockState(helper.absolutePos(outside)).is(Blocks.OAK_LEAVES), "outside natural leaf protected");
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
        final long[] pickupStartTick = {Long.MIN_VALUE};
        final long[] firstTransferDelta = {Long.MIN_VALUE};
        final String[] timingViolation = {null};

        helper.succeedWhen(() -> {
            BlockPos sack = lumberer.placedWorkContainerPos();
            // This assertion owns the tree collection site. The new camp
            // unload session has its own separately checked ground anchor.
            boolean sackVisible = sack != null
                && lumberer.placedWorkContainerKind() == WorkContainerKind.SACK
                && !lumberer.bagTransferPresentation().active();
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
                && lumberer.lastGroundItemPickupStartedTick() != Long.MIN_VALUE
                && lumberer.getOffhandItem().isEmpty()
                && sackVisible && physicalLogs > 0) {
                // GATHERING_LOG also covers putting the sack down; observe the
                // actual pickup event so the 12-tick hand contact stays exact.
                pickupStartTick[0] = lumberer.lastGroundItemPickupStartedTick();
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
        capacitySecondTripReload(helper, false, false);
    }

    @GameTest(template = "empty16", timeoutTicks = 2200,
        batch = "lumberer_capacity_expired_reload")
    public void capacitySecondTripRecoversExpiredOutputWithoutSavedIndex(
        GameTestHelper helper) {
        capacitySecondTripReload(helper, true, false);
    }

    /** A lower-Y reloaded worker must replace only an invalid old site, then bank the same drops. */
    @GameTest(template = "empty16", timeoutTicks = 2200,
        batch = "lumberer_stale_collection_site")
    public void expiredOutputRehomesOffZoneRememberedCollectionSite(
        GameTestHelper helper) {
        capacitySecondTripReload(helper, true, true);
    }

    private void capacitySecondTripReload(GameTestHelper helper, boolean expireLease,
                                          boolean staleRememberedSite) {
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
        WorkZone exactZone;
        if (staleRememberedSite) {
            exactZone = WorkZone.between(settlement.id, camp.id,
                WorkZone.Type.LUMBER, level.dimension().location(),
                helper.absolutePos(new BlockPos(6, 1, 6)),
                helper.absolutePos(new BlockPos(10, 8, 10)), 2);
            helper.assertTrue(camp.commitWorkZone(1, exactZone),
                "fixture: narrow exact zone around the real tree");
        } else {
            exactZone = camp.workZone().orElseThrow();
        }
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
        final boolean[] sawLawfulReplacementSite = {false};
        final BlockPos staleSite = helper.absolutePos(new BlockPos(2, 1, 2));
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
                if (expireLease) {
                    ItemEntity remaining = (ItemEntity) level.getEntity(remainingPhysicalId[0]);
                    remaining.getPersistentData().putLong("HearthsteadGroundCollectionLeaseExpiry",
                        level.getGameTime() - 1);
                    com.hearthstead.entity.ai.GroundCollectionSession.expireOwnershipLease(remaining);
                    saved.getCompound("NeoForgeData").remove("HearthsteadGroundCollectionDrops");
                    helper.assertTrue(!remaining.getPersistentData().hasUUID("HearthsteadGroundCollectionOwner"),
                        "expired public lease must release the real remaining item before recovery");
                }
                if (staleRememberedSite) {
                    saved.getCompound("NeoForgeData").putLong("HearthsteadLumberCollectionSite",
                        staleSite.asLong());
                }
                SettlerEntity replacement = ModEntities.SETTLER.get().create(level);
                helper.assertTrue(replacement != null,
                    "fixture must construct a replacement settler entity");
                replacement.load(saved);
                if (staleRememberedSite) {
                    replacement.setPos(Vec3.atBottomCenterOf(hearthAbs));
                }
                helper.assertTrue(originalId.equals(replacement.getUUID()),
                    "full NBT load must retain the ownership UUID");
                helper.assertTrue(level.addFreshEntity(replacement),
                    "the reloaded settler must re-enter the real ServerLevel");
                active[0] = replacement;
                reloaded[0] = true;
            }
            if (reloaded[0] && active[0].placedWorkContainerPos() != null) {
                BlockPos replacedSite = active[0].placedWorkContainerPos();
                sawPostReloadSack[0] = true;
                if (staleRememberedSite) {
                    helper.assertTrue(exactZone.contains(replacedSite)
                            && !staleSite.equals(replacedSite),
                        "recovery must replace the stale off-zone site with a lawful source-zone site, got "
                            + replacedSite + " from " + staleSite);
                    sawLawfulReplacementSite[0] = true;
                }
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
            helper.assertTrue(!staleRememberedSite || sawLawfulReplacementSite[0],
                "the stale persisted site must be replaced inside the exact work zone");
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
                    + lumberer.getCarryLoad() + "; position="
                    + lumberer.position() + "; activity="
                    + lumberer.getActivity() + "; route="
                    + lumberer.routeFailureNote() + "; navDone="
                    + lumberer.getNavigation().isDone() + "; navTarget="
                    + lumberer.getNavigation().getTargetPos()
                    + "; sinceUnsealed="
                    + (level.getGameTime() - unsealedAt[0])
                    + "; testTick=" + helper.getTick()
                    + "; unload=" + lumberer.bagTransferPresentation().active()
                    + ":" + lumberer.bagTransferPresentation().clock()
                    + ":" + lumberer.bagTransferPresentation().committed()
                    + "; placedBag=" + lumberer.placedWorkContainerPos()
                    + "; retryAfter=" + lumberer.getPersistentData().getLong(
                        "HearthsteadLumberRecoveryRetryAfter")
                    + "; hunger=" + lumberer.getHunger()
                    + "; energy=" + lumberer.getEnergy() + ")");
        });
    }

    /**
     * A normal closed door is a route, not a sealed-camp failure. Recovery
     * cargo already in the real bag must cross the only doorway and reach a
     * corner chest once, with physical contact on the mutation tick.
     */
    @GameTest(template = "empty16", timeoutTicks = 800,
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

        // Seven-by-seven room matching the native playtest failure: the
        // worker starts west/south of the room, while its only door sits on
        // the north wall and is deliberately offset from the corner chest.
        // A straight-line partial path ends at the west wall; a correct route
        // must discover the connected entrance and walk around the corner.
        for (int x = 7; x <= 13; x++) {
            for (int z = 5; z <= 11; z++) {
                // The native shell has an elevated full-block floor. Outside
                // feet are at Y=1; the door and interior feet are at Y=2.
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE_BRICKS);
                if (x == 7 || x == 13 || z == 5 || z == 11) {
                    for (int y = 2; y <= 4; y++) {
                        helper.setBlock(new BlockPos(x, y, z),
                            Blocks.STONE_BRICKS);
                    }
                }
            }
        }
        var lower = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.FACING, Direction.SOUTH)
            .setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER)
            .setValue(DoorBlock.OPEN, false);
        helper.setBlock(new BlockPos(11, 2, 5), lower);
        helper.setBlock(new BlockPos(11, 3, 5), lower
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));

        Building camp = GameTestFixtures.registerWithBounds(helper, settlement,
            BuildingType.LUMBER_CAMP, new BlockPos(7, 2, 5),
            new BlockPos(14, 3, 10), BoundingBox.fromCorners(
                helper.absolutePos(new BlockPos(7, 2, 5)),
                helper.absolutePos(new BlockPos(13, 4, 11))));
        BlockPos chestRel = new BlockPos(9, 2, 9);
        helper.setBlock(chestRel, Blocks.CHEST);
        Container chest = (Container) level.getBlockEntity(
            helper.absolutePos(chestRel));

        SettlerEntity lumberer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(3, 1, 10));
        lumberer.setSettlerName("Door Keeper");
        lumberer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(lumberer.getUUID(), "Door Keeper", Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, camp, lumberer).ok(),
            "fixture: the closed-door lumberer must be genuinely employed");
        helper.assertTrue(lumberer.bag.addItem(
                new ItemStack(Items.OAK_LOG)).isEmpty(),
            "fixture: one exact log must seed recovery routing");

        BlockPos door = helper.absolutePos(new BlockPos(11, 2, 5));
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

    /**
     * The exact owned ItemEntity must outlive a bounded unreachable-route
     * pause. This is the deterministic form of the intermittent 3/4-log
     * failure: keep one real output inside a sealed cell until the retry gate
     * has elapsed, then open one side and require that same UUID to reach camp
     * exactly once.
     */
    @GameTest(template = "empty16", timeoutTicks = 1400,
        batch = "lumberer_owned_drop_retry")
    public void ownedDropSurvivesRoutePauseAndTransfersAfterOpening(
        GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(2000);
        buildArena(helper, 16);

        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        SettlementSavedData data = SettlementSavedData.get(level);
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Lease Timber", hearthAbs);
        settlement.radius = 14;
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

        BlockPos dirtRel = new BlockPos(12, 1, 8);
        helper.setBlock(dirtRel, Blocks.DIRT);
        BlockPos treeRel = dirtRel.above();
        helper.setBlock(treeRel, Blocks.OAK_LOG);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    helper.setBlock(treeRel.above().offset(dx, 0, dz),
                        Blocks.OAK_LEAVES);
                }
            }
        }

        BlockPos cageRel = new BlockPos(7, 1, 8);
        BlockPos openingRel = cageRel.west();
        for (BlockPos wall : new BlockPos[]{cageRel.north(), cageRel.south(),
            cageRel.east(), openingRel}) {
            helper.setBlock(wall, Blocks.STONE_BRICKS);
            helper.setBlock(wall.above(), Blocks.STONE_BRICKS);
        }

        SettlerEntity lumberer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(10, 1, 8));
        lumberer.setSettlerName("Lease Keeper");
        lumberer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(lumberer.getUUID(), "Lease Keeper",
            Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, camp, lumberer).ok(),
            "fixture: owned-drop retry must run under real camp authority");

        BlockPos cageAbs = helper.absolutePos(cageRel);
        final ItemEntity[] pinned = {null};
        final UUID[] pinnedId = {null};
        final long[] pauseAt = {Long.MIN_VALUE};
        final boolean[] opened = {false};
        final boolean[] exactTransfer = {false};
        helper.onEachTick(() -> {
            if (pinned[0] == null) {
                for (ItemEntity item : level.getEntitiesOfClass(ItemEntity.class,
                        new AABB(hearthAbs).inflate(20.0))) {
                    if (item.isAlive() && item.getItem().is(Items.OAK_LOG)) {
                        pinned[0] = item;
                        pinnedId[0] = item.getUUID();
                        item.setPos(cageAbs.getX() + 0.5,
                            cageAbs.getY() + 0.25, cageAbs.getZ() + 0.5);
                        item.setDeltaMovement(Vec3.ZERO);
                        item.setNoGravity(true);
                        break;
                    }
                }
            }
            if (pinned[0] == null) {
                return;
            }

            long now = level.getGameTime();
            if (pauseAt[0] == Long.MIN_VALUE
                && lumberer.routeFailureNote().startsWith(
                    "felled_item_unreachable@")) {
                pauseAt[0] = now;
            }
            if (pauseAt[0] != Long.MIN_VALUE && !opened[0]) {
                helper.assertTrue(pinned[0].isAlive()
                        && lumberer.getUUID().equals(pinned[0].getTarget())
                        && pinned[0].hasPickUpDelay(),
                    "bounded pause must retain the exact physical UUID lease");
                if (now >= pauseAt[0]
                        + LumbererWorkGoal.recoveryRetryTicksForQa() + 2L) {
                    helper.setBlock(openingRel, Blocks.AIR);
                    helper.setBlock(openingRel.above(), Blocks.AIR);
                    opened[0] = true;
                }
            }
            if (!pinned[0].isAlive() && pinnedId[0] != null
                && level.getEntity(pinnedId[0]) == null
                && lumberer.getOffhandItem().is(Items.OAK_LOG)) {
                exactTransfer[0] = true;
            }
        });

        helper.succeedWhen(() -> {
            int stored = oakLogs(chest);
            int physical = physicalOakLogs(level, new AABB(hearthAbs).inflate(20.0));
            helper.assertTrue(pauseAt[0] != Long.MIN_VALUE && opened[0],
                "fixture must observe one bounded yield and open only after retry");
            helper.assertTrue(pinnedId[0] != null && exactTransfer[0]
                    && level.getEntity(pinnedId[0]) == null,
                "the exact retained UUID must transfer on physical contact");
            helper.assertTrue(stored == 1 && physical == 0
                    && lumberer.getOffhandItem().isEmpty()
                    && lumberer.getCarryLoad() == 0,
                "retry conservation must be storage=1, world=0, hand=0, bag=0"
                    + " (storage=" + stored + " world=" + physical
                    + " route=" + lumberer.routeFailureNote() + ")");
        });
    }

    /** Real tree output must survive a stand outside the claim beside in-zone ground. */
    @GameTest(template = "empty16", timeoutTicks = 2200,
        batch = "lumberer_zone_floor_contact")
    public void inZoneSackUsesFloorBelowClaimBeforeStartingNextTree(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        level.setDayTime(2000);
        buildArena(helper, 16);
        BlockPos hearthRel = new BlockPos(2, 1, 2);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        BlockPos hearthAbs = helper.absolutePos(hearthRel);
        Settlement settlement = new Settlement(UUID.randomUUID(), "Grounded Timber", hearthAbs);
        settlement.radius = 14;
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        ((HearthBlockEntity) level.getBlockEntity(hearthAbs)).bindSettlement(settlement.id);
        Building camp = GameTestFixtures.register(helper, settlement,
            BuildingType.LUMBER_CAMP, 2, 11);
        WorkZone zone = WorkZone.between(settlement.id, camp.id,
            WorkZone.Type.LUMBER, level.dimension().location(),
            helper.absolutePos(new BlockPos(6, 1, 6)),
            helper.absolutePos(new BlockPos(14, 7, 11)), 2);
        helper.assertTrue(camp.commitWorkZone(1, zone), "fixture: exact ground-height zone");
        BlockPos chestRel = new BlockPos(3, 1, 12);
        helper.setBlock(chestRel, Blocks.CHEST);
        BlockPos chestPos = helper.absolutePos(chestRel);
        Container chest = (Container) level.getBlockEntity(chestPos);
        BlockPos firstBase = new BlockPos(8, 2, 7);
        BlockPos secondBase = new BlockPos(12, 2, 9);
        for (BlockPos base : java.util.List.of(firstBase, secondBase)) {
            helper.setBlock(base.below(), Blocks.DIRT);
            for (int y = 0; y < 4; y++) helper.setBlock(base.above(y), Blocks.OAK_LOG);
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx != 0 || dz != 0) helper.setBlock(base.above(4).offset(dx, 0, dz),
                        Blocks.OAK_LEAVES);
                }
            }
        }
        SettlerEntity lumberer = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(7, 1, 5));
        lumberer.setSettlerName("Floor Keeper");
        lumberer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(lumberer.getUUID(), "Floor Keeper", Profession.NONE);
        helper.assertTrue(Employment.hire(level, settlement, camp, lumberer).ok(),
            "fixture: real Lumber Camp employment");
        lumberer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_AXE));
        helper.assertTrue(!zone.contains(lumberer.blockPosition())
                && zone.contains(lumberer.blockPosition().south())
                && !zone.contains(lumberer.blockPosition().south().below()),
            "fixture: outside feet, inside sack candidate, floor below minY");
        boolean[] sawOutsideChop = {false};
        boolean[] sawValidSack = {false};
        boolean[] sawPhysicalPickup = {false};
        boolean[] sawHaul = {false};
        int[] previousStored = {0};
        helper.onEachTick(() -> {
            if (lumberer.getActivity() == SettlerActivity.WORK_CHOP
                && !zone.contains(lumberer.blockPosition())) sawOutsideChop[0] = true;
            BlockPos placed = lumberer.placedWorkContainerPos();
            if (placed != null && lumberer.bagTransferPresentation().active()) {
                var unloading = lumberer.bagTransferPresentation();
                helper.assertTrue(placed.equals(unloading.bagAnchor())
                        && chestPos.equals(unloading.containerPos()),
                    "camp unloading must retain its exact separate bag and destination");
            } else if (placed != null) {
                helper.assertTrue(zone.contains(placed), "the actual sack must remain inside its claim");
                if (!zone.contains(placed.below())) sawValidSack[0] = true;
            }
            if (lumberer.getOffhandItem().is(Items.OAK_LOG)) sawPhysicalPickup[0] = true;
            if (lumberer.getActivity() == SettlerActivity.HAULING_LOG) sawHaul[0] = true;
            int stored = oakLogs(chest);
            if (stored > previousStored[0]) helper.assertTrue(
                ContainerApproach.inspect(level, lumberer, chestPos).canInteract(),
                "every storage increment requires actual chest contact");
            previousStored[0] = stored;
            if (!helper.getBlockState(secondBase.above(3)).is(Blocks.OAK_LOG)) {
                helper.assertTrue(stored >= 4,
                    "first tree's four real logs must bank before second-tree chopping; stored="
                        + stored + " route=" + lumberer.routeFailureNote());
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(sawOutsideChop[0] && sawValidSack[0]
                    && sawPhysicalPickup[0] && sawHaul[0],
                "must exercise outside-edge chopping, grounded in-zone sack, pickup and loaded return");
            helper.assertTrue(oakLogs(chest) == 8 && lumberer.getCarryLoad() == 0
                    && lumberer.getOffhandItem().isEmpty()
                    && lumberer.placedWorkContainerPos() == null
                    && physicalOakLogs(level, new AABB(
                        net.minecraft.world.phys.Vec3.atLowerCornerOf(
                            helper.absolutePos(new BlockPos(0, 0, 0))),
                        net.minecraft.world.phys.Vec3.atLowerCornerOf(
                            helper.absolutePos(new BlockPos(16, 16, 16))))) == 0,
                "all eight logs must be in the same camp with none in bag, hand, sack or world");
            helper.assertTrue(helper.getBlockState(firstBase).is(Blocks.OAK_SAPLING)
                    && helper.getBlockState(secondBase).is(Blocks.OAK_SAPLING),
                "both original trees must finish their ordinary replant");
            var ledger = WorkerProvenanceSavedData.get(level);
            var actions = ledger.actionsForSettlement(settlement.id).stream()
                .filter(action -> action.workerId().equals(lumberer.getUUID())
                    && action.kind() == WorkerProvenanceSavedData.Kind.LUMBER_TREE).toList();
            helper.assertTrue(actions.size() == 2, "exactly two distinct authoritative tree actions");
            for (var action : actions) {
                var receipts = ledger.receiptsFor(action.id());
                helper.assertTrue(action.workTerminal() && receipts.size() == 4
                        && receipts.stream().allMatch(receipt -> receipt.count() == 1
                            && receipt.workerId().equals(lumberer.getUUID())
                            && receipt.buildingId().equals(camp.id)
                            && receipt.itemId().equals(net.minecraft.resources.ResourceLocation
                                .withDefaultNamespace("oak_log"))
                            && receipt.containerPos().equals(chestPos)),
                    "each tree needs four exact-worker, exact-camp physical output receipts");
            }
        });
    }

    /** Full storage and a real entity reload cannot duplicate a visible unit. */
    @GameTest(template = "empty16", timeoutTicks = 900, batch = "lumberer_unload_units")
    public void unloadWaitsForSpaceAndResumesExactBagAfterReload(GameTestHelper helper) {
        ServerLevel level=helper.getLevel();
        buildArena(helper,16);
        BlockPos center=helper.absolutePos(new BlockPos(2,1,2));
        Settlement settlement=new Settlement(UUID.randomUUID(),"Unit unload",center);
        settlement.radius=14;
        SettlementSavedData.get(level).settlements.put(settlement.id,settlement);
        helper.setBlock(new BlockPos(2,1,2),ModBlocks.HEARTH.get());
        ((HearthBlockEntity)level.getBlockEntity(center)).bindSettlement(settlement.id);
        Building camp=GameTestFixtures.register(helper,settlement,BuildingType.LUMBER_CAMP,11,11);
        BlockPos chestPos=helper.absolutePos(new BlockPos(12,1,12));
        helper.setBlock(new BlockPos(12,1,12),Blocks.CHEST);
        Container chest=(Container)level.getBlockEntity(chestPos);
        for(int i=0;i<chest.getContainerSize();i++) chest.setItem(i,new ItemStack(Items.STONE,64));
        SettlerEntity original=helper.spawn(ModEntities.SETTLER.get(),new BlockPos(12,1,10));
        original.bindTo(settlement.id,center);
        settlement.putRecord(original.getUUID(),"Unit worker",Profession.NONE);
        helper.assertTrue(Employment.hire(level,settlement,camp,original).ok(),"actual lumber employment");
        original.bag.setItem(0,new ItemStack(Items.OAK_LOG,3));
        final SettlerEntity[] active={original};
        final int[] blockedTicks={0};
        final int[] lastStored={0};
        final boolean[] opened={false}, reloaded={false};
        final BlockPos[] anchor={null};
        helper.onEachTick(() -> {
            SettlerEntity worker=active[0];
            int stored=oakLogs(chest);
            int bagged=0;
            for(int slot=0;slot<worker.bag.getContainerSize();slot++) {
                ItemStack live=worker.bag.getItem(slot);
                if(live.is(Items.OAK_LOG)) bagged+=live.getCount();
            }
            helper.assertTrue(stored+bagged==3,"physical chest plus bag must conserve all three logs");
            helper.assertTrue(stored-lastStored[0]<=1,"no batched inventory jump");
            var view=worker.bagTransferPresentation();
            if(view.active()) {
                if(anchor[0]==null) anchor[0]=view.bagAnchor();
                helper.assertTrue(anchor[0].equals(view.bagAnchor()),"unload anchor survives repeats and reload");
                if(!opened[0] && view.clock()==47) {
                    helper.assertTrue(!view.committed() && stored==0,"full chest never commits or advances contact");
                    if(++blockedTicks[0]>=20) { chest.setItem(0,ItemStack.EMPTY);opened[0]=true; }
                }
            }
            if(stored>lastStored[0]) {
                helper.assertTrue(view.active() && view.committed() && view.clock()==48
                    && view.item().getCount()==1,"exact visible unit receipt owns each insertion");
            }
            lastStored[0]=stored;
            if(stored==1 && !reloaded[0]) {
                CompoundTag saved=worker.saveWithoutId(new CompoundTag());
                UUID id=worker.getUUID();
                worker.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
                SettlerEntity replacement=ModEntities.SETTLER.get().create(level);
                helper.assertTrue(replacement!=null,"replacement exists");
                replacement.load(saved);
                helper.assertTrue(id.equals(replacement.getUUID()) && replacement.bagTransferPresentation().committed(),
                    "reload preserves identity and consumed contact ticket");
                helper.assertTrue(level.addFreshEntity(replacement),"actual replacement publication");
                active[0]=replacement;reloaded[0]=true;
            }
            if(stored==3 && !active[0].bagTransferPresentation().active()) {
                helper.assertTrue(opened[0] && reloaded[0] && active[0].placedWorkContainerPos()==null,
                    "only final recovery picks up the emptied bag");
                for(int i=1;i<chest.getContainerSize();i++) helper.assertTrue(chest.getItem(i).is(Items.STONE)
                    && chest.getItem(i).getCount()==64,"unrelated chest stock remains exact");
                SettlementSavedData.get(level).settlements.remove(settlement.id);
                SettlementSavedData.get(level).setDirty();
                helper.succeed();
            }
        });
    }
}
