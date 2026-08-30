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

/** Adversarial physical-authority tests for worker provenance. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class WorkerProvenanceGameTests {

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
            "terminal insertion must strip transit data so ordinary logs stack");
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
                && WorkerStackProvenance.readTransit(wrongBearing).isEmpty(),
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
