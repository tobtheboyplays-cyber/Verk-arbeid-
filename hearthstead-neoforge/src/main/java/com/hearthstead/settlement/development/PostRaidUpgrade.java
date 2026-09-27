package com.hearthstead.settlement.development;

import com.hearthstead.registry.ModItems;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Small, paid, one-time settlement upgrades ("bonuses") that sit between the
 * jobs and houses of the Development tree.
 *
 * <p>These are deliberately kept outside {@link DevelopmentNode}: the node
 * enum drives the tree layout of the Development screen, and every new node
 * needs a position there. Upgrades persist by {@link #id} (never an ordinal)
 * in {@link DevelopmentState}, are paid through the same physical treasury
 * path as nodes (Coins plus real goods, all-or-nothing) and advance the same
 * revision, so a stale replay cannot pay twice.
 *
 * <p>Every upgrade names the Development node it hangs off ({@link #requires}),
 * an optional upgrade it chains from, and an optional lifetime milestone
 * ({@link #gate}) measured by the server. The first five entries are the
 * original post-raid upgrades; their ids, wire ids and Coin-only prices are
 * unchanged. Later entries are appended with new wire ids.
 *
 * <p>Every effect is read through {@link Development#hasUpgrade}; when an
 * upgrade is not owned, behaviour is exactly the pre-upgrade behaviour.
 */
public enum PostRaidUpgrade {
    /**
     * Courier Satchel: every Courier of the settlement carries
     * {@link #COURIER_SATCHEL_BONUS} more items per trip (8 to 12 by default).
     * Walking speed, bag slots and the one-owner rule for carried goods are
     * unchanged. Balance numbers are provisional.
     */
    COURIER_SATCHEL("courier_satchel", 0, Direction.LOGISTICS,
        DevelopmentNode.FIRST_RAID_AFTERMATH, null, 4),
    /**
     * Hand Cart: requires the Courier Satchel. Every Courier pulls a visible
     * cart that adds {@link #HAND_CART_PERCENT}% of the base trip budget on
     * top of the sack tier ({@link HaulGear}; 12 to 28 with the Satchel).
     * The cart is a presentation of the Courier's own bag: the goods never
     * leave that one container, so death, unload and reload behave exactly
     * like the sack (no second inventory that could duplicate or lose
     * items). It rolls faster on roads and slower on rough ground.
     */
    HAND_CART("hand_cart", 1, Direction.LOGISTICS,
        DevelopmentNode.FIRST_RAID_AFTERMATH, "courier_satchel", 8, null, 0,
        () -> List.of(plank(12), log(4), cost(Items.IRON_INGOT, 2))),
    /**
     * Worker Packs: Lumberers and Farmers carry the settlement's sack tier
     * too ({@link WorkerPacks}; at least the Satchel's +50%, 8 to 12 at base
     * Strength, up to +150% with the Frame Pack).
     */
    WORKER_PACKS("worker_packs", 2, Direction.LOGISTICS,
        DevelopmentNode.FIRST_RAID_AFTERMATH, null, 6),
    /**
     * Iron Arms Drill: a Guard from Veteran rank up is dressed one armour
     * tier earlier ({@link GuardArms}). Every piece is still withdrawn from
     * the settlement's own stores; nothing is conjured.
     */
    GUARD_ARMS_IRON("guard_arms_iron", 3, Direction.WATCH,
        DevelopmentNode.FIRST_RAID_AFTERMATH, null, 8),
    /**
     * Longbow Drill: unposted Archers loose at {@link #LONGBOW_RANGE_BONUS}
     * more blocks and draw {@link #LONGBOW_DRAW_TICKS_SAVED} ticks faster
     * ({@link ArcherDrill}). Tower Post range is unchanged.
     */
    ARCHER_LONGBOW_DRILL("archer_longbow_drill", 4, Direction.WATCH,
        DevelopmentNode.FIRST_RAID_AFTERMATH, null, 6),

    // ---- Appended bonuses between jobs and houses (wire ids 5+). ----

    /**
     * Warm Hearth (Hearth &amp; Household, ring 1): settlers lose
     * {@link #WARM_HEARTH_PERCENT}% less hunger at night.
     */
    WARM_HEARTH("warm_hearth", 5, Direction.HEARTH, DevelopmentNode.HOME, null, 3,
        DevelopmentObjective.HOUSED_SETTLERS, 2,
        () -> List.of(cost(Items.COBBLESTONE, 16), log(8))),
    /**
     * Sturdy Beds (Hearth &amp; Household, ring 2): a settler with a claimed
     * bed gains {@link #STURDY_BEDS_MORALE} morale target.
     */
    // Cottages follow Banner Fires, not the Tavern (tech tree Option 2).
    STURDY_BEDS("sturdy_beds", 6, Direction.HEARTH, DevelopmentNode.HOME,
        "warm_hearth", 4, DevelopmentObjective.HOUSED_SETTLERS, 3,
        () -> List.of(wool(3), plank(12))),
    /**
     * Feather Quilts (Hearth &amp; Household, outer ring): sleeping settlers
     * regain {@link #FEATHER_QUILTS_PERCENT}% more energy.
     */
    FEATHER_QUILTS("feather_quilts", 7, Direction.HEARTH,
        DevelopmentNode.FIRST_RAID_AFTERMATH, "sturdy_beds", 6, null, 0,
        () -> List.of(cost(Items.FEATHER, 8), wool(4))),
    /**
     * Sharpened Axes (Craft &amp; Trade, ring 1): Lumberers fell a tree in
     * {@link #SHARPENED_AXES_PERCENT}% less time.
     */
    SHARPENED_AXES("sharpened_axes", 8, Direction.CRAFT, DevelopmentNode.TIMBER_RIGHTS,
        null, 2, DevelopmentObjective.LUMBER_LOGS_STORED, 16,
        () -> List.of(cost(Items.IRON_INGOT, 2), cost(Items.COBBLESTONE, 8))),
    /**
     * Fisher's Nets (Craft &amp; Trade, ring 2): every Fisher cast finishes
     * {@link #FISHERS_NETS_PERCENT}% sooner.
     */
    FISHERS_NETS("fishers_nets", 9, Direction.CRAFT, DevelopmentNode.SHORE_PROVISIONS,
        null, 3, DevelopmentObjective.COURIER_DELIVERIES, 4,
        () -> List.of(cost(Items.STRING, 8), log(4))),
    /**
     * Stout Straps (Logistics, ring 1): Couriers carry
     * {@link #STOUT_STRAPS_BONUS} more items per trip (8 to 10); the Satchel
     * and Hand Cart stack on top.
     */
    STOUT_STRAPS("stout_straps", 10, Direction.LOGISTICS,
        DevelopmentNode.STORES_AND_ROADS, null, 2,
        DevelopmentObjective.COURIER_DELIVERIES, 6,
        () -> List.of(cost(Items.LEATHER, 4), cost(Items.STRING, 4))),
    /**
     * Guard Drill (Watch &amp; Defense, ring 1): Guards earn
     * {@link #GUARD_DRILL_PERCENT}% more combat experience.
     */
    GUARD_DRILL("guard_drill", 11, Direction.WATCH, DevelopmentNode.FIRST_WATCH,
        null, 3, DevelopmentObjective.GUARD_EQUIPMENT_DELIVERIES, 1,
        () -> List.of(log(8), cost(Items.LEATHER, 2))),

    // ---- Logistics: sack tiers, roads and couriers (wire ids 12+). ----

    /**
     * Leather Pack (sack tier 2): requires the Courier Satchel. Couriers
     * carry {@link #SACK_TIER_PERCENT}[2]% more than base (8 to 16), and
     * gatherers with Worker Packs follow the same tier.
     */
    LEATHER_PACK("leather_pack", 12, Direction.LOGISTICS,
        DevelopmentNode.FIRST_RAID_AFTERMATH, "courier_satchel", 6, null, 0,
        () -> List.of(cost(Items.LEATHER, 8), wool(4), cost(Items.STRING, 6))),
    /**
     * Frame Pack (sack tier 3): requires the Leather Pack. +150% of base
     * (8 to 20) for Couriers and packed gatherers.
     */
    FRAME_PACK("frame_pack", 13, Direction.LOGISTICS,
        DevelopmentNode.FIRST_RAID_AFTERMATH, "leather_pack", 10, null, 0,
        () -> List.of(cost(Items.LEATHER, 12), cost(Items.STRING, 8),
            cost(Items.STICK, 8), cost(Items.IRON_INGOT, 2))),
    /**
     * Paved Roads (Logistics, ring 1): every settler walks
     * {@link #PAVED_ROADS_PERCENT}% faster on road blocks (the
     * {@code hearthstead:roads} block tag: dirt path, gravel, stone bricks).
     */
    PAVED_ROADS("paved_roads", 14, Direction.LOGISTICS,
        DevelopmentNode.STORES_AND_ROADS, null, 4,
        DevelopmentObjective.COURIER_DELIVERIES, 8,
        () -> List.of(cost(Items.GRAVEL, 32), cost(Items.COBBLESTONE, 16))),
    /**
     * Swift Couriers (Logistics, outer ring): requires Stout Straps and the
     * First Raid Aftermath. Couriers walk {@link #SWIFT_COURIERS_PERCENT}%
     * faster everywhere.
     */
    SWIFT_COURIERS("swift_couriers", 15, Direction.LOGISTICS,
        DevelopmentNode.FIRST_RAID_AFTERMATH, "stout_straps", 4,
        DevelopmentObjective.COURIER_DELIVERIES, 12,
        () -> List.of(cost(Items.LEATHER, 6), cost(Items.STRING, 4))),

    // ---- Logistics: warehouse level recognition (wire ids 16+). ----
    // A warehouse's level comes from what its room holds (the plaque
    // checklist). L1-L2 are recognised with Warehouse & Courier alone; each
    // node below raises the highest recognised level by one. See
    // settlement/warehouse/WarehouseLevels for the capacity table.

    /**
     * Warehouse Racks: a warehouse whose room meets checklist L3 is
     * recognised as L3 (64 managed containers).
     */
    WAREHOUSE_RACKS("warehouse_racks", 16, Direction.LOGISTICS,
        DevelopmentNode.FIRST_RAID_AFTERMATH, null, 5, null, 0,
        () -> List.of(plank(24), cost(Items.IRON_INGOT, 4))),
    /**
     * Great Storehouse: requires Warehouse Racks. Recognises checklist L4
     * (128 managed containers).
     */
    GREAT_STOREHOUSE("great_storehouse", 17, Direction.LOGISTICS,
        DevelopmentNode.FIRST_RAID_AFTERMATH, "warehouse_racks", 12,
        DevelopmentObjective.COURIER_DELIVERIES, 24,
        () -> List.of(cost(Items.STONE_BRICKS, 32), cost(Items.IRON_INGOT, 8))),
    /**
     * Royal Storehouse: requires the Great Storehouse. Recognises checklist
     * L5 (256 managed containers).
     */
    ROYAL_STOREHOUSE("royal_storehouse", 18, Direction.LOGISTICS,
        DevelopmentNode.FIRST_RAID_AFTERMATH, "great_storehouse", 24,
        DevelopmentObjective.COURIER_DELIVERIES, 64,
        () -> List.of(cost(Items.STONE_BRICKS, 64), cost(Items.IRON_INGOT, 16),
            cost(Items.GOLD_INGOT, 4))),

    // ---- Builder lane (plan/BUILDER.md, ids agreed with the tech-tree designer) ----

    /**
     * Defense Plans (Watch, Hamlet ring): the Builder may raise palisade
     * lines with gates and the timber watchtower from the Builder's Plan.
     * Barricades do NOT need it (they come with the Builder himself).
     */
    DEFENSE_PLANS("defense_plans", 19, Direction.WATCH,
        DevelopmentNode.FIRST_WATCH, null, 3, null, 0,
        () -> List.of(log(16), cost(Items.STICK, 16))),
    /**
     * Masonry (Craft, Village ring): stone wall lines, the stone gatehouse,
     * the stone watchtower, and stone floors in Upgrade Orders. Unlocks no
     * building level: levels stay checklist-only.
     */
    MASONRY("masonry", 20, Direction.CRAFT,
        DevelopmentNode.FIRST_RAID_AFTERMATH, "defense_plans", 6, null, 0,
        () -> List.of(cost(Items.COBBLESTONE, 32), cost(Items.STONE_BRICKS, 16)));

    /** Presentation direction on the hub-shaped Development screen. */
    public enum Direction {
        WATCH("watch"),
        HEARTH("hearth"),
        CRAFT("craft"),
        LOGISTICS("logistics");

        private final String id;

        Direction(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /**
     * Sack tiers (none, Satchel, Leather Pack, Frame Pack): percent of the
     * base trip budget added on top of it. See {@link HaulGear}.
     */
    public static final int[] SACK_TIER_PERCENT = {0, 50, 100, 150};
    /** Extra items per Courier trip once the Satchel is owned (tier 1: 8 to 12). */
    public static final int COURIER_SATCHEL_BONUS =
        8 * 50 / 100;
    /** Hand Cart: percent of the base trip budget added on top of the sack. */
    public static final int HAND_CART_PERCENT = 200;
    /** Extra items per Courier trip from the Hand Cart at base (8 * 200%). */
    public static final int HAND_CART_BONUS = 8 * HAND_CART_PERCENT / 100;
    /** Hand Cart on a road block: walking speed bonus percent. */
    public static final int HAND_CART_ROAD_PERCENT = 10;
    /** Hand Cart off-road (grass, sand, mud...): walking speed penalty percent. */
    public static final int HAND_CART_ROUGH_PERCENT = 15;
    /** Paved Roads: walking speed bonus percent on road blocks, every settler. */
    public static final int PAVED_ROADS_PERCENT = 15;
    /** Swift Couriers: Courier walking speed bonus percent, everywhere. */
    public static final int SWIFT_COURIERS_PERCENT = 10;
    /** Worker Packs multiply the natural gathering budget by 3/2 (rounded down). */
    public static final int WORKER_PACKS_NUMERATOR = 3;
    public static final int WORKER_PACKS_DENOMINATOR = 2;
    /** Extra blocks of ordinary (unposted) Archer shot range. */
    public static final double LONGBOW_RANGE_BONUS = 2.0D;
    /** Ticks removed from the ordinary Archer draw (20 to 16). */
    public static final int LONGBOW_DRAW_TICKS_SAVED = 4;
    /** Warm Hearth: night-time hunger drain reduced by this percentage. */
    public static final int WARM_HEARTH_PERCENT = 10;
    /** Sturdy Beds: morale target bonus for a settler with a claimed bed. */
    public static final int STURDY_BEDS_MORALE = 2;
    /** Feather Quilts: sleeping energy regain increased by this percentage. */
    public static final int FEATHER_QUILTS_PERCENT = 20;
    /** Sharpened Axes: felling time reduced by this percentage. */
    public static final int SHARPENED_AXES_PERCENT = 10;
    /** Fisher's Nets: cast cycle shortened by this percentage. */
    public static final int FISHERS_NETS_PERCENT = 10;
    /** Stout Straps: extra items per Courier trip. */
    public static final int STOUT_STRAPS_BONUS = 2;
    /** Guard Drill: extra combat experience percentage. */
    public static final int GUARD_DRILL_PERCENT = 15;
    /** Shield Doctrine (a node): extra Guard combat experience percentage. */
    public static final int SHIELD_DOCTRINE_XP_PERCENT = 25;

    private final String id;
    /** Stable network id for Development actions/snapshots; never the ordinal. */
    private final int wireId;
    private final Direction direction;
    private final DevelopmentNode requires;
    private final int coinCost;
    /** Id of an upgrade that must already be owned, or null. */
    @Nullable
    private final String requiresUpgradeId;
    @Nullable
    private final DevelopmentObjective gateObjective;
    private final int gateTarget;
    private final Supplier<List<DevelopmentNode.Cost>> materials;
    private volatile List<DevelopmentNode.Cost> pricedCosts;

    PostRaidUpgrade(String id, int wireId, Direction direction,
                    DevelopmentNode requires, @Nullable String requiresUpgradeId,
                    int coinCost) {
        this(id, wireId, direction, requires, requiresUpgradeId, coinCost,
            null, 0, List::of);
    }

    PostRaidUpgrade(String id, int wireId, Direction direction,
                    DevelopmentNode requires, @Nullable String requiresUpgradeId,
                    int coinCost, @Nullable DevelopmentObjective gateObjective,
                    int gateTarget,
                    Supplier<List<DevelopmentNode.Cost>> materials) {
        this.id = id;
        this.wireId = wireId;
        this.direction = direction;
        this.requires = requires;
        this.requiresUpgradeId = requiresUpgradeId;
        this.coinCost = coinCost;
        this.gateObjective = gateObjective;
        this.gateTarget = gateObjective == null ? 0 : Math.max(1, gateTarget);
        this.materials = materials;
    }

    public String id() {
        return id;
    }

    public int wireId() {
        return wireId;
    }

    public Direction direction() {
        return direction;
    }

    /** Coins charged once; mirrors the first {@link #costs()} line. */
    public int coinCost() {
        return coinCost;
    }

    /** The Development node that must be learned before this can be bought. */
    public DevelopmentNode requires() {
        return requires;
    }

    /** True for the outer ring that opens only with the First Raid milestone. */
    public boolean postRaid() {
        return requires == DevelopmentNode.FIRST_RAID_AFTERMATH;
    }

    /**
     * The upgrade that must already be owned before this one can be
     * bought (Hand Cart requires the Courier Satchel), or null.
     */
    @Nullable
    public PostRaidUpgrade requiresUpgrade() {
        return requiresUpgradeId == null ? null : byId(requiresUpgradeId);
    }

    /** Lifetime milestone the settlement must have reached, or null. */
    @Nullable
    public DevelopmentObjective gateObjective() {
        return gateObjective;
    }

    public int gateTarget() {
        return gateTarget;
    }

    /** Physical goods lines only (never Coins). */
    public List<DevelopmentNode.Cost> materialCosts() {
        return materials.get();
    }

    /**
     * The full price, resolved lazily after registries exist: one Coins line
     * first, then any physical goods, all paid atomically.
     */
    public List<DevelopmentNode.Cost> costs() {
        List<DevelopmentNode.Cost> cached = pricedCosts;
        if (cached == null) {
            List<DevelopmentNode.Cost> priced = new ArrayList<>();
            priced.add(new DevelopmentNode.Cost(ModItems.GOLD_COIN.get(), coinCost));
            priced.addAll(materials.get());
            cached = List.copyOf(priced);
            pricedCosts = cached;
        }
        return cached;
    }

    public Component displayName() {
        return Component.translatable("hearthstead.development.upgrade." + id + ".name");
    }

    public Component description() {
        return Component.translatable("hearthstead.development.upgrade." + id + ".desc");
    }

    public Component tooltip() {
        return Component.translatable("hearthstead.development.upgrade." + id + ".tooltip");
    }

    @Nullable
    public static PostRaidUpgrade byWireId(int wireId) {
        for (PostRaidUpgrade upgrade : values()) {
            if (upgrade.wireId == wireId) {
                return upgrade;
            }
        }
        return null;
    }

    @Nullable
    public static PostRaidUpgrade byId(String id) {
        if (id == null) {
            return null;
        }
        for (PostRaidUpgrade upgrade : values()) {
            if (upgrade.id.equals(id)) {
                return upgrade;
            }
        }
        return null;
    }

    private static DevelopmentNode.Cost cost(net.minecraft.world.item.Item item, int count) {
        return new DevelopmentNode.Cost(item, count);
    }

    private static DevelopmentNode.Cost log(int count) {
        return new DevelopmentNode.Cost(Items.OAK_LOG, count, ItemTags.LOGS,
            "hearthstead.development.cost.any_log");
    }

    private static DevelopmentNode.Cost plank(int count) {
        return new DevelopmentNode.Cost(Items.OAK_PLANKS, count, ItemTags.PLANKS,
            "hearthstead.development.cost.any_plank");
    }

    private static DevelopmentNode.Cost wool(int count) {
        return new DevelopmentNode.Cost(Items.WHITE_WOOL, count, ItemTags.WOOL,
            "hearthstead.development.cost.any_wool");
    }
}
