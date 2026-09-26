package com.hearthstead.fx;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.work.GoodsQuality;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

/**
 * The one-line server hooks for particle moments. Each game system calls
 * exactly one of these where its moment is already decided; every hook is
 * fail-soft (a presentation failure is logged, never thrown into gameplay)
 * and sends at most one payload per nearby player (see {@link FxSend}).
 */
public final class FxHooks {
    private FxHooks() {
    }

    /** SkillLevels#onLevelUp: golden sparkle burst and rising motes. */
    public static void skillLevelUp(Entity settler) {
        safe(() -> FxSend.on(settler, FxEffect.SKILL_LEVEL_UP, 0));
    }

    /** TechTree#afterLearned / Development#purchaseNode: burst and cloth ripple at the Banner. */
    public static void techLearned(ServerLevel level, @Nullable Settlement settlement) {
        if (settlement == null || settlement.center == null) {
            return;
        }
        safe(() -> atBanner(level, settlement.center, FxEffect.TECH_LEARNED));
    }

    /** A Journey chapter completed: rising column and crown burst at the Banner. */
    public static void journeyChapter(ServerLevel level, @Nullable Settlement settlement) {
        if (settlement == null || settlement.center == null) {
            return;
        }
        safe(() -> atBanner(level, settlement.center, FxEffect.JOURNEY_CHAPTER));
    }

    /** A raid was held: celebration bursts above the Banner (particles only, no rockets). */
    public static void raidWon(ServerLevel level, @Nullable Settlement settlement) {
        if (settlement == null || settlement.center == null) {
            return;
        }
        safe(() -> atBanner(level, settlement.center, FxEffect.RAID_WON));
    }

    /**
     * Plaque survey raised a building's checklist level: sparkles along the
     * plaque and the room outline. The warehouse gets its own moment.
     */
    public static void buildingLevelUp(ServerLevel level, BlockPos plaque, BuildingType type,
                                       @Nullable BoundingBox room, int newLevel) {
        FxEffect effect = type == BuildingType.WAREHOUSE ? FxEffect.WAREHOUSE_LEVEL_UP : FxEffect.BUILDING_LEVEL_UP;
        safe(() -> FxSend.send(level, effect, plaque.getX() + 0.5D, plaque.getY() + 0.5D, plaque.getZ() + 0.5D,
            -1, newLevel, room));
    }

    /** Production#run by a crafter: Superior+ glints, Legendary radiates. */
    public static void crafted(@Nullable Entity crafter, int quality) {
        if (crafter == null || quality < GoodsQuality.SUPERIOR) {
            return;
        }
        FxEffect effect = quality >= GoodsQuality.HIGHEST ? FxEffect.CRAFT_LEGENDARY : FxEffect.CRAFT_GLINT;
        safe(() -> FxSend.on(crafter, effect, quality));
    }

    /** GoldCoinTrades#onSale: a coin sparkle at the merchant. */
    public static void coinSale(Entity trader) {
        safe(() -> FxSend.on(trader, FxEffect.COIN_SALE, 0));
    }

    /** BuildJobs#finish: dust at the base, wood chips off the top, a "done" sound. */
    public static void buildDone(ServerLevel level, BoundingBox bounds) {
        safe(() -> {
            var c = bounds.getCenter();
            return FxSend.send(level, FxEffect.BUILD_DONE, c.getX() + 0.5D, bounds.maxY() + 0.5D, c.getZ() + 0.5D,
                -1, 0, bounds);
        });
    }

    /** SettlementManager admission: a warm welcome glow round the new settler. */
    public static void settlerWelcome(Entity settler) {
        safe(() -> FxSend.on(settler, FxEffect.SETTLER_WELCOME, 0));
    }

    // ------------------------------------------------------------ Captain ---

    /** Client look family for a Captain special (packed into the payload arg; see FxRecipes). */
    public static final int CAPTAIN_BLADE = 0, CAPTAIN_RALLY = 1, CAPTAIN_HEAL = 2, CAPTAIN_BOW = 3,
        CAPTAIN_HEAVY = 4, CAPTAIN_SWEEP = 5, CAPTAIN_SHIELD = 6, CAPTAIN_EXECUTION = 7;

    public static int captainStyle(com.hearthstead.entity.combat.captain.CaptainSpecial special) {
        return switch (special) {
            case RALLY_CRY -> CAPTAIN_RALLY;
            case SECOND_WIND -> CAPTAIN_HEAL;
            case EXECUTION -> CAPTAIN_EXECUTION;
            case ARROW_VOLLEY, PIERCING_SHOT, MARK_TARGET -> CAPTAIN_BOW;
            case GROUND_SLAM, CRUSHING_BLOW, SHIELD_BREAKER, ARMOUR_BREAKER -> CAPTAIN_HEAVY;
            case BLADE_WHIRL, SPINNING_CHOP, AXE_CLEAVE, HALBERD_SWEEP -> CAPTAIN_SWEEP;
            case SHIELD_CHARGE, HOLD_THE_LINE, POMMEL_STUN, BRACE_CHARGE -> CAPTAIN_SHIELD;
            default -> CAPTAIN_BLADE;
        };
    }

    /** captain.promoted: the commissioning moment (the hero's level-up burst). */
    public static void captainPromoted(Entity captain) {
        safe(() -> FxSend.on(captain, FxEffect.CAPTAIN_PROMOTED, 0));
    }

    /**
     * captain.&lt;special&gt;.windup: call ONCE at wind-up tick 0; the client
     * pulses the telegraph ring for the whole wind-up itself (one payload,
     * not one per pulse). No-op for a special without a wind-up.
     */
    public static void captainWindup(Entity captain, com.hearthstead.entity.combat.captain.CaptainSpecial special) {
        int ticks = special.windupTicks();
        if (ticks <= 0) {
            return;
        }
        int arg = captainStyle(special) | Math.min(255, ticks) << 8;
        safe(() -> FxSend.on(captain, FxEffect.CAPTAIN_WINDUP, arg));
    }

    /** captain.&lt;special&gt;.impact: once when the special resolves, drawn at {@code at}. */
    public static void captainImpact(Entity captain, com.hearthstead.entity.combat.captain.CaptainSpecial special,
                                     net.minecraft.world.phys.Vec3 at) {
        if (!(captain.level() instanceof ServerLevel level)) {
            return;
        }
        int rangeTenths = (int) Math.round(Math.min(25.5D, Math.max(0.0D, special.range())) * 10.0D);
        int arg = captainStyle(special) | rangeTenths << 8;
        net.minecraft.world.phys.Vec3 point = at == null ? captain.position() : at;
        safe(() -> FxSend.send(level, FxEffect.CAPTAIN_IMPACT, point.x, point.y, point.z, captain.getId(), arg, null));
    }

    private static int atBanner(ServerLevel level, BlockPos banner, FxEffect effect) {
        return FxSend.at(level, effect, banner.getX() + 0.5D, banner.getY(), banner.getZ() + 0.5D);
    }

    private interface Send {
        int run();
    }

    private static void safe(Send send) {
        try {
            send.run();
        } catch (RuntimeException failure) {
            Hearthstead.LOGGER.debug("FX send failed (presentation only)", failure);
        }
    }
}
