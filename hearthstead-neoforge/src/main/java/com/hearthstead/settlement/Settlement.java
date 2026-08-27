package com.hearthstead.settlement;

import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.state.BlessingState;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RaidProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One founded settlement. Lives in {@link SettlementSavedData}; the member
 * list is kept here (not only on the entities) so population and employment
 * stay correct even while settler chunks are unloaded.
 */
public class Settlement {
    public static final int DEFAULT_RADIUS = 48;
    public static final int BASE_CAPACITY = 8;

    public final UUID id;
    public String name;
    public BlockPos center;
    public int radius = DEFAULT_RADIUS;
    public final List<SettlerRecord> settlers = new ArrayList<>();
    /** Automatically detected buildings (homes first; more types later). */
    public final List<Building> buildings = new ArrayList<>();

    /** Eligible seconds accumulated toward this settlement's locked target. */
    public int recruitProgress;
    /** Independently proves the universal two-day minimum was qualified. */
    public int recruitQualifiedSeconds;
    /** Deterministic 2-4 day target, locked until a traveler really spawns. */
    public int recruitTarget;
    /** Successful traveler-spawn generation used to derive the next target. */
    public int recruitCycle;
    public long alertUntilGameTime;
    public BlockPos alertPos;
    /** A traveler currently walking toward the hearth, if any. */
    public UUID travelerId;
    public long travelerSinceGameTime;

    /** Refreshed every second by the hearth block entity. */
    public int foodCache;
    /**
     * How badly the world wants to raid this place tonight. Persisted, and
     * the only thing that decides whether a raid happens — there is no night
     * counter with a safe floor (see RaidPressure).
     */
    public final com.hearthstead.settlement.raid.RaidPressure raidPressure =
        new com.hearthstead.settlement.raid.RaidPressure();

    /**
     * M1 server-authoritative settlement state. These records belong to the
     * shared village, never to an individual player. Slice B will make the
     * raid director consume {@link #raidLifecycle}; until then it is persisted
     * but intentionally dormant.
     */
    public RaidProfile raidProfile = RaidProfile.PEACEFUL;
    public RaidLifecycle raidLifecycle = new RaidLifecycle();
    public BlessingState blessingState = new BlessingState();
    public GuardOrder guardOrder = new GuardOrder();

    /**
     * Enemies this settlement has met, and the raid it is currently
     * expecting. This is the Tingbok's enemy gallery: captains persist so a
     * beaten one can come back harder and by a different road, which is the
     * tail MineColonies' own feature requests (#113, #129) keep asking for.
     */
    public final List<com.hearthstead.settlement.raid.RaidCaptain> raidCaptains =
        new ArrayList<>();
    /**
     * Transitional legacy runtime bridge. The current RaidDirector remains
     * authoritative for this field until Slice B switches atomically to
     * {@link #raidLifecycle}; M1 never tries to make both objects drive one
     * raid. A v0 pending plan is copied once into lifecycle migration solely
     * as an ineligible ACTIVE historical state.
     */
    public com.hearthstead.settlement.raid.RaidPlan pendingRaid;
    /**
     * Set when a laden raider gets clear of the settlement. Decides whether
     * the raid resolves as repelled or lost, which is the difference between
     * pressure rising and easing.
     */
    public boolean raidLootEscaped;
    /**
     * What the raid currently in progress has taken and who it has hurt,
     * tallied live as it happens ({@code RaiderLootGoal}'s successful
     * withdrawal, {@code RaiderEntity#doHurtTarget}) and folded into a
     * {@link com.hearthstead.settlement.raid.RaidLogEntry} then reset to
     * zero when {@code RaidDirector#resolveIfOver} closes the raid out. Never
     * a running lifetime total -- always "this raid, so far".
     */
    public int raidItemsStolenTonight;
    public int raidSettlersHurtTonight;
    /**
     * The settlement's memory of raids past: the morning defense report,
     * kept rather than only ever spoken once in chat (D-A3-8's "scar").
     * Capped in {@code RaidDirector#recordAftermath} the same way the enemy
     * gallery is capped -- a settlement remembers its history, not an
     * unbounded diary.
     */
    public final List<com.hearthstead.settlement.raid.RaidLogEntry> raidLog =
        new ArrayList<>();

