package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Transaction and persistence contracts for paid, cumulative doctrines. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class DoctrineProgressionGameTests {

    /** NeoForge creates holder instances for non-static {@link GameTest}s. */
    public DoctrineProgressionGameTests() {
    }

    /**
     * Transaction-focused fixture: prerequisites and quest counters are
     * primed through package-private state seams. The real quest fact suites
     * separately prove how those counters are earned in gameplay.
     */
    @GameTest(template = "empty16", batch = "development", timeoutTicks = 200)
    public void paidDoctrinesAccumulateWithoutChangingPrimaryAndSurviveReload(
            GameTestHelper helper) {
        Fixture owner = fixture(helper, new BlockPos(3, 1, 3), "Doctrine Owner");
        Fixture other = fixture(helper, new BlockPos(12, 1, 12), "Doctrine Other");
        DevelopmentState state = Development.of(helper.getLevel(), owner.settlement);
        DevelopmentState isolated = Development.of(helper.getLevel(), other.settlement);
        primeAftermath(state);
        primeAftermath(isolated);
        primeDoctrineObjectives(state);

        int shieldRevision = payFromWalletAndAssert(helper, owner,
            DevelopmentNode.SHIELD_DOCTRINE);
        long firstDoctrineTime = state.doctrineChangedAt();
        helper.assertTrue(state.activeDoctrine() == DevelopmentNode.SHIELD_DOCTRINE,
            "the first paid doctrine must become the durable primary identity");

        CompoundTag primaryOnly = state.writeNbt();
        CompoundTag legacySingle = primaryOnly.copy();
        legacySingle.putInt("Schema", 5);
        DevelopmentState migratedSingle = DevelopmentState.readNbt(legacySingle);
        helper.assertTrue(!migratedSingle.quarantined()
                && migratedSingle.activeDoctrine() == DevelopmentNode.SHIELD_DOCTRINE
                && migratedSingle.unlocked(DevelopmentNode.SHIELD_DOCTRINE),
            "one valid schema-5 doctrine must migrate without changing identity");

        int guildRevision = payAndAssert(helper, owner,
            DevelopmentNode.GUILD_DOCTRINE);
        helper.assertTrue(guildRevision == shieldRevision + 1
                && state.activeDoctrine() == DevelopmentNode.SHIELD_DOCTRINE
                && state.doctrineChangedAt() == firstDoctrineTime,
            "a later paid doctrine must preserve the first doctrine and timestamp");

        putCosts(owner.hearth, DevelopmentNode.GUILD_DOCTRINE);
        // Prices now use one physical Coins line. Preserve every inventory slot,
        // including unrelated goods, rather than assuming two historical barter lines.
        var replayInventory = com.hearthstead.settlement.CoinTreasury.snapshot(
            owner.hearth.getInventory());
        int revisionBeforeReplay = state.revision();
        Development.Result replay = Development.purchaseNode(helper.getLevel(),
            owner.settlement, owner.hearth, DevelopmentNode.GUILD_DOCTRINE,
            guildRevision - 1);
        helper.assertTrue(replay == Development.Result.STALE
                && state.revision() == revisionBeforeReplay,
            "a stale doctrine replay must author no revision");
        for (int slot = 0; slot < replayInventory.size(); slot++) {
            helper.assertTrue(ItemStack.matches(replayInventory.get(slot),
                    owner.hearth.getInventory().getStackInSlot(slot)),
                "a stale doctrine replay must preserve every physical stack and component");
        }

        payAndAssert(helper, owner, DevelopmentNode.HEARTH_DOCTRINE);
        payAndAssert(helper, owner, DevelopmentNode.BORDER_WARDENS);
        helper.assertTrue(state.unlocked(DevelopmentNode.SHIELD_DOCTRINE)
                && state.unlocked(DevelopmentNode.GUILD_DOCTRINE)
                && state.unlocked(DevelopmentNode.HEARTH_DOCTRINE)
                && state.activeDoctrine() == DevelopmentNode.SHIELD_DOCTRINE
                && state.doctrineChangedAt() == firstDoctrineTime,
            "all released doctrines must accumulate under the unchanged primary");

        helper.assertTrue(JobEmblemCatalog.RELEASE_CATALOG.size() == 31
                && JobEmblemCatalog.RELEASE_CATALOG.stream()
                    .filter(entry -> !entry.unlock().extendedTrade()).count() == 16,
            "the Mayor catalog must contain sixteen base professions (the eleven incl. Trader and Fisher, the Builder, the four battle roles) plus the fifteen extended trades");
        for (JobEmblemCatalog.Entry entry : JobEmblemCatalog.RELEASE_CATALOG) {
            if (entry.unlock().extendedTrade()) {
                continue; // specializations are not learned by this owner
            }
            // Tech tree v3: an emblem moved onto a new node (builders_hut ->
            // Builder, spearmen -> Spearman, ...) is reached by learning it.
            for (String claimant : com.hearthstead.settlement.techtree.EffectRegistry.get()
                    .professionClaimants(entry.profession())) {
                var def = com.hearthstead.settlement.techtree.TechTreeData.get().node(claimant);
                if (def != null && def.legacy() == null) {
                    state.learnTech(def.id());
                }
            }
            helper.assertTrue(Development.isEmblemUnlocked(helper.getLevel(),
                    owner.settlement, entry.profession()),
                "fully learned owner must reach " + entry.profession());
        }
        helper.assertTrue(!Development.isEmblemUnlocked(helper.getLevel(),
                other.settlement, Profession.HUNTER)
                && !Development.isEmblemUnlocked(helper.getLevel(),
                    other.settlement, Profession.SAWYER)
                && !Development.isEmblemUnlocked(helper.getLevel(),
                    other.settlement, Profession.SCHOLAR),
            "paid doctrine knowledge must remain isolated per settlement");

        CompoundTag savedRoot = Development.get(helper.getLevel()).save(
            new CompoundTag(), helper.getLevel().registryAccess());
        Development reloadedRoot = Development.load(savedRoot,
            helper.getLevel().registryAccess());
        DevelopmentState disk = reloadedRoot.existingState(owner.settlement.id);
        DevelopmentState isolatedDisk = reloadedRoot.existingState(other.settlement.id);
        helper.assertTrue(disk != null && isolatedDisk != null
                && !disk.quarantined() && !isolatedDisk.quarantined()
                && disk.activeDoctrine() == DevelopmentNode.SHIELD_DOCTRINE
                && disk.doctrineChangedAt() == firstDoctrineTime
                && disk.revision() == state.revision()
                && disk.unlocked(DevelopmentNode.SHIELD_DOCTRINE)
                && disk.unlocked(DevelopmentNode.GUILD_DOCTRINE)
                && disk.unlocked(DevelopmentNode.HEARTH_DOCTRINE)
                && isolatedDisk.activeDoctrine() == null
                && !isolatedDisk.unlocked(DevelopmentNode.SHIELD_DOCTRINE)
                && !isolatedDisk.unlocked(DevelopmentNode.GUILD_DOCTRINE)
                && !isolatedDisk.unlocked(DevelopmentNode.HEARTH_DOCTRINE),
            "the schema-6 SavedData root must reload cumulative doctrines and "
                + "preserve settlement isolation with exact primary identity");

        CompoundTag oldMultiple = state.writeNbt();
        oldMultiple.putInt("Schema", 5);
        helper.assertTrue(DevelopmentState.readNbt(oldMultiple).quarantined(),
            "multiple doctrines claiming an exclusive-era schema must fail closed");
        CompoundTag missingPrimary = state.writeNbt();
        missingPrimary.remove("ActiveDoctrine");
        helper.assertTrue(DevelopmentState.readNbt(missingPrimary).quarantined(),
            "current learned doctrines without a primary identity must fail closed");
        CompoundTag missingPrimaryKnowledge = primaryOnly.copy();
        removeUnlocked(missingPrimaryKnowledge, DevelopmentNode.SHIELD_DOCTRINE.id());
        helper.assertTrue(DevelopmentState.readNbt(missingPrimaryKnowledge).quarantined(),
            "current primary identity absent from learned knowledge must fail closed");
        CompoundTag missingPrimaryTimestamp = primaryOnly.copy();
        missingPrimaryTimestamp.remove("DoctrineChangedAt");
        helper.assertTrue(DevelopmentState.readNbt(missingPrimaryTimestamp).quarantined(),
            "current doctrine identity without its timestamp must fail closed");
        helper.succeed();
    }

    /** Exercises the exact one-second production seam used by onLevelTick. */
    @GameTest(template = "empty16", batch = "development", timeoutTicks = 120)
    public void housedDurationContinuesAfterAnotherPrimaryUntilHearthIsLearned(
            GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(3, 1, 3), "Housed Doctrine");
        DevelopmentState state = Development.of(helper.getLevel(), fixture.settlement);
        primeAftermath(state);
        state.noteGuardExperience(40);
        state.updateAllHousedTicks(true, 23_980);

        Building house = GameTestFixtures.register(helper, fixture.settlement,
            BuildingType.HOUSE, 7, 7);
        BlockPos bedRelative = new BlockPos(8, 1, 8);
        helper.setBlock(bedRelative, Blocks.RED_BED);
        house.beds.add(helper.absolutePos(bedRelative));
        fixture.settlement.putRecord(UUID.randomUUID(), "Housed Resident",
            Profession.NONE);
        payAndAssert(helper, fixture, DevelopmentNode.SHIELD_DOCTRINE);

        CompoundTag frozenLegacy = state.writeNbt();
        frozenLegacy.putInt("Schema", 5);
        DevelopmentState migratedLegacy = DevelopmentState.readNbt(frozenLegacy);
        DevelopmentState currentReload = DevelopmentState.readNbt(state.writeNbt());
        helper.assertTrue(!migratedLegacy.quarantined()
                && migratedLegacy.activeDoctrine() == DevelopmentNode.SHIELD_DOCTRINE
                && migratedLegacy.unlocked(DevelopmentNode.SHIELD_DOCTRINE)
                && migratedLegacy.revision() == state.revision()
                && migratedLegacy.counter(
                    DevelopmentObjective.GUARD_XP_EARNED) == 40
                && migratedLegacy.counter(DevelopmentObjective.ALL_HOUSED_TICKS) == 0,
            "schema-5 Shield progress frozen at 23,980 cannot prove continuity "
                + "and must reset without changing knowledge, payment revision or other facts");
        helper.assertTrue(!currentReload.quarantined()
                && currentReload.activeDoctrine() == DevelopmentNode.SHIELD_DOCTRINE
                && currentReload.counter(DevelopmentObjective.ALL_HOUSED_TICKS)
                    == 23_980,
            "schema-6 Shield progress was continuously observed and must survive reload");

        CompoundTag legacyRoot = new CompoundTag();
        ListTag settlements = new ListTag();
        CompoundTag entry = new CompoundTag();
        entry.putUUID("Id", fixture.settlement.id);
        entry.put("State", frozenLegacy);
        settlements.add(entry);
        legacyRoot.put("Settlements", settlements);
        Development migratedRoot = Development.load(legacyRoot,
            helper.getLevel().registryAccess());
        DevelopmentState migratedFromRoot = migratedRoot.existingState(
            fixture.settlement.id);
        helper.assertTrue(migratedRoot.isDirty() && migratedFromRoot != null
                && migratedFromRoot.counter(
                    DevelopmentObjective.ALL_HOUSED_TICKS) == 0,
            "the SavedData root must durably schedule the corrected legacy state for rewrite");

        Development development = Development.get(helper.getLevel());
        development.setDirty(false);
        helper.assertTrue(DevelopmentQuests.updateAllHousedProgress(
                helper.getLevel(), fixture.settlement) && development.isDirty(),
            "the real one-second driver seam must continue after Shield is primary "
                + "and dirty the same saved-data owner");
        int completedTicks = housedTicks(helper, fixture, state);
        helper.assertTrue(completedTicks == 24_000,
            "one valid housed sample must finish the exact 24,000-tick requirement");

        for (int index = 0; index < 5; index++) {
            state.noteEquipmentRequest(UUID.randomUUID());
        }
        payAndAssert(helper, fixture, DevelopmentNode.HEARTH_DOCTRINE);
        house.valid = false;
        development.setDirty(false);
        helper.assertTrue(!DevelopmentQuests.updateAllHousedProgress(
                helper.getLevel(), fixture.settlement)
                && housedTicks(helper, fixture, state) == completedTicks
                && !development.isDirty(),
            "the duration driver must stop only after Hearth itself is learned");
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

    private static void primeAftermath(DevelopmentState state) {
        DevelopmentNode[] trunk = {
            DevelopmentNode.TIMBER_RIGHTS,
            DevelopmentNode.STORES_AND_ROADS,
            DevelopmentNode.CULTIVATED_GROUND,
            DevelopmentNode.SHORE_PROVISIONS,
            DevelopmentNode.HOME,
            DevelopmentNode.HOSPITALITY,
            DevelopmentNode.FIRST_WATCH,
            DevelopmentNode.ARM_THE_WATCH,
            DevelopmentNode.FIRST_RAID_AFTERMATH
        };
        for (DevelopmentNode node : trunk) {
            state.unlock(node);
        }
        DevelopmentQuests.ensureEligibleBaselines(state);
    }

    private static void primeDoctrineObjectives(DevelopmentState state) {
        state.noteGuardExperience(40);
        state.noteCourierDelivery(22);
        state.noteCourierDelivery(21);
        state.noteCourierDelivery(21);
        state.updateAllHousedTicks(true, 24_000);
        for (int index = 0; index < 5; index++) {
            state.noteEquipmentRequest(UUID.randomUUID());
        }
    }

    private static int payAndAssert(GameTestHelper helper, Fixture fixture,
                                    DevelopmentNode node) {
        int expectedRevision = Development.revisionOf(helper.getLevel(),
            fixture.settlement);
        int[] before = new int[node.costs().size()];
        putCosts(fixture.hearth, node);
        for (int index = 0; index < before.length; index++) {
            before[index] = count(fixture.hearth, node.costs().get(index));
        }
        Development.Result result = Development.purchaseNode(helper.getLevel(),
            fixture.settlement, fixture.hearth, node, expectedRevision);
        helper.assertTrue(result == Development.Result.APPLIED,
            node.id() + " paid transaction failed: " + result);
        for (int index = 0; index < before.length; index++) {
            DevelopmentNode.Cost cost = node.costs().get(index);
            helper.assertTrue(count(fixture.hearth, cost)
                    == before[index] - cost.count(),
                node.id() + " must consume its exact declared "
                    + cost.displayName().getString() + " cost");
        }
        helper.assertTrue(Development.revisionOf(helper.getLevel(),
                fixture.settlement) == expectedRevision + 1,
            node.id() + " must commit exactly one revision");
        return expectedRevision + 1;
    }

    private static int payFromWalletAndAssert(GameTestHelper helper,
                                               Fixture fixture,
                                               DevelopmentNode node) {
        int expectedRevision = Development.revisionOf(helper.getLevel(),
            fixture.settlement);
        var actor = helper.makeMockServerPlayerInLevel();
        for (int index = 0; index < node.costs().size(); index++) {
            DevelopmentNode.Cost cost = node.costs().get(index);
            actor.getInventory().setItem(index,
                new ItemStack(cost.item(), cost.count()));
        }
        Development.Result result = Development.purchaseNode(helper.getLevel(),
            fixture.settlement, fixture.hearth, node, expectedRevision, actor);
        helper.assertTrue(result == Development.Result.APPLIED,
            node.id() + " must accept its published Coins price from the acting player");
        for (int index = 0; index < node.costs().size(); index++) {
            helper.assertTrue(actor.getInventory().getItem(index).isEmpty(),
                node.id() + " must consume the acting player's exact Coins payment");
        }
        helper.assertTrue(Development.revisionOf(helper.getLevel(),
                fixture.settlement) == expectedRevision + 1,
            node.id() + " must commit exactly one revision after wallet payment");
        return expectedRevision + 1;
    }

    private static int housedTicks(GameTestHelper helper, Fixture fixture,
                                   DevelopmentState state) {
        return DevelopmentQuests.progress(helper.getLevel(), fixture.settlement,
            fixture.hearth, state, DevelopmentNode.HEARTH_DOCTRINE).get(0).progress();
    }

    private static void putCosts(HearthBlockEntity hearth, DevelopmentNode node) {
        for (DevelopmentNode.Cost cost : node.costs()) {
            put(hearth, cost.item(), cost.count());
        }
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

    private static int count(HearthBlockEntity hearth,
                             DevelopmentNode.Cost cost) {
        int total = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (cost.matches(stack)) {
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
