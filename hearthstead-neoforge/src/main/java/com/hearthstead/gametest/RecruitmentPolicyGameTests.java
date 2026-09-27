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
        for (int x = 0; x < 32; x++) {
            for (int z = 0; z < 32; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        // Keep every accepted exterior route inside this owned test arena.
        // The separate long-route fixture is tracked at y+40, above this wall.
        for (int edge = 0; edge < 32; edge++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(edge, y, 0), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(edge, y, 32 - 1), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(0, y, edge), Blocks.STONE_BRICKS);
                helper.setBlock(new BlockPos(32 - 1, y, edge), Blocks.STONE_BRICKS);
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
        hearth.insertGoods(new ItemStack(Items.BREAD, 8));
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
            UUID id = UUID.randomUUID();
            s.putRecord(id, "Beboer " + i, Profession.NONE);
            // Synthetic loaded residents have authoritatively no bed; they are
            // not unresolved legacy claims that lock every newly built bed.
            s.record(id).bedClaim = com.hearthstead.settlement.ResidentBedClaim.known(null);
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
        admissionBed(helper, settlement);
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void exactPostPaymentBoundaryPasses(GameTestHelper helper) {
        Settlement s = eligible(helper);
        admissionBed(helper, s);
        hearth(helper).insertGoods(new ItemStack(com.hearthstead.registry.ModItems.GOLD_COIN.get(), 4));
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.WAITING_ADMISSION);
        helper.assertTrue(a.eligible(), "eight meals remain the exact admission reserve after payment is possible");
        helper.assertTrue(a.readyFoodAfterPrice() == 8 && a.requiredReadyFood() == 8
            && a.missingReadyFood() == 0,
            "admission must report after=8 required=8 missing=0");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void oneMealBelowPostPaymentBoundaryFails(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s); s.moraleCache = 60;
        admissionBed(helper, s);
        hearth(helper).insertGoods(new ItemStack(Items.BREAD, 7));
        hearth(helper).insertGoods(new ItemStack(com.hearthstead.registry.ModItems.GOLD_COIN.get(), 4));
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.WAITING_ADMISSION);
        helper.assertTrue(a.blocker() == RecruitmentPolicy.Blocker.INSUFFICIENT_READY_FOOD,
            "seven meals must fail the admission reserve blocker");
        helper.assertTrue(a.readyFoodAfterPrice() == 7 && a.missingReadyFood() == 1,
            "one-below admission boundary must report exactly one missing meal");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void baseForecastIsSimulatedBeforeCandidateReserve(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        Building tavern = tavern(helper, s);
        tavern.workers.add(UUID.randomUUID());
        hearth(helper).insertGoods(new ItemStack(Items.BREAD, 8));
        hearth(helper).insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.ATTRACTION);
        helper.assertTrue(a.price().lines().get(0).count() == 4
            && a.price().lines().size() == 1 && a.price().lines().get(0).exact() == com.hearthstead.registry.ModItems.GOLD_COIN.get(),
            "before a candidate exists, forecast is four Coins without consuming reserve meals");
        helper.assertTrue(a.eligible() && a.readyFoodAfterPrice() == 8,
            "coin forecast must not consume ready food");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void nonFoodStacksDoNotCountAsReadyMeals(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s);
        hearth(helper).insertGoods(new ItemStack(Items.BREAD, 4));
        hearth(helper).insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        hearth(helper).insertGoods(new ItemStack(Items.IRON_INGOT, 64));
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.ATTRACTION);
        helper.assertTrue(a.readyFoodAfterPrice() == 4 && a.eligible(),
            "the visitor threshold must count bread but never let iron add a meal");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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
        helper.assertTrue(a.readyFoodAfterPrice() == 4 && a.eligible(),
            "wheat must not add a meal above the real bread visitor threshold");
        helper.succeed();
    }

    /**
     * Future prices may name an edible tag rather than one exact item. A
     * hearth full of unrelated meals must still expose that line as missing,
     * and any concrete edible tag member a warehouse offers must satisfy the
     * same projection. This pins the generic path without changing today's
     * bread-and-planks recruit table.
     */
    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void FailedAssessmentNeverMutatesLiveInventory(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s);
        admissionBed(helper, s);
        HearthBlockEntity h = hearth(helper);
        h.insertGoods(new ItemStack(Items.BREAD, 7));
        h.insertGoods(new ItemStack(Items.OAK_PLANKS, 8));
        h.insertGoods(new ItemStack(com.hearthstead.registry.ModItems.GOLD_COIN.get(), 4));
        int breadBefore = count(h, Items.BREAD);
        int planksBefore = count(h, Items.OAK_PLANKS);
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.WAITING_ADMISSION);
        helper.assertTrue(!a.eligible(), "fixture must fail the admission reserve");
        helper.assertTrue(count(h, Items.BREAD) == breadBefore
            && count(h, Items.OAK_PLANKS) == planksBefore,
            "simulation must not shrink a live stack on failure");
        helper.succeed();
    }

    // ---------------------------------------------------------- policy gates

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void MissingHearthHasConcreteBlocker(GameTestHelper helper) {
        Settlement s = settlement(helper, false);
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.WAITING_ADMISSION);
        helper.assertTrue(a.blocker() == RecruitmentPolicy.Blocker.NO_HEARTH,
            "waiting admission may never grandfather the hearth");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void MissingTavernHasConcreteBlocker(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        seedFullBoundary(hearth(helper));
        helper.assertTrue(assess(helper, s, RecruitmentPolicy.Stage.ATTRACTION).blocker()
                == RecruitmentPolicy.Blocker.NO_TAVERN,
            "attraction must report the tavern, not a generic blocked state");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void FullSettlementCanAttractBeforeItsBedGate(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s); s.moraleCache = 60;
        addResidents(s, Settlement.FOUNDER_PLACES); // founder capacity is exactly four without homes
        hearth(helper).insertGoods(new ItemStack(Items.BREAD, 1));
        helper.assertTrue(assess(helper, s, RecruitmentPolicy.Stage.ATTRACTION).eligible(),
            "one real meal and a valid Tavern may invite a visitor before housing is available");
        helper.assertTrue(assess(helper, s, RecruitmentPolicy.Stage.WAITING_ADMISSION).blocker()
                == RecruitmentPolicy.Blocker.NO_BED,
            "the same full settlement must retain its concrete bed blocker at admission");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void zeroCoinsAndNoFreeBedStillAllowTavernArrivalButNotAdmission(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s); s.moraleCache = 60;
        addResidents(s, 3); // no home at all: no valid free bed (Banner places are not beds)
        HearthBlockEntity h = hearth(helper);
        h.insertGoods(new ItemStack(Items.BREAD, RecruitmentPolicy.MIN_VISITOR_READY_MEALS));

        RecruitmentPolicy.Assessment attraction = assess(helper, s,
            RecruitmentPolicy.Stage.ATTRACTION);
        helper.assertTrue(attraction.eligible()
                && attraction.requiredReadyFood() == RecruitmentPolicy.MIN_VISITOR_READY_MEALS
                && attraction.missingReadyFood() == 0
                && attraction.courierReadyFoodTarget() == 32
                && SettlementManager.primeRecruitment(helper.getLevel(), s),
            "one meal must publish a visitor despite zero Coins/no bed while Courier still sees the 32-meal admission reserve");
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        Entity entity = s.recruitment.travelerId() == null ? null
            : helper.getLevel().getEntity(s.recruitment.travelerId());
        helper.assertTrue(entity instanceof SettlerEntity, "the attraction path must create a real traveler");
        SettlerEntity traveler = (SettlerEntity) entity;
        BlockPos anchor = s.recruitment.tavernAnchor();
        traveler.moveTo(anchor.getX() + .5D, anchor.getY(), anchor.getZ() + .5D, 0.0F, 0.0F);
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.recruitment.status() == RecruitmentTransaction.Status.WAITING_ADMISSION,
            "the unstaffed Tavern visitor must physically arrive before recruitment is considered");

        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        int foodBefore = count(h, Items.BREAD);
        int revision = s.recruitment.revision();
        helper.assertTrue(assess(helper, s, RecruitmentPolicy.Stage.WAITING_ADMISSION).blocker()
                == RecruitmentPolicy.Blocker.NO_BED
            && SettlementManager.admitWaitingTraveler(player, s, traveler.getUUID(), revision)
                == SettlementManager.AdmissionResult.BLOCKED_POLICY
            && count(h, Items.BREAD) == foodBefore && s.record(traveler.getUUID()) == null,
            "no-bed admission must refuse without consuming food or turning the visitor into a resident");

        admissionBed(helper, s);
        helper.assertTrue(assess(helper, s, RecruitmentPolicy.Stage.WAITING_ADMISSION).blocker()
                == RecruitmentPolicy.Blocker.CANNOT_PAY
            && SettlementManager.admitWaitingTraveler(player, s, traveler.getUUID(), revision)
                == SettlementManager.AdmissionResult.BLOCKED_POLICY
            && count(h, com.hearthstead.registry.ModItems.GOLD_COIN.get()) == 0
            && count(h, Items.BREAD) == foodBefore && s.record(traveler.getUUID()) == null,
            "after housing appears, zero Coins must still refuse admission without charging any item");
        helper.succeed();
    }
    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void LowMoraleHasConcreteBlocker(GameTestHelper helper) {
        Settlement s = eligible(helper);
        s.moraleCache = 59;
        helper.assertTrue(assess(helper, s, RecruitmentPolicy.Stage.ATTRACTION).blocker()
                == RecruitmentPolicy.Blocker.LOW_MORALE,
            "morale 59 must fail the explicit 60 threshold");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void MissingPriceHasConcreteBlocker(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s);
        hearth(helper).insertGoods(new ItemStack(Items.BREAD, 64));
        spawnWaitingTraveler(helper, s);
        helper.assertTrue(assess(helper, s, RecruitmentPolicy.Stage.WAITING_ADMISSION).blocker()
                == RecruitmentPolicy.Blocker.CANNOT_PAY,
            "actual WAITING guest requires physical coins; abundant meals cannot pay");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void PostPaymentFoodHasConcreteBlocker(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        tavern(helper, s); s.moraleCache = 60;
        admissionBed(helper, s);
        hearth(helper).insertGoods(new ItemStack(Items.BREAD, 6));
        hearth(helper).insertGoods(new ItemStack(com.hearthstead.registry.ModItems.GOLD_COIN.get(), 4));
        RecruitmentPolicy.Assessment a = assess(helper, s,
            RecruitmentPolicy.Stage.WAITING_ADMISSION);
        helper.assertTrue(a.blocker() == RecruitmentPolicy.Blocker.INSUFFICIENT_READY_FOOD
            && a.missingReadyFood() == 2,
            "the full post-recruit reserve remains an admission-only two-meal shortfall");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void DeterministicTargetIsLockedInsideThreeToSixMinutes(GameTestHelper helper) {
        Settlement s = eligible(helper);
        int locked = s.recruitTarget;
        helper.assertTrue(locked >= RecruitmentPolicy.MIN_QUALIFIED_SECONDS
            && locked <= RecruitmentPolicy.MAX_TARGET_SECONDS,
            "new ordinary target must represent three to six qualified minutes, got " + locked);
        for (int i = 0; i < 20; i++) {
            SettlementManager.tickRecruitment(helper.getLevel(), s);
        }
        helper.assertTrue(s.recruitTarget == locked && s.recruitProgress == 20
            && s.recruitQualifiedSeconds == 20,
            "ordinary qualified ticks must not reroll or accelerate the target");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void DemoPacingKeepsExactThresholdsAndExistingEarnedLocks(GameTestHelper helper) {
        UUID settlementId = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        UUID transactionId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID tavernId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        BlockPos plaque = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos anchor = plaque.south();
        var fresh = RecruitmentTransaction.fresh(settlementId).beginQualification(
            transactionId, 100L, tavernId, plaque, anchor, helper.getLevel().dimension().location());
        helper.assertTrue(fresh.lockedTarget() >= 180 && fresh.lockedTarget() <= 360,
            "a new ordinary arrival must fit the three-to-six-minute production budget");
        var current = fresh;
        while (current.progress() < current.lockedTarget() - 1) current = current.advanceQualification();
        helper.assertTrue(current.status() == RecruitmentTransaction.Status.QUALIFYING
            && current.travelerId() == null, "one missing qualified second must still block candidate readiness");
        current = RecruitmentTransaction.readOrQuarantine(current.writeNbt(), settlementId);
        current = current.advanceQualification();
        helper.assertTrue(current.status() == RecruitmentTransaction.Status.READY_TO_SPAWN
            && current.travelerId() == null && current.transactionId().equals(transactionId),
            "the exact final second persists readiness; it does not fabricate a spawned traveler");

        // Golden pre-balance v2 nested record: this UUID/cycle locked at 3698,
        // with 2399 genuine seconds accumulated. Do not reroll or scale it.
        CompoundTag oldTag = fresh.writeNbt();
        oldTag.putInt("SchemaVersion", 2);
        oldTag.remove("TimingProfileWireId");
        oldTag.putInt("LockedTarget", 3698);
        oldTag.putInt("Progress", 2399);
        oldTag.putInt("QualifiedSeconds", 2399);
        oldTag.putInt("Revision", 2399);
        var old = RecruitmentTransaction.readOrQuarantine(oldTag, settlementId);
        helper.assertTrue(old.timingProfile()
                == RecruitmentPolicy.TimingProfile.LEGACY_LONG
            && old.lockedTarget() == 3698 && old.progress() == 2399
            && old.qualifiedSeconds() == 2399 && old.transactionId().equals(transactionId),
            "existing long target, accumulated time, transaction and Tavern identity must migrate unchanged");
        old = old.advanceQualification();
        helper.assertTrue(old.progress() == 2400 && old.qualifiedSeconds() == 2400
            && old.status() == RecruitmentTransaction.Status.QUALIFYING,
            "an existing two-day minimum is retained and does not shorten the locked target");
        while (old.progress() < 3697) old = old.advanceQualification();
        helper.assertTrue(old.status() == RecruitmentTransaction.Status.QUALIFYING,
            "legacy readiness still waits for its own last qualified second");
        old = old.advanceQualification();
        helper.assertTrue(old.status() == RecruitmentTransaction.Status.READY_TO_SPAWN
            && old.lockedTarget() == 3698 && old.transactionId().equals(transactionId),
            "old qualification completes only at its original threshold under the same identity");
        CompoundTag corrupt = oldTag.copy();
        corrupt.putInt("LockedTarget", 3699);
        helper.assertTrue(RecruitmentTransaction.readOrQuarantine(corrupt, settlementId).status()
            == RecruitmentTransaction.Status.QUARANTINED,
            "legacy support admits only the exact historical target, not an arbitrary old-range number");

        CompoundTag oldTenToTwentyTag = fresh.writeNbt();
        oldTenToTwentyTag.putInt("SchemaVersion", 2);
        oldTenToTwentyTag.remove("TimingProfileWireId");
        oldTenToTwentyTag.putInt("LockedTarget", 876);
        oldTenToTwentyTag.putInt("Progress", 599);
        oldTenToTwentyTag.putInt("QualifiedSeconds", 599);
        oldTenToTwentyTag.putInt("Revision", 599);
        var oldTenToTwenty = RecruitmentTransaction.readOrQuarantine(
            oldTenToTwentyTag, settlementId);
        helper.assertTrue(oldTenToTwenty.timingProfile()
                == RecruitmentPolicy.TimingProfile.LEGACY_10_TO_20
            && oldTenToTwenty.lockedTarget() == 876
            && oldTenToTwenty.progress() == 599
            && oldTenToTwenty.qualifiedSeconds() == 599,
            "a schema-v2 ten-to-twenty-minute lock must retain its exact target and earned seconds");
        oldTenToTwenty = oldTenToTwenty.advanceQualification();
        helper.assertTrue(oldTenToTwenty.progress() == 600
            && oldTenToTwenty.qualifiedSeconds() == 600
            && oldTenToTwenty.status() == RecruitmentTransaction.Status.QUALIFYING,
            "a legacy ten-to-twenty-minute lock must not shorten its target or minimum");

        var watch = RecruitmentTransaction.fresh(settlementId).beginQualification(transactionId,
            100L, tavernId, plaque, anchor, helper.getLevel().dimension().location(), true);
        helper.assertTrue(watch.lockedTarget() == 428,
            "the pre-existing Journey-gated Watch target for this UUID must not change");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void OverlappingTimingProfilesRemainDistinctAcrossSaveLoad(GameTestHelper helper) {
        UUID settlementId = UUID.fromString("00000000-0000-0000-0000-000000000217");
        UUID transactionId = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
        UUID tavernId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        BlockPos plaque = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos anchor = plaque.south();
        var ordinary = RecruitmentTransaction.fresh(settlementId).beginQualification(
            transactionId, 100L, tavernId, plaque, anchor,
            helper.getLevel().dimension().location());
        var watch = RecruitmentTransaction.fresh(settlementId).beginQualification(
            transactionId, 100L, tavernId, plaque, anchor,
            helper.getLevel().dimension().location(), true);
        helper.assertTrue(ordinary.lockedTarget() == 296 && watch.lockedTarget() == 296
            && ordinary.timingProfile() == RecruitmentPolicy.TimingProfile.ORDINARY_48H
            && watch.timingProfile() == RecruitmentPolicy.TimingProfile.CALL_TO_ARMS,
            "the 240-360 overlap must be distinguished by persisted timing profile, never target alone");

        ordinary = RecruitmentTransaction.readOrQuarantine(ordinary.writeNbt(), settlementId);
        CompoundTag oldWatchTag = watch.writeNbt();
        oldWatchTag.putInt("SchemaVersion", 2);
        oldWatchTag.remove("TimingProfileWireId");
        watch = RecruitmentTransaction.readOrQuarantine(oldWatchTag, settlementId);
        helper.assertTrue(ordinary.timingProfile() == RecruitmentPolicy.TimingProfile.ORDINARY_48H
            && watch.timingProfile() == RecruitmentPolicy.TimingProfile.CALL_TO_ARMS
            && ordinary.transactionId().equals(transactionId)
            && watch.transactionId().equals(transactionId),
            "both current and v2 roundtrips retain profile and transaction identity before pacing");
        for (int second = 1; second < RecruitmentPolicy.MIN_QUALIFIED_SECONDS; second++) {
            ordinary = ordinary.advanceQualification();
            watch = watch.advanceQualification();
        }
        helper.assertTrue(ordinary.status() == RecruitmentTransaction.Status.QUALIFYING
            && ordinary.progress() == 180 && ordinary.qualifiedSeconds() == 180
            && watch.status() == RecruitmentTransaction.Status.QUALIFYING
            && watch.progress() == 180 && watch.qualifiedSeconds() == 180,
            "ordinary and Call to Arms clocks both remain qualifying at the ordinary 180-second cap");
        for (int second = RecruitmentPolicy.MIN_QUALIFIED_SECONDS;
             second < RecruitmentPolicy.CALL_TO_ARMS_MIN_SECONDS - 1; second++) {
            ordinary = ordinary.advanceQualification();
            watch = watch.advanceQualification();
        }
        helper.assertTrue(ordinary.status() == RecruitmentTransaction.Status.QUALIFYING
            && ordinary.progress() == 239 && ordinary.qualifiedSeconds() == 180
            && watch.status() == RecruitmentTransaction.Status.QUALIFYING
            && watch.progress() == 239 && watch.qualifiedSeconds() == 239,
            "ordinary remains capped at 180 while Call to Arms continues to 240 below target 296");
        ordinary = ordinary.advanceQualification();
        watch = watch.advanceQualification();
        helper.assertTrue(ordinary.status() == RecruitmentTransaction.Status.QUALIFYING
            && ordinary.progress() == 240 && ordinary.qualifiedSeconds() == 180
            && watch.status() == RecruitmentTransaction.Status.QUALIFYING
            && watch.progress() == 240 && watch.qualifiedSeconds() == 240,
            "both profiles remain qualifying after their minimums while the shared target is 296");
        while (ordinary.progress() < 295) {
            ordinary = ordinary.advanceQualification();
            watch = watch.advanceQualification();
        }
        helper.assertTrue(ordinary.status() == RecruitmentTransaction.Status.QUALIFYING
            && watch.status() == RecruitmentTransaction.Status.QUALIFYING
            && ordinary.progress() == 295 && watch.progress() == 295,
            "both profiles remain qualifying at 295, one second below the locked target");
        ordinary = ordinary.advanceQualification();
        watch = watch.advanceQualification();
        ordinary = RecruitmentTransaction.readOrQuarantine(ordinary.writeNbt(), settlementId);
        watch = RecruitmentTransaction.readOrQuarantine(watch.writeNbt(), settlementId);
        helper.assertTrue(ordinary.status() == RecruitmentTransaction.Status.READY_TO_SPAWN
            && watch.status() == RecruitmentTransaction.Status.READY_TO_SPAWN
            && ordinary.lockedTarget() == 296 && watch.lockedTarget() == 296
            && ordinary.timingProfile() == RecruitmentPolicy.TimingProfile.ORDINARY_48H
            && watch.timingProfile() == RecruitmentPolicy.TimingProfile.CALL_TO_ARMS
            && ordinary.transactionId().equals(transactionId)
            && watch.transactionId().equals(transactionId)
            && ordinary.progress() == 296 && watch.progress() == 296
            && ordinary.qualifiedSeconds() == 180 && watch.qualifiedSeconds() == 240,
            "both profiles become ready exactly at 296 and retain profile, identity and capped clocks after reload");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void OrdinaryOverlapAdoptsCallToArmsExactlyOnce(GameTestHelper helper) {
        UUID settlementId = UUID.fromString("00000000-0000-0000-0000-000000000217");
        UUID transactionId = UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff");
        UUID tavernId = UUID.fromString("22222222-3333-4444-5555-666666666666");
        BlockPos plaque = helper.absolutePos(new BlockPos(2, 1, 2));
        BlockPos anchor = plaque.south();
        var ordinary = RecruitmentTransaction.fresh(settlementId).beginQualification(
            transactionId, 100L, tavernId, plaque, anchor,
            helper.getLevel().dimension().location());
        helper.assertTrue(ordinary.lockedTarget() == 296
            && ordinary.timingProfile() == RecruitmentPolicy.TimingProfile.ORDINARY_48H,
            "the regression starts with an ordinary profile whose target equals Call to Arms");
        for (int second = 1; second < 120; second++) ordinary = ordinary.advanceQualification();
        int revisionBefore = ordinary.revision();
        int progressBefore = ordinary.progress();
        UUID identityBefore = ordinary.transactionId();
        var adopted = ordinary.adoptCallToArmsWindow();
        helper.assertTrue(adopted.timingProfile() == RecruitmentPolicy.TimingProfile.CALL_TO_ARMS
            && adopted.lockedTarget() == 296 && adopted.progress() == progressBefore
            && adopted.qualifiedSeconds() == ordinary.qualifiedSeconds()
            && adopted.transactionId().equals(identityBefore)
            && adopted.revision() == revisionBefore + 1
            && adopted.status() == RecruitmentTransaction.Status.QUALIFYING,
            "an exact-296 ordinary profile adopts Call to Arms once while retaining identity, target and progress");
        var replay = adopted.adoptCallToArmsWindow();
        helper.assertTrue(replay.equals(adopted),
            "the existing Call to Arms profile guard makes adoption a no-op on replay");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void ContinuousEligibilityNeedsEveryLockedSecond(GameTestHelper helper) {
        Settlement s = eligible(helper);
        int locked = s.recruitTarget;
        var level = helper.getLevel();
        long originalDayTime = level.getDayTime();
        try {
            level.setDayTime(7L * 24_000L + SettlementManager.TAVERN_VISITOR_ARRIVAL_TIME - 1L);
            for (int second = 0; second < locked - 1; second++) {
                SettlementManager.tickRecruitment(level, s);
            }
            helper.assertTrue(s.travelerId == null && s.recruitProgress == locked - 1
                && s.recruitTarget == locked && s.recruitCycle == 0,
                "a traveler must not appear one qualified second before the locked target");
            SettlementManager.tickRecruitment(level, s);
            helper.assertTrue(s.recruitment.status()
                    == RecruitmentTransaction.Status.READY_TO_SPAWN
                    && s.travelerId == null,
                "the final qualified second must first persist READY_TO_SPAWN");
            helper.assertTrue(!SettlementManager.tavernVisitorArrivalDue(level, s),
                "a natural ready visitor must remain queued until the shared evening batch");
            long evening = 7L * 24_000L + SettlementManager.TAVERN_VISITOR_ARRIVAL_TIME;
            level.setDayTime(evening);
            SettlementManager.tickRecruitment(level, s);
            helper.assertTrue(s.recruitment.status()
                    == RecruitmentTransaction.Status.TRAVELING
                    && s.travelerId != null,
                "the same completed visitor publishes at its first eligible evening without rerolling");
            helper.succeed();
        } finally {
            level.setDayTime(originalDayTime);
        }
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void BlockedClocksPauseTogether(GameTestHelper helper) {
        Settlement s = eligible(helper);
        for (int i = 0; i < 5; i++) {
            SettlementManager.tickRecruitment(helper.getLevel(), s);
        }
        RecruitmentTransaction beforeBlocked = s.recruitment;
        UUID transactionId = beforeBlocked.transactionId();
        int lockedTarget = beforeBlocked.lockedTarget();
        Building locked = s.buildings.stream()
            .filter(b -> b.id.equals(s.recruitment.tavernBuildingId()))
            .findFirst().orElseThrow();
        boolean originalValid = locked.valid;
        locked.valid = false;
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.recruitment.equals(beforeBlocked)
                && s.recruitment.transactionId().equals(transactionId)
                && s.recruitment.lockedTarget() == lockedTarget
                && s.recruitProgress == 5 && s.recruitQualifiedSeconds == 5
                && s.travelerId == null,
            "an invalid locked Tavern must pause both clocks without decay, reset or reroll");
        locked.valid = originalValid;
        RecruitmentTransaction roundtrip = RecruitmentTransaction.readOrQuarantine(
            s.recruitment.writeNbt(), s.id);
        s.applyRecruitment(roundtrip);
        SettlementManager.tickRecruitment(helper.getLevel(), s);
        helper.assertTrue(s.recruitProgress == 6 && s.recruitQualifiedSeconds == 6
                && s.recruitment.transactionId().equals(transactionId)
                && s.recruitment.lockedTarget() == lockedTarget
                && s.travelerId == null,
            "restoring the exact Tavern and reloading the transaction resumes one qualified second");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void TwoPlayersProduceOnePaidAdmissionWinner(GameTestHelper helper) {
        Settlement s = eligible(helper);
        SettlerEntity traveler = spawnWaitingTraveler(helper, s);
        HearthBlockEntity h = hearth(helper);
        var quote = s.recruitment.quote();
        helper.assertTrue(quote.version() == 2, "fresh actual candidate uses Coins");
        helper.assertTrue(h.insertGoods(new ItemStack(com.hearthstead.registry.ModItems.GOLD_COIN.get(), quote.coins())).isEmpty(), "stock exact frozen coin price");
        int coinsBefore = count(h, com.hearthstead.registry.ModItems.GOLD_COIN.get());
        int breadBefore = count(h, Items.BREAD);
        int planksBefore = count(h, Items.OAK_PLANKS);
        int revision = s.recruitment.revision();

        ServerPlayer winner = helper.makeMockServerPlayerInLevel();
        ServerPlayer replay = helper.makeMockServerPlayerInLevel();
        var winnerInventoryBefore = winner.getInventory().save(new net.minecraft.nbt.ListTag());
        var replayInventoryBefore = replay.getInventory().save(new net.minecraft.nbt.ListTag());
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
        helper.assertTrue(breadAfterWinner == breadBefore - quote.bread()
                && planksAfterWinner == planksBefore - quote.planks()
                && count(h, Items.BREAD) == breadAfterWinner
                && count(h, Items.OAK_PLANKS) == planksAfterWinner,
            "winner pays one exact price; stale admit/reject replays mutate nothing");
        helper.assertTrue(s.recruitment.admissionReceipt() != null
                && winner.getUUID().equals(
                    s.recruitment.admissionReceipt().playerId())
                && s.recruitment.admissionReceipt().removedItemCount() == quote.coins(),
            "persisted receipt must name the winning actor and exact item delta");
        helper.assertTrue(count(h, com.hearthstead.registry.ModItems.GOLD_COIN.get()) == coinsBefore - quote.coins()
                && winnerInventoryBefore.equals(winner.getInventory().save(new net.minecraft.nbt.ListTag()))
                && replayInventoryBefore.equals(replay.getInventory().save(new net.minecraft.nbt.ListTag())),
            "one common treasury payment; both actual player inventories remain unchanged including losing replay");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void AdmissionFailuresConserveItemsAndMembership(GameTestHelper helper) {
        Settlement s = eligible(helper);
        SettlerEntity traveler = spawnWaitingTraveler(helper, s);
        HearthBlockEntity h = hearth(helper);
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        int revision = s.recruitment.revision();

        for (int slot = 0; slot < h.getInventory().getSlots(); slot++) {
            if (h.getInventory().getStackInSlot(slot).is(com.hearthstead.registry.ModItems.GOLD_COIN.get())) {
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

        h.insertGoods(new ItemStack(com.hearthstead.registry.ModItems.GOLD_COIN.get(), s.recruitment.quote().coins()));
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void CourierFoodTargetScalesPastTwentyFour(GameTestHelper helper) {
        Settlement s = settlement(helper, true);
        addResidents(s, 3);
        HearthBlockEntity h = hearth(helper);
        h.insertGoods(new ItemStack(Items.BREAD, 64));
        int target = RecruitmentPolicy.assess(helper.getLevel(), s,
            RecruitmentPolicy.Stage.ATTRACTION).courierReadyFoodTarget();
        helper.assertTrue(target == 32 && target > 24,
            "three residents plus next recruit need32 meals; coins add no food exposure, got "
                + target);
        helper.succeed();
    }

    // ------------------------------------------------------------- Data v2

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void LegacyVersionTwoPreservesCycleButDropsUnverifiableClocks(GameTestHelper helper) {
        UUID id = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        Settlement original = new Settlement(id, "Ny Matvik", BlockPos.ZERO);
        original.recruitCycle = 4;
        original.recruitTarget = 923; // New-policy golden target, safely above progress777.
        original.recruitProgress = 777;
        original.recruitQualifiedSeconds = 600;
        Settlement loaded = Settlement.readNbt(original.writeNbt(), 2);
        helper.assertTrue(loaded.recruitCycle == 4
            && loaded.recruitTarget == RecruitmentPolicy.targetFor(id, 4)
            && loaded.recruitProgress == 0
            && loaded.recruitQualifiedSeconds == 0
            && loaded.recruitment.status() == RecruitmentTransaction.Status.ATTRACTING
            && loaded.recruitment.transactionId() == null
            && !loaded.recruitment.hasLockedTavern(),
            "v2 can preserve cycle balance but cannot invent earned clocks or a Tavern identity");
        // Exact historical cycle4 target2774 with an earned two-day minimum.
        // Unlike nested v7 authority, loose v2-v6 scalars cannot prove a Tavern lock.
        CompoundTag historical = original.writeNbt();
        historical.putInt("RecruitTarget", 2774);
        historical.putInt("RecruitProgress", 2500);
        historical.putInt("RecruitQualifiedSeconds", 2400);
        for (int version = 2; version <= 6; version++) {
            Settlement old = Settlement.readNbt(historical.copy(), version);
            helper.assertTrue(old.recruitCycle == 4
                && old.recruitTarget == RecruitmentPolicy.targetFor(id, 4)
                && old.recruitProgress == 0 && old.recruitQualifiedSeconds == 0
                && old.recruitment.status() == RecruitmentTransaction.Status.ATTRACTING
                && old.recruitment.transactionId() == null && !old.recruitment.hasLockedTavern(),
                "valid historical loose clocks preserve cycle4, then safely start fresh without Tavern proof");
        }
        CompoundTag malformed = historical.copy();
        malformed.putInt("RecruitTarget", 2775);
        helper.assertTrue(Settlement.readNbt(malformed, 2).recruitCycle == 0,
            "an arbitrary number in the old range cannot preserve a fabricated cycle");
        malformed = historical.copy();
        malformed.putInt("RecruitQualifiedSeconds", 2401);
        helper.assertTrue(Settlement.readNbt(malformed, 2).recruitCycle == 0,
            "historical qualification above its actual old minimum must still fail closed");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
    public void VersionOneMigrationIsDeterministicAcrossReloads(GameTestHelper helper) {
        UUID id = UUID.fromString("b8b8d112-3174-4dd0-a893-5a7a8cf68f9b");
        Settlement original = new Settlement(id, "Fast Matvik", BlockPos.ZERO);
        CompoundTag tag = original.writeNbt();
        tag.putInt("RecruitProgress", 73);
        tag.putInt("RecruitTarget", 263);
        Settlement first = Settlement.readNbt(tag.copy(), 1);
        Settlement second = Settlement.readNbt(tag.copy(), 1);
        helper.assertTrue(first.recruitTarget == RecruitmentPolicy.targetFor(id, 0),
            "the fixed UUID migrates to the new deterministic cycle-zero target without earned-clock invention");
        helper.assertTrue(first.recruitTarget == second.recruitTarget
            && first.recruitProgress == second.recruitProgress
            && first.recruitQualifiedSeconds == second.recruitQualifiedSeconds
            && first.recruitProgress == 0
            && first.recruitTarget == RecruitmentPolicy.targetFor(id, 0),
            "identical legacy tags must reset identically without load-time relocking");
        helper.succeed();
    }

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    @GameTest(batch = "recruitment_policy", template = "empty32", timeoutTicks = 100)
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

    /** Payment tests still require a real, free bed; abstract founder capacity is insufficient. */
    private static void admissionBed(GameTestHelper helper, Settlement settlement) {
        if (com.hearthstead.settlement.BuildingManager.findFreeBed(helper.getLevel(), settlement) != null) return;
        Building home = GameTestFixtures.register(helper, settlement, BuildingType.HOUSE, 2, 7);
        BlockPos foot = new BlockPos(3, 1, 8);
        BlockPos head = foot.south();
        var state = Blocks.RED_BED.defaultBlockState()
            .setValue(net.minecraft.world.level.block.BedBlock.FACING, net.minecraft.core.Direction.SOUTH);
        helper.setBlock(foot, state.setValue(net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.FOOT));
        helper.setBlock(head, state.setValue(net.minecraft.world.level.block.BedBlock.PART,
            net.minecraft.world.level.block.state.properties.BedPart.HEAD));
        home.beds.add(helper.absolutePos(head));
        helper.assertTrue(com.hearthstead.settlement.BuildingManager.findFreeBed(helper.getLevel(), settlement)
            .equals(helper.absolutePos(head)), "payment fixture must expose the real unclaimed bed head");
    }
}