    /**
     * SAGA v1 (DESIGN.md system 4): the settlement's named cast within the
     * enemy gallery above -- up to {@link com.hearthstead.saga.CaptainRoster#MAX_ROSTER}
     * of the {@link #raidCaptains} carry an earned identity here, keyed by
     * the same id. Generated once, lazily, by
     * {@link com.hearthstead.saga.CaptainRoster#ensureRoster}; never a
     * second source of truth for menace, approach or the win/loss record,
     * all of which stay on {@code RaidCaptain} untouched.
     */
    public final List<com.hearthstead.saga.Captain> sagaRoster = new ArrayList<>();
    /**
     * Set for one tick's worth of bookkeeping when the raider wearing the
     * captain flag is personally killed mid-raid (see
     * {@code RaiderEntity#die}), read and cleared by
     * {@code RaidDirector#resolveIfOver} the same way {@link #raidLootEscaped}
     * is -- so a captain's death is told apart from their band merely being
     * driven off, which is what triggers Saga's lieutenant succession.
     */
    @javax.annotation.Nullable
    public UUID raidCaptainSlainId;

    /** Average morale of currently loaded members, refreshed every second. */
    public int moraleCache = 60;
    /** Who speaks for the settlement. See {@link Mayor}. Null when leaderless. */
    @javax.annotation.Nullable
    public UUID mayorId;
    /** Game time the current mayor took office; a new one settles in slowly. */
    public long mayorSince;
    /** Game time until which the settlement is mourning and cannot appoint. */
    public long mourningUntil;

    public Settlement(UUID id, String name, BlockPos center) {
        this.id = id;
        this.name = name;
        this.center = center;
        this.recruitTarget = RecruitmentPolicy.targetFor(id, 0);
    }

    /** Three founders shelter at the hearth; growth beyond that needs beds. */
    public int capacity() {
        return 3 + validBedCount();
    }

    public int validBedCount() {
        int beds = 0;
        for (Building b : buildings) {
            if (b.valid && b.type.housesResidents()) {
                beds += b.beds.size();
            }
        }
        return beds;
    }

    public int validHomeCount() {
        int homes = 0;
        for (Building b : buildings) {
            if (b.valid && b.type.housesResidents()) {
                homes++;
            }
        }
        return homes;
    }

    public int population() {
        return settlers.size();
    }

    public int employed() {
        int n = 0;
        for (SettlerRecord r : settlers) {
            if (r.profession.employed()) {
                n++;
            }
        }
        return n;
    }

    public boolean alertActive(long gameTime) {
        return gameTime < alertUntilGameTime;
    }

    public boolean inside(BlockPos pos) {
        return pos.distSqr(center) <= (double) radius * radius;
    }

    public SettlerRecord record(UUID entityId) {
        for (SettlerRecord r : settlers) {
            if (r.entityId.equals(entityId)) {
                return r;
            }
        }
        return null;
    }

    /** Idempotent: updates the existing record instead of duplicating it. */
    public void putRecord(UUID entityId, String settlerName, Profession profession) {
        SettlerRecord r = record(entityId);
        if (r == null) {
            settlers.add(new SettlerRecord(entityId, settlerName, profession));
        } else {
            r.name = settlerName;
            r.profession = profession;
        }
    }

