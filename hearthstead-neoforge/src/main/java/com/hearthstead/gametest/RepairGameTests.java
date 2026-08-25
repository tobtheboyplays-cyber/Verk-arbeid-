package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.RepairWorkGoal;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidDirector;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.List;
import java.util.UUID;

/**
 * SLICE REPAIR-1 — the repair dugnad, end to end.
 *
 * <p>What these guard: DESIGN.md's aftermath ("repair dugnad + defense
 * report") finally has its repair half, and it obeys the house physics.
 * Chest truth both ways — a repair consumes exactly one real item from a
 * real slot ({@code aMasonRepairsARaidScar...}), and no material means no
 * repair, with nothing consumed and nothing conjured
 * ({@code noMaterialMeansNoRepair...}). One scar is one settler's business
 * at a time via {@link RepairWorkGoal}'s claim ledger, proven the black-box
 * way: two settlers, two scars, exactly enough material for each — any
 * double-claim breaks the conservation arithmetic and the test times out
 * ({@code twoRepairersSplitTwoScars...}). And the ledger itself is bounded,
 * deduplicated and survives NBT verbatim
 * ({@code theScarLedgerIsBounded...}), because a scar that forgets what
 * stood there can only ever be rebuilt wrong.
 *
 * <p>Arena idiom is {@code RaiderGameTests}/{@code TradeMasonGameTests}:
 * the test builds its own floor (structure templates only reserve bounds),
 * registers the settlement through {@link SettlementSavedData}, and keeps
 * the radius modest so a neighbour's concurrently running arena is never
 * answered for.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RepairGameTests {

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static Settlement makeSettlement(GameTestHelper helper, BlockPos centerRel) {
        ServerLevel level = helper.getLevel();
        var arena = helper.getBounds();
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.values().removeIf(old ->
            arena.contains(old.center.getX() + 0.5, old.center.getY() + 0.5,
                old.center.getZ() + 0.5));
        Settlement s = new Settlement(UUID.randomUUID(), "Murbygd",
            helper.absolutePos(centerRel));
        s.radius = 12;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static Building building(GameTestHelper helper, Settlement s,
                                     BuildingType type, int x, int z) {
        BlockPos anchor = helper.absolutePos(new BlockPos(x, 1, z));
        Building building = new Building(UUID.randomUUID(), type,
            helper.absolutePos(new BlockPos(x, 2, z)), anchor,
            BoundingBox.fromCorners(anchor, anchor.offset(3, 2, 3)));
        building.valid = true;
        s.buildings.add(building);
        return building;
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    private static Container containerAt(GameTestHelper helper, BlockPos rel) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        return be instanceof Container c ? c : null;
    }

    private static int countOf(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countInHearth(HearthBlockEntity hearth, Item item) {
        int total = 0;
        ItemStackHandler inventory = hearth.getInventory();
        for (int slot = 0; slot < inventory.getSlots(); slot++) {
            ItemStack stack = inventory.getStackInSlot(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    /**
     * The core loop: a scar, a hired mason, stone bricks in her own chest.
     * The block comes back exactly as recorded, exactly one brick leaves
     * the chest (chest truth — the repair is paid for, once), the scar
     * closes, and WORK_CHISEL — the deliberate §16.3 reuse standing in for
     * the unauthored EMERGENCY_REPAIR clip — is actually observed doing the
     * work, not just declared.
     */
    @GameTest(template = "empty16", timeoutTicks = 1200, batch = "repair_day")
    public void aMasonRepairsARaidScarConsumingExactlyOneBrick(GameTestHelper helper) {
        helper.getLevel().setDayTime(3000); // mid-morning: working hours
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(7, 1, 7));
        Building yard = building(helper, s, BuildingType.MASON, 3, 3);
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.CHEST);
        Container chest = containerAt(helper, new BlockPos(4, 1, 3));
        helper.assertTrue(chest != null, "the yard chest should be a container");
        chest.setItem(0, new ItemStack(Items.STONE_BRICKS, 3));

        SettlerEntity steinar = settler(helper, s, "Steinar", 3, 3);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, yard, steinar).ok(),
            "a mason's yard must be able to take a mason");

        BlockPos scarRel = new BlockPos(10, 1, 10);
        BlockPos scarAbs = helper.absolutePos(scarRel);
        helper.assertTrue(helper.getBlockState(scarRel).isAir(),
            "the wound must start as a hole");
        RaidDirector.recordScar(helper.getLevel(), s.id, scarAbs,
            Blocks.STONE_BRICKS.defaultBlockState());
        helper.assertTrue(RaidDirector.hasScarAt(helper.getLevel(), s.id, scarAbs),
            "the scar must be on the ledger before anyone can work it");

        final boolean[] sawChisel = {false};
        helper.succeedWhen(() -> {
            if (steinar.getActivity() == SettlerActivity.WORK_CHISEL) {
                sawChisel[0] = true;
            }
            helper.assertTrue(
                helper.getBlockState(scarRel).is(Blocks.STONE_BRICKS),
                "the original block must stand again, got "
                    + helper.getBlockState(scarRel));
            int left = countOf(chest, Items.STONE_BRICKS);
            helper.assertTrue(left == 2,
                "exactly one brick must be consumed (chest truth), left " + left);
            helper.assertTrue(
                RaidDirector.scarsOf(helper.getLevel(), s.id).isEmpty(),
                "a repaired scar must close on the ledger");
            helper.assertTrue(sawChisel[0],
                "WORK_CHISEL must actually be observed doing the repair, "
                    + "not just documented as the stand-in motion");
        });
    }

    /**
     * No material, no repair — the negative that keeps the loop honest.
     * The chest holds wheat, which matches neither the exact item nor
     * either fallback family (planks for wood, cobble/stone/bricks for
     * stone), so after a generous wait: the hole is still a hole, the scar
     * is still open and unclaimed, the wheat is untouched, and no brick
     * has been conjured from anywhere (items are conserved — a repair that
     * ran without paying would be creation, not repair).
     */
    @GameTest(template = "empty16", timeoutTicks = 600, batch = "repair_day")
    public void noMaterialMeansNoRepairAndNothingConsumed(GameTestHelper helper) {
        helper.getLevel().setDayTime(3000);
        buildArena(helper, 14);
        Settlement s = makeSettlement(helper, new BlockPos(7, 1, 7));
        Building yard = building(helper, s, BuildingType.MASON, 3, 3);
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.CHEST);
        Container chest = containerAt(helper, new BlockPos(4, 1, 3));
        helper.assertTrue(chest != null, "the yard chest should be a container");
        chest.setItem(0, new ItemStack(Items.WHEAT, 5));

        SettlerEntity steinar = settler(helper, s, "Steinar", 3, 3);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, yard, steinar).ok(),
            "a mason's yard must be able to take a mason");

        BlockPos scarRel = new BlockPos(10, 1, 10);
        BlockPos scarAbs = helper.absolutePos(scarRel);
        RaidDirector.recordScar(helper.getLevel(), s.id, scarAbs,
            Blocks.STONE_BRICKS.defaultBlockState());

        helper.runAtTickTime(400, () -> {
            helper.assertTrue(helper.getBlockState(scarRel).isAir(),
                "with no material the hole must stay a hole, got "
                    + helper.getBlockState(scarRel));
            helper.assertTrue(
                RaidDirector.hasScarAt(helper.getLevel(), s.id, scarAbs),
                "the scar must stay open for the day material arrives");
            helper.assertTrue(!RepairWorkGoal.scarIsClaimed(s.id, scarAbs),
                "a scar nobody can pay for must not be claimed either -- a "
                    + "claim with no material would just park a settler");
            helper.assertTrue(countOf(chest, Items.WHEAT) == 5,
                "the wheat must be untouched, got " + countOf(chest, Items.WHEAT));
            helper.assertTrue(countOf(chest, Items.STONE_BRICKS) == 0,
                "and no brick may appear from nowhere");
            helper.succeed();
        });
    }

    /**
     * The dugnad, and the claim ledger under load: a hired mason (bricks
     * in her own yard chest) and an unemployed settler (bricks only at the
     * communal hearth — the dugnad source) against two scars, with exactly
     * one brick per scar in the whole world. Conservation is the
     * double-claim tripwire: if two settlers ever worked one scar, its
     * repair would consume both bricks (or restore without paying), the
     * arithmetic "blocks standing + bricks left = 2" would break, and the
     * end state below would be unreachable inside the timeout.
     */
    @GameTest(template = "empty16", timeoutTicks = 1600, batch = "repair_day")
    public void twoRepairersSplitTwoScarsWithoutDoubleClaim(GameTestHelper helper) {
        helper.getLevel().setDayTime(3000);
        buildArena(helper, 14);
        BlockPos hearthRel = new BlockPos(11, 1, 11);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        Settlement s = makeSettlement(helper, hearthRel);
        helper.assertTrue(helper.getLevel().getBlockEntity(
                helper.absolutePos(hearthRel)) instanceof HearthBlockEntity,
            "the arena hearth should have its block entity");
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel()
            .getBlockEntity(helper.absolutePos(hearthRel));
        hearth.bindSettlement(s.id);
        hearth.insertGoods(new ItemStack(Items.STONE_BRICKS, 1));

        Building yard = building(helper, s, BuildingType.MASON, 3, 3);
        helper.setBlock(new BlockPos(4, 1, 3), Blocks.CHEST);
        Container chest = containerAt(helper, new BlockPos(4, 1, 3));
        helper.assertTrue(chest != null, "the yard chest should be a container");
        chest.setItem(0, new ItemStack(Items.STONE_BRICKS, 1));

        SettlerEntity steinar = settler(helper, s, "Steinar", 3, 3);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, yard, steinar).ok(),
            "a mason's yard must be able to take a mason");
        SettlerEntity ubbe = settler(helper, s, "Ubbe", 10, 10);
        helper.assertTrue(ubbe.getProfession() == Profession.NONE,
            "the second repairer must be unemployed -- the dugnad is exactly "
                + "the idle turning out");

        BlockPos scarNearYardRel = new BlockPos(5, 1, 6);
        BlockPos scarNearHearthRel = new BlockPos(9, 1, 12);
        BlockPos scarNearYardAbs = helper.absolutePos(scarNearYardRel);
        BlockPos scarNearHearthAbs = helper.absolutePos(scarNearHearthRel);
        RaidDirector.recordScar(helper.getLevel(), s.id, scarNearYardAbs,
            Blocks.STONE_BRICKS.defaultBlockState());
        RaidDirector.recordScar(helper.getLevel(), s.id, scarNearHearthAbs,
            Blocks.STONE_BRICKS.defaultBlockState());

        helper.succeedWhen(() -> {
            int standing =
                (helper.getBlockState(scarNearYardRel).is(Blocks.STONE_BRICKS) ? 1 : 0)
                + (helper.getBlockState(scarNearHearthRel).is(Blocks.STONE_BRICKS) ? 1 : 0);
            int bricksLeft = countOf(chest, Items.STONE_BRICKS)
                + countInHearth(hearth, Items.STONE_BRICKS);
            helper.assertTrue(standing + bricksLeft == 2,
                "conservation must hold at every step -- a double-claim "
                    + "breaks it: standing=" + standing + " left=" + bricksLeft);
            helper.assertTrue(standing == 2,
                "both scars must be repaired, standing=" + standing
                    + " (steinar=" + steinar.getActivity()
                    + " ubbe=" + ubbe.getActivity() + ")");
            helper.assertTrue(bricksLeft == 0,
                "exactly one brick per scar must be consumed, left " + bricksLeft);
            helper.assertTrue(
                RaidDirector.scarsOf(helper.getLevel(), s.id).isEmpty(),
                "both scars must close on the ledger");
        });
    }

    /**
     * The ledger itself: capped at {@link RaidDirector#MAX_SCARS_PER_RAID}
     * with the oldest dropped first, first-recording-wins per position (the
     * first observer saw the true original; a second hit is wreckage), and
     * the whole book survives its own SavedData round trip verbatim —
     * positions and exact BlockStates — because a scar that forgets what
     * stood there can only be rebuilt wrong.
     */
    @GameTest(template = "empty16", timeoutTicks = 100, batch = "repair_day")
    public void theScarLedgerIsBoundedDedupedAndSurvivesNbt(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        UUID id = UUID.randomUUID(); // ledger-only test: no settlement needed
        BlockPos base = helper.absolutePos(new BlockPos(0, 60, 0));
        int over = RaidDirector.MAX_SCARS_PER_RAID + 6;
        for (int i = 0; i < over; i++) {
            RaidDirector.recordScar(level, id, base.offset(i, 0, 0),
                Blocks.STONE_BRICKS.defaultBlockState());
        }
        List<RaidDirector.Scar> scars = RaidDirector.scarsOf(level, id);
        helper.assertTrue(scars.size() == RaidDirector.MAX_SCARS_PER_RAID,
            "the ledger must cap at " + RaidDirector.MAX_SCARS_PER_RAID
                + ", got " + scars.size());
        helper.assertTrue(!RaidDirector.hasScarAt(level, id, base),
            "the oldest wound must be the one that fades");
        helper.assertTrue(
            RaidDirector.hasScarAt(level, id, base.offset(over - 1, 0, 0)),
            "and the newest must survive");

        // First recording wins: a second hit on the same position is
        // destroying wreckage, not architecture.
        BlockPos dup = base.offset(over - 1, 0, 0);
        RaidDirector.recordScar(level, id, dup, Blocks.OAK_PLANKS.defaultBlockState());
        for (RaidDirector.Scar scar : RaidDirector.scarsOf(level, id)) {
            if (scar.pos().equals(dup)) {
                helper.assertTrue(
                    scar.original() == Blocks.STONE_BRICKS.defaultBlockState(),
                    "the first recording must hold the true original, got "
                        + scar.original());
            }
        }

        // The book survives its own NBT round trip verbatim.
        RaidDirector.RaidScars book = RaidDirector.RaidScars.get(level);
        CompoundTag saved = book.save(new CompoundTag(), level.registryAccess());
        RaidDirector.RaidScars reloaded =
            RaidDirector.RaidScars.load(saved, level.registryAccess());
        List<RaidDirector.Scar> back = reloaded.scarsOf(id);
        helper.assertTrue(back.equals(RaidDirector.scarsOf(level, id)),
            "the ledger must survive NBT verbatim: " + back.size() + " vs "
                + RaidDirector.scarsOf(level, id).size());

        // Leave the shared level's book clean for the neighbours.
        for (RaidDirector.Scar scar : RaidDirector.scarsOf(level, id)) {
            RaidDirector.clearScar(level, id, scar.pos());
        }
        helper.succeed();
    }
}
