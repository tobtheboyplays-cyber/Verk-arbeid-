package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Production;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * SLICE CHAINS — the six intermediate goods bound by FLOWS.md
 * (docs/project/FLOWS.md), and the two rules that make them safe: D-007 (a
 * fed path is a multiplier, never a gate) and acyclicity (no chain of
 * recipes mints value it did not consume).
 *
 * <p>These tests exercise {@link Production} directly rather than through a
 * hired settler — the same style {@code WarehouseGameTests} uses for the
 * bakery — because that is the layer SLICE CHAINS actually owns and because
 * two of the six items (MILL, BREWERY) have no {@code Profession} to hire
 * into yet (see PLAN_CHAINS.md's uncertainties: {@code Employment.TRADES}
 * has no entry for either building, so {@code
 * EmploymentGameTests#everyTradeHasWorkAndAMotionOfItsOwn} will need a
 * follow-up from whoever owns {@code Employment}/{@code Profession} before a
 * miller or brewer can actually be hired). The recipe table itself —
 * conservation, the rough/fed multiplier, acyclicity — needs no settler to
 * be proven correct, and {@code CrafterWorkGoal} already runs the exact same
 * {@link Production#ready} / {@link Production#run} pair once a trade
 * exists.
 *
 * <p>Building/arena helpers mirror {@code EmploymentGameTests}' shape
 * ({@code floor}, a bare {@link Building} record) rather than its full
 * settlement+hire flow, for the same reason {@code WarehouseGameTests} does:
 * {@link Production} reads only {@code building.bounds} and the containers
 * inside it (via {@code WarehouseIndex}), so a registered {@code Settlement}
 * and a live {@code SettlerEntity} would test machinery these assertions do
 * not touch.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class ChainsGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /** A bare {@link Building} covering a small footprint, no plaque needed. */
    private static Building building(GameTestHelper helper, BuildingType type,
                                     int x0, int z0, int x1, int z1) {
        BlockPos min = helper.absolutePos(new BlockPos(x0, 1, z0));
        BlockPos max = helper.absolutePos(new BlockPos(x1, 3, z1));
        BoundingBox bounds = BoundingBox.fromCorners(min, max);
        Building b = new Building(UUID.randomUUID(), type, min, min, bounds);
        b.valid = true;
        return b;
    }

    private static Container containerAt(GameTestHelper helper, BlockPos rel) {
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        return be instanceof Container c ? c : null;
    }

    private static int countOf(Container c, Item item) {
        int total = 0;
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            ItemStack s = c.getItem(slot);
            if (s.is(item)) {
                total += s.getCount();
            }
        }
        return total;
    }

    /** Moves every stack of {@code item} from one chest to another — the
     * hand-simulated courier hop the tests below use in place of a live
     * {@code CourierWorkGoal} run, matching the direct-chest-move convention
     * already used across this package's Trade*GameTests. */
    private static void moveAll(Container from, Container to, Item item) {
        for (int slot = 0; slot < from.getContainerSize(); slot++) {
            ItemStack s = from.getItem(slot);
            if (s.isEmpty() || !s.is(item)) {
                continue;
            }
            to.setItem(slotFor(to), s.copy());
            from.setItem(slot, ItemStack.EMPTY);
        }
    }

    private static int slotFor(Container c) {
        for (int slot = 0; slot < c.getContainerSize(); slot++) {
            if (c.getItem(slot).isEmpty()) {
                return slot;
            }
        }
        return 0;
    }

    // ------------------------------------------------------------------ (a) ---

    /**
     * The mill turns wheat into flour, chest-true: exactly three wheat leave
     * for exactly two flour, in the mill's OWN chest, with no bakery, no
     * farm and no warehouse anywhere in the world (D-007 — the mill is a
     * pure upstream refiner, useful the hour it opens).
     */
    @GameTest(batch = "chains", template = "empty16", timeoutTicks = 200)
    public void millGrindsWheatIntoFlourChestTrue(GameTestHelper helper) {
        floor(helper, 16);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.CHEST);
        Building mill = building(helper, BuildingType.MILL, 1, 1, 4, 4);
        Container chest = containerAt(helper, new BlockPos(2, 1, 2));
        helper.assertTrue(chest != null, "the arena chest should be a container");
        chest.setItem(0, new ItemStack(Items.WHEAT, 10));

        Production.Recipe recipe = Production.ready(helper.getLevel(), mill);
        helper.assertTrue(recipe != null,
            "a mill holding wheat has work to do, with nothing else built");
        helper.assertTrue(recipe.id().equals("flour"),
            "the mill's only job is grinding flour, got " + recipe.id());
        helper.assertTrue(recipe.inputCount() == 3 && recipe.outputCount() == 2,
            "flour should cost 3 wheat for 2 flour, got " + recipe.inputCount()
                + " -> " + recipe.outputCount());

        boolean ran = Production.run(helper.getLevel(), mill, recipe);
        helper.assertTrue(ran, "the recipe should have run");

        helper.assertTrue(countOf(chest, Items.WHEAT) == 7,
            "three wheat gone, 7 left of 10; saw " + countOf(chest, Items.WHEAT));
        helper.assertTrue(countOf(chest, ModItems.FLOUR.get()) == 2,
            "and two flour made; saw " + countOf(chest, ModItems.FLOUR.get()));
        helper.succeed();
    }

    // ----------------------------------------------------------------- (a2) ---

    /**
     * SURVIVAL_AUDIT.md F7: the library's 81-paper bill was permanent
     * hand-labour forever because no recipe anywhere made paper. The mill's
     * new "paper" entry closes that -- chest-true, exactly like the flour
     * job right beside it: three sugar cane leave for exactly two paper, in
     * the mill's own chest, nothing more or less.
     *
     * <p>Registered through {@link GameTestFixtures#register} rather than
     * this file's bare {@link #building} helper -- the task that added this
     * recipe called for the fixture path everything else in the gametest
     * package uses, and unlike (a)-(c) this test does not need the "no
     * settlement, no plaque, no couriers" story to make its point.
     */
    @GameTest(batch = "chains", template = "empty16", timeoutTicks = 200)
    public void millGrindsSugarCaneIntoPaperChestTrue(GameTestHelper helper) {
        floor(helper, 16);
        // NOT registered into SettlementSavedData, deliberately, and the
        // static guard (qa/scripts/check_fixture_plaques.py) is why we know
        // it matters: this file's other tests hand-build bare Buildings with
        // no plaque, which is safe ONLY while nothing here registers a live
        // settlement. Registering one -- as the first draft of this test did
        // -- would have made every one of them reachable by
        // BuildingManager's sweep and dissolvable mid-run, reopening KF-021
        // in the exact file FLAKE-2 had audited as safe. Production.run
        // needs the Building and the level, never the settlement, so the
        // registration bought nothing and risked everything.
        Settlement s = new Settlement(UUID.randomUUID(), "Papirholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 6;
        Building mill = GameTestFixtures.register(helper, s, BuildingType.MILL, 4, 4);
        // GameTestFixtures.register hangs the plaque and records the bounds;
        // it deliberately does NOT furnish the room, so the chest this
        // recipe reads from and writes into is the fixture's own job. Its
        // absence is what failed this test on its first run: Production.run
        // had nowhere to take sugar cane from.
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        Container chest = containerAt(helper, new BlockPos(5, 1, 4));
        helper.assertTrue(chest != null, "the registered mill's chest should be a container");
        chest.setItem(0, new ItemStack(Items.SUGAR_CANE, 10));

        Production.Recipe recipe = null;
        for (Production.Recipe r : Production.of(BuildingType.MILL)) {
            if (r.id().equals("paper")) {
                recipe = r;
                break;
            }
        }
        helper.assertTrue(recipe != null, "the mill must know how to grind paper");
        helper.assertTrue(recipe.inputCount() == 3 && recipe.outputCount() == 2,
            "paper should cost 3 sugar cane for 2 paper, matching the flour "
                + "idiom, got " + recipe.inputCount() + " -> " + recipe.outputCount());

        boolean ran = Production.run(helper.getLevel(), mill, recipe);
        helper.assertTrue(ran, "the paper recipe should have run");

        helper.assertTrue(countOf(chest, Items.SUGAR_CANE) == 7,
            "three sugar cane gone, 7 left of 10; saw " + countOf(chest, Items.SUGAR_CANE));
        helper.assertTrue(countOf(chest, Items.PAPER) == 2,
            "and two paper made; saw " + countOf(chest, Items.PAPER));
        helper.succeed();
    }

    // ------------------------------------------------------------------ (b) ---

    /**
     * D-007's multiplier, proven both ways in one test. Two bakeries, same
     * recipe TICK cost (160), unequal stock: one holds only wheat, the other
     * only flour. The wheat-only bakery must still bake — the rough path is
     * never disabled — and the flour-fed one must yield MORE bread for that
     * same 160 ticks of labour, because {@code bread_flour} is listed first
     * and costs the same ticks for double the loaves. That is the multiplier
     * FLOWS.md asks for, read directly off the recipe table rather than
     * asserted by fiat.
     */
    @GameTest(batch = "chains", template = "empty16", timeoutTicks = 200)
    public void bakeryWithFlourOutproducesWheatAlone(GameTestHelper helper) {
        floor(helper, 16);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.CHEST);
        helper.setBlock(new BlockPos(9, 1, 2), Blocks.CHEST);
        Building wheatBakery = building(helper, BuildingType.BAKERY, 1, 1, 4, 4);
        Building flourBakery = building(helper, BuildingType.BAKERY, 8, 1, 11, 4);
        Container wheatChest = containerAt(helper, new BlockPos(2, 1, 2));
        Container flourChest = containerAt(helper, new BlockPos(9, 1, 2));
        helper.assertTrue(wheatChest != null && flourChest != null,
            "both arena chests should be containers");

        // Trial 1: grain only. D-007 — must still work, with zero flour
        // anywhere in the building or the world.
        wheatChest.setItem(0, new ItemStack(Items.WHEAT, 5));
        // An oven burns (FUEL-1): fuel in both bakeries so this comparison
        // stays about flour vs grain, not about who has firewood.
        wheatChest.setItem(1, new ItemStack(Items.CHARCOAL, 4));
        Production.Recipe rough = Production.ready(helper.getLevel(), wheatBakery);
        helper.assertTrue(rough != null, "a bakery holding only wheat has work to do");
        helper.assertTrue(rough.id().equals("bread"),
            "with no flour anywhere it must fall through to the grain recipe, got "
                + rough.id());
        helper.assertTrue(Production.run(helper.getLevel(), wheatBakery, rough),
            "the grain recipe should have run");
        int breadFromWheat = countOf(wheatChest, Items.BREAD);

        // Trial 2: flour only, same building type, same tick cost.
        flourChest.setItem(0, new ItemStack(ModItems.FLOUR.get(), 4));
        flourChest.setItem(1, new ItemStack(Items.CHARCOAL, 4));
        Production.Recipe fed = Production.ready(helper.getLevel(), flourBakery);
        helper.assertTrue(fed != null, "a bakery holding flour has work to do");
        helper.assertTrue(fed.id().equals("bread_flour"),
            "flour must be preferred once it exists, got " + fed.id());
        helper.assertTrue(Production.run(helper.getLevel(), flourBakery, fed),
            "the flour recipe should have run");
        int breadFromFlour = countOf(flourChest, Items.BREAD);

        helper.assertTrue(rough.ticks() == fed.ticks(),
            "the multiplier must show up as yield, not a shorter clip: rough="
                + rough.ticks() + " fed=" + fed.ticks());
        helper.assertTrue(breadFromFlour > breadFromWheat,
            "a mill-fed bakery must outproduce a grain-only one in the same "
                + rough.ticks() + " ticks: flour path made " + breadFromFlour
                + ", grain path made " + breadFromWheat);
        helper.succeed();
    }

    // ----------------------------------------------------------------- (b2) ---

    /**
     * JOB 1: the hunter's feathers finally have somewhere to go. Same shape
     * as {@link #bakeryWithFlourOutproducesWheatAlone} above — two fletchers,
     * same recipe TICK cost (100), unequal stock: one holds only flint, the
     * other only feather. The flint-only fletcher must still fletch arrows
     * (D-007 — no hunter, no problem), and the feather-fed one must yield
     * MORE arrows for that same 100 ticks, because {@code arrows_feather} is
     * listed first and costs the same ticks for more arrows per batch — the
     * fed-pair multiplier read directly off the table, not asserted by fiat.
     */
    @GameTest(batch = "chains", template = "empty16", timeoutTicks = 200)
    public void fletcherWithFeathersOutproducesFlintAlone(GameTestHelper helper) {
        floor(helper, 16);
        // GameTestFixtures.register, not the bare building() helper this
        // file's older (a)-(c) tests use: the task that retuned this table
        // asked for the fixture path everything newer in this package uses
        // (same call as millGrindsSugarCaneIntoPaperChestTrue above). The
        // Settlement below is deliberately never put into
        // SettlementSavedData, so BuildingManager's sweep can never reach
        // it — see that test's own comment for why that matters.
        Settlement s = new Settlement(UUID.randomUUID(), "Fjaerholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 8;
        Building flintFletcher = GameTestFixtures.register(helper, s, BuildingType.FLETCHER, 2, 2);
        Building featherFletcher = GameTestFixtures.register(helper, s, BuildingType.FLETCHER, 9, 9);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.CHEST);
        helper.setBlock(new BlockPos(10, 1, 9), Blocks.CHEST);
        Container flintChest = containerAt(helper, new BlockPos(3, 1, 2));
        Container featherChest = containerAt(helper, new BlockPos(10, 1, 9));
        helper.assertTrue(flintChest != null && featherChest != null,
            "both arena chests should be containers");

        // Trial 1: flint only. D-007 — a settlement with no hunter must
        // still get arrows.
        flintChest.setItem(0, new ItemStack(Items.FLINT, 5));
        Production.Recipe rough = Production.ready(helper.getLevel(), flintFletcher);
        helper.assertTrue(rough != null, "a fletcher holding only flint has work to do");
        helper.assertTrue(rough.id().equals("arrows"),
            "with no feather anywhere it must fall through to the flint "
                + "recipe, got " + rough.id());
        helper.assertTrue(Production.run(helper.getLevel(), flintFletcher, rough),
            "the flint recipe should have run");
        int arrowsFromFlint = countOf(flintChest, Items.ARROW);

        // Trial 2: feather only, same building type, same tick cost.
        featherChest.setItem(0, new ItemStack(Items.FEATHER, 4));
        Production.Recipe fed = Production.ready(helper.getLevel(), featherFletcher);
        helper.assertTrue(fed != null, "a fletcher holding feather has work to do");
        helper.assertTrue(fed.id().equals("arrows_feather"),
            "feather must be preferred once it exists, got " + fed.id());
        helper.assertTrue(Production.run(helper.getLevel(), featherFletcher, fed),
            "the feather recipe should have run");
        int arrowsFromFeather = countOf(featherChest, Items.ARROW);

        helper.assertTrue(rough.ticks() == fed.ticks(),
            "the multiplier must show up as yield, not a shorter clip: rough="
                + rough.ticks() + " fed=" + fed.ticks());
        helper.assertTrue(arrowsFromFeather > arrowsFromFlint,
            "a hunter-fed fletcher must outproduce a flint-only one in the "
                + "same " + rough.ticks() + " ticks: feather path made "
                + arrowsFromFeather + ", flint path made " + arrowsFromFlint);
        helper.assertTrue(arrowsFromFlint == 4 && arrowsFromFeather == 7,
            "pins the JOB 1 arithmetic exactly (25.00 vs 14.29 ticks/arrow, "
                + "x1.75): flint=" + arrowsFromFlint + " feather=" + arrowsFromFeather);
        helper.succeed();
    }

    // ------------------------------------------------------------------ (c) ---

    /**
     * One full chain, three buildings, end to end: a lumber camp's log
     * stockpile, carried by hand (standing in for a courier trip — see the
     * class javadoc) to a sawmill, milled into timber beams, carried again to
     * a carpenter, and worked into barrels. Every count below is exactly
     * what the two fed-path ratios predict (6 logs -&gt; 4 beams -&gt; 4
     * barrels, JOB 4's fix to Production.CARPENTER's barrel_beam — it used
     * to yield only 2, the same as the rough path would from the same beam
     * count, which was BALANCE_AUDIT.md finding 7's whole complaint); anything
     * else would mean an item vanished or was minted somewhere along the trip
     * (INV-3).
     */
    @GameTest(batch = "chains", template = "empty16", timeoutTicks = 400)
    public void threeBuildingChainConservesItemsEndToEnd(GameTestHelper helper) {
        floor(helper, 16);
        helper.setBlock(new BlockPos(2, 1, 2), Blocks.CHEST);
        helper.setBlock(new BlockPos(7, 1, 2), Blocks.CHEST);
        helper.setBlock(new BlockPos(12, 1, 2), Blocks.CHEST);
        Building lumberCamp = building(helper, BuildingType.LUMBER_CAMP, 1, 1, 4, 4);
        Building sawmill = building(helper, BuildingType.SAWMILL, 6, 1, 9, 4);
        Building carpenter = building(helper, BuildingType.CARPENTER, 11, 1, 14, 4);
        Container campChest = containerAt(helper, new BlockPos(2, 1, 2));
        Container sawChest = containerAt(helper, new BlockPos(7, 1, 2));
        Container carpChest = containerAt(helper, new BlockPos(12, 1, 2));
        helper.assertTrue(campChest != null && sawChest != null && carpChest != null,
            "all three arena chests should be containers");

        // The lumber camp is a Ring-1 source (FLOWS.md): it has no Production
        // recipe of its own, it just holds what the lumberjack cut.
        campChest.setItem(0, new ItemStack(Items.OAK_LOG, 6));
        helper.assertTrue(Production.ready(helper.getLevel(), lumberCamp) == null,
            "a lumber camp makes nothing through Production — it is a source, "
                + "not a refiner");

        // Hop 1: camp -> sawmill (the courier trip). Nothing lost in transit.
        moveAll(campChest, sawChest, Items.OAK_LOG);
        helper.assertTrue(countOf(sawChest, Items.OAK_LOG) == 6
            && countOf(campChest, Items.OAK_LOG) == 0,
            "all six logs should have moved, none left behind and none duplicated");

        // Sawmill: 3 logs -> 2 beams, run twice to clear the 6 logs. The beam
        // recipe is named explicitly here rather than taken from ready():
        // this test's subject is the CHAIN (do six logs become four beams and
        // reach the carpenter without losing anything), not which recipe a
        // sawmill picks on a given tick. The selector alternates beams and
        // planks by need on purpose — a sawmill that spent every log on beams
        // and never cut a plank was the bug, not the behaviour — and it has
        // its own tests. Asking ready() here would be testing two things at
        // once and getting a false failure on the one not under test.
        Production.Recipe beams = null;
        for (Production.Recipe r : Production.of(BuildingType.SAWMILL)) {
            if (r.id().equals("timber_beam")) {
                beams = r;
                break;
            }
        }
        helper.assertTrue(beams != null,
            "the sawmill must still know how to make timber beams");
        for (int i = 0; i < 2; i++) {
            helper.assertTrue(Production.run(helper.getLevel(), sawmill, beams),
                "the beam recipe should have run");
        }
        helper.assertTrue(countOf(sawChest, Items.OAK_LOG) == 0,
            "all six logs should be spent; saw " + countOf(sawChest, Items.OAK_LOG));
        helper.assertTrue(countOf(sawChest, ModItems.TIMBER_BEAM.get()) == 4,
            "6 logs at 3:2 should leave exactly 4 beams; saw "
                + countOf(sawChest, ModItems.TIMBER_BEAM.get()));

        // Hop 2: sawmill -> carpenter.
        moveAll(sawChest, carpChest, ModItems.TIMBER_BEAM.get());
        helper.assertTrue(countOf(carpChest, ModItems.TIMBER_BEAM.get()) == 4
            && countOf(sawChest, ModItems.TIMBER_BEAM.get()) == 0,
            "all four beams should have moved, none left behind and none duplicated");

        // Carpenter: 2 beams -> 2 barrels (JOB 4), run twice to clear the 4
        // beams.
        for (int i = 0; i < 2; i++) {
            Production.Recipe r = Production.ready(helper.getLevel(), carpenter);
            helper.assertTrue(r != null && r.id().equals("barrel_beam"),
                "with beams and no 7-plank stockpile in the chest, the "
                    + "carpenter's fed recipe (barrel_beam) must be the one "
                    + "that runs, got " + (r == null ? "null" : r.id()));
            helper.assertTrue(Production.run(helper.getLevel(), carpenter, r),
                "the barrel recipe should have run");
        }
        helper.assertTrue(countOf(carpChest, ModItems.TIMBER_BEAM.get()) == 0,
            "all four beams should be spent; saw "
                + countOf(carpChest, ModItems.TIMBER_BEAM.get()));
        helper.assertTrue(countOf(carpChest, Items.BARREL) == 4,
            "4 beams at 2:2 should leave exactly 4 barrels (JOB 4 doubled "
                + "barrel_beam's output so the fed path is no longer a "
                + "zero-benefit detour — BALANCE_AUDIT.md finding 7); saw "
                + countOf(carpChest, Items.BARREL));

        // End to end: 6 logs became exactly 4 barrels, with nothing left over
        // anywhere in the chain and nothing extra anywhere either.
        int leftoverLogs = countOf(campChest, Items.OAK_LOG) + countOf(sawChest, Items.OAK_LOG)
            + countOf(carpChest, Items.OAK_LOG);
        int leftoverBeams = countOf(campChest, ModItems.TIMBER_BEAM.get())
            + countOf(sawChest, ModItems.TIMBER_BEAM.get())
            + countOf(carpChest, ModItems.TIMBER_BEAM.get());
        helper.assertTrue(leftoverLogs == 0 && leftoverBeams == 0,
            "nothing but barrels should remain: leftover logs=" + leftoverLogs
                + " leftover beams=" + leftoverBeams);
        helper.succeed();
    }

    // ----------------------------------------------------------------- (c2) ---

    /**
     * JOB 3: the butcher-&gt;tannery leather chain, hand-carried (see the
     * class javadoc), conserved end to end. Two rabbits become two cured
     * hides at the butcher; carried to the tannery, they become two leather
     * — chest-true at every hop, nothing minted or lost (INV-3), and the
     * exact counts pin the retuned arithmetic on TANNERY's own recipe
     * comment: a rabbit is worth exactly as much leather whichever path it
     * takes, only the wall-clock differs.
     */
    @GameTest(batch = "chains", template = "empty16", timeoutTicks = 300)
    public void butcherToTanneryLeatherChainConservesItemsEndToEnd(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = new Settlement(UUID.randomUUID(), "Skinnholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 8;
        Building butcher = GameTestFixtures.register(helper, s, BuildingType.BUTCHER, 2, 2);
        Building tannery = GameTestFixtures.register(helper, s, BuildingType.TANNERY, 9, 9);
        helper.setBlock(new BlockPos(3, 1, 2), Blocks.CHEST);
        helper.setBlock(new BlockPos(10, 1, 9), Blocks.CHEST);
        Container butcherChest = containerAt(helper, new BlockPos(3, 1, 2));
        Container tanneryChest = containerAt(helper, new BlockPos(10, 1, 9));
        helper.assertTrue(butcherChest != null && tanneryChest != null,
            "both arena chests should be containers");

        butcherChest.setItem(0, new ItemStack(Items.RABBIT, 2));
        Production.Recipe hide = null;
        for (Production.Recipe r : Production.of(BuildingType.BUTCHER)) {
            if (r.id().equals("hide")) {
                hide = r;
                break;
            }
        }
        helper.assertTrue(hide != null, "the butcher must still know how to cure a hide");
        helper.assertTrue(Production.run(helper.getLevel(), butcher, hide),
            "the hide recipe should have run");
        helper.assertTrue(countOf(butcherChest, Items.RABBIT) == 0,
            "both rabbits should be spent; saw " + countOf(butcherChest, Items.RABBIT));
        helper.assertTrue(countOf(butcherChest, ModItems.CURED_HIDE.get()) == 2,
            "and two cured hides made; saw " + countOf(butcherChest, ModItems.CURED_HIDE.get()));

        moveAll(butcherChest, tanneryChest, ModItems.CURED_HIDE.get());
        helper.assertTrue(countOf(tanneryChest, ModItems.CURED_HIDE.get()) == 2
            && countOf(butcherChest, ModItems.CURED_HIDE.get()) == 0,
            "both cured hides should have moved, none left behind and none duplicated");

        Production.Recipe leatherCured = Production.ready(helper.getLevel(), tannery);
        helper.assertTrue(leatherCured != null && leatherCured.id().equals("leather_cured"),
            "with cured hide and no rabbit-hide stockpile the tannery's fed "
                + "recipe must be the one that runs, got "
                + (leatherCured == null ? "null" : leatherCured.id()));
        helper.assertTrue(Production.run(helper.getLevel(), tannery, leatherCured),
            "the leather_cured recipe should have run");
        helper.assertTrue(countOf(tanneryChest, ModItems.CURED_HIDE.get()) == 0,
            "both cured hides should be spent; saw "
                + countOf(tanneryChest, ModItems.CURED_HIDE.get()));
        helper.assertTrue(countOf(tanneryChest, Items.LEATHER) == 2,
            "2 cured hide at 2:2 should leave exactly 2 leather; saw "
                + countOf(tanneryChest, Items.LEATHER));
        helper.succeed();
    }

    // ----------------------------------------------------------------- (c3) ---

    /**
     * JOB 3: the brewery's own internal malt chain, single building —
     * unlike leather's cross-building hop, malting and brewing share one
     * bench (BALANCE_AUDIT.md Q5's own note), so this test proves the whole
     * wheat-&gt;malt-&gt;ale path conserves items in place rather than
     * across a courier hop.
     */
    @GameTest(batch = "chains", template = "empty16", timeoutTicks = 300)
    public void breweryMaltsThenBrewsConservingItemsInPlace(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = new Settlement(UUID.randomUUID(), "Malthaugen",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        s.radius = 8;
        Building brewery = GameTestFixtures.register(helper, s, BuildingType.BREWERY, 4, 4);
        helper.setBlock(new BlockPos(5, 1, 4), Blocks.CHEST);
        Container chest = containerAt(helper, new BlockPos(5, 1, 4));
        helper.assertTrue(chest != null, "the registered brewery's chest should be a container");

        // The brewery is one of the four burning buildings (Fuel#burns) --
        // both batches below need firewood, not just grain.
        chest.setItem(0, new ItemStack(Items.WHEAT, 4));
        chest.setItem(1, new ItemStack(Items.CHARCOAL, 2));
        Production.Recipe malt = null;
        for (Production.Recipe r : Production.of(BuildingType.BREWERY)) {
            if (r.id().equals("malt")) {
                malt = r;
                break;
            }
        }
        helper.assertTrue(malt != null, "the brewery must still know how to malt wheat");
        helper.assertTrue(Production.run(helper.getLevel(), brewery, malt),
            "the malt recipe should have run");
        helper.assertTrue(countOf(chest, Items.WHEAT) == 0,
            "all four wheat should be spent; saw " + countOf(chest, Items.WHEAT));
        helper.assertTrue(countOf(chest, ModItems.MALT.get()) == 3,
            "and three malt made; saw " + countOf(chest, ModItems.MALT.get()));

        Production.Recipe aleMalt = Production.ready(helper.getLevel(), brewery);
        helper.assertTrue(aleMalt != null && aleMalt.id().equals("ale_malt"),
            "with malt on hand and no wheat left the brewery's fed recipe "
                + "must be the one that runs, got "
                + (aleMalt == null ? "null" : aleMalt.id()));
        helper.assertTrue(Production.run(helper.getLevel(), brewery, aleMalt),
            "the ale_malt recipe should have run");
        helper.assertTrue(countOf(chest, ModItems.MALT.get()) == 1,
            "two of the three malt should be spent, one left over; saw "
                + countOf(chest, ModItems.MALT.get()));
        helper.assertTrue(countOf(chest, ModItems.ALE.get()) == 2,
            "2 malt at 2:2 should leave exactly 2 ale; saw "
                + countOf(chest, ModItems.ALE.get()));
        helper.succeed();
    }

    // ----------------------------------------------------------------- (c4) ---

    /**
     * JOB 3's own promise, made executable: FLOWS.md says every fed path is
     * a x1.5-x2 multiplier over its rough path, "measured end to end, from
     * raw material" — the same method PLAN_CHAINS.md uses (correctly) for
     * iron and BALANCE_AUDIT.md finding 3 demanded of the other four. This
     * walks the live {@link Production} table for bread, leather, ale and
     * barrel exactly the way the audit's own arithmetic does — rough
     * ticks-per-unit from the raw material vs. fed ticks-per-unit summed
     * across BOTH buildings — and fails the instant any future retune drifts
     * a pair back out of band, the same way {@code
     * FuelGameTests#bloomFedPathBeatsRoughSmeltingWithinTheFlowsBand} already
     * guards iron.
     */
    @GameTest(batch = "chains", template = "empty16", timeoutTicks = 100)
    public void fedPathsClearTheFlowsBandMeasuredEndToEndFromRawMaterial(GameTestHelper helper) {
        Production.Recipe bread = recipeOf(BuildingType.BAKERY, "bread");
        Production.Recipe breadFlour = recipeOf(BuildingType.BAKERY, "bread_flour");
        Production.Recipe flour = recipeOf(BuildingType.MILL, "flour");
        assertBand(helper, "bread",
            bread.ticks(),
            (double) flour.ticks() / flour.outputCount() * breadFlour.inputCount() / breadFlour.outputCount()
                + (double) breadFlour.ticks() / breadFlour.outputCount());

        Production.Recipe leather = recipeOf(BuildingType.TANNERY, "leather");
        Production.Recipe leatherCured = recipeOf(BuildingType.TANNERY, "leather_cured");
        Production.Recipe hide = recipeOf(BuildingType.BUTCHER, "hide");
        assertBand(helper, "leather",
            leather.ticks(),
            (double) hide.ticks() / hide.outputCount() * leatherCured.inputCount() / leatherCured.outputCount()
                + (double) leatherCured.ticks() / leatherCured.outputCount());

        Production.Recipe ale = recipeOf(BuildingType.BREWERY, "ale");
        Production.Recipe aleMalt = recipeOf(BuildingType.BREWERY, "ale_malt");
        Production.Recipe malt = recipeOf(BuildingType.BREWERY, "malt");
        assertBand(helper, "ale",
            ale.ticks(),
            (double) malt.ticks() / malt.outputCount() * aleMalt.inputCount() / aleMalt.outputCount()
                + (double) aleMalt.ticks() / aleMalt.outputCount());

        Production.Recipe barrel = recipeOf(BuildingType.CARPENTER, "barrel");
        Production.Recipe barrelBeam = recipeOf(BuildingType.CARPENTER, "barrel_beam");
        Production.Recipe planks = recipeOf(BuildingType.SAWMILL, "planks");
        Production.Recipe timberBeam = recipeOf(BuildingType.SAWMILL, "timber_beam");
        double roughBarrelTicks = (double) planks.ticks() / planks.outputCount() * barrel.inputCount()
            / barrel.outputCount()
            + (double) barrel.ticks() / barrel.outputCount();
        double fedBarrelTicks = (double) timberBeam.ticks() / timberBeam.outputCount() * barrelBeam.inputCount()
            / barrelBeam.outputCount()
            + (double) barrelBeam.ticks() / barrelBeam.outputCount();
        assertBand(helper, "barrel", roughBarrelTicks, fedBarrelTicks);
        helper.assertTrue(barrelBeam.outputCount() > barrel.outputCount(),
            "JOB 4: barrel_beam must yield strictly more per batch than the "
                + "rough recipe, or the fed path is worthless under the real "
                + "effort-bound economy (BALANCE_AUDIT.md finding 7) — rough="
                + barrel.outputCount() + " fed=" + barrelBeam.outputCount());
        helper.succeed();
    }

    private static Production.Recipe recipeOf(BuildingType type, String id) {
        for (Production.Recipe r : Production.of(type)) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        throw new IllegalStateException("no recipe '" + id + "' for " + type.id());
    }

    /** FLOWS.md's own rule: every fed path is a x1.5-x2 multiplier over its
     *  rough path, ticks-per-unit, measured end to end from raw material. */
    private static void assertBand(GameTestHelper helper, String chain,
                                   double roughTicksPerUnit, double fedTicksPerUnit) {
        double ratio = roughTicksPerUnit / fedTicksPerUnit;
        helper.assertTrue(ratio >= 1.5 && ratio <= 2.0,
            chain + "'s fed path must clear FLOWS.md's x1.5-x2 band measured "
                + "end to end from raw material: rough=" + roughTicksPerUnit
                + "t/unit fed=" + fedTicksPerUnit + "t/unit ratio=" + ratio);
    }

    // ------------------------------------------------------------------ (d) ---

    /**
     * No chain of recipes anywhere in {@link Production}'s table may return
     * to an item it started from — the static acyclicity proof FLOWS.md
     * requires ("Acyclicity (no value mints)"). Built as a plain directed
     * graph over every recipe's (input -&gt; output) edge, across every
     * {@link BuildingType}, not just the six SLICE CHAINS items: a cycle
     * anywhere would let a settlement duplicate value by looping a
     * building's own output back through itself, which is exactly the
     * planks-&gt;beam-&gt;planks exploit the task brief names.
     */
    @GameTest(batch = "chains", template = "empty16", timeoutTicks = 100)
    public void noValueMintingCycleInProductionTable(GameTestHelper helper) {
        Map<Item, Set<Item>> edges = new LinkedHashMap<>();
        int recipeCount = 0;
        for (BuildingType type : BuildingType.values()) {
            for (Production.Recipe recipe : Production.of(type)) {
                recipeCount++;
                helper.assertTrue(!recipe.input().test(new ItemStack(recipe.output())),
                    type.id() + "/" + recipe.id() + " makes its own input in one step — "
                        + "an immediate mint loop");
                for (ItemStack accepted : recipe.input().getItems()) {
                    edges.computeIfAbsent(accepted.getItem(), k -> new LinkedHashSet<>())
                        .add(recipe.output());
                }
            }
        }
        helper.assertTrue(recipeCount > 0, "the table should not be empty");

        // Standard white/gray/black DFS cycle check over the whole graph.
        Set<Item> visiting = new HashSet<>();
        Set<Item> done = new HashSet<>();
        for (Item start : edges.keySet()) {
            List<Item> cycle = findCycle(start, edges, visiting, done, new ArrayDeque<>());
            helper.assertTrue(cycle == null,
                "value-minting cycle in the Production table: "
                    + describeCycle(cycle));
        }
        helper.succeed();
    }

    /** Returns the cycle (as a path) if one is reachable from {@code start}
     * that has not already been cleared, else null. */
    private static List<Item> findCycle(Item node, Map<Item, Set<Item>> edges,
                                        Set<Item> visiting, Set<Item> done,
                                        Deque<Item> path) {
        if (done.contains(node)) {
            return null;
        }
        if (visiting.contains(node)) {
            List<Item> cycle = new ArrayList<>(path);
            cycle.add(node);
            return cycle;
        }
        visiting.add(node);
        path.addLast(node);
        for (Item next : edges.getOrDefault(node, Set.of())) {
            List<Item> cycle = findCycle(next, edges, visiting, done, path);
            if (cycle != null) {
                return cycle;
            }
        }
        path.removeLast();
        visiting.remove(node);
        done.add(node);
        return null;
    }

    private static String describeCycle(List<Item> cycle) {
        if (cycle == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Item item : cycle) {
            if (!sb.isEmpty()) {
                sb.append(" -> ");
            }
            sb.append(item);
        }
        return sb.toString();
    }
}