    public boolean removeRecord(UUID entityId) {
        return settlers.removeIf(r -> r.entityId.equals(entityId));
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        if (mayorId != null) {
            tag.putUUID("MayorId", mayorId);
        }
        tag.putLong("MayorSince", mayorSince);
        tag.putLong("MourningUntil", mourningUntil);
        tag.putString("Name", name);
        tag.put("Center", NbtUtils.writeBlockPos(center));
        tag.putInt("Radius", radius);
        tag.putInt("RecruitProgress", recruitProgress);
        tag.putInt("RecruitQualifiedSeconds", recruitQualifiedSeconds);
        tag.putInt("RecruitTarget", recruitTarget);
        tag.putInt("RecruitCycle", recruitCycle);
        tag.putLong("AlertUntil", alertUntilGameTime);
        if (alertPos != null) {
            tag.put("AlertPos", NbtUtils.writeBlockPos(alertPos));
        }
        if (travelerId != null) {
            tag.putUUID("TravelerId", travelerId);
            tag.putLong("TravelerSince", travelerSinceGameTime);
        }
        ListTag list = new ListTag();
        for (SettlerRecord r : settlers) {
            CompoundTag rt = new CompoundTag();
            rt.putUUID("EntityId", r.entityId);
            rt.putString("Name", r.name);
            rt.putByte("Profession", r.profession.id());
            list.add(rt);
        }
        tag.put("Settlers", list);
        ListTag buildingList = new ListTag();
        for (Building b : buildings) {
            buildingList.add(b.writeNbt());
        }
        tag.put("Buildings", buildingList);
        tag.putInt("RaidProfileWireId", raidProfile.wireId());
        tag.putString("RaidProfile", raidProfile.id());
        tag.put("RaidLifecycle", raidLifecycle.writeNbt());
        tag.put("BlessingState", blessingState.writeNbt());
        tag.put("GuardOrder", guardOrder.writeNbt());
        tag.put("RaidPressure", raidPressure.writeNbt());
        ListTag captainList = new ListTag();
        for (com.hearthstead.settlement.raid.RaidCaptain c : raidCaptains) {
            captainList.add(c.writeNbt());
        }
        tag.put("RaidCaptains", captainList);
        if (pendingRaid != null) {
            tag.put("PendingRaid", pendingRaid.writeNbt());
        }
        tag.putBoolean("RaidLootEscaped", raidLootEscaped);
        tag.putInt("RaidItemsStolenTonight", raidItemsStolenTonight);
        tag.putInt("RaidSettlersHurtTonight", raidSettlersHurtTonight);
        ListTag raidLogList = new ListTag();
        for (com.hearthstead.settlement.raid.RaidLogEntry e : raidLog) {
            raidLogList.add(e.writeNbt());
        }
        tag.put("RaidLog", raidLogList);
        ListTag sagaRosterList = new ListTag();
        for (com.hearthstead.saga.Captain c : sagaRoster) {
            sagaRosterList.add(c.writeNbt());
        }
        tag.put("SagaRoster", sagaRosterList);
        if (raidCaptainSlainId != null) {
            tag.putUUID("RaidCaptainSlainId", raidCaptainSlainId);
        }
        return tag;
    }

    /**
     * Reads a settlement tag produced by the current code. Standalone
     * GameTest fixtures and round-trip tests do not carry the root
     * {@code DataVersion}, so this overload deliberately assumes the current
     * schema. Real world loads always call {@link #readNbt(CompoundTag, int)}
     * with the root source version, including explicit v0 migration.
     */
    public static Settlement readNbt(CompoundTag tag) {
        return readNbt(tag, SettlementSavedData.CURRENT_DATA_VERSION);
    }

