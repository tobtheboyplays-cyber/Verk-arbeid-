package com.hearthstead.settlement;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The mayor: one settler who speaks for the settlement, and whose character
 * the whole settlement takes on.
 *
 * <h2>Why the mayor is a person and not a slider</h2>
 *
 * <p>Owner's ask, 2026-08-25: a mayor whose death is a real blow, who can be
 * swapped, and whose buff makes swapping interesting. The way to make that a
 * decision rather than a menu is to derive the buff from <b>who they are</b> —
 * a mayor's {@link Attribute#knack} decides what the settlement is good at,
 * so appointing the strong one and appointing the clever one are different
 * settlements. You are not picking a buff; you are picking a person and
 * getting their buff.
 *
 * <h2>Three costs keep it from being a toggle</h2>
 *
 * <ol>
 *   <li><b>Settling in.</b> A new mayor's buff arrives after
 *       {@link #SETTLING_TICKS}. Swapping for a raid you can see coming works;
 *       swapping every morning does not.
 *   <li><b>Mourning.</b> If the mayor dies the settlement can appoint nobody
 *       for {@link #MOURNING_TICKS}, so a killed mayor is a stretch of days
 *       with no buff at all — that is the "stor straff".
 *   <li><b>Morale.</b> Losing a mayor costs every settler morale; standing
 *       down voluntarily costs a little.
 * </ol>
 */
public final class Mayor {

    /** A day and a half before a new mayor's character shows in the village. */
    public static final long SETTLING_TICKS = 30000L;
    /** Three days of mourning before anyone can take office after a death. */
    public static final long MOURNING_TICKS = 72000L;
    /** What losing a mayor costs every settler. */
    public static final float DEATH_MORALE_HIT = -22.0F;
    /** What replacing one costs — real, but nothing like a death. */
    public static final float STAND_DOWN_MORALE_HIT = -4.0F;

    /**
     * What a settlement gains from its mayor. One per attribute, so every
     * settler is a plausible mayor and none of them is the obvious one.
     */
    public enum Boon {
        HARD_HANDS("hard_hands", Attribute.STRENGTH),
        LONG_DAYS("long_days", Attribute.STAMINA),
        GOOD_COUNSEL("good_counsel", Attribute.WITS),
        CAREFUL_WORK("careful_work", Attribute.DEXTERITY),
        OPEN_HEARTH("open_hearth", Attribute.SPIRIT);

        private final String key;
        private final Attribute from;

        Boon(String key, Attribute from) {
            this.key = key;
            this.from = from;
        }

        public String key() {
            return key;
        }

        public Attribute from() {
            return from;
        }

        public Component displayName() {
            return Component.translatable("hearthstead.mayor.boon." + key);
        }

        public Component describe() {
            return Component.translatable("hearthstead.mayor.boon." + key + ".desc");
        }

        public static Boon of(Attribute attribute) {
            for (Boon boon : values()) {
                if (boon.from == attribute) {
                    return boon;
                }
            }
            return HARD_HANDS;
        }
    }

    /** The mayor's entity, or null if there is none or they are not loaded. */
    @Nullable
    public static SettlerEntity find(ServerLevel level, Settlement settlement) {
        if (settlement.mayorId == null) {
            return null;
        }
        return level.getEntity(settlement.mayorId) instanceof SettlerEntity settler
            && settler.isAlive() ? settler : null;
    }

    /** Whether a settlement is in mourning and may not appoint anyone. */
    public static boolean mourning(ServerLevel level, Settlement settlement) {
        return level.getGameTime() < settlement.mourningUntil;
    }

    /**
     * The boon in effect right now, or null.
     *
     * <p>Null while a new mayor is settling in, which is the whole point of
     * the settling period: the seat is filled, the benefit is not there yet.
     */
    @Nullable
    public static Boon activeBoon(ServerLevel level, Settlement settlement) {
        SettlerEntity mayor = find(level, settlement);
        if (mayor == null) {
            return null;
        }
        if (level.getGameTime() - settlement.mayorSince < SETTLING_TICKS) {
            return null;
        }
        return Boon.of(mayor.attributes().knack());
    }

    /** The boon this settler would eventually bring, for the UI to show. */
    public static Boon boonOf(SettlerEntity settler) {
        return Boon.of(settler.attributes().knack());
    }

    /**
     * Everyone in the settlement who could take the seat -- everyone but
     * whoever holds it now. Lives here rather than in the network glue so
     * the hearth screen's Mayor tab and any future caller share one
     * definition of "candidate" instead of each re-deriving it.
     */
    public static List<SettlerEntity> candidates(ServerLevel level, Settlement settlement) {
        SettlerEntity mayor = find(level, settlement);
        List<SettlerEntity> candidates = new ArrayList<>();
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            if (mayor == null || !settler.getUUID().equals(mayor.getUUID())) {
                candidates.add(settler);
            }
        }
        return candidates;
    }

    /**
     * Appoints a new mayor.
     *
     * @return null on success, or the reason it was refused
     */
    @Nullable
    public static Component appoint(ServerLevel level, Settlement settlement,
                                    SettlerEntity settler) {
        if (mourning(level, settlement)) {
            return Component.translatable("hearthstead.mayor.refused.mourning");
        }
        if (settlement.mayorId != null && settlement.mayorId.equals(settler.getUUID())) {
            return Component.translatable("hearthstead.mayor.refused.already");
        }
        SettlerEntity previous = find(level, settlement);
        settlement.mayorId = settler.getUUID();
        settlement.mayorSince = level.getGameTime();
        if (previous != null) {
            // Standing somebody down is a small public unkindness, not a
            // free swap.
            for (SettlerEntity member : SettlementManager.loadedMembers(level, settlement)) {
                member.addMorale(STAND_DOWN_MORALE_HIT);
            }
        }
        settler.addMorale(12.0F);
        settler.celebrate();
        SettlementManager.data(level).setDirty();
        return null;
    }

    /**
     * The mayor has died.
     *
     * <p>The heavy penalty the owner asked for, and it is deliberately made of
     * time rather than numbers: every settler takes a morale hit, and the seat
     * cannot be filled for three days, so the settlement runs with no boon at
     * all through whatever comes next.
     */
    public static void onDeath(ServerLevel level, Settlement settlement,
                               SettlerEntity dead) {
        if (settlement.mayorId == null || !settlement.mayorId.equals(dead.getUUID())) {
            return;
        }
        settlement.mayorId = null;
        settlement.mayorSince = 0L;
        settlement.mourningUntil = level.getGameTime() + MOURNING_TICKS;
        for (SettlerEntity member : SettlementManager.loadedMembers(level, settlement)) {
            member.addMorale(DEATH_MORALE_HIT);
        }
        SettlementManager.data(level).setDirty();
    }

    // ------------------------------------------------------- the effects ---

    /** Work-speed multiplier the settlement currently enjoys. */
    public static float workSpeed(ServerLevel level, Settlement settlement) {
        return activeBoon(level, settlement) == Boon.HARD_HANDS ? 1.10F : 1.0F;
    }

    /** Energy-drain multiplier; below one means longer days. */
    public static float energyDrain(ServerLevel level, Settlement settlement) {
        return activeBoon(level, settlement) == Boon.LONG_DAYS ? 0.85F : 1.0F;
    }

    /** Attribute-growth multiplier. */
    public static float growth(ServerLevel level, Settlement settlement) {
        return activeBoon(level, settlement) == Boon.GOOD_COUNSEL ? 1.25F : 1.0F;
    }

    /** Chance a finished piece of work yields one extra. */
    public static float extraYieldChance(ServerLevel level, Settlement settlement) {
        return activeBoon(level, settlement) == Boon.CAREFUL_WORK ? 0.10F : 0.0F;
    }

    /** Morale-decay multiplier; below one means a happier village. */
    public static float moraleDecay(ServerLevel level, Settlement settlement) {
        return activeBoon(level, settlement) == Boon.OPEN_HEARTH ? 0.80F : 1.0F;
    }

    private Mayor() {
    }
}
