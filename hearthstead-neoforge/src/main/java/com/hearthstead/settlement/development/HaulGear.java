package com.hearthstead.settlement.development;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * The logistics branch in one place: sack tiers, the Hand Cart, road speed
 * and courier speed.
 *
 * <h2>Carrying</h2>
 * <p>Sack tiers are three chained upgrades: {@link PostRaidUpgrade#COURIER_SATCHEL}
 * (tier 1), {@link PostRaidUpgrade#LEATHER_PACK} (2) and
 * {@link PostRaidUpgrade#FRAME_PACK} (3). Each adds a percentage of the base
 * trip budget ({@link PostRaidUpgrade#SACK_TIER_PERCENT}: +50/+100/+150%).
 * The {@link PostRaidUpgrade#HAND_CART} adds {@link #cartPercent()} on top
 * for Couriers. Everything still lands in the one entity-owned number
 * {@link SettlerEntity#getCarryCapacity()} and the one real bag, so the cart
 * is never a second inventory: death drops, unloads and reloads are exactly
 * the sack's, and nothing can be duplicated or stranded in a cart.
 *
 * <h2>Presentation</h2>
 * <p>{@link #publish} packs the tier and the cart into one synced int so the
 * client can grow the sack prop and draw the cart; it never feeds back into
 * server logic.
 *
 * <h2>Speed</h2>
 * <p>{@link #onFootfall} runs once per block a settler steps onto and keeps
 * one transient movement modifier in sync with the ground: Paved Roads on
 * road blocks, Swift Couriers, and the cart's road/rough multiplier.
 */
public final class HaulGear {
    private HaulGear() {
    }

    /** Road blocks: dirt path, gravel, stone bricks and friends. */
    public static final TagKey<Block> ROADS =
        TagKey.create(Registries.BLOCK, Hearthstead.id("roads"));

    static final ResourceLocation TERRAIN_SPEED_ID = Hearthstead.id("haul_terrain_speed");

    public static final int MAX_TIER = 3;
    private static final int CART_BIT = 1 << 4;
    /** Cart tier above the Hand Cart (0 hand, 1 Coster's, 2 Mule) in two bits. */
    private static final int CART_TIER_SHIFT = 5;
    public static final int MAX_CART_TIER = 3;

    // ---- Tiers and capacity (pure math, JUnit-safe) ----

    /** Percent of the base budget a sack tier adds (0, 50, 100, 150). */
    public static int sackPercent(int tier) {
        int clamped = Math.max(0, Math.min(MAX_TIER, tier));
        return PostRaidUpgrade.SACK_TIER_PERCENT[clamped];
    }

    /** Hand Cart percent, from the server config (default 200). */
    public static int cartPercent() {
        return HearthsteadServerConfig.handCartPercent();
    }

    /** A budget raised by {@code percent} of itself, rounded down. */
    public static int raised(int natural, int percent) {
        return natural + natural * Math.max(0, percent) / 100;
    }

    /** Courier budget before Straps and skill: base * (100 + sack + cart)%. */
    public static int courierCapacity(int tier, boolean cart) {
        return courierCapacity(tier, cart ? cartPercent() : 0, 0);
    }

    /**
     * Courier budget with an explicit cart percent (Hand Cart 200, Coster's
     * Cart 300, Mule Cart 400; 0 = no cart) and a whole-trip load percent
     * (Porters' Guild +50) applied to the sack-and-cart budget.
     */
    public static int courierCapacity(int tier, int cartPercent, int loadPercent) {
        int budget = raised(SettlerEntity.BASE_CARRY_CAPACITY,
            sackPercent(tier) + (tier >= 1 ? Math.max(0, cartPercent) : 0));
        return raised(budget, loadPercent);
    }

    /**
     * A gatherer's packed budget: Worker Packs give at least the Satchel's
     * tier, and follow the courier sack tier upward once it is higher.
     */
    public static int gathererCapacity(int natural, int tier) {
        return raised(natural, sackPercent(Math.max(1, tier)));
    }

    public static String tierKey(int tier) {
        return switch (Math.max(0, Math.min(MAX_TIER, tier))) {
            case 1 -> "courier_satchel";
            case 2 -> "leather_pack";
            case 3 -> "frame_pack";
            default -> "none";
        };
    }

    /** Client sack prop scale per tier: 1.00, 1.15, 1.30, 1.45. */
    public static float visualScale(int tier) {
        return 1.0F + 0.15F * Math.max(0, Math.min(MAX_TIER, tier));
    }

    public static int pack(int tier, boolean cart) {
        return pack(tier, cart ? 1 : 0);
    }

    /** Sack tier plus cart tier (0 none, 1 Hand Cart, 2 Coster's, 3 Mule Cart). */
    public static int pack(int tier, int cartTier) {
        int packed = Math.max(0, Math.min(MAX_TIER, tier));
        if (cartTier >= 1) {
            packed |= CART_BIT | (Math.min(MAX_CART_TIER, cartTier) - 1) << CART_TIER_SHIFT;
        }
        return packed;
    }

    /** Cart tier from a packed gear value (0 when no cart). */
    public static int unpackCartTier(int packed) {
        return unpackCart(packed) ? 1 + ((packed >> CART_TIER_SHIFT) & 0x3) : 0;
    }

    public static int unpackTier(int packed) {
        return packed & 0xF;
    }

    public static boolean unpackCart(int packed) {
        return (packed & CART_BIT) != 0;
    }

    // ---- Settlement reads (server) ----

    /**
     * Sunday kill-switch ({@code [features] logisticsUpgrades}): false keeps every
     * carrier at the base budget, draws no sack growth or cart and applies no
     * road/courier speed. Owned upgrades stay saved, so re-enabling restores them.
     */
    public static boolean enabled() {
        return HearthsteadServerConfig.logisticsUpgradesEnabled();
    }

    /** Highest owned sack tier (the upgrades chain, so ownership is ordered). */
    public static int sackTier(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        if (level == null || settlement == null || !enabled()) {
            return 0;
        }
        if (Development.hasUpgrade(level, settlement, PostRaidUpgrade.FRAME_PACK)) {
            return 3;
        }
        if (Development.hasUpgrade(level, settlement, PostRaidUpgrade.LEATHER_PACK)) {
            return 2;
        }
        return Development.hasUpgrade(level, settlement, PostRaidUpgrade.COURIER_SATCHEL)
            ? 1 : 0;
    }

    /** True once the Hand Cart is owned (it chains from the Satchel). */
    public static boolean cartOwned(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return enabled() && DevelopmentBonuses.owned(level, settlement, PostRaidUpgrade.HAND_CART)
            && DevelopmentBonuses.owned(level, settlement, PostRaidUpgrade.COURIER_SATCHEL);
    }

    /**
     * Cart percent this settlement's Couriers get: the Hand Cart's config
     * percent plus the tech tree's Coster's Cart / Mule Cart steps.
     */
    public static int cartPercent(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return cartPercent()
            + com.hearthstead.settlement.techtree.effects.LogisticsEffects.extraCartPercent(level, settlement);
    }

    /** 0 without a cart, 1 Hand Cart, 2 Coster's Cart, 3 Mule Cart. */
    public static int cartTier(@Nullable ServerLevel level, @Nullable Settlement settlement) {
        return cartOwned(level, settlement)
            ? com.hearthstead.settlement.techtree.effects.LogisticsEffects.cartUpgradeTier(level, settlement)
            : 0;
    }

    /**
     * The gear this settler visibly wears: Couriers use the sack tier and the
     * cart; Farmers and Lumberers the sack tier when Worker Packs are owned.
     */
    public static int gearFor(@Nullable ServerLevel level, @Nullable Settlement settlement,
                              SettlerEntity settler) {
        if (level == null || settlement == null || settler == null || !enabled()) {
            return 0;
        }
        Profession profession = settler.getProfession();
        if (profession == Profession.COURIER) {
            return pack(sackTier(level, settlement), cartTier(level, settlement));
        }
        if (WorkerPacks.appliesTo(profession)
            && Development.hasUpgrade(level, settlement, PostRaidUpgrade.WORKER_PACKS)) {
            return pack(Math.max(1, sackTier(level, settlement)), false);
        }
        return 0;
    }

    /** Publishes the gear to the synced projection (presentation only). */
    public static void publish(@Nullable ServerLevel level, @Nullable Settlement settlement,
                               SettlerEntity settler) {
        if (settler != null && !settler.level().isClientSide) {
            settler.setHaulGear(gearFor(level, settlement, settler));
        }
    }

    // ---- Ground and speed ----

    /** True when the settler's feet are on a road block. */
    public static boolean onRoad(BlockGetter level, BlockPos feet) {
        BlockState at = level.getBlockState(feet);
        // A dirt path is 15/16 tall, so the feet stand inside it.
        if (at.is(ROADS)) {
            return true;
        }
        return at.getCollisionShape(level, feet).isEmpty()
            && level.getBlockState(feet.below()).is(ROADS);
    }

    /**
     * Movement multiplier percent for one settler on the given ground:
     * Paved Roads on a road, Swift Couriers for Couriers, and the cart's
     * road bonus or rough-ground penalty while it is hitched.
     */
    public static int terrainPercent(boolean road, boolean pavedRoads, boolean courier,
                                     boolean swift, boolean cartHitched) {
        return terrainPercent(road, pavedRoads, courier, swift, cartHitched, 0, 0);
    }

    /**
     * As above, plus the tech tree: {@code courierPercent} for Couriers
     * (Runners' Guild +15, Porters' Guild -10) and {@code cartRoadPercent}
     * for a hitched cart on a road (Mule Cart +20).
     */
    public static int terrainPercent(boolean road, boolean pavedRoads, boolean courier,
                                     boolean swift, boolean cartHitched,
                                     int courierPercent, int cartRoadPercent) {
        int percent = courier ? courierPercent : 0;
        if (road && pavedRoads) {
            percent += HearthsteadServerConfig.pavedRoadsPercent();
        }
        if (courier && swift) {
            percent += PostRaidUpgrade.SWIFT_COURIERS_PERCENT;
        }
        if (cartHitched) {
            percent += road ? PostRaidUpgrade.HAND_CART_ROAD_PERCENT + cartRoadPercent
                : -HearthsteadServerConfig.handCartRoughPercent();
        }
        return percent;
    }

    /**
     * Server hook, once per block a settler steps onto. Keeps the terrain
     * speed modifier and the gear projection current; both are transient and
     * recomputed from ownership, so nothing here is saved or can drift.
     */
    public static void onFootfall(SettlerEntity settler) {
        if (!(settler.level() instanceof ServerLevel level)) {
            return;
        }
        Settlement settlement = settler.settlement();
        int gear = gearFor(level, settlement, settler);
        settler.setHaulGear(gear);
        if (!enabled()) {
            applySpeed(settler, 0);
            return;
        }
        boolean courier = settler.getProfession() == Profession.COURIER;
        boolean road = onRoad(level, settler.blockPosition());
        int percent = terrainPercent(road,
            DevelopmentBonuses.owned(level, settlement, PostRaidUpgrade.PAVED_ROADS),
            courier,
            courier && DevelopmentBonuses.owned(level, settlement,
                PostRaidUpgrade.SWIFT_COURIERS),
            courier && unpackCart(gear) && cartHitched(settler),
            courier ? com.hearthstead.settlement.techtree.effects.LogisticsEffects
                .courierSpeedPercent(level, settlement) : 0,
            courier ? com.hearthstead.settlement.techtree.effects.LogisticsEffects
                .cartRoadPercent(level, settlement) : 0);
        applySpeed(settler, percent);
    }

    /**
     * Whether the cart is on the road with the Courier (server twin of the
     * client presentation): walking a delivery or travelling, not in a
     * building. A parked cart never slows anyone.
     */
    public static boolean cartHitched(SettlerEntity settler) {
        var activity = settler.getActivity();
        return (activity == com.hearthstead.entity.SettlerActivity.CARRYING
                || activity == com.hearthstead.entity.SettlerActivity.TRAVELING)
            && settler.level().canSeeSky(settler.blockPosition().above());
    }

    static void applySpeed(SettlerEntity settler, int percent) {
        var speed = settler.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed == null) {
            return;
        }
        AttributeModifier current = speed.getModifier(TERRAIN_SPEED_ID);
        double amount = percent / 100.0D;
        if (percent == 0) {
            if (current != null) {
                speed.removeModifier(TERRAIN_SPEED_ID);
            }
            return;
        }
        if (current != null && current.amount() == amount) {
            return;
        }
        speed.addOrUpdateTransientModifier(new AttributeModifier(TERRAIN_SPEED_ID,
            amount, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
    }

    /** Current terrain modifier percent on a settler (tests, status lines). */
    public static int appliedPercent(SettlerEntity settler) {
        var speed = settler.getAttribute(Attributes.MOVEMENT_SPEED);
        AttributeModifier current = speed == null ? null : speed.getModifier(TERRAIN_SPEED_ID);
        return current == null ? 0 : (int) Math.round(current.amount() * 100.0D);
    }
}