    public static Settlement readNbt(CompoundTag tag, int sourceVersion) {
        if (sourceVersion < 0 || sourceVersion > SettlementSavedData.CURRENT_DATA_VERSION) {
            throw new SettlementSavedData.DataVersionException(
                "Unsupported settlement source version " + sourceVersion);
        }
        Settlement s = new Settlement(tag.getUUID("Id"), tag.getString("Name"),
            NbtUtils.readBlockPos(tag, "Center").orElse(BlockPos.ZERO));
        s.radius = tag.getInt("Radius");
        if (s.radius <= 0) {
            s.radius = DEFAULT_RADIUS;
        }
        s.mayorId = tag.hasUUID("MayorId") ? tag.getUUID("MayorId") : null;
        s.mayorSince = tag.getLong("MayorSince");
        s.mourningUntil = tag.getLong("MourningUntil");
        readRecruitment(tag, sourceVersion, s);
        s.alertUntilGameTime = tag.getLong("AlertUntil");
        if (tag.contains("AlertPos")) {
            s.alertPos = NbtUtils.readBlockPos(tag, "AlertPos").orElse(null);
        }
        if (tag.hasUUID("TravelerId")) {
            s.travelerId = tag.getUUID("TravelerId");
            s.travelerSinceGameTime = tag.getLong("TravelerSince");
            // A real traveler spawn clears both attraction clocks before the
            // save can be written. Non-zero clocks beside a waiting guest are
            // therefore malformed hidden progress; quarantine only the
            // counters while preserving the already-locked next target.
            s.recruitProgress = 0;
            s.recruitQualifiedSeconds = 0;
        }
        ListTag list = tag.getList("Settlers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag rt = list.getCompound(i);
            s.settlers.add(new SettlerRecord(rt.getUUID("EntityId"), rt.getString("Name"),
                Profession.byId(rt.getByte("Profession"))));
        }
        ListTag buildingList = tag.getList("Buildings", Tag.TAG_COMPOUND);
        for (int i = 0; i < buildingList.size(); i++) {
            s.buildings.add(Building.readNbt(buildingList.getCompound(i)));
        }
        if (tag.contains("RaidPressure")) {
            s.raidPressure.copyFrom(com.hearthstead.settlement.raid.RaidPressure
                .readNbt(tag.getCompound("RaidPressure")));
        }
        ListTag captainList = tag.getList("RaidCaptains", Tag.TAG_COMPOUND);
        for (int i = 0; i < captainList.size(); i++) {
            s.raidCaptains.add(com.hearthstead.settlement.raid.RaidCaptain
                .readNbt(captainList.getCompound(i)));
        }
        s.raidLootEscaped = tag.getBoolean("RaidLootEscaped");
        // New keys; absent on an older save defaults to zero/empty, which is
        // exactly right -- an old save has no raid mid-flight and no history.
        s.raidItemsStolenTonight = tag.getInt("RaidItemsStolenTonight");
        s.raidSettlersHurtTonight = tag.getInt("RaidSettlersHurtTonight");
        ListTag raidLogList = tag.getList("RaidLog", Tag.TAG_COMPOUND);
        for (int i = 0; i < raidLogList.size(); i++) {
            s.raidLog.add(com.hearthstead.settlement.raid.RaidLogEntry
                .readNbt(raidLogList.getCompound(i)));
        }
        boolean malformedPendingRaid = false;
        Tag rawPendingRaid = tag.get("PendingRaid");
        if (rawPendingRaid != null) {
            Optional<com.hearthstead.settlement.raid.RaidPlan> decoded =
                com.hearthstead.settlement.raid.RaidPlan.tryReadNbt(rawPendingRaid);
            if (decoded.isPresent()) {
                s.pendingRaid = decoded.get();
            } else {
                // Never turn unknown objectives or wrong numeric tag types
                // into a fabricated live raid. The versioned lifecycle below
                // retains the integrity loss so it cannot become a new first
                // raid or issue a reward after reload.
                malformedPendingRaid = true;
            }
        }
        // New keys; absent on an older save defaults to an empty roster and
        // no captain mid-death -- exactly right, an old save has no Saga
        // identities yet and no raid mid-flight to have killed one in.
        ListTag sagaRosterList = tag.getList("SagaRoster", Tag.TAG_COMPOUND);
        for (int i = 0; i < sagaRosterList.size(); i++) {
            s.sagaRoster.add(com.hearthstead.saga.Captain
                .readNbt(sagaRosterList.getCompound(i)));
        }
        s.raidCaptainSlainId = tag.hasUUID("RaidCaptainSlainId")
            ? tag.getUUID("RaidCaptainSlainId") : null;

        if (sourceVersion <= 0) {
            // V0 had no authored profile/lifecycle and no auditable reward
            // participants. A real pending raid continues through the legacy
            // field above, but it must never become a free Blessing. RaidLog
            // is the v0 persisted result/history; forecast fields in
            // RaidPressure are deliberately ignored as queued plans.
            s.raidProfile = RaidProfile.PEACEFUL;
            s.raidLifecycle = RaidLifecycle.migrateV0(s.pendingRaid,
                hasLegacyRaidResult(s), malformedPendingRaid);
            s.blessingState = new BlessingState();
            s.guardOrder = new GuardOrder();
        } else {
            s.raidProfile = readRaidProfile(tag).orElse(RaidProfile.PEACEFUL);
            if (tag.contains("RaidLifecycle", Tag.TAG_COMPOUND)) {
                s.raidLifecycle = RaidLifecycle.readNbt(tag.getCompound("RaidLifecycle"));
            } else {
                // A versioned settlement may not silently become a pristine new
                // first-raid lifecycle after its authoritative record was
                // removed or changed to the wrong tag type. Reading an empty
                // compound retains UNINITIALIZED but marks integrity lost, so
                // a later runtime bootstrap cannot mint a repeat reward.
                s.raidLifecycle = RaidLifecycle.readNbt(new CompoundTag());
            }
            if (tag.contains("BlessingState", Tag.TAG_COMPOUND)) {
                s.blessingState = BlessingState.readNbt(tag.getCompound("BlessingState"));
            } else {
                s.blessingState = BlessingState.quarantinedEmpty();
            }
            if (tag.contains("GuardOrder", Tag.TAG_COMPOUND)) {
                s.guardOrder = GuardOrder.readNbt(tag.getCompound("GuardOrder"));
            }
            if (malformedPendingRaid) {
                s.raidLifecycle.markIntegrityLost();
            }
        }
        return s;
    }

