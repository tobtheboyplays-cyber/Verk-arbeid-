package com.hearthstead.settlement.state;

import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Persisted authority for recurring raids after the authored first raid.
 *
 * <p>The old recurring runtime treated an empty loaded AABB as completion.
 * That is not evidence: every raider may merely be in an unloaded chunk. A
 * new recurring raid therefore moves through one strict state machine:
 * QUEUED holds the exact rolled plan, ACTIVE holds a sealed 1-9 UUID set,
 * and resolution is possible only after every sealed UUID has received a
 * definitive death or explicit-discard event. Chunk unload is never recorded.
 *
 * <p>The serial watermarks are deliberately part of this same record. One
 * resolved serial is also one processed reward decision, including a lost or
 * ineligible raid, so re-entry and reload cannot turn an old outcome into a
 * fresh Blessing. The one LEGACY_BRIDGE shape is created only by v1/v2
 * migration; it may AABB-close once to preserve an in-flight world, but is
 * structurally incapable of yielding a reward.
 */
public final class RecurringRaidRun {
    public static final int MAX_PARTICIPANTS = RaidLifecycle.MAX_PARTICIPANTS;

    /** Stable persisted identifiers; never serialize ordinal. */
    public enum Stage {
        NONE(0, "none"),
        QUEUED(1, "queued"),
        ACTIVE(2, "active"),
        LEGACY_BRIDGE(3, "legacy_bridge"),
        BLOCKED(4, "blocked");

        private final int wireId;
        private final String id;

        Stage(int wireId, String id) {
            this.wireId = wireId;
            this.id = id;
        }

        public int wireId() {
            return wireId;
        }

        public String id() {
            return id;
        }

        private static Optional<Stage> decode(int wireId, String id) {
            for (Stage stage : values()) {
                if (stage.wireId == wireId && stage.id.equals(id)) {
                    return Optional.of(stage);
                }
            }
            return Optional.empty();
        }
    }

    /** The one-shot decision returned by {@link #resolve(boolean)}. */
    public enum Resolution {
        GRANT_OFFER,
        NO_OFFER,
        INVALID
    }

    private long lastIssuedSerial;
    private long lastResolvedSerial;
    /** Advances for every resolution, even loss/no-reward. */
    private long lastRewardProcessedSerial;
    private Stage stage = Stage.NONE;
    private long activeSerial;
    @Nullable
    private RaidPlan plan;
    private final LinkedHashSet<UUID> participants = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> terminalParticipants = new LinkedHashSet<>();
    private boolean participantsSealed;
    /** Per-run loss of integrity; a valid damaged run may still close, never reward. */
    private boolean integrityLost;

    public Stage stage() {
        return stage;
    }

    public long lastIssuedSerial() {
        return lastIssuedSerial;
    }

    public long lastResolvedSerial() {
        return lastResolvedSerial;
    }

    public long lastRewardProcessedSerial() {
        return lastRewardProcessedSerial;
    }

    public long activeSerial() {
        return activeSerial;
    }

    public Optional<RaidPlan> plan() {
        return Optional.ofNullable(plan);
    }

    /** Allocation-free hot-path check for the compatibility plan mirror. */
    public boolean planMatches(@Nullable RaidPlan expected) {
        return RaidPlan.isValid(plan) && plan.equals(expected);
    }

