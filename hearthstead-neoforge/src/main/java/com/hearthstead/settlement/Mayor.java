package com.hearthstead.settlement;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.util.AuthorityTelemetry;
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
 *   <li><b>Mourning.</b> If the mayor dies the settlement loses its boon
 *       for {@link #MOURNING_TICKS}, even if a successor takes the vacant office,
 *       so a killed mayor is a stretch of days
 *       with no buff at all — that is the "stor straff".
 *   <li><b>Morale.</b> Losing a mayor costs every settler morale; standing
 *       down voluntarily costs a little.
 * </ol>
 */
public final class Mayor {

    /** A day and a half before a new mayor's character shows in the village. */
    public static final long SETTLING_TICKS = 30000L;
    /** Three days of mourning before a successor's boon can become active. */
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
        OPEN_HEARTH("open_hearth", Attribute.SPIRIT),
        CLEAR_SIGHT("clear_sight", Attribute.PERCEPTION),
        STEADY_PURPOSE("steady_purpose", Attribute.FOCUS),
        COMMON_VOICE("common_voice", Attribute.PRESENCE);

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
            // Exhaustive by design: adding an attribute cannot silently turn
            // an unrelated mayor into HARD_HANDS.
            return switch (attribute) {
                case STRENGTH -> HARD_HANDS;
                case STAMINA -> LONG_DAYS;
                case WITS -> GOOD_COUNSEL;
                case DEXTERITY -> CAREFUL_WORK;
                case SPIRIT -> OPEN_HEARTH;
                case PERCEPTION -> CLEAR_SIGHT;
                case FOCUS -> STEADY_PURPOSE;
                case PRESENCE -> COMMON_VOICE;
            };
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

    /** Whether the inherited death-mourning interval is still active. */
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
        // A death keeps the boon off for the full mourning interval, including
        // when the genuinely vacant seat has already received its successor.
        if (mourning(level, settlement)) {
            return null;
        }
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
     * Appointing a Mayor is retired (owner decision, 26 Sep 2026: the Mayor is
     * removed; a separate Guildmaster at the Banner trades profession emblems).
     * Every remaining caller receives the same refusal and nothing about the
     * settlement changes: no seat, no feast, no morale hit, no settling clock.
     *
     * @return always the retirement refusal
     */
    @Nullable
    public static Component appoint(ServerLevel level, Settlement settlement,
                                    SettlerEntity settler) {
        return Component.translatable("hearthstead.mayor.refused.retired");
    }

    /**
     * The mayor has died.
     *
     * <p>The heavy penalty the owner asked for, and it is deliberately made of
     * time rather than numbers: every settler takes a morale hit, and the boon
     * stays off for three days. A later successor may fill only the vacant
     * office; that does not clear inherited mourning.
     */
    public static void onDeath(ServerLevel level, Settlement settlement,
                               SettlerEntity dead) {
        if (settlement.mayorId == null || !settlement.mayorId.equals(dead.getUUID())) {
            return;
        }
        settlement.mayorId = null;
        settlement.mayorCourierWarehouseId = null;
        settlement.mayorSince = 0L;
        settlement.mourningUntil = level.getGameTime() + MOURNING_TICKS;
        for (SettlerEntity member : SettlementManager.loadedMembers(level, settlement)) {
            // Tech tree (Hall of Heroes): grief is halved.
            member.addMorale(DEATH_MORALE_HIT * com.hearthstead.settlement.techtree.effects.CommonsEffects
                .griefScale(level, settlement));
        }
        SettlementManager.data(level).setDirty();
    }

    // ------------------------------------------------------- the effects ---

    /**
     * Work-speed multiplier the settlement currently enjoys (Hard Hands:
     * 1.05..1.10 by the mayor's Strength). Informational; the effect itself
     * is applied per work action through AttributeRuntime.workBoon.
     */
    public static float workSpeed(ServerLevel level, Settlement settlement) {
        return 1.0F + (float) com.hearthstead.entity.AttributeRuntime.boonAmount(level, settlement,
            com.hearthstead.entity.AttributeEffects.Boon.HARD_HANDS);
    }

    /** Energy-drain multiplier; below one means longer days. */
    public static float energyDrain(ServerLevel level, Settlement settlement) {
        return activeBoon(level, settlement) == Boon.LONG_DAYS ? 0.85F : 1.0F;
    }

    /** Attribute-growth multiplier. */
    public static float growth(ServerLevel level, Settlement settlement) {
        return activeBoon(level, settlement) == Boon.GOOD_COUNSEL ? 1.25F : 1.0F;
    }

    /** Chance a gathered unit yields one extra (Careful Work: 5..10% by the mayor's Dexterity). */
    public static float extraYieldChance(ServerLevel level, Settlement settlement) {
        return (float) com.hearthstead.entity.AttributeRuntime.boonAmount(level, settlement,
            com.hearthstead.entity.AttributeEffects.Boon.CAREFUL_WORK);
    }

    /** Morale-loss multiplier (Open Hearth: 0.80..0.90 by the mayor's Spirit). */
    public static float moraleDecay(ServerLevel level, Settlement settlement) {
        return 1.0F - (float) com.hearthstead.entity.AttributeRuntime.boonAmount(level, settlement,
            com.hearthstead.entity.AttributeEffects.Boon.OPEN_HEARTH);
    }

    private Mayor() {
    }
}