    /**
     * DataVersion 2 replaces the old 200-279 point gauge with real eligible
     * seconds. V0/v1 progress migrates as a fraction of its old locked
     * target, deterministically against the settlement UUID. Wrong tag types
     * or impossible ranges reset only these clocks to a safe non-ready state;
     * they never fabricate a nearly-complete candidate during load.
     */
    private static void readRecruitment(CompoundTag tag, int sourceVersion,
                                        Settlement settlement) {
        if (sourceVersion <= 1) {
            boolean hasProgress = tag.contains("RecruitProgress", Tag.TAG_INT);
            boolean hasTarget = tag.contains("RecruitTarget", Tag.TAG_INT);
            if (!hasProgress || !hasTarget) {
                resetRecruitmentClocks(settlement);
                return;
            }
            int oldProgress = tag.getInt("RecruitProgress");
            int oldTarget = tag.getInt("RecruitTarget");
            // An untouched legacy settlement persisted 0/0 before its first
            // attractive second. It is valid, but carries no fraction.
            if (oldProgress == 0 && oldTarget == 0) {
                resetRecruitmentClocks(settlement);
                return;
            }
            // The complete v0/v1 contract was target 200..279 and progress
            // between zero and that target. Anything else is quarantined.
            if (oldTarget < 200 || oldTarget > 279
                || oldProgress < 0 || oldProgress > oldTarget) {
                resetRecruitmentClocks(settlement);
                return;
            }
            settlement.recruitCycle = 0;
            settlement.recruitTarget = RecruitmentPolicy.targetFor(settlement.id, 0);
            settlement.recruitProgress = proportional(oldProgress, oldTarget,
                settlement.recruitTarget);
            settlement.recruitQualifiedSeconds = proportional(oldProgress, oldTarget,
                RecruitmentPolicy.MIN_QUALIFIED_SECONDS);
            return;
        }

        if (!tag.contains("RecruitProgress", Tag.TAG_INT)
            || !tag.contains("RecruitQualifiedSeconds", Tag.TAG_INT)
            || !tag.contains("RecruitTarget", Tag.TAG_INT)
            || !tag.contains("RecruitCycle", Tag.TAG_INT)) {
            resetRecruitmentClocks(settlement);
            return;
        }
        int progress = tag.getInt("RecruitProgress");
        int qualified = tag.getInt("RecruitQualifiedSeconds");
        int target = tag.getInt("RecruitTarget");
        int cycle = tag.getInt("RecruitCycle");
        if (cycle < 0
            || target != RecruitmentPolicy.targetFor(settlement.id, cycle)
            || progress < 0 || progress > target
            || qualified < 0
            || qualified > RecruitmentPolicy.MIN_QUALIFIED_SECONDS) {
            resetRecruitmentClocks(settlement);
            return;
        }
        settlement.recruitCycle = cycle;
        settlement.recruitTarget = target;
        settlement.recruitProgress = progress;
        settlement.recruitQualifiedSeconds = qualified;
    }

    private static int proportional(int numerator, int denominator, int scale) {
        long scaled = (long) numerator * scale;
        return (int) ((scaled + denominator / 2L) / denominator);
    }

    private static void resetRecruitmentClocks(Settlement settlement) {
        settlement.recruitCycle = 0;
        settlement.recruitProgress = 0;
        settlement.recruitQualifiedSeconds = 0;
        settlement.recruitTarget = RecruitmentPolicy.targetFor(settlement.id, 0);
    }

    private static Optional<RaidProfile> readRaidProfile(CompoundTag tag) {
        if (tag.contains("RaidProfileWireId", Tag.TAG_INT)) {
            Optional<RaidProfile> wire = RaidProfile.tryFromWireId(
                tag.getInt("RaidProfileWireId"));
            if (wire.isEmpty()) {
                return Optional.empty();
            }
            if (tag.contains("RaidProfile", Tag.TAG_STRING)
                && !wire.get().id().equals(tag.getString("RaidProfile"))) {
                return Optional.empty();
            }
            return wire;
        }
        return RaidProfile.tryFromId(tag.getString("RaidProfile"));
    }

    private static boolean hasLegacyRaidResult(Settlement settlement) {
        if (!settlement.raidLog.isEmpty()) {
            return true;
        }
        // Captain records predate the morning RaidLog. A win or loss is an
        // equally decisive v0 result marker; merely knowing a named captain
        // is not, because rosters can be generated before the first attack.
        for (com.hearthstead.settlement.raid.RaidCaptain captain
            : settlement.raidCaptains) {
            if (captain.victories() > 0 || captain.defeats() > 0) {
                return true;
            }
        }
        return false;
    }

    public static class SettlerRecord {
        public final UUID entityId;
        public String name;
        public Profession profession;

        public SettlerRecord(UUID entityId, String name, Profession profession) {
            this.entityId = entityId;
            this.name = name;
            this.profession = profession;
        }
    }
}
