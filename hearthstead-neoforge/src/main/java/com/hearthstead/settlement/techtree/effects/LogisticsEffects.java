package com.hearthstead.settlement.techtree.effects;

import com.hearthstead.event.worldevent.WorldEventType;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.HaulGear;
import com.hearthstead.settlement.development.TechTree;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.request.RequestLedger;
import com.hearthstead.settlement.request.RequestLedgerSavedData;
import com.hearthstead.settlement.request.RequestPriority;
import com.hearthstead.settlement.request.RequestRecord;
import com.hearthstead.settlement.request.RequestState;
import com.hearthstead.settlement.techtree.EffectRegistry;
import com.hearthstead.settlement.techtree.TechBonus;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Logistics branch effects. OWNED BY THE LOGISTICS BRANCH LANE: only that
 * lane edits this file (and data/hearthstead/techtree/logistics.json).
 *
 * <p>The twelve A nodes (sacks, Hand Cart, roads, storehouse levels...) are
 * legacy-backed and already read by {@code HaulGear}, {@code CourierSatchel}
 * and {@code WarehouseLevels}. The six v3 nodes below are read here, by the
 * same systems, and every read sits behind {@code [features]
 * logisticsUpgrades} ({@link HaulGear#enabled()}): with the switch off the
 * nodes stay learned but change nothing, exactly like the sacks and carts.
 *
 * <p>All reads are per settlement (the tree is shared by every player of a
 * co-op kingdom) and side-effect free, so they are safe from AI ticks.
 */
public final class LogisticsEffects {
    // ---- Numbers (one place; the node text in logistics.json quotes them) ----

    /** Coster's Cart and Mule Cart each add this much to the Hand Cart's +200%. */
    public static final int CART_STEP_PERCENT = 100;
    /** Porters' Guild: the whole trip budget (sack + cart) grows by half. */
    public static final int PORTERS_LOAD_PERCENT = 50;
    /** Porters' Guild: Couriers walk this much slower. */
    public static final int PORTERS_SLOW_PERCENT = 10;
    /** Runners' Guild: Couriers walk this much faster. */
    public static final int RUNNERS_SPEED_PERCENT = 15;
    /** Runners' Guild: rest after a blocked route is divided by this. */
    public static final int RUNNERS_REST_DIVISOR = 2;
    /** Mule Cart: a hitched cart rolls this much faster on roads. */
    public static final int MULE_ROAD_PERCENT = 20;
    /** Caravan Routes: caravan weight multiplier on other event days. */
    public static final int CARAVAN_WEIGHT_MULTIPLIER = 3;
    /** Caravan Routes: a caravan is due once this many days passed without one. */
    public static final int CARAVAN_DUE_DAYS = 3;

    // ---- Side-panel bonus keys (summed over learned nodes by TechTree.bonus) ----

    /** Extra cart carry, percent of the base Courier load (Hand Cart is +200). */
    public static final TechBonus CART_PERCENT = TechBonus.percent("logistics.cart_percent",
        "Courier carts carry +%s%% of the base load more (Hand Cart: +200%%)");
    /** Whole-trip load increase for Couriers. */
    public static final TechBonus COURIER_LOAD = TechBonus.percent("logistics.courier_load",
        "Couriers carry +%s%% on every trip");
    /** Courier walking speed increase. */
    public static final TechBonus COURIER_SPEED = TechBonus.percent("logistics.courier_speed",
        "Couriers walk +%s%% faster");
    /** Courier walking speed decrease (heavy loads). */
    public static final TechBonus COURIER_SLOW = TechBonus.percent("logistics.courier_slow",
        "Couriers walk %s%% slower under their heavy loads");
    /** Extra road speed for a hitched cart. */
    public static final TechBonus CART_ROAD_SPEED = TechBonus.percent("logistics.cart_road_speed",
        "Carts roll +%s%% faster on roads");

    private LogisticsEffects() {
    }

    static void register(EffectRegistry r) {
        // Stout Straps keeps its legacy +2 carry (PostRaidUpgrade) and adds
        // two-request pickup batching (techaudit lane, owner 26 Sep).
        r.node("stout_straps")
            .flag("CourierWorkGoal.finishWithdrawalTravel -> RequestLedgerService.reserveBatchPartner (CourierBatching)",
                "Pickup batching: a Courier with room left takes one more pickup within 16 blocks on the same trip");
        r.node("courier_ledger")
            .flag("CourierWorkGoal.selectPrioritizedRoute via LogisticsEffects.courierLadder",
                "Couriers serve Urgent, then High, then Normal requests, whatever the kind: "
                    + "Hearth food (High) now goes before workshop inputs (Normal)")
            .flag("CourierWorkGoal.selectPrioritizedRoute",
                "Priorities are the colours in the Banner's request list");
        r.node("costers_cart")
            .bonus(CART_PERCENT, CART_STEP_PERCENT)
            .flag("HaulGear.cartTier / HandCartRenderer",
                "Cart tier 2: a bigger, high-sided cart (Frame Pack + cart: 36 -> 44 per trip)");
        r.node("porters_guild")
            .bonus(COURIER_LOAD, PORTERS_LOAD_PERCENT)
            .bonus(COURIER_SLOW, PORTERS_SLOW_PERCENT)
            .flag("CourierSatchel.targetCapacity",
                "Frame Pack + Hand Cart: 36 -> 54 per trip; with the Coster's Cart 44 -> 66");
        r.node("runners_guild")
            .bonus(COURIER_SPEED, RUNNERS_SPEED_PERCENT)
            .flag("CourierWorkGoal.restRoute",
                "After a blocked route Couriers try again twice as soon (5 s -> 2.5 s, max 20 s -> 10 s)");
        r.node("mule_cart")
            .bonus(CART_PERCENT, CART_STEP_PERCENT)
            .bonus(CART_ROAD_SPEED, MULE_ROAD_PERCENT)
            .flag("HaulGear.cartTier / HandCartRenderer",
                "Cart tier 3: a heavy-laden cart (Frame Pack + cart: 44 -> 52 per trip)");
        r.node("caravan_routes")
            .flag("WorldEventDirector.observe via LogisticsEffects.caravanDue",
                "A trade caravan comes at least every " + CARAVAN_DUE_DAYS
                    + " days while someone is home and no raid is due")
            .flag("WorldEventSchedule.plan via LogisticsEffects.eventWeight",
                "On other event days a caravan is " + CARAVAN_WEIGHT_MULTIPLIER + "x as likely");
    }

    // ------------------------------------------------------------ reads --

    private static boolean on(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return level != null && settlement != null && HaulGear.enabled();
    }

    private static int bonus(@Nullable ServerLevel level, @Nullable Settlement settlement,
                             TechBonus key) {
        return on(level, settlement) ? (int) Math.round(TechTree.bonus(level, settlement, key)) : 0;
    }

    /** Extra cart percent from Coster's Cart / Mule Cart (0, 100 or 200). */
    public static int extraCartPercent(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return bonus(level, settlement, CART_PERCENT);
    }

    /** Cart tier beyond the Hand Cart: 2 with the Coster's Cart, 3 with the Mule Cart. */
    public static int cartUpgradeTier(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        if (!on(level, settlement)) {
            return 1;
        }
        if (Development.has(level, settlement, "mule_cart")) {
            return 3;
        }
        return Development.has(level, settlement, "costers_cart") ? 2 : 1;
    }

    /** Whole-trip load percent for Couriers (Porters' Guild: 50). */
    public static int courierLoadPercent(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return bonus(level, settlement, COURIER_LOAD);
    }

    /** Net Courier walking speed percent from the guilds (+15 Runners, -10 Porters). */
    public static int courierSpeedPercent(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return bonus(level, settlement, COURIER_SPEED) - bonus(level, settlement, COURIER_SLOW);
    }

    /** Extra road percent for a hitched cart (Mule Cart: 20). */
    public static int cartRoadPercent(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return bonus(level, settlement, CART_ROAD_SPEED);
    }

    /** Rest after a failed courier leg, shortened by the Runners' Guild. */
    public static int courierRestTicks(@Nullable ServerLevel level, @Nullable Settlement settlement,
                                       int rest) {
        return on(level, settlement) && Development.has(level, settlement, "runners_guild")
            ? Math.max(1, rest / RUNNERS_REST_DIVISOR) : rest;
    }

    // ------------------------------------------------ Courier's Ledger --

    /** The courier's work ladder, in its default (no-ledger) order. */
    public enum Rung {
        EQUIPMENT,
        RESTOCK,
        FOOD,
        TAVERN,
        COLLECTION
    }

    private static final List<Rung> DEFAULT_LADDER = List.of(Rung.values());

    /**
     * Pure ordering: without the ledger, the fixed ladder. With it, the rungs
     * holding an Urgent request go first, then High, then Normal; ties keep
     * the default order (stable sort). Hearth food requests are always High.
     */
    public static List<Rung> courierLadder(boolean ledger, Map<Rung, RequestPriority> top) {
        if (!ledger) {
            return DEFAULT_LADDER;
        }
        List<Rung> order = new ArrayList<>(DEFAULT_LADDER);
        order.sort(Comparator.comparingInt((Rung rung) -> priorityOf(rung, top).ordinal()).reversed());
        return List.copyOf(order);
    }

    private static RequestPriority priorityOf(Rung rung, Map<Rung, RequestPriority> top) {
        RequestPriority p = top == null ? null : top.get(rung);
        if (p == null) {
            p = RequestPriority.NORMAL;
        }
        if (rung == Rung.FOOD && p.ordinal() < RequestPriority.HIGH.ordinal()) {
            p = RequestPriority.HIGH; // RequestRecord.openFood is always HIGH
        }
        return p;
    }

    /** The ladder this settlement's Couriers use right now. */
    public static List<Rung> courierLadder(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        if (!on(level, settlement) || !Development.has(level, settlement, "courier_ledger")) {
            return DEFAULT_LADDER;
        }
        return courierLadder(true, openPriorities(level, settlement));
    }

    /** Highest open priority per rung: equipment requests and typed ledger rows. */
    static Map<Rung, RequestPriority> openPriorities(ServerLevel level, Settlement settlement) {
        Map<Rung, RequestPriority> top = new EnumMap<>(Rung.class);
        // The read-only list: the courier's own findEquipmentJob syncs the queue.
        for (EquipmentRequest request : EquipmentRequests.list(settlement)) {
            if (request.status() == EquipmentRequest.Status.OPEN) {
                raise(top, Rung.EQUIPMENT, switch (request.priority()) {
                    case URGENT -> RequestPriority.URGENT;
                    case HIGH -> RequestPriority.HIGH;
                    default -> RequestPriority.NORMAL;
                });
            }
        }
        RequestLedgerSavedData saved = RequestLedgerSavedData.existing(level);
        RequestLedger ledger = saved == null ? null : saved.existing(settlement.id);
        if (ledger != null && !ledger.quarantined()) {
            for (RequestRecord row : ledger.active()) {
                if (row.effectiveState() != RequestState.OPEN) {
                    continue;
                }
                Rung rung = switch (row.type()) {
                    case AMMUNITION -> Rung.RESTOCK;
                    case FOOD -> Rung.FOOD;
                    case MATERIAL_INPUT -> Rung.TAVERN;
                    case OUTPUT_PICKUP -> Rung.COLLECTION;
                    default -> null;
                };
                if (rung != null) {
                    raise(top, rung, row.priority());
                }
            }
        }
        return top;
    }

    private static void raise(Map<Rung, RequestPriority> top, Rung rung, RequestPriority p) {
        RequestPriority current = top.get(rung);
        if (current == null || p.ordinal() > current.ordinal()) {
            top.put(rung, p);
        }
    }

    // ---------------------------------------------------- Caravan Routes --

    /** Event weight multiplier this settlement's tech gives an event type. */
    public static double eventWeight(@Nullable ServerLevel level, @Nullable Settlement settlement,
                                     WorldEventType type) {
        return type == WorldEventType.CARAVAN && caravanRoutes(level, settlement)
            ? CARAVAN_WEIGHT_MULTIPLIER : 1.0D;
    }

    public static boolean caravanRoutes(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return on(level, settlement) && Development.has(level, settlement, "caravan_routes");
    }

    /**
     * Pure rule: with Caravan Routes a caravan is due when none has come for
     * {@link #CARAVAN_DUE_DAYS} days (or ever). {@code lastCaravanDay} &lt; 0
     * means never.
     */
    public static boolean caravanDue(boolean routes, long day, @Nullable Long lastCaravanDay) {
        if (!routes || day < 0L) {
            return false;
        }
        return lastCaravanDay == null || lastCaravanDay < 0L
            || day - lastCaravanDay >= CARAVAN_DUE_DAYS;
    }
}
