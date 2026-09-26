package com.hearthstead.settlement.work;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.gametest.GameTestFixtures;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData.Phase;
import com.hearthstead.settlement.workzone.WorkZone;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import com.hearthstead.settlement.journey.JourneyDefinition;
import com.hearthstead.settlement.journey.JourneyEvidence;
import com.hearthstead.settlement.journey.JourneyIds;
import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.journey.JourneyPresentationMode;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.journey.JourneySource;
import com.hearthstead.settlement.journey.JourneyState;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.Optional;

/** Adversarial physical-authority tests for worker provenance. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class WorkerProvenanceGameTests {
    @GameTest(template = "empty16", timeoutTicks = 750, batch = "worker_provenance")
    public void missingFarmOutputRecoversWithoutInventingDelivery(GameTestHelper helper) {
        Fixture f = fixture(helper, BuildingType.FARMHOUSE, Profession.FARMER, Items.IRON_HOE);
        f.worker().setNoAi(true); // Isolate loss and return authority, not navigation.
        BlockPos chestRel = new BlockPos(5, 1, 5);
        Container storage = chest(helper, chestRel);
        BlockPos crop = helper.absolutePos(new BlockPos(8, 2, 8));
        helper.setBlock(new BlockPos(8, 1, 8), Blocks.FARMLAND);
        helper.getLevel().setBlockAndUpdate(crop, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        standAtFarmContact(helper, f.worker(), crop, crop.east());
        UUID harvest = WorkerProvenanceService.beginFarmHarvest(helper.getLevel(), f.settlement(),
            f.building(), f.worker(), crop);
        ItemStack wheat = new ItemStack(Items.WHEAT);
        helper.assertTrue(harvest != null && WorkerProvenanceService.stampOutput(helper.getLevel(),
            f.settlement(), f.building(), f.worker(), harvest, wheat, crop), "actual crop output stamps");
        helper.getLevel().setBlockAndUpdate(crop, Blocks.AIR.defaultBlockState());
        f.worker().bag.addItem(wheat.copy());
        helper.assertTrue(WorkerProvenanceService.commitFarmHarvest(helper.getLevel(), f.settlement(),
            f.building(), f.worker(), harvest, crop, List.of(wheat)), "actual crop removal commits");
        var data = WorkerProvenanceSavedData.get(helper.getLevel());
        helper.runAfterDelay(610, () -> {
            WorkerProvenanceService.reconcileUnavailableFarmOutput(helper.getLevel(), f.settlement(), f.building(), f.worker());
            helper.assertTrue(data.action(harvest).phase() == Phase.WORK_COMMITTED, "carried crop cannot be declared absent");
            ItemStack carried = f.worker().bag.removeItemNoUpdate(taggedSlot(f.worker(), harvest));
            var drop = new net.minecraft.world.entity.item.ItemEntity(helper.getLevel(), crop.getX()+.5, crop.getY(), crop.getZ()+.5, carried);
            helper.getLevel().addFreshEntity(drop);
            WorkerProvenanceService.reconcileUnavailableFarmOutput(helper.getLevel(), f.settlement(), f.building(), f.worker());
            helper.assertTrue(data.action(harvest).phase() == Phase.WORK_COMMITTED, "loaded world crop cannot be declared absent");
            // Model a player taking this physical stack away, retaining it for a later return.
            ItemStack taken = drop.getItem();
            drop.discard();
            f.worker().getPersistentData().put("HearthsteadGroundedFieldBag", new CompoundTag());
            WorkerProvenanceService.reconcileUnavailableFarmOutput(helper.getLevel(), f.settlement(), f.building(), f.worker());
            helper.assertTrue(data.action(harvest).phase() == Phase.WORK_COMMITTED, "even malformed active field intent blocks absence recovery");
            f.worker().getPersistentData().remove("HearthsteadGroundedFieldBag");
            WorkerProvenanceService.reconcileUnavailableFarmOutput(helper.getLevel(), f.settlement(), f.building(), f.worker());
            helper.assertTrue(data.action(harvest).phase() == Phase.OUTPUT_UNAVAILABLE
                && data.action(harvest).deposited().isEmpty() && data.receiptsFor(harvest).isEmpty(),
                "missing crop is recorded without any fictional delivery");
            helper.assertTrue(!WorkerProvenanceService.readinessEvidence(helper.getLevel(), f.settlement()).unresolvedWorkerTransit(),
                "missing output no longer permanently locks readiness");
            f.worker().bag.addItem(taken);
            helper.assertTrue(WorkerProvenanceService.readinessEvidence(helper.getLevel(), f.settlement()).unresolvedWorkerTransit(),
                "returned real cargo is observed again");
            helper.assertTrue(depositTaggedBag(helper, f, storage, helper.absolutePos(chestRel)) == 1
                && count(storage, Items.WHEAT) == 1, "the returned physical crop deposits once");
            helper.succeed();
        });
    }


    @GameTest(template = "empty16", timeoutTicks = 100, batch = "worker_provenance")
    public void untouchedObsoleteLumberActionAllowsNewZoneWork(GameTestHelper helper) {
        Fixture f = fixture(helper, BuildingType.LUMBER_CAMP, Profession.LUMBERER, Items.IRON_AXE);
        f.worker().setNoAi(true);
        BlockPos root = helper.absolutePos(new BlockPos(8, 2, 8));
        naturalTree(helper, root, 2);
        WorkZone old = f.building().workZone().orElseThrow();
        UUID first = WorkerProvenanceService.beginLumberTree(helper.getLevel(), f.settlement(),
            f.building(), f.worker(), old, root, List.of(root.above(), root));
        helper.assertTrue(first != null, "original actual tree starts without mutation");
        WorkZone next = new WorkZone(old.settlementId(), old.buildingId(), old.type(),
            old.dimension(), old.min(), old.max(), old.revision() + 1);
        helper.assertTrue(f.building().commitWorkZone(old.revision(), next), "zone edit commits");
        UUID replacement = WorkerProvenanceService.beginLumberTree(helper.getLevel(), f.settlement(),
            f.building(), f.worker(), next, root, List.of(root.above(), root));
        var data = WorkerProvenanceSavedData.get(helper.getLevel());
        helper.assertTrue(replacement != null && !replacement.equals(first)
            && data.action(first).phase() == Phase.RETIRED && data.action(first).produced().isEmpty()
            && f.worker().bag.isEmpty() && f.worker().getMainHandItem().getDamageValue() == 0,
            "obsolete untouched action no longer deadlocks new zone and creates no goods/durability");
        helper.assertTrue(Employment.dismiss(helper.getLevel(), f.settlement(), f.worker()) == f.building(),
            "actual dismissal closes employment");
        WorkerProvenanceService.reconcileLumberWork(helper.getLevel(), f.worker());
        var loaded = WorkerProvenanceSavedData.load(data.save(new CompoundTag(), helper.getLevel().registryAccess()),
            helper.getLevel().registryAccess());
        helper.assertTrue(!loaded.quarantined() && loaded.action(replacement).phase() == Phase.RETIRED,
            "dismissed untouched work remains retired after ledger decode");
        SettlementSavedData.get(helper.getLevel()).settlements.remove(f.settlement().id);
        SettlementSavedData.get(helper.getLevel()).setDirty();
        f.worker().discard();
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100, batch = "worker_provenance")
    public void obsoleteLumberZoneRetainsOnePhysicalLogThroughDismissalAndReload(GameTestHelper helper) {
        Fixture f = fixture(helper, BuildingType.LUMBER_CAMP, Profession.LUMBERER, Items.IRON_AXE);
        f.worker().setNoAi(true); // Isolate public authority seams, not a walking claim.
        BlockPos root = helper.absolutePos(new BlockPos(8, 2, 8));
        BlockPos top = root.above();
        naturalTree(helper, root, 2);
        WorkZone old = f.building().workZone().orElseThrow();
        UUID action = WorkerProvenanceService.beginLumberTree(helper.getLevel(),
            f.settlement(), f.building(), f.worker(), old, root, List.of(top, root));
        helper.assertTrue(action != null, "real natural tree starts under original zone");
        helper.assertTrue(WorkerProvenanceService.prepareLumberLog(helper.getLevel(),
            f.settlement(), f.building(), f.worker(), action, top), "one real axe use prepares");
        ItemStack physical = new ItemStack(Items.OAK_LOG);
        helper.assertTrue(WorkerProvenanceService.stampOutput(helper.getLevel(),
            f.settlement(), f.building(), f.worker(), action, physical, top), "real output is stamped");
        helper.getLevel().setBlockAndUpdate(top, Blocks.AIR.defaultBlockState());
        helper.assertTrue(f.worker().bag.addItem(physical).isEmpty()
            && WorkerProvenanceService.completeLumberLog(helper.getLevel(),
                f.settlement(), f.building(), f.worker(), action, top, physical), "one physical output resolves");
        WorkZone next = new WorkZone(old.settlementId(), old.buildingId(), old.type(),
            old.dimension(), old.min(), old.max(), old.revision() + 1);
        helper.assertTrue(f.building().commitWorkZone(old.revision(), next), "actual revision edit commits");
        WorkerProvenanceService.reconcileLumberWork(helper.getLevel(), f.worker());
        var data = WorkerProvenanceSavedData.get(helper.getLevel());
        helper.assertTrue(data.action(action).phase() == Phase.RETIRED
            && !WorkerProvenanceService.prepareLumberLog(helper.getLevel(), f.settlement(),
                f.building(), f.worker(), action, root)
            && helper.getLevel().getBlockState(root).is(Blocks.OAK_LOG),
            "old authority cannot chop remaining root or claim whole-tree completion");
        helper.assertTrue(Employment.dismiss(helper.getLevel(), f.settlement(), f.worker()) == f.building(),
            "actual dismissal succeeds without deleting owned cargo");
        helper.assertTrue(WorkerProvenanceService.recoverableLumberWork(helper.getLevel(),
            f.settlement(), f.building(), f.worker()) == null, "unemployed worker has no storage authority");
        CompoundTag actorSave = new CompoundTag();
        f.worker().saveWithoutId(actorSave);
        SettlerEntity decoded = ModEntities.SETTLER.get().create(helper.getLevel());
        helper.assertTrue(decoded != null, "reload decoder exists");
        decoded.load(actorSave);
        var loaded = WorkerProvenanceSavedData.load(data.save(new CompoundTag(), helper.getLevel().registryAccess()),
            helper.getLevel().registryAccess());
        helper.assertTrue(!loaded.quarantined() && loaded.action(action).phase() == Phase.RETIRED
            && loaded.action(action).remaining(ResourceLocation.withDefaultNamespace("oak_log")) == 1
            && count(decoded.bag, Items.OAK_LOG) == 1
            && decoded.getMainHandItem().getDamageValue() == 1,
            "clean entity and ledger decode preserve exactly one log and one paid axe use");
        helper.assertTrue(Employment.hire(helper.getLevel(), f.settlement(), f.building(), f.worker()).ok(),
            "rehire at exact original employer restores lawful recovery");
        Container storage = chest(helper, new BlockPos(5, 1, 5));
        ItemStack replay = tagged(f.worker(), WorkerStackProvenance.TransitKind.LUMBER_LOG).copy();
        helper.assertTrue(depositTaggedBag(helper, f, storage,
            helper.absolutePos(new BlockPos(5, 1, 5))) == 1 && f.worker().bag.isEmpty(),
            "the preserved physical output reaches original camp despite changed zone");
        var retry = WorkerProvenanceService.depositOutput(helper.getLevel(), f.settlement(), f.building(),
            f.worker(), helper.absolutePos(new BlockPos(5, 1, 5)), replay);
        helper.assertTrue(retry.receipt() == null && retry.remainder().getCount() == 1
            && count(storage, Items.OAK_LOG) == 1 && data.action(action).phase() == Phase.RETIRED
            && data.action(action).resolved().size() == 1 && data.receiptsFor(action).size() == 1,
            "replayed stamp cannot duplicate output or upgrade a partial tree to completed");
        SettlementSavedData.get(helper.getLevel()).settlements.remove(f.settlement().id);
        SettlementSavedData.get(helper.getLevel()).setDirty();
        f.worker().discard();
        helper.succeed();
    }



    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "worker_provenance")
    public void lumberActionPinsEveryPhysicalLogAndDurabilityUse(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper, BuildingType.LUMBER_CAMP,
            Profession.LUMBERER, Items.IRON_AXE);
        Container chest = chest(helper, new BlockPos(5, 1, 5));
        BlockPos root = helper.absolutePos(new BlockPos(8, 2, 8));
        BlockPos top = root.above();
        naturalTree(helper, root, 2);

        UUID action = WorkerProvenanceService.beginLumberTree(helper.getLevel(),
            fixture.settlement(), fixture.building(), fixture.worker(),
            fixture.building().workZone().orElseThrow(), root,
            List.of(top, root));
        helper.assertTrue(action != null, "natural tree must open one action");

        for (BlockPos log : List.of(top, root)) {
            helper.assertTrue(WorkerProvenanceService.prepareLumberLog(
                helper.getLevel(), fixture.settlement(), fixture.building(),
                fixture.worker(), action, log),
                "each real log must prepare exactly one axe use");
            ItemStack physical = new ItemStack(Items.OAK_LOG);
            helper.assertTrue(WorkerProvenanceService.stampOutput(
                helper.getLevel(), fixture.settlement(), fixture.building(),
                fixture.worker(), action, physical, log),
                "the physical log must carry its action before world removal");
            helper.getLevel().setBlockAndUpdate(log, Blocks.AIR.defaultBlockState());
            helper.assertTrue(fixture.worker().bag.addItem(physical).isEmpty(),
                "test bag must accept the stamped physical log");
            helper.assertTrue(WorkerProvenanceService.completeLumberLog(
                helper.getLevel(), fixture.settlement(), fixture.building(),
                fixture.worker(), action, log, physical),
                "the same log operation must resolve once");
            helper.assertTrue(WorkerStackProvenance.readToolUse(
                    fixture.worker().getMainHandItem()).isEmpty(),
                "completed log must clear its transient axe-use marker");
        }
        helper.assertTrue(WorkerProvenanceService.commitLumberTree(
            helper.getLevel(), fixture.settlement(), fixture.building(),
            fixture.worker(), action), "whole tree must terminal-commit");

        int deposited = depositTaggedBag(helper, fixture, chest,
            helper.absolutePos(new BlockPos(5, 1, 5)));
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(
            helper.getLevel());
        var terminal = data.action(action);
        helper.assertTrue(deposited == 2 && count(chest, Items.OAK_LOG) == 2,
            "exactly two physical logs must reach linked camp storage");
        helper.assertTrue(terminal != null
                && terminal.phase() == Phase.OUTPUT_COMMITTED
                && terminal.resolved().size() == 2
                && terminal.appliedToolDamage() == 2
                && terminal.remaining(ResourceLocation.withDefaultNamespace(
                    "oak_log")) == 0,
            "ledger must bind two logs to two durability points and no remainder");
        helper.assertTrue(fixture.worker().getMainHandItem().getDamageValue() == 2,
            "axe must lose one durability per actually removed log");
        helper.assertTrue(data.receiptsFor(action).size() == 2,
            "each separately tagged physical log must have one deposit receipt");
        helper.assertTrue(WorkerStackProvenance.readTransit(
            first(chest, Items.OAK_LOG)).isEmpty(),
            "terminal insertion must strip temporary transit data");
        helper.assertTrue(GoodsQuality.of(first(chest, Items.OAK_LOG)) >= GoodsQuality.FINE,
            "real Lumberer quality must survive terminal workplace insertion");
        var readiness = WorkerProvenanceService.readinessEvidence(
            helper.getLevel(), fixture.settlement());
        helper.assertTrue(readiness.authoritative()
                && readiness.lumberZoneCommitted()
                && readiness.lumberWorkCommitted()
                && readiness.lumberOutputCommitted()
                && readiness.allResidentStacksObserved()
                && !readiness.unresolvedWorkerTransit()
                && !readiness.conflictingStackOwnership(),
            "bounded readiness query must re-observe clean lumber authority");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "worker_provenance_adversarial")
    public void partialDepositWithWorldItemRemainsUnresolvedWithoutWorldScan(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper, BuildingType.LUMBER_CAMP,
            Profession.LUMBERER, Items.IRON_AXE);
        BlockPos chestRel = new BlockPos(5, 1, 5);
        BlockPos chestPos = helper.absolutePos(chestRel);
        Container chest = chest(helper, chestRel);
        BlockPos root = helper.absolutePos(new BlockPos(8, 2, 8));
        BlockPos top = root.above();
        naturalTree(helper, root, 2);

        UUID action = WorkerProvenanceService.beginLumberTree(helper.getLevel(),
            fixture.settlement(), fixture.building(), fixture.worker(),
            fixture.building().workZone().orElseThrow(), root,
            List.of(top, root));
        helper.assertTrue(action != null, "two-log authority must open");
        for (BlockPos log : List.of(top, root)) {
            helper.assertTrue(WorkerProvenanceService.prepareLumberLog(
                helper.getLevel(), fixture.settlement(), fixture.building(),
                fixture.worker(), action, log), "log must prepare");
            ItemStack physical = new ItemStack(Items.OAK_LOG);
            helper.assertTrue(WorkerProvenanceService.stampOutput(
                helper.getLevel(), fixture.settlement(), fixture.building(),
                fixture.worker(), action, physical, log), "log must stamp");
            helper.getLevel().setBlockAndUpdate(log, Blocks.AIR.defaultBlockState());
            helper.assertTrue(WorkerProvenanceService.completeLumberLog(
                helper.getLevel(), fixture.settlement(), fixture.building(),
                fixture.worker(), action, log, physical), "log must complete");
            helper.assertTrue(fixture.worker().bag.addItem(physical).isEmpty(),
                "physical output must enter the bag");
        }
        helper.assertTrue(WorkerProvenanceService.commitLumberTree(
            helper.getLevel(), fixture.settlement(), fixture.building(),
            fixture.worker(), action), "two-log action must commit");

        int depositedSlot = taggedSlot(fixture.worker(), action);
        ItemStack first = fixture.worker().bag.getItem(depositedSlot);
        var firstDeposit = WorkerProvenanceService.depositOutput(
            helper.getLevel(), fixture.settlement(), fixture.building(),
            fixture.worker(), chestPos, first);
        fixture.worker().bag.setItem(depositedSlot, firstDeposit.remainder());
        helper.assertTrue(firstDeposit.receipt() != null
                && count(chest, Items.OAK_LOG) == 1,
            "only one physical log must be receipted before the interruption");

        int worldSlot = taggedSlot(fixture.worker(), action);
        ItemStack worldStack = fixture.worker().bag.removeItem(worldSlot, 1);
        ItemEntity worldItem = new ItemEntity(helper.getLevel(), root.getX() + 0.5,
            root.getY() + 0.25, root.getZ() + 0.5, worldStack);
        helper.assertTrue(!worldStack.isEmpty()
                && helper.getLevel().addFreshEntity(worldItem),
            "remaining tagged log must exist as a real world item");

        var interrupted = WorkerProvenanceService.readinessEvidence(
            helper.getLevel(), fixture.settlement());
        helper.assertTrue(interrupted.lumberOutputCommitted()
                && interrupted.unresolvedWorkerTransit()
                && !interrupted.conflictingStackOwnership(),
            "persisted produced-minus-deposited remainder must block readiness "
                + "even when the physical item is outside resident inventories");

        ItemStack recovered = worldItem.getItem().copy();
        worldItem.discard();
        helper.assertTrue(fixture.worker().bag.addItem(recovered).isEmpty(),
            "test recovery must conserve the same physical world item");
        helper.assertTrue(depositTaggedBag(helper, fixture, chest, chestPos) == 1,
            "recovered physical log must create exactly the missing receipt");
        var resolved = WorkerProvenanceService.readinessEvidence(
            helper.getLevel(), fixture.settlement());
        helper.assertTrue(count(chest, Items.OAK_LOG) == 2
                && !resolved.unresolvedWorkerTransit()
                && !resolved.conflictingStackOwnership(),
            "readiness may clear only after all produced output is receipted");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "worker_provenance_adversarial")
    public void changedZoneAndOffOperationCannotStampOutput(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper, BuildingType.LUMBER_CAMP,
            Profession.LUMBERER, Items.IRON_AXE);
        BlockPos root = helper.absolutePos(new BlockPos(8, 2, 8));
        naturalTree(helper, root, 1);
        WorkZone original = fixture.building().workZone().orElseThrow();
        UUID action = WorkerProvenanceService.beginLumberTree(helper.getLevel(),
            fixture.settlement(), fixture.building(), fixture.worker(), original,
            root, List.of(root));
        helper.assertTrue(action != null && WorkerProvenanceService.prepareLumberLog(
            helper.getLevel(), fixture.settlement(), fixture.building(),
            fixture.worker(), action, root), "setup action must prepare");

        ItemStack authorisedTool = fixture.worker().getMainHandItem().copy();
        ItemStack receiptlessTool = new ItemStack(Items.IRON_AXE);
        receiptlessTool.setDamageValue(authorisedTool.getDamageValue());
        fixture.worker().setItemSlot(EquipmentSlot.MAINHAND, receiptlessTool);
        helper.assertTrue(!WorkerProvenanceService.prepareLumberLog(
                helper.getLevel(), fixture.settlement(), fixture.building(),
                fixture.worker(), action, root),
            "persisted pending=true may not replace the physical tool receipt");
        fixture.worker().setItemSlot(EquipmentSlot.MAINHAND, authorisedTool);
        helper.assertTrue(WorkerProvenanceService.prepareLumberLog(
                helper.getLevel(), fixture.settlement(), fixture.building(),
                fixture.worker(), action, root),
            "the exact receipt-bearing tool must remain restart-authoritative");

        ItemStack wrongBearing = new ItemStack(Items.OAK_LOG);
        helper.assertTrue(!WorkerProvenanceService.stampOutput(helper.getLevel(),
                fixture.settlement(), fixture.building(), fixture.worker(),
                action, wrongBearing, root.east())
                && WorkerStackProvenance.readTransit(wrongBearing).isEmpty()
                && GoodsQuality.of(wrongBearing) == GoodsQuality.BASIC,
            "an off-operation source may not inherit output authority");

        WorkZone replacement = WorkZone.between(fixture.settlement().id,
            fixture.building().id, WorkZone.Type.LUMBER,
            helper.getLevel().dimension().location(),
            helper.absolutePos(new BlockPos(6, 1, 6)),
            helper.absolutePos(new BlockPos(10, 6, 10)), 2);
        helper.assertTrue(fixture.building().commitWorkZone(1, replacement),
            "test must advance the authoritative zone revision");
        ItemStack stale = new ItemStack(Items.OAK_LOG);
        helper.assertTrue(!WorkerProvenanceService.stampOutput(helper.getLevel(),
                fixture.settlement(), fixture.building(), fixture.worker(),
                action, stale, root)
                && WorkerStackProvenance.readTransit(stale).isEmpty()
                && GoodsQuality.of(stale) == GoodsQuality.BASIC
                && helper.getBlockState(new BlockPos(8, 2, 8)).is(Blocks.OAK_LOG),
            "stale-zone action may neither stamp output nor mutate its log");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "worker_provenance_adversarial")
    public void fullStorageKeepsTaggedOutputAndAuthorsNoReceipt(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper, BuildingType.LUMBER_CAMP,
            Profession.LUMBERER, Items.IRON_AXE);
        BlockPos chestRel = new BlockPos(5, 1, 5);
        Container chest = chest(helper, chestRel);
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            chest.setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        }
        PreparedLog prepared = oneTerminalLog(helper, fixture);
        ItemStack physical = fixture.worker().bag.getItem(prepared.bagSlot());
        ItemStack before = physical.copy();
        var result = WorkerProvenanceService.depositOutput(helper.getLevel(),
            fixture.settlement(), fixture.building(), fixture.worker(),
            helper.absolutePos(chestRel), physical);
        fixture.worker().bag.setItem(prepared.bagSlot(), result.remainder());
        var action = WorkerProvenanceSavedData.get(helper.getLevel())
            .action(prepared.action());

        helper.assertTrue(result.receipt() == null
                && result.remainder().getCount() == before.getCount()
                && WorkerStackProvenance.readTransit(result.remainder()).isPresent()
                && count(chest, Items.OAK_LOG) == 0
                && action != null && action.phase() == Phase.WORK_COMMITTED
                && action.deposited().isEmpty(),
            "full target must preserve tagged cargo and author zero evidence");
        var readiness = WorkerProvenanceService.readinessEvidence(
            helper.getLevel(), fixture.settlement());
        helper.assertTrue(readiness.unresolvedWorkerTransit()
                && !readiness.conflictingStackOwnership(),
            "valid undeposited output is unresolved, not conflicting ownership");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "worker_provenance_adversarial")
    public void deletedBuildingCannotReceiveOrStripTaggedOutput(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper, BuildingType.LUMBER_CAMP,
            Profession.LUMBERER, Items.IRON_AXE);
        BlockPos chestRel = new BlockPos(5, 1, 5);
        Container chest = chest(helper, chestRel);
        PreparedLog prepared = oneTerminalLog(helper, fixture);
        fixture.settlement().buildings.remove(fixture.building());
        ItemStack physical = fixture.worker().bag.getItem(prepared.bagSlot());

        var result = WorkerProvenanceService.depositOutput(helper.getLevel(),
            fixture.settlement(), fixture.building(), fixture.worker(),
            helper.absolutePos(chestRel), physical);
        fixture.worker().bag.setItem(prepared.bagSlot(), result.remainder());
        helper.assertTrue(result.receipt() == null
                && result.remainder().getCount() == 1
                && WorkerStackProvenance.readTransit(result.remainder()).isPresent()
                && count(chest, Items.OAK_LOG) == 0
                && WorkerProvenanceSavedData.get(helper.getLevel())
                    .receiptsFor(prepared.action()).isEmpty(),
            "deleted identity must fail closed without item loss or evidence");
        helper.assertTrue(WorkerProvenanceService.readinessEvidence(
                helper.getLevel(), fixture.settlement())
                .conflictingStackOwnership(),
            "stale physical ownership must block the bounded readiness query");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "worker_provenance_adversarial")
    public void supersededZoneRevisionCannotCountTerminalAction(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper, BuildingType.LUMBER_CAMP,
            Profession.LUMBERER, Items.IRON_AXE);
        PreparedLog prepared = oneTerminalLog(helper, fixture);
        WorkZone replacement = WorkZone.between(fixture.settlement().id,
            fixture.building().id, WorkZone.Type.LUMBER,
            helper.getLevel().dimension().location(),
            helper.absolutePos(new BlockPos(5, 1, 5)),
            helper.absolutePos(new BlockPos(10, 7, 10)), 2);
        helper.assertTrue(fixture.building().commitWorkZone(1, replacement),
            "test must supersede the action's exact zone revision");

        var readiness = WorkerProvenanceService.readinessEvidence(
            helper.getLevel(), fixture.settlement());
        helper.assertTrue(readiness.lumberZoneCommitted()
                && !readiness.lumberWorkCommitted()
                && !readiness.lumberOutputCommitted()
                && readiness.conflictingStackOwnership()
                && taggedSlot(fixture.worker(), prepared.action()) >= 0,
            "stale terminal action and cargo may not count for the live revision");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "worker_provenance")
    public void farmInputPlantHarvestAndDepositStayOnePhysicalChain(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper, BuildingType.FARMHOUSE,
            Profession.FARMER, Items.IRON_HOE);
        BlockPos chestRel = new BlockPos(5, 1, 5);
        Container chest = chest(helper, chestRel);
        chest.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 4));
        BlockPos crop = helper.absolutePos(new BlockPos(8, 2, 8));
        helper.setBlock(new BlockPos(8, 1, 8), Blocks.FARMLAND);

        UUID plant = WorkerProvenanceService.supplyOneSeed(helper.getLevel(),
            fixture.settlement(), fixture.building(), fixture.worker(),
            stack -> stack.is(Items.WHEAT_SEEDS));
        ItemStack seed = tagged(fixture.worker(),
            WorkerStackProvenance.TransitKind.FARM_SEED_INPUT);
        helper.assertTrue(plant != null && count(chest, Items.WHEAT_SEEDS) == 3
                && seed.getCount() == 1
                && WorkerProvenanceSavedData.get(helper.getLevel()).action(plant)
                    .phase() == Phase.INPUT_HELD,
            "source seed must physically leave exact Farmhouse but stay non-terminal");
        // Both plots must remain physically reachable so the wrong-plot
        // assertion below continues to test persisted identity, not range.
        standAtFarmContact(helper, fixture.worker(), crop, crop.east());
        helper.assertTrue(WorkerProvenanceService.prepareFarmPlant(
            helper.getLevel(), fixture.settlement(), fixture.building(),
            fixture.worker(), plant, crop), "exact plot must prepare one hoe use");
        var resumablePlant = WorkerProvenanceService.resumableFarmPlant(
            helper.getLevel(), fixture.settlement(), fixture.building(),
            fixture.worker());
        helper.assertTrue(resumablePlant != null
                && resumablePlant.planned().equals(List.of(crop))
                && !WorkerProvenanceService.prepareFarmPlant(
                    helper.getLevel(), fixture.settlement(), fixture.building(),
                    fixture.worker(), plant, crop.east()),
            "restart must pin the paid seed and hoe receipt to the exact plot");
        removeOneTagged(fixture.worker(), plant);
        helper.getLevel().setBlockAndUpdate(crop,
            Blocks.WHEAT.defaultBlockState());
        helper.assertTrue(WorkerProvenanceService.commitFarmPlant(
                helper.getLevel(), fixture.settlement(), fixture.building(),
                fixture.worker(), plant, crop,
                ResourceLocation.withDefaultNamespace("wheat"))
                && WorkerProvenanceSavedData.get(helper.getLevel()).action(plant)
                    .phase() == Phase.WORK_COMMITTED
                && fixture.worker().getMainHandItem().getDamageValue() == 1
                && WorkerStackProvenance.readToolUse(
                    fixture.worker().getMainHandItem()).isEmpty(),
            "FJ-360 seam must exist only after tagged seed becomes real crop");

        helper.getLevel().setBlockAndUpdate(crop,
            Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        standAtFarmContact(helper, fixture.worker(), crop, crop.east());
        UUID harvest = WorkerProvenanceService.beginFarmHarvest(helper.getLevel(),
            fixture.settlement(), fixture.building(), fixture.worker(), crop);
        BlockState mature = helper.getLevel().getBlockState(crop);
        List<ItemStack> drops = new ArrayList<>(Block.getDrops(mature,
            helper.getLevel(), crop, null));
        helper.assertTrue(harvest != null && !drops.isEmpty(),
            "mature exact field crop must open a harvest action");
        for (ItemStack drop : drops) {
            helper.assertTrue(WorkerProvenanceService.stampOutput(helper.getLevel(),
                fixture.settlement(), fixture.building(), fixture.worker(),
                harvest, drop, crop), "every physical crop drop must be stamped");
        }
        helper.getLevel().setBlockAndUpdate(crop, Blocks.AIR.defaultBlockState());
        for (ItemStack drop : drops) {
            helper.assertTrue(fixture.worker().bag.addItem(drop.copy()).isEmpty(),
                "bag must physically own every declared harvest output");
        }
        helper.assertTrue(WorkerProvenanceService.commitFarmHarvest(
                helper.getLevel(), fixture.settlement(), fixture.building(),
                fixture.worker(), harvest, crop, drops)
                && fixture.worker().getMainHandItem().getDamageValue() == 2
                && WorkerStackProvenance.readToolUse(
                    fixture.worker().getMainHandItem()).isEmpty(),
            "one crop removal must add one and only one second hoe damage");
        int offered = total(drops);
        int inserted = depositTaggedBag(helper, fixture, chest,
            helper.absolutePos(chestRel));
        var terminal = WorkerProvenanceSavedData.get(helper.getLevel())
            .action(harvest);
        helper.assertTrue(inserted == offered && terminal != null
                && terminal.phase() == Phase.OUTPUT_COMMITTED
                && terminal.appliedToolDamage() == 1
                && terminal.deposited().equals(terminal.produced())
                && !WorkerProvenanceSavedData.get(helper.getLevel())
                    .receiptsFor(harvest).isEmpty(),
            "conserved crop output must terminate only in linked Farmhouse storage");
        helper.assertTrue(GoodsQuality.of(first(chest, Items.WHEAT)) >= GoodsQuality.FINE,
            "real Farmer quality must survive physical crop deposit");
        var readiness = WorkerProvenanceService.readinessEvidence(
            helper.getLevel(), fixture.settlement());
        helper.assertTrue(readiness.authoritative()
                && readiness.farmZoneCommitted()
                && readiness.farmSeedPlantedCommitted()
                && readiness.farmHarvestCommitted()
                && readiness.farmOutputCommitted()
                && readiness.allResidentStacksObserved()
                && !readiness.unresolvedWorkerTransit()
                && !readiness.conflictingStackOwnership(),
            "bounded readiness query must re-observe clean farm authority");
        helper.succeed();
    }

    /** Direct-service fixture positioning; never changes terrain or work authority. */
    private static void standAtFarmContact(GameTestHelper helper,
                                            SettlerEntity worker, BlockPos crop,
                                            BlockPos alsoVisible) {
        var level = helper.getLevel();
        for (int dy : new int[] {1, 0, 2, -1, -2}) {
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    BlockPos feet = crop.offset(dx, dy, dz);
                    if (feet.distSqr(crop) > 6.5D || feet.distSqr(alsoVisible) > 6.5D
                        || !com.hearthstead.settlement.workzone.WorkZoneService.livePositionAvailable(level, feet)
                        || !com.hearthstead.settlement.workzone.WorkZoneService.livePositionAvailable(level, feet.above())
                        || !com.hearthstead.settlement.workzone.WorkZoneService.livePositionAvailable(level, feet.below())
                        || !level.getBlockState(feet.below()).isFaceSturdy(level,
                            feet.below(), net.minecraft.core.Direction.UP)) {
                        continue;
                    }
                    var position = net.minecraft.world.phys.Vec3.atBottomCenterOf(feet);
                    if (!level.noCollision(worker, worker.getBoundingBox().move(
                            position.subtract(worker.position())))) continue;
                    worker.moveTo(position.x, position.y, position.z,
                        worker.getYRot(), worker.getXRot());
                    worker.getNavigation().stop();
                    if (FarmWorkApproach.canContact(level, worker, crop,
                            FarmWorkApproach.Contact.PLANT)
                        && FarmWorkApproach.canContact(level, worker, alsoVisible,
                            FarmWorkApproach.Contact.PLANT)) {
                        helper.assertTrue(worker.isAlive() && worker.level() == level
                                && level.noCollision(worker),
                            "fixture must occupy a clear supported live field stand");
                        return;
                    }
                }
            }
        }
        helper.fail("fixture has no loaded, supported, collision-free farm contact for "
            + crop + " and " + alsoVisible + "; lastFeet=" + worker.position());
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "worker_provenance_telemetry")
    public void completedJourneyStillLogsDistinctPhysicalTreesAndDeposits(GameTestHelper helper) {
        workerTelemetryBeyondJourney(helper, false);
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "worker_provenance_telemetry")
    public void skippedJourneyStillLogsDistinctPhysicalTreesAndDeposits(GameTestHelper helper) {
        workerTelemetryBeyondJourney(helper, true);
    }

    private static void workerTelemetryBeyondJourney(GameTestHelper helper, boolean skipped) {
        Fixture fixture = fixture(helper, BuildingType.LUMBER_CAMP,
            Profession.LUMBERER, Items.IRON_AXE);
        // Direct physical-service fixture, not an AI walking/animation claim.
        // NoAI isolates the later replay window without forging any output receipt.
        fixture.worker().setNoAi(true);
        fixture.settlement().journeyState = skipped
            ? JourneyState.skipped(fixture.settlement().id)
            : completedJourneyFixture(fixture.settlement().id);
        JourneyPresentationMode expected = skipped
            ? JourneyPresentationMode.SKIPPED : JourneyPresentationMode.COMPLETE;
        helper.assertTrue(fixture.settlement().journeyState.mode() == expected,
            "strictly decoded terminal presentation fixture must be valid");
        CompoundTag journeyBefore = fixture.settlement().journeyState.writeNbt();
        BlockPos chestPos = helper.absolutePos(new BlockPos(5, 1, 5));
        Container chest = chest(helper, new BlockPos(5, 1, 5));
        WorkerLogCapture capture = new WorkerLogCapture(fixture.settlement().id);
        try {
            List<UUID> actions = new ArrayList<>();
            List<UUID> receipts = new ArrayList<>();
            List<ItemStack> replayCopies = new ArrayList<>();
            for (int iteration = 0; iteration < 2; iteration++) {
                BlockPos stand = helper.absolutePos(new BlockPos(6, 1, 6));
                fixture.worker().moveTo(stand.getX() + 0.5D, stand.getY(),
                    stand.getZ() + 0.5D, 0.0F, 0.0F);
                PreparedLog physical = oneTerminalLog(helper, fixture);
                actions.add(physical.action());
                replayCopies.add(fixture.worker().bag.getItem(physical.bagSlot()).copy());
                helper.assertTrue(depositTaggedBag(helper, fixture, chest, chestPos) == 1,
                    "each new tree must move its one stamped physical log into linked storage");
                var actual = WorkerProvenanceSavedData.get(helper.getLevel())
                    .receiptsFor(physical.action());
                helper.assertTrue(actual.size() == 1, "each physical insert must own one receipt");
                receipts.add(actual.getFirst().id());
            }
            helper.assertTrue(!actions.getFirst().equals(actions.getLast())
                    && !receipts.getFirst().equals(receipts.getLast()),
                "second physical work must have distinct persisted action and receipt identities");
            assertWorkerLogs(helper, fixture, capture, actions, receipts);
            helper.assertTrue(journeyBefore.equals(fixture.settlement().journeyState.writeNbt()),
                "ordinary later work must not reopen or mutate completed/skipped Journey");
            long committedTick = helper.getLevel().getGameTime();
            helper.runAfterDelay(2, () -> {
                try {
                    helper.assertTrue(helper.getLevel().getGameTime() >= committedTick + 2,
                        "replay must cross real ticks so emitter deduplication cannot hide duplicates");
                    for (int index = 0; index < actions.size(); index++) {
                        UUID action = actions.get(index);
                        helper.assertTrue(!WorkerProvenanceService.commitLumberTree(
                            helper.getLevel(), fixture.settlement(), fixture.building(),
                            fixture.worker(), action), "terminal action may not commit twice");
                        // These public adapters re-observe real authority; they must not emit it again.
                        JourneyServerHooks.noteLumberTreeCommitted(helper.getLevel(),
                            fixture.settlement(), fixture.building(), fixture.worker(), action);
                        JourneyServerHooks.noteWorkplaceOutputCommitted(helper.getLevel(),
                            fixture.settlement(), fixture.building(), fixture.worker(), receipts.get(index));
                        ItemStack duplicate = replayCopies.get(index);
                        var retry = WorkerProvenanceService.depositOutput(helper.getLevel(),
                            fixture.settlement(), fixture.building(), fixture.worker(), chestPos, duplicate);
                        helper.assertTrue(retry.receipt() == null && retry.remainder().getCount() == 1,
                            "exhausted action cannot turn a replayed transit copy into another insert");
                        helper.assertTrue(WorkerProvenanceSavedData.get(helper.getLevel())
                            .receiptsFor(action).size() == 1, "receipt replay may not append authority");
                    }
                    UUID missing = UUID.randomUUID();
                    helper.assertTrue(!WorkerProvenanceService.commitLumberTree(helper.getLevel(),
                            fixture.settlement(), fixture.building(), fixture.worker(), missing)
                        && !JourneyServerHooks.noteLumberTreeCommitted(helper.getLevel(),
                            fixture.settlement(), fixture.building(), fixture.worker(), missing)
                        && !JourneyServerHooks.noteWorkplaceOutputCommitted(helper.getLevel(),
                            fixture.settlement(), fixture.building(), fixture.worker(), missing),
                        "missing persisted action/receipt identities must fail closed");
                    ItemStack untagged = new ItemStack(Items.OAK_LOG);
                    var invalid = WorkerProvenanceService.depositOutput(helper.getLevel(),
                        fixture.settlement(), fixture.building(), fixture.worker(), chestPos, untagged);
                    helper.assertTrue(invalid.receipt() == null && invalid.remainder().getCount() == 1,
                        "untagged output cannot mint a domain receipt");
                    assertWorkerLogs(helper, fixture, capture, actions, receipts);
                    helper.assertTrue(journeyBefore.equals(fixture.settlement().journeyState.writeNbt()),
                        "later valid re-observation and invalid attempts preserve exact Journey NBT");
                    helper.assertTrue(count(chest, Items.OAK_LOG) == 2
                            && fixture.worker().bag.isEmpty()
                            && fixture.worker().getMainHandItem().getDamageValue() == 2,
                        "two real logs and two axe uses remain conserved after all replay attempts");
                    helper.succeed();
                } finally {
                    capture.close();
                }
            });
        } catch (RuntimeException | Error failure) {
            capture.close();
            throw failure;
        }
    }

    private static void assertWorkerLogs(GameTestHelper helper, Fixture fixture,
                                         WorkerLogCapture capture,
                                         List<UUID> actions, List<UUID> receipts) {
        helper.assertTrue(capture.lines.size() == 4,
            "two actual tree commits and two real deposits must emit exactly four domain lines; got "
                + capture.lines.size());
        for (String line : capture.lines) {
            var parsed = AuthorityTelemetry.parse(line).orElseThrow();
            helper.assertTrue(parsed.result() == AuthorityTelemetry.Result.COMMITTED
                    && parsed.settlement().equals(fixture.settlement().id.toString()),
                "captured domain record must identify the real settlement");
            // Reason is deliberately bounded/truncated; resolve the full owner from
            // the exact logged action/receipt UUID instead of reading a clipped tail.
            UUID identity = UUID.fromString(parsed.target().substring(parsed.target().indexOf(':') + 1));
            WorkerProvenanceSavedData data = WorkerProvenanceSavedData.get(helper.getLevel());
            if (parsed.event() == AuthorityTelemetry.Event.LUMBER_TREE_COMMITTED) {
                var action = data.action(identity);
                helper.assertTrue(action != null
                        && action.workerId().equals(fixture.worker().getUUID())
                        && action.zone().buildingId().equals(fixture.building().id)
                        && action.zone().settlementId().equals(fixture.settlement().id),
                    "logged action must resolve to the exact persisted worker and building");
            } else {
                var receipt = data.receipt(identity);
                helper.assertTrue(receipt != null
                        && receipt.workerId().equals(fixture.worker().getUUID())
                        && receipt.buildingId().equals(fixture.building().id)
                        && receipt.settlementId().equals(fixture.settlement().id)
                        && parsed.item().equals(receipt.itemId().toString())
                        && parsed.itemBefore() == receipt.destinationBefore()
                        && parsed.itemAfter() == receipt.destinationAfter()
                        && parsed.itemExpectedDelta() == receipt.count(),
                    "logged insertion must match the exact persisted owner and physical item delta");
            }
        }
        for (UUID action : actions) {
            helper.assertTrue(capture.count(AuthorityTelemetry.Event.LUMBER_TREE_COMMITTED,
                    "action:" + action) == 1, "each distinct physical tree emits exactly once");
        }
        for (UUID receipt : receipts) {
            helper.assertTrue(capture.count(AuthorityTelemetry.Event.WORKPLACE_OUTPUT_COMMITTED,
                    "receipt:" + receipt) == 1, "each distinct physical insertion emits exactly once");
        }
    }

    /** Typed historical fixture only; does not bypass any current worker transaction. */
    private static JourneyState completedJourneyFixture(UUID settlement) {
        CompoundTag saved = JourneyState.fresh(settlement).writeNbt();
        ListTag evidence = new ListTag();
        ListTag completed = new ListTag();
        UUID actor = UUID.randomUUID();
        for (var step : JourneyDefinition.CURRENT.orderedSteps()) {
            UUID transaction = UUID.randomUUID();
            completed.add(StringTag.valueOf(step.id().toString()));
            for (var event : step.requiredEvents()) {
                evidence.add(new JourneyEvidence(step.id(), event, transaction, 0L,
                    settlement, Optional.of(actor), Optional.of(actor), Optional.of(actor),
                    Optional.of(actor), Optional.of("minecraft:oak_log#count=1"),
                    JourneySource.SURVIVAL, event.terminalOutcomeRequired()
                        ? JourneyOutcome.HELD : JourneyOutcome.NONE).writeNbt());
            }
        }
        saved.put("Evidence", evidence);
        saved.put("Completed", completed);
        saved.putInt("Revision", evidence.size());
        saved.putString("PresentationMode", "complete");
        saved.putString("Outcome", "held");
        saved.putString("CurrentChapter", JourneyIds.CHAPTER_FIRST_RAID.toString());
        return JourneyState.readNbt(saved, settlement);
    }

    /** Captures actual logger output, never calls the emitter or replaces its budget. */
    private static final class WorkerLogCapture extends AbstractAppender implements AutoCloseable {
        private final org.apache.logging.log4j.core.config.LoggerConfig loggerConfig;
        private final UUID settlement;
        private final List<String> lines = new ArrayList<>();

        private WorkerLogCapture(UUID settlement) {
            super("hsqa_worker_" + settlement, null, PatternLayout.createDefaultLayout(),
                false, Property.EMPTY_ARRAY);
            this.settlement = settlement;
            var logger = (org.apache.logging.log4j.core.Logger) LogManager.getLogger(Hearthstead.MODID);
            // Attach to the existing effective configuration. Logger.addAppender
            // can create a named non-additive config and strand normal output.
            loggerConfig = logger.getContext().getConfiguration().getLoggerConfig(Hearthstead.MODID);
            start();
            loggerConfig.addAppender(this, null, null);
        }

        @Override public void append(LogEvent event) {
            if (!Hearthstead.MODID.equals(event.getLoggerName())) return;
            String line = event.getMessage().getFormattedMessage();
            if (line.startsWith("HEARTHSTEAD_AUTHORITY_V1 ")
                && line.contains(" settlement=" + settlement + " ")
                && (line.contains(" event=LUMBER_TREE_COMMITTED ")
                    || line.contains(" event=WORKPLACE_OUTPUT_COMMITTED "))) {
                lines.add(line);
            }
        }

        private long count(AuthorityTelemetry.Event event, String target) {
            return lines.stream().map(line -> AuthorityTelemetry.parse(line).orElseThrow())
                .filter(row -> row.event() == event && row.target().equals(target)).count();
        }

        @Override public void close() {
            loggerConfig.removeAppender(getName());
            stop();
        }
    }

    private static PreparedLog oneTerminalLog(GameTestHelper helper,
                                               Fixture fixture) {
        BlockPos root = helper.absolutePos(new BlockPos(8, 2, 8));
        naturalTree(helper, root, 1);
        UUID action = WorkerProvenanceService.beginLumberTree(helper.getLevel(),
            fixture.settlement(), fixture.building(), fixture.worker(),
            fixture.building().workZone().orElseThrow(), root, List.of(root));
        helper.assertTrue(action != null && WorkerProvenanceService.prepareLumberLog(
            helper.getLevel(), fixture.settlement(), fixture.building(),
            fixture.worker(), action, root), "one-log action setup must prepare");
        ItemStack stack = new ItemStack(Items.OAK_LOG);
        helper.assertTrue(WorkerProvenanceService.stampOutput(helper.getLevel(),
            fixture.settlement(), fixture.building(), fixture.worker(), action,
            stack, root), "one-log output must stamp");
        helper.getLevel().setBlockAndUpdate(root, Blocks.AIR.defaultBlockState());
        helper.assertTrue(WorkerProvenanceService.completeLumberLog(
                helper.getLevel(), fixture.settlement(), fixture.building(),
                fixture.worker(), action, root, stack)
                && WorkerProvenanceService.commitLumberTree(helper.getLevel(),
                    fixture.settlement(), fixture.building(), fixture.worker(),
                    action), "one-log action must terminal-commit");
        ItemStack leftover = fixture.worker().bag.addItem(stack.copy());
        helper.assertTrue(leftover.isEmpty(),
            "terminal tagged log must remain physically in worker bag");
        int slot = taggedSlot(fixture.worker(), action);
        helper.assertTrue(slot >= 0, "physical tagged log must be discoverable");
        return new PreparedLog(action, slot);
    }

    private static int depositTaggedBag(GameTestHelper helper, Fixture fixture,
                                        Container chest, BlockPos target) {
        // Deposit authority now requires the same physical contact a real
        // Farmer/Courier has at its terminal chest. This fixture is a direct
        // service test, so make that prerequisite explicit rather than
        // retaining its old implicit remote-insert shortcut.
        fixture.worker().moveTo(target.getX() + 0.5D, target.getY(),
            target.getZ() - 0.5D, fixture.worker().getYRot(),
            fixture.worker().getXRot());
        helper.assertTrue(ContainerApproach.inspect(helper.getLevel(),
                fixture.worker(), target).canInteract(),
            "fixture must place the worker at real linked-storage contact before deposit");
        int inserted = 0;
        for (int slot = 0; slot < fixture.worker().bag.getContainerSize(); slot++) {
            ItemStack stack = fixture.worker().bag.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            int before = stack.getCount();
            var result = WorkerProvenanceService.depositOutput(helper.getLevel(),
                fixture.settlement(), fixture.building(), fixture.worker(),
                target, stack);
            fixture.worker().bag.setItem(slot, result.remainder());
            inserted += before - result.remainder().getCount();
        }
        return inserted;
    }

    private static Fixture fixture(GameTestHelper helper, BuildingType type,
                                   Profession profession, Item tool) {
        helper.getLevel().setDayTime(2000);
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Provenance", helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 48;
        SettlementSavedData saved = SettlementSavedData.get(helper.getLevel());
        saved.settlements.put(settlement.id, settlement);
        saved.setDirty();
        Building building = GameTestFixtures.register(helper, settlement, type,
            2, 2);
        SettlerEntity worker = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(6, 1, 6));
        worker.bindTo(settlement.id, settlement.center);
        worker.setSettlerName("Receipt Worker");
        settlement.putRecord(worker.getUUID(), "Receipt Worker", Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, building,
            worker).ok(), "fixture worker must hire into exact building");
        helper.assertTrue(worker.getProfession() == profession,
            "fixture building must assign expected profession");
        worker.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(tool));
        return new Fixture(settlement, building, worker);
    }

    private static Container chest(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, Blocks.CHEST);
        return (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(relative));
    }

    private static void naturalTree(GameTestHelper helper, BlockPos root,
                                    int logs) {
        helper.getLevel().setBlockAndUpdate(root.below(),
            Blocks.DIRT.defaultBlockState());
        for (int index = 0; index < logs; index++) {
            helper.getLevel().setBlockAndUpdate(root.above(index),
                Blocks.OAK_LOG.defaultBlockState());
        }
        BlockPos canopy = root.above(logs);
        for (BlockPos leaf : List.of(canopy.north(), canopy.south(),
                canopy.east(), canopy.west())) {
            helper.getLevel().setBlockAndUpdate(leaf,
                Blocks.OAK_LEAVES.defaultBlockState());
        }
    }

    private static ItemStack tagged(SettlerEntity worker,
                                    WorkerStackProvenance.TransitKind kind) {
        for (int slot = 0; slot < worker.bag.getContainerSize(); slot++) {
            ItemStack stack = worker.bag.getItem(slot);
            if (WorkerStackProvenance.readTransit(stack)
                    .filter(transit -> transit.kind() == kind).isPresent()) {
                return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static int taggedSlot(SettlerEntity worker, UUID action) {
        for (int slot = 0; slot < worker.bag.getContainerSize(); slot++) {
            if (WorkerStackProvenance.readTransit(worker.bag.getItem(slot))
                    .filter(transit -> transit.actionId().equals(action))
                    .isPresent()) {
                return slot;
            }
        }
        return -1;
    }

    private static void removeOneTagged(SettlerEntity worker, UUID action) {
        for (int slot = 0; slot < worker.bag.getContainerSize(); slot++) {
            ItemStack stack = worker.bag.getItem(slot);
            if (WorkerStackProvenance.readTransit(stack)
                    .filter(transit -> transit.actionId().equals(action))
                    .isPresent()) {
                worker.bag.removeItem(slot, 1);
                return;
            }
        }
    }

    private static ItemStack first(Container container, Item item) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                return container.getItem(slot);
            }
        }
        return ItemStack.EMPTY;
    }

    private static int count(Container container, Item item) {
        int count = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int total(List<ItemStack> stacks) {
        int count = 0;
        for (ItemStack stack : stacks) {
            count += stack.getCount();
        }
        return count;
    }

    private record Fixture(Settlement settlement, Building building,
                           SettlerEntity worker) {
    }

    private record PreparedLog(UUID action, int bagSlot) {
    }
}
