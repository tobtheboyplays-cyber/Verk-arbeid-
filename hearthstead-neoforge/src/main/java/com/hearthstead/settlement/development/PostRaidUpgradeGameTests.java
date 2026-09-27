package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
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
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Transaction, effect and persistence contracts for the first post-raid
 * upgrade, the Courier Satchel. Prerequisites are primed through the same
 * package-private state seams as {@link DoctrineProgressionGameTests}.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class PostRaidUpgradeGameTests {

    /** NeoForge creates holder instances for non-static {@link GameTest}s. */
    public PostRaidUpgradeGameTests() {
    }

    @GameTest(template = "empty16", batch = "development", timeoutTicks = 100)
    public void courierSatchelLockedBeforeAftermathThenPaysExactCoinsOnce(
            GameTestHelper helper) {
        Fixture owner = fixture(helper, new BlockPos(3, 1, 3), "Satchel Owner");
        Fixture poor = fixture(helper, new BlockPos(12, 1, 12), "Satchel Poor");
        DevelopmentState state = Development.of(helper.getLevel(), owner.settlement);
        DevelopmentState poorState = Development.of(helper.getLevel(), poor.settlement);
        PostRaidUpgrade satchel = PostRaidUpgrade.COURIER_SATCHEL;
        DevelopmentNode.Cost price = satchel.costs().get(0);
        helper.assertTrue(satchel.costs().size() == 1
                && price.item() == ModItems.GOLD_COIN.get() && price.count() == 4,
            "the Courier Satchel must publish one physical 4 Coins line");

        primeBeforeAftermath(state);
        put(owner.hearth, ModItems.GOLD_COIN.get(), 7);
        int revision = state.revision();
        helper.assertTrue(Development.assessUpgrade(helper.getLevel(),
                owner.settlement, owner.hearth, satchel, null)
                == Development.Result.FIRST_RAID_REQUIRED,
            "the Satchel must stay locked before the First Raid Aftermath");
        Development.Result early = Development.purchaseUpgrade(helper.getLevel(),
            owner.settlement, owner.hearth, satchel, revision, null);
        helper.assertTrue(early == Development.Result.FIRST_RAID_REQUIRED
                && state.revision() == revision
                && coins(owner.hearth) == 7
                && !state.hasUpgrade(satchel),
            "a pre-aftermath purchase must pay nothing and author no revision: " + early);

        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        Development.Result stale = Development.purchaseUpgrade(helper.getLevel(),
            owner.settlement, owner.hearth, satchel, revision - 1, null);
        helper.assertTrue(stale == Development.Result.STALE
                && state.revision() == revision && coins(owner.hearth) == 7,
            "a stale Satchel request must pay nothing");

        Development.Result bought = Development.purchaseUpgrade(helper.getLevel(),
            owner.settlement, owner.hearth, satchel, revision, null);
        helper.assertTrue(bought == Development.Result.APPLIED
                && coins(owner.hearth) == 3
                && state.revision() == revision + 1
                && state.hasUpgrade(satchel)
                && Development.hasUpgrade(helper.getLevel(), owner.settlement, satchel),
            "the Satchel must consume exactly 4 Coins and commit one revision: " + bought);

        Development.Result replay = Development.purchaseUpgrade(helper.getLevel(),
            owner.settlement, owner.hearth, satchel, revision + 1, null);
        helper.assertTrue(replay == Development.Result.ALREADY_UNLOCKED
                && coins(owner.hearth) == 3 && state.revision() == revision + 1,
            "a second Satchel purchase must be refused without payment: " + replay);

        primeBeforeAftermath(poorState);
        poorState.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        put(poor.hearth, ModItems.GOLD_COIN.get(), 3);
        int poorRevision = poorState.revision();
        Development.Result unfunded = Development.purchaseUpgrade(helper.getLevel(),
            poor.settlement, poor.hearth, satchel, poorRevision, null);
        helper.assertTrue(unfunded == Development.Result.MATERIALS
                && coins(poor.hearth) == 3 && poorState.revision() == poorRevision
                && !Development.hasUpgrade(helper.getLevel(), poor.settlement, satchel),
            "3 of 4 Coins must not buy the Satchel, and ownership stays per settlement");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "development", timeoutTicks = 100)
    public void courierSatchelRaisesCourierBudgetAndSurvivesReload(
            GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(3, 1, 3), "Satchel Route");
        DevelopmentState state = Development.of(helper.getLevel(), fixture.settlement);
        primeBeforeAftermath(state);
        state.unlock(DevelopmentNode.FIRST_RAID_AFTERMATH);
        PostRaidUpgrade satchel = PostRaidUpgrade.COURIER_SATCHEL;

        SettlerEntity courier = settler(helper, fixture, new BlockPos(6, 1, 6),
            "Satchel Courier", Profession.COURIER);
        SettlerEntity farmer = settler(helper, fixture, new BlockPos(8, 1, 6),
            "Satchel Farmer", Profession.FARMER);
        SettlerEntity reduced = settler(helper, fixture, new BlockPos(10, 1, 6),
            "Reduced Courier", Profession.COURIER);
        reduced.setCarryCapacity(3);
        int base = SettlerEntity.BASE_CARRY_CAPACITY;
        helper.assertTrue(CourierSatchel.apply(helper.getLevel(), fixture.settlement,
                courier) == base,
            "without the Satchel a Courier keeps the base trip budget");

        put(fixture.hearth, ModItems.GOLD_COIN.get(), 4);
        helper.assertTrue(Development.purchaseUpgrade(helper.getLevel(),
                fixture.settlement, fixture.hearth, satchel, state.revision(), null)
                == Development.Result.APPLIED,
            "fixture Satchel purchase must apply");
        int raised = CourierSatchel.apply(helper.getLevel(), fixture.settlement, courier);
        helper.assertTrue(raised == base + PostRaidUpgrade.COURIER_SATCHEL_BONUS
                && raised == 12 && courier.getCarryCapacity() == 12,
            "an owned Satchel must raise the Courier trip budget from 8 to 12, got " + raised);
        helper.assertTrue(CourierSatchel.apply(helper.getLevel(), fixture.settlement,
                courier) == 12,
            "reapplying the Satchel must not stack");
        helper.assertTrue(CourierSatchel.apply(helper.getLevel(), fixture.settlement,
                farmer) == base,
            "the Satchel only changes Couriers");
        helper.assertTrue(CourierSatchel.apply(helper.getLevel(), fixture.settlement,
                reduced) == 3,
            "deliberately reduced budgets must be preserved");

        CompoundTag entityTag = new CompoundTag();
        courier.addAdditionalSaveData(entityTag);
        SettlerEntity reloadedCourier = ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(reloadedCourier != null, "Settler type must recreate a saved Courier");
        reloadedCourier.readAdditionalSaveData(entityTag);
        helper.assertTrue(reloadedCourier.getCarryCapacity() == 12,
            "the raised Courier budget must survive entity save/reload");

        CompoundTag saved = state.writeNbt();
        DevelopmentState reloaded = DevelopmentState.readNbt(saved);
        helper.assertTrue(!reloaded.quarantined() && reloaded.hasUpgrade(satchel)
                && reloaded.revision() == state.revision(),
            "the owned Satchel must survive a Development NBT round-trip");
        CompoundTag savedRoot = Development.get(helper.getLevel()).save(
            new CompoundTag(), helper.getLevel().registryAccess());
        DevelopmentState disk = Development.load(savedRoot,
            helper.getLevel().registryAccess()).existingState(fixture.settlement.id);
        helper.assertTrue(disk != null && !disk.quarantined() && disk.hasUpgrade(satchel),
            "the SavedData root must reload the owned Satchel");

        CompoundTag oldSave = saved.copy();
        oldSave.remove("PostRaidUpgrades");
        DevelopmentState legacy = DevelopmentState.readNbt(oldSave);
        helper.assertTrue(!legacy.quarantined() && !legacy.hasUpgrade(satchel)
                && legacy.unlocked(DevelopmentNode.FIRST_RAID_AFTERMATH),
            "a save written before upgrades existed must load safely with none owned");
        CompoundTag unknown = saved.copy();
        ListTag bogus = new ListTag();
        bogus.add(StringTag.valueOf("bogus_upgrade"));
        unknown.put("PostRaidUpgrades", bogus);
        helper.assertTrue(DevelopmentState.readNbt(unknown).quarantined(),
            "an unknown upgrade id must fail closed");
        CompoundTag orphan = saved.copy();
        removeUnlocked(orphan, DevelopmentNode.FIRST_RAID_AFTERMATH.id());
        helper.assertTrue(DevelopmentState.readNbt(orphan).quarantined(),
            "a Satchel without its aftermath prerequisite must fail closed");
        helper.succeed();
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

    private static void put(HearthBlockEntity hearth, Item item, int amount) {
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack existing = hearth.getInventory().getStackInSlot(slot);
            if (existing.isEmpty()) {
                hearth.getInventory().setStackInSlot(slot,
                    new ItemStack(item, amount));
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

    private static void removeUnlocked(CompoundTag tag, String id) {
        ListTag unlocked = tag.getList("Unlocked", Tag.TAG_STRING);
        for (int index = unlocked.size() - 1; index >= 0; index--) {
            if (id.equals(unlocked.getString(index))) {
                unlocked.remove(index);
            }
        }
    }

    private record Fixture(Settlement settlement, HearthBlockEntity hearth) {
    }
}
