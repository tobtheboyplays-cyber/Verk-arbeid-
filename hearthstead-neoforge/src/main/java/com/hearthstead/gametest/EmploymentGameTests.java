package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerAttributes;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Trait;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.DayPhase;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * SLICE JOBS-1 — hire and dismissal, the settler's five numbers, and the
 * village clock.
 *
 * <p>Each test here is one line of {@code docs/project/PLAN_EMPLOYMENT.md}
 * section 4 or of the attribute design, written so that breaking the rule in
 * the code fails the test by name rather than by a mystery.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class EmploymentGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /**
     * A settlement the entity layer can actually find.
     *
     * <p>Registered with {@link com.hearthstead.settlement.SettlementSavedData},
     * because {@code settler.settlement()} resolves by id through the manager:
     * a bare Settlement object is invisible to every goal, and the symptom is
     * a settler who simply stands there with no error anywhere.
     */
    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        // Deliberately does NOT sweep settlements inside the arena bounds the
        // way the older fixture does. Tests share one level, arenas sit next
        // to each other, and a sweep from one test deleted the settlement
        // another was counting settlers in -- which surfaced as
        // "expected 3 initial settlers, got 1" in a test this file never
        // touches. Each test here makes its own settlement and leaves
        // everyone else's alone.
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        // Small on purpose. GameTest arenas sit close together and
        // SettlementManager.at() resolves by radius, so a generous test
        // settlement answers for its NEIGHBOUR's hearth -- which is how
        // foundingSpawnsSettlers came to report "expected 3 initial
        // settlers, got 1" while testing something else entirely.
        s.radius = 6;
        data.settlements.put(s.id, s);
        data.setDirty();
        return s;
    }

    private static Building building(GameTestHelper helper, Settlement s,
                                     BuildingType type, int x, int z) {
        // Delegates to the one place that places the plaque a building
        // needs to survive BuildingManager's sweep -- see GameTestFixtures
        // (KF-021 / FLAKE-2, 2026-08-26).
        return GameTestFixtures.register(helper, s, type, x, z);
    }

    private static SettlerEntity settler(GameTestHelper helper, Settlement s,
                                         String name, int x, int z) {
        SettlerEntity settler = helper.spawn(ModEntities.SETTLER.get(),
            new BlockPos(x, 1, z));
        settler.setSettlerName(name);
        settler.bindTo(s.id, s.center);
        s.putRecord(settler.getUUID(), name, Profession.NONE);
        return settler;
    }

    /**
     * Binds the real plaque block entity placed by {@link GameTestFixtures}
     * to its synthetic fixture building. The fixture is intentionally a real
     * standing plaque; surveys below call the production public API rather
     * than duplicating or reflecting the private unlink branch.
     */
    private static PlaqueBlockEntity linkedFixturePlaque(GameTestHelper helper,
                                                          Settlement settlement,
                                                          Building building) {
        Object blockEntity = helper.getLevel().getBlockEntity(building.plaquePos);
        if (!(blockEntity instanceof PlaqueBlockEntity plaque)) {
            throw new IllegalStateException("fixture building plaque missing at "
                + building.plaquePos);
        }
        try {
            var buildingId = PlaqueBlockEntity.class.getDeclaredField("buildingId");
            buildingId.setAccessible(true);
            buildingId.set(plaque, building.id);
            var settlementId = PlaqueBlockEntity.class.getDeclaredField("settlementId");
            settlementId.setAccessible(true);
            settlementId.set(plaque, settlement.id);
            var type = PlaqueBlockEntity.class.getDeclaredField("type");
            type.setAccessible(true);
            type.set(plaque, building.type);
            var state = PlaqueBlockEntity.class.getDeclaredField("state");
            state.setAccessible(true);
            state.set(plaque, PlaqueState.LINKED_VALID);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot bind fixture plaque to "
                + building.id, failure);
        }
        return plaque;
    }

    /**
     * Fast-forwards the real scheduled survey cadence to a due tick. It does
     * not call {@code survey} directly: each reading enters through
     * {@link PlaqueBlockEntity#serverTick} just as an installed plaque does.
     */
    private static void scheduledSurveyPastFailedRoomGrace(GameTestHelper helper,
                                                           PlaqueBlockEntity plaque) {
        try {
            var nextSurveyTick = PlaqueBlockEntity.class.getDeclaredField("nextSurveyTick");
            nextSurveyTick.setAccessible(true);
            for (int attempt = 0; attempt < 4; attempt++) {
                nextSurveyTick.setLong(plaque, helper.getLevel().getGameTime());
                BlockPos pos = plaque.getBlockPos();
                PlaqueBlockEntity.serverTick(helper.getLevel(), pos,
                    helper.getLevel().getBlockState(pos), plaque);
            }
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("cannot make fixture plaque survey due", failure);
        }
    }
    // ------------------------------------------------------- the relation ---

    /**
     * D-011's central promise: hiring someone into a second building takes
     * them out of the first, in the same operation. There is no instant in
     * which a settler holds two jobs.
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void noSettlerHoldsTwoPosts(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building farm = building(helper, s, BuildingType.FARMHOUSE, 2, 2);
        Building camp = building(helper, s, BuildingType.LUMBER_CAMP, 10, 10);
        SettlerEntity astrid = settler(helper, s, "Astrid", 4, 4);

        helper.assertTrue(Employment.hire(helper.getLevel(), s, farm, astrid).ok(),
            "hiring into an empty farmhouse must succeed");
        helper.assertTrue(Employment.hire(helper.getLevel(), s, camp, astrid).ok(),
            "hiring away must succeed");

        helper.assertFalse(farm.workers.contains(astrid.getUUID()),
            "the farmhouse must no longer list a settler who left it");
        helper.assertTrue(camp.workers.contains(astrid.getUUID()),
            "the lumber camp must list its new worker");
        helper.assertTrue(astrid.getProfession() == Profession.LUMBERER,
            "the trade follows the building, got " + astrid.getProfession());
        helper.succeed();
    }

    /** A building seats what its type says, and not one more. */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void hiringStopsAtCapacity(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building farm = building(helper, s, BuildingType.FARMHOUSE, 2, 2);
        int capacity = BuildingType.FARMHOUSE.workerCapacity();

        for (int i = 0; i < capacity; i++) {
            SettlerEntity hand = settler(helper, s, "Hand" + i, 4 + i, 4);
            helper.assertTrue(Employment.hire(helper.getLevel(), s, farm, hand).ok(),
                "hire " + i + " should fit inside a capacity of " + capacity);
        }
        SettlerEntity extra = settler(helper, s, "Extra", 4, 8);
        Employment.Hired refused = Employment.hire(helper.getLevel(), s, farm, extra);

        helper.assertFalse(refused.ok(), "a full building must refuse");
        helper.assertTrue(farm.workers.size() == capacity,
            "a refused hire must not change the roster, got " + farm.workers.size());
        helper.assertTrue(extra.getProfession() == Profession.NONE,
            "a refused hire must leave the settler unemployed");
        helper.succeed();
    }

    /**
     * The sentence that offers the hire has to name what it costs. MineColonies'
     * worst habit is taking a worker silently; you find out the farm has no
     * farmer when the bread stops.
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void takingAWorkerNamesTheLoss(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building farm = building(helper, s, BuildingType.FARMHOUSE, 2, 2);
        Building camp = building(helper, s, BuildingType.LUMBER_CAMP, 10, 10);
        SettlerEntity only = settler(helper, s, "Only", 4, 4);
        SettlerEntity spare = settler(helper, s, "Spare", 5, 4);

        helper.assertTrue(Employment.costOfHiring(s, only).loses() == null,
            "an unemployed settler costs nobody anything");

        Employment.hire(helper.getLevel(), s, farm, only);
        Employment.Cost lone = Employment.costOfHiring(s, only);
        helper.assertTrue(lone.loses() == farm, "the cost must name the farmhouse");
        helper.assertTrue(lone.leavesEmpty(),
            "taking the only farmer must be reported as leaving it empty");

        Employment.hire(helper.getLevel(), s, farm, spare);
        helper.assertFalse(Employment.costOfHiring(s, only).leavesEmpty(),
            "with two hands on the farm, taking one does not empty it");

        Employment.hire(helper.getLevel(), s, camp, only);
        helper.assertTrue(farm.workers.size() == 1,
            "the farm keeps the settler who stayed");
        helper.succeed();
    }

    /**
     * A settler pointing at a building that no longer exists is the exact class
     * of bug KF-013 and KF-014 both were. Made impossible rather than findable.
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void aDissolvedBuildingKeepsNoWorkers(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building farm = building(helper, s, BuildingType.FARMHOUSE, 2, 2);
        SettlerEntity astrid = settler(helper, s, "Astrid", 4, 4);
        Employment.hire(helper.getLevel(), s, farm, astrid);

        Employment.freeWorkers(helper.getLevel(), s, farm);

        helper.assertTrue(farm.workers.isEmpty(),
            "a dissolved building must keep nobody");
        helper.assertTrue(astrid.getProfession() == Profession.NONE,
            "its workers must lose the trade with it, got " + astrid.getProfession());
        helper.assertTrue(astrid.isAlive(), "they are freed, not deleted");
        helper.succeed();
    }

    /**
     * A standing building may report a failed room survey during a sealed,
     * active first raid, but its already armed defenders must not vanish
     * before the encounter has a terminal fact. This goes through the real
     * plaque survey and its grace window, not a copied unlink implementation.
     * Once terminal, the same public survey path tears the posts down again.
     */
    @GameTest(batch = "employment_active_raid_defender_retention",
        template = "empty16", timeoutTicks = 200)
    public void activeFirstRaidDefersOnlyMartialScanTeardown(
            GameTestHelper helper) {
        floor(helper, 16);
        Settlement settlement = settlement(helper);
        Building barracks = building(helper, settlement, BuildingType.BARRACKS,
            4, 4);
        Building tower = building(helper, settlement, BuildingType.WATCHTOWER,
            10, 4);
        Building emptyBarracks = building(helper, settlement, BuildingType.BARRACKS,
            4, 10);
        Building farmhouse = building(helper, settlement, BuildingType.FARMHOUSE,
            10, 10);
        SettlerEntity guard = settler(helper, settlement, "Holding Guard", 6, 6);
        SettlerEntity archer = settler(helper, settlement, "Holding Archer", 12, 6);
        SettlerEntity farmer = settler(helper, settlement, "Released Farmer", 12, 12);

        helper.assertTrue(Employment.hire(helper.getLevel(), settlement, barracks, guard).ok()
                && Employment.hire(helper.getLevel(), settlement, tower, archer).ok()
                && Employment.hire(helper.getLevel(), settlement, farmhouse, farmer).ok(),
            "fixture: exact standing buildings must own their named workers");
        guard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        archer.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.BOW));
        GuardOrder guardOrder = settlement.guardOrders.orderForMutation(
            settlement.id, guard.getUUID(), helper.getLevel().dimension().location())
            .orElseThrow();
        GuardOrder archerOrder = settlement.guardOrders.orderForMutation(
            settlement.id, archer.getUUID(), helper.getLevel().dimension().location())
            .orElseThrow();
        helper.assertTrue(guardOrder.issueStand(guard.blockPosition(), Direction.NORTH,
                GuardOrder.DEFAULT_LEASH_RADIUS, UUID.randomUUID(), barracks.id,
                helper.getLevel().getGameTime())
                && archerOrder.issueStand(archer.blockPosition(), Direction.NORTH,
                    GuardOrder.DEFAULT_LEASH_RADIUS, UUID.randomUUID(), tower.id,
                    helper.getLevel().getGameTime()),
            "fixture: both existing defenders need an exact persisted post");

        PlaqueBlockEntity barracksPlaque = linkedFixturePlaque(helper, settlement, barracks);
        PlaqueBlockEntity towerPlaque = linkedFixturePlaque(helper, settlement, tower);
        PlaqueBlockEntity farmhousePlaque = linkedFixturePlaque(helper, settlement, farmhouse);
        RaidLifecycle lifecycle = settlement.raidLifecycle;
        RaidPlan plan = new RaidPlan(UUID.randomUUID(), RaidObjective.BLOD, 0.0F, 4L);
        RaiderEntity participant = helper.spawn(ModEntities.RAIDER.get(),
            new BlockPos(8, 1, 8));
        participant.assign(plan.captainId(), settlement.id, plan.objective(), 1.0F, false);
        helper.assertTrue(lifecycle.initializeAtFounding(0L, 4, 1)
                && lifecycle.queueFirstPlan(plan)
                && lifecycle.beginFirstRaid(plan)
                && lifecycle.recordParticipant(participant.getUUID())
                && lifecycle.sealParticipants()
                && lifecycle.isAuthoredFirstRaidActive()
                && lifecycle.participantsTracked(),
            "fixture: one exact sealed participant must keep the authored first raid ACTIVE");

        // These are failed real surveys: three grace readings, then the
        // fourth executes PlaqueBlockEntity.unlink for each standing plaque.
        scheduledSurveyPastFailedRoomGrace(helper, barracksPlaque);
        scheduledSurveyPastFailedRoomGrace(helper, towerPlaque);
        scheduledSurveyPastFailedRoomGrace(helper, farmhousePlaque);

        helper.assertTrue(barracksPlaque.state() == PlaqueState.PLAN_INSERTED_UNLINKED
                && towerPlaque.state() == PlaqueState.PLAN_INSERTED_UNLINKED
                && barracks.valid && tower.valid
                && barracks.workers.contains(guard.getUUID())
                && tower.workers.contains(archer.getUUID())
                && guard.getProfession() == Profession.GUARD
                && archer.getProfession() == Profession.ARCHER
                && guard.getMainHandItem().is(Items.IRON_SWORD)
                && archer.getMainHandItem().is(Items.BOW)
                && settlement.guardOrders.order(guard.getUUID()).orElseThrow() == guardOrder
                && settlement.guardOrders.order(archer.getUUID()).orElseThrow() == archerOrder,
            "the real failed-room unlink keeps only sealed-active martial posts, "
                + "including their exact worker, weapon and order state");
        helper.assertFalse(Employment.defersMartialUnlinkForActiveFirstRaid(
                settlement, emptyBarracks),
            "an empty Barracks must not claim the active-raid retention exception");
        helper.assertTrue(farmhousePlaque.state() == PlaqueState.PLAN_INSERTED_UNLINKED
                && !farmhouse.valid && farmhouse.workers.isEmpty()
                && farmer.getProfession() == Profession.NONE,
            "an occupied non-martial building must still use the ordinary failed-room unlink");

        helper.assertTrue(lifecycle.recordTerminalParticipant(participant.getUUID())
                && lifecycle.completeFirstRaid(false),
            "fixture: the sealed participant must close the first raid before teardown");
        participant.discard();
        helper.assertFalse(Employment.defersMartialUnlinkForActiveFirstRaid(
                settlement, barracks),
            "a terminal raid must return Barracks teardown to its ordinary path");

        // The plaque remains visibly unlinked while the sealed raid runs.
        // Once terminal, another public failed-room grace cycle reaches the
        // unchanged unlink path and releases the two defender posts.
        scheduledSurveyPastFailedRoomGrace(helper, barracksPlaque);
        scheduledSurveyPastFailedRoomGrace(helper, towerPlaque);
        helper.assertTrue(!barracks.valid && !tower.valid
                && barracks.workers.isEmpty() && tower.workers.isEmpty()
                && guard.getProfession() == Profession.NONE
                && archer.getProfession() == Profession.NONE,
            "the real post-terminal survey must resume ordinary defender teardown");
        helper.succeed();
    }
    /**
     * The profession on the settler is a projection of the settlement's record,
     * never a second copy of it. Tamper with the record and the projection must
     * follow, not argue.
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void professionIsDerivedNeverStored(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building farm = building(helper, s, BuildingType.FARMHOUSE, 2, 2);
        SettlerEntity astrid = settler(helper, s, "Astrid", 4, 4);
        Employment.hire(helper.getLevel(), s, farm, astrid);
        helper.assertTrue(astrid.getProfession() == Profession.FARMER,
            "hired into a farmhouse, they farm");

        // Go behind the service's back, the way a bug would.
        farm.workers.remove(astrid.getUUID());
        Employment.refresh(s, astrid);

        helper.assertTrue(astrid.getProfession() == Profession.NONE,
            "the settlement's record wins; the settler holds no opinion of "
                + "their own, got " + astrid.getProfession());
        helper.succeed();
    }

    /** Dismissal costs them something and leaves them standing in the village. */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void dismissalLeavesThemInTheVillage(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building farm = building(helper, s, BuildingType.FARMHOUSE, 2, 2);
        SettlerEntity astrid = settler(helper, s, "Astrid", 4, 4);
        Employment.hire(helper.getLevel(), s, farm, astrid);
        float before = astrid.getMorale();

        Building left = Employment.dismiss(helper.getLevel(), s, astrid);

        helper.assertTrue(left == farm, "dismissal must report the building they left");
        helper.assertTrue(astrid.isAlive(), "a dismissed settler is not deleted");
        helper.assertTrue(astrid.getProfession() == Profession.NONE,
            "a dismissed settler holds no trade");
        helper.assertTrue(astrid.getMorale() < before,
            "being let go costs morale: " + before + " -> " + astrid.getMorale());
        helper.assertTrue(Employment.employerOf(s, astrid.getUUID()) == null,
            "and no employer");
        helper.succeed();
    }

    /**
     * A building whose trade is not implemented refuses honestly instead of
     * seating a worker who would stand there doing nothing (D-014).
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void aBuildingWithNoTradeRefusesHiring(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        // The example is DERIVED, never named: this test used to hard-code the
        // brewery, and the night the brewery got a brewer the test failed for
        // the best possible reason -- the mod had grown. Ask the trade table
        // itself which building is still unstaffed, so the check survives
        // every future trade landing.
        BuildingType tradeless = null;
        for (BuildingType type : BuildingType.values()) {
            if (Employment.tradeOf(type) == com.hearthstead.entity.Profession.NONE) {
                tradeless = type;
                break;
            }
        }
        if (tradeless == null) {
            // Every building employs somebody. The rule still holds, there is
            // simply nothing left to test it on -- and saying so is honest,
            // where quietly passing would not be.
            helper.succeed();
            return;
        }
        Building unstaffed = building(helper, s, tradeless, 2, 2);
        SettlerEntity astrid = settler(helper, s, "Astrid", 4, 4);

        Employment.Hired result = Employment.hire(helper.getLevel(), s, unstaffed, astrid);

        helper.assertFalse(result.ok(),
            "a trade that does not exist cannot be taken up (" + tradeless.id() + ")");
        helper.assertTrue(result.refusal() != null, "and it must say why");
        helper.assertTrue(unstaffed.workers.isEmpty(), "nobody is seated");
        helper.succeed();
    }

    // -------------------------------------------------------- the numbers ---

    /**
     * The owner's rule, enforced: a settler arrives at 15 out of 100 at the
     * very best. Everything above that is earned by doing the work.
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void nobodyArrivesBetterThanFifteen(GameTestHelper helper) {
        RandomSource random = RandomSource.create(20260825L);
        int best = 0;
        long total = 0;
        int samples = 0;
        for (int i = 0; i < 4000; i++) {
            SettlerAttributes rolled = SettlerAttributes.roll(random);
            for (Attribute attribute : Attribute.ALL) {
                int value = rolled.get(attribute);
                best = Math.max(best, value);
                total += value;
                samples++;
            }
        }
        helper.assertTrue(best <= SettlerAttributes.START_CAP,
            "no newcomer may exceed " + SettlerAttributes.START_CAP + ", saw " + best);
        double mean = (double) total / samples;
        // The cap alone is not the design -- a flat roll of 1..15 would pass it
        // and make every newcomer a solid seven. The shape has to stay crushed
        // towards the bottom.
        helper.assertTrue(mean < 7.0,
            "newcomers must be mostly unremarkable; mean was " + mean);
        helper.succeed();
    }

    /** Growth is asymptotic: 100 is a direction, not a destination. */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void nobodyEverReachesTheCeiling(GameTestHelper helper) {
        SettlerAttributes a = SettlerAttributes.blank();
        for (int i = 0; i < 200000; i++) {
            a.train(Attribute.STRENGTH, 1.0F, 1.0F);
        }
        int value = a.get(Attribute.STRENGTH);
        helper.assertTrue(value < 100,
            "nobody reaches 100, got " + value);
        helper.assertTrue(value > 60,
            "but real work must get genuinely far, got " + value);
        helper.succeed();
    }

    /** The same work is worth far less to someone who is already good at it. */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void growthSlowsAsItRises(GameTestHelper helper) {
        SettlerAttributes novice = SettlerAttributes.blank();
        int noviceGain = trainFor(novice, 400);

        SettlerAttributes veteran = SettlerAttributes.blank();
        while (veteran.get(Attribute.STRENGTH) < 70) {
            veteran.train(Attribute.STRENGTH, 100.0F, 1.0F);
        }
        int veteranGain = trainFor(veteran, 400);

        helper.assertTrue(noviceGain > veteranGain * 3,
            "400 units must be worth far more to a novice (" + noviceGain
                + ") than to a veteran (" + veteranGain + ")");
        helper.succeed();
    }

    private static int trainFor(SettlerAttributes a, int units) {
        int before = a.get(Attribute.STRENGTH);
        for (int i = 0; i < units; i++) {
            a.train(Attribute.STRENGTH, 1.0F, 1.0F);
        }
        return a.get(Attribute.STRENGTH) - before;
    }

    /**
     * Every trait has to cost something.
     *
     * <p>A trait that is only an advantage is a stat point with a name, and a
     * roster of pure advantages collapses into "reroll until you get the good
     * one". This is the test that stops a future trait from quietly being free.
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void everyTraitCostsSomething(GameTestHelper helper) {
        for (Trait trait : Trait.ALL) {
            boolean pays = trait.carry() < 1.0F || trait.speed() < 1.0F
                || trait.work() < 1.0F || trait.growth() < 1.0F
                || trait.moraleGain() < 1.0F || trait.hunger() > 1.0F
                || trait.sight() < 1.0F
                || trait.has(Trait.Flag.SLOW_START)
                || trait.has(Trait.Flag.FEARFUL)
                || trait.has(Trait.Flag.NIGHT_OWL)
                || trait.has(Trait.Flag.EARLY_RISER);
            helper.assertTrue(pays,
                trait.key() + " is a pure advantage — every trait must trade");
        }
        helper.succeed();
    }

    // ---------------------------------------------------------- the clock ---

    /** One rhythm for the whole village, cut at the light the player sees. */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void theVillageRunsOnOneClock(GameTestHelper helper) {
        helper.assertTrue(DayPhase.of(23500) == DayPhase.RISE, "dawn is RISE");
        helper.assertTrue(DayPhase.of(3000).work(), "mid-morning is work");
        helper.assertTrue(DayPhase.of(6000).meal(), "noon is the meal");
        helper.assertTrue(DayPhase.of(9000).work(), "mid-afternoon is work");
        helper.assertTrue(DayPhase.of(12000).social(), "dusk is the evening");
        helper.assertTrue(DayPhase.of(18000).rest(), "midnight is rest");
        // Day two must look exactly like day one.
        helper.assertTrue(DayPhase.of(24000 + 6000).meal(),
            "the clock must wrap, not drift");
        helper.assertFalse(DayPhase.of(6000).work(),
            "the midday meal is not working hours — that break is the point");
        helper.succeed();
    }

    /**
     * A garrison that all sleeps at midnight is not a garrison. Two guards in
     * one barracks must stand opposite watches.
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void theNightWatchIsAwakeWhenTheDayWatchIsNot(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building barracks = building(helper, s, BuildingType.BARRACKS, 2, 2);
        SettlerEntity first = settler(helper, s, "Dag", 4, 4);
        SettlerEntity second = settler(helper, s, "Natt", 5, 4);
        Employment.hire(helper.getLevel(), s, barracks, first);
        Employment.hire(helper.getLevel(), s, barracks, second);

        helper.assertTrue(Employment.watchOf(s, first) != Employment.watchOf(s, second),
            "two guards in one barracks must split the clock between them");

        boolean firstUp = Schedule.onWatch(s, first, DayPhase.REST);
        boolean secondUp = Schedule.onWatch(s, second, DayPhase.REST);
        helper.assertTrue(firstUp != secondUp,
            "exactly one of them is awake at midnight");

        SettlerEntity nightGuard = firstUp ? first : second;
        helper.assertFalse(Schedule.shouldSleep(s, nightGuard, DayPhase.REST),
            "the guard standing the night watch must not also be asleep in it");
        helper.assertTrue(Schedule.shouldSleep(s, nightGuard, DayPhase.AFTERNOON_WORK),
            "they take their rest in the afternoon instead");
        helper.succeed();
    }

    /**
     * Where the day sends people: to their own building in working hours, and
     * to the table at noon.
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void theDaySendsPeopleSomewhereReal(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        // A BAKERY, not a farmhouse. Employment.worksAtTheBuilding was added
        // later and deliberately EXCLUDES the farmer and the lumberjack: their
        // work is out in the field and the trees, and posting them back to the
        // shed made a hired lumberjack orbit his camp instead of felling
        // anything (the code says so, with the sighting that found it). This
        // test still asks its real question -- does the day send a hired
        // settler to their work -- but asks it of a trade whose work IS the
        // building. Changing the schedule to satisfy the old wording would
        // reintroduce a regression somebody had to watch happen once.
        Building bakery = building(helper, s, BuildingType.BAKERY, 2, 2);
        Building hall = building(helper, s, BuildingType.DINING_HALL, 12, 12);
        SettlerEntity astrid = settler(helper, s, "Astrid", 6, 6);
        Employment.hire(helper.getLevel(), s, bakery, astrid);

        Schedule.Posting atWork = Schedule.postFor(s, astrid, DayPhase.MORNING_WORK);
        helper.assertTrue(atWork != null && atWork.where().equals(bakery.anchor),
            "in working hours a hired settler is sent to their own building");

        Schedule.Posting atNoon = Schedule.postFor(s, astrid, DayPhase.MEAL);
        helper.assertTrue(atNoon != null && atNoon.where().equals(hall.anchor),
            "at noon they are sent to the dining hall");

        helper.assertTrue(Schedule.postFor(s, astrid, DayPhase.REST) == null,
            "at night the day has nothing to say — the bed goal owns that");

        Employment.dismiss(helper.getLevel(), s, astrid);
        Schedule.Posting idle = Schedule.postFor(s, astrid, DayPhase.MORNING_WORK);
        helper.assertTrue(idle != null && "idle".equals(idle.reason()),
            "the unemployed gather in plain sight, so the player can see them");
        helper.succeed();
    }

    // ----------------------------------------------------------- the work ---

    /**
     * Every trade that exists has a motion of its own, and every building that
     * can make something has somebody who can be hired to make it.
     *
     * <p>These two together are what stop the roster drifting: a building with
     * recipes and no trade is unstaffable, and a trade with no motion would
     * fall back to standing still, which is the generic work loop the animation
     * invariant exists to forbid.
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void everyTradeHasWorkAndAMotionOfItsOwn(GameTestHelper helper) {
        for (BuildingType type : BuildingType.values()) {
            if (com.hearthstead.building.Production.produces(type)) {
                helper.assertTrue(Employment.teaches(type),
                    type.id() + " has recipes but nobody can be hired to run them");
            }
            if (!Employment.teaches(type)) {
                continue;
            }
            Profession trade = Employment.tradeOf(type);
            if (trade == Profession.FARMER || trade == Profession.LUMBERER
                || trade == Profession.COURIER || trade == Profession.GUARD) {
                continue;  // these had their own clips before CHAINS-1
            }
            helper.assertTrue(
                Employment.motionOf(type) != com.hearthstead.entity.SettlerActivity.IDLE,
                type.id() + " would work by standing still — every task needs "
                    + "its own motion");
        }
        helper.succeed();
    }

    /**
     * The whole loop, end to end: a room with wheat in its chest, a settler
     * hired into it, and bread that did not exist before.
     *
     * <p>This is the test that proves the chain the owner asked for actually
     * turns: hire someone and the building starts producing, with no mill, no
     * farm and no warehouse anywhere in the world (D-007).
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 600)
    public void aHiredBakerActuallyBakes(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building bakery = building(helper, s, BuildingType.BAKERY, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        net.minecraft.world.level.block.entity.BlockEntity be =
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        helper.assertTrue(be instanceof net.minecraft.world.Container,
            "the arena chest should be a container");
        net.minecraft.world.Container chest = (net.minecraft.world.Container) be;
        chest.setItem(0, new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.WHEAT, 12));
        // The oven burns fuel since FUEL-1 (DESIGN R20): four loaves' worth.
        chest.setItem(1, new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.CHARCOAL, 4));

        SettlerEntity astrid = settler(helper, s, "Astrid", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, bakery, astrid).ok(),
            "a bakery must be able to take a baker");
        helper.assertTrue(astrid.getProfession() == Profession.BAKER,
            "hired into a bakery, they bake");

        // Mid-morning: working hours, so the trade goal is allowed to run.
        helper.getLevel().setDayTime(3000);

        helper.succeedWhen(() -> {
            int bread = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                net.minecraft.world.item.ItemStack stack = chest.getItem(slot);
                if (stack.is(net.minecraft.world.item.Items.BREAD)) {
                    bread += stack.getCount();
                }
            }
            helper.assertTrue(bread > 0,
                "a hired baker standing in a bakery full of wheat must produce "
                    + "bread (activity=" + astrid.getActivity() + ")");
        });
    }

    /** Doing the job makes you better at it — counted on completion, not on a timer. */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void craftingTrainsTheTradeItPractises(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building smithy = building(helper, s, BuildingType.SMITHY, 4, 4);
        SettlerEntity smith = settler(helper, s, "Smed", 4, 4);
        Employment.hire(helper.getLevel(), s, smithy, smith);

        Attribute trained = Employment.trainedBy(BuildingType.SMITHY);
        helper.assertTrue(trained == Attribute.STRENGTH,
            "a smith's work is strength, got " + trained);
        int before = smith.attribute(trained);
        for (int i = 0; i < 400; i++) {
            smith.train(trained, 1.0F);
        }
        helper.assertTrue(smith.attribute(trained) > before,
            "four hundred completed strikes must move the needle: "
                + before + " -> " + smith.attribute(trained));
        helper.assertTrue(smith.attribute(Attribute.DEXTERITY)
                == smith.attributes().get(Attribute.DEXTERITY),
            "and must not quietly raise anything else");
        helper.succeed();
    }

    // ------------------------------------------------------- starter pack ---

    /** The miner cuts stone into the mine's own chests, and never into nothing. */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void aMinerNeverBreaksWhatItCannotStore(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building mine = building(helper, s, BuildingType.MINE, 4, 4);
        SettlerEntity pick = settler(helper, s, "Berg", 4, 4);

        helper.assertTrue(Employment.hire(helper.getLevel(), s, mine, pick).ok(),
            "a mine entrance must be able to take a miner");
        helper.assertTrue(pick.getProfession() == Profession.MINER,
            "hired into a mine, they mine");
        helper.assertTrue(Employment.trainedBy(BuildingType.MINE) == Attribute.STRENGTH,
            "mining is strength");
        helper.assertTrue(Employment.motionOf(BuildingType.MINE)
                == com.hearthstead.entity.SettlerActivity.WORK_MINE,
            "and it has a motion of its own");
        helper.succeed();
    }

    /**
     * The courier's legacy tidy pass only merges ordinary material: same item,
     * same total, fewer stacks. Wood and crops use the physical warehouse
     * courier route instead, so this covers the tidy pass without bypassing it.
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 1800)
    public void tidyingTheWarehouseConservesEverything(GameTestHelper helper) {
        floor(helper, 16);
        // The template's Y1 floor otherwise embeds both chests and the
        // courier spawn. Own a real aisle at the same level as the containers;
        // do not make production contact rules reach through a solid floor.
        for (int x = 3; x <= 7; x++) for (int z = 3; z <= 7; z++) {
            helper.setBlock(new BlockPos(x, 1, z), Blocks.AIR);
            helper.setBlock(new BlockPos(x, 2, z), Blocks.AIR);
        }
        BlockPos hearthRel = new BlockPos(8, 1, 8);
        helper.setBlock(hearthRel, com.hearthstead.registry.ModBlocks.HEARTH.get());
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.CHEST);
        // Keep these as two physical stores. Adjacent chests form one
        // double-chest container, which cannot prove a Courier made a leg.
        helper.setBlock(new BlockPos(6, 1, 4), Blocks.CHEST);
        net.minecraft.world.Container a = (net.minecraft.world.Container)
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 4)));
        net.minecraft.world.Container b = (net.minecraft.world.Container)
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(6, 1, 4)));
        Settlement s = settlement(helper);
        helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(hearthRel))
                instanceof com.hearthstead.block.HearthBlockEntity,
            "tidy fixture needs a real Hearth block entity");
        ((com.hearthstead.block.HearthBlockEntity) helper.getLevel().getBlockEntity(
            helper.absolutePos(hearthRel))).bindSettlement(s.id);
        Building warehouse = building(helper, s, BuildingType.WAREHOUSE, 4, 4);
        // WarehouseIndex orders real blocks by y/x/z, not fixture declaration
        // order. Ask the production selector which unit it claimed, then put
        // the cargo only in the other physical chest.
        java.util.List<BlockPos> materialTargets =
            com.hearthstead.settlement.warehouse.WarehouseSorting.destinations(
            helper.getLevel(), warehouse, new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.COBBLESTONE));
        BlockPos aPos = helper.absolutePos(new BlockPos(4, 1, 4));
        BlockPos bPos = helper.absolutePos(new BlockPos(6, 1, 4));
        helper.assertTrue(materialTargets.size() == 1
                && (materialTargets.contains(aPos) || materialTargets.contains(bPos)),
            "tidy fixture must have exactly one real Materials destination: "
                + materialTargets);
        net.minecraft.world.Container target = materialTargets.contains(aPos) ? a : b;
        net.minecraft.world.Container source = target == a ? b : a;
        BlockPos targetPos = target == a ? aPos : bPos;
        BlockPos sourcePos = source == a ? aPos : bPos;
        source.setItem(0, new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.COBBLESTONE, 9));
        source.setItem(1, new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.COBBLESTONE, 3));
        int before = count(a) + count(b);
        // (4,1,4) is one of the physical chests. Starting inside it gives
        // the normal contact path an honest NO_PATH before any cargo can move.
        SettlerEntity bud = settler(helper, s, "Bud", 5, 6);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, warehouse, bud).ok(),
            "tidy fixture must acquire real Warehouse employment authority");
        bud.setHunger(100.0F);
        bud.setEnergy(100.0F);
        helper.getLevel().setDayTime(3000);

        helper.succeedWhen(() -> {
            int inBag = count(bud.bag);
            int after = count(a) + count(b) + inBag;
            helper.assertTrue(after == before,
                "tidying must conserve items: " + before + " -> " + after + " "
                    + tidyDiagnostic(helper, s, bud, sourcePos, source, targetPos, target));
            helper.assertTrue(count(target) == before && count(source) == 0 && inBag == 0,
                "the physical Courier route must finish all twelve Cobblestone in its assigned"
                    + " Warehouse chest with no in-transit cargo "
                    + tidyDiagnostic(helper, s, bud, sourcePos, source, targetPos, target));
        });
    }

    private static int count(net.minecraft.world.Container container) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            total += container.getItem(slot).getCount();
        }
        return total;
    }

    private static String tidyDiagnostic(GameTestHelper helper, Settlement settlement,
                                         SettlerEntity courier, BlockPos sourcePos,
                                         net.minecraft.world.Container source,
                                         BlockPos targetPos,
                                         net.minecraft.world.Container target) {
        var data = com.hearthstead.settlement.request.RequestLedgerSavedData
            .existing(helper.getLevel());
        var ledger = data == null ? null : data.existing(settlement.id);
        String requests = ledger == null ? "none" : ledger.active().stream()
            .filter(row -> courier.getUUID().equals(row.courierId()))
            .map(row -> row.type() + ":" + row.state() + ":" + row.blocker()
                + " src=" + row.sourceContainer().toShortString()
                + " dst=" + row.targetContainer().toShortString()
                + " moved=" + row.movedCount() + " delivered=" + row.deliveredCount())
            .reduce((left, right) -> left + "|" + right).orElse("none");
        return "[source=" + sourcePos.toShortString() + ":" + count(source)
            + " target=" + targetPos.toShortString() + ":" + count(target)
            + " bag=" + count(courier.bag) + " activity=" + courier.getActivity()
            + " lifecycle=" + courier.workerLifecycle().state()
            + "/" + courier.workerLifecycle().task()
            + " route=" + courier.routeFailureNote() + " request=" + requests + "]";
    }

    // ------------------------------------------------------- guard ranks ---

    /** Rank is reached, not bought, and every ability has a threshold. */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void guardAbilitiesUnlockWithEarnedStrength(GameTestHelper helper) {
        helper.assertTrue(com.hearthstead.entity.GuardRank.of(0)
            == com.hearthstead.entity.GuardRank.RECRUIT, "nobody starts able");
        helper.assertTrue(com.hearthstead.entity.GuardRank.of(19)
            == com.hearthstead.entity.GuardRank.RECRUIT, "and 19 is not 20");
        helper.assertTrue(com.hearthstead.entity.GuardRank.of(20)
            == com.hearthstead.entity.GuardRank.SPEARMAN, "20 earns the first");
        helper.assertTrue(com.hearthstead.entity.GuardRank.of(60)
            == com.hearthstead.entity.GuardRank.SERGEANT, "60 earns the leap");
        helper.assertFalse(com.hearthstead.entity.GuardRank.of(59)
                .atLeast(com.hearthstead.entity.GuardRank.SERGEANT),
            "and 59 does not");
        helper.assertTrue(com.hearthstead.entity.GuardRank.of(999)
            == com.hearthstead.entity.GuardRank.CAPTAIN, "the top is the top");
        // Secondary targets must never take a full blow, or the area attack
        // becomes the only attack worth making.
        helper.assertTrue(com.hearthstead.entity.GuardRank.CLEAVE_SHARE < 1.0F,
            "a cleave's second target takes less");
        helper.succeed();
    }

    /** Actual v1xLcR/ZnREKz regression: the Archer must keep job, bow and
     * order after recorded tower damage, including settlement serialization. */
    @GameTest(batch = "employment_recorded_raid_recovery", template = "empty16", timeoutTicks = 200)
    public void recordedTowerRaidDamageRetainsArcherEquipmentAndOrder(GameTestHelper helper) {
        floor(helper,16);
        Settlement s=settlement(helper);
        Building tower=building(helper,s,BuildingType.WATCHTOWER,4,4);
        SettlerEntity archer=settler(helper,s,"Jorund",8,6);
        helper.assertTrue(Employment.hire(helper.getLevel(),s,tower,archer).ok(),
            "the standing tower must have a real Archer employment assignment");
        archer.setItemSlot(EquipmentSlot.MAINHAND,new ItemStack(Items.BOW));
        GuardOrder order=s.guardOrders.orderForMutation(s.id,archer.getUUID(),
            helper.getLevel().dimension().location()).orElseThrow();
        helper.assertTrue(order.issueStand(archer.blockPosition(),Direction.NORTH,
                GuardOrder.DEFAULT_LEASH_RADIUS,UUID.randomUUID(),tower.id,helper.getLevel().getGameTime()),
            "the defender must have an explicit existing post order");
        var ordersBefore=s.guardOrders.writeNbt();
        BlockPos wound=tower.bounds.getCenter();
        helper.getLevel().setBlock(wound,Blocks.STONE_BRICKS.defaultBlockState(),3);
        com.hearthstead.settlement.raid.RaidDirector.recordScar(helper.getLevel(),s.id,wound,
            Blocks.STONE_BRICKS.defaultBlockState());
        helper.getLevel().setBlock(wound,Blocks.AIR.defaultBlockState(),3);
        PlaqueBlockEntity plaque=linkedFixturePlaque(helper,s,tower);
        scheduledSurveyPastFailedRoomGrace(helper,plaque);
        helper.assertTrue(!tower.valid && tower.workers.contains(archer.getUUID())
                && archer.getProfession()==Profession.ARCHER && archer.getMainHandItem().is(Items.BOW)
                && s.guardOrders.writeNbt().equals(ordersBefore),
            "recorded damage after combat must suspend the tower without firing, disarming or resetting its defender");
        Settlement restored=Settlement.readNbt(s.writeNbt());
        var data=com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        data.settlements.put(restored.id,restored);
        data.setDirty();
        scheduledSurveyPastFailedRoomGrace(helper,plaque);
        Building employer=Employment.employerOf(restored,archer.getUUID());
        helper.assertTrue(employer!=null && !employer.valid
                && Employment.professionOf(restored,archer.getUUID())==Profession.ARCHER
                && archer.getMainHandItem().is(Items.BOW)
                && restored.guardOrders.writeNbt().equals(ordersBefore),
            "another failed survey after reload must preserve the same suspended armed assignment and order");
        helper.succeed();
    }

}
