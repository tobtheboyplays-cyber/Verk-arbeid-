package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Tech-tree rework contracts: hub ordering and prerequisites, Coins plus real
 * goods paid exactly once with all-or-nothing rollback, a sample of bonus
 * effects on and off, NBT round trip and old-save defaults. Own batch; never
 * touches world time.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class TechTreeUpgradeGameTests {

    public TechTreeUpgradeGameTests() {
    }

    @GameTest(template = "empty16", batch = "techtree", timeoutTicks = 60)
    public void catalogueIsOrderedPricedAndStable(GameTestHelper helper) {
        Item coin = ModItems.GOLD_COIN.get();
        Set<Integer> wires = new HashSet<>();
        Set<String> ids = new HashSet<>();
        for (PostRaidUpgrade upgrade : PostRaidUpgrade.values()) {
            helper.assertTrue(wires.add(upgrade.wireId()) && ids.add(upgrade.id()),
                "upgrade wire ids and ids must be unique: " + upgrade.id());
            List<DevelopmentNode.Cost> costs = upgrade.costs();
            helper.assertTrue(costs.getFirst().item() == coin
                    && costs.getFirst().count() == upgrade.coinCost()
                    && costs.size() == 1 + upgrade.materialCosts().size(),
                upgrade.id() + " must publish Coins first, then its goods");
            if (upgrade.wireId() >= 5) {
                helper.assertTrue(!upgrade.materialCosts().isEmpty(),
                    upgrade.id() + " must also cost real goods");
            }
            PostRaidUpgrade chain = upgrade.requiresUpgrade();
            helper.assertTrue(chain == null || chain.wireId() != upgrade.wireId(),
                upgrade.id() + " cannot require itself");
        }
        // Original wire ids are save identities and must never move.
        helper.assertTrue(PostRaidUpgrade.byWireId(0) == PostRaidUpgrade.COURIER_SATCHEL
                && PostRaidUpgrade.byWireId(4) == PostRaidUpgrade.ARCHER_LONGBOW_DRILL
                && PostRaidUpgrade.COURIER_SATCHEL.costs().size() == 1,
            "original upgrade ids, wires and Coin-only prices are unchanged");
        helper.assertTrue(PostRaidUpgrade.values().length >= 12,
            "the tree offers at least twelve upgrades");
        for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
            if (node.materialCosts().isEmpty()) {
                helper.assertTrue(node.costs().isEmpty(),
                    node.id() + " declares no goods and must stay free");
                continue;
            }
            helper.assertTrue(node.costs().getFirst().item() == coin
                    && node.costs().getFirst().count() == node.coinCost()
                    && node.costs().size() == 1 + node.materialCosts().size(),
                node.id() + " must charge Coins plus its declared goods");
        }
        // Inner rings are cheaper than outer rings.
        helper.assertTrue(DevelopmentNode.TIMBER_RIGHTS.coinCost()
                < DevelopmentNode.HOSPITALITY.coinCost()
                && DevelopmentNode.HOSPITALITY.coinCost()
                    < DevelopmentNode.SHIELD_DOCTRINE.coinCost(),
            "each step outward must cost more Coins");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree", timeoutTicks = 60)
    public void prerequisitesAndMilestonesGateUpgradesInOrder(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(3, 1, 3), "Order");
        DevelopmentState state = Development.of(helper.getLevel(), fixture.settlement);
        PostRaidUpgrade axes = PostRaidUpgrade.SHARPENED_AXES;
        fund(fixture.hearth, axes);
        helper.assertTrue(assess(helper, fixture, axes) == Development.Result.PREREQUISITE,
            "Sharpened Axes must wait for the Lumber Camp");
        founding(state);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        helper.assertTrue(assess(helper, fixture, axes) == Development.Result.QUEST_REQUIRED,
            "Sharpened Axes must wait for 16 logs stored");
        state.noteLumberLogs(axes.gateTarget());
        helper.assertTrue(assess(helper, fixture, axes) == Development.Result.APPLIED,
            "with the milestone met and goods stored the axes are available");

        PostRaidUpgrade beds = PostRaidUpgrade.STURDY_BEDS;
        state.unlock(DevelopmentNode.HOME);
        state.unlock(DevelopmentNode.HOSPITALITY);
        fund(fixture.hearth, beds);
        helper.assertTrue(assess(helper, fixture, beds) == Development.Result.PREREQUISITE,
            "Sturdy Beds must chain from Warm Hearth");

        PostRaidUpgrade quilts = PostRaidUpgrade.FEATHER_QUILTS;
        helper.assertTrue(assess(helper, fixture, quilts)
                == Development.Result.FIRST_RAID_REQUIRED,
            "outer-ring bonuses stay locked until the first raid milestone");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree", timeoutTicks = 60)
    public void coinsAndGoodsArePaidExactlyOnceOrNotAtAll(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(3, 1, 3), "Payment");
        DevelopmentState state = Development.of(helper.getLevel(), fixture.settlement);
        founding(state);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        state.unlock(DevelopmentNode.FIRST_WATCH);
        state.noteGuardEquipmentRequest(UUID.randomUUID());
        PostRaidUpgrade drill = PostRaidUpgrade.GUARD_DRILL;

        // 3 Coins + 8 Logs (mixed species) + only 1 of 2 Leather.
        put(fixture.hearth, ModItems.GOLD_COIN.get(), 5);
        put(fixture.hearth, Items.SPRUCE_LOG, 5);
        put(fixture.hearth, Items.BIRCH_LOG, 4);
        put(fixture.hearth, Items.LEATHER, 1);
        int revision = state.revision();
        Development.Result short1 = Development.purchaseUpgrade(helper.getLevel(),
            fixture.settlement, fixture.hearth, drill, revision, null);
        helper.assertTrue(short1 == Development.Result.MATERIALS
                && count(fixture.hearth, ModItems.GOLD_COIN.get()) == 5
                && count(fixture.hearth, Items.SPRUCE_LOG) == 5
                && count(fixture.hearth, Items.BIRCH_LOG) == 4
                && count(fixture.hearth, Items.LEATHER) == 1
                && state.revision() == revision && !state.hasUpgrade(drill),
            "a short goods line must refuse and take nothing, got " + short1);

        put(fixture.hearth, Items.LEATHER, 2);
        Development.Result paid = Development.purchaseUpgrade(helper.getLevel(),
            fixture.settlement, fixture.hearth, drill, revision, null);
        int logsLeft = count(fixture.hearth, Items.SPRUCE_LOG)
            + count(fixture.hearth, Items.BIRCH_LOG);
        helper.assertTrue(paid == Development.Result.APPLIED
                && state.hasUpgrade(drill)
                && count(fixture.hearth, ModItems.GOLD_COIN.get()) == 2
                && logsLeft == 1
                && count(fixture.hearth, Items.LEATHER) == 1
                && state.revision() == revision + 1,
            "the drill must charge exactly 3 Coins, 8 Logs of any kind and 2 Leather once");

        Development.Result replay = Development.purchaseUpgrade(helper.getLevel(),
            fixture.settlement, fixture.hearth, drill, revision, null);
        Development.Result again = Development.purchaseUpgrade(helper.getLevel(),
            fixture.settlement, fixture.hearth, drill, state.revision(), null);
        helper.assertTrue(replay == Development.Result.STALE
                && again == Development.Result.ALREADY_UNLOCKED
                && count(fixture.hearth, ModItems.GOLD_COIN.get()) == 2
                && count(fixture.hearth, Items.LEATHER) == 1,
            "a replay or second purchase must charge nothing");

        // Rollback: a container that refuses one extraction mid-payment.
        ItemStackHandler refusing = new ItemStackHandler(3) {
            @Override
            public ItemStack extractItem(int slot, int amount, boolean simulate) {
                return slot == 2 ? ItemStack.EMPTY : super.extractItem(slot, amount, simulate);
            }
        };
        refusing.setStackInSlot(0, new ItemStack(ModItems.GOLD_COIN.get(), 3));
        refusing.setStackInSlot(1, new ItemStack(Items.OAK_LOG, 8));
        refusing.setStackInSlot(2, new ItemStack(Items.LEATHER, 2));
        boolean threw = false;
        try {
            Development.pay(refusing, drill.costs());
        } catch (IllegalStateException expected) {
            threw = true;
        }
        helper.assertTrue(threw
                && refusing.getStackInSlot(0).getCount() == 3
                && refusing.getStackInSlot(1).getCount() == 8
                && refusing.getStackInSlot(2).getCount() == 2,
            "a refused extraction must roll every already-taken line back");

        // Nodes now charge their declared goods too.
        DevelopmentNode timber = DevelopmentNode.TIMBER_RIGHTS;
        ItemStackHandler stores = new ItemStackHandler(4);
        stores.setStackInSlot(0, new ItemStack(ModItems.GOLD_COIN.get(), timber.coinCost()));
        stores.setStackInSlot(1, new ItemStack(Items.DARK_OAK_LOG, 8));
        stores.setStackInSlot(2, new ItemStack(Items.COBBLESTONE, 7));
        helper.assertTrue(!Development.canPayForTest(stores, timber.costs()),
            "Coins alone no longer buy a node; its goods are required");
        stores.setStackInSlot(2, new ItemStack(Items.COBBLESTONE, 8));
        Development.pay(stores, timber.costs());
        helper.assertTrue(stores.getStackInSlot(0).isEmpty()
                && stores.getStackInSlot(1).isEmpty()
                && stores.getStackInSlot(2).isEmpty(),
            "a node price is taken in full: Coins, any Logs and Cobblestone");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree", timeoutTicks = 60)
    public void bonusEffectsApplyOnlyWhenOwned(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(3, 1, 3), "Effects");
        DevelopmentState state = Development.of(helper.getLevel(), fixture.settlement);
        var level = helper.getLevel();
        Settlement s = fixture.settlement;
        helper.assertTrue(DevelopmentBonuses.fellingTicks(level, s, 100) == 100
                && DevelopmentBonuses.guardCombatXp(level, s, 20) == 20
                && DevelopmentBonuses.courierStrapBonus(level, s) == 0
                && CourierSatchel.targetCapacity(level, s) == SettlerEntity.BASE_CARRY_CAPACITY,
            "without upgrades every value is the old base value");
        founding(state);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.unlock(DevelopmentNode.STORES_AND_ROADS);
        state.unlock(DevelopmentNode.FIRST_WATCH);
        state.unlockUpgrade(PostRaidUpgrade.SHARPENED_AXES);
        state.unlockUpgrade(PostRaidUpgrade.GUARD_DRILL);
        state.unlockUpgrade(PostRaidUpgrade.STOUT_STRAPS);
        helper.assertTrue(DevelopmentBonuses.fellingTicks(level, s, 100) == 90
                && DevelopmentBonuses.fellingTicks(level, s, 1) == 1,
            "Sharpened Axes fell 10% faster, never below one tick");
        helper.assertTrue(DevelopmentBonuses.guardCombatXp(level, s, 20) == 23,
            "Guard Drill adds 15% combat XP");
        helper.assertTrue(CourierSatchel.targetCapacity(level, s)
                == SettlerEntity.BASE_CARRY_CAPACITY + PostRaidUpgrade.STOUT_STRAPS_BONUS,
            "Stout Straps add two items per Courier trip");
        state.unlock(DevelopmentNode.SHIELD_DOCTRINE);
        helper.assertTrue(DevelopmentBonuses.guardCombatXp(level, s, 20) == 28,
            "Shield Doctrine stacks +25% on the drill (+40% total)");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "techtree", timeoutTicks = 60)
    public void newUpgradesRoundTripAndOldSavesLoadWithDefaults(GameTestHelper helper) {
        DevelopmentState state = new DevelopmentState();
        founding(state);
        state.unlock(DevelopmentNode.TIMBER_RIGHTS);
        state.unlock(DevelopmentNode.HOME);
        state.unlockUpgrade(PostRaidUpgrade.SHARPENED_AXES);
        state.unlockUpgrade(PostRaidUpgrade.WARM_HEARTH);
        CompoundTag saved = state.writeNbt();
        DevelopmentState disk = DevelopmentState.readNbt(saved);
        helper.assertTrue(!disk.quarantined()
                && disk.hasUpgrade(PostRaidUpgrade.SHARPENED_AXES)
                && disk.hasUpgrade(PostRaidUpgrade.WARM_HEARTH)
                && !disk.hasUpgrade(PostRaidUpgrade.GUARD_DRILL),
            "new upgrades must survive an NBT round trip by id");

        CompoundTag oldSave = saved.copy();
        oldSave.remove("PostRaidUpgrades");
        DevelopmentState legacy = DevelopmentState.readNbt(oldSave);
        helper.assertTrue(!legacy.quarantined() && legacy.upgradeIds().isEmpty()
                && legacy.unlocked(DevelopmentNode.TIMBER_RIGHTS),
            "a save from before the bonuses loads with none owned");

        CompoundTag orphan = saved.copy();
        ListTag unlocked = orphan.getList("Unlocked", Tag.TAG_STRING);
        for (int i = unlocked.size() - 1; i >= 0; i--) {
            if (DevelopmentNode.TIMBER_RIGHTS.id().equals(unlocked.getString(i))) {
                unlocked.remove(i);
            }
        }
        helper.assertTrue(DevelopmentState.readNbt(orphan).quarantined(),
            "Sharpened Axes without the Lumber Camp must fail closed");

        CompoundTag chainOrphan = saved.copy();
        ListTag upgrades = new ListTag();
        upgrades.add(StringTag.valueOf(PostRaidUpgrade.STURDY_BEDS.id()));
        chainOrphan.put("PostRaidUpgrades", upgrades);
        helper.assertTrue(DevelopmentState.readNbt(chainOrphan).quarantined(),
            "Sturdy Beds without Hospitality and Warm Hearth must fail closed");
        helper.succeed();
    }

    private static void founding(DevelopmentState state) {
        state.unlock(DevelopmentNode.SETTLEMENT_CHARTER);
        state.unlock(DevelopmentNode.SHELTER);
    }

    private static Development.Result assess(GameTestHelper helper, Fixture fixture,
                                             PostRaidUpgrade upgrade) {
        return Development.assessUpgrade(helper.getLevel(), fixture.settlement,
            fixture.hearth, upgrade, null);
    }

    private static void fund(HearthBlockEntity hearth, PostRaidUpgrade upgrade) {
        for (DevelopmentNode.Cost cost : upgrade.costs()) {
            put(hearth, cost.item(), cost.count());
        }
    }

    private static Fixture fixture(GameTestHelper helper, BlockPos hearthRelative,
                                   String name) {
        helper.setBlock(hearthRelative, ModBlocks.HEARTH.get());
        BlockPos absolute = helper.absolutePos(hearthRelative);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel()
            .getBlockEntity(absolute);
        Settlement settlement = new Settlement(UUID.randomUUID(), name, absolute);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        Development.revisionOf(helper.getLevel(), settlement);
        return new Fixture(settlement, hearth);
    }

    private static void put(HearthBlockEntity hearth, Item item, int amount) {
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack existing = hearth.getInventory().getStackInSlot(slot);
            if (existing.isEmpty()) {
                hearth.getInventory().setStackInSlot(slot, new ItemStack(item, amount));
                return;
            }
            if (existing.is(item)
                && existing.getCount() + amount <= existing.getMaxStackSize()) {
                existing.grow(amount);
                hearth.getInventory().setStackInSlot(slot, existing);
                return;
            }
        }
        throw new IllegalStateException("fixture Hearth inventory full");
    }

    private static int count(HearthBlockEntity hearth, Item item) {
        int total = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth) {
    }
}
