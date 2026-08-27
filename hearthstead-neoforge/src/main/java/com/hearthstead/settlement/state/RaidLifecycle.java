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
 * <p>This class is deliberately dormant with respect to the existing raid
 * director. Slice B will switch runtime scheduling to it. Until then the
 * legacy {@code Settlement.pendingRaid} field remains runtime-authoritative;
 * v0 migration copies that one legacy plan into {@link #activePlan} only to
 * preserve state, and permanently disarms its reward eligibility.
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
    /** True only after the 1-9 UUID capture has been explicitly sealed. */
    private boolean participantsTracked;
    private boolean rewardEligible;
    private boolean integrityLost;

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

    /** Closes the first raid; only a held, intact defense remains eligible. */
    public boolean completeFirstRaid(boolean held) {
        if (firstState != FirstRaidState.ACTIVE || activePlan == null) {
            return false;
        }
        firstState = FirstRaidState.COMPLETED;
        rewardEligible = held && participantsTracked && !participants.isEmpty()
            && participants.size() <= MAX_PARTICIPANTS && !integrityLost;
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
        tag.putBoolean("ParticipantsTracked", participantsTracked);
        tag.putBoolean("RewardEligible", rewardEligible);
        tag.putBoolean("IntegrityLost", integrityLost);
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
                || !participants.isEmpty() || participantsTracked || rewardEligible;
            if (carriedState) {
                integrityLost = true;
            }
            foundedNight = UNSET_NIGHT;
            firstAttackNight = UNSET_NIGHT;
            firstWarningNight = UNSET_NIGHT;
            queuedPlan = null;
            activePlan = null;
            participants.clear();
            participantsTracked = false;
            rewardEligible = false;
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
            participants.clear();
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
        }
        if (participantsTracked && participants.isEmpty()) {
            participantsTracked = false;
            rewardEligible = false;
            integrityLost = true;
        }
        if (firstState != FirstRaidState.COMPLETED) {
            rewardEligible = false;
        }
        if (activePlan == null || integrityLost) {
            rewardEligible = false;
        }
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
