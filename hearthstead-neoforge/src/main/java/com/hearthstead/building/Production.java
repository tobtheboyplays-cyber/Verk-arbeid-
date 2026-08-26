package com.hearthstead.building;

import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.research.Research;
import com.hearthstead.settlement.research.ResearchKey;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * What a building can make, and the act of making it.
 *
 * <p>This is the seam every profession plugs into. A work building's job is
 * always the same shape — take something out of its own chests, spend time,
 * put something back — so it is written once here rather than once per
 * profession. Twenty-eight buildings cannot each have a bespoke crafting
 * implementation and stay correct.
 *
 * <h2>The rules this encodes</h2>
 *
 * <p><b>D-007 — a building works alone.</b> Inputs come from the building's
 * OWN containers. A bakery with wheat in its chest bakes bread with no mill,
 * no farm and no warehouse anywhere in the world. Couriers keep it stocked
 * once they exist, but they are an optimisation, never a precondition.
 *
 * <p><b>INV-3 — chest truth, and nothing is ever destroyed.</b> A recipe runs
 * only when there is somewhere to put the result, the inputs come out of real
 * slots, and the output goes into real slots. If the output cannot be placed
 * after the inputs are gone — which should be impossible, because room is
 * checked first — it is dropped into the world rather than voided.
 *
 * <p><b>THE ONE SANCTIONED EXCEPTION — burned fuel.</b> At a burning
 * building ({@link Fuel#burns}) each finished batch also consumes
 * {@link Fuel#perBatch} fuel from the same chests, and that fuel is
 * DESTROYED, deliberately and in exactly one place ({@link #run}). This is
 * the vanilla furnace's own bargain — coal in, nothing of the coal out,
 * finished goods instead — and it is the "firewood/warmth" upkeep flow
 * DESIGN.md pillar 2 / R20 decided. It converts to finished goods, it never
 * silently vanishes on a failure path (every refusal and race gives fuel
 * back), and no OTHER path through this class reduces the number of items
 * in the world. Tests that assert whole-chest conservation over a burning
 * building must account for it: total shrinks by exactly perBatch per
 * completed batch, never more.
 *
 * <p><b>D-009 — domains, not recipe lists.</b> Inputs are matched with
 * {@link Ingredient}, so a recipe can accept a whole tag rather than one item.
 *
 * <p><b>FLOWS.md — multiply, never gate.</b> ({@code docs/project/FLOWS.md},
 * the coordinator's constitution for how buildings feed each other.) SLICE
 * CHAINS added six intermediate goods on top of the rows above: FLOUR,
 * MALT (+ its terminal, ALE), IRON_BLOOM, TIMBER_BEAM, CURED_HIDE and
 * WOOL_BOLT. Every one sits on a fed-path edge — a second recipe on the SAME
 * output item, listed FIRST, that only fires once the intermediate exists —
 * beside the rough path it never replaces. See PLAN_CHAINS.md for the full
 * ledger and the acyclicity proof {@code ChainsGameTests} checks statically.
 */
public final class Production {

    /**
     * One thing a building knows how to make.
     *
     * @param id     stable name, for logs and tests
     * @param input  what it consumes — an {@link Ingredient}, so a recipe can
     *               take any member of a tag
     * @param inputCount how many
     * @param output what it produces
     * @param outputCount how many
     * @param ticks  how long the work takes, so a profession can animate it
     */
    public record Recipe(String id, Ingredient input, int inputCount,
                         Item output, int outputCount, int ticks) {
    }

    private static final Map<BuildingType, List<Recipe>> RECIPES =
        new EnumMap<>(BuildingType.class);

    static {
        // SLICE CHAINS -- six intermediate goods bound by FLOWS.md
        // (docs/project/FLOWS.md), the coordinator's constitution for how
        // buildings feed each other. Its one rule: every fed path sits beside
        // a rough path that never goes away (D-007) -- the mill, brewery,
        // smelter, sawmill, butcher and weaver entries below are the
        // "upstream" halves; the improved recipes they feed are listed FIRST
        // in the consuming building's own table so Production#ready prefers
        // them once the ingredient exists, and fall through to the untouched
        // rough recipe when it does not. See PLAN_CHAINS.md for the full
        // ledger (every ratio, every tick count, the acyclicity proof).

        // Mill: pure upstream, like FLOWS describes it -- no rough/fed split
        // of its own, it just turns wheat into flour for the bakery (and,
        // eventually, whoever else wants it).
        put(BuildingType.MILL,
            new Recipe("flour", Ingredient.of(Items.WHEAT), 3, ModItems.FLOUR.get(), 2, 140));

        // Chain A, food. Three wheat to a loaf: the same ratio vanilla uses,
        // so a player already knows the exchange rate -- and it stays exactly
        // as it was, first slice to last (D-007: the mill multiplies, never
        // gates). The flour recipe is listed FIRST and costs half the ticks
        // per loaf (80 vs 160), so a bakery fed by a mill visibly outproduces
        // one running on grain alone, without the grain-only path ever
        // stopping working.
        put(BuildingType.BAKERY,
            new Recipe("bread_flour", Ingredient.of(ModItems.FLOUR.get()), 2, Items.BREAD, 2, 160),
            new Recipe("bread", Ingredient.of(Items.WHEAT), 3, Items.BREAD, 1, 160));

        // Chain A again: the butcher makes what was caught keep longer, and
        // (SLICE CHAINS) also cures a rabbit skin into CURED_HIDE for the
        // tannery's fed path -- a fresh input (RABBIT), not one already
        // claimed by the four cooking recipes above, so neither competes with
        // the other for Production#ready's "first satisfiable" pick.
        put(BuildingType.BUTCHER,
            new Recipe("beef", Ingredient.of(Items.BEEF), 1, Items.COOKED_BEEF, 1, 120),
            new Recipe("pork", Ingredient.of(Items.PORKCHOP), 1, Items.COOKED_PORKCHOP, 1, 120),
            new Recipe("mutton", Ingredient.of(Items.MUTTON), 1, Items.COOKED_MUTTON, 1, 120),
            new Recipe("chicken", Ingredient.of(Items.CHICKEN), 1, Items.COOKED_CHICKEN, 1, 120),
            new Recipe("hide", Ingredient.of(Items.RABBIT), 2, ModItems.CURED_HIDE.get(), 2, 160));

        // Chain B, tools: the half of it that is unambiguous today. Ore to
        // ingot needs no judgement call about which plank a log becomes.
        // SLICE CHAINS adds the smelter's own fed path -- iron_bloom, listed
        // FIRST with a threshold (3 raw iron) higher than the plain smelt's
        // (1), so a small stockpile still smelts straight to ingots and only
        // a comfortable surplus gets batched into bloom for the smithy to
        // finish (see SMITHY below).
        //
        // FED-PATH ARITHMETIC (owner-critic verdict #1 / krav 10 -- the
        // FLOWS.md x1.5-x2 band, measured END TO END, not per recipe):
        //   rough:  1 raw -> 1 ingot in 200t            = 200 t/ingot
        //   fed:    3 raw -> 4 bloom in 160t, then the
        //           smithy finishes 2 bloom -> 2 ingot
        //           in 160t, twice:  160 + 2x160 = 480t
        //           for 4 ingots                        = 120 t/ingot
        //   advantage: 200/120 = x1.67 -- inside the band, and a real one.
        // Ore rides along at 3 raw -> 4 ingots (x1.33), and FUEL counts too:
        // the fed chain burns 3 batches' fuel per 4 ingots (0.75/ingot)
        // against the rough path's 1/ingot (x1.33 on firewood).
        // FuelGameTests#bloomFedPathBeatsRoughSmeltingWithinTheFlowsBand
        // asserts the x1.5 floor and x2.0 ceiling as RATIOS over this very
        // table, so a retune that drifts out of the band fails the suite,
        // not the review.
        put(BuildingType.SMELTER,
            new Recipe("iron_bloom", Ingredient.of(Items.RAW_IRON), 3, ModItems.IRON_BLOOM.get(), 4, 160),
            new Recipe("iron", Ingredient.of(Items.RAW_IRON), 1, Items.IRON_INGOT, 1, 200),
            new Recipe("copper", Ingredient.of(Items.RAW_COPPER), 1, Items.COPPER_INGOT, 1, 200),
            new Recipe("gold", Ingredient.of(Items.RAW_GOLD), 1, Items.GOLD_INGOT, 1, 240),
            // FUEL (DESIGN.md pillar 2 / R20, "firewood/warmth"): the smelter
            // chars any log into charcoal — dense, stackable firewood the
            // couriers can carry to every burning building. Listed LAST so
            // ore work keeps precedence on need-aware ties; a whole tag
            // (LOGS) as input, so no biome's wood is locked out (D-009).
            //
            // This is the COLD-START EXEMPT recipe: it is the only recipe at
            // a burning building that ready()/run() do NOT gate on fuel, and
            // it burns none, because the log IS the fire — it chars in its
            // own heat. Without the exemption the settlement deadlocks from
            // any fuel-empty state: making fuel would require fuel, forever.
            //
            // Acyclicity (FLOWS.md "no value mints", ChainsGameTests (d)):
            // log -> charcoal adds no cycle to the goods DAG — charcoal is
            // an input to NO recipe, so it is a sink node. The apparent
            // self-loop ("charcoal is fuel, and fuel makes charcoal") lives
            // only in the FUEL ledger, and the exemption is exactly what
            // keeps that loop safe: charcoal creation consumes only its log
            // (1:1, value-conserving), while charcoal CONSUMPTION is pure
            // destruction (the sanctioned sink above) — every trip around
            // the "loop" strictly loses a log and mints nothing.
            new Recipe("charcoal", Ingredient.of(ItemTags.LOGS), 1, Items.CHARCOAL, 1, 90));

        // The kitchen turns what the settlement has into something worth
        // sitting down to. Until the Meal item exists (D-008) it cooks, which
        // is honest work and useful on its own.
        put(BuildingType.KITCHEN,
            new Recipe("stew", Ingredient.of(Items.BROWN_MUSHROOM), 2, Items.MUSHROOM_STEW, 1, 140),
            new Recipe("baked_potato", Ingredient.of(Items.POTATO), 1, Items.BAKED_POTATO, 1, 100),
            new Recipe("dried_kelp", Ingredient.of(Items.KELP), 1, Items.DRIED_KELP, 1, 80));

        // SLICE CHAINS -- the brewery, previously empty in this table (a
        // building type with no recipe at all yet). FLOWS.md's rough path is
        // wheat -> ale directly; the fed path malts the wheat first and
        // brews from THAT, at half the ticks per unit of ale (100 vs 200).
        // ale_malt is listed first (a different item, malt, so it can never
        // starve the other two of a turn); malt is listed before the rough
        // ale recipe with a HIGHER wheat threshold (4 vs 3) so a modest
        // stockpile still brews directly and only a surplus gets malted for
        // the better batch.
        put(BuildingType.BREWERY,
            new Recipe("ale_malt", Ingredient.of(ModItems.MALT.get()), 2, ModItems.ALE.get(), 2, 200),
            new Recipe("malt", Ingredient.of(Items.WHEAT), 4, ModItems.MALT.get(), 3, 140),
            new Recipe("ale", Ingredient.of(Items.WHEAT), 3, ModItems.ALE.get(), 1, 200));

        // Chain B, timber. A sawmill gets six planks from a log where a settler
        // with a hand axe gets four -- the chain buys yield, never permission.
        // SLICE CHAINS adds timber_beam, listed FIRST with a 3-log threshold
        // (vs the plank recipes' 1) so ordinary plank supply for building and
        // every other consumer is never starved by it -- only a genuine log
        // surplus gets milled into beams for the carpenter's fed barrel
        // recipe below.
        put(BuildingType.SAWMILL,
            new Recipe("timber_beam", Ingredient.of(Items.OAK_LOG), 3, ModItems.TIMBER_BEAM.get(), 2, 180),
            new Recipe("planks", Ingredient.of(Items.OAK_LOG), 1, Items.OAK_PLANKS, 6, 120),
            new Recipe("spruce_planks", Ingredient.of(Items.SPRUCE_LOG), 1, Items.SPRUCE_PLANKS, 6, 120),
            new Recipe("birch_planks", Ingredient.of(Items.BIRCH_LOG), 1, Items.BIRCH_PLANKS, 6, 120));

        // barrel_beam is the fed half of the sawmill -> carpenter edge: same
        // barrel, half the ticks per unit (130 vs 260) when the carpenter has
        // beams on hand. A different input (TIMBER_BEAM) to the rough barrel
        // recipe's OAK_PLANKS, so it cannot starve it either way.
        put(BuildingType.CARPENTER,
            new Recipe("sticks", Ingredient.of(Items.OAK_PLANKS), 2, Items.STICK, 4, 60),
            new Recipe("barrel", Ingredient.of(Items.OAK_PLANKS), 7, Items.BARREL, 1, 260),
            new Recipe("barrel_beam", Ingredient.of(ModItems.TIMBER_BEAM.get()), 2, Items.BARREL, 1, 130),
            new Recipe("ladder", Ingredient.of(Items.STICK), 7, Items.LADDER, 3, 140));

        // "The smithy forges a tool from metal alone" -- PLAN_PRODUCTION_CHAINS,
        // and it is the load-bearing example of D-007. A smithy that demanded
        // both an ingot and a carpenter's haft would do nothing until two other
        // buildings existed.
        //
        // SLICE CHAINS: bloom_ingot finishes the smelter's iron_bloom into a
        // proper ingot -- the smelter<->smithy edge FLOWS.md names -- at 80
        // ticks per ingot (160t for 2) against the rough smelt's 200. This
        // is the smithy half of the end-to-end x1.67 fed-path arithmetic
        // spelled out on the SMELTER's iron_bloom entry above; retune the
        // pair together or the band test in FuelGameTests fails. Its input
        // (IRON_BLOOM) never collides with the four tool recipes' IRON_INGOT,
        // so it is simply additional smithy work, not a competitor to them.
        put(BuildingType.SMITHY,
            new Recipe("bloom_ingot", Ingredient.of(ModItems.IRON_BLOOM.get()), 2, Items.IRON_INGOT, 2, 160),
            new Recipe("axe", Ingredient.of(Items.IRON_INGOT), 3, Items.IRON_AXE, 1, 300),
            new Recipe("pickaxe", Ingredient.of(Items.IRON_INGOT), 3, Items.IRON_PICKAXE, 1, 300),
            new Recipe("hoe", Ingredient.of(Items.IRON_INGOT), 2, Items.IRON_HOE, 1, 240),
            new Recipe("sword", Ingredient.of(Items.IRON_INGOT), 2, Items.IRON_SWORD, 1, 260));

        put(BuildingType.MASON,
            new Recipe("stone_bricks", Ingredient.of(Items.STONE), 4, Items.STONE_BRICKS, 4, 160),
            new Recipe("stone", Ingredient.of(Items.COBBLESTONE), 1, Items.STONE, 1, 120));

        put(BuildingType.FLETCHER,
            new Recipe("arrows", Ingredient.of(Items.FLINT), 1, Items.ARROW, 4, 100),
            new Recipe("bow", Ingredient.of(Items.STRING), 3, Items.BOW, 1, 280));

        // SLICE CHAINS: wool_bolt is the weaver's own upstream good (FLOWS.md
        // calls it "wool -> cloth", named WOOL_BOLT here) -- no rough/fed
        // split of its own, the same "pure upstream" shape as the mill's
        // flour. It shares WOOL with the existing banner recipe and is
        // listed FIRST with a lower threshold (3 vs 6), so once a weaver has
        // any real wool surplus it becomes the settlement's main use for
        // wool (feeding a future outfits/market economy per FLOWS.md);
        // banner still exists and still fires below wool_bolt's threshold,
        // and stays directly player-craftable at a loom regardless.
        put(BuildingType.WEAVER,
            new Recipe("wool", Ingredient.of(Items.STRING), 4, Items.WHITE_WOOL, 1, 140),
            new Recipe("wool_bolt", Ingredient.of(Items.WHITE_WOOL), 3, ModItems.WOOL_BOLT.get(), 2, 130),
            new Recipe("banner", Ingredient.of(Items.WHITE_WOOL), 6, Items.WHITE_BANNER, 1, 260));

        // leather_cured is the tannery's fed path: the butcher's CURED_HIDE
        // (see BUTCHER above) makes leather at half the ticks per unit (90 vs
        // 180) of the rabbit-hide-alone recipe below, which is untouched and
        // still the tannery's whole D-007 story with no butcher in the
        // world.
        put(BuildingType.TANNERY,
            new Recipe("leather_cured", Ingredient.of(ModItems.CURED_HIDE.get()), 2, Items.LEATHER, 2, 180),
            new Recipe("leather", Ingredient.of(Items.RABBIT_HIDE), 4, Items.LEATHER, 1, 180));

        // BALANCE_AUDIT.md finding 1 (BROKEN) / PLAN_CIRCULATION.md F3: the
        // armoury building has existed since BuildingType was written, and
        // GuardRank.applyEquipment has withdrawn real pieces from its chests
        // since owner-critic verdict #1 krav 4 -- but nothing ever PUT a
        // piece there. Every armour item the guard ladder can wear had to be
        // placed by the player's own hand; the village could not arm itself.
        // These eight recipes are that maker. (GuardRank's own equipment
        // table -- SPEARMAN through CAPTAIN -- actually asks for eight
        // distinct items, not the seven the audit named: LEATHER_LEGGINGS
        // is VETERAN's own piece and was missing from the audit's list too.)
        //
        // INPUTS ARE GOODS THIS ECONOMY ALREADY MAKES (design constraint):
        // LEATHER from the TANNERY (rough: rabbit hide; fed: the butcher's
        // cured hide), IRON_INGOT from the SMELTER (rough: raw iron; fed:
        // the smithy's bloom finish). No new material, no new building --
        // exactly the two goods FLOWS.md's "tannery -> armoury" and
        // "smithy -> armoury" table rows already promise a consumer for.
        //
        // MATERIAL COUNTS ANCHOR TO VANILLA'S OWN CRAFTING-TABLE RECIPE
        // (same idiom as BAKERY's 3-wheat loaf above: "the same ratio
        // vanilla uses, so a player already knows the exchange rate"):
        // helmet 5, chestplate 8, leggings 7, boots 4 -- identical for the
        // leather and iron line, so the two tiers read as the same kit in a
        // different metal, exactly like the visible ramp GuardRank's own
        // class doc describes ("a leather vest, then full leather, then
        // iron creeping in").
        //
        // TICK COST ANCHORS TO THE SMITHY'S OWN REGISTER, split by tier so
        // the guard ladder stays a LADDER (design constraint: "leather must
        // be meaningfully cheaper than iron, or a village that can field a
        // captain as easily as a spearman has no ladder"):
        //   - IRON pieces run at 130 ticks/ingot -- exactly the smithy's
        //     sword rate (2 IRON_INGOT -> 260 ticks, SMITHY table above).
        //     Iron armour is priced as "another smithy-grade forging job",
        //     not a discount on one.
        //   - LEATHER pieces run at 80 ticks/leather -- a deliberately
        //     cheaper bench rate than iron's 130, on TOP of leather's own
        //     upstream being cheaper to begin with (tannery's rough leather
        //     is 180t for 1, against the smelter's rough ingot at 200t for
        //     1, and the tannery's cured-hide fed path at 90t/2=45t per
        //     unit beats the smithy's own bloom-finish fed ingot at
        //     160t/2=80t per unit). The two gaps compound: iron is slower
        //     to grow AND slower to forge, so a full leather Veteran kit
        //     (24 leather, 1920 ticks total) is cheaper on both axes than a
        //     full iron Captain kit (24 ingots, 3120 ticks total) -- a real
        //     ladder, not just a label change on the same cost.
        // (ticks = materialCount * rate, both tiers, so the ladder holds at
        // every piece, not just the kit total: helmet 400 vs 650, boots 320
        // vs 520, leggings 560 vs 910, chestplate 640 vs 1040.)
        //
        // ACYCLICITY (FLOWS.md "no value mints", ChainsGameTests (d)): every
        // output here (the eight armour items) is a SINK -- none of them is
        // ever an INPUT to any recipe in this table, in ANY building, so
        // this only ever adds new leaf edges off LEATHER and IRON_INGOT.
        // Neither of those two items gains a path back to itself or to any
        // of its own ancestors (WHEAT/RABBIT/RABBIT_HIDE/CURED_HIDE for
        // leather; RAW_IRON/IRON_BLOOM for iron): a guard wears the armour
        // or GuardRank returns it to a chest on supersession, and either way
        // it never re-enters Production as an ingredient. No cycle is
        // reachable; ArmouryGameTests and the existing static DFS proof both
        // hold.
        put(BuildingType.ARMOURY,
            new Recipe("leather_helmet", Ingredient.of(Items.LEATHER), 5, Items.LEATHER_HELMET, 1, 400),
            new Recipe("leather_chestplate", Ingredient.of(Items.LEATHER), 8, Items.LEATHER_CHESTPLATE, 1, 640),
            new Recipe("leather_leggings", Ingredient.of(Items.LEATHER), 7, Items.LEATHER_LEGGINGS, 1, 560),
            new Recipe("leather_boots", Ingredient.of(Items.LEATHER), 4, Items.LEATHER_BOOTS, 1, 320),
            new Recipe("iron_helmet", Ingredient.of(Items.IRON_INGOT), 5, Items.IRON_HELMET, 1, 650),
            new Recipe("iron_chestplate", Ingredient.of(Items.IRON_INGOT), 8, Items.IRON_CHESTPLATE, 1, 1040),
            new Recipe("iron_leggings", Ingredient.of(Items.IRON_INGOT), 7, Items.IRON_LEGGINGS, 1, 910),
            new Recipe("iron_boots", Ingredient.of(Items.IRON_INGOT), 4, Items.IRON_BOOTS, 1, 520));
    }

    private static void put(BuildingType type, Recipe... recipes) {
        RECIPES.put(type, List.of(recipes));
    }

    /**
     * What a recipe actually costs this settlement in ticks, after research.
     *
     * <p>The completed project multiplies the printed cost — Bedre Gjær makes
     * a bakery's loaf 0.85x the ticks, and nothing else changes. FLOWS.md's
     * one rule holds: multiply, never gate. A settlement that has researched
     * nothing pays the table price, which is why {@link Research#bonus}
     * returns a neutral 1.0 rather than an absence.
     *
     * <p>Floored at one tick: a stack of future multipliers must never make
     * work instantaneous, and a zero-tick recipe would complete inside the
     * same tick it started, skipping the animation the player is supposed to
     * see doing it.
     */
    public static int ticksFor(ServerLevel level, java.util.UUID settlementId,
                               BuildingType type, Recipe recipe) {
        ResearchKey key = researchKeyFor(type);
        if (key == null || settlementId == null) {
            return recipe.ticks();
        }
        float multiplier = Research.bonus(level, settlementId, key);
        return Math.max(1, Math.round(recipe.ticks() * multiplier));
    }

    /** Which project, if any, speeds this kind of building up. */
    @Nullable
    private static ResearchKey researchKeyFor(BuildingType type) {
        return switch (type) {
            case BAKERY -> ResearchKey.BAKERY_TICKS;
            case SAWMILL -> ResearchKey.SAWMILL_TICKS;
            case SMELTER -> ResearchKey.SMELTER_TICKS;
            case TANNERY -> ResearchKey.TANNERY_TICKS;
            default -> null;
        };
    }

    /** Everything this kind of building knows how to make. Never null. */
    public static List<Recipe> of(BuildingType type) {
        return RECIPES.getOrDefault(type, List.of());
    }

    /** Whether any profession would have production work to do here at all. */
    public static boolean produces(BuildingType type) {
        return !of(type).isEmpty();
    }

    /**
     * The recipe this building should run right now: its inputs are in the
     * building's own chests, there is room for what comes out — and, among
     * every recipe that could run, the one whose OUTPUT the building has the
     * least of already.
     *
     * <p>Why need-aware and not first-listed: four independent trade audits
     * (sawyer, carpenter, weaver, fletcher — 20260825) found the same defect.
     * "Return the first satisfiable recipe" means a building with a steady
     * supply of one input makes that recipe forever and its siblings NEVER
     * run — the sawmill saws planks eternally while the beam order starves.
     * Preferring the scarcest output is the smallest rule that fixes all
     * four: a pile of planks stops attracting work by itself, and whatever
     * the chests are short of gets made next.
     *
     * <p>Ties (including the everyone-at-zero start) keep LIST ORDER, which
     * preserves the FLOWS.md fed-path doctrine: the improved recipe for the
     * same output is listed first and still wins whenever its intermediate
     * ingredient exists — bread_flour over bread, never the other way.
     *
     * <p><b>Fire is part of "satisfiable".</b> At a burning building
     * ({@link Fuel#burns}) a recipe joins the need-aware contest only if the
     * chests also hold at least {@link Fuel#perBatch} fuel — a cold forge
     * has no candidates at all, not a candidate it will fail to run. The
     * one exception is the smelter's {@code charcoal} recipe (see its table
     * entry): fuel-MAKING is exempt from the fuel gate, or an empty-handed
     * settlement could never light its first fire.
     *
     * <p>Deliberately a pure read — it changes nothing — so a work goal can
     * ask "is there anything to do?" every tick without side effects.
     */
    /**
     * How much of an output counts as a working stock -- below it, the recipe
     * that makes it keeps the bench in list order (the fed path first); above
     * it, the scarcest output wins. Deliberately the same eight as the
     * courier's keep-back: both answer "how much of a thing does a workshop
     * keep on hand before the surplus is somebody else's problem".
     */
    private static final int WORKING_RESERVE = 8;

    @Nullable
    public static Recipe ready(ServerLevel level, Building building) {
        List<Recipe> recipes = of(building.type);
        if (recipes.isEmpty() || building.bounds == null) {
            return null;
        }
        List<Container> containers = containersOf(level, building);
        if (containers.isEmpty()) {
            return null;
        }
        Recipe best = null;
        int bestStock = Integer.MAX_VALUE;
        for (Recipe recipe : recipes) {
            if (count(containers, recipe) < recipe.inputCount()
                || !hasRoomFor(containers, recipe)
                || !hasFuelFor(containers, building.type, recipe)) {
                continue;
            }
            int stock = countItem(containers, recipe.output());
            // The reserve rule applies ONLY between recipes making the SAME
            // thing -- the fed/rough pair (bread_flour before bread). Two
            // recipes making DIFFERENT things out of one input are not a fed
            // path at all, they are siblings competing for the same log, and
            // giving the first-listed one a reserve let it monopolise the
            // input: a sawmill with twelve logs spent every one on beams and
            // never cut a plank. Those alternate on need instead.
            boolean fedPair = false;
            for (Recipe other : recipes) {
                if (other != recipe && other.output() == recipe.output()) {
                    fedPair = true;
                    break;
                }
            }
            // List order wins until a WORKING RESERVE of that output exists.
            // Without this the selector abandoned a recipe the moment its
            // first batch landed: the sawmill made two beams, noticed planks
            // were scarcer, and never finished the beam order -- caught by
            // ChainsGameTests#threeBuildingChainConservesItemsEndToEnd, and
            // exactly the kind of specification conflict the protocol says to
            // resolve in the code rather than in the test. FLOWS lists the
            // fed path first because it should be preferred, so it keeps the
            // bench until the building holds a real stock of it; only then do
            // siblings get their turn, which is the starvation the need-aware
            // policy exists to cure.
            if (fedPair && stock < WORKING_RESERVE) {
                return recipe;
            }
            if (stock < bestStock) {
                best = recipe;
                bestStock = stock;
            }
        }
        return best;
    }

    /**
     * Whether this building is idle for want of FIREWOOD specifically: some
     * recipe's inputs are in the chests and its output has room — it would
     * run right now — but every such recipe is fuel-gated and the fuel is
     * not there. False whenever anything CAN run (including the charcoal
     * cold-start), and false when the problem is inputs or space rather
     * than fire.
     *
     * <p>A pure read, like {@link #ready}, for diagnostics and courier
     * prioritisation: "this workshop is cold" is a different call to action
     * than "this workshop is empty", and the settlement should be able to
     * say which without side effects.
     */
    public static boolean starvedForFuel(ServerLevel level, Building building) {
        if (!Fuel.burns(building.type) || building.bounds == null) {
            return false;
        }
        if (ready(level, building) != null) {
            return false;
        }
        List<Container> containers = containersOf(level, building);
        if (containers.isEmpty()) {
            return false;
        }
        for (Recipe recipe : of(building.type)) {
            // Inputs there, room there, and yet ready() had no candidates:
            // the only gate left standing is fuel.
            if (count(containers, recipe) >= recipe.inputCount()
                && hasRoomFor(containers, recipe)
                && !hasFuelFor(containers, building.type, recipe)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Runs one recipe: takes the inputs out of the building's chests and puts
     * the output back.
     *
     * <p>Room is checked BEFORE anything is removed, so the ordinary path
     * cannot strand a worker's inputs. The drop at the end is the belt and
     * braces for the impossible case — a container changing under us between
     * the check and the write — and it drops rather than voids, because INV-3
     * says items are conserved and a race is not an excuse.
     *
     * <p><b>Fuel burns in the same transaction.</b> At a burning building a
     * fuel-gated recipe (see {@link #fuelGated}) also removes
     * {@link Fuel#perBatch} fuel here, atomically with the inputs: refuse
     * up front if the fuel is not there, and if a race empties the fuel
     * slots between the check and the take, EVERYTHING already removed —
     * fuel and inputs alike — goes back exactly as it was. Only a batch
     * that fully completes burns anything. The burned fuel is then simply
     * gone: THE ONE SANCTIONED ITEM SINK (see the class doc's INV-3 note) —
     * it converted to finished goods, the way a vanilla furnace's coal does.
     *
     * @return whether the recipe actually ran
     */
    public static boolean run(ServerLevel level, Building building, Recipe recipe) {
        List<Container> containers = containersOf(level, building);
        boolean burns = fuelGated(building.type, recipe);
        int fuelNeeded = burns ? Fuel.perBatch(building.type) : 0;
        if (containers.isEmpty()
            || count(containers, recipe) < recipe.inputCount()
            || !hasRoomFor(containers, recipe)
            || (burns && countFuel(containers) < fuelNeeded)) {
            return false;
        }
        int taken = take(containers, recipe, recipe.inputCount());
        if (taken < recipe.inputCount()) {
            // Could not get everything after all: give back what we took and
            // leave the world exactly as we found it.
            giveBack(level, building, containers, recipe, taken);
            return false;
        }
        if (burns) {
            // Exact stacks recorded, because fuel is a KIND (charcoal, coal,
            // any log) and a give-back must return the very items it took,
            // not a normalised substitute.
            List<ItemStack> fuelTaken = takeFuel(containers, fuelNeeded);
            int fuelGot = 0;
            for (ItemStack stack : fuelTaken) {
                fuelGot += stack.getCount();
            }
            if (fuelGot < fuelNeeded) {
                // The race path: the fire went out under us. Undo the whole
                // transaction — fuel first, then the inputs — and refuse.
                for (ItemStack stack : fuelTaken) {
                    ItemStack left = insert(containers, stack);
                    if (!left.isEmpty()) {
                        Block.popResource(level, building.anchor, left);
                    }
                }
                giveBack(level, building, containers, recipe, taken);
                return false;
            }
            // The batch is now certain: the fuel just taken is DESTROYED,
            // deliberately — no re-insert, no drop. This line is the one
            // sanctioned item sink (INV-3 note in the class doc).
        }
        ItemStack output = new ItemStack(recipe.output(), recipe.outputCount());
        ItemStack left = insert(containers, output);
        if (!left.isEmpty()) {
            Block.popResource(level, building.anchor, left);
        }
        return true;
    }

    // ------------------------------------------------------------ helpers ---

    /**
     * The cold-start exemption: the one recipe at a burning building that
     * the fuel gate never touches. Making fuel cannot require fuel — from
     * any all-chests-cold state the settlement would deadlock permanently,
     * with every forge waiting for the charcoal none of them may make. The
     * physical story holds too: the log chars in its own fire.
     */
    private static final String FUEL_EXEMPT_RECIPE = "charcoal";

    /**
     * Whether this recipe, at this kind of building, must burn fuel to run.
     * Only the four burning trades ({@link Fuel#burns}), and never the
     * {@link #FUEL_EXEMPT_RECIPE} cold-start recipe.
     */
    private static boolean fuelGated(BuildingType type, Recipe recipe) {
        return Fuel.burns(type) && !FUEL_EXEMPT_RECIPE.equals(recipe.id());
    }

    /** The fuel half of "satisfiable": trivially true for anything not
     *  fuel-gated. (No current gated recipe's INPUT is itself a fuel item —
     *  the only overlap, logs into charcoal, is the exempt recipe — so
     *  counting inputs and fuel independently cannot double-promise one
     *  stack; if a future recipe overlaps, run()'s race path still gives
     *  everything back rather than half-running.) */
    private static boolean hasFuelFor(List<Container> containers,
                                      BuildingType type, Recipe recipe) {
        return !fuelGated(type, recipe)
            || countFuel(containers) >= Fuel.perBatch(type);
    }

    /** How much fuel of any kind the building's chests hold. */
    private static int countFuel(List<Container> containers) {
        int total = 0;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (Fuel.isFuel(stack)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    /**
     * Removes up to {@code wanted} fuel items and returns EXACTLY what was
     * taken, stack by stack, so the race path in {@link #run} can put back
     * the very items it removed — fuel is a kind, not one item, and a
     * give-back that swapped spruce logs for oak would violate chest truth
     * in spirit even while conserving the count.
     */
    private static List<ItemStack> takeFuel(List<Container> containers, int wanted) {
        List<ItemStack> taken = new ArrayList<>();
        int got = 0;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize() && got < wanted; slot++) {
                ItemStack stack = container.getItem(slot);
                if (!Fuel.isFuel(stack)) {
                    continue;
                }
                int move = Math.min(wanted - got, stack.getCount());
                taken.add(stack.copyWithCount(move));
                container.removeItem(slot, move);
                got += move;
            }
        }
        return taken;
    }

    private static List<Container> containersOf(ServerLevel level, Building building) {
        List<Container> found = new ArrayList<>();
        for (BlockPos pos : WarehouseIndex.containers(level, building)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container container) {
                found.add(container);
            }
        }
        return found;
    }

    /** How many of one item the building's chests hold — the "need" signal. */
    private static int countItem(List<Container> containers, Item item) {
        int total = 0;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && stack.is(item)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private static int count(List<Container> containers, Recipe recipe) {
        int total = 0;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && recipe.input().test(stack)) {
                    total += stack.getCount();
                }
            }
        }
        return total;
    }

    private static boolean hasRoomFor(List<Container> containers, Recipe recipe) {
        int needed = recipe.outputCount();
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) {
                    return true;
                }
                if (stack.is(recipe.output())
                    && stack.getCount() < stack.getMaxStackSize()) {
                    needed -= stack.getMaxStackSize() - stack.getCount();
                    if (needed <= 0) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Removes up to {@code wanted} matching items; returns how many it got. */
    private static int take(List<Container> containers, Recipe recipe, int wanted) {
        int got = 0;
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize() && got < wanted; slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty() || !recipe.input().test(stack)) {
                    continue;
                }
                int move = Math.min(wanted - got, stack.getCount());
                container.removeItem(slot, move);
                got += move;
            }
        }
        return got;
    }

    /** Puts back what a half-finished withdrawal took. */
    private static void giveBack(ServerLevel level, Building building,
                                 List<Container> containers, Recipe recipe, int amount) {
        // The ingredient may match several items; return the first it
        // accepts. For every single-item recipe that IS the item taken; for
        // a tag recipe (charcoal's LOGS) a race-path give-back may normalise
        // wood species to the tag's first member — count-conserving, and
        // reachable only on the impossible-in-practice race. (Fuel give-back
        // in run() is exact by contrast, because takeFuel records stacks.)
        ItemStack[] accepted = recipe.input().getItems();
        if (accepted.length == 0 || amount <= 0) {
            return;
        }
        ItemStack back = accepted[0].copyWithCount(amount);
        ItemStack left = insert(containers, back);
        if (!left.isEmpty()) {
            Block.popResource(level, building.anchor, left);
        }
    }

    /** Inserts what it can; returns the remainder. */
    private static ItemStack insert(List<Container> containers, ItemStack stack) {
        ItemStack remaining = stack.copy();
        for (Container container : containers) {
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (remaining.isEmpty()) {
                    return ItemStack.EMPTY;
                }
                ItemStack in = container.getItem(slot);
                if (in.isEmpty()) {
                    container.setItem(slot, remaining.copy());
                    return ItemStack.EMPTY;
                }
                if (ItemStack.isSameItemSameComponents(in, remaining)
                    && in.getCount() < in.getMaxStackSize()) {
                    int move = Math.min(remaining.getCount(),
                        in.getMaxStackSize() - in.getCount());
                    in.grow(move);
                    container.setChanged();
                    remaining.shrink(move);
                }
            }
        }
        return remaining;
    }

    private Production() {
    }
}