    public Set<UUID> participants() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(participants));
    }

    public Set<UUID> terminalParticipants() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(terminalParticipants));
    }

    public boolean participantsSealed() {
        return participantsSealed;
    }

    /** Constant-authority membership check without exposing a mutable ledger. */
    public boolean isParticipant(UUID participantId) {
        return participantId != null && participants.contains(participantId);
    }

    public boolean integrityLost() {
        return integrityLost;
    }

    public boolean isEmpty() {
        return stage == Stage.NONE;
    }

    public boolean isQueued() {
        return stage == Stage.QUEUED;
    }

    public boolean isActive() {
        return stage == Stage.ACTIVE;
    }

    public boolean isLegacyBridgeActive() {
        return stage == Stage.LEGACY_BRIDGE && exactLegacyShape();
    }

    public boolean isBlocked() {
        return stage == Stage.BLOCKED;
    }

    /** Queue one exact plan and allocate its never-reused serial. */
    public boolean queue(RaidPlan queuedPlan) {
        if (stage != Stage.NONE || integrityLost || !RaidPlan.isValid(queuedPlan)
            || lastIssuedSerial != lastResolvedSerial
            || lastRewardProcessedSerial != lastResolvedSerial
            || lastIssuedSerial == Long.MAX_VALUE) {
            return false;
        }
        lastIssuedSerial++;
        activeSerial = lastIssuedSerial;
        plan = queuedPlan;
        participants.clear();
        terminalParticipants.clear();
        participantsSealed = false;
        stage = Stage.QUEUED;
        return true;
    }

    /**
     * Atomically validates, captures, seals and activates actual spawned ids.
     * An empty collection means terrain/chunks produced no band and leaves the
     * exact QUEUED plan untouched for retry. A non-empty malformed capture is
     * evidence of runtime disagreement and permanently blocks this ledger.
     */
    public boolean sealAndActivate(RaidPlan expectedPlan,
                                   Collection<UUID> spawnedParticipants) {
        if (stage != Stage.QUEUED || plan == null || !plan.equals(expectedPlan)
            || spawnedParticipants == null) {
            block();
            return false;
        }
        if (spawnedParticipants.isEmpty()) {
            return false;
        }
        LinkedHashSet<UUID> captured = new LinkedHashSet<>();
        boolean valid = spawnedParticipants.size() <= MAX_PARTICIPANTS;
        for (UUID id : spawnedParticipants) {
            valid &= id != null && captured.add(id);
        }
        if (!valid || captured.isEmpty() || captured.size() > MAX_PARTICIPANTS) {
            block();
            return false;
        }
        participants.clear();
        participants.addAll(captured);
        terminalParticipants.clear();
        participantsSealed = true;
        stage = Stage.ACTIVE;
        return true;
    }

    /** Duplicate terminal delivery is idempotent; unknown UUID loses integrity. */
    public boolean recordTerminalParticipant(UUID participantId) {
        if (stage != Stage.ACTIVE || !participantsSealed || participantId == null) {
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

    public boolean allParticipantsTerminal() {
        return stage == Stage.ACTIVE && participantsSealed
            && !participants.isEmpty()
            && terminalParticipants.size() == participants.size()
            && participants.containsAll(terminalParticipants);
    }

    /**
     * Processes one terminal outcome exactly once and clears the active run.
     * The returned GRANT is a permission for the caller to attempt one
     * BlessingState.grantOffer(); the serial is already consumed even if that
     * ledger is quarantined or saturated, so repair/reload cannot retry it.
     */
    public Resolution resolve(boolean held) {
        if (!allParticipantsTerminal() || activeSerial <= lastResolvedSerial
            || activeSerial != lastIssuedSerial
            || lastRewardProcessedSerial != lastResolvedSerial) {
            return Resolution.INVALID;
        }
        long resolved = activeSerial;
        boolean grant = held && !integrityLost;
        lastResolvedSerial = resolved;
        lastRewardProcessedSerial = resolved;
        clearOpenRun();
        return grant ? Resolution.GRANT_OFFER : Resolution.NO_OFFER;
    }

    /** Close the exact explicitly migrated v1/v2 bridge, never with reward. */
    public boolean completeLegacyBridge(RaidPlan resolvedPlan) {
        if (!isLegacyBridgeActive() || plan == null || resolvedPlan == null
            || !plan.equals(resolvedPlan)) {
            return false;
        }
        clearOpenRun();
        return true;
    }

    public void markIntegrityLost() {
        if (stage == Stage.ACTIVE) {
            integrityLost = true;
        } else if (stage != Stage.BLOCKED) {
            block();
        }
    }

    /** Permanent fail-closed state for missing/malformed v3 state. */
    public void block() {
        // Quarantining an issued run is itself a definitive no-reward
        // decision. Collapse every known watermark onto the greatest
        // non-negative serial so the blocked record round-trips without ever
        // making that serial available for replay.
        long consumed = Math.max(0L, Math.max(activeSerial,
            Math.max(lastIssuedSerial,
                Math.max(lastResolvedSerial, lastRewardProcessedSerial))));
        lastIssuedSerial = consumed;
        lastResolvedSerial = consumed;
        lastRewardProcessedSerial = consumed;
        stage = Stage.BLOCKED;
        activeSerial = 0L;
        plan = null;
        participants.clear();
        terminalParticipants.clear();
        participantsSealed = false;
        integrityLost = true;
    }

    public static RecurringRaidRun blocked() {
        RecurringRaidRun state = new RecurringRaidRun();
        state.block();
        return state;
    }

    /** The only constructor for the unauditable pre-v3 recurring bridge. */
    public static RecurringRaidRun migrateLegacy(RaidPlan legacyPlan) {
        if (!RaidPlan.isValid(legacyPlan)) {
            return blocked();
        }
        RecurringRaidRun state = new RecurringRaidRun();
        state.stage = Stage.LEGACY_BRIDGE;
        state.plan = legacyPlan;
        return state;
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("StageWireId", stage.wireId());
        tag.putString("Stage", stage.id());
        tag.putLong("LastIssuedSerial", lastIssuedSerial);
        tag.putLong("LastResolvedSerial", lastResolvedSerial);
        tag.putLong("LastRewardProcessedSerial", lastRewardProcessedSerial);
        tag.putLong("ActiveSerial", activeSerial);
        tag.putBoolean("ParticipantsSealed", participantsSealed);
        tag.putBoolean("IntegrityLost", integrityLost);
        if (plan != null) {
            tag.put("Plan", plan.writeNbt());
        }
        tag.put("Participants", uuidList(participants));
        tag.put("TerminalParticipants", uuidList(terminalParticipants));
        return tag;
    }

    /** Strict v3 parser. Any malformed shape becomes permanently BLOCKED. */
    public static RecurringRaidRun readNbt(CompoundTag tag) {
        if (tag == null
            || !tag.contains("StageWireId", Tag.TAG_INT)
            || !tag.contains("Stage", Tag.TAG_STRING)
            || !tag.contains("LastIssuedSerial", Tag.TAG_LONG)
            || !tag.contains("LastResolvedSerial", Tag.TAG_LONG)
            || !tag.contains("LastRewardProcessedSerial", Tag.TAG_LONG)
            || !tag.contains("ActiveSerial", Tag.TAG_LONG)
            || !tag.contains("ParticipantsSealed", Tag.TAG_BYTE)
            || !tag.contains("IntegrityLost", Tag.TAG_BYTE)
            || !(tag.get("Participants") instanceof ListTag participantList)
            || !(tag.get("TerminalParticipants") instanceof ListTag terminalList)) {
            return blocked();
        }
        Optional<Stage> decodedStage = Stage.decode(tag.getInt("StageWireId"),
            tag.getString("Stage"));
        if (decodedStage.isEmpty()) {
            return blocked();
        }

        RecurringRaidRun state = new RecurringRaidRun();
        state.stage = decodedStage.get();
        state.lastIssuedSerial = tag.getLong("LastIssuedSerial");
        state.lastResolvedSerial = tag.getLong("LastResolvedSerial");
        state.lastRewardProcessedSerial = tag.getLong("LastRewardProcessedSerial");
        state.activeSerial = tag.getLong("ActiveSerial");
        state.participantsSealed = tag.getBoolean("ParticipantsSealed");
        state.integrityLost = tag.getBoolean("IntegrityLost");

        Tag rawPlan = tag.get("Plan");
        if (rawPlan != null) {
            Optional<RaidPlan> decodedPlan = RaidPlan.tryReadNbt(rawPlan);
            if (decodedPlan.isEmpty()) {
                return blocked();
            }
            state.plan = decodedPlan.get();
        }
        if (!readUuidList(participantList, state.participants)
            || !readUuidList(terminalList, state.terminalParticipants)
            || !state.persistedShapeValid(rawPlan != null)) {
            return blocked();
        }
        return state;
    }

    private boolean persistedShapeValid(boolean hadPlanTag) {
        if (lastIssuedSerial < 0L || lastResolvedSerial < 0L
            || lastRewardProcessedSerial < 0L
            || lastRewardProcessedSerial != lastResolvedSerial
            || lastResolvedSerial > lastIssuedSerial
            || participants.size() > MAX_PARTICIPANTS
            || terminalParticipants.size() > MAX_PARTICIPANTS
            || !participants.containsAll(terminalParticipants)) {
            return false;
        }
        return switch (stage) {
            case NONE -> !hadPlanTag && plan == null && activeSerial == 0L
                && !participantsSealed && participants.isEmpty()
                && terminalParticipants.isEmpty() && !integrityLost
                && lastIssuedSerial == lastResolvedSerial;
            case QUEUED -> hadPlanTag && RaidPlan.isValid(plan)
                && activeSerial > 0L && activeSerial == lastIssuedSerial
                && lastResolvedSerial < Long.MAX_VALUE
                && activeSerial == lastResolvedSerial + 1L
                && !participantsSealed && participants.isEmpty()
                && terminalParticipants.isEmpty() && !integrityLost;
            case ACTIVE -> hadPlanTag && RaidPlan.isValid(plan)
                && activeSerial > 0L && activeSerial == lastIssuedSerial
                && lastResolvedSerial < Long.MAX_VALUE
                && activeSerial == lastResolvedSerial + 1L
                && participantsSealed && !participants.isEmpty();
            case LEGACY_BRIDGE -> hadPlanTag && RaidPlan.isValid(plan)
                && exactLegacyShape();
            case BLOCKED -> !hadPlanTag && plan == null && activeSerial == 0L
                && !participantsSealed && participants.isEmpty()
                && terminalParticipants.isEmpty() && integrityLost
                && lastIssuedSerial == lastResolvedSerial;
        };
    }

    private boolean exactLegacyShape() {
        return stage == Stage.LEGACY_BRIDGE && RaidPlan.isValid(plan)
            && lastIssuedSerial == 0L && lastResolvedSerial == 0L
            && lastRewardProcessedSerial == 0L && activeSerial == 0L
            && !participantsSealed && participants.isEmpty()
            && terminalParticipants.isEmpty() && !integrityLost;
    }

    private void clearOpenRun() {
        stage = Stage.NONE;
        activeSerial = 0L;
        plan = null;
        participants.clear();
        terminalParticipants.clear();
        participantsSealed = false;
        integrityLost = false;
    }

    private static ListTag uuidList(Collection<UUID> ids) {
        ListTag list = new ListTag();
        for (UUID id : ids) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("Id", id);
            list.add(entry);
        }
        return list;
    }

    private static boolean readUuidList(ListTag list, LinkedHashSet<UUID> out) {
        if (list.size() > MAX_PARTICIPANTS) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            Tag rawEntry = list.get(i);
            if (!(rawEntry instanceof CompoundTag entry) || !entry.hasUUID("Id")
                || !out.add(entry.getUUID("Id"))) {
                return false;
            }
        }
        return true;
    }
}
