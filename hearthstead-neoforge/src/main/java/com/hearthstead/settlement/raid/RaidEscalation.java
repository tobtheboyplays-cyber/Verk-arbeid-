package com.hearthstead.settlement.raid;

import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.state.FirstRaidState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * Raid escalation curve (owner request 26 Sep: "brutes are hard to beat.
 * Maybe have bandits early in the game, then it increases"). The band's
 * make-up follows the raid number AND a settlement strength score, so a town
 * that lost its Guards is not hit by the maximum band.
 *
 * <pre>
 *   raid 1     3-4 bandits (a bandit captain leads)
 *   raid 2-3   bandits + 1-2 skirmishers
 *   raid 4-5   skirmishers + bandits, 1 brute only with 3+ fighters
 *   raid 6+    mixed bands, 1-3 brutes as the town grows
 * </pre>
 *
 * The strength score is the fighters' weighted gear (a bare-handed defender
 * counts little, a diamond sword much) plus a small, capped bonus for days
 * since founding. Pure arithmetic lives in {@link #compose}; {@link #strength}
 * is the only world read.
 */
public final class RaidEscalation {
    /** The authored first raid: a bandit captain and three bandits. */
    public static final int FIRST_RAID_BANDITS = 4;
    /** Raids up to this number are outlaw bands (bandits lead). */
    public static final int LAST_OUTLAW_RAID = 3;
    /** From this raid on a Brute may join, and only with this many fighters. */
    public static final int FIRST_BRUTE_RAID = 4;
    public static final int BRUTE_MIN_FIGHTERS = 3;
    /** From this raid on Brutes are regular. */
    public static final int REGULAR_BRUTE_RAID = 6;
    /** Days since founding add at most this much strength (one point per 8 days). */
    public static final double MAX_DAY_STRENGTH = 2.0D;

    private RaidEscalation() {
    }

    /** One band's make-up; slot 0 (the captain) is {@link #captain}. */
    public record Band(RaiderEntity.Variant captain, int bandits, int skirmishers, int brutes) {
        public int size() {
            return 1 + bandits + skirmishers + brutes;
        }

        /** Slot order: captain, brutes, skirmishers, bandits. */
        public List<RaiderEntity.Variant> slots() {
            List<RaiderEntity.Variant> slots = new ArrayList<>(size());
            slots.add(captain);
            for (int i = 0; i < brutes; i++) slots.add(RaiderEntity.Variant.BRUTE);
            for (int i = 0; i < skirmishers; i++) slots.add(RaiderEntity.Variant.SKIRMISHER);
            for (int i = 0; i < bandits; i++) slots.add(RaiderEntity.Variant.BANDIT);
            return slots;
        }
    }

    /** A strength reading: fighters (martial settlers with a weapon) and the score. */
    public record Strength(int fighters, double score) {
    }

    /**
     * The overall number of the raid being planned or spawned: the authored
     * first raid is 1; recurring serial k is raid k+1.
     */
    public static int raidNumber(Settlement settlement) {
        if (settlement.raidLifecycle.firstState() != FirstRaidState.COMPLETED) {
            return 1;
        }
        long serial = settlement.recurringRaidRun.activeSerial() > 0L
            ? settlement.recurringRaidRun.activeSerial()
            : settlement.recurringRaidRun.lastResolvedSerial() + 1L;
        return (int) Math.min(Integer.MAX_VALUE, serial + 1L);
    }

    /** Whether the settlement's next or current raid is still an outlaw band. */
    public static boolean isOutlawBand(Settlement settlement) {
        return raidNumber(settlement) <= LAST_OUTLAW_RAID;
    }

    /** Whole days since the founding night, or 0 when unknown. */
    public static long daysSinceFounding(ServerLevel level, Settlement settlement) {
        long founded = settlement.raidLifecycle.foundedNight();
        long today = level.getDayTime() / 24_000L;
        return founded < 0L ? 0L : Math.max(0L, today - founded);
    }

    /** Gear weight of one defender, from its main-hand weapon. */
    public static double gearWeight(ItemStack weapon) {
        if (weapon == null || weapon.isEmpty()) return 0.3D;
        if (weapon.is(Items.NETHERITE_SWORD) || weapon.is(Items.NETHERITE_AXE)) return 1.6D;
        if (weapon.is(Items.DIAMOND_SWORD) || weapon.is(Items.DIAMOND_AXE)) return 1.35D;
        if (weapon.is(Items.WOODEN_SWORD) || weapon.is(Items.WOODEN_AXE)) return 0.6D;
        if (weapon.is(Items.STONE_SWORD) || weapon.is(Items.STONE_AXE)) return 0.8D;
        return 1.0D; // iron, bows and the mod's own weapons
    }

    /** The one world read: loaded martial settlers and their gear, plus days. */
    public static Strength strength(ServerLevel level, Settlement settlement) {
        int fighters = 0;
        double power = 0.0D;
        for (SettlerEntity member : SettlementManager.loadedMembers(level, settlement)) {
            if (!member.isAlive() || !member.getProfession().martial()) continue;
            fighters++;
            power += gearWeight(member.getItemBySlot(EquipmentSlot.MAINHAND));
        }
        double days = Math.min(MAX_DAY_STRENGTH, daysSinceFounding(level, settlement) / 8.0D);
        return new Strength(fighters, power + days);
    }

    /**
     * The band for raid {@code raidNumber} against a settlement of this
     * strength. {@code bruteCaptainRoll} in [0,1) decides a Skirmisher or
     * Brute captain from raid {@link #REGULAR_BRUTE_RAID} on.
     */
    public static Band compose(int raidNumber, Strength strength, double bruteCaptainRoll) {
        int n = Math.max(1, raidNumber);
        double s = Math.max(0.0D, strength.score());
        int fighters = Math.max(0, strength.fighters());
        if (n == 1) {
            return new Band(RaiderEntity.Variant.BANDIT, FIRST_RAID_BANDITS - 1, 0, 0);
        }
        if (n <= LAST_OUTLAW_RAID) {
            int bandits = s >= 3.0D ? 3 : 2;
            int light = fighters == 0 ? 0 : s >= 3.0D ? 2 : 1;
            return new Band(RaiderEntity.Variant.BANDIT, bandits, light, 0);
        }
        if (n < REGULAR_BRUTE_RAID) {
            int light = fighters == 0 ? 1 : s >= 4.0D ? 3 : 2;
            int brutes = fighters >= BRUTE_MIN_FIGHTERS ? 1 : 0;
            return new Band(RaiderEntity.Variant.SKIRMISHER, 2, light, brutes);
        }
        // Raid 6+: mixed bands; more Brutes as the town grows, none against a
        // town without fighters.
        int brutes = fighters == 0 ? 0
            : Mth.clamp(1 + (s >= 6.0D ? 1 : 0) + (s >= 9.0D ? 1 : 0), 1, 3);
        int light = fighters == 0 ? 1 : Mth.clamp(2 + (int) (s / 4.0D), 2, 4);
        int bandits = s < 4.0D ? 2 : 1;
        RaiderEntity.Variant captain = fighters >= BRUTE_MIN_FIGHTERS && bruteCaptainRoll < 0.4D
            ? RaiderEntity.Variant.BRUTE : RaiderEntity.Variant.SKIRMISHER;
        Band band = new Band(captain, bandits, light, brutes);
        // Hard cap: a raid is a band with a leader, never a horde.
        while (band.size() > RaidDirector.MAX_BAND) {
            if (band.bandits() > 0) band = new Band(band.captain(), band.bandits() - 1, band.skirmishers(), band.brutes());
            else band = new Band(band.captain(), 0, band.skirmishers() - 1, band.brutes());
        }
        return band;
    }

    /**
     * Whether the live raid's band is broken: at least half of its sealed
     * participants have reached a terminal end (killed or removed). Read-only;
     * used by the bandits' morale (combat lane).
     */
    public static boolean bandBroken(Settlement settlement) {
        if (settlement == null) return false;
        if (settlement.raidLifecycle.isAuthoredFirstRaidActive()) {
            int total = settlement.raidLifecycle.participants().size();
            int down = settlement.raidLifecycle.terminalParticipants().size();
            return total > 0 && down * 2 >= total;
        }
        if (settlement.recurringRaidRun.isActive()) {
            int total = settlement.recurringRaidRun.participants().size();
            int down = settlement.recurringRaidRun.terminalParticipants().size();
            return total > 0 && down * 2 >= total;
        }
        return false;
    }
}
