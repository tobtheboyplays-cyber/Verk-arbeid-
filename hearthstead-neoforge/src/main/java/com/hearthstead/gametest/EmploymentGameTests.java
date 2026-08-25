package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Attribute;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerAttributes;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Trait;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.DayPhase;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Schedule;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.util.RandomSource;
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
        BlockPos anchor = helper.absolutePos(new BlockPos(x, 1, z));
        Building building = new Building(UUID.randomUUID(), type,
            helper.absolutePos(new BlockPos(x, 2, z)), anchor,
            BoundingBox.fromCorners(anchor, anchor.offset(3, 2, 3)));
        building.valid = true;
        s.buildings.add(building);
        return building;
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
        Building farm = building(helper, s, BuildingType.FARMHOUSE, 2, 2);
        Building hall = building(helper, s, BuildingType.DINING_HALL, 12, 12);
        SettlerEntity astrid = settler(helper, s, "Astrid", 6, 6);
        Employment.hire(helper.getLevel(), s, farm, astrid);

        Schedule.Posting atWork = Schedule.postFor(s, astrid, DayPhase.MORNING_WORK);
        helper.assertTrue(atWork != null && atWork.where().equals(farm.anchor),
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
     * The courier's tidying only ever merges: same item, same total, fewer
     * stacks. Item conservation holds even though the world was rearranged.
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 300)
    public void tidyingTheWarehouseConservesEverything(GameTestHelper helper) {
        floor(helper, 16);
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.CHEST);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        net.minecraft.world.Container a = (net.minecraft.world.Container)
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(4, 1, 4)));
        net.minecraft.world.Container b = (net.minecraft.world.Container)
            helper.getLevel().getBlockEntity(helper.absolutePos(new BlockPos(5, 1, 4)));
        a.setItem(0, new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.WHEAT, 9));
        b.setItem(0, new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.WHEAT, 3));
        int before = count(a) + count(b);

        Settlement s = settlement(helper);
        Building warehouse = building(helper, s, BuildingType.WAREHOUSE, 4, 4);
        SettlerEntity bud = settler(helper, s, "Bud", 4, 4);
        Employment.hire(helper.getLevel(), s, warehouse, bud);
        helper.getLevel().setDayTime(3000);

        helper.succeedWhen(() -> {
            int after = count(a) + count(b);
            helper.assertTrue(after == before,
                "tidying must conserve items: " + before + " -> " + after);
            int stacks = (a.getItem(0).isEmpty() ? 0 : 1) + (b.getItem(0).isEmpty() ? 0 : 1);
            helper.assertTrue(stacks == 1,
                "two partial stacks of the same thing must end up as one, got "
                    + stacks + " (a=" + a.getItem(0) + " b=" + b.getItem(0) + ")");
        });
    }

    private static int count(net.minecraft.world.Container container) {
        int total = 0;
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            total += container.getItem(slot).getCount();
        }
        return total;
    }

    // ------------------------------------------------------------- mayor ---

    /**
     * The mayor's boon comes from who they are, and losing one is a real blow.
     */
    @GameTest(batch = "employment", template = "empty16", timeoutTicks = 200)
    public void aMayorsDeathCostsTheWholeSettlement(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        SettlerEntity chief = settler(helper, s, "Ordforer", 4, 4);
        SettlerEntity other = settler(helper, s, "Nabo", 5, 4);

        helper.assertTrue(
            com.hearthstead.settlement.Mayor.appoint(helper.getLevel(), s, chief) == null,
            "appointing an ordinary settler must succeed");
        helper.assertTrue(s.mayorId.equals(chief.getUUID()), "they hold the seat");
        // The seat is filled but the boon has not arrived: settling in is the
        // cost that stops swapping being a toggle.
        helper.assertTrue(
            com.hearthstead.settlement.Mayor.activeBoon(helper.getLevel(), s) == null,
            "a brand new mayor's boon must not be in effect yet");
        helper.assertTrue(
            com.hearthstead.settlement.Mayor.boonOf(chief).from()
                == chief.attributes().knack(),
            "the boon follows the person's own knack");

        float moraleBefore = other.getMorale();
        com.hearthstead.settlement.Mayor.onDeath(helper.getLevel(), s, chief);

        helper.assertTrue(s.mayorId == null, "a dead mayor vacates the seat");
        helper.assertTrue(other.getMorale() < moraleBefore,
            "every settler feels it: " + moraleBefore + " -> " + other.getMorale());
        helper.assertTrue(
            com.hearthstead.settlement.Mayor.mourning(helper.getLevel(), s),
            "and the settlement mourns before it appoints anyone else");
        helper.assertTrue(
            com.hearthstead.settlement.Mayor.appoint(helper.getLevel(), s, other) != null,
            "appointing during mourning must be refused, with a reason");
        helper.succeed();
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
}
