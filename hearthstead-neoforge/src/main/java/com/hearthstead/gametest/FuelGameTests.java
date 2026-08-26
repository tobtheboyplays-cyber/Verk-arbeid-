package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.Fuel;
import com.hearthstead.building.Production;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
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

import java.util.UUID;

/**
 * FUEL — the firewood half of DESIGN.md pillar 2 / R20's decided upkeep
 * flows ("food + firewood/warmth + tool wear"): burning buildings
 * ({@link com.hearthstead.building.Fuel#burns} — smelter, bakery, smithy,
 * brewery) consume one fuel item per finished batch, and a cold forge does
 * nothing at all.
 *
 * <p>Arena idiom copied from {@code TradeSmelterGameTests}. Tests (a) and
 * (b) reuse its deliberate trick of seeding 2 raw iron, BELOW the
 * iron_bloom recipe's threshold of 3, so the need-aware selector cannot
 * divert their proofs into the bloom recipe's by-design yield multiplier
 * and their ledgers stay exactly 1:1; test (e) crosses that threshold on
 * purpose, because the bloom path's economics are its entire subject.
 *
 * <p>What each test pins:
 * <ul>
 * <li>(a) the GATE — ore, a hired smelter, working hours, and still nothing,
 *     because there is no fuel;</li>
 * <li>(b) the LEDGER — with charcoal in the chest the same smelter smelts,
 *     and exactly one charcoal is destroyed per batch, never more, never
 *     sooner (the one sanctioned item sink — INV-3's amended note in
 *     {@link Production});</li>
 * <li>(c) the COLD-START EXEMPTION — bare logs still become charcoal,
 *     conserving count exactly (the charcoal batch burns nothing beyond its
 *     own input log), so a fuel-empty settlement can always light its first
 *     fire instead of deadlocking;</li>
 * <li>(d) the gate is per-TYPE, not a smelter special: the bakery's ovens
 *     go cold too, and wake with charcoal. Tested at the {@link Production}
 *     layer directly (the same sanctioned style {@code ChainsGameTests}
 *     uses), because the gate lives in Production and (a)–(c) already prove
 *     the identical ready()/run() pair live through a hired settler;</li>
 * <li>(e) the FLOWS.md BAND — seeded ABOVE the bloom threshold, the
 *     smelter–smithy fed path is physically run end to end (fuel and all)
 *     and its ticks-per-ingot advantage over the rough smelt is asserted as
 *     a RATIO read off the very recipes that ran — at least ×1.5, at most
 *     ×2 — so a future tick retune that drifts out of the band fails here,
 *     whatever the numbers become (owner-critic verdict #1 / krav 10).</li>
 * </ul>
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class FuelGameTests {

    private static void floor(GameTestHelper helper, int size) {
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
    }

    /** Registered with SettlementSavedData so the entity layer can find it —
     *  see {@code TradeSmelterGameTests#settlement} for the failure mode a
     *  bare Settlement object produces. */
    private static Settlement settlement(GameTestHelper helper) {
        com.hearthstead.settlement.SettlementSavedData data =
            com.hearthstead.settlement.SettlementSavedData.get(helper.getLevel());
        Settlement s = new Settlement(UUID.randomUUID(), "Testholm",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        // Small on purpose: neighbouring arenas must not answer for each
        // other through SettlementManager.at()'s radius resolution.
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

    private static Container chestAt(GameTestHelper helper, BlockPos rel) {
        helper.setBlock(rel, Blocks.CHEST);
        BlockEntity be = helper.getLevel().getBlockEntity(helper.absolutePos(rel));
        helper.assertTrue(be instanceof Container,
            "the arena chest should be a container");
        return (Container) be;
    }

    /** How many slots the chest still has, for refusal diagnostics. */
    private static int freeSlots(net.minecraft.world.Container c) {
        int free = 0;
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (c.getItem(i).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    private static int countOf(Container chest, Item item) {
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            ItemStack stack = chest.getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countAll(Container chest) {
        int total = 0;
        for (int slot = 0; slot < chest.getContainerSize(); slot++) {
            total += chest.getItem(slot).getCount();
        }
        return total;
    }

    // ------------------------------------------------------------------ (a) ---

    /**
     * The gate itself: ore in the chest, a smelter hired and standing at the
     * forge, working hours — and NOTHING for 400 ticks (two full batch
     * lengths), because the building holds no fuel. The chest must be
     * byte-identical to how it was seeded: no ingots, both raw iron still
     * there, nothing consumed on a batch that never ran. And the diagnosis
     * must name the right shortage: {@link Production#starvedForFuel} is
     * true the whole time — this workshop is cold, not empty.
     */
    @GameTest(batch = "fuel", template = "empty16", timeoutTicks = 600)
    public void aSmelterWithoutFirewoodMakesNothing(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building smelter = building(helper, s, BuildingType.SMELTER, 4, 4);
        Container chest = chestAt(helper, new BlockPos(5, 1, 4));
        // Two raw iron, deliberately below iron_bloom's threshold of three
        // (TradeSmelterGameTests' own idiom) so only the 1:1 recipe is even
        // in question and the "untouched" assertion below is exact.
        chest.setItem(0, new ItemStack(Items.RAW_IRON, 2));

        SettlerEntity brann = settler(helper, s, "Brann", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, smelter, brann).ok(),
            "a forge must be able to take a smelter");

        helper.assertTrue(Production.ready(helper.getLevel(), smelter) == null,
            "a fuel-less smelter must offer no work at all, not work that fails");
        helper.assertTrue(Production.starvedForFuel(helper.getLevel(), smelter),
            "and the diagnosis must be 'cold', not 'empty': inputs and room "
                + "are both there, only the fire is missing");

        // Mid-morning: working hours, so only the fuel gate stands between
        // the hired smelter and the ore.
        helper.getLevel().setDayTime(3000);

        helper.runAtTickTime(400, () -> {
            helper.assertTrue(countOf(chest, Items.IRON_INGOT) == 0,
                "no fuel, no ingots — saw "
                    + countOf(chest, Items.IRON_INGOT) + " after 400 ticks");
            helper.assertTrue(countOf(chest, Items.RAW_IRON) == 2,
                "a batch that never ran must consume nothing: expected both "
                    + "raw iron untouched, saw " + countOf(chest, Items.RAW_IRON));
            helper.assertTrue(countAll(chest) == 2,
                "the chest must be exactly as seeded, saw " + countAll(chest)
                    + " items");
            helper.assertTrue(Production.starvedForFuel(helper.getLevel(), smelter),
                "the fuel-starvation diagnostic must still hold after 400 ticks");
            helper.succeed();
        });
    }

    // ------------------------------------------------------------------ (b) ---

    /**
     * The exact fuel ledger. Same arena as (a) plus two charcoal: now the
     * hired smelter smelts both raw iron, and at EVERY observed tick the
     * books balance — each finished ingot has cost exactly one raw iron and
     * exactly one charcoal, and the chest's grand total has shrunk by
     * precisely the charcoal burned (the one sanctioned item sink; every
     * other count is conserved). A fuel system that burned early, burned
     * double, or burned on failure could not hold these equalities through
     * a single poll.
     */
    @GameTest(batch = "fuel", template = "empty16", timeoutTicks = 900)
    public void firewoodFedSmelterSmeltsWithAnExactLedger(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building smelter = building(helper, s, BuildingType.SMELTER, 4, 4);
        Container chest = chestAt(helper, new BlockPos(5, 1, 4));
        // Below the bloom threshold, as in (a), so the ledger stays 1:1.
        chest.setItem(0, new ItemStack(Items.RAW_IRON, 2));
        chest.setItem(1, new ItemStack(Items.CHARCOAL, 2));
        int before = countAll(chest);

        SettlerEntity brann = settler(helper, s, "Brann", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, smelter, brann).ok(),
            "a forge must be able to take a smelter");
        helper.assertTrue(!Production.starvedForFuel(helper.getLevel(), smelter),
            "with charcoal in the chest the smelter is not fuel-starved");

        helper.getLevel().setDayTime(3000);

        helper.succeedWhen(() -> {
            int ingots = countOf(chest, Items.IRON_INGOT);
            int raw = countOf(chest, Items.RAW_IRON);
            int charcoal = countOf(chest, Items.CHARCOAL);
            helper.assertTrue(raw == 2 - ingots,
                "each ingot must cost exactly one raw iron: ingots=" + ingots
                    + " raw=" + raw);
            helper.assertTrue(charcoal == 2 - ingots,
                "each batch must burn exactly one charcoal, at completion and "
                    + "never before: ingots=" + ingots + " charcoal=" + charcoal);
            helper.assertTrue(countAll(chest) == before - ingots,
                "burned fuel is the ONLY thing that may leave the books: "
                    + "started with " + before + ", chest holds " + countAll(chest)
                    + " with " + ingots + " ingots made");
            helper.assertTrue(ingots == 2,
                "both raw iron should end as ingots; saw " + ingots
                    + " (raw=" + raw + " charcoal=" + charcoal + ")");
        });
    }

    // ------------------------------------------------------------------ (c) ---

    /**
     * The cold-start exemption, proven by conservation. A smelter with
     * nothing but three logs — the classic day-one settlement — must char
     * all three into charcoal. The per-poll invariant {@code logs + charcoal
     * == 3} is what actually pins the exemption: if the charcoal recipe
     * were fuel-gated like the rest, each batch would eat a second item as
     * fuel and the total would fall, and the last log could never be
     * charred at all (it cannot be its own input AND its own fuel) — the
     * deadlock the exemption exists to prevent.
     */
    @GameTest(batch = "fuel", template = "empty16", timeoutTicks = 600)
    public void bareLogsStillBecomeCharcoalColdStart(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building smelter = building(helper, s, BuildingType.SMELTER, 4, 4);
        Container chest = chestAt(helper, new BlockPos(5, 1, 4));
        chest.setItem(0, new ItemStack(Items.OAK_LOG, 3));

        Production.Recipe recipe = Production.ready(helper.getLevel(), smelter);
        helper.assertTrue(recipe != null && recipe.id().equals("charcoal"),
            "a smelter holding only logs must offer the charcoal recipe "
                + "(no fuel gate on making fuel), got "
                + (recipe == null ? "null" : recipe.id()));
        helper.assertTrue(!Production.starvedForFuel(helper.getLevel(), smelter),
            "a building that can make its own fuel is never fuel-starved");

        SettlerEntity brann = settler(helper, s, "Brann", 4, 4);
        helper.assertTrue(Employment.hire(helper.getLevel(), s, smelter, brann).ok(),
            "a forge must be able to take a smelter");
        helper.getLevel().setDayTime(3000);

        helper.succeedWhen(() -> {
            int logs = countOf(chest, Items.OAK_LOG);
            int charcoal = countOf(chest, Items.CHARCOAL);
            helper.assertTrue(logs + charcoal == 3,
                "charring must conserve count exactly — the exempt batch "
                    + "consumes only its own input log and burns nothing "
                    + "besides: logs=" + logs + " charcoal=" + charcoal);
            helper.assertTrue(charcoal == 3,
                "all three logs should be charred, down to the very last one; "
                    + "saw charcoal=" + charcoal + " logs=" + logs);
        });
    }

    // ------------------------------------------------------------------ (d) ---

    /**
     * The gate is a property of every burning TYPE, not a smelter special:
     * the bakery's ovens go cold the same way and wake the same way. Wheat
     * alone — inputs present, room present — bakes nothing and reads as
     * fuel-starved; one charcoal turns the same chest into one loaf, three
     * wheat spent, the charcoal destroyed (the sanctioned sink), and not
     * one item more or less anywhere in the building.
     */
    @GameTest(batch = "fuel", template = "empty16", timeoutTicks = 200)
    public void aBakeryBakesOnlyWithFirewood(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building bakery = building(helper, s, BuildingType.BAKERY, 4, 4);
        Container chest = chestAt(helper, new BlockPos(5, 1, 4));
        chest.setItem(0, new ItemStack(Items.WHEAT, 6));

        helper.assertTrue(Production.ready(helper.getLevel(), bakery) == null,
            "a bakery with wheat but no firewood must offer no work — cold "
                + "ovens bake nothing");
        helper.assertTrue(Production.starvedForFuel(helper.getLevel(), bakery),
            "and it must diagnose as fuel-starved, not as empty");

        chest.setItem(1, new ItemStack(Items.CHARCOAL, 1));
        helper.assertTrue(!Production.starvedForFuel(helper.getLevel(), bakery),
            "one charcoal lights the ovens");
        Production.Recipe recipe = Production.ready(helper.getLevel(), bakery);
        helper.assertTrue(recipe != null && recipe.id().equals("bread"),
            "with no flour anywhere the fuelled bakery falls through to the "
                + "rough grain recipe, got "
                + (recipe == null ? "null" : recipe.id()));

        helper.assertTrue(Production.run(helper.getLevel(), bakery, recipe),
            "the bread recipe should have run");

        helper.assertTrue(countOf(chest, Items.BREAD) == 1,
            "one loaf baked; saw " + countOf(chest, Items.BREAD));
        helper.assertTrue(countOf(chest, Items.WHEAT) == 3,
            "three wheat of six spent; saw " + countOf(chest, Items.WHEAT));
        helper.assertTrue(countOf(chest, Items.CHARCOAL) == 0,
            "the charcoal burned with the batch — destroyed, the one "
                + "sanctioned sink; saw " + countOf(chest, Items.CHARCOAL));
        helper.assertTrue(countAll(chest) == 4,
            "the whole ledger: 7 items seeded, minus 3 wheat and 1 charcoal, "
                + "plus 1 loaf = 4; saw " + countAll(chest));
        helper.succeed();
    }

    // ------------------------------------------------------------------ (e) ---

    /** The named recipe from a type's table, or a failed test. */
    private static Production.Recipe recipeById(GameTestHelper helper,
                                                BuildingType type, String id) {
        for (Production.Recipe r : Production.of(type)) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        helper.fail("recipe '" + id + "' missing from " + type.id() + "'s table");
        return null;
    }

    /** The hand-simulated courier hop {@code ChainsGameTests} uses. */
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

    /**
     * The FLOWS.md band, proven by running the chain — not by trusting the
     * comment that claims it. Seeded ABOVE the bloom threshold (two full
     * bloom batches of raw iron, where {@code TradeSmelterGameTests}
     * deliberately stays below it), the smelter batches bloom, a
     * hand-simulated courier hops it to the smithy, and the smithy finishes
     * it into ingots — every batch physically executed through
     * {@link Production#run} with its fuel really burned. The tick and fuel
     * ledgers are then read off the SAME recipe records that ran, and the
     * verdict is a ratio, not a constant: ticks-per-ingot on the fed path
     * must beat the rough smelt by at least ×1.5 and by no more than ×2
     * ("multiply, never gate" — the rough path has to stay worth using),
     * and the fed path may not spend more firewood or more ore per ingot
     * either. Retune any of the three recipes and this test re-derives the
     * arithmetic; only leaving the band fails it.
     */
    @GameTest(batch = "fuel", template = "empty16", timeoutTicks = 200)
    public void bloomFedPathBeatsRoughSmeltingWithinTheFlowsBand(GameTestHelper helper) {
        floor(helper, 16);
        Settlement s = settlement(helper);
        Building smelter = building(helper, s, BuildingType.SMELTER, 2, 2);
        Building smithy = building(helper, s, BuildingType.SMITHY, 9, 2);
        Container smelterChest = chestAt(helper, new BlockPos(3, 1, 2));
        Container smithyChest = chestAt(helper, new BlockPos(10, 1, 2));

        Production.Recipe bloom = recipeById(helper, BuildingType.SMELTER, "iron_bloom");
        Production.Recipe rough = recipeById(helper, BuildingType.SMELTER, "iron");
        Production.Recipe finish = recipeById(helper, BuildingType.SMITHY, "bloom_ingot");

        // Everything below is DERIVED from the table, so the ledger keeps
        // fitting whatever the recipes are retuned to — as long as the
        // counts still divide cleanly, which is itself asserted.
        int bloomBatches = 2;
        int rawSeeded = bloomBatches * bloom.inputCount();
        int blooms = bloomBatches * bloom.outputCount();
        helper.assertTrue(blooms % finish.inputCount() == 0,
            "a retune broke the clean ledger: " + blooms + " blooms do not "
                + "divide into " + finish.inputCount() + "-bloom finishing batches");
        int finishBatches = blooms / finish.inputCount();
        int ingots = finishBatches * finish.outputCount();
        helper.assertTrue(ingots % rough.outputCount() == 0,
            "a retune broke the comparison: " + ingots + " ingots do not "
                + "divide into " + rough.outputCount() + "-ingot rough batches");
        int roughBatches = ingots / rough.outputCount();

        // --- the fed path, physically run, fuel and all ---
        smelterChest.setItem(0, new ItemStack(Items.RAW_IRON, rawSeeded));
        smelterChest.setItem(1, new ItemStack(Items.CHARCOAL,
            bloomBatches * Fuel.perBatch(BuildingType.SMELTER)));

        Production.Recipe picked = Production.ready(helper.getLevel(), smelter);
        helper.assertTrue(picked != null && picked.id().equals(bloom.id()),
            "above the bloom threshold, with fuel and no ingot stock, the "
                + "fed recipe must be the smelter's pick, got "
                + (picked == null ? "null" : picked.id()));
        // Batches beyond the first are run directly: need-aware ready()
        // deliberately alternates toward whichever output is scarcer, and
        // this test measures the chain's arithmetic, not the scheduler.
        for (int i = 0; i < bloomBatches; i++) {
            helper.assertTrue(Production.run(helper.getLevel(), smelter, bloom),
                "bloom batch " + (i + 1) + " should have run (fuel present)");
        }
        helper.assertTrue(countOf(smelterChest, Items.RAW_IRON) == 0
                && countOf(smelterChest, ModItems.IRON_BLOOM.get()) == blooms
                && countOf(smelterChest, Items.CHARCOAL) == 0,
            "after " + bloomBatches + " bloom batches: raw="
                + countOf(smelterChest, Items.RAW_IRON) + " (want 0), bloom="
                + countOf(smelterChest, ModItems.IRON_BLOOM.get()) + " (want " + blooms
                + "), charcoal=" + countOf(smelterChest, Items.CHARCOAL)
                + " (want 0 — one burned per batch)");

        moveAll(smelterChest, smithyChest, ModItems.IRON_BLOOM.get());
        smithyChest.setItem(1, new ItemStack(Items.CHARCOAL,
            finishBatches * Fuel.perBatch(BuildingType.SMITHY)));
        for (int i = 0; i < finishBatches; i++) {
            helper.assertTrue(Production.run(helper.getLevel(), smithy, finish),
                "finishing batch " + (i + 1) + " should have run (fuel present)"
                    // Name what was missing instead of leaving the reader to
                    // guess between input, fuel and room -- run() refuses on
                    // any of the three and says nothing about which.
                    + " [bloom=" + countOf(smithyChest, ModItems.IRON_BLOOM.get())
                    + "/" + finish.inputCount()
                    + " charcoal=" + countOf(smithyChest, Items.CHARCOAL)
                    + " ingots=" + countOf(smithyChest, Items.IRON_INGOT)
                    + " freeSlots=" + freeSlots(smithyChest) + "]");
        }
        helper.assertTrue(countOf(smithyChest, Items.IRON_INGOT) == ingots
                && countOf(smithyChest, ModItems.IRON_BLOOM.get()) == 0
                && countOf(smithyChest, Items.CHARCOAL) == 0,
            "after finishing: ingots=" + countOf(smithyChest, Items.IRON_INGOT)
                + " (want " + ingots + "), bloom="
                + countOf(smithyChest, ModItems.IRON_BLOOM.get()) + " charcoal="
                + countOf(smithyChest, Items.CHARCOAL));

        // --- the ledgers, read off the recipes that just ran ---
        int fedTicks = bloomBatches * bloom.ticks() + finishBatches * finish.ticks();
        int roughTicks = roughBatches * rough.ticks();
        int fedFuel = bloomBatches * Fuel.perBatch(BuildingType.SMELTER)
            + finishBatches * Fuel.perBatch(BuildingType.SMITHY);
        int roughFuel = roughBatches * Fuel.perBatch(BuildingType.SMELTER);
        int roughOre = roughBatches * rough.inputCount();

        helper.assertTrue(2 * roughTicks >= 3 * fedTicks,
            "the fed path must be at least a x1.5 tick advantage end to end "
                + "(FLOWS.md band floor): rough=" + roughTicks + "t vs fed="
                + fedTicks + "t for the same " + ingots + " ingots");
        helper.assertTrue(roughTicks <= 2 * fedTicks,
            "and no more than x2 (multiply, never gate — the rough path must "
                + "stay worth using): rough=" + roughTicks + "t vs fed="
                + fedTicks + "t");
        helper.assertTrue(fedFuel <= roughFuel,
            "the fed path may not burn more firewood for the same ingots: fed="
                + fedFuel + " vs rough=" + roughFuel);
        helper.assertTrue(rawSeeded <= roughOre,
            "nor eat more ore: fed=" + rawSeeded + " vs rough=" + roughOre);
        helper.succeed();
    }

}
