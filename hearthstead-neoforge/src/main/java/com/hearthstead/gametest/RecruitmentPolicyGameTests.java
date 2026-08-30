package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.network.HearthMayorAction;
import com.hearthstead.network.HearthNetwork;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Costs;
import com.hearthstead.settlement.ReadyFood;
import com.hearthstead.settlement.RecruitmentPolicy;
import com.hearthstead.settlement.RecruitmentTransaction;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.FoundingJourney;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.GameType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.Entity;
import net.minecraft.server.level.ServerPlayer;
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
import net.neoforged.neoforge.network.registration.NetworkRegistry;

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
        Building building = GameTestFixtures.registerWithBounds(helper, s,
            BuildingType.TAVERN,
            TAVERN, TAVERN.above(),
            BoundingBox.fromCorners(anchor, anchor.offset(2, 2, 2)));
        // Synthetic fixture registration bypasses the real survey commit;
        // recruitment deliberately requires the plaque to carry the exact
        // same durable building UUID, so mirror that one committed field.
        if (!(helper.getLevel().getBlockEntity(building.plaquePos)
            instanceof PlaqueBlockEntity plaque)) {
            throw new IllegalStateException("fixture Tavern plaque missing");
        }
        try {
            var field = PlaqueBlockEntity.class.getDeclaredField("buildingId");
            field.setAccessible(true);
            field.set(plaque, building.id);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot bind fixture Tavern plaque",
                failure);
        }
        return building;
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

    private static SettlerEntity spawnWaitingTraveler(GameTestHelper helper,
                                                       Settlement settlement) {
        helper.assertTrue(SettlementManager.primeRecruitment(helper.getLevel(),
            settlement), "eligible fixture must prime exact recruitment state");
        SettlementManager.tickRecruitment(helper.getLevel(), settlement);
        UUID travelerId = settlement.recruitment.travelerId();
        Entity entity = travelerId == null ? null
            : helper.getLevel().getEntity(travelerId);
        helper.assertTrue(entity instanceof SettlerEntity,
            "primed recruitment must spawn the physical traveler");
        SettlerEntity traveler = (SettlerEntity) entity;
        BlockPos anchor = settlement.recruitment.tavernAnchor();
        traveler.moveTo(anchor.getX() + 0.5, anchor.getY(), anchor.getZ() + 0.5,
            0.0F, 0.0F);
        SettlementManager.tickRecruitment(helper.getLevel(), settlement);
        helper.assertTrue(settlement.recruitment.status()
                == RecruitmentTransaction.Status.WAITING_ADMISSION,
            "arrival must commit only at the exact Tavern anchor");
        return traveler;
    }

    private static HearthMenu openHearth(GameTestHelper helper,
                                         Settlement settlement,
                                         ServerPlayer player) {
        HearthBlockEntity hearth = (HearthBlockEntity) helper.getLevel()
            .getBlockEntity(settlement.center);
        NetworkRegistry.configureMockConnection(player.connection.getConnection());
        player.setPos(settlement.center.getX() + 0.5D,
            settlement.center.getY() + 0.5D,
            settlement.center.getZ() + 0.5D);
        player.openMenu(hearth, buf -> {
            buf.writeBlockPos(settlement.center);
            buf.writeUUID(settlement.id);
            buf.writeUtf(settlement.name);
        });
        helper.assertTrue(player.containerMenu instanceof HearthMenu,
            "fixture must open the exact live HearthMenu");
        return (HearthMenu) player.containerMenu;
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
    public void WaitingAdmissionRevalidatesTavernAndMorale(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        s.moraleCache = 1;
        seedFullBoundary(hearth(helper));
        RecruitmentPolicy.Assessment attraction = assess(helper, s,
            RecruitmentPolicy.Stage.ATTRACTION);
        RecruitmentPolicy.Assessment waiting = assess(helper, s,
            RecruitmentPolicy.Stage.WAITING_ADMISSION);
        helper.assertTrue(attraction.blocker() == RecruitmentPolicy.Blocker.NO_TAVERN,
            "attraction must still require a tavern");
        helper.assertTrue(waiting.blocker() == RecruitmentPolicy.Blocker.NO_TAVERN,
            "waiting admission must revalidate the live Tavern before morale");
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
            && RecruitmentPolicy.Stage.INVALID.wireId() == 2
            && RecruitmentPolicy.Stage.QUALIFYING.wireId() == 3
            && RecruitmentPolicy.Stage.TRAVELING.wireId() == 4,
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
        helper.assertTrue(s.recruitment.status()
                == RecruitmentTransaction.Status.READY_TO_SPAWN
                && s.travelerId == null,
            "the final qualified second must first persist READY_TO_SPAWN");
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.recruitment.status()
                == RecruitmentTransaction.Status.TRAVELING
                && s.travelerId != null,
            "a later tick may publish the exact physical candidate");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void TargetAloneCannotBypassQualifiedMinimum(GameTestHelper helper) {
        Settlement s = eligible(helper);
        // Loose compatibility mirrors are never recruitment authority.
        s.recruitProgress = s.recruitTarget;
        s.recruitQualifiedSeconds = RecruitmentPolicy.MIN_QUALIFIED_SECONDS;
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.recruitment.status()
                == RecruitmentTransaction.Status.QUALIFYING
                && s.recruitProgress == 1
                && s.recruitQualifiedSeconds == 1 && s.travelerId == null,
            "legacy scalar writes must not bypass the persisted transaction");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void BlockedClocksDecayTogether(GameTestHelper helper) {
        Settlement s = eligible(helper);
        for (int i = 0; i < 5; i++) {
            SettlementManager.tickRecruitment(helper.getLevel(), s);
        }
        s.moraleCache = 0;
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.recruitProgress == 4 && s.recruitQualifiedSeconds == 4,
            "one blocked second must decay both clocks by exactly one");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void WaitingTravelerNeverAutoPaysOrAutoConverts(GameTestHelper helper) {
        Settlement s = eligible(helper);
        HearthBlockEntity h = hearth(helper);
        int breadBefore = count(h, Items.BREAD);
        int planksBefore = count(h, Items.OAK_PLANKS);
        SettlerEntity traveler = spawnWaitingTraveler(helper, s);
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.recruitment.status()
                == RecruitmentTransaction.Status.WAITING_ADMISSION
                && traveler.isTraveler() && s.record(traveler.getUUID()) == null,
            "waiting ticks must never turn a guest into a member");
        helper.assertTrue(count(h, Items.BREAD) == breadBefore
                && count(h, Items.OAK_PLANKS) == planksBefore
                && s.recruitCycle == 0,
            "waiting ticks must never auto-pay or silently advance the cycle");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void RejectedTravelerSpawnKeepsLockedTargetAndCompletedClocks(GameTestHelper helper) {
        Settlement s = eligible(helper);
        int lockedTarget = s.recruitTarget;
        int lockedCycle = s.recruitCycle;
        helper.assertTrue(SettlementManager.primeRecruitment(helper.getLevel(), s),
            "fixture must persist READY_TO_SPAWN before cancellation");

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

        helper.assertTrue(s.travelerId == null
                && s.recruitment.status()
                    == RecruitmentTransaction.Status.READY_TO_SPAWN,
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
        helper.assertTrue(s.recruitProgress == s.recruitTarget
            && s.recruitQualifiedSeconds == RecruitmentPolicy.MIN_QUALIFIED_SECONDS
            && s.recruitment.status()
                == RecruitmentTransaction.Status.READY_TO_SPAWN
            && !s.recruitment.survivalAuthored(),
            "command may prepare physical flow but cannot author survival progress");

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
    public void ArrivalCommitsOnlyAtExactTavernNotHearth(GameTestHelper helper) {
        Settlement s = eligible(helper);
        helper.assertTrue(SettlementManager.primeRecruitment(helper.getLevel(), s),
            "fixture must prime recruitment");
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        SettlerEntity traveler = (SettlerEntity) helper.getLevel()
            .getEntity(s.recruitment.travelerId());
        helper.assertTrue(traveler != null && s.recruitment.status()
                == RecruitmentTransaction.Status.TRAVELING,
            "candidate must exist physically in TRAVELING state");

        traveler.moveTo(s.center.getX() + 0.5D, s.center.getY(),
            s.center.getZ() + 0.5D, 0.0F, 0.0F);
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.recruitment.status()
                == RecruitmentTransaction.Status.TRAVELING
                && s.recruitment.arrivedTick() == RecruitmentTransaction.NO_TICK,
            "standing at the Hearth cannot fake a Tavern arrival");

        BlockPos anchor = s.recruitment.tavernAnchor();
        traveler.moveTo(anchor.getX() + 0.5D, anchor.getY(),
            anchor.getZ() + 0.5D, 0.0F, 0.0F);
        long arrivalTick = helper.getLevel().getGameTime();
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.recruitment.status()
                == RecruitmentTransaction.Status.WAITING_ADMISSION
                && s.recruitment.arrivedTick() == arrivalTick,
            "actual <=3-block Tavern arrival must persist the patience start");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void TwoPlayersProduceOnePaidAdmissionWinner(GameTestHelper helper) {
        Settlement s = eligible(helper);
        SettlerEntity traveler = spawnWaitingTraveler(helper, s);
        HearthBlockEntity h = hearth(helper);
        int breadBefore = count(h, Items.BREAD);
        int planksBefore = count(h, Items.OAK_PLANKS);
        int revision = s.recruitment.revision();

        ServerPlayer winner = helper.makeMockServerPlayerInLevel();
        ServerPlayer replay = helper.makeMockServerPlayerInLevel();
        HearthMenu winnerMenu = openHearth(helper, s, winner);
        HearthMenu replayMenu = openHearth(helper, s, replay);
        HearthNetwork.handle(winner, new HearthMayorAction(s.center, s.id,
            winnerMenu.getContainerId(), HearthMayorAction.Kind.ADMIT_TRAVELER,
            traveler.getUUID(), revision));
        int breadAfterWinner = count(h, Items.BREAD);
        int planksAfterWinner = count(h, Items.OAK_PLANKS);
        HearthNetwork.handle(replay, new HearthMayorAction(s.center, s.id,
            replayMenu.getContainerId(), HearthMayorAction.Kind.ADMIT_TRAVELER,
            traveler.getUUID(), revision));
        HearthNetwork.handle(replay, new HearthMayorAction(s.center, s.id,
            replayMenu.getContainerId(), HearthMayorAction.Kind.REJECT_TRAVELER,
            traveler.getUUID(), revision));

        helper.assertTrue(s.recruitment.status()
                == RecruitmentTransaction.Status.ADMITTED
                && s.record(traveler.getUUID()) != null && !traveler.isTraveler(),
            "exactly one action must bind exactly the saved traveler");
        helper.assertTrue(breadAfterWinner == breadBefore - 4
                && planksAfterWinner == planksBefore - 8
                && count(h, Items.BREAD) == breadAfterWinner
                && count(h, Items.OAK_PLANKS) == planksAfterWinner,
            "winner pays one exact price; stale admit/reject replays mutate nothing");
        helper.assertTrue(s.recruitment.admissionReceipt() != null
                && winner.getUUID().equals(
                    s.recruitment.admissionReceipt().playerId())
                && s.recruitment.admissionReceipt().removedItemCount() == 12,
            "persisted receipt must name the winning actor and exact item delta");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void ExplicitDismissalTakesNoPaymentAndReplayIsInert(GameTestHelper helper) {
        Settlement s = eligible(helper);
        SettlerEntity traveler = spawnWaitingTraveler(helper, s);
        HearthBlockEntity h = hearth(helper);
        int breadBefore = count(h, Items.BREAD);
        int planksBefore = count(h, Items.OAK_PLANKS);
        int revision = s.recruitment.revision();
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        HearthMenu menu = openHearth(helper, s, player);

        HearthNetwork.handle(player, new HearthMayorAction(s.center, s.id,
            menu.getContainerId(), HearthMayorAction.Kind.REJECT_TRAVELER,
            traveler.getUUID(), revision));
        HearthNetwork.handle(player, new HearthMayorAction(s.center, s.id,
            menu.getContainerId(), HearthMayorAction.Kind.ADMIT_TRAVELER,
            traveler.getUUID(), revision));

        helper.assertTrue(s.recruitment.status()
                == RecruitmentTransaction.Status.LEFT
                && s.recruitment.terminalReason()
                    == RecruitmentTransaction.TerminalReason.PLAYER_REJECTED
                && s.record(traveler.getUUID()) == null,
            "dismissal must persist a terminal no-member decision");
        helper.assertTrue(count(h, Items.BREAD) == breadBefore
                && count(h, Items.OAK_PLANKS) == planksBefore,
            "dismissal and stale admission replay must take no payment");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void AdmissionFailuresConserveItemsAndMembership(GameTestHelper helper) {
        Settlement s = eligible(helper);
        SettlerEntity traveler = spawnWaitingTraveler(helper, s);
        HearthBlockEntity h = hearth(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        int revision = s.recruitment.revision();

        for (int slot = 0; slot < h.getInventory().getSlots(); slot++) {
            if (h.getInventory().getStackInSlot(slot).is(ItemTags.PLANKS)) {
                h.getInventory().setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        int breadBefore = count(h, Items.BREAD);
        helper.assertTrue(SettlementManager.admitWaitingTraveler(player, s,
                traveler.getUUID(), revision)
                == SettlementManager.AdmissionResult.BLOCKED_POLICY,
            "missing physical payment must fail before mutation");
        helper.assertTrue(count(h, Items.BREAD) == breadBefore
                && s.record(traveler.getUUID()) == null,
            "failed payment preflight must conserve inventory and membership");

        h.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        addResidents(s, s.capacity());
        helper.assertTrue(SettlementManager.admitWaitingTraveler(player, s,
                traveler.getUUID(), revision)
                == SettlementManager.AdmissionResult.BLOCKED_POLICY,
            "a capacity race must fail admission without payment");
        s.settlers.clear();

        Building locked = s.buildings.stream()
            .filter(b -> b.id.equals(s.recruitment.tavernBuildingId()))
            .findFirst().orElseThrow();
        locked.valid = false;
        helper.assertTrue(SettlementManager.admitWaitingTraveler(player, s,
                traveler.getUUID(), revision)
                == SettlementManager.AdmissionResult.INVALID_TAVERN,
            "destroying the exact Tavern must block rather than fall back to Hearth");
        helper.assertTrue(SettlementManager.admitWaitingTraveler(player, s,
                traveler.getUUID(), revision - 1)
                == SettlementManager.AdmissionResult.STALE_REVISION,
            "stale revisions fail before any changed live gate is considered");
        helper.assertTrue(count(h, Items.BREAD) == breadBefore
                && s.record(traveler.getUUID()) == null,
            "every failed path must preserve physical conservation");
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
    public void LegacyProgressMigratesWithoutRelockingOrFreeRecruit(GameTestHelper helper) {
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
        helper.assertTrue(migrated.recruitProgress == 0
            && migrated.recruitQualifiedSeconds == 0
            && migrated.recruitCycle == 0
            && migrated.recruitment.status()
                == RecruitmentTransaction.Status.ATTRACTING
            && migrated.recruitment.transactionId() == null,
            "legacy clocks cannot fabricate an exact Tavern lock or candidate");
        CompoundTag v2Root = migratedData.save(new CompoundTag(),
            helper.getLevel().registryAccess());
        Settlement reloaded = SettlementSavedData.load(v2Root,
            helper.getLevel().registryAccess()).settlements.get(original.id);
        helper.assertTrue(reloaded.recruitment.equals(migrated.recruitment),
            "the fail-closed migrated cycle must survive its current-schema reload");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void LegacyVersionTwoPreservesCycleButDropsUnverifiableClocks(GameTestHelper helper) {
        Settlement original = new Settlement(UUID.randomUUID(), "Ny Matvik", BlockPos.ZERO);
        original.recruitCycle = 4;
        original.recruitTarget = RecruitmentPolicy.targetFor(original.id, 4);
        original.recruitProgress = 777;
        original.recruitQualifiedSeconds = 600;
        Settlement loaded = Settlement.readNbt(original.writeNbt(), 2);
        helper.assertTrue(loaded.recruitCycle == 4
            && loaded.recruitTarget == original.recruitTarget
            && loaded.recruitProgress == 0
            && loaded.recruitQualifiedSeconds == 0
            && loaded.recruitment.status()
                == RecruitmentTransaction.Status.ATTRACTING,
            "v2 can preserve cycle balance but cannot invent a Tavern identity");
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
            "the fixed UUID's cycle-zero target remains deterministic");
        helper.assertTrue(first.recruitTarget == second.recruitTarget
            && first.recruitProgress == second.recruitProgress
            && first.recruitQualifiedSeconds == second.recruitQualifiedSeconds
            && first.recruitProgress == 0,
            "identical legacy tags must reset identically without load-time relocking");
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
    public void CurrentMalformedAndLegacyTravelerStatesQuarantine(GameTestHelper helper) {
        Settlement current = eligible(helper);
        SettlementManager.tickRecruitment(helper.getLevel(), current);
        CompoundTag currentTag = current.writeNbt();
        currentTag.getCompound("RecruitmentTransaction").remove("TavernAnchor");
        Settlement damaged = Settlement.readNbt(currentTag,
            SettlementSavedData.CURRENT_DATA_VERSION);
        helper.assertTrue(damaged.recruitment.status()
                == RecruitmentTransaction.Status.QUARANTINED,
            "partial current Tavern identity must quarantine, never relock");

        Settlement legacy = new Settlement(UUID.randomUUID(), "Legacy guest",
            BlockPos.ZERO);
        CompoundTag legacyTag = legacy.writeNbt();
        legacyTag.putUUID("TravelerId", UUID.randomUUID());
        Settlement quarantined = Settlement.readNbt(legacyTag, 6);
        helper.assertTrue(quarantined.recruitment.status()
                == RecruitmentTransaction.Status.QUARANTINED
                && quarantined.recruitment.terminalReason()
                    == RecruitmentTransaction.TerminalReason.LEGACY_UNVERIFIABLE,
            "legacy loose TravelerId must never become a free member");
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
    public void VersionZeroRecruitFractionCannotFabricateQualification(GameTestHelper helper) {
        Settlement original = new Settlement(UUID.randomUUID(), "Eldste Matvik", BlockPos.ZERO);
        CompoundTag tag = original.writeNbt();
        tag.putInt("RecruitProgress", 50);
        tag.putInt("RecruitTarget", 200);
        Settlement migrated = Settlement.readNbt(tag, 0);
        helper.assertTrue(migrated.recruitQualifiedSeconds == 0
            && migrated.recruitProgress == 0
            && migrated.recruitment.status()
                == RecruitmentTransaction.Status.ATTRACTING,
            "v0 lacked exact Tavern proof and must restart without free progress");
        helper.succeed();
    }

    // --------------------------------------------------------- UI authority

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void HearthMenuDataMatchesPolicyAssessment(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        s.foundingJourney = FoundingJourney.fresh();
        helper.assertTrue(s.foundingJourney.noteLumberCampLinked(),
            "journey menu fixture must advance to its second authoritative phase");
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
        helper.assertTrue(menu.get(HearthMenu.DATA_JOURNEY_PHASE)
                == FoundingJourney.Phase.HIRE_LUMBERER.wireId()
                && menu.get(HearthMenu.DATA_JOURNEY_REVISION) == 1
                && menu.get(HearthMenu.DATA_JOURNEY_CAN_SKIP) == 1,
            "journey phase, revision and skip permission must come from server menu data");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty16", timeoutTicks = 100)
    public void WaitingStateIsServerAuthoritativeInMenu(GameTestHelper helper) {
        Settlement s = eligible(helper);
        spawnWaitingTraveler(helper, s);
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.WAITING_ADMISSION);
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        HearthMenu menu = (HearthMenu) hearth(helper)
            .createMenu(2, player.getInventory(), player);
        helper.assertTrue(menu.get(HearthMenu.DATA_RECRUIT_STAGE)
                == RecruitmentPolicy.Stage.WAITING_ADMISSION.wireId()
            && menu.get(HearthMenu.DATA_RECRUIT_BLOCKER) == a.blocker().wireId()
            && menu.get(HearthMenu.DATA_RECRUIT_REVISION)
                == s.recruitment.revision()
            && menu.get(HearthMenu.DATA_RECRUIT_TRANSACTION_STATUS)
                == RecruitmentTransaction.Status.WAITING_ADMISSION.wireId(),
            "waiting/admission state and blocker must come from server policy");
        helper.succeed();
    }
}
