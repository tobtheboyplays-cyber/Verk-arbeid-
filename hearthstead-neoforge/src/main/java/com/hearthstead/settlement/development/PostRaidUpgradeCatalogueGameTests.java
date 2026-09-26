package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Gate, exact-once payment, effect and NBT contracts for the post-raid
 * upgrades added after the Courier Satchel: Hand Cart, Worker Packs, Iron
 * Arms Drill and Longbow Drill. Own batch; never touches world time.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class PostRaidUpgradeCatalogueGameTests {

    private static final String BATCH = "development_upgrade_catalogue";

    /** NeoForge creates holder instances for non-static {@link GameTest}s. */
    public PostRaidUpgradeCatalogueGameTests() {
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 100)
    public void handCartRequiresSatchelPaysOnceAndRaisesCourierTo28(
            GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(3, 1, 3), "Cart Town");
        DevelopmentState state = Development.of(helper.getLevel(), fixture.settlement);
        PostRaidUpgrade cart = PostRaidUpgrade.HAND_CART;
        helper.assertTrue(cart.requiresUpgrade() == PostRaidUpgrade.COURIER_SATCHEL
                && cart.coinCost() == 8,
            "the Hand Cart must cost 8 Coins and require the Courier Satchel");
        assertAftermathGate(helper, fixture, state, cart);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        put(fixture.hearth, 20);

        int revision = state.revision();
        Development.Result early = Development.purchaseUpgrade(helper.getLevel(),
            fixture.settlement, fixture.hearth, cart, revision, null);
        helper.assertTrue(early == Development.Result.PREREQUISITE
                && coins(fixture.hearth) == 20 && state.revision() == revision
                && !state.hasUpgrade(cart),
            "a Hand Cart without the Satchel must pay nothing: " + early);

        SettlerEntity courier = settler(helper, fixture, new BlockPos(6, 1, 6),
            "Cart Courier", Profession.COURIER);
        helper.assertTrue(Development.purchaseUpgrade(helper.getLevel(),
                fixture.settlement, fixture.hearth, PostRaidUpgrade.COURIER_SATCHEL,
                revision, null) == Development.Result.APPLIED
                && coins(fixture.hearth) == 16,
            "fixture Satchel purchase must apply for 4 Coins");
        helper.assertTrue(CourierSatchel.apply(helper.getLevel(), fixture.settlement,
                courier) == 12, "the Satchel alone must give 12");
        // The real cart also costs physical goods (planks, logs, iron).
        for (DevelopmentNode.Cost material : cart.materialCosts()) {
            putItem(fixture.hearth, material.item(), material.count());
        }

        assertPaysExactlyOnce(helper, fixture, state, cart, 16);
        for (DevelopmentNode.Cost material : cart.materialCosts()) {
            helper.assertTrue(countItem(fixture.hearth, material.item()) == 0,
                "the Hand Cart must consume exactly its " + material.count() + " "
                    + material.item());
        }
        int raised = CourierSatchel.apply(helper.getLevel(), fixture.settlement, courier);
        helper.assertTrue(raised == 28 && courier.getCarryCapacity() == 28
                && CourierSatchel.handCartCapacity() == 28
                && courier.hasHandCart() && courier.sackTier() == 1,
            "the Hand Cart must raise the Courier trip budget 12 -> 28, got " + raised);
        helper.assertTrue(CourierSatchel.apply(helper.getLevel(), fixture.settlement,
                courier) == 28, "reapplying the Hand Cart must not stack");

        assertRoundTrip(helper, state, cart);
        CompoundTag orphan = state.writeNbt();
        ListTag onlyCart = new ListTag();
        onlyCart.add(StringTag.valueOf(cart.id()));
        orphan.put("PostRaidUpgrades", onlyCart);
        helper.assertTrue(DevelopmentState.readNbt(orphan).quarantined(),
            "a saved Hand Cart without its Satchel must fail closed");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 100)
    public void workerPacksPayOnceAndRaiseGatherersOnly(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(3, 1, 3), "Pack Town");
        DevelopmentState state = Development.of(helper.getLevel(), fixture.settlement);
        PostRaidUpgrade packs = PostRaidUpgrade.WORKER_PACKS;
        assertAftermathGate(helper, fixture, state, packs);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);

        SettlerEntity farmer = settler(helper, fixture, new BlockPos(6, 1, 6),
            "Pack Farmer", Profession.FARMER);
        SettlerEntity lumberer = settler(helper, fixture, new BlockPos(8, 1, 6),
            "Pack Lumberer", Profession.LUMBERER);
        SettlerEntity courier = settler(helper, fixture, new BlockPos(10, 1, 6),
            "Pack Courier", Profession.COURIER);
        int base = SettlerEntity.BASE_CARRY_CAPACITY;
        helper.assertTrue(WorkerPacks.apply(helper.getLevel(), fixture.settlement,
                farmer) == base
                && WorkerPacks.farmerBagTrigger(farmer, 8) == 8,
            "without the packs a Farmer keeps 8 items and the 8-item trigger");

        put(fixture.hearth, 6);
        assertPaysExactlyOnce(helper, fixture, state, packs, 6);
        helper.assertTrue(WorkerPacks.apply(helper.getLevel(), fixture.settlement,
                farmer) == 12 && WorkerPacks.farmerBagTrigger(farmer, 8) == 12,
            "Worker Packs must raise a Farmer 8 -> 12 and its storage trigger with it");
        int lumberTarget = WorkerPacks.packCapacity(WorkerPacks.naturalCapacity(lumberer));
        helper.assertTrue(lumberTarget >= 12 && WorkerPacks.apply(helper.getLevel(),
                fixture.settlement, lumberer) == lumberTarget,
            "Worker Packs must raise a Lumberer by half its natural budget");
        helper.assertTrue(WorkerPacks.apply(helper.getLevel(), fixture.settlement,
                farmer) == 12, "reapplying the packs must not stack");
        helper.assertTrue(WorkerPacks.apply(helper.getLevel(), fixture.settlement,
                courier) == base, "Worker Packs never change a Courier");
        assertRoundTrip(helper, state, packs);
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 100)
    public void ironArmsDrillShiftsVeteranKitOnlyAfterPurchase(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(3, 1, 3), "Arms Town");
        DevelopmentState state = Development.of(helper.getLevel(), fixture.settlement);
        PostRaidUpgrade arms = PostRaidUpgrade.GUARD_ARMS_IRON;
        assertAftermathGate(helper, fixture, state, arms);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        for (GuardRank rank : GuardRank.values()) {
            helper.assertTrue(kit(helper, fixture, rank) == rank,
                "without the drill every rank keeps its own kit: " + rank);
        }

        put(fixture.hearth, 8);
        assertPaysExactlyOnce(helper, fixture, state, arms, 8);
        helper.assertTrue(kit(helper, fixture, GuardRank.RECRUIT) == GuardRank.RECRUIT
                && kit(helper, fixture, GuardRank.SPEARMAN) == GuardRank.SPEARMAN
                && kit(helper, fixture, GuardRank.VETERAN) == GuardRank.SERGEANT
                && kit(helper, fixture, GuardRank.SERGEANT) == GuardRank.CAPTAIN
                && kit(helper, fixture, GuardRank.CAPTAIN) == GuardRank.CAPTAIN,
            "the drill must dress Veterans and Sergeants one armour tier earlier only");

        // Ownership reads are side-effect free: an unrelated settlement with
        // no stored state must not have one created by an effect lookup.
        Settlement stranger = new Settlement(UUID.randomUUID(), "Stranger",
            helper.absolutePos(new BlockPos(12, 1, 12)));
        helper.assertTrue(!GuardArms.owned(helper.getLevel(), stranger)
                && Development.get(helper.getLevel()).existingState(stranger.id) == null,
            "effect lookups must never create Development state");
        assertRoundTrip(helper, state, arms);
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = BATCH, timeoutTicks = 100)
    public void longbowDrillExtendsRangeAndDrawOnlyAfterPurchase(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(3, 1, 3), "Bow Town");
        DevelopmentState state = Development.of(helper.getLevel(), fixture.settlement);
        PostRaidUpgrade drill = PostRaidUpgrade.ARCHER_LONGBOW_DRILL;
        assertAftermathGate(helper, fixture, state, drill);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        helper.assertTrue(ArcherDrill.normalShotRange(helper.getLevel(),
                fixture.settlement, 18.0D) == 18.0D
                && ArcherDrill.ordinaryDrawTicks(helper.getLevel(),
                    fixture.settlement, 20) == 20,
            "without the drill Archers keep 18 blocks and a 20-tick draw");

        put(fixture.hearth, 6);
        assertPaysExactlyOnce(helper, fixture, state, drill, 6);
        helper.assertTrue(ArcherDrill.normalShotRange(helper.getLevel(),
                fixture.settlement, 18.0D) == 20.0D
                && ArcherDrill.ordinaryDrawTicks(helper.getLevel(),
                    fixture.settlement, 20) == 16,
            "the Longbow Drill must give 20 blocks and a 16-tick draw");
        assertRoundTrip(helper, state, drill);
        helper.succeed();
    }

    // ------------------------------------------------------------ helpers ---

    private static GuardRank kit(GameTestHelper helper, Fixture fixture, GuardRank rank) {
        return GuardArms.kitRank(helper.getLevel(), fixture.settlement, rank);
    }

    /** Locked before the aftermath: pays nothing and authors no revision. */
    private static void assertAftermathGate(GameTestHelper helper, Fixture fixture,
                                            DevelopmentState state,
                                            PostRaidUpgrade upgrade) {
        primeBeforeAftermath(state);
        put(fixture.hearth, 20);
        int before = coins(fixture.hearth);
        int revision = state.revision();
        Development.Result early = Development.purchaseUpgrade(helper.getLevel(),
            fixture.settlement, fixture.hearth, upgrade, revision, null);
        helper.assertTrue(early == Development.Result.FIRST_RAID_REQUIRED
                && coins(fixture.hearth) == before && state.revision() == revision
                && !state.hasUpgrade(upgrade),
            upgrade.id() + " must stay locked before the aftermath: " + early);
        take(fixture.hearth, before);
    }

    /** Stale refused, exact cost paid once, replay refused without payment. */
    private static void assertPaysExactlyOnce(GameTestHelper helper, Fixture fixture,
                                              DevelopmentState state,
                                              PostRaidUpgrade upgrade, int startCoins) {
        helper.assertTrue(coins(fixture.hearth) == startCoins,
            "fixture coin count drifted for " + upgrade.id());
        int revision = state.revision();
        Development.Result stale = Development.purchaseUpgrade(helper.getLevel(),
            fixture.settlement, fixture.hearth, upgrade, revision - 1, null);
        helper.assertTrue(stale == Development.Result.STALE
                && coins(fixture.hearth) == startCoins,
            "a stale " + upgrade.id() + " request must pay nothing");
        Development.Result bought = Development.purchaseUpgrade(helper.getLevel(),
            fixture.settlement, fixture.hearth, upgrade, revision, null);
        int after = startCoins - upgrade.coinCost();
        helper.assertTrue(bought == Development.Result.APPLIED
                && coins(fixture.hearth) == after
                && state.revision() == revision + 1
                && Development.hasUpgrade(helper.getLevel(), fixture.settlement, upgrade),
            upgrade.id() + " must consume exactly " + upgrade.coinCost()
                + " Coins once: " + bought);
        Development.Result replay = Development.purchaseUpgrade(helper.getLevel(),
            fixture.settlement, fixture.hearth, upgrade, revision + 1, null);
        helper.assertTrue(replay == Development.Result.ALREADY_UNLOCKED
                && coins(fixture.hearth) == after && state.revision() == revision + 1,
            "a second " + upgrade.id() + " purchase must be refused: " + replay);
    }

    private static void assertRoundTrip(GameTestHelper helper, DevelopmentState state,
                                        PostRaidUpgrade upgrade) {
        CompoundTag saved = state.writeNbt();
        DevelopmentState reloaded = DevelopmentState.readNbt(saved);
        helper.assertTrue(!reloaded.quarantined() && reloaded.hasUpgrade(upgrade)
                && reloaded.revision() == state.revision(),
            upgrade.id() + " must survive a Development NBT round-trip");
        CompoundTag legacy = saved.copy();
        legacy.remove("PostRaidUpgrades");
        DevelopmentState old = DevelopmentState.readNbt(legacy);
        helper.assertTrue(!old.quarantined() && !old.hasUpgrade(upgrade),
            "a save without upgrades must load with " + upgrade.id() + " not owned");
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

    private static SettlerEntity settler(GameTestHelper helper, Fixture fixture,
                                         BlockPos relative, String name,
                                         Profession profession) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), relative);
        settler.setNoAi(true);
        settler.bindTo(fixture.settlement.id, fixture.settlement.center);
        settler.setProfessionProjection(profession);
        fixture.settlement.putRecord(settler.getUUID(), name, profession);
        return settler;
    }

    private static void primeBeforeAftermath(DevelopmentState state) {
        DevelopmentNode[] trunk = {
            DevelopmentNode.TIMBER_RIGHTS,
            DevelopmentNode.STORES_AND_ROADS,
            DevelopmentNode.CULTIVATED_GROUND,
            DevelopmentNode.SHORE_PROVISIONS,
            DevelopmentNode.HOME,
            DevelopmentNode.HOSPITALITY,
            DevelopmentNode.FIRST_WATCH,
            DevelopmentNode.ARM_THE_WATCH
        };
        for (DevelopmentNode node : trunk) {
            state.unlock(node);
        }
        DevelopmentQuests.ensureEligibleBaselines(state);
    }

    private static void put(HearthBlockEntity hearth, int amount) {
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack existing = hearth.getInventory().getStackInSlot(slot);
            if (existing.isEmpty()) {
                hearth.getInventory().setStackInSlot(slot,
                    new ItemStack(ModItems.GOLD_COIN.get(), amount));
                return;
            }
            if (existing.is(ModItems.GOLD_COIN.get())
                && existing.getCount() + amount <= existing.getMaxStackSize()) {
                existing.grow(amount);
                hearth.getInventory().setStackInSlot(slot, existing);
                return;
            }
        }
        throw new IllegalStateException("fixture Hearth inventory full");
    }

    private static void take(HearthBlockEntity hearth, int amount) {
        int left = amount;
        for (int slot = 0; slot < hearth.getInventory().getSlots() && left > 0; slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(ModItems.GOLD_COIN.get())) {
                int removed = Math.min(left, stack.getCount());
                ItemStack remaining = stack.copy();
                remaining.shrink(removed);
                hearth.getInventory().setStackInSlot(slot, remaining);
                left -= removed;
            }
        }
    }

    private static void putItem(HearthBlockEntity hearth, net.minecraft.world.item.Item item,
                                int amount) {
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            if (hearth.getInventory().getStackInSlot(slot).isEmpty()) {
                hearth.getInventory().setStackInSlot(slot, new ItemStack(item, amount));
                return;
            }
        }
        throw new IllegalStateException("fixture Hearth inventory full");
    }

    private static int countItem(HearthBlockEntity hearth, net.minecraft.world.item.Item item) {
        int total = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int coins(HearthBlockEntity hearth) {
        int total = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(ModItems.GOLD_COIN.get())) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth) {
    }
}
