package com.hearthstead.saga;

import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.raid.RaidBroadcast;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidObjective;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;

import java.util.UUID;

/**
 * The settlement's cast of named enemies -- SAGA v1's slice of DESIGN.md
 * system 4 ("enemy factions field 3-5 named captains each ... generated
 * names/scars, learnable strengths, they grow on victories, lieutenants
 * inherit hatred when captains fall").
 *
 * <p>Kept thin like {@link RaidDirector} itself: this class only decides
 * WHEN a roster is generated and WHAT a raid's outcome means for it. The
 * identity itself lives on {@link Captain}; the menace/approach/win-loss
 * mechanics it decorates stay entirely on {@link RaidCaptain}, untouched.
 *
 * <p>v1 tightens the design doc's "3-5" down to exactly {@value #MAX_ROSTER}
 * -- bounded, so a long campaign accumulates a small cast of names a player
 * can actually learn to dread, never a crowd.
 */
public final class CaptainRoster {

    /** The named cast size. Bounded on purpose -- see the class doc. */
    public static final int MAX_ROSTER = 3;

    private CaptainRoster() {
    }

    /** The settlement's own seed for its roster: stable for its lifetime,
     * so calling this twice on the same settlement id always proposes the
     * same names in the same order. */
    private static long seedFor(UUID settlementId) {
        return settlementId.getMostSignificantBits() ^ settlementId.getLeastSignificantBits();
    }

    /**
     * Generates the settlement's named cast the first time it is worth
     * generating one for -- "at first raid pressure" (the task's phrasing):
     * a hamlet nobody will ever raid never gets named enemies for no
     * reason, matching how {@link com.hearthstead.settlement.raid.RaidTelegraph}
     * and {@link com.hearthstead.settlement.raid.RaidPressure#rollForNight}
     * both gate on {@link com.hearthstead.settlement.raid.RaidPressure#worthRaiding}.
     *
     * <p>Idempotent and cheap -- one list-emptiness check -- so it is safe
     * to call from every tick, exactly like {@code RaidPressure#rollForNight}
     * is safe to call redundantly.
     *
     * <p>The names are deterministic from the settlement's own id: a fixed
     * {@link RandomSource} seeded from it drives every name pick, so the
     * same settlement always proposes the same three names in the same
     * order, however many times its world is loaded. The paired
     * {@code RaidCaptain} identities still draw their own (unused for
     * display) name/byname and id from the live {@code random} passed in --
     * only WHICH THREE NAMES THE PLAYER READS needs to be reproducible.
     */
    public static void ensureRoster(Settlement settlement, RandomSource random) {
        if (!settlement.sagaRoster.isEmpty()) {
            return; // already generated once; a roster is a cast, not reshuffled
        }
        if (!com.hearthstead.settlement.raid.RaidPressure.worthRaiding(settlement)) {
            return; // nothing here is worth naming enemies for yet
        }
        RandomSource nameRandom = RandomSource.create(seedFor(settlement.id));
        for (int i = 0; i < MAX_ROSTER; i++) {
            RaidCaptain base = RaidCaptain.generate(random);
            Captain saga = Captain.generate(nameRandom, base.id());
            settlement.raidCaptains.add(base);
            settlement.sagaRoster.add(saga);
        }
        while (settlement.raidCaptains.size() > RaidDirector.MAX_REMEMBERED_CAPTAINS) {
            settlement.raidCaptains.remove(0);
        }
    }

    /** The Saga identity for this RaidCaptain id, or null if it is a "wild"
     * captain outside the tracked three (the gallery can hold up to
     * {@link RaidDirector#MAX_REMEMBERED_CAPTAINS}, more than Saga names). */
    public static Captain find(Settlement settlement, UUID captainId) {
        if (captainId == null) {
            return null;
        }
        for (Captain c : settlement.sagaRoster) {
            if (c.id().equals(captainId)) {
                return c;
            }
        }
        return null;
    }

    /**
     * Resolves everything Saga cares about for one just-finished raid.
     * Called once, from {@code RaidDirector#recordAftermath}, after that
     * night's base {@code RaidPressure}/{@code RaidCaptain} bookkeeping
     * (victory/defeat, approach) is already final -- this only adds the
     * narrative layer on top.
     *
     * @param captainSlain whether the raider wearing the captain flag was
     *                     personally killed this raid (as opposed to the
     *                     band merely being driven off) -- see
     *                     {@code RaiderEntity#die}
     * @return the name this raid should be remembered under: the roster
     *         entry's earned display name if one exists, else the bare
     *         {@code RaidCaptain} name, so a captain outside Saga's tracked
     *         three still reports honestly instead of reading "null"
     */
    public static String recordRaidOutcome(ServerLevel level, Settlement settlement,
                                           RaidCaptain raidCaptain, RaidObjective objective,
                                           boolean held, boolean captainSlain,
                                           RandomSource random) {
        if (raidCaptain == null) {
            return "?";
        }
        Captain saga = find(settlement, raidCaptain.id());
        if (saga == null) {
            return raidCaptain.name();
        }
        if (captainSlain) {
            // Reported under the name they carried into this raid -- they
            // do not live to grow from it.
            String fallenAs = saga.displayName();
            succeed(level, settlement, saga, random);
            return fallenAs;
        }
        if (!held && saga.earnEpithetFrom(objective, raidCaptain.victories())) {
            // Growth lands before the return, so THIS raid's own report
            // already reads under the name it just earned.
            RaidBroadcast.send(level, settlement, Component.translatable(
                "hearthstead.message.raid_captain_named", saga.firstName(), saga.displayName()));
        }
        return saga.displayName();
    }

    /**
     * A captain killed in the field is gone permanently -- removed from
     * both the named roster and the underlying enemy gallery, so
     * {@code RaidDirector#pickCaptain} can never send a dead captain out
     * again. A fresh lieutenant takes their slot at once, carrying the
     * grudge marker in their own name (v1 flavor only, per the task).
     */
    private static void succeed(ServerLevel level, Settlement settlement,
                                Captain fallen, RandomSource random) {
        settlement.sagaRoster.removeIf(c -> c.id().equals(fallen.id()));
        settlement.raidCaptains.removeIf(c -> c.id().equals(fallen.id()));
        RaidCaptain freshBase = RaidCaptain.generate(random);
        RandomSource nameRandom = RandomSource.create(
            seedFor(settlement.id) ^ freshBase.id().getLeastSignificantBits());
        Captain lieutenant = Captain.lieutenantOf(nameRandom, freshBase.id(), fallen.firstName());
        settlement.raidCaptains.add(freshBase);
        settlement.sagaRoster.add(lieutenant);
        while (settlement.raidCaptains.size() > RaidDirector.MAX_REMEMBERED_CAPTAINS) {
            settlement.raidCaptains.remove(0);
        }
        RaidBroadcast.send(level, settlement, Component.translatable(
            "hearthstead.message.raid_captain_fallen",
            fallen.displayName(), lieutenant.displayName()));
    }
}
