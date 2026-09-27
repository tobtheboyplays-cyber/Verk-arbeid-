package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.FarmerWorkGoal;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.work.ContainerApproach;
import com.hearthstead.settlement.work.FarmWorkApproach;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData;
import com.hearthstead.settlement.work.WorkerProvenanceService;
import com.hearthstead.settlement.work.WorkerStackProvenance;
import com.hearthstead.settlement.workzone.WorkZone;
import com.hearthstead.settlement.workzone.WorkZoneService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FarmBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * Adversarial regressions around the Farmer's exact-source input route and
 * fail-closed logistics recovery. These deliberately drive the real goal
 * object one selection/contact at a time: the assertions pin state-machine
 * boundaries without depending on an unrelated navigation timeout.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class FarmerRecoveryAdversarialGameTests {
    private static final BlockPos PRIMARY_CHEST = new BlockPos(10, 1, 10);
    private static final BlockPos SECONDARY_CHEST = new BlockPos(11, 1, 11);
    private static final BlockPos BARE_TILE = new BlockPos(7, 0, 7);

    private record Fixture(Settlement settlement, Building farmhouse,
                           SettlerEntity farmer, Container primary) {
    }

    private static void buildArena(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                boolean rim = x == 0 || z == 0 || x == 15 || z == 15;
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
                for (int y = 1; y <= 4; y++) {
                    helper.setBlock(new BlockPos(x, y, z),
                        rim && y <= 2 ? Blocks.STONE_BRICKS.defaultBlockState()
                            : Blocks.AIR.defaultBlockState());
                }
            }
        }
    }

    private static Fixture fixture(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        buildArena(helper);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(),
            "Adversarial Farm", helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 20;
        data.settlements.put(settlement.id, settlement);
        data.setDirty();

        Building farmhouse = GameTestFixtures.register(helper, settlement,
            BuildingType.FARMHOUSE, 8, 8);
        Container primary = chest(helper, PRIMARY_CHEST);
        SettlerEntity farmer = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(7, 1, 7));
        farmer.setSettlerName("Astrid Adversarial");
        farmer.bindTo(settlement.id, settlement.center);
        settlement.putRecord(farmer.getUUID(), farmer.getSettlerName(),
            Profession.NONE);
        helper.assertTrue(Employment.hire(helper.getLevel(), settlement,
                farmhouse, farmer).ok(),
            "fixture: exact Farmhouse must hire the adversarial Farmer");
        farmer.attributes().pinForTest(com.hearthstead.entity.Attribute.DEXTERITY,
            10);
        farmer.setNoAi(true);
        return new Fixture(settlement, farmhouse, farmer, primary);
    }

    private static Container chest(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, Blocks.CHEST);
        Container container = (Container) helper.getLevel().getBlockEntity(
            helper.absolutePos(relative));
        helper.assertTrue(container != null,
            "fixture: expected a real loaded chest at " + relative);
        return container;
    }

    private static int count(Container container, Item item) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) {
                total += container.getItem(slot).getCount();
            }
        }
        return total;
    }

    private static int bagCount(SettlerEntity farmer, Item item) {
        int total = 0;
        for (int slot = 0; slot < farmer.bag.getContainerSize(); slot++) {
            if (farmer.bag.getItem(slot).is(item)) {
                total += farmer.bag.getItem(slot).getCount();
            }
        }
        return total;
    }

    private static void giveServiceableHoe(SettlerEntity farmer) {
        farmer.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_HOE));
    }

    private static void placeAtContact(GameTestHelper helper,
                                       SettlerEntity farmer,
                                       BlockPos relativeChest) {
        BlockPos target = helper.absolutePos(relativeChest);
        farmer.moveTo(target.getX() + 0.5D, target.getY(),
            target.getZ() - 0.5D, farmer.getYRot(), farmer.getXRot());
        helper.assertTrue(ContainerApproach.inspect(helper.getLevel(), farmer,
                target).canInteract(),
            "fixture: Farmer must stand at physical chest contact for "
                + relativeChest);
    }

    private static FarmerWorkGoal selectBareTileInput(GameTestHelper helper,
                                                       Fixture fixture) {
        helper.setBlock(BARE_TILE,
            Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
        helper.setBlock(BARE_TILE.above(), Blocks.AIR);
        FarmerWorkGoal goal = new FarmerWorkGoal(fixture.farmer());
        helper.assertTrue(goal.canUse(),
            "fixture: bare field and linked seed stock must select one input route");
        goal.start();
        helper.assertTrue(fixture.farmer().getActivity()
                == SettlerActivity.COLLECTING_ITEMS,
            "fixture: selected Farmer input route must enter COLLECTING_ITEMS");
        return goal;
    }

    /**
     * Source selection is sticky. If that exact chest empties after selection,
     * contact may fail, but the same attempt must not silently withdraw from a
     * different Farmhouse chest.
     */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "farmer_input_adversarial")
    public void selectedInputChestEmptiesWithoutRemoteFallback(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        Container secondary = chest(helper, SECONDARY_CHEST);
        fixture.primary().setItem(0, new ItemStack(Items.WHEAT_SEEDS));
        secondary.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 4));
        giveServiceableHoe(fixture.farmer());

        FarmerWorkGoal goal = selectBareTileInput(helper, fixture);
        fixture.primary().setItem(0, ItemStack.EMPTY);
        fixture.primary().setChanged();
        placeAtContact(helper, fixture.farmer(), PRIMARY_CHEST);
        goal.tick();

        helper.assertTrue(count(fixture.primary(), Items.WHEAT_SEEDS) == 0
                && count(secondary, Items.WHEAT_SEEDS) == 4
                && bagCount(fixture.farmer(), Items.WHEAT_SEEDS) == 0,
            "an emptied selected source must be a strict no-op; another remote "
                + "chest may not satisfy the in-flight route");
        helper.assertTrue(!goal.canContinueToUse(),
            "failed exact-source contact must terminate its bounded attempt");
        helper.succeed();
    }

    /** A removed input container must clear TO_INPUT instead of spinning. */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "farmer_input_adversarial")
    public void failedInputAcquisitionCannotLeaveStaleToInputLoop(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        fixture.primary().setItem(0, new ItemStack(Items.WHEAT_SEEDS));
        giveServiceableHoe(fixture.farmer());

        FarmerWorkGoal goal = selectBareTileInput(helper, fixture);
        helper.setBlock(PRIMARY_CHEST, Blocks.AIR);
        goal.tick();

        helper.assertTrue(!goal.canContinueToUse()
                && fixture.farmer().getActivity() == SettlerActivity.IDLE
                && bagCount(fixture.farmer(), Items.WHEAT_SEEDS) == 0,
            "INVALID_TARGET must leave neither stale TO_INPUT continuation nor "
                + "a synthetic seed (activity=" + fixture.farmer().getActivity()
                + ", route=" + fixture.farmer().routeFailureNote() + ")");
        helper.succeed();
    }

    /** Missing zone authority cannot strand already-produced physical cargo. */
    @GameTest(template = "empty16", timeoutTicks = 240,
        batch = "farmer_storage_adversarial")
    public void subThresholdProduceRoutesWhenFarmZoneIsMissing(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        CompoundTag tag = fixture.farmhouse().writeNbt();
        tag.remove("WorkZone");
        tag.putInt("WorkZoneRevision", 0);
        tag.putBoolean("WorkZoneQuarantined", false);
        Building noZone = replaceBuilding(fixture, Building.readNbt(tag));
        helper.assertTrue(noZone.workZone().isEmpty()
                && !noZone.workZoneQuarantined(),
            "fixture: replacement must be truthful revision-zero NO_WORK_ZONE");
        assertSubThresholdDeposit(helper, fixture, noZone);
    }

    /** Quarantine blocks field mutation, but must not erase physical output. */
    @GameTest(template = "empty16", timeoutTicks = 240,
        batch = "farmer_storage_adversarial")
    public void subThresholdProduceRoutesWhenFarmZoneIsQuarantined(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        CompoundTag tag = fixture.farmhouse().writeNbt();
        tag.putBoolean("WorkZoneQuarantined", true);
        Building quarantined = replaceBuilding(fixture, Building.readNbt(tag));
        helper.assertTrue(quarantined.workZoneQuarantined()
                && quarantined.workZone().isEmpty(),
            "fixture: replacement must preserve sticky Work Zone quarantine");
        assertSubThresholdDeposit(helper, fixture, quarantined);
    }

    /** A revision change invalidates the old scan, not its physical produce. */
    @GameTest(template = "empty16", timeoutTicks = 240,
        batch = "farmer_storage_adversarial")
    public void subThresholdProduceRoutesWhenActiveFarmZoneIsSuperseded(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        giveServiceableHoe(fixture.farmer());
        helper.setBlock(BARE_TILE,
            Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
        helper.setBlock(BARE_TILE.above(), Blocks.WHEAT.defaultBlockState()
            .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7));
        FarmerWorkGoal goal = new FarmerWorkGoal(fixture.farmer());
        helper.assertTrue(goal.canUse(),
            "fixture: revision-one field must establish an active survey/target");

        WorkZone replacement = WorkZone.between(fixture.settlement().id,
            fixture.farmhouse().id, WorkZone.Type.FARM,
            helper.getLevel().dimension().location(),
            helper.absolutePos(BlockPos.ZERO),
            helper.absolutePos(new BlockPos(15, 15, 15)), 2);
        helper.assertTrue(fixture.farmhouse().commitWorkZone(1, replacement),
            "fixture: the live Farm Zone must advance to revision two");
        fixture.farmer().bag.addItem(new ItemStack(Items.WHEAT, 3));
        placeAtContact(helper, fixture.farmer(), PRIMARY_CHEST);

        helper.assertTrue(goal.canUse(),
            "superseded scan with real produce must select storage immediately");
        goal.start();
        observeThreeUnitDeposit(helper,fixture,goal);
    }

    /**
     * A goal reconstructed after entity reload must return an invalid target's
     * exact tagged input to the exact chest recorded by the persisted action.
     * The hoe is deliberately gone at restart: recovery is logistics, not a
     * new field mutation, and may not be blocked behind equipment acquisition.
     */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "farmer_input_adversarial")
    public void invalidPersistedPlantTargetReturnsExactSeedToExactSource(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        Container secondary = chest(helper, SECONDARY_CHEST);
        ItemStack founderSeed = new ItemStack(Items.WHEAT_SEEDS);
        founderSeed.set(DataComponents.CUSTOM_NAME,
            Component.literal("Founder Seed"));
        fixture.primary().setItem(0, founderSeed);
        secondary.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 7));
        giveServiceableHoe(fixture.farmer());
        placeAtContact(helper, fixture.farmer(), PRIMARY_CHEST);

        BlockPos planned = helper.absolutePos(BARE_TILE.above());
        UUID action = WorkerProvenanceService.supplyOneSeedAt(helper.getLevel(),
            fixture.settlement(), fixture.farmhouse(), fixture.farmer(),
            helper.absolutePos(PRIMARY_CHEST),
            stack -> stack.is(Items.WHEAT_SEEDS), planned);
        helper.assertTrue(action != null
                && count(fixture.primary(), Items.WHEAT_SEEDS) == 0
                && bagCount(fixture.farmer(), Items.WHEAT_SEEDS) == 1,
            "fixture: exact source seed must become one persisted tagged input");
        helper.setBlock(BARE_TILE.above(), Blocks.STONE);
        fixture.farmer().setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);

        SettlerEntity reloaded = reload(helper, fixture.farmer());
        reloaded.setNoAi(true);
        helper.assertTrue(reloaded.getMainHandItem().isEmpty(),
            "fixture: restart recovery must not inherit a serviceable hoe");
        placeAtContact(helper, reloaded, PRIMARY_CHEST);
        FarmerWorkGoal resumed = new FarmerWorkGoal(reloaded);
        helper.assertTrue(resumed.canUse(),
            "invalid persisted target must select exact return even without a hoe");
        resumed.start();
        resumed.tick();

        ItemStack returned = fixture.primary().getItem(0);
        helper.assertTrue(returned.is(Items.WHEAT_SEEDS)
                && returned.getHoverName().getString().equals("Founder Seed")
                && WorkerStackProvenance.readTransit(returned).isEmpty(),
            "the returned unit must be the exact named seed with transit cleared");
        helper.assertTrue(count(fixture.primary(), Items.WHEAT_SEEDS) == 1
                && count(secondary, Items.WHEAT_SEEDS) == 7
                && bagCount(reloaded, Items.WHEAT_SEEDS) == 0
                && WorkerProvenanceSavedData.get(helper.getLevel())
                    .action(action) == null,
            "invalid-target recovery must return 1+7 exact seed matter with no "
                + "duplication, loss, remote deposit or live pending action");
        helper.succeed();
    }

    /**
     * Revision drift is another invalid-target seam, not permission to
     * reinterpret tagged input as ordinary output. The seed is withdrawn
     * under revision one; revision two excludes its paid target while keeping
     * the same Farmhouse and physical source chest. A fresh goal must recover
     * the old action to that exact source, never the other linked chest.
     */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "farmer_input_adversarial")
    public void supersededZoneReturnsRevisionOneSeedToExactOriginalSource(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        Container secondary = chest(helper, SECONDARY_CHEST);
        ItemStack revisionOneSeed = new ItemStack(Items.WHEAT_SEEDS);
        revisionOneSeed.set(DataComponents.CUSTOM_NAME,
            Component.literal("Revision One Seed"));
        fixture.primary().setItem(0, revisionOneSeed);
        secondary.setItem(0, new ItemStack(Items.WHEAT_SEEDS, 7));
        giveServiceableHoe(fixture.farmer());
        placeAtContact(helper, fixture.farmer(), PRIMARY_CHEST);

        BlockPos oldTarget = helper.absolutePos(BARE_TILE.above());
        UUID action = WorkerProvenanceService.supplyOneSeedAt(helper.getLevel(),
            fixture.settlement(), fixture.farmhouse(), fixture.farmer(),
            helper.absolutePos(PRIMARY_CHEST),
            stack -> stack.is(Items.WHEAT_SEEDS), oldTarget);
        helper.assertTrue(action != null
                && count(fixture.primary(), Items.WHEAT_SEEDS) == 0
                && bagCount(fixture.farmer(), Items.WHEAT_SEEDS) == 1,
            "fixture: revision-one action must own exactly one named input seed");

        WorkZone revisionTwo = WorkZone.between(fixture.settlement().id,
            fixture.farmhouse().id, WorkZone.Type.FARM,
            helper.getLevel().dimension().location(),
            helper.absolutePos(new BlockPos(8, 0, 8)),
            helper.absolutePos(new BlockPos(15, 15, 15)), 2);
        helper.assertTrue(fixture.farmhouse().commitWorkZone(1, revisionTwo)
                && !WorkZoneService.livePositionAllowed(helper.getLevel(),
                    revisionTwo, oldTarget),
            "fixture: revision two must supersede revision one and exclude its target");

        FarmerWorkGoal resumed = new FarmerWorkGoal(fixture.farmer());
        helper.assertTrue(resumed.canUse(),
            "fresh Farmer goal must select recovery for superseded input action");
        resumed.start();
        resumed.tick();

        ItemStack returned = fixture.primary().getItem(0);
        helper.assertTrue(returned.is(Items.WHEAT_SEEDS)
                && returned.getHoverName().getString().equals("Revision One Seed")
                && WorkerStackProvenance.readTransit(returned).isEmpty(),
            "revision-one input must return as the exact named, untagged seed");
        helper.assertTrue(count(fixture.primary(), Items.WHEAT_SEEDS) == 1
                && count(secondary, Items.WHEAT_SEEDS) == 7
                && bagCount(fixture.farmer(), Items.WHEAT_SEEDS) == 0
                && WorkerProvenanceSavedData.get(helper.getLevel())
                    .action(action) == null,
            "revision recovery must conserve 1+7 seeds at their exact sources "
                + "and remove the old pending action without duplication");
        helper.succeed();
    }

    /**
     * A crop committed under the previous Farm Zone remains authenticated
     * cargo after an identical re-confirmation. It may enter only the same
     * live Farmhouse through physical contact; an unrelated transit row must
     * still remain untouched.
     */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "farmer_storage_adversarial")
    public void terminalHarvestSurvivesSameFarmhouseZoneReconfirmation(
        GameTestHelper helper) {
        Fixture fixture = fixture(helper);
        giveServiceableHoe(fixture.farmer());
        BlockPos cropRelative = new BlockPos(12, 1, 12);
        BlockPos crop = helper.absolutePos(cropRelative);
        helper.setBlock(cropRelative.below(), Blocks.FARMLAND.defaultBlockState()
            .setValue(FarmBlock.MOISTURE, 7));
        helper.setBlock(cropRelative, Blocks.CARROTS.defaultBlockState()
            .setValue(net.minecraft.world.level.block.CropBlock.AGE, 7));
        fixture.farmer().moveTo(crop.getX() + 0.5D, crop.getY(),
            crop.getZ() - 1.5D, fixture.farmer().getYRot(),
            fixture.farmer().getXRot());
        helper.assertTrue(FarmWorkApproach.canContact(helper.getLevel(),
                fixture.farmer(), crop, FarmWorkApproach.Contact.PLANT),
            "fixture: Farmer must have a real harvest contact before ownership");

        UUID action = WorkerProvenanceService.beginFarmHarvest(helper.getLevel(),
            fixture.settlement(), fixture.farmhouse(), fixture.farmer(), crop);
        ItemStack carried = new ItemStack(Items.CARROT);
        helper.assertTrue(action != null && WorkerProvenanceService.stampOutput(
                helper.getLevel(), fixture.settlement(), fixture.farmhouse(),
                fixture.farmer(), action, carried, crop),
            "fixture: one carrot must be stamped while the old Farm Zone is live");
        helper.setBlock(cropRelative, Blocks.AIR);
        helper.assertTrue(WorkerProvenanceService.commitFarmHarvest(
                helper.getLevel(), fixture.settlement(), fixture.farmhouse(),
                fixture.farmer(), action, crop, java.util.List.of(carried)),
            "fixture: source removal must make the old-zone action terminal");
        fixture.farmer().bag.setItem(0, carried);

        WorkZone oldZone = fixture.farmhouse().workZone().orElseThrow();
        WorkZone reconfirmed = WorkZone.between(fixture.settlement().id,
            fixture.farmhouse().id, WorkZone.Type.FARM,
            helper.getLevel().dimension().location(), oldZone.min(), oldZone.max(),
            oldZone.revision() + 1);
        helper.assertTrue(fixture.farmhouse().commitWorkZone(oldZone.revision(),
                reconfirmed),
            "fixture: same Farmhouse may advance only to the next zone revision");
        placeAtContact(helper, fixture.farmer(), PRIMARY_CHEST);

        ItemStack foreign = new ItemStack(Items.CARROT);
        helper.assertTrue(WorkerStackProvenance.stampTransit(foreign,
                UUID.randomUUID(), WorkerStackProvenance.TransitKind.FARM_CROP,
                fixture.settlement().id, fixture.farmhouse().id,
                fixture.farmer().getUUID(), helper.getLevel().dimension().location(),
                crop.asLong()), "fixture: foreign transit row must be well-formed");
        WorkerProvenanceService.DepositResult rejected =
            WorkerProvenanceService.depositOutput(helper.getLevel(),
                fixture.settlement(), fixture.farmhouse(), fixture.farmer(),
                helper.absolutePos(PRIMARY_CHEST), foreign);
        helper.assertTrue(rejected.receipt() == null
                && rejected.remainder().getCount() == 1
                && count(fixture.primary(), Items.CARROT) == 0,
            "a foreign action remains blocked despite the recovery exception");

        ItemStack live = fixture.farmer().bag.getItem(0);
        WorkerProvenanceService.DepositResult deposited =
            WorkerProvenanceService.depositOutput(helper.getLevel(),
                fixture.settlement(), fixture.farmhouse(), fixture.farmer(),
                helper.absolutePos(PRIMARY_CHEST), live);
        fixture.farmer().bag.setItem(0, deposited.remainder());
        WorkerProvenanceSavedData.ActionView finalAction =
            WorkerProvenanceSavedData.get(helper.getLevel()).action(action);
        helper.assertTrue(deposited.receipt() != null
                && deposited.remainder().isEmpty()
                && count(fixture.primary(), Items.CARROT) == 1
                && bagCount(fixture.farmer(), Items.CARROT) == 0
                && finalAction != null && finalAction.remaining(
                    net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(Items.CARROT)) == 0,
            "the exact terminal carrot must deposit once after zone revision drift");
        helper.succeed();
    }

    private static Building replaceBuilding(Fixture fixture,
                                            Building replacement) {
        int index = fixture.settlement().buildings.indexOf(fixture.farmhouse());
        if (index < 0) {
            throw new IllegalStateException("fixture Farmhouse was not registered");
        }
        fixture.settlement().buildings.set(index, replacement);
        return replacement;
    }

    private static void assertSubThresholdDeposit(GameTestHelper helper,
                                                  Fixture fixture,
                                                  Building activeFarmhouse) {
        helper.assertTrue(Employment.employerOf(fixture.settlement(),
                fixture.farmer().getUUID()) == activeFarmhouse,
            "fixture: replacement Farmhouse must remain exact employer authority");
        fixture.farmer().bag.addItem(new ItemStack(Items.WHEAT, 3));
        placeAtContact(helper, fixture.farmer(), PRIMARY_CHEST);
        FarmerWorkGoal goal = new FarmerWorkGoal(fixture.farmer());
        helper.assertTrue(goal.canUse(),
            "sub-threshold physical produce must select storage before zone work");
        goal.start();
        observeThreeUnitDeposit(helper,fixture,goal);
    }

    private static void observeThreeUnitDeposit(GameTestHelper helper,Fixture fixture,FarmerWorkGoal goal) {
        int[] previous={0};
        helper.onEachTick(() -> {
            goal.tick(); // Existing explicit-goal fixture: once per actual world tick, never synthetic clock acceleration.
            int stored=count(fixture.primary(),Items.WHEAT);
            helper.assertTrue(stored+bagCount(fixture.farmer(),Items.WHEAT)==3,"all three original cargo units stay owned exactly once");
            if(stored!=previous[0])helper.assertTrue(stored-previous[0]==1
                &&fixture.farmer().bagTransferPresentation().clock()==48
                &&fixture.farmer().bagTransferPresentation().committed(),"each actual unit transfers at contact48");
            previous[0]=stored;
            if(stored==3&&!fixture.farmer().bagTransferPresentation().active())helper.succeed();
        });
    }

    private static SettlerEntity reload(GameTestHelper helper,
                                        SettlerEntity original) {
        ServerLevel level = helper.getLevel();
        UUID id = original.getUUID();
        CompoundTag saved = original.saveWithoutId(new CompoundTag());
        original.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        SettlerEntity replacement = ModEntities.SETTLER.get().create(level);
        helper.assertTrue(replacement != null,
            "fixture: reloaded Farmer entity must be constructible");
        replacement.load(saved);
        helper.assertTrue(id.equals(replacement.getUUID())
                && level.addFreshEntity(replacement),
            "entity reload must preserve UUID and re-enter the ServerLevel");
        return replacement;
    }
}
