package com.hearthstead.building;

import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.warehouse.WarehouseIndex;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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
 * checked first — it is dropped into the world rather than voided. There is no
 * path through this class that reduces the number of items in the world.
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
        // finish (see SMITHY below). 3 raw iron -> 4 bloom -> (smithy) 4
        // ingot is the yield side of the multiplier; the tick side is on the
        // smithy recipe that spends the bloom.
        put(BuildingType.SMELTER,
            new Recipe("iron_bloom", Ingredient.of(Items.RAW_IRON), 3, ModItems.IRON_BLOOM.get(), 4, 200),
            new Recipe("iron", Ingredient.of(Items.RAW_IRON), 1, Items.IRON_INGOT, 1, 200),
            new Recipe("copper", Ingredient.of(Items.RAW_COPPER), 1, Items.COPPER_INGOT, 1, 200),
            new Recipe("gold", Ingredient.of(Items.RAW_GOLD), 1, Items.GOLD_INGOT, 1, 240));

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
        // proper ingot -- the smelter<->smithy edge FLOWS.md names -- at half
        // the smelter's own ticks per ingot (100 vs 200). Its input
        // (IRON_BLOOM) never collides with the four tool recipes' IRON_INGOT,
        // so it is simply additional smithy work, not a competitor to them.
        put(BuildingType.SMITHY,
            new Recipe("bloom_ingot", Ingredient.of(ModItems.IRON_BLOOM.get()), 2, Items.IRON_INGOT, 2, 200),
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
    }

    private static void put(BuildingType type, Recipe... recipes) {
        RECIPES.put(type, List.of(recipes));
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
     * The first recipe this building could run right now: its inputs are in
     * the building's own chests and there is room for what comes out.
     *
     * <p>Deliberately a pure read — it changes nothing — so a work goal can
     * ask "is there anything to do?" every tick without side effects.
     */
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
        for (Recipe recipe : recipes) {
            if (count(containers, recipe) >= recipe.inputCount()
                && hasRoomFor(containers, recipe)) {
                return recipe;
            }
        }
        return null;
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
     * @return whether the recipe actually ran
     */
    public static boolean run(ServerLevel level, Building building, Recipe recipe) {
        List<Container> containers = containersOf(level, building);
        if (containers.isEmpty()
            || count(containers, recipe) < recipe.inputCount()
            || !hasRoomFor(containers, recipe)) {
            return false;
        }
        int taken = take(containers, recipe, recipe.inputCount());
        if (taken < recipe.inputCount()) {
            // Could not get everything after all: give back what we took and
            // leave the world exactly as we found it.
            giveBack(level, building, containers, recipe, taken);
            return false;
        }
        ItemStack output = new ItemStack(recipe.output(), recipe.outputCount());
        ItemStack left = insert(containers, output);
        if (!left.isEmpty()) {
            Block.popResource(level, building.anchor, left);
        }
        return true;
    }

    // ------------------------------------------------------------ helpers ---

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
        // The ingredient may match several items; return the first it accepts,
        // which for every recipe in the table above is the only one it accepts.
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
