package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.gear.GearGate;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import com.hearthstead.settlement.warehouse.WarehouseLevelService;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * SCENARIO lane (26 Sep): progression branches with JUnit-only coverage
 * until now (batches {@code scenario_warehouse_*}, {@code scenario_gear_*}).
 *
 * <ul>
 *   <li>Warehouse levels 3, 4 and 5 in the world: a warehouse whose room
 *   meets checklist L5 is recognised only up to what the Logistics tree
 *   allows. Learning Warehouse Racks (paid for real from the Hearth), then
 *   the Great and the Royal Storehouse, raises it through L3, L4 and L5,
 *   with 64, 128 and 256 managed containers out of 300.</li>
 *   <li>Gear tier 4 (Netherite) in the world: a Captain with the Kingdom
 *   Crown and the Master Armoury wears it, and a Sergeant with the same
 *   knowledge refuses it and keeps it in the pack.</li>
 *   <li>Demotion keeps what is already worn (by design, GearGate never
 *   strips) but refuses the next hand-over, and nothing is lost.</li>
 *   <li>The refusal line names the settler and their rank.</li>
 * </ul>
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class ScenarioProgressionGameTests {

    public ScenarioProgressionGameTests() {
    }

    // ------------------------------------------------------------ warehouse --

    @GameTest(template = "empty32", batch = "scenario_warehouse_levels", timeoutTicks = 200)
    public void warehouseLevelsThreeFourFiveFollowTheLogisticsTree(GameTestHelper helper) {
        for (int x = 0; x < 32; x++) for (int z = 0; z < 32; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 4; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        BlockPos hearthRel = new BlockPos(30, 1, 30);
        helper.setBlock(hearthRel, ModBlocks.HEARTH.get());
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel));
        Settlement s = new Settlement(UUID.randomUUID(), "Storeholm", helper.absolutePos(hearthRel));
        s.radius = 30;
        SettlementSavedData.get(helper.getLevel()).settlements.put(s.id, s);
        hearth.bindSettlement(s.id);
        Development.revisionOf(helper.getLevel(), s);

        Building warehouse = GameTestFixtures.registerWithBounds(helper, s, BuildingType.WAREHOUSE,
            new BlockPos(1, 1, 1), new BlockPos(1, 3, 1),
            BoundingBox.fromCorners(helper.absolutePos(new BlockPos(1, 1, 1)), helper.absolutePos(new BlockPos(28, 3, 28))));
        warehouse.interiorVolume = 2000;
        warehouse.level = 5; // the room meets checklist L5; only the tree may hold it back
        int placed = 0;
        for (int x = 2; x <= 27 && placed < 300; x++) {
            for (int z = 2; z <= 27 && placed < 300; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.BARREL);
                placed++;
            }
        }
        helper.assertTrue(placed == 300, "fixture: 300 containers");
        var level = helper.getLevel();
        WarehouseLevelService.sync(level, s, warehouse);
        WarehouseLevelService.Status start = WarehouseLevelService.status(level, s, warehouse);
        helper.assertTrue(start.level() == 2 && start.capacity() == 32 && start.techLocked()
                && start.nextGate() == PostRaidUpgrade.WAREHOUSE_RACKS,
            "no Logistics knowledge: an L5 room is recognised as L2 and names Warehouse Racks, got " + start);

        DevelopmentState state = Development.of(level, s);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        DevelopmentQuests.ensureEligibleBaselines(state);
        // Warehouse Racks has no quest: pay it from the Hearth, exactly once.
        for (DevelopmentNode.Cost cost : PostRaidUpgrade.WAREHOUSE_RACKS.costs()) {
            put(hearth, cost.item(), cost.count());
        }
        int revision = Development.revisionOf(level, s);
        Development.Result racks = Development.purchaseUpgrade(level, s, hearth, PostRaidUpgrade.WAREHOUSE_RACKS,
            revision, null);
        helper.assertTrue(racks == Development.Result.APPLIED, "Warehouse Racks is bought: " + racks);
        for (DevelopmentNode.Cost cost : PostRaidUpgrade.WAREHOUSE_RACKS.costs()) {
            helper.assertTrue(count(hearth, cost.item()) == 0, "Warehouse Racks took exactly its " + cost.item());
        }
        assertLevel(helper, s, warehouse, 3, 64, 300);

        // The Storehouses are gated by Courier-delivery quests (proven by the development tests).
        state.unlockUpgrade(PostRaidUpgrade.GREAT_STOREHOUSE);
        assertLevel(helper, s, warehouse, 4, 128, 300);
        state.unlockUpgrade(PostRaidUpgrade.ROYAL_STOREHOUSE);
        assertLevel(helper, s, warehouse, 5, 256, 300);
        helper.assertTrue(!WarehouseLevelService.status(level, s, warehouse).techLocked(),
            "at L5 nothing is tech-locked any more");
        SettlementSavedData.get(level).settlements.remove(s.id);
        helper.succeed();
    }

    private static void assertLevel(GameTestHelper helper, Settlement s, Building warehouse,
                                    int level, int capacity, int total) {
        WarehouseLevelService.sync(helper.getLevel(), s, warehouse);
        WarehouseLevelService.Status status = WarehouseLevelService.status(helper.getLevel(), s, warehouse);
        int managed = WarehouseIndex.containers(helper.getLevel(), warehouse).size();
        helper.assertTrue(status.level() == level && status.capacity() == capacity
                && managed == Math.min(capacity, total) && status.unmanaged() == total - Math.min(capacity, total),
            "L" + level + ": capacity " + capacity + ", managed " + Math.min(capacity, total) + " of " + total
                + ", got " + status + " managed " + managed);
    }

    // ----------------------------------------------------------------- gear --

    @GameTest(template = "empty16", batch = "scenario_gear_netherite", timeoutTicks = 400)
    public void netheriteIsWornByACaptainWithTheCrownAndRefusedByASergeant(GameTestHelper helper) {
        Settlement s = gearArena(helper, "Crownholm");
        SettlerEntity captain = guard(helper, s, "Hild", 4);
        SettlerEntity sergeant = guard(helper, s, "Arne", 10);
        trainStrengthTo(captain, GuardRank.CAPTAIN.threshold());
        trainStrengthTo(sergeant, GuardRank.SERGEANT.threshold());
        GearGate.grantForTest(s.id, "node:castle_charter");
        GearGate.grantForTest(s.id, "node:master_armoury");
        GearGate.grantForTest(s.id, "node:kingdom_crown");
        captain.bag.setItem(0, new ItemStack(Items.NETHERITE_CHESTPLATE));
        sergeant.bag.setItem(0, new ItemStack(Items.NETHERITE_CHESTPLATE));
        helper.succeedWhen(() -> {
            helper.assertTrue(GuardRank.of(captain) == GuardRank.CAPTAIN
                    && GuardRank.of(sergeant) == GuardRank.SERGEANT,
                "ranks: " + GuardRank.of(captain) + " / " + GuardRank.of(sergeant));
            helper.assertTrue(captain.getItemBySlot(EquipmentSlot.CHEST).is(Items.NETHERITE_CHESTPLATE),
                "the Captain with the Crown and the Master Armoury wears netherite, found "
                    + captain.getItemBySlot(EquipmentSlot.CHEST));
            helper.assertTrue(!sergeant.getItemBySlot(EquipmentSlot.CHEST).is(Items.NETHERITE_CHESTPLATE)
                    && count(sergeant, Items.NETHERITE_CHESTPLATE) == 1,
                "the Sergeant refuses netherite and keeps it in the pack");
            helper.assertTrue(!GearGate.allows(sergeant, new ItemStack(Items.NETHERITE_SWORD))
                    && GearGate.allows(captain, new ItemStack(Items.NETHERITE_SWORD)),
                "the same rule for the netherite sword");
            GearGate.clearTestGrants(s.id);
        });
    }

    @GameTest(template = "empty16", batch = "scenario_gear_demotion", timeoutTicks = 400)
    public void aDemotedGuardKeepsWhatHeWearsButIsRefusedTheNextPiece(GameTestHelper helper) {
        Settlement s = gearArena(helper, "Demoteholm");
        SettlerEntity guard = guard(helper, s, "Knut", 4);
        trainStrengthTo(guard, GuardRank.SERGEANT.threshold());
        GearGate.grantForTest(s.id, "node:castle_charter");
        GearGate.grantForTest(s.id, "node:master_armoury");
        guard.bag.setItem(0, new ItemStack(Items.DIAMOND_CHESTPLATE));
        boolean[] demoted = {false};
        helper.succeedWhen(() -> {
            if (!demoted[0]) {
                helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE),
                    "a Sergeant with the Castle knowledge puts the diamond on");
                int guardRail = 0;
                while (GuardRank.of(guard) != GuardRank.RECRUIT && guardRail++ < 2000) guard.attributes().penalise(5);
                demoted[0] = true;
                guard.bag.setItem(1, new ItemStack(Items.DIAMOND_HELMET));
                helper.fail("demoted: wait a dressing tick");
            }
            helper.assertTrue(GuardRank.of(guard) == GuardRank.RECRUIT, "demoted to Recruit: " + GuardRank.of(guard));
            helper.assertTrue(guard.getItemBySlot(EquipmentSlot.CHEST).is(Items.DIAMOND_CHESTPLATE)
                    && count(guard, Items.DIAMOND_CHESTPLATE) == 1,
                "what is worn is kept (GearGate never strips), exactly once");
            helper.assertTrue(!guard.getItemBySlot(EquipmentSlot.HEAD).is(Items.DIAMOND_HELMET)
                    && count(guard, Items.DIAMOND_HELMET) == 1,
                "a new diamond piece is refused and stays in the pack");
            helper.assertTrue(!GearGate.allows(guard, new ItemStack(Items.DIAMOND_SWORD)), "and diamond is refused again");
            GearGate.clearTestGrants(s.id);
        });
    }

    @GameTest(template = "empty16", batch = "scenario_gear_refusal", timeoutTicks = 60)
    public void theRefusalLineNamesTheSettlerAndWhatIsMissing(GameTestHelper helper) {
        Settlement s = gearArena(helper, "Nayholm");
        SettlerEntity guard = guard(helper, s, "Brenna", 4);
        ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
        helper.assertTrue(!GearGate.allows(guard, sword), "a Recruit may not take a diamond sword");
        Component line = GearGate.refusal(guard, sword);
        helper.assertTrue(line.getContents() instanceof TranslatableContents t
                && t.getKey().equals("hearthstead.gear.refuse") && t.getArgs().length >= 4,
            "a refusal line is produced: " + line);
        TranslatableContents t = (TranslatableContents) line.getContents();
        helper.assertTrue(String.valueOf(t.getArgs()[0] instanceof Component c ? c.getString() : t.getArgs()[0])
                .contains("Brenna"), "it names the settler: " + t.getArgs()[0]);
        helper.succeed();
    }

    // -------------------------------------------------------------- fixture --

    private static Settlement gearArena(GameTestHelper helper, String name) {
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            for (int y = 1; y <= 3; y++) helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
        }
        Settlement s = new Settlement(UUID.randomUUID(), name, helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 12;
        SettlementSavedData.get(helper.getLevel()).settlements.put(s.id, s);
        return s;
    }

    private static SettlerEntity guard(GameTestHelper helper, Settlement s, String name, int x) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), new BlockPos(x, 1, 4));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        settler.assignProfession(Profession.GUARD);
        return settler;
    }

    private static void trainStrengthTo(SettlerEntity settler, int target) {
        int rail = 0;
        while (settler.attribute(Attribute.STRENGTH) < target && rail++ < 20000) {
            settler.attributes().train(Attribute.STRENGTH, 5.0F, 1.0F);
        }
    }

    private static int count(SettlerEntity settler, Item item) {
        int total = 0;
        for (int i = 0; i < settler.bag.getContainerSize(); i++) {
            if (settler.bag.getItem(i).is(item)) total += settler.bag.getItem(i).getCount();
        }
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            if (settler.getItemBySlot(slot).is(item)) total += settler.getItemBySlot(slot).getCount();
        }
        return total;
    }

    private static void put(HearthBlockEntity hearth, Item item, int amount) {
        while (amount > 0) {
            int batch = Math.min(amount, new ItemStack(item).getMaxStackSize());
            boolean placed = false;
            for (int slot = 0; slot < hearth.getInventory().getSlots() && !placed; slot++) {
                if (hearth.getInventory().getStackInSlot(slot).isEmpty()) {
                    hearth.getInventory().setStackInSlot(slot, new ItemStack(item, batch));
                    placed = true;
                }
            }
            if (!placed) throw new IllegalStateException("fixture Hearth inventory full");
            amount -= batch;
        }
    }

    private static int count(HearthBlockEntity hearth, Item item) {
        int n = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            if (hearth.getInventory().getStackInSlot(slot).is(item)) n += hearth.getInventory().getStackInSlot(slot).getCount();
        }
        return n;
    }
}
