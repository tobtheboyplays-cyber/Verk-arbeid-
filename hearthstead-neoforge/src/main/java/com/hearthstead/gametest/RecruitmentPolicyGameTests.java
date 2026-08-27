package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Costs;
import com.hearthstead.settlement.ReadyFood;
import com.hearthstead.settlement.RecruitmentPolicy;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * R2 recruitment policy acceptance tests. These pin externally observable
 * boundaries (real stacks, stable wire ids, elapsed seconds and saved tags),
 * rather than restating the policy's internal branches.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RecruitmentPolicyGameTests {
    private static final BlockPos HEARTH = new BlockPos(6, 1, 6);
    private static final BlockPos TAVERN = new BlockPos(10, 1, 10);

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    private static Settlement settlement(GameTestHelper helper, boolean withHearth) {
        floor(helper);
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        var arena = helper.getBounds();
        data.settlements.values().removeIf(old -> arena.contains(
            old.center.getX() + 0.5, old.center.getY() + 0.5,
            old.center.getZ() + 0.5));
        if (withHearth) {
            helper.setBlock(HEARTH, ModBlocks.HEARTH.get());
        }
        Settlement s = new Settlement(UUID.randomUUID(), "Matvik",
            helper.absolutePos(HEARTH));
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        if (withHearth && helper.getLevel().getBlockEntity(s.center)
            instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(s.id);
        }
        return s;
    }

    private static HearthBlockEntity hearth(GameTestHelper helper) {
        return (HearthBlockEntity) helper.getLevel()
            .getBlockEntity(helper.absolutePos(HEARTH));
    }

    private static Building tavern(GameTestHelper helper, Settlement s) {
        BlockPos anchor = helper.absolutePos(TAVERN);
        return GameTestFixtures.registerWithBounds(helper, s, BuildingType.TAVERN,
            TAVERN, TAVERN.above(),
            BoundingBox.fromCorners(anchor, anchor.offset(2, 2, 2)));
    }

    private static void seedFullBoundary(HearthBlockEntity hearth) {
        hearth.insertGoods(new ItemStack(Items.BREAD, 12));
        hearth.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
    }

    private static RecruitmentPolicy.Assessment assess(GameTestHelper helper,
                                                        Settlement settlement,
                                                        RecruitmentPolicy.Stage stage) {
        return RecruitmentPolicy.assess(helper.getLevel(), settlement, stage);
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

    private static void addResidents(Settlement s, int count) {
        for (int i = 0; i < count; i++) {
            s.putRecord(UUID.randomUUID(), "Beboer " + i, Profession.NONE);
        }
    }

    private static Settlement eligible(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s);
        s.moraleCache = 60;
        seedFullBoundary(hearth(helper));
        return s;
    }

    // ------------------------------------------------ transaction boundaries

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void exactPostPaymentBoundaryPasses(GameTestHelper helper) {
        Settlement s = eligible(helper);
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.ATTRACTION);
        helper.assertTrue(a.eligible(), "12 bread + 8 planks must leave exactly 8 meals");
        helper.assertTrue(a.readyFoodAfterPrice() == 8 && a.requiredReadyFood() == 8
            && a.missingReadyFood() == 0,
            "exact boundary must report after=8 required=8 missing=0");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void oneMealBelowPostPaymentBoundaryFails(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s);
        hearth(helper).insertGoods(new ItemStack(Items.BREAD, 11));
        hearth(helper).insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.ATTRACTION);
        helper.assertTrue(a.blocker() == RecruitmentPolicy.Blocker.INSUFFICIENT_READY_FOOD,
            "seven meals after price must fail on the reserve blocker");
        helper.assertTrue(a.readyFoodAfterPrice() == 7 && a.missingReadyFood() == 1,
            "one-below boundary must report exactly one missing meal");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void discountedPriceIsSimulatedBeforeReserve(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        Building tavern = tavern(helper, s);
        tavern.workers.add(UUID.randomUUID());
        hearth(helper).insertGoods(new ItemStack(Items.BREAD, 11));
        hearth(helper).insertGoods(new ItemStack(Items.OAK_PLANKS, 6));
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.ATTRACTION);
        helper.assertTrue(a.price().lines().get(0).count() == 3
            && a.price().lines().get(1).count() == 6,
            "innkeeper price must be 3 bread + 6 planks");
        helper.assertTrue(a.eligible() && a.readyFoodAfterPrice() == 8,
            "discounted bread charge must leave the exact reserve");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void nonFoodStacksDoNotCountAsReadyMeals(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s);
        hearth(helper).insertGoods(new ItemStack(Items.BREAD, 4));
        hearth(helper).insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        hearth(helper).insertGoods(new ItemStack(Items.IRON_INGOT, 64));
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.ATTRACTION);
        helper.assertTrue(a.readyFoodAfterPrice() == 0
            && a.blocker() == RecruitmentPolicy.Blocker.INSUFFICIENT_READY_FOOD,
            "iron cannot masquerade as a communal meal");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void foodIngredientsThatCannotBeEatenAreExcluded(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s);
        hearth(helper).insertGoods(new ItemStack(Items.BREAD, 4));
        hearth(helper).insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        hearth(helper).insertGoods(new ItemStack(Items.WHEAT, 64));
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.ATTRACTION);
        helper.assertTrue(!ReadyFood.isReadyMeal(new ItemStack(Items.WHEAT)),
            "fixture sanity: wheat itself is not directly edible");
        helper.assertTrue(a.readyFoodAfterPrice() == 0,
            "food ingredients must not count until crafted into an edible stack");
        helper.succeed();
    }

    /**
     * Future prices may name an edible tag rather than one exact item. A
     * hearth full of unrelated meals must still expose that line as missing,
     * and any concrete edible tag member a warehouse offers must satisfy the
     * same projection. This pins the generic path without changing today's
     * bread-and-planks recruit table.
     */
    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void edibleTagPriceNeedSurvivesWrongMealScalarTarget(GameTestHelper helper) {
        var inventory = new net.neoforged.neoforge.items.ItemStackHandler(4);
        inventory.setStackInSlot(0, new ItemStack(Items.BAKED_POTATO, 20));
        Costs.Price fishPrice = Costs.of(Costs.PriceKey.RECRUIT,
            Costs.Line.ofTag(ItemTags.FISHES, 4));

        var needs = RecruitmentPolicy.missingReadyFoodPrices(inventory, fishPrice);
        helper.assertTrue(needs.size() == 1 && needs.get(0).missing() == 4,
            "twenty wrong meals must not hide a four-fish tagged price deficit");
        helper.assertTrue(needs.get(0).matches(new ItemStack(Items.COD))
                && needs.get(0).matches(new ItemStack(Items.SALMON))
                && !needs.get(0).matches(new ItemStack(Items.BAKED_POTATO)),
            "need must accept edible fish tag members and reject unrelated meals");

        inventory.setStackInSlot(1, new ItemStack(Items.COD, 3));
        var reduced = RecruitmentPolicy.missingReadyFoodPrices(inventory, fishPrice);
        helper.assertTrue(reduced.size() == 1 && reduced.get(0).missing() == 1,
            "three matching fish should reduce the live tagged deficit to one");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void FailedAssessmentNeverMutatesLiveInventory(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s);
        HearthBlockEntity h = hearth(helper);
        h.insertGoods(new ItemStack(Items.BREAD, 11));
        h.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        int breadBefore = count(h, Items.BREAD);
        int planksBefore = count(h, Items.OAK_PLANKS);
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.ATTRACTION);
        helper.assertTrue(!a.eligible(), "fixture must fail the post-payment reserve");
        helper.assertTrue(count(h, Items.BREAD) == breadBefore
            && count(h, Items.OAK_PLANKS) == planksBefore,
            "simulation must not shrink a live stack on failure");
        helper.succeed();
    }

    // ---------------------------------------------------------- policy gates

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void WaitingAdmissionGrandfathersTavernAndMorale(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        s.moraleCache = 1;
        seedFullBoundary(hearth(helper));
        RecruitmentPolicy.Assessment attraction = assess(helper, s,
            RecruitmentPolicy.Stage.ATTRACTION);
        RecruitmentPolicy.Assessment waiting = assess(helper, s,
            RecruitmentPolicy.Stage.WAITING_ADMISSION);
        helper.assertTrue(attraction.blocker() == RecruitmentPolicy.Blocker.NO_TAVERN,
            "attraction must still require a tavern");
        helper.assertTrue(waiting.eligible(),
            "a waiting guest must grandfather both tavern and morale");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void MissingHearthHasConcreteBlocker(GameTestHelper helper) {
        Settlement s = settlement(helper, false);
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.WAITING_ADMISSION);
        helper.assertTrue(a.blocker() == RecruitmentPolicy.Blocker.NO_HEARTH,
            "waiting admission may never grandfather the hearth");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void MissingTavernHasConcreteBlocker(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        seedFullBoundary(hearth(helper));
        helper.assertTrue(assess(helper, s, RecruitmentPolicy.Stage.ATTRACTION).blocker()
                == RecruitmentPolicy.Blocker.NO_TAVERN,
            "attraction must report the tavern, not a generic blocked state");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void FullSettlementHasConcreteBedBlocker(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s);
        addResidents(s, 3); // founder capacity is exactly three without homes
        hearth(helper).insertGoods(new ItemStack(Items.BREAD, 36));
        hearth(helper).insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        helper.assertTrue(assess(helper, s, RecruitmentPolicy.Stage.ATTRACTION).blocker()
                == RecruitmentPolicy.Blocker.NO_BED,
            "a full founder shelter must report the bed gate before payment");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void LowMoraleHasConcreteBlocker(GameTestHelper helper) {
        Settlement s = eligible(helper);
        s.moraleCache = 59;
        helper.assertTrue(assess(helper, s, RecruitmentPolicy.Stage.ATTRACTION).blocker()
                == RecruitmentPolicy.Blocker.LOW_MORALE,
            "morale 59 must fail the explicit 60 threshold");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void MissingPriceHasConcreteBlocker(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s);
        hearth(helper).insertGoods(new ItemStack(Items.BREAD, 64));
        helper.assertTrue(assess(helper, s, RecruitmentPolicy.Stage.ATTRACTION).blocker()
                == RecruitmentPolicy.Blocker.CANNOT_PAY,
            "food abundance cannot replace the planks line of the price");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void PostPaymentFoodHasConcreteBlocker(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s);
        hearth(helper).insertGoods(new ItemStack(Items.BREAD, 10));
        hearth(helper).insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.ATTRACTION);
        helper.assertTrue(a.blocker() == RecruitmentPolicy.Blocker.INSUFFICIENT_READY_FOOD
            && a.missingReadyFood() == 2,
            "payable but underfed must report the exact two-meal shortfall");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void BlockerAndStageWireIdsAreStable(GameTestHelper helper) {
        helper.assertTrue(RecruitmentPolicy.Blocker.NONE.wireId() == 0
            && RecruitmentPolicy.Blocker.NO_HEARTH.wireId() == 1
            && RecruitmentPolicy.Blocker.NO_TAVERN.wireId() == 2
            && RecruitmentPolicy.Blocker.NO_BED.wireId() == 3
            && RecruitmentPolicy.Blocker.LOW_MORALE.wireId() == 4
            && RecruitmentPolicy.Blocker.CANNOT_PAY.wireId() == 5
            && RecruitmentPolicy.Blocker.INSUFFICIENT_READY_FOOD.wireId() == 6
            && RecruitmentPolicy.Blocker.INVALID_STATE.wireId() == 7,
            "blocker protocol ids changed");
        helper.assertTrue(RecruitmentPolicy.Stage.ATTRACTION.wireId() == 0
            && RecruitmentPolicy.Stage.WAITING_ADMISSION.wireId() == 1
            && RecruitmentPolicy.Stage.INVALID.wireId() == 2,
            "stage protocol ids changed");
        helper.assertTrue(RecruitmentPolicy.Blocker.fromWireId(999)
                == RecruitmentPolicy.Blocker.INVALID_STATE,
            "unknown blocker ids must fail closed");
        helper.succeed();
    }

    // --------------------------------------------------------------- clocks

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void DeterministicTargetIsLockedInsideTwoToFourDays(GameTestHelper helper) {
        Settlement s = eligible(helper);
        int locked = s.recruitTarget;
        helper.assertTrue(locked >= RecruitmentPolicy.MIN_QUALIFIED_SECONDS
            && locked <= RecruitmentPolicy.MAX_TARGET_SECONDS,
            "target must represent two to four Minecraft days, got " + locked);
        for (int i = 0; i < 20; i++) {
            SettlementManager.tickRecruitment(helper.getLevel(), s);
        }
        helper.assertTrue(s.recruitTarget == locked && s.recruitProgress == 20
            && s.recruitQualifiedSeconds == 20,
            "ordinary qualified ticks must not reroll or accelerate the target");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void ContinuousEligibilityNeedsEveryLockedSecond(GameTestHelper helper) {
        Settlement s = eligible(helper);
        int locked = s.recruitTarget;
        for (int second = 0; second < locked - 1; second++) {
            SettlementManager.tickRecruitment(helper.getLevel(), s);
        }
        helper.assertTrue(s.travelerId == null && s.recruitProgress == locked - 1
            && s.recruitTarget == locked && s.recruitCycle == 0,
            "a traveler must not appear one qualified second before the locked target");
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.travelerId != null,
            "the final qualified second of the 2-4 day target must spawn the candidate");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void TargetAloneCannotBypassQualifiedMinimum(GameTestHelper helper) {
        Settlement s = eligible(helper);
        s.recruitProgress = s.recruitTarget - 1;
        s.recruitQualifiedSeconds = 100;
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.recruitProgress == s.recruitTarget
            && s.recruitQualifiedSeconds == 101 && s.travelerId == null,
            "a full target gauge without 2400 qualified seconds must not spawn");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void BlockedClocksDecayTogether(GameTestHelper helper) {
        Settlement s = settlement(helper, true); // no tavern: attraction blocked
        s.recruitProgress = 70;
        s.recruitQualifiedSeconds = 50;
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.recruitProgress == 69 && s.recruitQualifiedSeconds == 49,
            "one blocked second must decay both clocks by exactly one");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void SuccessfulTravelerSpawnRenewsTargetOnlyThen(GameTestHelper helper) {
        Settlement s = eligible(helper);
        int oldTarget = s.recruitTarget;
        s.recruitProgress = oldTarget - 1;
        s.recruitQualifiedSeconds = RecruitmentPolicy.MIN_QUALIFIED_SECONDS - 1;
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.travelerId != null,
            "both completed clocks must create a real waiting traveler");
        helper.assertTrue(s.recruitCycle == 1 && s.recruitProgress == 0
            && s.recruitQualifiedSeconds == 0
            && s.recruitTarget == RecruitmentPolicy.targetFor(s.id, 1),
            "only successful spawn may advance the generation and renew clocks");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void RejectedTravelerSpawnKeepsLockedTargetAndCompletedClocks(GameTestHelper helper) {
        Settlement s = eligible(helper);
        int lockedTarget = s.recruitTarget;
        int lockedCycle = s.recruitCycle;
        s.recruitProgress = lockedTarget - 1;
        s.recruitQualifiedSeconds = RecruitmentPolicy.MIN_QUALIFIED_SECONDS - 1;

        Consumer<EntityJoinLevelEvent> rejectSettler = event -> {
            if (event.getLevel() == helper.getLevel()
                && event.getEntity() instanceof SettlerEntity) {
                event.setCanceled(true);
            }
        };
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGHEST,
            EntityJoinLevelEvent.class, rejectSettler);
        try {
            SettlementManager.tickRecruitment(helper.getLevel(), s);
        } finally {
            NeoForge.EVENT_BUS.unregister(rejectSettler);
        }

        helper.assertTrue(s.travelerId == null,
            "a candidate rejected by the level must not become the waiting traveler");
        helper.assertTrue(s.recruitCycle == lockedCycle
            && s.recruitTarget == lockedTarget,
            "a rejected spawn must not advance the cycle or renew the locked target");
        helper.assertTrue(s.recruitProgress == lockedTarget
            && s.recruitQualifiedSeconds == RecruitmentPolicy.MIN_QUALIFIED_SECONDS,
            "completed clocks must remain ready for retry after a rejected spawn");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void RecruitCommandHelperSetsBothClocksWithoutBypassingGates(GameTestHelper helper) {
        Settlement s = eligible(helper);
        helper.assertTrue(SettlementManager.primeRecruitment(helper.getLevel(), s),
            "eligible settlement should accept command fast-forward");
        helper.assertTrue(s.recruitProgress == s.recruitTarget - 1
            && s.recruitQualifiedSeconds == RecruitmentPolicy.MIN_QUALIFIED_SECONDS - 1,
            "command must prime both clocks to exactly one second before completion");

        Settlement blocked = settlement(helper, true);
        blocked.recruitProgress = 7;
        blocked.recruitQualifiedSeconds = 6;
        helper.assertTrue(!SettlementManager.primeRecruitment(helper.getLevel(), blocked),
            "command must refuse a settlement missing admission resources/tavern");
        helper.assertTrue(blocked.recruitProgress == 7
            && blocked.recruitQualifiedSeconds == 6,
            "refused command must not alter either clock");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void CourierFoodTargetScalesPastTwentyFour(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        addResidents(s, 3);
        HearthBlockEntity h = hearth(helper);
        h.insertGoods(new ItemStack(Items.BREAD, 64));
        int target = RecruitmentPolicy.assess(helper.getLevel(), s,
            RecruitmentPolicy.Stage.ATTRACTION).courierReadyFoodTarget();
        helper.assertTrue(target == 36 && target > 24,
            "three residents + next recruit + four-bread exposure must target 36, got "
                + target);
        helper.succeed();
    }

    // ------------------------------------------------------------- Data v2

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void DataVersionOneProgressMigratesProportionally(GameTestHelper helper) {
        Settlement original = new Settlement(UUID.randomUUID(), "Gamle Matvik", BlockPos.ZERO);
        CompoundTag tag = original.writeNbt();
        tag.putInt("RecruitProgress", 120);
        tag.putInt("RecruitTarget", 240);
        CompoundTag v1Root = new CompoundTag();
        v1Root.putInt("DataVersion", 1);
        ListTag entries = new ListTag();
        entries.add(tag);
        v1Root.put("Settlements", entries);
        SettlementSavedData migratedData = SettlementSavedData.load(v1Root,
            helper.getLevel().registryAccess());
        Settlement migrated = migratedData.settlements.get(original.id);
        int halfTargetRounded = (migrated.recruitTarget + 1) / 2;
        helper.assertTrue(migrated.recruitProgress == halfTargetRounded,
            "half of the old gauge must remain half of the deterministic target");
        helper.assertTrue(migrated.recruitQualifiedSeconds == 1_200
            && migrated.recruitCycle == 0,
            "half progress must become exactly one qualified Minecraft day");
        CompoundTag v2Root = migratedData.save(new CompoundTag(),
            helper.getLevel().registryAccess());
        Settlement reloaded = SettlementSavedData.load(v2Root,
            helper.getLevel().registryAccess()).settlements.get(original.id);
        helper.assertTrue(reloaded.recruitTarget == migrated.recruitTarget
            && reloaded.recruitProgress == migrated.recruitProgress
            && reloaded.recruitQualifiedSeconds == migrated.recruitQualifiedSeconds,
            "migrated fraction must survive its first DataVersion 2 reload exactly");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void DataVersionTwoReloadPreservesCorrectClocks(GameTestHelper helper) {
        Settlement original = new Settlement(UUID.randomUUID(), "Ny Matvik", BlockPos.ZERO);
        original.recruitCycle = 4;
        original.recruitTarget = RecruitmentPolicy.targetFor(original.id, 4);
        original.recruitProgress = 777;
        original.recruitQualifiedSeconds = 600;
        Settlement loaded = Settlement.readNbt(original.writeNbt(), 2);
        helper.assertTrue(loaded.recruitCycle == 4
            && loaded.recruitTarget == original.recruitTarget
            && loaded.recruitProgress == 777
            && loaded.recruitQualifiedSeconds == 600,
            "a valid v2 save must round-trip without resetting correct progress");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void VersionOneMigrationIsDeterministicAcrossReloads(GameTestHelper helper) {
        UUID id = UUID.fromString("b8b8d112-3174-4dd0-a893-5a7a8cf68f9b");
        Settlement original = new Settlement(id, "Fast Matvik", BlockPos.ZERO);
        CompoundTag tag = original.writeNbt();
        tag.putInt("RecruitProgress", 73);
        tag.putInt("RecruitTarget", 263);
        Settlement first = Settlement.readNbt(tag.copy(), 1);
        Settlement second = Settlement.readNbt(tag.copy(), 1);
        helper.assertTrue(first.recruitTarget == 4_538,
            "the fixed UUID's cycle-zero migration target is a frozen v2 contract");
        helper.assertTrue(first.recruitTarget == second.recruitTarget
            && first.recruitProgress == second.recruitProgress
            && first.recruitQualifiedSeconds == second.recruitQualifiedSeconds,
            "identical UUID/tags must migrate identically without load-time RNG");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void MalformedRecruitmentTagsResetFailClosed(GameTestHelper helper) {
        Settlement original = new Settlement(UUID.randomUUID(), "Skadet Matvik", BlockPos.ZERO);
        original.recruitProgress = original.recruitTarget;
        original.recruitQualifiedSeconds = RecruitmentPolicy.MIN_QUALIFIED_SECONDS;
        CompoundTag tag = original.writeNbt();
        tag.putString("RecruitProgress", "almost-ready");
        Settlement loaded = Settlement.readNbt(tag, 2);
        helper.assertTrue(loaded.recruitProgress == 0
            && loaded.recruitQualifiedSeconds == 0 && loaded.recruitCycle == 0
            && loaded.recruitTarget == RecruitmentPolicy.targetFor(loaded.id, 0),
            "malformed clocks must become a deterministic, non-ready cycle");

        CompoundTag malformedV1 = original.writeNbt();
        malformedV1.putInt("RecruitProgress", 199);
        malformedV1.putString("RecruitTarget", "two hundred");
        Settlement migrated = Settlement.readNbt(malformedV1, 1);
        helper.assertTrue(migrated.recruitProgress == 0
            && migrated.recruitQualifiedSeconds == 0
            && migrated.recruitTarget == RecruitmentPolicy.targetFor(migrated.id, 0),
            "malformed v1 input must also migrate to a deterministic non-ready cycle");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void FutureDataVersionIsRejected(GameTestHelper helper) {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SettlementSavedData.CURRENT_DATA_VERSION + 1);
        root.put("Settlements", new ListTag());
        boolean rejected = false;
        try {
            SettlementSavedData.load(root, helper.getLevel().registryAccess());
        } catch (SettlementSavedData.DataVersionException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected,
            "a future settlement schema must fail closed instead of guessing");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void VersionZeroRecruitFractionStillMigratesWithLegacyContract(GameTestHelper helper) {
        Settlement original = new Settlement(UUID.randomUUID(), "Eldste Matvik", BlockPos.ZERO);
        CompoundTag tag = original.writeNbt();
        tag.putInt("RecruitProgress", 50);
        tag.putInt("RecruitTarget", 200);
        Settlement migrated = Settlement.readNbt(tag, 0);
        helper.assertTrue(migrated.recruitQualifiedSeconds == 600,
            "v0's quarter gauge must survive as one half Minecraft day");
        helper.assertTrue(migrated.recruitProgress > 0
            && migrated.recruitProgress < migrated.recruitTarget,
            "v0 progress must neither reset nor become ready during migration");
        helper.succeed();
    }

    // --------------------------------------------------------- UI authority

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void HearthMenuDataMatchesPolicyAssessment(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s);
        HearthBlockEntity h = hearth(helper);
        h.insertGoods(new ItemStack(Items.BREAD, 10));
        h.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.ATTRACTION);

        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        HearthMenu menu = (HearthMenu) h.createMenu(1, player.getInventory(), player);
        helper.assertTrue(menu != null, "bound hearth must create its menu");
        helper.assertTrue(menu.get(HearthMenu.DATA_RECRUIT_BLOCKER) == a.blocker().wireId()
            && menu.get(HearthMenu.DATA_READY_AFTER_PRICE) == a.readyFoodAfterPrice()
            && menu.get(HearthMenu.DATA_REQUIRED_RESERVE) == a.requiredReadyFood()
            && menu.get(HearthMenu.DATA_MISSING_RESERVE) == a.missingReadyFood()
            && menu.get(HearthMenu.DATA_RECRUIT_STAGE) == a.stage().wireId(),
            "menu must expose the exact server assessment, not a client formula");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void WaitingStateIsServerAuthoritativeInMenu(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        s.travelerId = UUID.randomUUID();
        seedFullBoundary(hearth(helper));
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.WAITING_ADMISSION);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        HearthMenu menu = (HearthMenu) hearth(helper)
            .createMenu(2, player.getInventory(), player);
        helper.assertTrue(menu.get(HearthMenu.DATA_RECRUIT_STAGE)
                == RecruitmentPolicy.Stage.WAITING_ADMISSION.wireId()
            && menu.get(HearthMenu.DATA_RECRUIT_BLOCKER) == a.blocker().wireId(),
            "waiting/admission state and blocker must come from server policy");
        helper.succeed();
    }
}
