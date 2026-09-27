package com.hearthstead.settlement.state;

import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.raid.RaidCadence;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Versioned, server-authoritative state for the first authored raid.
 *
 * <p>The authored first raid is runtime-authoritative here. The legacy
 * {@code Settlement.pendingRaid} field remains a compatibility mirror for
 * existing objective goals and recurring raids; v0 migration copies that one
 * old plan into {@link #activePlan} only to preserve state, and permanently
 * disarms its reward eligibility.
 */
public final class RaidLifecycle {
    public static final long DAY_LENGTH = 24_000L;
    public static final long UNSET_NIGHT = -1L;
    public static final int FIRST_ATTACK_MIN_OFFSET = 4;
    public static final int FIRST_ATTACK_MAX_OFFSET = 7;
    public static final int MAX_PARTICIPANTS = 9;
    public static final int PARTICIPANT_ROSTER_SCHEMA = 1;
    /** B03/B04 recurring cadence persistence; never infer an unknown shape. */
    public static final int RECURRING_SCHEDULE_SCHEMA = 1;
    public static final long RECURRING_VICTORY_COOLDOWN_TICKS = 24_000L;
    public static final long RECURRING_DEFEAT_COOLDOWN_TICKS = 48_000L;
    public static final long RECURRING_WARNING_MIN_LEAD_TICKS = 6_000L;
    public static final int RECURRING_FORCE_AFTER_QUIET_ROLLS = 3;
    /**
     * Regular recurring cadence (owner decision 25 Sep, revised: "a raid
     * every 3-4 in-game days after the first raid").
     *
     * <p>Once a raid resolves on raid night {@code R} (the night whose dusk
     * most recently passed, see {@link #raidNightOf}), one interval {@code I}
     * is drawn from the server-config window (default 3..4 days, see
     * {@link RaidCadence}) and persisted. The next attack is GUARANTEED on
     * night {@code R + I}, whatever the outcome. The warning is committed
     * exactly one dusk earlier ({@code attack - 1}), so players always get
     * one full day/night to prepare. The old random dusk roll is kept only as
     * pressure bookkeeping; it no longer decides whether a raid comes. A save
     * with no cadence yet ({@link #UNSET_NIGHT}) is due at its next eligible
     * dusk, which is exactly what the old forced roll produced. The existing
     * worth threshold (RaidPressure.worthRaiding) and the nearby-player gate
     * still apply: missed nights are never replayed, so only a blocked raid
     * can come later than the window's last day.
     */
    public static final int RECURRING_CADENCE_SCHEMA = 2;
    /** Dusk day-time (matches RaidDirector.ROLL_AT_DAYTIME). */
    public static final long DUSK_DAYTIME = 13_000L;
    /**
     * Sleep-proof server-time floor between a cadence resolution and the next
     * warning. The night arithmetic above is the real cadence; this only stops
     * a same-night re-warning if day-time is manipulated.
     */
    public static final long RECURRING_CADENCE_MIN_RECOVERY_TICKS = 6_000L;
    /** A recurring raid stays live at least this much day-time before a dawn retreat may close it. */
    public static final long RECURRING_MIN_RAID_DAYTIME = 6_000L;
    /** Bounded memory of raider UUIDs sent home at dawn (late-loading stragglers). */
    public static final int MAX_RETREATED_RAIDERS = MAX_PARTICIPANTS * 2;
    /** Hard ceiling on the persisted survived counter (escalation caps far lower). */
    public static final int MAX_RAIDS_SURVIVED = 10_000;

    /** How one raid ended for cadence/escalation purposes. */
    public enum CadenceOutcome {
        /** Objective denied: +1 survived raid. */
        HELD,
        /** Objective taken: breather (-1 level next raid). */
        LOST,
        /** Stragglers fled at dawn: neutral -- no reward, no defeat penalty. */
        RETREATED
    }

    /**
     * Calendar provenance, rather than today's mutable profile, validates an
     * authored first-raid date after reload. Missing provenance is the
     * deployed 4..7-night contract and must never be reinterpreted as B02.
     */
    private enum CalendarProvenance {
        LEGACY(0, FIRST_ATTACK_MIN_OFFSET, FIRST_ATTACK_MAX_OFFSET),
        BALANCED_B02(1, 2, 3);

        private final int wireId;
        private final int minOffset;
        private final int maxOffset;

        CalendarProvenance(int wireId, int minOffset, int maxOffset) {
            this.wireId = wireId;
            this.minOffset = minOffset;
            this.maxOffset = maxOffset;
        }

        private boolean acceptsOffset(long offset) {
            return offset >= minOffset && offset <= maxOffset;
        }

        private boolean acceptsWarningLead(int warningLead) {
            return this == BALANCED_B02
                    ? warningLead == 1
                    : warningLead >= 1 && warningLead <= 2;
        }

        private static CalendarProvenance forNewFounding(RaidProfile profile) {
            return profile == RaidProfile.BALANCED ? BALANCED_B02 : LEGACY;
        }

        private static Optional<CalendarProvenance> tryFromWireId(int wireId) {
            for (CalendarProvenance provenance : values()) {
                if (provenance.wireId == wireId) {
                    return Optional.of(provenance);
                }
            }
            return Optional.empty();
        }
    }

    private long foundedNight = UNSET_NIGHT;
    /** One founding-only provenance-validated roll; never rerolled at readiness. */
    private long rolledNotBeforeNight = UNSET_NIGHT;
    /** Persisted one- or two-night warning lead selected by the raid profile. */
    private int warningLead;
    /** Exact versioned origin for the persisted founding floor. */
    private CalendarProvenance calendarProvenance = CalendarProvenance.LEGACY;
    /** The exact night on which the player committed the readiness checklist. */
    private long readinessNight = UNSET_NIGHT;
    /** Separate timer authority: never stands in for player readiness evidence. */
    private int firstTimerDays;
    private long firstTimerCommittedNight = UNSET_NIGHT;
    private boolean firstTimerWarningPresented;
    private boolean firstTimerSunsetPresented;
    private int firstTimerBandSize;
    private long firstTimerRetreatAtGameTime = -1L;
    public static final long FIRST_TIMER_RAID_BUDGET_TICKS = 12_000L;
    private long firstAttackNight = UNSET_NIGHT;
    private long firstWarningNight = UNSET_NIGHT;
    private FirstRaidState firstState = FirstRaidState.UNINITIALIZED;
    @Nullable
    private RaidPlan queuedPlan;
    @Nullable
    private RaidPlan activePlan;
    /** Actual spawned raider entity UUIDs, in capture order (never players). */
    private final LinkedHashSet<UUID> participants = new LinkedHashSet<>();
    /** Optional exact role/captain companion for the sealed UUID ledger. */
    private final LinkedHashMap<UUID, RaidParticipantRecord> participantRoster =
        new LinkedHashMap<>();
    /**
     * Participants that reached a definitive terminal state. This is a
     * subset of {@link #participants}: death and explicit destruction count;
     * a chunk unload never does. Keeping this bounded ledger in the
     * settlement save is what prevents an unloaded band from becoming a
     * false victory after an empty local entity query.
     */
    private final LinkedHashSet<UUID> terminalParticipants = new LinkedHashSet<>();
    /** True only after the 1-9 UUID capture has been explicitly sealed. */
    private boolean participantsTracked;
    /** True only when every participant has one validated persisted record. */
    private boolean participantRosterTracked;
    /** Zero is the pre-roster save shape; it is preserved without guessing. */
    private int participantRosterSchema = PARTICIPANT_ROSTER_SCHEMA;
    private boolean rewardEligible;
    private boolean integrityLost;
    /**
     * Exact terminal truth captured by the authored resolution transaction.
     * It deliberately duplicates the immutable plan and Aftermath row: a
     * restart may repair FJ-610 only when all three persisted authorities
     * still agree byte-for-byte, never by re-reading today's population.
     */
    @Nullable
    private FirstRaidTerminal firstRaidTerminal;
    /**
     * Provenance for the one deliberately unauditable v0 ACTIVE bridge.
     *
     * <p>This must never be inferred merely from damaged authored dates:
     * doing so would let a corrupt v1+ first raid fall back to the legacy
     * loaded-AABB completion path and become a false victory while a tracked
     * participant is only unloaded. The marker is written only by
     * {@link #migrateV0}; {@link #normalize()} accepts it only alongside the
     * exact empty-ledger, all-dates-unset v0 shape.
     */
    private boolean legacyBridge;

    /**
     * B03/B04 keeps recurring scheduling separate from RecurringRaidRun:
     * that run remains the sole owner of an issued plan, serial and roster.
     */
    private int recurringScheduleSchema = RECURRING_SCHEDULE_SCHEMA;
    private long recurringCooldownUntilGameTime;
    private int recurringQuietEligibleRolls;
    @Nullable
    private RaidPlan recurringWarnedPlan;
    private long recurringWarningGameTime = -1L;
    private long recurringWarningNight = UNSET_NIGHT;
    private boolean recurringScheduleBlocked;
    /**
     * Cadence/escalation state (added 25 Sep). Every field has a neutral
     * default and is read independently, so saves from before the cadence
     * load unchanged and are never marked corrupt.
     */
    private long recurringNextWarningNight = UNSET_NIGHT;
    /** Raid night the current cadence counts from; UNSET before the first resolution. */
    private long recurringLastRaidNight = UNSET_NIGHT;
    /** The persisted day pick for the current cadence; 0 when none is scheduled. */
    private int recurringIntervalDays;
    private int raidsSurvived;
    private boolean raidBreather;
    private long recurringRetreatAtDayTime = -1L;
    private final LinkedHashSet<UUID> retreatedRaiders = new LinkedHashSet<>();

    public long foundedNight() {
        return foundedNight;
    }

    public long firstAttackNight() {
        return firstAttackNight;
    }

    public long rolledNotBeforeNight() {
        return rolledNotBeforeNight;
    }

    public int warningLead() {
        return warningLead;
    }

    public long readinessNight() {
        return readinessNight;
    }

    public long firstWarningNight() {
        return firstWarningNight;
    }

    public FirstRaidState firstState() {
        return firstState;
    }

    public Optional<RaidPlan> queuedPlan() {
        return Optional.ofNullable(queuedPlan);
    }

    public Optional<RaidPlan> activePlan() {
        return Optional.ofNullable(activePlan);
    }

    public boolean recurringScheduleBlocked() {
        return recurringScheduleBlocked;
    }

    public boolean recurringCoolingDown(long gameTime) {
        return gameTime < recurringCooldownUntilGameTime;
    }

    public long recurringCooldownUntilGameTime() {
        return recurringCooldownUntilGameTime;
    }

    public int recurringQuietEligibleRolls() {
        return recurringQuietEligibleRolls;
    }

    public Optional<RaidPlan> recurringWarnedPlan() {
        return Optional.ofNullable(recurringWarnedPlan);
    }

    public long recurringWarningGameTime() {
        return recurringWarningGameTime;
    }

    public long recurringWarningNight() {
        return recurringWarningNight;
    }

    /** First dusk on which the cadence may commit the next warning; UNSET = next eligible dusk. */
    public long recurringNextWarningNight() {
        return recurringNextWarningNight;
    }

    /** The guaranteed attack night of the cadence, or UNSET before the first cadence resolution. */
    public long recurringNextAttackNight() {
        return recurringNextWarningNight == UNSET_NIGHT ? UNSET_NIGHT
            : recurringNextWarningNight + 1L;
    }

    /** Raid night the current cadence counts from, or UNSET. */
    public long recurringLastRaidNight() {
        return recurringLastRaidNight;
    }

    /** Days between the last raid and the scheduled next attack, or 0 if unknown. */
    public int recurringIntervalDays() {
        return recurringIntervalDays;
    }

    public int raidsSurvived() {
        return raidsSurvived;
    }

    public boolean raidBreather() {
        return raidBreather;
    }

    /**
     * MineColonies-style raid level: raids held so far, lowered by one for
     * the single raid right after a loss (the breather).
     */
    public int raidLevel() {
        return Math.max(0, raidsSurvived - (raidBreather ? 1 : 0));
    }

    public long recurringRetreatAtDayTime() {
        return recurringRetreatAtDayTime;
    }

    public boolean isRetreatedRaider(UUID id) {
        return id != null && retreatedRaiders.contains(id);
    }

    public Set<UUID> retreatedRaiders() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(retreatedRaiders));
    }

    /** The raid night a day-time belongs to: the night whose dusk most recently passed. */
    public static long raidNightOf(long dayTime) {
        return Math.floorDiv(dayTime - DUSK_DAYTIME, DAY_LENGTH);
    }

    /** Pure cadence arithmetic: the warning dusk that follows a raid on {@code raidNight}. */
    public static long cadenceWarningNightAfter(long raidNight, int intervalDays) {
        return RaidCadence.warningNightAfter(raidNight, intervalDays);
    }

    /** Whether the cadence allows a warning at the dusk of {@code night}. */
    public boolean recurringCadenceDue(long night) {
        return recurringNextWarningNight == UNSET_NIGHT
            || night >= recurringNextWarningNight;
    }

    /**
     * Records one authoritative raid resolution for the regular cadence and
     * escalation. Replaces the gameTime-only {@link #recordRecurringRecovery}
     * for live resolutions; that method stays for QA fixtures/old tests.
     *
     * @param intervalDays the day pick drawn ONCE by the caller from the
     *                     configured window ({@link RaidCadence#pickIntervalDays});
     *                     it is persisted, so a reload never rerolls it
     */
    public void recordRecurringCadence(long gameTime, long resolvedDayTime,
                                       CadenceOutcome outcome, int intervalDays) {
        if (outcome == null) {
            return;
        }
        switch (outcome) {
            case HELD -> {
                if (raidsSurvived < MAX_RAIDS_SURVIVED) {
                    raidsSurvived++;
                }
                raidBreather = false;
            }
            case LOST -> raidBreather = true;
            case RETREATED -> raidBreather = false;
        }
        recurringRetreatAtDayTime = -1L;
        if (recurringScheduleBlocked || firstState != FirstRaidState.COMPLETED
            || gameTime < 0L || resolvedDayTime < 0L) {
            return;
        }
        if (gameTime > Long.MAX_VALUE - RECURRING_CADENCE_MIN_RECOVERY_TICKS
            || resolvedDayTime > Long.MAX_VALUE
                - (RaidCadence.MAX_ALLOWED_DAYS + 1L) * DAY_LENGTH) {
            blockRecurringSchedule();
            return;
        }
        int interval = RaidCadence.clampDays(intervalDays);
        long raidNight = Math.max(0L, raidNightOf(resolvedDayTime));
        recurringLastRaidNight = raidNight;
        recurringIntervalDays = interval;
        recurringNextWarningNight = cadenceWarningNightAfter(raidNight, interval);
        recurringCooldownUntilGameTime = gameTime + RECURRING_CADENCE_MIN_RECOVERY_TICKS;
        recurringQuietEligibleRolls = 0;
        clearRecurringWarning();
    }

    /**
     * Arms the dawn retreat for a raid that just arrived: the first dawn
     * after its raid night, but never sooner than
     * {@link #RECURRING_MIN_RAID_DAYTIME} after arrival. Despite the name it
     * is shared by the authored first raid since 26 Sep; only one raid is
     * ever live, and every resolution clears it.
     */
    public void armRecurringRetreat(long startDayTime) {
        if (startDayTime < 0L || startDayTime > Long.MAX_VALUE - 4L * DAY_LENGTH) {
            recurringRetreatAtDayTime = -1L;
            return;
        }
        long dawnAfter = (raidNightOf(startDayTime) + 1L) * DAY_LENGTH;
        recurringRetreatAtDayTime = Math.max(dawnAfter,
            startDayTime + RECURRING_MIN_RAID_DAYTIME);
    }

    public boolean recurringRetreatDue(long dayTime) {
        return recurringRetreatAtDayTime >= 0L && dayTime >= recurringRetreatAtDayTime;
    }

    /**
     * Dawn retreat of the authored first raid (soft-lock guard): records
     * every captured participant that has not reached a definitive end as
     * terminal, so the ledger can close, and disarms the reward. A capture
     * that never sealed can only exist after integrity loss (it is otherwise
     * sealed in the same tick it is recorded); it is closed as it stands
     * rather than holding the raid open forever.
     *
     * @return the stragglers the retreat closed (possibly empty), or null
     *         when there is no authored first raid with known participants
     */
    @Nullable
    public List<UUID> retreatFirstRaid() {
        if (!isAuthoredFirstRaidActive() || participants.isEmpty()
            || participants.size() > MAX_PARTICIPANTS) {
            return null;
        }
        List<UUID> stragglers = new java.util.ArrayList<>();
        for (UUID id : participants) {
            if (!terminalParticipants.contains(id)) {
                stragglers.add(id);
            }
        }
        if (!participantsTracked) {
            integrityLost = true;
            participantsTracked = true;
        }
        terminalParticipants.addAll(stragglers);
        rewardEligible = false;
        return stragglers;
    }

    /** Remembers stragglers of a retreated raid so their late death is never evidence for a newer raid. */
    public void rememberRetreatedRaiders(java.util.Collection<UUID> ids) {
        if (ids == null) {
            return;
        }
        for (UUID id : ids) {
            if (id != null) {
                retreatedRaiders.remove(id);
                retreatedRaiders.add(id);
            }
        }
        while (retreatedRaiders.size() > MAX_RETREATED_RAIDERS) {
            java.util.Iterator<UUID> oldest = retreatedRaiders.iterator();
            oldest.next();
            oldest.remove();
        }
    }

    /** Test seam: overwrite the escalation counters without a real raid. */
    public void setEscalationForTesting(int survived, boolean breather) {
        raidsSurvived = Math.max(0, Math.min(MAX_RAIDS_SURVIVED, survived));
        raidBreather = breather;
    }

    /**
     * Records the recovery interval only after an authoritative resolution.
     * World day-time is deliberately absent: sleep cannot shorten this timer.
     */
    public void recordRecurringRecovery(long gameTime, boolean held) {
        if (recurringScheduleBlocked || firstState != FirstRaidState.COMPLETED
            || gameTime < 0L) {
            return;
        }
        long duration = held ? RECURRING_VICTORY_COOLDOWN_TICKS
            : RECURRING_DEFEAT_COOLDOWN_TICKS;
        if (gameTime > Long.MAX_VALUE - duration) {
            blockRecurringSchedule();
            return;
        }
        recurringCooldownUntilGameTime = gameTime + duration;
        recurringQuietEligibleRolls = 0;
        clearRecurringWarning();
    }

    /**
     * One eligible quiet nightly roll. The third quiet result commits the
     * same warning/plan path as an ordinary successful roll.
     */
    public boolean recordRecurringQuietEligibleRoll(long gameTime) {
        if (!mayRollRecurring(gameTime) || recurringWarnedPlan != null) {
            return false;
        }
        if (recurringQuietEligibleRolls < RECURRING_FORCE_AFTER_QUIET_ROLLS) {
            recurringQuietEligibleRolls++;
        }
        return recurringQuietEligibleRolls >= RECURRING_FORCE_AFTER_QUIET_ROLLS;
    }

    public boolean mayRollRecurring(long gameTime) {
        return !recurringScheduleBlocked && firstState == FirstRaidState.COMPLETED
            && gameTime >= 0L && !recurringCoolingDown(gameTime)
            && recurringWarnedPlan == null;
    }

    /** Persists the exact already-warned plan before any arrival/roster work. */
    public boolean commitRecurringWarning(RaidPlan plan, long warningGameTime,
                                          long warningNight) {
        if (!mayRollRecurring(warningGameTime) || !planValid(plan)
            || warningNight < 0L || warningGameTime < 0L
            || warningGameTime > Long.MAX_VALUE - RECURRING_WARNING_MIN_LEAD_TICKS
            || plan.night() <= warningNight) {
            return false;
        }
        recurringWarnedPlan = plan;
        recurringWarningGameTime = warningGameTime;
        recurringWarningNight = warningNight;
        recurringQuietEligibleRolls = 0;
        return true;
    }

    public boolean recurringWarningDue(long gameTime, long currentNight) {
        return !recurringScheduleBlocked && recurringWarnedPlan != null
            && recurringWarningGameTime >= 0L
            && recurringWarningGameTime
                <= Long.MAX_VALUE - RECURRING_WARNING_MIN_LEAD_TICKS
            && gameTime >= recurringWarningGameTime
                + RECURRING_WARNING_MIN_LEAD_TICKS
            && currentNight > recurringWarningNight
            // The persisted plan's authored arrival night is authoritative.
            // A long-lead warning must not arrive on the first later night.
            && currentNight >= recurringWarnedPlan.night();
    }

    /**
     * Hands the exact warned plan to the existing RecurringRaidRun only after
     * that run has accepted it. A rejected queue leaves the warning intact.
     */
    public boolean consumeRecurringWarning(RaidPlan plan) {
        if (recurringScheduleBlocked || plan == null
            || !plan.equals(recurringWarnedPlan)) {
            return false;
        }
        clearRecurringWarning();
        return true;
    }

    public void blockRecurringSchedule() {
        recurringScheduleBlocked = true;
        recurringQuietEligibleRolls = 0;
        clearRecurringWarning();
    }

    private void clearRecurringWarning() {
        recurringWarnedPlan = null;
        recurringWarningGameTime = -1L;
        recurringWarningNight = UNSET_NIGHT;
    }

    /** Allocation-free hot-path check for the compatibility plan mirror. */
    public boolean activePlanMatches(@Nullable RaidPlan expected) {
        return planValid(activePlan) && activePlan.equals(expected);
    }

    public Set<UUID> participants() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(participants));
    }

    public Set<UUID> terminalParticipants() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(terminalParticipants));
    }

    public List<RaidParticipantRecord> participantRoster() {
        return List.copyOf(participantRoster.values());
    }

    public boolean participantRosterTracked() {
        return participantRosterTracked;
    }

    public boolean participantsTracked() {
        return participantsTracked;
    }

    /** True only after all lifecycle invariants have passed normalization. */
    public boolean rewardEligible() {
        return rewardEligible;
    }

    public boolean integrityLost() {
        return integrityLost;
    }

    /**
     * Whether an ACTIVE first raid must resolve through its persisted
     * terminal ledger rather than the old loaded-entity query.
     *
     * <p>Invalid dates and integrity loss deliberately do not make this
     * false. They disarm the reward, but the known participant evidence is
     * still the only safe completion authority. If that evidence is itself
     * missing, {@link #allParticipantsTerminal()} remains false and the raid
     * stops fail-closed instead of falling through to recurring runtime.
     */
    public boolean isAuthoredFirstRaidActive() {
        return firstState == FirstRaidState.ACTIVE && !legacyBridge
            && activePlan != null;
    }

    /** True only for the structurally validated v0 compatibility bridge. */
    public boolean isLegacyBridgeActive() {
        return legacyBridge && exactLegacyBridgeShape();
    }

    public boolean isParticipant(UUID participantId) {
        return participantId != null && participants.contains(participantId);
    }

    /**
     * True only when every sealed, actually-spawned participant has reached a
     * definitive terminal state. Integrity loss intentionally does not make
     * this false forever: a damaged ledger may still close once all of its
     * remaining known entities resolve, but it can never mint a reward.
     */
    public boolean allParticipantsTerminal() {
        return firstState == FirstRaidState.ACTIVE && participantsTracked
            && !participants.isEmpty()
            && terminalParticipants.size() == participants.size()
            && participants.containsAll(terminalParticipants);
    }

    /** A completed, intact first raid may issue exactly one offer in Slice B. */
    public boolean mayGrantReward() {
        return firstState == FirstRaidState.COMPLETED && rewardEligible
            && firstRaidTerminal != null
            && firstRaidTerminal.outcome() == JourneyOutcome.HELD;
    }

    public Optional<FirstRaidTerminal> firstRaidTerminal() {
        return Optional.ofNullable(firstRaidTerminal);
    }

    /**
     * Persists the one founding roll without arming the attack calendar.
     * Readiness later applies {@code max(rolledNotBefore, ready + lead)}, so a
     * fast player keeps the profile's founding floor while a slow player
     * always receives the full warning lead.
     */
    public boolean prepareAtFounding(long foundingNight, RandomSource random,
                                     RaidProfile profile) {
        if (random == null || profile == null || foundingNight < 0L
            || integrityLost || firstState != FirstRaidState.UNINITIALIZED
            || foundedNight != UNSET_NIGHT
            || rolledNotBeforeNight != UNSET_NIGHT || warningLead != 0
            || readinessNight != UNSET_NIGHT
            || firstAttackNight != UNSET_NIGHT
            || firstWarningNight != UNSET_NIGHT) {
            return false;
        }
        CalendarProvenance provenance = CalendarProvenance.forNewFounding(profile);
        if (foundingNight > Long.MAX_VALUE - provenance.maxOffset) {
            return false;
        }
        int attackOffset = provenance.minOffset + random.nextInt(
            provenance.maxOffset - provenance.minOffset + 1);
        int selectedWarningLead = profile == RaidProfile.IRON_WINTER
            || profile == RaidProfile.BALANCED ? 1 : 2;
        return prepareAtFounding(foundingNight, attackOffset,
            selectedWarningLead, profile);
    }

    /**
     * Legacy deterministic overload retained for existing save-contract and
     * calendar tests. It deliberately keeps the deployed 4..7 contract.
     */
    public boolean prepareAtFounding(long foundingNight, int attackOffset,
                                     int selectedWarningLead) {
        return prepareAtFounding(foundingNight, attackOffset,
            selectedWarningLead, CalendarProvenance.LEGACY);
    }

    /** Explicit profile seam for new-founding calendar tests and B02 callers. */
    public boolean prepareAtFounding(long foundingNight, int attackOffset,
                                     int selectedWarningLead,
                                     RaidProfile profile) {
        if (profile == null) {
            return false;
        }
        int profileWarningLead = profile == RaidProfile.IRON_WINTER
            || profile == RaidProfile.BALANCED ? 1 : 2;
        if (selectedWarningLead != profileWarningLead) {
            return false;
        }
        return prepareAtFounding(foundingNight, attackOffset,
            selectedWarningLead, CalendarProvenance.forNewFounding(profile));
    }

    private boolean prepareAtFounding(long foundingNight, int attackOffset,
                                      int selectedWarningLead,
                                      CalendarProvenance provenance) {
        if (foundingNight < 0L || integrityLost
            || provenance == null || !provenance.acceptsOffset(attackOffset)
            || !provenance.acceptsWarningLead(selectedWarningLead)
            || foundingNight > Long.MAX_VALUE - attackOffset
            || firstState != FirstRaidState.UNINITIALIZED
            || foundedNight != UNSET_NIGHT
            || rolledNotBeforeNight != UNSET_NIGHT || warningLead != 0
            || readinessNight != UNSET_NIGHT
            || firstAttackNight != UNSET_NIGHT
            || firstWarningNight != UNSET_NIGHT) {
            return false;
        }
        this.foundedNight = foundingNight;
        this.rolledNotBeforeNight = foundingNight + attackOffset;
        this.warningLead = selectedWarningLead;
        this.calendarProvenance = provenance;
        this.firstState = FirstRaidState.PREPARING;
        return true;
    }

    /**
     * Arms the exact calendar once after a server-authoritative readiness
     * transaction. It contains no random draw and is idempotently one-shot.
     */
    public boolean scheduleAfterReadiness(long committedReadinessNight) {
        if (firstState != FirstRaidState.PREPARING || integrityLost
            || !preparingFieldsValid()
            || committedReadinessNight < foundedNight
            || committedReadinessNight > Long.MAX_VALUE - warningLead) {
            return false;
        }
        long readyFloor = committedReadinessNight + warningLead;
        long attack = Math.max(rolledNotBeforeNight, readyFloor);
        long warning = attack - warningLead;
        if (warning < committedReadinessNight || warning < foundedNight) {
            return false;
        }
        readinessNight = committedReadinessNight;
        firstAttackNight = attack;
        firstWarningNight = warning;
        firstState = FirstRaidState.SCHEDULED;
        return true;
    }

    /** The calendar uses day-time, so configured longer days and sleep retain their meaning. */
    public boolean firstTimerDue(long currentNight, int days) {
        return firstState == FirstRaidState.PREPARING && !integrityLost
            && preparingFieldsValid() && days >= 1 && days <= 365
            && foundedNight <= Long.MAX_VALUE - days
            && currentNight >= foundedNight + days - 1L
            && currentNight < Long.MAX_VALUE;
    }

    /** One full warning night, with an independent persisted timer provenance. */
    public boolean scheduleAfterTimer(long currentNight, int days, int bandSize) {
        if (!firstTimerDue(currentNight, days) || bandSize < 2 || bandSize > 6) return false;
        firstTimerBandSize = bandSize;
        firstTimerDays = days;
        firstTimerCommittedNight = currentNight;
        firstWarningNight = currentNight;
        firstAttackNight = currentNight + 1L;
        firstState = FirstRaidState.SCHEDULED;
        return true;
    }

    /** Only an unannounced plan may move: a late warning still gets its full day. */
    public boolean deferTimerWarning(long warningNight) {
        if (!isTimerScheduled() || firstState != FirstRaidState.SCHEDULED
            || firstTimerWarningPresented
            || warningNight < firstWarningNight || warningNight == Long.MAX_VALUE) return false;
        firstTimerCommittedNight = warningNight;
        firstWarningNight = warningNight;
        firstAttackNight = warningNight + 1L;
        if (queuedPlan != null) {
            queuedPlan = new RaidPlan(queuedPlan.captainId(), queuedPlan.objective(),
                queuedPlan.approachDegrees(), firstAttackNight);
        }
        return true;
    }

    public int firstTimerBandSize() { return firstTimerBandSize; }

    public boolean timerSunsetPresented() { return firstTimerSunsetPresented; }

    public boolean recordTimerSunsetPresented() {
        if (!timerWarningPresented() || firstState != FirstRaidState.SCHEDULED) return false;
        firstTimerSunsetPresented = true;
        return true;
    }

    public void armFirstTimerRetreat(long gameTime) {
        if (isTimerScheduled() && firstState == FirstRaidState.ACTIVE
            && firstTimerRetreatAtGameTime < 0L && gameTime >= 0L
            && gameTime <= Long.MAX_VALUE - FIRST_TIMER_RAID_BUDGET_TICKS) {
            firstTimerRetreatAtGameTime = gameTime + FIRST_TIMER_RAID_BUDGET_TICKS;
        }
    }

    public boolean firstTimerRetreatDue(long gameTime) {
        return isTimerScheduled() && firstTimerRetreatAtGameTime >= 0L
            && gameTime >= firstTimerRetreatAtGameTime;
    }

    public boolean isTimerScheduled() {
        return firstTimerDays > 0 && !integrityLost && datesValid();
    }

    public boolean timerWarningPresented() {
        return isTimerScheduled() && firstTimerWarningPresented;
    }

    public boolean recordTimerWarningPresented() {
        if (!isTimerScheduled() || firstState != FirstRaidState.SCHEDULED
            || queuedPlan == null || queuedPlan.night() != firstAttackNight) return false;
        firstTimerWarningPresented = true;
        return true;
    }

    /**
     * Legacy/deterministic scheduled constructor retained for existing save
     * fixtures. Production founding uses {@link #prepareAtFounding}.
     */
    public boolean initializeAtFounding(long foundingNight, RandomSource random,
                                        RaidProfile profile) {
        if (random == null || profile == null || foundingNight < 0L
            || integrityLost
            || foundingNight > Long.MAX_VALUE - FIRST_ATTACK_MAX_OFFSET
            || firstState != FirstRaidState.UNINITIALIZED
            || foundedNight != UNSET_NIGHT || firstAttackNight != UNSET_NIGHT
            || firstWarningNight != UNSET_NIGHT) {
            return false;
        }
        int attackOffset = FIRST_ATTACK_MIN_OFFSET
            + random.nextInt(FIRST_ATTACK_MAX_OFFSET - FIRST_ATTACK_MIN_OFFSET + 1);
        // One random draw total. Profile controls the readable warning lead;
        // PEACEFUL gets the full two nights requested by the owner directive.
        int warningLead = profile == RaidProfile.IRON_WINTER ? 1 : 2;
        return initializeAtFounding(foundingNight, attackOffset, warningLead);
    }

    /** Pure deterministic overload used by migration/contract GameTests. */
    public boolean initializeAtFounding(long foundingNight, int attackOffset,
                                        int warningLead) {
        if (foundingNight < 0L
            || integrityLost
            || attackOffset < FIRST_ATTACK_MIN_OFFSET
            || attackOffset > FIRST_ATTACK_MAX_OFFSET
            || warningLead < 1 || warningLead > 2
            || foundingNight > Long.MAX_VALUE - attackOffset
            || firstState != FirstRaidState.UNINITIALIZED
            || foundedNight != UNSET_NIGHT || firstAttackNight != UNSET_NIGHT
            || firstWarningNight != UNSET_NIGHT) {
            return false;
        }
        this.foundedNight = foundingNight;
        this.rolledNotBeforeNight = foundingNight + attackOffset;
        this.warningLead = warningLead;
        // This scheduled constructor is a compatibility fixture seam. Keep
        // its historic 4..7 validation independent of the live profile.
        this.calendarProvenance = CalendarProvenance.LEGACY;
        // A directly constructed SCHEDULED fixture is equivalent to readiness
        // having committed on the warning night.
        this.readinessNight = foundingNight + attackOffset - warningLead;
        this.firstAttackNight = foundingNight + attackOffset;
        this.firstWarningNight = firstAttackNight - warningLead;
        this.firstState = FirstRaidState.SCHEDULED;
        return true;
    }

    /** Stores the authored plan without activating it. */
    public boolean queueFirstPlan(RaidPlan plan) {
        if (!planValid(plan) || firstState != FirstRaidState.SCHEDULED
            || !datesValid() || plan.night() != firstAttackNight) {
            return false;
        }
        if (queuedPlan != null) {
            return false; // one-shot: never replace the plan already warned
        }
        queuedPlan = plan;
        return true;
    }

    /**
     * Begins the exact authored plan players were warned about. Participant
     * capture starts open and must be explicitly sealed after 1-9 actual
     * spawned raider ids have been recorded.
     */
    public boolean beginFirstRaid(RaidPlan plan) {
        if (!planValid(plan) || firstState != FirstRaidState.SCHEDULED
            || integrityLost || !datesValid()
            || firstTimerDays > 0 && !firstTimerWarningPresented
            || plan.night() != firstAttackNight
            || queuedPlan == null || !queuedPlan.equals(plan)) {
            return false;
        }
        firstState = FirstRaidState.ACTIVE;
        activePlan = plan;
        queuedPlan = null;
        participants.clear();
        participantRoster.clear();
        terminalParticipants.clear();
        participantsTracked = false;
        participantRosterTracked = false;
        // A pre-roster SCHEDULED save is beginning a genuinely new capture.
        // Promote here—never while reading an already ACTIVE UUID-only raid.
        participantRosterSchema = PARTICIPANT_ROSTER_SCHEMA;
        rewardEligible = false;
        normalize();
        return true;
    }

    /** Adds an actual spawned raid participant, before capture is sealed. */
    public boolean recordParticipant(UUID participantId) {
        if (participantId == null || firstState != FirstRaidState.ACTIVE
            || participantsTracked || integrityLost) {
            return false;
        }
        if (participants.contains(participantId)) {
            return true;
        }
        if (participants.size() >= MAX_PARTICIPANTS) {
            integrityLost = true;
            rewardEligible = false;
            return false;
        }
        participants.add(participantId);
        return true;
    }

    /**
     * Production capture path: records both completion identity and the exact
     * stable role facts required by the encounter bar after restart.
     */
    public boolean recordParticipant(RaidParticipantRecord record) {
        if (record == null || !recordParticipant(record.entityId())) {
            return false;
        }
        RaidParticipantRecord previous = participantRoster.putIfAbsent(
            record.entityId(), record);
        if (previous != null && !previous.equals(record)) {
            markIntegrityLost();
            return false;
        }
        return true;
    }

    /** Seals a complete, non-empty 1-9 UUID capture exactly once. */
    public boolean sealParticipants() {
        if (firstState != FirstRaidState.ACTIVE || participantsTracked
            || integrityLost || participants.isEmpty()
            || participants.size() > MAX_PARTICIPANTS) {
            return false;
        }
        if (!participantRoster.isEmpty() && !rosterShapeValid()) {
            markIntegrityLost();
            return false;
        }
        participantsTracked = true;
        participantRosterTracked = !participantRoster.isEmpty();
        return true;
    }

    /**
     * Records death or explicit destruction for one sealed participant.
     * Duplicate delivery is idempotent. An unknown UUID is evidence that the
     * runtime and persisted capture disagree, so it permanently disarms the
     * reward instead of being silently accepted.
     */
    public boolean recordTerminalParticipant(UUID participantId) {
        if (participantId == null || firstState != FirstRaidState.ACTIVE
            || !participantsTracked) {
            return false;
        }
        if (!participants.contains(participantId)) {
            markIntegrityLost();
            return false;
        }
        if (terminalParticipants.contains(participantId)) {
            return false;
        }
        if (terminalParticipants.size() >= MAX_PARTICIPANTS) {
            markIntegrityLost();
            return false;
        }
        terminalParticipants.add(participantId);
        return true;
    }

    /**
     * Closes the authored first raid under one exact persisted terminal fact.
     * Only HELD is reward-eligible; HIT and SETTLEMENT_LOST remain distinct so
     * restart recovery never infers either one from a later population.
     */
    public boolean completeFirstRaid(JourneyOutcome outcome,
                                     RaidLogEntry aftermath) {
        if (firstState != FirstRaidState.ACTIVE || activePlan == null
            || !allParticipantsTerminal()) {
            return false;
        }
        FirstRaidTerminal terminal = FirstRaidTerminal.create(outcome,
            activePlan, aftermath).orElse(null);
        if (terminal == null) {
            return false;
        }
        firstState = FirstRaidState.COMPLETED;
        firstRaidTerminal = terminal;
        rewardEligible = outcome == JourneyOutcome.HELD
            && participantsTracked && !participants.isEmpty()
            && participants.size() <= MAX_PARTICIPANTS && !integrityLost;
        normalize();
        return true;
    }

    /**
     * Compatibility fixture seam. Production must provide the exact
     * Aftermath row through {@link #completeFirstRaid(JourneyOutcome,
     * RaidLogEntry)}; this deterministic row keeps older lifecycle-only tests
     * source-compatible but cannot match a real settlement log for recovery.
     */
    public boolean completeFirstRaid(boolean held) {
        if (activePlan == null) {
            return false;
        }
        JourneyOutcome outcome = held ? JourneyOutcome.HELD : JourneyOutcome.HIT;
        RaidLogEntry fixture = new RaidLogEntry(activePlan.night(),
            "Fixture Captain", activePlan.objective().id(), held, 0, 0,
            "rolig");
        return completeFirstRaid(outcome, fixture);
    }

    /**
     * Closes the deliberately unauditable v0 ACTIVE bridge after the legacy
     * director resolves its persisted pending plan. It can never grant a
     * Blessing and cannot be used for a valid authored schedule.
     */
    public boolean completeLegacyBridge(RaidPlan resolvedPlan) {
        if (resolvedPlan == null || !isLegacyBridgeActive()
            || activePlan == null
            || !activePlan.equals(resolvedPlan)) {
            return false;
        }
        firstState = FirstRaidState.COMPLETED;
        legacyBridge = false;
        rewardEligible = false;
        firstRaidTerminal = null;
        normalize();
        return true;
    }

    /** Called after Slice B atomically grants the corresponding offer. */
    public void markRewardGranted() {
        rewardEligible = false;
    }

    /** Permanently disarms unauditable persisted state. */
    public void markIntegrityLost() {
        integrityLost = true;
        rewardEligible = false;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putLong("FoundedNight", foundedNight);
        tag.putLong("RolledNotBeforeNight", rolledNotBeforeNight);
        tag.putInt("WarningLead", warningLead);
        if (firstState != FirstRaidState.UNINITIALIZED) {
            tag.putInt("FirstRaidCalendarProvenance", calendarProvenance.wireId);
        }
        tag.putLong("ReadinessNight", readinessNight);
        if (firstTimerDays != 0 || firstTimerCommittedNight != UNSET_NIGHT
            || firstTimerWarningPresented) {
            CompoundTag timer = new CompoundTag();
            timer.putInt("Schema", 1);
            timer.putInt("Days", firstTimerDays);
            timer.putLong("CommittedNight", firstTimerCommittedNight);
            timer.putBoolean("WarningPresented", firstTimerWarningPresented);
            timer.putBoolean("SunsetPresented", firstTimerSunsetPresented);
            timer.putInt("BandSize", firstTimerBandSize);
            timer.putLong("RetreatAtGameTime", firstTimerRetreatAtGameTime);
            tag.put("FirstRaidTimer", timer);
        }
        tag.putLong("FirstAttackNight", firstAttackNight);
        tag.putLong("FirstWarningNight", firstWarningNight);
        tag.putInt("FirstStateWireId", firstState.wireId());
        tag.putString("FirstState", firstState.id());
        if (queuedPlan != null) {
            tag.put("QueuedPlan", queuedPlan.writeNbt());
        }
        if (activePlan != null) {
            tag.put("ActivePlan", activePlan.writeNbt());
        }
        if (firstRaidTerminal != null) {
            tag.put("FirstRaidTerminal", firstRaidTerminal.writeNbt());
        }
        ListTag participantList = new ListTag();
        for (UUID participant : participants) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", participant);
            participantList.add(entry);
        }
        tag.put("Participants", participantList);
        ListTag rosterList = new ListTag();
        for (RaidParticipantRecord record : participantRoster.values()) {
            rosterList.add(record.writeNbt());
        }
        tag.putInt("ParticipantRosterSchema", participantRosterSchema);
        tag.putBoolean("ParticipantRosterTracked", participantRosterTracked);
        tag.put("ParticipantRoster", rosterList);
        ListTag terminalList = new ListTag();
        for (UUID participant : terminalParticipants) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", participant);
            terminalList.add(entry);
        }
        tag.put("TerminalParticipants", terminalList);
        tag.putBoolean("ParticipantsTracked", participantsTracked);
        tag.putBoolean("RewardEligible", rewardEligible);
        tag.putBoolean("IntegrityLost", integrityLost);
        tag.putBoolean("LegacyBridge", legacyBridge);
        tag.putInt("RecurringScheduleSchema", recurringScheduleSchema);
        tag.putLong("RecurringCooldownUntilGameTime", recurringCooldownUntilGameTime);
        tag.putInt("RecurringQuietEligibleRolls", recurringQuietEligibleRolls);
        if (recurringWarnedPlan != null) {
            tag.put("RecurringWarnedPlan", recurringWarnedPlan.writeNbt());
        }
        tag.putLong("RecurringWarningGameTime", recurringWarningGameTime);
        tag.putLong("RecurringWarningNight", recurringWarningNight);
        tag.putBoolean("RecurringScheduleBlocked", recurringScheduleBlocked);
        tag.putLong("RecurringCadenceNextWarningNight", recurringNextWarningNight);
        tag.putInt("RecurringCadenceSchema", RECURRING_CADENCE_SCHEMA);
        tag.putLong("RecurringCadenceLastRaidNight", recurringLastRaidNight);
        tag.putInt("RecurringCadenceIntervalDays", recurringIntervalDays);
        tag.putInt("RaidsSurvived", raidsSurvived);
        tag.putBoolean("RaidBreather", raidBreather);
        tag.putLong("RecurringRetreatAtDayTime", recurringRetreatAtDayTime);
        ListTag retreated = new ListTag();
        for (UUID id : retreatedRaiders) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", id);
            retreated.add(entry);
        }
        tag.put("RetreatedRaiders", retreated);
        return tag;
    }

    public static RaidLifecycle readNbt(CompoundTag tag) {
        return readNbt(tag, com.hearthstead.HearthsteadServerConfig.recurringRaidWindow());
    }

    /**
     * Cadence save migration (schema 1 -> 2). Before the 3-4 day window a
     * save stored only the next warning night, computed as
     * {@code R + gap - 1} with gap 2 (held/retreated) or 3 (lost, which is
     * exactly when the persisted breather flag is set). That night is
     * re-anchored on raid night {@code R} with the window's EARLIEST day, so
     * a migrated raid is never earlier than the configured minimum and, with
     * the defaults, never earlier than the old promise. An already committed
     * warning is a promise to players and is left completely untouched, as
     * are queued/active runs (they live in RecurringRaidRun). Schema 2 reads
     * the persisted anchor and day pick; malformed values fall back to
     * neutral instead of blocking.
     */
    private void readCadenceWindow(CompoundTag tag, RaidCadence.Window window) {
        int schema = tag.contains("RecurringCadenceSchema", Tag.TAG_INT)
            ? tag.getInt("RecurringCadenceSchema") : 1;
        if (schema >= RECURRING_CADENCE_SCHEMA) {
            if (tag.contains("RecurringCadenceLastRaidNight", Tag.TAG_LONG)) {
                long last = tag.getLong("RecurringCadenceLastRaidNight");
                recurringLastRaidNight = last < 0L ? UNSET_NIGHT : last;
            }
            if (tag.contains("RecurringCadenceIntervalDays", Tag.TAG_INT)) {
                int days = tag.getInt("RecurringCadenceIntervalDays");
                recurringIntervalDays = days <= 0 ? 0 : RaidCadence.clampDays(days);
            }
            return;
        }
        if (recurringNextWarningNight == UNSET_NIGHT) {
            return; // no cadence yet: next eligible dusk, same as before
        }
        long legacyRaidNight = RaidCadence.legacyRaidNight(
            recurringNextWarningNight, raidBreather);
        RaidCadence.Window w = window == null ? RaidCadence.DEFAULT_WINDOW : window;
        recurringLastRaidNight = legacyRaidNight;
        if (recurringWarnedPlan != null) {
            // Warned already: keep the promised plan and its nights as-is.
            recurringIntervalDays = 0;
            return;
        }
        recurringIntervalDays = w.minDays();
        recurringNextWarningNight = RaidCadence.migrateLegacyWarningNight(
            recurringNextWarningNight, raidBreather, w);
    }

    /** Test/QA seam: read with an explicit cadence window instead of the config. */
    public static RaidLifecycle readNbt(CompoundTag tag, RaidCadence.Window cadenceWindow) {
        RaidLifecycle lifecycle = new RaidLifecycle();
        if (!tag.contains("FoundedNight", Tag.TAG_LONG)
            || !tag.contains("FirstAttackNight", Tag.TAG_LONG)
            || !tag.contains("FirstWarningNight", Tag.TAG_LONG)) {
            lifecycle.integrityLost = true;
        }
        lifecycle.foundedNight = readNight(tag, "FoundedNight");
        lifecycle.firstAttackNight = readNight(tag, "FirstAttackNight");
        lifecycle.firstWarningNight = readNight(tag, "FirstWarningNight");

        Optional<FirstRaidState> decodedState = decodeState(tag);
        if (decodedState.isPresent()) {
            lifecycle.firstState = decodedState.get();
        } else {
            lifecycle.integrityLost = true;
        }

        boolean hasCalendarProvenance = tag.contains("FirstRaidCalendarProvenance");
        if (hasCalendarProvenance) {
            if (!tag.contains("FirstRaidCalendarProvenance", Tag.TAG_INT)) {
                lifecycle.integrityLost = true;
            } else {
                Optional<CalendarProvenance> provenance = CalendarProvenance
                    .tryFromWireId(tag.getInt("FirstRaidCalendarProvenance"));
                if (provenance.isPresent()) {
                    lifecycle.calendarProvenance = provenance.get();
                } else {
                    lifecycle.integrityLost = true;
                }
            }
        }

        boolean anyReadinessCalendar = tag.contains("RolledNotBeforeNight")
            || tag.contains("WarningLead") || tag.contains("ReadinessNight");
        boolean hasReadinessCalendar = tag.contains("RolledNotBeforeNight", Tag.TAG_LONG)
            && tag.contains("WarningLead", Tag.TAG_INT)
            && tag.contains("ReadinessNight", Tag.TAG_LONG);
        if (hasCalendarProvenance
            && (lifecycle.firstState == FirstRaidState.UNINITIALIZED
                || !hasReadinessCalendar)) {
            // A versioned calendar is valid only alongside its complete
            // foundation metadata. Do not fall back to legacy derivation.
            lifecycle.integrityLost = true;
        }
        if (hasReadinessCalendar) {
            lifecycle.rolledNotBeforeNight = readNight(tag, "RolledNotBeforeNight");
            lifecycle.warningLead = tag.getInt("WarningLead");
            lifecycle.readinessNight = readNight(tag, "ReadinessNight");
        } else if (anyReadinessCalendar) {
            // Partial or wrong-typed calendar fields are corruption. Never
            // reinterpret them as an older save merely because one key is
            // absent or has a different NBT type.
            lifecycle.integrityLost = true;
        } else if (lifecycle.firstState == FirstRaidState.SCHEDULED
            || lifecycle.firstState == FirstRaidState.ACTIVE
            || lifecycle.firstState == FirstRaidState.COMPLETED) {
            // Save migration: preserve the exact existing attack/warning dates
            // and plan. The derived metadata makes the new formula true without
            // moving an already-authored raid by even one night.
            long derivedLead = lifecycle.firstAttackNight - lifecycle.firstWarningNight;
            if (derivedLead >= 1L && derivedLead <= 2L) {
                lifecycle.warningLead = (int) derivedLead;
                lifecycle.rolledNotBeforeNight = lifecycle.firstAttackNight;
                lifecycle.readinessNight = lifecycle.firstWarningNight;
            } else {
                lifecycle.integrityLost = true;
            }
        } else if (lifecycle.firstState == FirstRaidState.PREPARING) {
            // PREPARING never existed before these fields; missing authority
            // cannot be reconstructed from a scheduled date.
            lifecycle.integrityLost = true;
        }

        if (tag.contains("FirstRaidTimer")) {
            CompoundTag timer = tag.getCompound("FirstRaidTimer");
            if (!tag.contains("FirstRaidTimer", Tag.TAG_COMPOUND)
                || !timer.contains("Schema", Tag.TAG_INT) || timer.getInt("Schema") != 1
                || !timer.contains("Days", Tag.TAG_INT)
                || !timer.contains("CommittedNight", Tag.TAG_LONG)
                || !timer.contains("WarningPresented", Tag.TAG_BYTE)
                || timer.getByte("WarningPresented") < 0
                || timer.getByte("WarningPresented") > 1
                || !timer.contains("SunsetPresented", Tag.TAG_BYTE)
                || timer.getByte("SunsetPresented") < 0 || timer.getByte("SunsetPresented") > 1
                || !timer.contains("BandSize", Tag.TAG_INT)
                || !timer.contains("RetreatAtGameTime", Tag.TAG_LONG)
                || timer.getLong("RetreatAtGameTime") < -1L
                || (lifecycle.firstState == FirstRaidState.ACTIVE
                    || lifecycle.firstState == FirstRaidState.COMPLETED)
                    && timer.getLong("RetreatAtGameTime") < 0L
                || timer.getInt("Days") < 1 || timer.getInt("Days") > 365
                || !hasReadinessCalendar || !hasCalendarProvenance) {
                lifecycle.integrityLost = true;
            }
            lifecycle.firstTimerDays = timer.getInt("Days");
            lifecycle.firstTimerCommittedNight = timer.getLong("CommittedNight");
            lifecycle.firstTimerWarningPresented = timer.getBoolean("WarningPresented");
            lifecycle.firstTimerSunsetPresented = timer.getBoolean("SunsetPresented");
            lifecycle.firstTimerBandSize = timer.getInt("BandSize");
            lifecycle.firstTimerRetreatAtGameTime = timer.getLong("RetreatAtGameTime");
        }

        boolean hadQueuedPlan = tag.contains("QueuedPlan");
        boolean hadActivePlan = tag.contains("ActivePlan");
        lifecycle.queuedPlan = readPlan(tag, "QueuedPlan");
        lifecycle.activePlan = readPlan(tag, "ActivePlan");
        if ((hadQueuedPlan && lifecycle.queuedPlan == null)
            || (hadActivePlan && lifecycle.activePlan == null)) {
            lifecycle.integrityLost = true;
        }
        boolean hadFirstRaidTerminal = tag.contains("FirstRaidTerminal");
        lifecycle.firstRaidTerminal = FirstRaidTerminal.tryReadNbt(
            tag.get("FirstRaidTerminal")).orElse(null);
        if (hadFirstRaidTerminal && lifecycle.firstRaidTerminal == null) {
            lifecycle.integrityLost = true;
        }
        if (!tag.contains("ParticipantsTracked", Tag.TAG_BYTE)
            || !tag.contains("RewardEligible", Tag.TAG_BYTE)
            || !tag.contains("IntegrityLost", Tag.TAG_BYTE)) {
            lifecycle.integrityLost = true;
        }
        lifecycle.participantsTracked = tag.getBoolean("ParticipantsTracked");
        lifecycle.rewardEligible = tag.getBoolean("RewardEligible");
        lifecycle.integrityLost |= tag.getBoolean("IntegrityLost");
        // Missing means an earlier v1 authored record (false), never an
        // inferred legacy bridge. A previously migrated interim v0 save that
        // predates this marker therefore stops fail-closed; safety wins over
        // guessing whether damaged authored state was really old state.
        lifecycle.legacyBridge = tag.contains("LegacyBridge", Tag.TAG_BYTE)
            && tag.getBoolean("LegacyBridge");

        boolean anyRecurringSchedule = tag.contains("RecurringScheduleSchema")
            || tag.contains("RecurringCooldownUntilGameTime")
            || tag.contains("RecurringQuietEligibleRolls")
            || tag.contains("RecurringWarnedPlan")
            || tag.contains("RecurringWarningGameTime")
            || tag.contains("RecurringWarningNight")
            || tag.contains("RecurringScheduleBlocked");
        boolean completeRecurringSchedule =
            tag.contains("RecurringScheduleSchema", Tag.TAG_INT)
            && tag.contains("RecurringCooldownUntilGameTime", Tag.TAG_LONG)
            && tag.contains("RecurringQuietEligibleRolls", Tag.TAG_INT)
            && tag.contains("RecurringWarningGameTime", Tag.TAG_LONG)
            && tag.contains("RecurringWarningNight", Tag.TAG_LONG)
            && tag.contains("RecurringScheduleBlocked", Tag.TAG_BYTE);
        if (!anyRecurringSchedule) {
            // Deployed saves had no committed recurring warning or recovery
            // timestamp. They enter the versioned neutral state; no debt,
            // catch-up roll or fabricated plan is inferred.
            lifecycle.recurringScheduleSchema = RECURRING_SCHEDULE_SCHEMA;
        } else if (!completeRecurringSchedule) {
            lifecycle.blockRecurringSchedule();
        } else {
            lifecycle.recurringScheduleSchema = tag.getInt("RecurringScheduleSchema");
            lifecycle.recurringCooldownUntilGameTime =
                tag.getLong("RecurringCooldownUntilGameTime");
            lifecycle.recurringQuietEligibleRolls =
                tag.getInt("RecurringQuietEligibleRolls");
            lifecycle.recurringWarningGameTime =
                tag.getLong("RecurringWarningGameTime");
            lifecycle.recurringWarningNight = tag.getLong("RecurringWarningNight");
            lifecycle.recurringScheduleBlocked =
                tag.getBoolean("RecurringScheduleBlocked");
            boolean hadRecurringPlan = tag.contains("RecurringWarnedPlan");
            lifecycle.recurringWarnedPlan = readPlan(tag, "RecurringWarnedPlan");
            if (hadRecurringPlan && lifecycle.recurringWarnedPlan == null) {
                lifecycle.blockRecurringSchedule();
            }
            lifecycle.normalizeRecurringSchedule();
        }

        // Cadence/escalation fields: optional, independent, neutral defaults.
        // A missing or malformed value falls back instead of blocking.
        if (tag.contains("RecurringCadenceNextWarningNight", Tag.TAG_LONG)) {
            long next = tag.getLong("RecurringCadenceNextWarningNight");
            lifecycle.recurringNextWarningNight = next < 0L ? UNSET_NIGHT : next;
        }
        if (tag.contains("RaidsSurvived", Tag.TAG_INT)) {
            lifecycle.raidsSurvived = Math.max(0,
                Math.min(MAX_RAIDS_SURVIVED, tag.getInt("RaidsSurvived")));
        }
        if (tag.contains("RaidBreather", Tag.TAG_BYTE)) {
            lifecycle.raidBreather = tag.getBoolean("RaidBreather");
        }
        lifecycle.readCadenceWindow(tag, cadenceWindow);
        if (tag.contains("RecurringRetreatAtDayTime", Tag.TAG_LONG)) {
            lifecycle.recurringRetreatAtDayTime =
                Math.max(-1L, tag.getLong("RecurringRetreatAtDayTime"));
        }
        if (tag.get("RetreatedRaiders") instanceof ListTag retreatedList) {
            for (int i = 0; i < retreatedList.size()
                && lifecycle.retreatedRaiders.size() < MAX_RETREATED_RAIDERS; i++) {
                if (retreatedList.get(i) instanceof CompoundTag entry
                    && entry.hasUUID("Id")) {
                    lifecycle.retreatedRaiders.add(entry.getUUID("Id"));
                }
            }
        }

        Tag participantTag = tag.get("Participants");
        if (participantTag == null) {
            lifecycle.integrityLost = true;
        } else if (!(participantTag instanceof ListTag participantList)) {
            lifecycle.integrityLost = true;
        } else {
            for (int i = 0; i < participantList.size(); i++) {
                Tag rawEntry = participantList.get(i);
                if (!(rawEntry instanceof CompoundTag entry) || !entry.hasUUID("Id")) {
                    lifecycle.integrityLost = true;
                    continue;
                }
                UUID participant = entry.getUUID("Id");
                if (lifecycle.participants.contains(participant)) {
                    lifecycle.integrityLost = true;
                    continue;
                }
                if (lifecycle.participants.size() >= MAX_PARTICIPANTS) {
                    lifecycle.integrityLost = true;
                    continue;
                }
                lifecycle.participants.add(participant);
            }
        }

        boolean anyRosterField = tag.contains("ParticipantRosterSchema")
            || tag.contains("ParticipantRosterTracked")
            || tag.contains("ParticipantRoster");
        boolean completeRosterFields = tag.contains("ParticipantRosterSchema", Tag.TAG_INT)
            && tag.contains("ParticipantRosterTracked", Tag.TAG_BYTE)
            && tag.get("ParticipantRoster") instanceof ListTag;
        if (!anyRosterField) {
            // Exact migration rule: UUID-only saves keep their completion
            // ledger, but presentation remains unavailable. Never infer roles.
            lifecycle.participantRosterSchema = 0;
            lifecycle.participantRosterTracked = false;
        } else if (!completeRosterFields) {
            lifecycle.participantRosterSchema = 0;
            lifecycle.participantRosterTracked = false;
            lifecycle.integrityLost = true;
        } else {
            lifecycle.participantRosterSchema = tag.getInt("ParticipantRosterSchema");
            lifecycle.participantRosterTracked =
                tag.getBoolean("ParticipantRosterTracked");
            ListTag rosterList = (ListTag) tag.get("ParticipantRoster");
            if (lifecycle.participantRosterSchema == 0) {
                // A previously migrated UUID-only save writes its legacy
                // schema explicitly. It must remain stable on every restart.
                if (lifecycle.participantRosterTracked || !rosterList.isEmpty()) {
                    lifecycle.integrityLost = true;
                }
            } else if (lifecycle.participantRosterSchema
                    != PARTICIPANT_ROSTER_SCHEMA) {
                lifecycle.integrityLost = true;
            } else {
                for (int i = 0; i < rosterList.size(); i++) {
                    RaidParticipantRecord record = RaidParticipantRecord
                        .tryReadNbt(rosterList.get(i)).orElse(null);
                    if (record == null
                        || lifecycle.participantRoster.putIfAbsent(
                            record.entityId(), record) != null
                        || lifecycle.participantRoster.size() > MAX_PARTICIPANTS) {
                        lifecycle.integrityLost = true;
                    }
                }
            }
        }

        Tag terminalTag = tag.get("TerminalParticipants");
        if (terminalTag == null) {
            // Compatibility with the brief M1-only save shape: before the
            // runtime existed, an uninitialized/scheduled lifecycle could not
            // have any terminal outcomes. Empty is therefore provable there;
            // ACTIVE/COMPLETED state without the ledger is unauditable.
            if (lifecycle.firstState == FirstRaidState.ACTIVE
                || lifecycle.firstState == FirstRaidState.COMPLETED
                || lifecycle.participantsTracked
                || !lifecycle.participants.isEmpty()
                || lifecycle.rewardEligible) {
                lifecycle.integrityLost = true;
            }
        } else if (!(terminalTag instanceof ListTag terminalList)) {
            lifecycle.integrityLost = true;
        } else {
            for (int i = 0; i < terminalList.size(); i++) {
                Tag rawEntry = terminalList.get(i);
                if (!(rawEntry instanceof CompoundTag entry) || !entry.hasUUID("Id")) {
                    lifecycle.integrityLost = true;
                    continue;
                }
                UUID participant = entry.getUUID("Id");
                if (!lifecycle.participants.contains(participant)
                    || lifecycle.terminalParticipants.contains(participant)
                    || lifecycle.terminalParticipants.size() >= MAX_PARTICIPANTS) {
                    lifecycle.integrityLost = true;
                    continue;
                }
                lifecycle.terminalParticipants.add(participant);
            }
        }
        lifecycle.normalize();
        return lifecycle;
    }

    /**
     * Explicit v0 bridge. Forecast fields are intentionally not inspected:
     * an old omen was not an authored queued first-raid plan.
     */
    public static RaidLifecycle migrateV0(@Nullable RaidPlan legacyPendingRaid,
                                          boolean hasLegacyRaidResult,
                                          boolean malformedLegacyPending) {
        RaidLifecycle lifecycle = new RaidLifecycle();
        if (legacyPendingRaid != null) {
            lifecycle.firstState = FirstRaidState.ACTIVE;
            lifecycle.activePlan = validateLegacyPlan(legacyPendingRaid);
            lifecycle.integrityLost = true;
            lifecycle.legacyBridge = lifecycle.activePlan != null;
        } else if (hasLegacyRaidResult) {
            lifecycle.firstState = FirstRaidState.COMPLETED;
            lifecycle.integrityLost = true;
        }
        // A v0 save has neither an authored founding schedule nor auditable
        // participant tracking. It can continue, but can never mint a free
        // post-upgrade Blessing.
        lifecycle.participantsTracked = false;
        lifecycle.participantRosterTracked = false;
        lifecycle.participantRosterSchema = 0;
        lifecycle.rewardEligible = false;
        lifecycle.firstRaidTerminal = null;
        lifecycle.integrityLost |= malformedLegacyPending;
        lifecycle.normalize();
        return lifecycle;
    }

    @Nullable
    private static RaidPlan validateLegacyPlan(RaidPlan legacy) {
        if (!planValid(legacy)) {
            return null;
        }
        return legacy;
    }

    private static boolean planValid(@Nullable RaidPlan plan) {
        return RaidPlan.isValid(plan);
    }

    private void normalizeRecurringSchedule() {
        if (recurringScheduleBlocked) {
            recurringScheduleSchema = RECURRING_SCHEDULE_SCHEMA;
            recurringCooldownUntilGameTime = Math.max(0L, recurringCooldownUntilGameTime);
            recurringQuietEligibleRolls = 0;
            clearRecurringWarning();
            return;
        }
        if (recurringScheduleSchema != RECURRING_SCHEDULE_SCHEMA
            || recurringCooldownUntilGameTime < 0L
            || recurringQuietEligibleRolls < 0
            || recurringQuietEligibleRolls > RECURRING_FORCE_AFTER_QUIET_ROLLS
            || recurringWarningGameTime < -1L
            || recurringWarningNight < UNSET_NIGHT) {
            blockRecurringSchedule();
            return;
        }
        if (recurringWarnedPlan == null) {
            if (recurringWarningGameTime != -1L
                || recurringWarningNight != UNSET_NIGHT) {
                blockRecurringSchedule();
            }
            return;
        }
        if (!planValid(recurringWarnedPlan)
            || recurringWarningGameTime < 0L || recurringWarningNight < 0L
            || recurringWarnedPlan.night() <= recurringWarningNight
            || recurringQuietEligibleRolls != 0) {
            blockRecurringSchedule();
        }
    }

    private void normalize() {
        normalizeRecurringSchedule();
        if ((firstTimerDays != 0 || firstTimerCommittedNight != UNSET_NIGHT
                || firstTimerWarningPresented)
            && (firstState == FirstRaidState.UNINITIALIZED
                || firstState == FirstRaidState.PREPARING
                || !datesValid()
                || firstTimerSunsetPresented && !firstTimerWarningPresented
                || firstState == FirstRaidState.SCHEDULED && firstTimerRetreatAtGameTime != -1L
                || firstTimerWarningPresented && queuedPlan == null && activePlan == null
                || (firstState == FirstRaidState.ACTIVE || firstState == FirstRaidState.COMPLETED)
                    && !firstTimerWarningPresented)) {
            integrityLost = true;
            rewardEligible = false;
        }
        if (foundedNight < UNSET_NIGHT) {
            foundedNight = UNSET_NIGHT;
            integrityLost = true;
        }
        if (firstAttackNight < UNSET_NIGHT) {
            firstAttackNight = UNSET_NIGHT;
            integrityLost = true;
        }
        if (firstWarningNight < UNSET_NIGHT) {
            firstWarningNight = UNSET_NIGHT;
            integrityLost = true;
        }

        if (firstState == FirstRaidState.UNINITIALIZED) {
            boolean carriedState = foundedNight != UNSET_NIGHT
                || rolledNotBeforeNight != UNSET_NIGHT || warningLead != 0
                || calendarProvenance != CalendarProvenance.LEGACY
                || readinessNight != UNSET_NIGHT
                || firstAttackNight != UNSET_NIGHT
                || firstWarningNight != UNSET_NIGHT
                || queuedPlan != null || activePlan != null
                || !participants.isEmpty() || !terminalParticipants.isEmpty()
                || !participantRoster.isEmpty() || participantsTracked
                || participantRosterTracked || rewardEligible
                || firstRaidTerminal != null;
            if (carriedState) {
                integrityLost = true;
            }
            foundedNight = UNSET_NIGHT;
            rolledNotBeforeNight = UNSET_NIGHT;
            warningLead = 0;
            calendarProvenance = CalendarProvenance.LEGACY;
            readinessNight = UNSET_NIGHT;
            firstAttackNight = UNSET_NIGHT;
            firstWarningNight = UNSET_NIGHT;
            queuedPlan = null;
            activePlan = null;
            participants.clear();
            participantRoster.clear();
            terminalParticipants.clear();
            participantsTracked = false;
            participantRosterTracked = false;
            rewardEligible = false;
            firstRaidTerminal = null;
            legacyBridge = false;
            return;
        }

        if (firstState == FirstRaidState.PREPARING) {
            if (!preparingFieldsValid() || queuedPlan != null || activePlan != null
                || !participants.isEmpty() || !terminalParticipants.isEmpty()
                || !participantRoster.isEmpty() || participantsTracked
                || participantRosterTracked || rewardEligible
                || firstRaidTerminal != null || legacyBridge) {
                integrityLost = true;
            }
            firstAttackNight = UNSET_NIGHT;
            firstWarningNight = UNSET_NIGHT;
            readinessNight = UNSET_NIGHT;
            queuedPlan = null;
            activePlan = null;
            participants.clear();
            participantRoster.clear();
            terminalParticipants.clear();
            participantsTracked = false;
            participantRosterTracked = false;
            rewardEligible = false;
            firstRaidTerminal = null;
            legacyBridge = false;
            return;
        }

        boolean validDates = datesValid();
        if (!validDates) {
            // Retain a legacy ACTIVE/COMPLETED marker so old runtime state is
            // not lied about, but permanently disarm its unauditable reward.
            integrityLost = true;
            rewardEligible = false;
        }

        if (firstState == FirstRaidState.SCHEDULED) {
            if (activePlan != null) {
                activePlan = null;
                integrityLost = true;
            }
            if (queuedPlan != null && validDates
                && queuedPlan.night() != firstAttackNight) {
                queuedPlan = null;
                integrityLost = true;
            }
            if (!participants.isEmpty() || !terminalParticipants.isEmpty()
                || !participantRoster.isEmpty() || participantsTracked
                || participantRosterTracked || rewardEligible) {
                integrityLost = true;
            }
            participants.clear();
            participantRoster.clear();
            terminalParticipants.clear();
            participantsTracked = false;
            participantRosterTracked = false;
            rewardEligible = false;
            if (firstRaidTerminal != null) {
                firstRaidTerminal = null;
                integrityLost = true;
            }
        } else {
            if (queuedPlan != null) {
                queuedPlan = null;
                integrityLost = true;
            }
            if (firstState == FirstRaidState.ACTIVE && activePlan == null) {
                // An "active" raid with no plan cannot be acted on safely.
                firstState = FirstRaidState.UNINITIALIZED;
                foundedNight = UNSET_NIGHT;
                firstAttackNight = UNSET_NIGHT;
                firstWarningNight = UNSET_NIGHT;
                participants.clear();
                participantRoster.clear();
                terminalParticipants.clear();
                participantsTracked = false;
                participantRosterTracked = false;
                rewardEligible = false;
                firstRaidTerminal = null;
                integrityLost = true;
                return;
            }
            if (activePlan != null && validDates
                && activePlan.night() != firstAttackNight) {
                rewardEligible = false;
                integrityLost = true;
            }
        }

        if (!participantsTracked) {
            // An in-progress partial capture is evidence worth preserving.
            // It is simply not reward-capable until sealed.
            rewardEligible = false;
            if (!terminalParticipants.isEmpty()) {
                terminalParticipants.clear();
                integrityLost = true;
            }
        }
        if (participantRosterSchema != 0
            && participantRosterSchema != PARTICIPANT_ROSTER_SCHEMA) {
            participantRosterTracked = false;
            integrityLost = true;
        }
        if (participantRosterTracked && !rosterShapeValid()) {
            participantRosterTracked = false;
            integrityLost = true;
        } else if (!participantRosterTracked && !participantRoster.isEmpty()) {
            // A partial roster is preserved nowhere: it is not presentation
            // authority and must not be completed from loaded entities later.
            participantRoster.clear();
            integrityLost = true;
        }
        if (participantsTracked && participants.isEmpty()) {
            participantsTracked = false;
            participantRosterTracked = false;
            participantRoster.clear();
            rewardEligible = false;
            integrityLost = true;
        }
        if (!participants.containsAll(terminalParticipants)) {
            terminalParticipants.removeIf(id -> !participants.contains(id));
            rewardEligible = false;
            integrityLost = true;
        }
        if (firstState == FirstRaidState.COMPLETED
            && (!participantsTracked || terminalParticipants.size() != participants.size())) {
            rewardEligible = false;
            integrityLost = true;
        }
        if (firstState != FirstRaidState.COMPLETED) {
            rewardEligible = false;
            if (firstRaidTerminal != null) {
                firstRaidTerminal = null;
                integrityLost = true;
            }
        } else if (activePlan != null && !legacyBridge) {
            if (firstRaidTerminal == null
                || !firstRaidTerminal.matches(activePlan)) {
                integrityLost = true;
                rewardEligible = false;
            } else if (rewardEligible
                && firstRaidTerminal.outcome() != JourneyOutcome.HELD) {
                integrityLost = true;
                rewardEligible = false;
            }
        } else if (firstRaidTerminal != null) {
            integrityLost = true;
            rewardEligible = false;
        }
        if (activePlan == null || integrityLost) {
            rewardEligible = false;
        }

        // A single corrupted boolean must never opt an authored raid into
        // legacy AABB completion. Only the exact v0-active shape survives;
        // every mismatch clears provenance and leaves the lifecycle on the
        // non-legacy, fail-closed path with rewards disarmed.
        if (legacyBridge && !exactLegacyBridgeShape()) {
            legacyBridge = false;
            integrityLost = true;
            rewardEligible = false;
        }
    }

    private boolean exactLegacyBridgeShape() {
        return firstState == FirstRaidState.ACTIVE
            && activePlan != null
            && foundedNight == UNSET_NIGHT
            && rolledNotBeforeNight == UNSET_NIGHT
            && warningLead == 0
            && calendarProvenance == CalendarProvenance.LEGACY
            && readinessNight == UNSET_NIGHT
            && firstAttackNight == UNSET_NIGHT
            && firstWarningNight == UNSET_NIGHT
            && queuedPlan == null
            && participants.isEmpty()
            && participantRoster.isEmpty()
            && terminalParticipants.isEmpty()
            && !participantsTracked
            && !participantRosterTracked
            && !rewardEligible
            && integrityLost;
    }

    /**
     * The authored first raid's exact roster. Since the 26 Sep escalation
     * curve it is a bandit captain and three bandit followers; a first raid
     * already sealed under the old shape (a captain, one BRUTE and three
     * SKIRMISHER followers) stays valid after the update.
     */
    private boolean rosterShapeValid() {
        if (participantRoster.size() != participants.size()
            || !participantRoster.keySet().equals(participants)) {
            return false;
        }
        int captains = 0;
        int bruteFollowers = 0;
        int skirmisherFollowers = 0;
        int banditFollowers = 0;
        for (RaidParticipantRecord record : participantRoster.values()) {
            if (record == null) {
                return false;
            }
            if (record.captain()) {
                captains++;
            } else if (record.build() == RaidParticipantRecord.Build.BRUTE) {
                bruteFollowers++;
            } else if (record.build() == RaidParticipantRecord.Build.BANDIT) {
                banditFollowers++;
            } else {
                skirmisherFollowers++;
            }
        }
        boolean outlawBand = participantRoster.size() == 4 && captains == 1
            && banditFollowers == 3 && bruteFollowers == 0 && skirmisherFollowers == 0;
        boolean legacyBand = participantRoster.size() == 5 && captains == 1
            && bruteFollowers == 1 && skirmisherFollowers == 3 && banditFollowers == 0;
        if (firstTimerDays > 0) {
            return participantRoster.size() == firstTimerBandSize && captains == 1
                && banditFollowers == firstTimerBandSize - 1
                && bruteFollowers == 0 && skirmisherFollowers == 0
                && participantRoster.values().stream().allMatch(
                    record -> record.build() == RaidParticipantRecord.Build.BANDIT);
        }
        return outlawBand || legacyBand;
    }

    private boolean datesValid() {
        if (firstTimerDays != 0 || firstTimerCommittedNight != UNSET_NIGHT
            || firstTimerWarningPresented) {
            return firstTimerDays >= 1 && firstTimerDays <= 365
                && firstTimerBandSize >= 2 && firstTimerBandSize <= 6
                && foundedNight >= 0L && foundedNight <= Long.MAX_VALUE - firstTimerDays
                && calendarProvenance != null
                && calendarProvenance.acceptsWarningLead(warningLead)
                && rolledNotBeforeNight >= foundedNight
                && calendarProvenance.acceptsOffset(rolledNotBeforeNight - foundedNight)
                && readinessNight == UNSET_NIGHT
                && firstTimerCommittedNight >= foundedNight + firstTimerDays - 1L
                && firstTimerCommittedNight < Long.MAX_VALUE
                && firstWarningNight == firstTimerCommittedNight
                && firstAttackNight == firstTimerCommittedNight + 1L;
        }
        if (foundedNight < 0L || firstAttackNight < foundedNight
            || firstWarningNight < foundedNight
            || rolledNotBeforeNight < foundedNight
            || readinessNight < foundedNight
            || calendarProvenance == null
            || !calendarProvenance.acceptsWarningLead(warningLead)) {
            return false;
        }
        long rolledOffset = rolledNotBeforeNight - foundedNight;
        if (!calendarProvenance.acceptsOffset(rolledOffset)
            || readinessNight > Long.MAX_VALUE - warningLead) {
            return false;
        }
        long expectedAttack = Math.max(rolledNotBeforeNight,
            readinessNight + warningLead);
        return firstAttackNight == expectedAttack
            && firstWarningNight == expectedAttack - warningLead;
    }

    private boolean preparingFieldsValid() {
        if (firstTimerDays != 0 || firstTimerCommittedNight != UNSET_NIGHT
            || firstTimerWarningPresented
            || foundedNight < 0L || rolledNotBeforeNight < foundedNight
            || calendarProvenance == null
            || !calendarProvenance.acceptsWarningLead(warningLead)
            || readinessNight != UNSET_NIGHT
            || firstWarningNight != UNSET_NIGHT
            || firstAttackNight != UNSET_NIGHT) {
            return false;
        }
        long offset = rolledNotBeforeNight - foundedNight;
        return calendarProvenance.acceptsOffset(offset);
    }

    private static long readNight(CompoundTag tag, String key) {
        return tag.contains(key, Tag.TAG_LONG) ? tag.getLong(key) : UNSET_NIGHT;
    }

    @Nullable
    private static RaidPlan readPlan(CompoundTag tag, String key) {
        return RaidPlan.tryReadNbt(tag.get(key)).orElse(null);
    }

    private static Optional<FirstRaidState> decodeState(CompoundTag tag) {
        if (tag.contains("FirstStateWireId", Tag.TAG_INT)) {
            Optional<FirstRaidState> wire = FirstRaidState.tryFromWireId(
                tag.getInt("FirstStateWireId"));
            if (wire.isEmpty()) {
                return Optional.empty();
            }
            if (tag.contains("FirstState", Tag.TAG_STRING)
                && !wire.get().id().equals(tag.getString("FirstState"))) {
                return Optional.empty();
            }
            return wire;
        }
        return FirstRaidState.tryFromId(tag.getString("FirstState"));
    }

    /** Immutable, bounded restart evidence for one authored first raid. */
    public record FirstRaidTerminal(JourneyOutcome outcome, RaidPlan plan,
                                    RaidLogEntry aftermath) {
        private static Optional<FirstRaidTerminal> create(
                JourneyOutcome outcome, RaidPlan plan, RaidLogEntry aftermath) {
            FirstRaidTerminal terminal = new FirstRaidTerminal(outcome, plan,
                aftermath);
            return terminal.valid() ? Optional.of(terminal) : Optional.empty();
        }

        public boolean matches(RaidPlan expectedPlan) {
            return valid() && plan.equals(expectedPlan);
        }

        public boolean matches(RaidPlan expectedPlan,
                               RaidLogEntry expectedAftermath) {
            return matches(expectedPlan) && aftermath.equals(expectedAftermath);
        }

        private boolean valid() {
            if (outcome == null || !outcome.terminal()
                || !RaidPlan.isValid(plan) || !RaidLogEntry.isValid(aftermath)
                || plan.captainId().getMostSignificantBits() == 0L
                    && plan.captainId().getLeastSignificantBits() == 0L
                || aftermath.night() != plan.night()
                || !aftermath.objectiveId().equals(plan.objective().id())) {
                return false;
            }
            return aftermath.held() == (outcome == JourneyOutcome.HELD);
        }

        private CompoundTag writeNbt() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("OutcomeWireId", outcome.wireId());
            tag.putString("Outcome", outcome.id());
            tag.put("Plan", plan.writeNbt());
            tag.put("Aftermath", aftermath.writeNbt());
            return tag;
        }

        private static Optional<FirstRaidTerminal> tryReadNbt(Tag raw) {
            if (!(raw instanceof CompoundTag tag)
                || !tag.contains("OutcomeWireId", Tag.TAG_INT)
                || !tag.contains("Outcome", Tag.TAG_STRING)
                || !tag.contains("Plan", Tag.TAG_COMPOUND)
                || !exactAftermathTag(tag.get("Aftermath"))) {
                return Optional.empty();
            }
            Optional<JourneyOutcome> byWire = JourneyOutcome.tryFromWireId(
                tag.getInt("OutcomeWireId"));
            Optional<JourneyOutcome> byName = JourneyOutcome.tryFromId(
                tag.getString("Outcome"));
            Optional<RaidPlan> plan = RaidPlan.tryReadNbt(tag.get("Plan"));
            if (byWire.isEmpty() || byName.isEmpty()
                || byWire.get() != byName.get() || plan.isEmpty()) {
                return Optional.empty();
            }
            return create(byWire.get(), plan.get(), RaidLogEntry.readNbt(
                tag.getCompound("Aftermath")));
        }

        private static boolean exactAftermathTag(Tag raw) {
            return raw instanceof CompoundTag tag
                && tag.contains("Night", Tag.TAG_LONG)
                && tag.contains("Captain", Tag.TAG_STRING)
                && tag.contains("Objective", Tag.TAG_STRING)
                && tag.contains("Held", Tag.TAG_BYTE)
                && tag.contains("ItemsStolen", Tag.TAG_INT)
                && tag.contains("SettlersHurt", Tag.TAG_INT)
                && tag.contains("StageAfter", Tag.TAG_STRING);
        }
    }
}
