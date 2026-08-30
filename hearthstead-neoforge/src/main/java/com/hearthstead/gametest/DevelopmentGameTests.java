package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Mayor;
import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.DevelopmentObjective;
import com.hearthstead.settlement.development.DevelopmentQuests;
import com.hearthstead.settlement.development.DevelopmentRecipeBook;
import com.hearthstead.settlement.development.DevelopmentState;
import com.hearthstead.settlement.development.JobEmblemCatalog;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.request.RequestLedgerService;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.RaidLifecycle;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.UUID;
import java.util.function.Consumer;

/** Contract tests for per-settlement knowledge and physical Mayor emblems. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class DevelopmentGameTests {

    @GameTest(template = "empty16", batch = "development", timeoutTicks = 100)
    public void everyImplementedKnowledgeEmblemHasOnePhysicalMayorItem(
            GameTestHelper helper) {
        HashSet<Profession> seen = new HashSet<>();
        for (JobEmblemCatalog.Entry entry : JobEmblemCatalog.RELEASE_CATALOG) {
            helper.assertTrue(seen.add(entry.profession()),
                "Mayor catalog contains duplicate " + entry.profession());
            helper.assertTrue(entry.unlock().implemented()
                    && entry.unlock().knowledge().jobEmblems().contains(entry.profession()),
                entry.profession() + " must be granted by its implemented knowledge node");
            helper.assertTrue(!JobEmblemItem.stackFor(entry.profession()).isEmpty(),
                entry.profession() + " must resolve to one registered physical emblem");
        }
        for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
            if (!node.implemented()) {
                continue;
            }
            for (Profession profession : node.knowledge().jobEmblems()) {
                helper.assertTrue(JobEmblemCatalog.releaseReady(profession),
                    node.id() + " must not promise an unissued " + profession + " emblem");
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "development", timeoutTicks = 200)
    public void knowledgeIsDeniedBeforeLearnedPersistentAndSettlementIsolated(
            GameTestHelper helper) {
        Fixture a = fixture(helper, new BlockPos(3, 1, 3), "A");
        Fixture b = fixture(helper, new BlockPos(12, 1, 12), "B");
        prepareFoundation(helper, a, 1, 9);
        helper.assertTrue(!Development.isBuildingUnlocked(helper.getLevel(), a.settlement,
            BuildingType.HOUSE), "fresh A must not know the later Home plans");
        helper.assertTrue(!Development.isBuildingUnlocked(helper.getLevel(), b.settlement,
            BuildingType.HOUSE), "fresh B must not know the later Home plans");
        helper.assertTrue(!Development.isBuildingUnlocked(helper.getLevel(), a.settlement,
            BuildingType.LUMBER_CAMP), "fresh A must not know Lumber Camp plans");
        helper.assertTrue(!Development.isBuildingUnlocked(helper.getLevel(), b.settlement,
            BuildingType.LUMBER_CAMP), "fresh B must not know Lumber Camp plans");
        ServerPlayer viewer = helper.makeMockServerPlayerInLevel();
        DevelopmentRecipeBook.syncPlayerHints(viewer, a.settlement);
        helper.assertTrue(!viewer.getRecipeBook().contains(
                Hearthstead.id("build_plan_house"))
                && !viewer.getRecipeBook().contains(
                    Hearthstead.id("build_plan_lumber_camp")),
            "recipe hints must not reveal the later Home or Timber plans early");

        int revision = Development.revisionOf(helper.getLevel(), a.settlement);
        payAndUnlock(helper, a, DevelopmentNode.TIMBER_RIGHTS);
        helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(), a.settlement,
            BuildingType.LUMBER_CAMP), "A must know Lumber Camp plan use after learning");
        helper.assertTrue(!Development.isBuildingUnlocked(helper.getLevel(), b.settlement,
            BuildingType.LUMBER_CAMP), "A's Timber knowledge must never leak into B");
        putCosts(a.hearth, DevelopmentNode.TIMBER_RIGHTS);
        int replayLogs = count(a.hearth, Items.OAK_LOG);
        int replayLeather = count(a.hearth, Items.LEATHER);
        Development.Result replay = Development.purchaseNode(helper.getLevel(), a.settlement,
            a.hearth, DevelopmentNode.TIMBER_RIGHTS, revision);
        helper.assertTrue(replay == Development.Result.STALE
                && count(a.hearth, Items.OAK_LOG) == replayLogs
                && count(a.hearth, Items.LEATHER) == replayLeather,
            "a replayed node packet must be stale and charge nothing");
        DevelopmentRecipeBook.syncPlayerHints(viewer, a.settlement);
        helper.assertTrue(viewer.getRecipeBook().contains(
                Hearthstead.id("build_plan_lumber_camp")),
            "learned Timber knowledge must become visible as a player recipe-book hint");

        DevelopmentState disk = DevelopmentState.readNbt(
            Development.of(helper.getLevel(), a.settlement).writeNbt());
        helper.assertTrue(disk.unlocked(DevelopmentNode.SHELTER)
                && disk.unlocked(DevelopmentNode.TIMBER_RIGHTS),
            "free Shelter and paid Timber knowledge must survive NBT round trip");
        helper.assertTrue(disk.revision() == revision + 1,
            "revision must survive NBT round trip");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "development", timeoutTicks = 200)
    public void insufficientAndPrerequisiteRefusalsTakeNothing(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(5, 1, 5), "Short");
        putCosts(fixture.hearth, DevelopmentNode.TIMBER_RIGHTS);
        int revision = Development.revisionOf(helper.getLevel(), fixture.settlement);
        Development.Result quest = Development.purchaseNode(helper.getLevel(),
            fixture.settlement, fixture.hearth, DevelopmentNode.TIMBER_RIGHTS, revision);
        helper.assertTrue(quest == Development.Result.QUEST_REQUIRED,
            "materials cannot bypass the live First Fire foundation quest, got " + quest);
        helper.assertTrue(count(fixture.hearth, Items.OAK_LOG) == 8
                && count(fixture.hearth, Items.COBBLESTONE) == 8,
            "an unmet quest must take no physical materials");

        prepareFoundation(helper, fixture, 1, 9);
        fixture.hearth.getInventory().extractItem(1, 1, false);
        Development.Result shortResult = Development.purchaseNode(helper.getLevel(),
            fixture.settlement, fixture.hearth, DevelopmentNode.TIMBER_RIGHTS, revision);
        helper.assertTrue(shortResult == Development.Result.MATERIALS,
            "8 logs + 7/8 cobblestone must refuse atomically, got " + shortResult);
        helper.assertTrue(count(fixture.hearth, Items.OAK_LOG) == 8
                && count(fixture.hearth, Items.COBBLESTONE) == 7,
            "refused node must not partially charge");
        helper.assertTrue(Development.revisionOf(helper.getLevel(), fixture.settlement)
            == revision, "refusal must not advance revision");

        putCosts(fixture.hearth, DevelopmentNode.STORES_AND_ROADS);
        Development.Result prereq = Development.purchaseNode(helper.getLevel(),
            fixture.settlement, fixture.hearth, DevelopmentNode.STORES_AND_ROADS, revision);
        helper.assertTrue(prereq == Development.Result.PREREQUISITE,
            "Stores and Roads before Timber Rights must refuse on prerequisite, got "
                + prereq);
        helper.assertTrue(count(fixture.hearth, Items.LEATHER) == 2
                && count(fixture.hearth, Items.OAK_LOG) == 16,
            "prerequisite refusal must take no physical materials");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "development", timeoutTicks = 250)
    public void questProgressIsServerMeasuredPersistentAndSettlementIsolated(
            GameTestHelper helper) {
        Fixture a = fixture(helper, new BlockPos(3, 1, 3), "Quest A");
        Fixture b = fixture(helper, new BlockPos(13, 1, 13), "Quest B");
        unlockThrough(helper, a, DevelopmentNode.TIMBER_RIGHTS);
        Building camp = GameTestFixtures.register(helper, a.settlement,
            BuildingType.LUMBER_CAMP, 1, 1);
        SettlerEntity lumberer = settler(helper, a.settlement, "Measured Lumberer",
            new BlockPos(2, 1, 6));
        helper.assertTrue(Employment.hire(helper.getLevel(), a.settlement,
                camp, lumberer).ok(),
            "quest fixture needs one real Lumber Camp employment record");
        helper.assertTrue(DevelopmentQuests.noteLumberLogsStored(helper.getLevel(),
                a.settlement, camp, lumberer, new ItemStack(Items.OAK_LOG), 1),
            "one committed Lumberer log must advance only A's server counter");

        DevelopmentQuests.Progress aProgress = DevelopmentQuests.progress(helper.getLevel(),
            a.settlement, a.hearth, Development.of(helper.getLevel(), a.settlement),
            DevelopmentNode.STORES_AND_ROADS).get(0);
        DevelopmentQuests.Progress bProgress = DevelopmentQuests.progress(helper.getLevel(),
            b.settlement, b.hearth, Development.of(helper.getLevel(), b.settlement),
            DevelopmentNode.STORES_AND_ROADS).get(0);
        helper.assertTrue(aProgress.objective() == DevelopmentObjective.LUMBER_LOGS_STORED
                && aProgress.progress() == 1 && aProgress.target() == 1,
            "A must expose the exact 1/1 physical Lumber output progress");
        helper.assertTrue(bProgress.progress() == 0,
            "A's worker event must never advance B's settlement quest");

        DevelopmentState disk = DevelopmentState.readNbt(
            Development.of(helper.getLevel(), a.settlement).writeNbt());
        DevelopmentQuests.Progress afterReload = DevelopmentQuests.progress(helper.getLevel(),
            a.settlement, a.hearth, disk, DevelopmentNode.STORES_AND_ROADS).get(0);
        helper.assertTrue(afterReload.progress() == 1 && afterReload.target() == 1,
            "quest counters and their activation baseline must survive NBT reload");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "development", timeoutTicks = 200)
    public void emblemPurchasePaysOnceAndReplayCannotDuplicate(GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(6, 1, 6), "Emblem");
        unlockThrough(helper, fixture, DevelopmentNode.TIMBER_RIGHTS);
        int unfundedRevision = Development.revisionOf(helper.getLevel(), fixture.settlement);
        Development.EmblemPurchase unfunded = Development.purchaseEmblem(helper.getLevel(),
            fixture.settlement, fixture.hearth, Profession.LUMBERER, unfundedRevision);
        helper.assertTrue(unfunded.result() == Development.Result.MATERIALS
                && unfunded.emblem().isEmpty(),
            "an unfunded Mayor purchase must issue nothing, got " + unfunded.result());
        helper.assertTrue(Development.revisionOf(helper.getLevel(), fixture.settlement)
                == unfundedRevision,
            "an unfunded Mayor purchase must not consume the replay revision");

        put(fixture.hearth, Items.FLINT, 2);
        put(fixture.hearth, Items.LEATHER, 2);
        int revision = Development.revisionOf(helper.getLevel(), fixture.settlement);

        Development.EmblemPurchase first = Development.purchaseEmblem(helper.getLevel(),
            fixture.settlement, fixture.hearth, Profession.LUMBERER, revision);
        helper.assertTrue(first.applied(), "unlocked, funded emblem must be issued");
        helper.assertTrue(JobEmblemItem.professionOf(first.emblem()) == Profession.LUMBERER,
            "issued item must carry Lumberer identity");
        helper.assertTrue(count(fixture.hearth, Items.FLINT) == 0
            && count(fixture.hearth, Items.LEATHER) == 0,
            "the exact physical Mayor price must leave the Hearth");

        put(fixture.hearth, Items.FLINT, 2);
        put(fixture.hearth, Items.LEATHER, 2);
        Development.EmblemPurchase replay = Development.purchaseEmblem(helper.getLevel(),
            fixture.settlement, fixture.hearth, Profession.LUMBERER, revision);
        helper.assertTrue(replay.result() == Development.Result.STALE
            && replay.emblem().isEmpty(), "same revision replay must issue nothing");
        helper.assertTrue(count(fixture.hearth, Items.FLINT) == 2
            && count(fixture.hearth, Items.LEATHER) == 2,
            "same revision replay must charge nothing");

        ServerPlayer actor = helper.makeMockServerPlayerInLevel();
        actor.setPos(fixture.hearth.getBlockPos().getX() + 0.5D,
            fixture.hearth.getBlockPos().getY() + 1.0D,
            fixture.hearth.getBlockPos().getZ() + 0.5D);
        for (int slot = 0; slot < actor.getInventory().getContainerSize(); slot++) {
            actor.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE,
                Items.COBBLESTONE.getDefaultMaxStackSize()));
        }
        int paidRevision = Development.revisionOf(helper.getLevel(),
            fixture.settlement);
        Development.EmblemPurchase paid = Development.purchaseEmblem(
            helper.getLevel(), fixture.settlement, fixture.hearth,
            Profession.LUMBERER, paidRevision, actor);
        DevelopmentState liveState = Development.of(helper.getLevel(),
            fixture.settlement);
        helper.assertTrue(paid.applied() && paid.deliveryId() != null
                && liveState.pendingDeliveryCount() == 1
                && count(fixture.hearth, Items.FLINT) == 0
                && count(fixture.hearth, Items.LEATHER) == 0,
            "a paid player purchase must commit its exact pending output with the charge");

        Consumer<EntityJoinLevelEvent> rejectEmblemSpawn = event -> {
            if (event.getLevel() == helper.getLevel()
                && event.getEntity() instanceof ItemEntity item
                && item.getUUID().equals(paid.deliveryId())) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
            EntityJoinLevelEvent.class, rejectEmblemSpawn);
        PendingPlayerDeliveryLedger.DeliveryResult refused;
        try {
            refused = Development.deliverPending(helper.getLevel(),
                fixture.settlement, actor, paid.deliveryId());
        } finally {
            NeoForge.EVENT_BUS.unregister(rejectEmblemSpawn);
        }
        helper.assertTrue(refused.outcome()
                == PendingPlayerDeliveryLedger.Outcome.PENDING
                && liveState.pendingDeliveryCount() == 1,
            "rejected paid-emblem spawn must retain durable source ownership");

        DevelopmentState restarted = DevelopmentState.readNbt(
            liveState.writeNbt());
        helper.assertTrue(!restarted.quarantined()
                && restarted.pendingDeliveryCount() == 1,
            "paid-emblem pending authority must survive source-state reload");
        PendingPlayerDeliveryLedger.DeliveryResult materialized =
            restarted.deliverPending(helper.getLevel(), actor, paid.deliveryId());
        AABB nearby = actor.getBoundingBox().inflate(3.0D);
        int physical = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
            nearby, item -> JobEmblemItem.professionOf(item.getItem())
                == Profession.LUMBERER).stream()
            .mapToInt(item -> item.getItem().getCount()).sum();
        helper.assertTrue(materialized.outcome()
                == PendingPlayerDeliveryLedger.Outcome.DROP
                && restarted.pendingDeliveryCount() == 0 && physical == 1,
            "reload retry must materialize exactly one paid Lumberer emblem");

        PendingPlayerDeliveryLedger.DeliveryResult staleSourceReplay =
            Development.deliverPending(helper.getLevel(), fixture.settlement,
                actor, paid.deliveryId());
        int afterReplay = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
            nearby, item -> JobEmblemItem.professionOf(item.getItem())
                == Profession.LUMBERER).stream()
            .mapToInt(item -> item.getItem().getCount()).sum();
        helper.assertTrue(staleSourceReplay.outcome()
                == PendingPlayerDeliveryLedger.Outcome.ALREADY_DELIVERED
                && liveState.pendingDeliveryCount() == 0 && afterReplay == 1,
            "a stale paid source row must acknowledge the stable physical emblem without duplication");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "development", timeoutTicks = 300)
    public void doctrineChoicePermanentlyExcludesTheOtherBranchesAndPersists(
            GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(8, 1, 8), "Doctrine");
        unlockThrough(helper, fixture, DevelopmentNode.ARM_THE_WATCH);
        completeFirstRaid(fixture.settlement);
        payAndUnlock(helper, fixture, DevelopmentNode.FIRST_RAID_AFTERMATH);
        Building barracks = GameTestFixtures.register(helper, fixture.settlement,
            BuildingType.BARRACKS, 10, 9);
        SettlerEntity guard = settler(helper, fixture.settlement, "Doctrine Guard",
            new BlockPos(9, 1, 7));
        helper.assertTrue(Employment.hire(helper.getLevel(), fixture.settlement,
                barracks, guard).ok(),
            "fixture Guard must have one real registered Barracks employer");
        helper.assertTrue(DevelopmentQuests.noteGuardExperience(helper.getLevel(),
                fixture.settlement, guard, 40),
            "40 exact server-awarded Guard XP must complete the Shield quest");
        payAndUnlock(helper, fixture, DevelopmentNode.SHIELD_DOCTRINE);

        DevelopmentState state = Development.of(helper.getLevel(), fixture.settlement);
        helper.assertTrue(state.activeDoctrine() == DevelopmentNode.SHIELD_DOCTRINE,
            "Shield must be the one active doctrine");
        putCosts(fixture.hearth, DevelopmentNode.GUILD_DOCTRINE);
        int iron = count(fixture.hearth, Items.IRON_INGOT);
        int logs = count(fixture.hearth, Items.OAK_LOG);
        Development.Result switchResult = Development.purchaseNode(helper.getLevel(),
            fixture.settlement, fixture.hearth, DevelopmentNode.GUILD_DOCTRINE,
            state.revision());
        helper.assertTrue(switchResult == Development.Result.DOCTRINE_EXCLUSIVE,
            "a second doctrine must be permanently excluded, got " + switchResult);
        helper.assertTrue(state.activeDoctrine() == DevelopmentNode.SHIELD_DOCTRINE,
            "refusal must leave exactly one active doctrine");
        helper.assertTrue(count(fixture.hearth, Items.IRON_INGOT) == iron
            && count(fixture.hearth, Items.OAK_LOG) == logs,
            "refused doctrine switch must charge nothing");

        DevelopmentState disk = DevelopmentState.readNbt(state.writeNbt());
        helper.assertTrue(disk.activeDoctrine() == DevelopmentNode.SHIELD_DOCTRINE
            && disk.unlocked(DevelopmentNode.SHIELD_DOCTRINE),
            "active and permanent doctrine knowledge must survive reload");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "development", timeoutTicks = 180)
    public void guardQuestFactRejectsNoopCapAndWrongSettlement(GameTestHelper helper) {
        Fixture owner = fixture(helper, new BlockPos(4, 1, 4), "Guard Owner");
        Fixture other = fixture(helper, new BlockPos(13, 1, 13), "Guard Other");
        Building barracks = GameTestFixtures.register(helper, owner.settlement,
            BuildingType.BARRACKS, 8, 2);
        SettlerEntity guard = settler(helper, owner.settlement, "Measured Guard",
            new BlockPos(7, 1, 7));
        helper.assertTrue(Employment.hire(helper.getLevel(), owner.settlement,
                barracks, guard).ok(),
            "guard fact fixture needs a real Barracks employment record");
        helper.assertTrue(!DevelopmentQuests.noteGuardExperience(helper.getLevel(),
                owner.settlement, guard, 0),
            "a capped/no-op award of zero must never advance the quest");
        helper.assertTrue(!DevelopmentQuests.noteGuardExperience(helper.getLevel(),
                other.settlement, guard, 5),
            "a guard bound to another settlement must never advance its quest");
        helper.assertTrue(DevelopmentQuests.noteGuardExperience(helper.getLevel(),
                owner.settlement, guard, Integer.MAX_VALUE),
            "the first positive actual award must clamp safely into the counter");
        helper.assertTrue(!DevelopmentQuests.noteGuardExperience(helper.getLevel(),
                owner.settlement, guard, 1),
            "once the bounded counter is capped, another award must be a no-op");
        helper.succeed();
    }

    @GameTest(template = "empty16", batch = "development", timeoutTicks = 400)
    public void watchSchemaMigrationPreservesOldProofAndCurrentDamageFailsClosed(
            GameTestHelper helper) {
        Fixture fixture = fixture(helper, new BlockPos(7, 1, 7), "Watch Migration");
        unlockThrough(helper, fixture, DevelopmentNode.FIRST_WATCH);
        DevelopmentState current = Development.of(helper.getLevel(), fixture.settlement);

        CompoundTag schemaTwo = current.writeNbt();
        schemaTwo.putInt("Schema", 2);
        schemaTwo.getCompound("QuestCounters").remove("GuardEquipmentDeliveries");
        schemaTwo.remove("SeenGuardEquipmentRequests");
        // A real schema-2 payload predates Arm the Watch, including its
        // baseline row. Downgrading a current payload without removing that
        // later-owned row correctly looks corrupt to the strict decoder.
        var schemaTwoBaselines = schemaTwo.getList("QuestBaselines",
            Tag.TAG_COMPOUND);
        for (int index = schemaTwoBaselines.size() - 1; index >= 0; index--) {
            if ((DevelopmentNode.ARM_THE_WATCH.id() + "/"
                    + DevelopmentObjective.GUARD_EQUIPMENT_DELIVERIES.id())
                    .equals(schemaTwoBaselines.getCompound(index).getString("Key"))) {
                schemaTwoBaselines.remove(index);
            }
        }
        DevelopmentState migrated = DevelopmentState.readNbt(schemaTwo);
        DevelopmentQuests.Progress migratedArm = DevelopmentQuests.progress(
            helper.getLevel(), fixture.settlement, fixture.hearth, migrated,
            DevelopmentNode.ARM_THE_WATCH).getFirst();
        helper.assertTrue(!migrated.quarantined()
                && migrated.unlocked(DevelopmentNode.FIRST_WATCH)
                && !migrated.unlocked(DevelopmentNode.ARM_THE_WATCH)
                && migratedArm.progress() == 1 && migratedArm.target() == 1,
            "schema-2 First Watch proof must expose one explicit zero-cost Arm the Watch claim");
        DevelopmentState rewritten = DevelopmentState.readNbt(migrated.writeNbt());
        DevelopmentQuests.Progress rewrittenArm = DevelopmentQuests.progress(
            helper.getLevel(), fixture.settlement, fixture.hearth, rewritten,
            DevelopmentNode.ARM_THE_WATCH).getFirst();
        helper.assertTrue(!rewritten.quarantined()
                && !rewritten.unlocked(DevelopmentNode.ARM_THE_WATCH)
                && rewrittenArm.progress() == 1 && rewrittenArm.target() == 1,
            "the claimable Watch objective must remain available after rewrite");

        CompoundTag impossibleOld = migrated.writeNbt();
        impossibleOld.putInt("Schema", 2);
        impossibleOld.getList("Unlocked", Tag.TAG_STRING).add(
            StringTag.valueOf(DevelopmentNode.ARM_THE_WATCH.id()));
        impossibleOld.getCompound("QuestCounters").remove(
            "GuardEquipmentDeliveries");
        impossibleOld.remove("SeenGuardEquipmentRequests");
        helper.assertTrue(DevelopmentState.readNbt(impossibleOld).quarantined(),
            "a schema-2 payload that already names the new node must fail closed");

        CompoundTag damagedCurrent = current.writeNbt();
        damagedCurrent.remove("SeenGuardEquipmentRequests");
        helper.assertTrue(DevelopmentState.readNbt(damagedCurrent).quarantined(),
            "current Watch state missing its owned Guard-delivery ledger must fail closed");
        helper.succeed();
    }

    /**
     * The Stores quest is not allowed to pass on a test-only adapter call.
     * A worker hired through the real Lumber Camp must fetch the physical axe,
     * fell the real tree, collect its drops through the fixed sack loop and
     * commit those logs to the Camp chest before Development can read 1/1.
     */
    @GameTest(template = "empty16", batch = "development_runtime",
        timeoutTicks = 2000)
    public void realLumbererDepositAdvancesStoresObjective(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);
        Fixture fixture = fixture(helper, new BlockPos(2, 1, 2),
            "Runtime Timber");
        fixture.settlement.radius = 20;
        Foundation foundation = prepareFoundation(helper, fixture, 10, 1);
        payAndUnlock(helper, fixture, DevelopmentNode.TIMBER_RIGHTS);

        Building camp = GameTestFixtures.register(helper, fixture.settlement,
            BuildingType.LUMBER_CAMP, 1, 9);
        Container storage = placeChest(helper, new BlockPos(2, 1, 10));
        storage.setItem(0, new ItemStack(Items.IRON_AXE));

        BlockPos dirt = new BlockPos(7, 1, 7);
        helper.setBlock(dirt, Blocks.DIRT);
        BlockPos trunk = dirt.above();
        for (int y = 0; y < 4; y++) {
            helper.setBlock(trunk.above(y), Blocks.OAK_LOG);
        }
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                helper.setBlock(trunk.above(4).offset(x, 0, z),
                    Blocks.OAK_LEAVES);
            }
        }

        SettlerEntity worker = foundation.worker;
        helper.assertTrue(Employment.hire(helper.getLevel(), fixture.settlement,
                camp, worker).ok(),
            "runtime Stores proof needs a real Lumber Camp hire");
        assertObjective(helper, fixture, DevelopmentNode.STORES_AND_ROADS,
            DevelopmentObjective.LUMBER_LOGS_STORED, 0, 1,
            "Stores must start at 0/1 before the real Lumberer deposit");

        final boolean[] sawChop = {false};
        final boolean[] sawHaul = {false};
        helper.succeedWhen(() -> {
            sawChop[0] |= worker.getActivity() == SettlerActivity.WORK_CHOP;
            sawHaul[0] |= worker.getActivity() == SettlerActivity.HAULING_LOG;
            int stored = count(storage, Items.OAK_LOG);
            helper.assertTrue(stored == 4,
                "the real Lumberer must bank all four tree logs before Stores passes"
                    + " [stored=" + stored + " activity=" + worker.getActivity()
                    + " bag=" + bagCount(worker) + " route="
                    + worker.routeFailureNote() + "]");
            helper.assertTrue(sawChop[0] && sawHaul[0],
                "Stores proof must include both real WORK_CHOP and HAULING_LOG states");
            assertObjective(helper, fixture, DevelopmentNode.STORES_AND_ROADS,
                DevelopmentObjective.LUMBER_LOGS_STORED, 1, 1,
                "the production Lumberer deposit must author exact Stores 1/1");
        });
    }

    /**
     * Cultivated Ground is credited only after the real Courier output route
     * reserves the Lumber Camp source, lifts its logs into the Courier bag and
     * physically inserts them into Warehouse storage.
     */
    @GameTest(template = "empty16", batch = "development_runtime",
        timeoutTicks = 2400)
    public void realCourierCollectionAdvancesCultivatedObjective(
            GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);
        Fixture fixture = fixture(helper, new BlockPos(2, 1, 2),
            "Runtime Courier");
        fixture.settlement.radius = 20;
        Foundation foundation = prepareFoundation(helper, fixture, 10, 1);
        payAndUnlock(helper, fixture, DevelopmentNode.TIMBER_RIGHTS);

        Building camp = GameTestFixtures.register(helper, fixture.settlement,
            BuildingType.LUMBER_CAMP, 1, 9);
        SettlerEntity worker = foundation.worker;
        helper.assertTrue(Employment.hire(helper.getLevel(), fixture.settlement,
                camp, worker).ok(),
            "runtime Courier setup needs the prior real Lumber Camp employer");
        helper.assertTrue(DevelopmentQuests.noteLumberLogsStored(helper.getLevel(),
                fixture.settlement, camp, worker,
                new ItemStack(Items.OAK_LOG), 1),
            "setup: one prior Lumber output must make Stores purchasable");
        payAndUnlock(helper, fixture, DevelopmentNode.STORES_AND_ROADS);

        Container source = placeChest(helper, new BlockPos(2, 1, 10));
        source.setItem(0, new ItemStack(Items.OAK_LOG, 12));
        Building warehouse = GameTestFixtures.register(helper, fixture.settlement,
            BuildingType.WAREHOUSE, 7, 9);
        Container destination = placeChest(helper, new BlockPos(8, 1, 10));
        helper.assertTrue(Employment.hire(helper.getLevel(), fixture.settlement,
                warehouse, worker).ok(),
            "runtime Cultivated proof needs a real Warehouse-employed Courier");
        assertObjective(helper, fixture, DevelopmentNode.CULTIVATED_GROUND,
            DevelopmentObjective.COURIER_DELIVERIES, 0, 1,
            "Cultivated must start at 0/1 before the real Courier route");

        final boolean[] sawReservedRequest = {false};
        final boolean[] sawPhysicalBag = {false};
        helper.succeedWhen(() -> {
            RequestLedgerService.Route route = RequestLedgerService
                .routeForCourier(helper.getLevel(), fixture.settlement, worker);
            if (route.request() != null && route.source() != null
                && route.source().id.equals(camp.id)
                && worker.getUUID().equals(route.request().courierId())) {
                sawReservedRequest[0] = true;
            }
            if (bagCount(worker) > 0) {
                sawPhysicalBag[0] = true;
            }
            int atSource = count(source, Items.OAK_LOG);
            int atDestination = count(destination, Items.OAK_LOG);
            int inBag = bagCount(worker);
            helper.assertTrue(atSource + atDestination + inBag == 12,
                "Courier output collection must conserve all 12 physical logs"
                    + " [source=" + atSource + " bag=" + inBag
                    + " warehouse=" + atDestination + "]");
            helper.assertTrue(atSource == 0 && atDestination == 12,
                "the real Courier must complete source -> bag -> Warehouse"
                    + " [activity=" + worker.getActivity() + " route="
                    + worker.routeFailureNote() + "]");
            helper.assertTrue(sawReservedRequest[0] && sawPhysicalBag[0],
                "Cultivated proof must observe both the reserved output request and "
                    + "its physical Courier bag load");
            assertObjective(helper, fixture, DevelopmentNode.CULTIVATED_GROUND,
                DevelopmentObjective.COURIER_DELIVERIES, 1, 1,
                "the completed production Courier route must author exact Cultivated 1/1");
        });
    }

    /**
     * Home is credited only after the Farmer goal harvests a mature world crop,
     * replants it and commits the physical produce into its Farmhouse chest.
     */
    @GameTest(template = "empty16", batch = "development_runtime",
        timeoutTicks = 2000)
    public void realFarmerHarvestAdvancesHomeObjective(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper, 16);
        Fixture fixture = fixture(helper, new BlockPos(2, 1, 2),
            "Runtime Farmer");
        fixture.settlement.radius = 20;
        Foundation foundation = prepareFoundation(helper, fixture, 10, 1);
        payAndUnlock(helper, fixture, DevelopmentNode.TIMBER_RIGHTS);

        Building camp = GameTestFixtures.register(helper, fixture.settlement,
            BuildingType.LUMBER_CAMP, 1, 9);
        SettlerEntity worker = foundation.worker;
        helper.assertTrue(Employment.hire(helper.getLevel(), fixture.settlement,
                camp, worker).ok(),
            "runtime Farmer setup needs the prior Lumberer employer");
        helper.assertTrue(DevelopmentQuests.noteLumberLogsStored(helper.getLevel(),
                fixture.settlement, camp, worker,
                new ItemStack(Items.OAK_LOG), 1),
            "setup: one prior Lumber output must complete Stores");
        payAndUnlock(helper, fixture, DevelopmentNode.STORES_AND_ROADS);

        Building warehouse = GameTestFixtures.register(helper, fixture.settlement,
            BuildingType.WAREHOUSE, 6, 1);
        helper.assertTrue(Employment.hire(helper.getLevel(), fixture.settlement,
                warehouse, worker).ok(),
            "runtime Farmer setup needs the prior Warehouse-employed Courier");
        helper.assertTrue(DevelopmentQuests.noteCourierDelivery(helper.getLevel(),
                fixture.settlement, worker, camp, warehouse, 1),
            "setup: one prior productive Courier delivery must complete Cultivated");
        payAndUnlock(helper, fixture, DevelopmentNode.CULTIVATED_GROUND);

        Building farmhouse = GameTestFixtures.register(helper, fixture.settlement,
            BuildingType.FARMHOUSE, 9, 9);
        Container storage = placeChest(helper, new BlockPos(11, 1, 11));
        storage.setItem(0, new ItemStack(Items.IRON_HOE));
        BlockPos crop = new BlockPos(10, 1, 9);
        helper.setBlock(crop.below(), Blocks.FARMLAND.defaultBlockState()
            .setValue(FarmBlock.MOISTURE, 7));
        helper.setBlock(crop, Blocks.WHEAT.defaultBlockState()
            .setValue(CropBlock.AGE, 7));
        worker.bag.addItem(new ItemStack(Items.WHEAT_SEEDS, 4));
        helper.assertTrue(Employment.hire(helper.getLevel(), fixture.settlement,
                farmhouse, worker).ok(),
            "runtime Home proof needs a real Farmhouse-employed Farmer");
        assertObjective(helper, fixture, DevelopmentNode.HOME,
            DevelopmentObjective.FARM_CROPS_STORED, 0, 1,
            "Home must start at 0/1 before the real Farmer harvest");

        final boolean[] sawHarvest = {false};
        helper.succeedWhen(() -> {
            sawHarvest[0] |= worker.getActivity() == SettlerActivity.WORK_HARVEST;
            int wheat = count(storage, Items.WHEAT);
            helper.assertTrue(wheat > 0,
                "the real Farmer harvest must reach Farmhouse storage"
                    + " [wheat=" + wheat + " activity=" + worker.getActivity()
                    + " bag=" + bagCount(worker) + " route="
                    + worker.routeFailureNote() + "]");
            helper.assertTrue(sawHarvest[0],
                "Home proof must observe the real WORK_HARVEST state");
            helper.assertTrue(helper.getBlockState(crop).is(Blocks.WHEAT)
                    && helper.getBlockState(crop).getValue(CropBlock.AGE) < 7,
                "the harvested world crop must be physically replanted");
            assertObjective(helper, fixture, DevelopmentNode.HOME,
                DevelopmentObjective.FARM_CROPS_STORED, 1, 1,
                "the production Farmer deposit must author exact Home 1/1");
        });
    }

    private static void buildArena(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean rim = x == 0 || z == 0 || x == size - 1
                    || z == size - 1;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 6; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 2 ? Blocks.STONE_BRICKS.defaultBlockState()
                            : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static Container placeChest(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, Blocks.CHEST);
        Object blockEntity = helper.getLevel().getBlockEntity(
            helper.absolutePos(relative));
        helper.assertTrue(blockEntity instanceof Container,
            "fixture chest must expose a real Container at " + relative);
        return (Container) blockEntity;
    }

    private static int count(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int bagCount(SettlerEntity settler) {
        int total = 0;
        for (int slot = 0; slot < settler.bag.getContainerSize(); slot++) {
            total += settler.bag.getItem(slot).getCount();
        }
        return total;
    }

    private static void assertObjective(GameTestHelper helper, Fixture fixture,
                                        DevelopmentNode node,
                                        DevelopmentObjective objective,
                                        int expectedProgress, int expectedTarget,
                                        String message) {
        var rows = DevelopmentQuests.progress(helper.getLevel(),
            fixture.settlement, fixture.hearth,
            Development.of(helper.getLevel(), fixture.settlement), node);
        helper.assertTrue(rows.size() == 1,
            node.id() + " fixture expected one objective, got " + rows.size());
        DevelopmentQuests.Progress progress = rows.get(0);
        helper.assertTrue(progress.objective() == objective
                && progress.progress() == expectedProgress
                && progress.target() == expectedTarget,
            message + " [objective=" + progress.objective() + " progress="
                + progress.progress() + '/' + progress.target() + "]");
    }

    private static void unlockThrough(GameTestHelper helper, Fixture fixture,
                                      DevelopmentNode target) {
        Foundation foundation = prepareFoundation(helper, fixture, 1, 9);
        DevelopmentState founding = Development.of(helper.getLevel(), fixture.settlement);
        helper.assertTrue(founding.unlocked(DevelopmentNode.SETTLEMENT_CHARTER)
                && founding.unlocked(DevelopmentNode.SHELTER),
            "Settlement Charter and Shelter must be founding knowledge, not paid nodes");
        if (target == DevelopmentNode.SHELTER) {
            return;
        }
        payAndUnlock(helper, fixture, DevelopmentNode.TIMBER_RIGHTS);
        if (target == DevelopmentNode.TIMBER_RIGHTS) {
            return;
        }

        Building camp = GameTestFixtures.register(helper, fixture.settlement,
            BuildingType.LUMBER_CAMP, 1, 1);
        SettlerEntity worker = foundation.worker;
        helper.assertTrue(Employment.hire(helper.getLevel(), fixture.settlement,
                camp, worker).ok(),
            "fixture Lumberer must have one registered Lumber Camp employer");
        helper.assertTrue(DevelopmentQuests.noteLumberLogsStored(helper.getLevel(),
                fixture.settlement, camp, worker,
                new ItemStack(Items.OAK_LOG), 1),
            "one physical Lumberer log must complete the Stores and Roads quest");
        payAndUnlock(helper, fixture, DevelopmentNode.STORES_AND_ROADS);
        if (target == DevelopmentNode.STORES_AND_ROADS) {
            return;
        }

        Building warehouse = GameTestFixtures.register(helper, fixture.settlement,
            BuildingType.WAREHOUSE, 10, 1);
        helper.assertTrue(Employment.hire(helper.getLevel(), fixture.settlement,
                warehouse, worker).ok(),
            "fixture Courier must have one registered Warehouse employer");
        helper.assertTrue(DevelopmentQuests.noteCourierDelivery(helper.getLevel(),
                fixture.settlement, worker, camp, warehouse, 1),
            "one physical productive Courier route must complete Cultivated Ground");
        payAndUnlock(helper, fixture, DevelopmentNode.CULTIVATED_GROUND);
        if (target == DevelopmentNode.CULTIVATED_GROUND) {
            return;
        }

        Building farm = GameTestFixtures.register(helper, fixture.settlement,
            BuildingType.FARMHOUSE, 5, 1);
        helper.assertTrue(Employment.hire(helper.getLevel(), fixture.settlement,
                farm, worker).ok(),
            "fixture Farmer must have one registered Farmhouse employer");
        helper.assertTrue(DevelopmentQuests.noteFarmCropsStored(helper.getLevel(),
                fixture.settlement, farm, worker, new ItemStack(Items.WHEAT), 1),
            "one physical Farmer crop must complete the Home quest");
        payAndUnlock(helper, fixture, DevelopmentNode.HOME);
        if (target == DevelopmentNode.HOME) {
            return;
        }

        addBed(helper, foundation.house, new BlockPos(3, 1, 10), Blocks.GREEN_BED);
        payAndUnlock(helper, fixture, DevelopmentNode.HOSPITALITY);
        if (target == DevelopmentNode.HOSPITALITY) {
            return;
        }

        addBed(helper, foundation.house, new BlockPos(4, 1, 10), Blocks.YELLOW_BED);
        SettlerEntity recruit = settler(helper, fixture.settlement, "Quest Recruit",
            new BlockPos(7, 1, 8));
        helper.assertTrue(fixture.settlement.population() == 4
                && fixture.settlement.validBedCount() >= 4,
            "First Watch must follow the fourth housed resident");
        payAndUnlock(helper, fixture, DevelopmentNode.FIRST_WATCH);
        if (target == DevelopmentNode.FIRST_WATCH) {
            return;
        }

        BlockPos equipmentSourceRelative = new BlockPos(11, 1, 2);
        helper.setBlock(equipmentSourceRelative, Blocks.CHEST);
        BlockPos equipmentSource = helper.absolutePos(equipmentSourceRelative);
        helper.assertTrue(Employment.hire(helper.getLevel(), fixture.settlement,
                warehouse, worker).ok(),
            "the established worker must return to the Warehouse as the live Courier");
        SettlerEntity courier = worker;
        Building barracks = GameTestFixtures.register(helper, fixture.settlement,
            BuildingType.BARRACKS, 10, 10);
        BlockPos equipmentTargetRelative = new BlockPos(11, 1, 11);
        helper.setBlock(equipmentTargetRelative, Blocks.CHEST);
        BlockPos equipmentTarget = helper.absolutePos(equipmentTargetRelative);
        SettlerEntity guard = recruit;
        guard.setSettlerName("Quest Guard");
        helper.assertTrue(Employment.hire(helper.getLevel(), fixture.settlement,
                barracks, guard).ok(),
            "First Watch must expose a real Barracks post for the Guard");
        EquipmentRequest request = EquipmentRequests.requestFor(barracks,
            guard.getUUID());
        helper.assertTrue(request != null
                && request.profession() == Profession.GUARD,
            "the Barracks must publish the Guard's missing-sword request");
        SettlerEntity rogueCourier = settler(helper, fixture.settlement,
            "Unemployed Courier", new BlockPos(8, 1, 9));
        rogueCourier.assignProfession(Profession.COURIER);
        fixture.settlement.putRecord(rogueCourier.getUUID(),
            rogueCourier.getSettlerName(), Profession.COURIER);
        helper.assertTrue(EquipmentRequests.claim(helper.getLevel(), fixture.settlement,
                request.id(), rogueCourier.getUUID()),
            "fixture: even a raw id may claim; quest authority must reject it later");
        helper.assertTrue(!EquipmentRequests.markDelivered(helper.getLevel(),
                fixture.settlement, request.id(), rogueCourier,
                equipmentTarget)
                && request.status() == EquipmentRequest.Status.CLAIMED,
            "an unemployed raw-id claimant without SOURCE -> BAG proof must not "
                + "forge a delivery");
        DevelopmentQuests.Progress beforeCourierCredit = DevelopmentQuests.progress(
            helper.getLevel(), fixture.settlement, fixture.hearth,
            Development.of(helper.getLevel(), fixture.settlement),
            DevelopmentNode.ARM_THE_WATCH).get(0);
        Development.Assessment beforeRealDelivery = Development.assessNode(
            helper.getLevel(), fixture.settlement, fixture.hearth,
            DevelopmentNode.ARM_THE_WATCH);
        helper.assertTrue(beforeCourierCredit.progress() == 0
                && beforeRealDelivery.result() == Development.Result.QUEST_REQUIRED
                && !Development.isBuildingUnlocked(helper.getLevel(),
                    fixture.settlement, BuildingType.WATCHTOWER)
                && !Development.isEmblemUnlocked(helper.getLevel(),
                    fixture.settlement, Profession.ARCHER),
            "Arm the Watch must deny Watchtower and Archer before one real "
                + "Guard-to-Barracks weapon delivery");
        helper.assertTrue(EquipmentRequests.release(helper.getLevel(),
                fixture.settlement, request.id(), rogueCourier.getUUID()),
            "the rejected pre-pickup claim must release without touching an item");

        var sourceEntity = helper.getLevel().getBlockEntity(equipmentSource);
        var targetEntity = helper.getLevel().getBlockEntity(equipmentTarget);
        helper.assertTrue(sourceEntity instanceof Container
                && targetEntity instanceof Container,
            "fixture: equipment source and Barracks target must be real containers");
        Container sourceContainer = (Container) sourceEntity;
        Container targetContainer = (Container) targetEntity;
        sourceContainer.setItem(0, new ItemStack(Items.IRON_SWORD));
        sourceContainer.setChanged();
        helper.assertTrue(courier.bag.getItem(0).isEmpty()
                && targetContainer.getItem(0).isEmpty(),
            "fixture: strict route must begin with one source owner only");
        helper.assertTrue(EquipmentRequests.claim(helper.getLevel(), fixture.settlement,
                request.id(), courier.getUUID()),
            "the live Warehouse-employed Courier must claim the reopened request");
        ItemStack selected = sourceContainer.getItem(0).copyWithCount(1);
        helper.assertTrue(EquipmentRequests.bindRoute(helper.getLevel(),
                fixture.settlement, request.id(), courier, warehouse,
                equipmentSource, 0, barracks, equipmentTarget, selected),
            "the valid Courier must bind an exact loaded source slot and target");
        ItemStack inTransit = sourceContainer.removeItem(0, 1);
        sourceContainer.setChanged();
        courier.bag.setItem(0, inTransit);
        helper.assertTrue(!inTransit.isEmpty()
                && EquipmentRequests.markPickedUp(helper.getLevel(),
                    fixture.settlement, request.id(), courier),
            "SOURCE -> COURIER_BAG must commit only after the physical move");
        ItemStack delivered = courier.bag.removeItem(0, 1);
        targetContainer.setItem(0, delivered);
        targetContainer.setChanged();
        helper.assertTrue(!delivered.isEmpty()
                && EquipmentRequests.markDelivered(helper.getLevel(),
                    fixture.settlement, request.id(), courier,
                    equipmentTarget),
            "COURIER_BAG -> TARGET must commit only after the exact insert");
        DevelopmentQuests.Progress afterCourierCredit = DevelopmentQuests.progress(
            helper.getLevel(), fixture.settlement, fixture.hearth,
            Development.of(helper.getLevel(), fixture.settlement),
            DevelopmentNode.ARM_THE_WATCH).get(0);
        helper.assertTrue(afterCourierCredit.progress() == 1
                && afterCourierCredit.target() == 1,
            "one strict Guard-to-Barracks Courier delivery must author exact 1/1 progress");
        helper.assertTrue(!DevelopmentQuests.noteEquipmentRequestDelivered(
                helper.getLevel(), fixture.settlement, request.id(), courier.getUUID()),
            "replaying the same delivered Guard request id must never count twice");
        payAndUnlock(helper, fixture, DevelopmentNode.ARM_THE_WATCH);
        helper.assertTrue(Development.isBuildingUnlocked(helper.getLevel(),
                fixture.settlement, BuildingType.WATCHTOWER)
                && Development.isEmblemUnlocked(helper.getLevel(),
                    fixture.settlement, Profession.ARCHER),
            "the committed Guard weapon delivery must make the baseline "
                + "Watchtower and Archer emblem available before the first raid");
        if (target != DevelopmentNode.ARM_THE_WATCH) {
            throw new IllegalArgumentException("target is not in the release trunk: " + target);
        }
    }

    private static void payAndUnlock(GameTestHelper helper, Fixture fixture,
                                     DevelopmentNode node) {
        putCosts(fixture.hearth, node);
        Development.Result result = Development.purchaseNode(helper.getLevel(),
            fixture.settlement, fixture.hearth, node,
            Development.revisionOf(helper.getLevel(), fixture.settlement));
        helper.assertTrue(result == Development.Result.APPLIED,
            node.id() + " fixture unlock failed: " + result);
    }

    private static void putCosts(HearthBlockEntity hearth, DevelopmentNode node) {
        for (DevelopmentNode.Cost cost : node.costs()) {
            put(hearth, cost.item(), cost.count());
        }
    }

    private static Foundation prepareFoundation(GameTestHelper helper, Fixture fixture,
                                                int houseX, int houseZ) {
        Building house = GameTestFixtures.register(helper, fixture.settlement,
            BuildingType.HOUSE, houseX, houseZ);
        addBed(helper, house, new BlockPos(houseX, 1, houseZ + 1), Blocks.RED_BED);
        addBed(helper, house, new BlockPos(houseX + 1, 1, houseZ + 1), Blocks.BLUE_BED);
        SettlerEntity mayor = settler(helper, fixture.settlement, "Quest Mayor",
            new BlockPos(houseX, 1, Math.max(1, houseZ - 2)));
        settler(helper, fixture.settlement, "Quest Resident",
            new BlockPos(houseX + 1, 1, Math.max(1, houseZ - 2)));
        SettlerEntity worker = settler(helper, fixture.settlement, "Quest Worker",
            new BlockPos(houseX + 2, 1, Math.max(1, houseZ - 2)));
        helper.assertTrue(Mayor.appoint(helper.getLevel(), fixture.settlement, mayor) == null
                && mayor.getUUID().equals(fixture.settlement.mayorId),
            "foundation requires one living, appointed Mayor");
        helper.assertTrue(fixture.settlement.population() == 3,
            "foundation requires exactly three live settlement records");
        return new Foundation(house, mayor, worker);
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement settlement,
                                         String name, BlockPos relative) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(), relative);
        settler.setSettlerName(name);
        settler.bindTo(settlement.id, settlement.center);
        settlement.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    private static void addBed(GameTestHelper helper, Building house,
                               BlockPos relative, net.minecraft.world.level.block.Block bed) {
        helper.setBlock(relative, bed);
        BlockPos absolute = helper.absolutePos(relative);
        if (!house.beds.contains(absolute)) {
            house.beds.add(absolute);
        }
    }

    private static void completeFirstRaid(Settlement settlement) {
        RaidLifecycle lifecycle = new RaidLifecycle();
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.KORN, 0.0F, 4L);
        UUID participant = UUID.randomUUID();
        boolean complete = lifecycle.initializeAtFounding(0L, 4, 2)
            && lifecycle.queueFirstPlan(plan)
            && lifecycle.beginFirstRaid(plan)
            && lifecycle.recordParticipant(participant)
            && lifecycle.sealParticipants()
            && lifecycle.recordTerminalParticipant(participant)
            && lifecycle.completeFirstRaid(false);
        if (!complete || lifecycle.firstState() != FirstRaidState.COMPLETED) {
            throw new IllegalStateException("could not complete first raid fixture");
        }
        settlement.raidLifecycle = lifecycle;
    }

    private static Fixture fixture(GameTestHelper helper, BlockPos hearthRelative,
                                   String name) {
        helper.setBlock(hearthRelative, ModBlocks.HEARTH.get());
        BlockPos absolute = helper.absolutePos(hearthRelative);
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel()
            .getBlockEntity(absolute);
        Settlement settlement = new Settlement(UUID.randomUUID(), name, absolute);
        settlement.radius = 7;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        hearth.bindSettlement(settlement.id);
        // Initialize before fixtures register buildings. Otherwise the
        // deliberate legacy grandfather pass would turn a new test House or
        // workplace into free Development knowledge.
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
            if (existing.is(item) && existing.getCount() + amount <= existing.getMaxStackSize()) {
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

    private record Foundation(Building house, SettlerEntity mayor,
                              SettlerEntity worker) {
    }
}
