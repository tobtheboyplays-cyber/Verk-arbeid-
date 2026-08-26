package com.hearthstead.logistics;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.ItemStack;

/**
 * What a thing costs to carry — the one physical fact that makes village
 * layout matter.
 *
 * <h2>Why weight exists</h2>
 *
 * <p>The owner's brief for this system (2026-08-26): <i>"det skal være gøy å
 * holde på for å optimize logistikk. Putte ting som er tungt nærme warehouse
 * og lignende."</i> Optimizing a layout is only a game when a bad layout
 * genuinely costs something — and until this class, it did not. A courier's
 * speed penalty scaled with how FULL her bag was
 * ({@code SettlerEntity#carryFraction}), not with what was in it, so eight
 * iron ingots and eight feathers cost exactly the same walk. With every trip
 * priced identically, no arrangement of buildings could ever beat any other,
 * and "put the mason near the warehouse" was flavour text.
 *
 * <p>A bag therefore gets a WEIGHT budget as well as its eight slots, and
 * whichever runs out first ends the load. A stone chain then moves a
 * fraction of what a grain chain moves per trip, so distance stops being a
 * linear cost and starts multiplying: trips × weight × walk. That
 * multiplication is the whole optimization game, and it falls out of one
 * table.
 *
 * <h2>What this deliberately does NOT do</h2>
 *
 * <p>It does not touch item identity, stack sizes, counts, or conservation.
 * Chest truth is untouched: the same items exist, are carried, and arrive.
 * Weight changes only <i>how many fit in one walk</i> — the smallest change
 * that produces the design, which is why it was chosen over cart
 * inventories, per-item stamina drain, or any other scheme that would have
 * needed the whole economy re-proved from scratch.
 *
 * <h2>Classes, not a number per item</h2>
 *
 * <p>Four classes, resolved by tag first and exact id second, so an item
 * nobody listed still gets a sane answer instead of a lookup miss. The
 * classes are coarse on purpose: a player has to hold the model in their
 * head — <i>ore is heavy, grain is light</i> — without consulting a table,
 * or the optimization game turns into bookkeeping.
 *
 * <p>Frozen contract, in the sense {@link com.hearthstead.building.Fuel} is:
 * callers ask this class and never keep their own copy of the numbers.
 */
public final class Weight {

    private Weight() {
    }

    /** Seeds, thread, feathers — these loads are limited by slots, not mass. */
    public static final int LIGHT = 1;
    /** Bread, planks, leather, tools — the default for worked goods. */
    public static final int ORDINARY = 2;
    /** Ingots, bricks, charcoal, logs — dense, and why a mill sits near a mine. */
    public static final int HEAVY = 4;
    /** Stone, ore, sand, gravel — raw mass, the reason to build the road. */
    public static final int DEAD_WEIGHT = 6;

    /**
     * A courier's carrying budget, in the units above.
     *
     * <p>Chosen against the bag that already exists: {@code
     * SettlerEntity.BAG_SIZE} is 8 slots, so a full bag of ORDINARY goods
     * (8 × 2 = 16) spends exactly this budget and <b>nothing changes for the
     * median load</b> — every chain balanced before today keeps its settled
     * throughput. LIGHT cargo stays slot-bound (8 × 1 = 8, well under),
     * while HEAVY fills the budget at 4 slots and DEAD_WEIGHT at under 3.
     * The table therefore bites precisely where the design wants it to — on
     * stone and ore — and leaves bread alone.
     */
    public static final int BAG_BUDGET = 16;

    /** What one unit of {@code stack}'s item costs to carry. */
    public static int of(ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        // Tags first: they cover the vanilla families and any modded item
        // that declares itself one of them, which keeps the exact-id list
        // below short enough to actually read.
        if (stack.is(ItemTags.LOGS) || stack.is(ItemTags.COALS)) {
            return HEAVY;
        }
        if (stack.is(ItemTags.STONE_CRAFTING_MATERIALS)
            || stack.is(ItemTags.STONE_BRICKS)
            || stack.is(ItemTags.SAND)
            || stack.is(ItemTags.TERRACOTTA)) {
            return DEAD_WEIGHT;
        }
        if (stack.is(ItemTags.PLANKS) || stack.is(ItemTags.WOOL)
            || stack.is(ItemTags.ARROWS)) {
            return ORDINARY;
        }
        if (stack.is(ItemTags.SAPLINGS) || stack.is(ItemTags.LEAVES)
            || stack.is(ItemTags.SMALL_FLOWERS)) {
            return LIGHT;
        }

        String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath();
        return switch (id) {
            // Raw mass.
            case "cobblestone", "stone", "deepslate", "cobbled_deepslate",
                 "gravel", "clay", "clay_ball", "raw_iron", "raw_gold",
                 "raw_copper", "iron_ore", "gold_ore", "copper_ore",
                 "coal_ore", "deepslate_iron_ore", "deepslate_gold_ore",
                 "deepslate_copper_ore", "deepslate_coal_ore" -> DEAD_WEIGHT;
            // Dense worked goods.
            case "iron_ingot", "gold_ingot", "copper_ingot", "brick",
                 "bricks", "iron_block", "anvil", "cauldron",
                 "iron_nugget", "iron_bloom" -> HEAVY;
            // The light end: a courier carrying these is slot-bound.
            case "wheat_seeds", "beetroot_seeds", "melon_seeds",
                 "pumpkin_seeds", "string", "feather", "paper", "sugar_cane",
                 "egg", "glowstone_dust", "gunpowder", "sugar" -> LIGHT;
            default -> ORDINARY;
        };
    }

    /** What {@code count} of {@code stack}'s item costs to carry. */
    public static int of(ItemStack stack, int count) {
        return of(stack) * count;
    }

    /**
     * How many of {@code stack}'s item fit in one load, given both limits at
     * once: the weight budget and the slots.
     *
     * <p>Never returns less than one. A courier who could not lift a single
     * unit of something would strand it in a warehouse forever, and stranded
     * goods is exactly the failure this project has already paid for once
     * (KF-023): the restock route declined a trip on a technicality and the
     * remaining stock sat there for the rest of the game.
     */
    public static int perLoad(ItemStack stack, int slots, int maxStackSize) {
        int unit = of(stack);
        if (unit <= 0) {
            return 0;
        }
        int bySlots = slots * maxStackSize;
        int byWeight = BAG_BUDGET / unit;
        return Math.max(1, Math.min(bySlots, byWeight));
    }
}
