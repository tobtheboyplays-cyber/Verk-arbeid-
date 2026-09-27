package com.hearthstead.settlement.builder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges {@link MaterialRules} (pure strings) to real blocks and items, and
 * sums what a job still needs.
 */
public final class BuilderMaterials {

    /**
     * What the Builder's drafting of a plaque's plan costs: a flat paper +
     * feather + plank for every type. Hand-crafted plans use each type's own
     * recipe (the Builder's Hut plan is paper + feather + crafting table).
     */
    public static final List<Item> PLAN_COST = List.of(Items.PAPER, Items.FEATHER, Items.OAK_PLANKS);

    private BuilderMaterials() {
    }

    /**
     * The main item and count that places this state, or null when free.
     * Use {@link #costsOf} for the full bill (a potted flower is two items).
     */
    public static ItemCount costOf(BlockState state) {
        List<ItemCount> all = costsOf(state);
        return all.isEmpty() ? null : all.get(0);
    }

    /**
     * Every item this state costs. An entry with {@code item == AIR} means a
     * block no item can place (a technical block): it cannot be charged, so
     * it cannot be built either -- the planner skips it.
     */
    public static List<ItemCount> costsOf(BlockState state) {
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        Map<String, String> props = new HashMap<>();
        for (Property<?> property : state.getProperties()) {
            props.put(property.getName(), valueName(state, property));
        }
        List<MaterialRules.Cost> costs = MaterialRules.costsOf(id, props);
        List<ItemCount> out = new java.util.ArrayList<>(costs.size());
        for (MaterialRules.Cost cost : costs) {
            Item item = item(cost.itemId());
            out.add(item == Items.AIR ? new ItemCount(Items.AIR, 0) : new ItemCount(item, cost.count()));
        }
        return out;
    }

    /** Whether every item of the state is obtainable (else it cannot be built honestly). */
    public static boolean buildable(BlockState state) {
        for (ItemCount c : costsOf(state)) {
            if (!c.buildable()) {
                return false;
            }
        }
        return true;
    }

    /** Property name -> value name of a state (for the pure rules). */
    public static Map<String, String> props(BlockState state) {
        Map<String, String> props = new HashMap<>();
        for (Property<?> property : state.getProperties()) {
            props.put(property.getName(), valueName(state, property));
        }
        return props;
    }

    /** {@link MaterialRules#compare} on real states. */
    public static MaterialRules.Match compare(BlockState planned, BlockState present) {
        return MaterialRules.compare(BuiltInRegistries.BLOCK.getKey(planned.getBlock()).toString(), props(planned),
            BuiltInRegistries.BLOCK.getKey(present.getBlock()).toString(), props(present));
    }

    private static <T extends Comparable<T>> String valueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }

    public static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
    }

    /** An item and a count; {@code item == AIR} means "not buildable". */
    public record ItemCount(Item item, int count) {
        public boolean buildable() {
            return item != Items.AIR && count > 0;
        }
    }

    /** Water buckets a set of planned water cells costs: two per connected body (one for a single cell). */
    public static int waterBuckets(java.util.Collection<net.minecraft.core.BlockPos> cells) {
        int buckets = 0;
        for (List<net.minecraft.core.BlockPos> body : waterBodies(cells)) {
            buckets += Math.min(2, body.size());
        }
        return buckets;
    }

    /** The planned water cells split into 6-connected bodies, each sorted bottom-up. */
    public static List<List<net.minecraft.core.BlockPos>> waterBodies(java.util.Collection<net.minecraft.core.BlockPos> cells) {
        java.util.Set<net.minecraft.core.BlockPos> left = new java.util.HashSet<>(cells);
        List<List<net.minecraft.core.BlockPos>> bodies = new java.util.ArrayList<>();
        List<net.minecraft.core.BlockPos> order = new java.util.ArrayList<>(cells);
        order.sort(java.util.Comparator.<net.minecraft.core.BlockPos>comparingInt(net.minecraft.core.BlockPos::getY)
            .thenComparingInt(net.minecraft.core.BlockPos::getZ).thenComparingInt(net.minecraft.core.BlockPos::getX));
        for (net.minecraft.core.BlockPos start : order) {
            if (!left.remove(start)) {
                continue;
            }
            List<net.minecraft.core.BlockPos> body = new java.util.ArrayList<>();
            java.util.ArrayDeque<net.minecraft.core.BlockPos> queue = new java.util.ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                net.minecraft.core.BlockPos at = queue.poll();
                body.add(at);
                for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
                    net.minecraft.core.BlockPos next = at.relative(d);
                    if (left.remove(next)) {
                        queue.add(next);
                    }
                }
            }
            body.sort(java.util.Comparator.<net.minecraft.core.BlockPos>comparingInt(net.minecraft.core.BlockPos::getY)
                .thenComparingInt(net.minecraft.core.BlockPos::getZ).thenComparingInt(net.minecraft.core.BlockPos::getX));
            bodies.add(body);
        }
        return bodies;
    }

    /** A still water source block (what a basin cell is planned as). */
    public static boolean waterSource(BlockState state) {
        return state.is(Blocks.WATER) && state.getFluidState().isSource();
    }

    /** Every item one step consumes (empty for clears, drains and free blocks). */
    public static List<ItemCount> costsOfStep(BuildJob job, int i) {
        byte flags = job.flags(i);
        if ((flags & BuildJob.F_POUR) != 0) {
            return (flags & BuildJob.F_POUR_PAID) != 0
                ? List.of(new ItemCount(Items.WATER_BUCKET, 1)) : List.of();
        }
        if ((flags & BuildJob.F_FIT_PLAN) != 0) {
            return List.of(new ItemCount(Items.PAPER, 1), new ItemCount(Items.FEATHER, 1),
                new ItemCount(Items.OAK_PLANKS, 1));
        }
        if ((flags & (BuildJob.F_CLEAR | BuildJob.F_DRAIN)) != 0 || job.phase(i).removes()) {
            return List.of();
        }
        List<ItemCount> costs = costsOf(job.state(i));
        for (ItemCount c : costs) {
            if (!c.buildable()) {
                return List.of();
            }
        }
        return costs;
    }

    /**
     * What the job's unfinished steps still need, in build order (the order a
     * player reads the list in). Skipped and blocked steps still count: they
     * are only postponed.
     */
    public static Map<Item, Integer> remaining(BuildJob job) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (int i = job.cursor(); i < job.size(); i++) {
            if (job.isDone(i)) {
                continue;
            }
            for (ItemCount c : costsOfStep(job, i)) {
                out.merge(c.item(), c.count(), Integer::sum);
            }
        }
        return out;
    }

    /** The whole bill of the job (done or not), for the Sites list. */
    public static Map<Item, Integer> total(BuildJob job) {
        Map<Item, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < job.size(); i++) {
            for (ItemCount c : costsOfStep(job, i)) {
                out.merge(c.item(), c.count(), Integer::sum);
            }
        }
        return out;
    }
}
