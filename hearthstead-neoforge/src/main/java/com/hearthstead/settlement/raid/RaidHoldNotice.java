package com.hearthstead.settlement.raid;

import com.hearthstead.settlement.Settlement;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.ChatFormatting;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Tells players WHY a due raid is being held, instead of the old "silent
 * stall". Presentation only: it never changes a gate, never clears or
 * rerolls a plan, and never schedules anything. The tested rule that a
 * held plan stays queued for a later retry is untouched; this only makes the
 * wait audible.
 *
 * <p>Throttled per settlement so a gate re-checked once a second does not
 * flood chat: one notice per raid night and reason, plus one more when the
 * set of blockers changes, at most every {@link #RENOTIFY_TICKS}. The
 * throttle is transient server memory; after a restart players simply get
 * the (still true) notice once more.
 */
public final class RaidHoldNotice {

    /** Why a due raid did not advance. */
    public enum Reason {
        /** The first raid's warning night came, but readiness failed. */
        FIRST_WARNING_READINESS,
        /** The warned first raid's attack night came, but readiness failed. */
        FIRST_ATTACK_READINESS,
        /** The band found no standable, loaded ground outside the claim. */
        NO_FOOTING
    }

    /** Minimum gap before a changed blocker list is announced again (2 min). */
    public static final long RENOTIFY_TICKS = 2_400L;
    /** Blockers listed by name; the rest are counted. */
    public static final int MAX_LISTED_BLOCKERS = 3;

    /**
     * While a raid stays held, readiness is re-assessed at most this often
     * (20 s): the assessment walks the settlement and is not free.
     */
    public static final long RECHECK_TICKS = 400L;

    /** The last notice sent for one settlement. */
    public record Last(Reason reason, long night, int signature, long gameTime) {
    }

    /** The last hold assessment for one settlement. */
    public record Checked(long night, long gameTime) {
    }

    private static final class State {
        private final Map<UUID, Last> sent = new HashMap<>();
        private final Map<UUID, Checked> checked = new HashMap<>();
    }

    private static final Map<MinecraftServer, State> STATES = new WeakHashMap<>();

    private RaidHoldNotice() {
    }

    /**
     * Pure throttle decision. A new reason or a new night always speaks; a
     * changed blocker signature speaks again only after the renotify gap.
     */
    public static boolean shouldNotify(Last previous, Reason reason, long night,
                                       int signature, long gameTime) {
        if (previous == null || previous.reason() != reason
            || previous.night() != night) {
            return true;
        }
        return previous.signature() != signature
            && gameTime - previous.gameTime() >= RENOTIFY_TICKS;
    }

    /**
     * Pure pre-check before an expensive readiness assessment: always on a
     * new night, otherwise at most once per {@link #RECHECK_TICKS}.
     */
    public static boolean mayCheck(Checked previous, long night, long gameTime) {
        return previous == null || previous.night() != night
            || gameTime - previous.gameTime() >= RECHECK_TICKS;
    }

    /**
     * Claims one hold assessment for this settlement now, or returns false
     * when one ran too recently (see {@link #mayCheck}).
     */
    public static boolean beginCheck(ServerLevel level, Settlement settlement,
                                     long night) {
        if (level == null || settlement == null) {
            return false;
        }
        State state = state(level);
        long now = level.getGameTime();
        if (!mayCheck(state.checked.get(settlement.id), night, now)) {
            return false;
        }
        state.checked.put(settlement.id, new Checked(night, now));
        return true;
    }

    /** The last notice sent for this settlement, if any (tests/QA). */
    public static java.util.Optional<Last> lastNotice(ServerLevel level,
                                                      Settlement settlement) {
        if (level == null || settlement == null) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.ofNullable(state(level).sent.get(settlement.id));
    }

    /** Stable signature of a blocker list, for the throttle. */
    public static int signatureOf(List<FirstRaidReadinessService.Blocker> blockers) {
        int hash = 1;
        if (blockers != null) {
            for (FirstRaidReadinessService.Blocker blocker : blockers) {
                hash = 31 * hash + (blocker == null ? 0 : blocker.wireId() + 1);
            }
        }
        return hash;
    }

    /** Forgets the throttle once the raid advanced (warned, started, resolved). */
    public static void clear(ServerLevel level, Settlement settlement) {
        if (level != null && settlement != null) {
            State state;
            synchronized (STATES) {
                state = STATES.get(level.getServer());
            }
            if (state != null) {
                state.sent.remove(settlement.id);
                state.checked.remove(settlement.id);
            }
        }
    }

    /**
     * Announces that a due first-raid warning or attack is held by readiness
     * and names the blockers.
     *
     * @return true if a notice was actually sent this call
     */
    public static boolean readinessHeld(ServerLevel level, Settlement settlement,
                                        Reason reason, long night,
                                        FirstRaidReadinessService.Report report,
                                        long attackNight, String captainName) {
        if (level == null || settlement == null || report == null
            || report.ready()) {
            return false;
        }
        List<FirstRaidReadinessService.Blocker> blockers = report.blockers();
        if (!record(level, settlement, reason, night, signatureOf(blockers))) {
            return false;
        }
        Component header = reason == Reason.FIRST_ATTACK_READINESS
            && captainName != null
            ? Component.translatable("hearthstead.message.raid_hold_first_attack",
                attackNight, Component.literal(captainName), settlement.name)
            : Component.translatable("hearthstead.message.raid_hold_first_warning",
                settlement.name);
        RaidBroadcast.send(level, settlement, styled(header));
        int listed = Math.min(MAX_LISTED_BLOCKERS, blockers.size());
        for (int i = 0; i < listed; i++) {
            RaidBroadcast.send(level, settlement, Component.translatable(
                "hearthstead.message.raid_hold_blocker", Component.translatable(
                    "hearthstead.raid.readiness.blocker." + blockers.get(i).id())));
        }
        RaidBroadcast.send(level, settlement, blockers.size() > listed
            ? Component.translatable("hearthstead.message.raid_hold_more",
                blockers.size() - listed)
            : Component.translatable("hearthstead.message.raid_hold_check"));
        com.hearthstead.Hearthstead.LOGGER.info(
            "Raid held for {} ({} on night {}): {}", settlement.name, reason,
            night, blockers.stream().map(FirstRaidReadinessService.Blocker::id)
                .toList());
        return true;
    }

    /**
     * Announces that a due band could not form up outside the claim.
     *
     * @return true if a notice was actually sent this call
     */
    public static boolean noFooting(ServerLevel level, Settlement settlement,
                                    long night, long attackNight,
                                    String captainName) {
        if (level == null || settlement == null
            || !record(level, settlement, Reason.NO_FOOTING, night, 0)) {
            return false;
        }
        RaidBroadcast.send(level, settlement, styled(Component.translatable(
            "hearthstead.message.raid_hold_no_footing", attackNight,
            Component.literal(captainName == null ? "?" : captainName),
            settlement.name)));
        return true;
    }

    private static MutableComponent styled(Component header) {
        return header.copy().withStyle(ChatFormatting.GOLD);
    }

    private static boolean record(ServerLevel level, Settlement settlement,
                                  Reason reason, long night, int signature) {
        Map<UUID, Last> map = state(level).sent;
        long now = level.getGameTime();
        if (!shouldNotify(map.get(settlement.id), reason, night, signature, now)) {
            return false;
        }
        map.put(settlement.id, new Last(reason, night, signature, now));
        return true;
    }

    private static State state(ServerLevel level) {
        synchronized (STATES) {
            return STATES.computeIfAbsent(level.getServer(), ignored -> new State());
        }
    }
}
