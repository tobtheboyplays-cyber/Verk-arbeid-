package com.hearthstead.settlement.builder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Owner, 26 Sep: "Builder lager det selv". Early towns have no Carpenter or
 * Smelter (they unlock in ring 3), so the Builder makes the simple things
 * himself at his hut, from what the village keeps there, with the vanilla
 * crafting ratios -- exact and conserved, spare output stays in the hut:
 * <ul>
 * <li>wood shapes of any plank species the village has: stairs (6 planks ->
 *     4), slab (3 -> 6), fence (4 planks + 2 sticks -> 3), fence gate (2 planks
 *     + 4 sticks -> 1), door (6 -> 3), trapdoor (6 -> 2), pressure plate
 *     (2 -> 1), sign (6 planks + 1 stick -> 3);</li>
 * <li>sticks (2 planks -> 4), planks from logs of the same wood (1 -> 4);</li>
 * <li>glass panes (6 glass -> 16), and glass from sand in a furnace at the
 *     hut, slowly, with the village's fuel (1 coal or charcoal smelts 8; a
 *     log or planks fire 1).</li>
 * </ul>
 * Order of preference is the caller's: a staffed workshop's order first,
 * then this, then asking the player. What is missing is then the INPUT
 * ("Needs sand"), never the finished block.
 */
public final class BuilderCraft {

    /** One recipe application. */
    public record Recipe(Item output, int count, List<BuilderMaterials.ItemCount> inputs, boolean smelt) {
    }

    private BuilderCraft() {
    }

