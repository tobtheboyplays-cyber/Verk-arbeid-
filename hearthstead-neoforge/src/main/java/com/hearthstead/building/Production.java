package com.hearthstead.building;

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
 * The table below is small today because the six intermediate goods of D-008
 * do not exist yet; what matters is that adding a building is one entry, not
 * one class.
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
        // Chain A, food. Three wheat to a loaf: the same ratio vanilla uses,
        // so a player already knows the exchange rate. Flour from the mill
        // will improve it rather than replace it (D-007).
        put(BuildingType.BAKERY,
            new Recipe("bread", Ingredient.of(Items.WHEAT), 3, Items.BREAD, 1, 160));

        // Chain A again: the butcher makes what was caught keep longer. Cured
        // meat is the D-008 item this should eventually produce; until that
        // item exists, cooking is the honest stand-in and is still useful on
        // its own.
        put(BuildingType.BUTCHER,
            new Recipe("beef", Ingredient.of(Items.BEEF), 1, Items.COOKED_BEEF, 1, 120),
            new Recipe("pork", Ingredient.of(Items.PORKCHOP), 1, Items.COOKED_PORKCHOP, 1, 120),
            new Recipe("mutton", Ingredient.of(Items.MUTTON), 1, Items.COOKED_MUTTON, 1, 120),
            new Recipe("chicken", Ingredient.of(Items.CHICKEN), 1, Items.COOKED_CHICKEN, 1, 120));

        // Chain B, tools: the half of it that is unambiguous today. Ore to
        // ingot needs no judgement call about which plank a log becomes.
        put(BuildingType.SMELTER,
            new Recipe("iron", Ingredient.of(Items.RAW_IRON), 1, Items.IRON_INGOT, 1, 200),
            new Recipe("copper", Ingredient.of(Items.RAW_COPPER), 1, Items.COPPER_INGOT, 1, 200),
            new Recipe("gold", Ingredient.of(Items.RAW_GOLD), 1, Items.GOLD_INGOT, 1, 240));
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
