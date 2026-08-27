package com.hearthstead.settlement.state;

import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.LinkedHashSet;
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

    private long foundedNight = UNSET_NIGHT;
    private long firstAttackNight = UNSET_NIGHT;
    private long firstWarningNight = UNSET_NIGHT;
    private FirstRaidState firstState = FirstRaidState.UNINITIALIZED;
    @Nullable
    private RaidPlan queuedPlan;
    @Nullable
    private RaidPlan activePlan;
    /** Actual spawned raider entity UUIDs, in capture order (never players). */
    private final LinkedHashSet<UUID> participants = new LinkedHashSet<>();
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
    private boolean rewardEligible;
    private boolean integrityLost;
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

    public long foundedNight() {
        return foundedNight;
    }

    public long firstAttackNight() {
        return firstAttackNight;
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

    public Set<UUID> participants() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(participants));
    }

    public Set<UUID> terminalParticipants() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(terminalParticipants));
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
        return firstState == FirstRaidState.COMPLETED && rewardEligible;
    }

    /**
     * Rolls and persists the first attack once. Repeated calls, including a
     * hearth rebinding after reload, are no-ops.
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
            || !datesValid() || plan.night() != firstAttackNight
            || queuedPlan == null || !queuedPlan.equals(plan)) {
            return false;
        }
        firstState = FirstRaidState.ACTIVE;
        activePlan = plan;
        queuedPlan = null;
        participants.clear();
        terminalParticipants.clear();
        participantsTracked = false;
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

    /** Seals a complete, non-empty 1-9 UUID capture exactly once. */
    public boolean sealParticipants() {
        if (firstState != FirstRaidState.ACTIVE || participantsTracked
            || integrityLost || participants.isEmpty()
            || participants.size() > MAX_PARTICIPANTS) {
            return false;
        }
        participantsTracked = true;
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

    /** Closes the first raid; only a held, intact defense remains eligible. */
    public boolean completeFirstRaid(boolean held) {
        if (firstState != FirstRaidState.ACTIVE || activePlan == null
            || !allParticipantsTerminal()) {
            return false;
        }
        firstState = FirstRaidState.COMPLETED;
        rewardEligible = held && participantsTracked && !participants.isEmpty()
            && participants.size() <= MAX_PARTICIPANTS && !integrityLost;
        normalize();
        return true;
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
        ListTag participantList = new ListTag();
        for (UUID participant : participants) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", participant);
            participantList.add(entry);
        }
        tag.put("Participants", participantList);
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
        return tag;
    }

    public static RaidLifecycle readNbt(CompoundTag tag) {
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

        boolean hadQueuedPlan = tag.contains("QueuedPlan");
        boolean hadActivePlan = tag.contains("ActivePlan");
        lifecycle.queuedPlan = readPlan(tag, "QueuedPlan");
        lifecycle.activePlan = readPlan(tag, "ActivePlan");
        if ((hadQueuedPlan && lifecycle.queuedPlan == null)
            || (hadActivePlan && lifecycle.activePlan == null)) {
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
        lifecycle.rewardEligible = false;
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

    private void normalize() {
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
                || firstAttackNight != UNSET_NIGHT
                || firstWarningNight != UNSET_NIGHT
                || queuedPlan != null || activePlan != null
                || !participants.isEmpty() || !terminalParticipants.isEmpty()
                || participantsTracked || rewardEligible;
            if (carriedState) {
                integrityLost = true;
            }
            foundedNight = UNSET_NIGHT;
            firstAttackNight = UNSET_NIGHT;
            firstWarningNight = UNSET_NIGHT;
            queuedPlan = null;
            activePlan = null;
            participants.clear();
            terminalParticipants.clear();
            participantsTracked = false;
            rewardEligible = false;
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
                || participantsTracked || rewardEligible) {
                integrityLost = true;
            }
            participants.clear();
            terminalParticipants.clear();
            participantsTracked = false;
            rewardEligible = false;
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
                terminalParticipants.clear();
                participantsTracked = false;
                rewardEligible = false;
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
        if (participantsTracked && participants.isEmpty()) {
            participantsTracked = false;
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
            && firstAttackNight == UNSET_NIGHT
            && firstWarningNight == UNSET_NIGHT
            && queuedPlan == null
            && participants.isEmpty()
            && terminalParticipants.isEmpty()
            && !participantsTracked
            && !rewardEligible
            && integrityLost;
    }

    private boolean datesValid() {
        if (foundedNight < 0L || firstAttackNight < foundedNight
            || firstWarningNight < foundedNight) {
            return false;
        }
        long offset = firstAttackNight - foundedNight;
        long warningLead = firstAttackNight - firstWarningNight;
        return offset >= FIRST_ATTACK_MIN_OFFSET
            && offset <= FIRST_ATTACK_MAX_OFFSET
            && warningLead >= 1L && warningLead <= 2L;
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
}