    /** The recipe the Builder uses for {@code output}, or null when he cannot make it. */
    @Nullable
    public static Recipe recipeFor(Item output) {
        if (output == Items.STICK) {
            return null; // made on demand from planks inside craft()
        }
        if (output == Items.GLASS_PANE) {
            return new Recipe(Items.GLASS_PANE, 16, List.of(new BuilderMaterials.ItemCount(Items.GLASS, 6)), false);
        }
        if (output == Items.GLASS) {
            return new Recipe(Items.GLASS, 1, List.of(new BuilderMaterials.ItemCount(Items.SAND, 1)), true);
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(output);
        String path = id.getPath();
        String[][] shapes = {
            {"_stairs", "4", "6", "0"}, {"_slab", "6", "3", "0"}, {"_fence_gate", "1", "2", "4"},
            {"_fence", "3", "4", "2"}, {"_door", "3", "6", "0"}, {"_trapdoor", "2", "6", "0"},
            {"_pressure_plate", "1", "2", "0"}, {"_sign", "3", "6", "1"}};
        for (String[] shape : shapes) {
            if (!path.endsWith(shape[0]) || path.endsWith("_hanging_sign") || path.endsWith("_wall_sign")) {
                continue;
            }
            String species = path.substring(0, path.length() - shape[0].length());
            Item planks = item(id.getNamespace(), species + "_planks");
            if (planks == null || !isPlanks(planks)) {
                return null; // stone stairs, brick slabs, iron doors: not a woodworker's job
            }
            List<BuilderMaterials.ItemCount> in = Integer.parseInt(shape[3]) > 0
                ? List.of(new BuilderMaterials.ItemCount(planks, Integer.parseInt(shape[2])),
                    new BuilderMaterials.ItemCount(Items.STICK, Integer.parseInt(shape[3])))
                : List.of(new BuilderMaterials.ItemCount(planks, Integer.parseInt(shape[2])));
            return new Recipe(output, Integer.parseInt(shape[1]), in, false);
        }
        if (isPlanks(output)) {
            String species = path.substring(0, path.length() - "_planks".length());
            Item log = item(id.getNamespace(), species + "_log");
            if (log == null) {
                log = item(id.getNamespace(), species + "_stem");
            }
            if (log != null) {
                return new Recipe(output, 4, List.of(new BuilderMaterials.ItemCount(log, 1)), false);
            }
        }
        return null;
    }

    /** Planks by id (works before item tags are bound, e.g. in unit tests). */
    static boolean isPlanks(Item item) {
        return BuiltInRegistries.ITEM.getKey(item).getPath().endsWith("_planks");
    }

    @Nullable
    private static Item item(String namespace, String path) {
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath(namespace, path);
        return BuiltInRegistries.ITEM.containsKey(rl) ? BuiltInRegistries.ITEM.get(rl) : null;
    }

    /**
     * The first input the containers lack for one application of the recipe
     * of {@code output} (going down to planks from logs and sticks from
     * planks), or null when one application is possible. Used to say what
     * is really missing ("Needs sand") and to fetch it.
     */
    @Nullable
    public static Item missingInput(List<Container> from, Item output) {
        Recipe recipe = recipeFor(output);
        if (recipe == null) {
            return output;
        }
        for (BuilderMaterials.ItemCount in : recipe.inputs()) {
            if (available(from, in.item()) < in.count()) {
                return in.item() == Items.STICK ? firstPlanks(from, recipe) : in.item();
            }
        }
        if (recipe.smelt() && fuelUnits(from) < 1) {
            return Items.CHARCOAL;
        }
        return null;
    }

    /**
     * Makes up to {@code wanted} of {@code output} from {@code from} (the
     * sack first, then the hut), putting everything made into {@code into}
     * (the hut). Returns how many were made; 0 when an input is missing.
     * Glass is made at most {@code smeltCap} per call (the furnace is slow).
     */
    public static int craft(List<Container> from, List<Container> into, Item output, int wanted, int smeltCap) {
        Recipe recipe = recipeFor(output);
        if (recipe == null || wanted <= 0) {
            return 0;
        }
        int made = 0;
        if (recipe.smelt()) {
            // One furnace load: up to smeltCap glass, 1 coal/charcoal per 8
            // (rounded up) or one log / plank each.
            int n = Math.min(Math.min(wanted, smeltCap), available(from, Items.SAND));
            int coal = available(from, Items.COAL) + available(from, Items.CHARCOAL);
            int woodFuel = countTag(from, ItemTags.LOGS) + countTag(from, ItemTags.PLANKS);
            n = Math.min(n, coal * 8 + woodFuel);
            if (n <= 0 || !BuilderStock.fits(into, Items.GLASS, n)) {
                return 0;
            }
            take(from, Items.SAND, n);
            int coalUsed = Math.min(coal, (n + 7) / 8);
            int covered = Math.min(n, coalUsed * 8);
            for (int k = 0; k < coalUsed; k++) {
                take(from, available(from, Items.CHARCOAL) > 0 ? Items.CHARCOAL : Items.COAL, 1);
            }
            for (int k = covered; k < n; k++) {
                if (countTag(from, ItemTags.LOGS) > 0) {
                    takeTag(from, ItemTags.LOGS);
                } else {
                    takeTag(from, ItemTags.PLANKS);
                }
            }
            BuilderStock.insertAll(into, new ItemStack(Items.GLASS, n));
            return n;
        }
        int rounds = 0;
        while (made < wanted && rounds++ < 64) {
            if (!ensureInputs(from, into, recipe)) {
                break;
            }
            if (!BuilderStock.fits(into, recipe.output(), recipe.count())) {
                break;
            }
            for (BuilderMaterials.ItemCount in : recipe.inputs()) {
                take(from, in.item(), in.count());
            }
            BuilderStock.insertAll(into, new ItemStack(recipe.output(), recipe.count()));
            made += recipe.count();
        }
        return made;
    }

    /** Sticks from planks and planks from logs, made first when a recipe needs them. */
    private static boolean ensureInputs(List<Container> from, List<Container> into, Recipe recipe) {
        for (BuilderMaterials.ItemCount in : recipe.inputs()) {
            int have = available(from, in.item());
            if (have >= in.count()) {
                continue;
            }
            if (in.item() == Items.STICK) {
                Item planks = firstPlanks(from, recipe);
                while (available(from, Items.STICK) < in.count()) {
                    if (planks == null || available(from, planks) < 2 + plankReserve(recipe, planks)
                        || !BuilderStock.fits(into, Items.STICK, 4)) {
                        return false;
                    }
                    take(from, planks, 2);
                    BuilderStock.insertAll(into, new ItemStack(Items.STICK, 4));
                }
                continue;
            }
            Recipe sub = recipeFor(in.item());
            if (sub == null || sub.smelt()) {
                return false;
            }
            while (available(from, in.item()) < in.count()) {
                boolean ok = true;
                for (BuilderMaterials.ItemCount s2 : sub.inputs()) {
                    if (available(from, s2.item()) < s2.count()) {
                        ok = false;
                    }
                }
                if (!ok || !BuilderStock.fits(into, sub.output(), sub.count())) {
                    return false;
                }
                for (BuilderMaterials.ItemCount s2 : sub.inputs()) {
                    take(from, s2.item(), s2.count());
                }
                BuilderStock.insertAll(into, new ItemStack(sub.output(), sub.count()));
            }
        }
        return !recipe.smelt() || fuelUnits(from) >= 1;
    }

    private static int plankReserve(Recipe recipe, Item planks) {
        for (BuilderMaterials.ItemCount in : recipe.inputs()) {
            if (in.item() == planks) {
                return in.count();
            }
        }
        return 0;
    }

    @Nullable
    private static Item firstPlanks(List<Container> from, Recipe recipe) {
        for (BuilderMaterials.ItemCount in : recipe.inputs()) {
            if (isPlanks(in.item())) {
                return in.item();
            }
        }
        for (Container c : from) {
            for (int slot = 0; slot < c.getContainerSize(); slot++) {
                ItemStack s = c.getItem(slot);
                if (!s.isEmpty() && isPlanks(s.getItem())) {
                    return s.getItem();
                }
            }
        }
        return Items.OAK_PLANKS;
    }

    static int available(List<Container> from, Item item) {
        return BuilderStock.count(from, item);
    }

    private static void take(List<Container> from, Item item, int count) {
        int left = count;
        for (Container c : from) {
            for (int slot = 0; slot < c.getContainerSize() && left > 0; slot++) {
                ItemStack s = c.getItem(slot);
                if (!s.isEmpty() && s.is(item) && BuilderStock.plain(s)) {
                    int t = Math.min(left, s.getCount());
                    s.shrink(t);
                    if (s.isEmpty()) {
                        c.setItem(slot, ItemStack.EMPTY);
                    }
                    c.setChanged();
                    left -= t;
                }
            }
        }
    }

    /** Smelting fuel in whole glass: coal/charcoal fire 8, a log or planks 1. */
    static int fuelUnits(List<Container> from) {
        return 8 * (available(from, Items.COAL) + available(from, Items.CHARCOAL))
            + countTag(from, ItemTags.LOGS) + countTag(from, ItemTags.PLANKS);
    }

    private static int countTag(List<Container> from, net.minecraft.tags.TagKey<Item> tag) {
        int n = 0;
        for (Container c : from) {
            for (int slot = 0; slot < c.getContainerSize(); slot++) {
                ItemStack s = c.getItem(slot);
                if (!s.isEmpty() && s.is(tag) && BuilderStock.plain(s)) {
                    n += s.getCount();
                }
            }
        }
        return n;
    }

    private static void takeTag(List<Container> from, net.minecraft.tags.TagKey<Item> tag) {
        for (Container c : from) {
            for (int slot = 0; slot < c.getContainerSize(); slot++) {
                ItemStack s = c.getItem(slot);
                if (!s.isEmpty() && s.is(tag) && BuilderStock.plain(s)) {
                    s.shrink(1);
                    if (s.isEmpty()) {
                        c.setItem(slot, ItemStack.EMPTY);
                    }
                    c.setChanged();
                    return;
                }
            }
        }
    }
}
