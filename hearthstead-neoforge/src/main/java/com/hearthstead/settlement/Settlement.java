package com.hearthstead.settlement;

import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.equipment.EquipmentRequestQueue;
import com.hearthstead.settlement.journey.JourneyEvent;
import com.hearthstead.settlement.journey.JourneyEvidence;
import com.hearthstead.settlement.journey.JourneyIds;
import com.hearthstead.settlement.journey.JourneyMigration;
import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.journey.JourneySource;
import com.hearthstead.settlement.journey.JourneyState;
import com.hearthstead.settlement.journey.JourneyTransactionIds;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.BlessingState;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.FoundingJourney;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.state.GuardOrderBook;
import com.hearthstead.settlement.state.RecurringRaidRun;
import com.hearthstead.settlement.state.RaidLifecycle;
import com.hearthstead.settlement.state.RaidProfile;
import com.hearthstead.settlement.state.TargetBlessingState;
import com.hearthstead.settlement.raid.FirstRaidReadiness;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

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
    /** Persisted manual delivery order shared by Courier AI and its queue UI. */
    public EquipmentRequestQueue equipmentRequestQueue =
        new EquipmentRequestQueue();
    /**
     * Automatically detected buildings (homes first; more types later).
     * Structural changes invalidate the transient Blessing spatial index at
     * the collection boundary, so every add/remove path (including plaque
     * loss in BuildingManager and NBT load) is covered without relying on a
     * caller remembering a second bookkeeping call.
     */
    public final List<Building> buildings = new BlessingTrackedBuildingList();

    /** Quarantines persisted Work Zones that belong to another dimension. */
    public boolean quarantineWorkZonesForDimension(ResourceLocation dimension) {
        boolean changed = false;
        // A malformed legacy/save-test row must never crash level loading.
        // Remove nulls at this persistence boundary, then quarantine every
        // remaining real building normally.
        for (int index = buildings.size() - 1; index >= 0; index--) {
            Building building = buildings.get(index);
            if (building == null) {
                buildings.remove(index);
                changed = true;
                continue;
            }
            changed |= building.quarantineWorkZoneDimension(dimension);
        }
        return changed;
    }

    /** Runtime-only, lazily rebuilt; never written into settlement NBT. */
    private final BuildingBlessingIndex buildingBlessingIndex =
        new BuildingBlessingIndex();
    /** Monotonic change token used by throttled raider zone checks. */
    private long buildingBlessingRevision;

    /** Eligible seconds accumulated toward this settlement's locked target. */
    public int recruitProgress;
    /** Independently proves the locked timing profile's minimum was qualified. */
    public int recruitQualifiedSeconds;
    /** Deterministic qualified-second target, locked until a traveler really spawns. */
    public int recruitTarget;
    /** Successful traveler-spawn generation used to derive the next target. */
    public int recruitCycle;
    /**
     * Strict v7 recruitment authority. The four scalar fields above and the
     * two traveler mirrors below remain only for old callers/GameTests; every
     * runtime mutation must go through {@link #applyRecruitment}.
     */
    public RecruitmentTransaction recruitment;
    /** Saved day that published the ordinary Tavern visitor batch, or -1 before any batch. */
    public long lastTavernVisitorDay = -1L;
    /** Exact outside feet cell selected for the published daily visitor's return journey. */
    public BlockPos tavernVisitorDepartureOrigin;
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
    /** Strict v3 authority for every recurring raid after the first. */
    public RecurringRaidRun recurringRaidRun = new RecurringRaidRun();
    public com.hearthstead.settlement.raid.RaidCoinRewards raidCoinRewards = new com.hearthstead.settlement.raid.RaidCoinRewards();
    public BlessingState blessingState = new BlessingState();
    /** Completed repair count modulo four; preserves both 25% and 50% cadence. */
    private int repairDiscountProgress;

    public int repairDiscountProgress() {
        return repairDiscountProgress;
    }

    public void recordScarMended() {
        repairDiscountProgress = (repairDiscountProgress + 1) % 4;
    }
    /** Current per-Guard authority. Every runtime query is keyed by Guard UUID. */
    public GuardOrderBook guardOrders = GuardOrderBook.fresh();
    public com.hearthstead.settlement.guard.BannerTeamBook bannerTeams = new com.hearthstead.settlement.guard.BannerTeamBook();
    /** Player-authored patrol routes (PATROL ROUTES lane), shared by every member. */
    public com.hearthstead.settlement.guard.patrol.PatrolRouteBook patrolRoutes =
        new com.hearthstead.settlement.guard.patrol.PatrolRouteBook();
    /**
     * V0-v7 migration carrier only. Runtime networking, AI and readiness must
     * never read this settlement-global value.
     */
    @Deprecated(forRemoval = false)
    public GuardOrder guardOrder = new GuardOrder();
    /** Premium first-session onboarding; fixtures/legacy worlds default skipped. */
    public FoundingJourney foundingJourney = FoundingJourney.skipped();
    /** Schema-3/definition-2 authoritative 45-milestone evidence ledger. */
    public JourneyState journeyState;
    /** Durable proof plus live gate for the authored first raid. */
    public FirstRaidReadiness firstRaidReadiness = FirstRaidReadiness.quarantined();
    /** Exact current charged-emblem authority; Journey remains immutable history. */
    public EmploymentAuthorizationLedger employmentAuthorizations =
        EmploymentAuthorizationLedger.fresh();
    /** Player-owned Job Emblems returned by the Staff Fire action. */
    public PendingPlayerDeliveryLedger employmentReturns =
        new PendingPlayerDeliveryLedger();

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
    /**
     * Players who have used this settlement's Banner: the members who get its
     * town chat ({@link TownChat}). Insertion-ordered, never pruned.
     */
    public final Set<UUID> members = new java.util.LinkedHashSet<>();
    /**
     * The Warehouse the seated Mayor currently helps to run.  This is a
     * logistics authority, deliberately separate from {@link Building#workers}:
     * a Mayor remains Mayor and must never enter the ordinary hire/fire path.
     */
    @javax.annotation.Nullable
    public UUID mayorCourierWarehouseId;
    /** Game time the current mayor took office; a new one settles in slowly. */
    public long mayorSince;
    /** Game time until which the settlement is mourning and cannot appoint. */
    public long mourningUntil;

    /** Makes {@code player} a member (town chat). True when newly added. */
    public boolean addMember(UUID player) {
        return player != null && members.add(player);
    }

    public Settlement(UUID id, String name, BlockPos center) {
        this.id = id;
        this.name = name;
        this.center = center;
        this.recruitTarget = RecruitmentPolicy.targetFor(id, 0);
        this.recruitment = RecruitmentTransaction.fresh(id);
        this.journeyState = JourneyState.skipped(id);
    }

    /**
     * Places for the four founders by the Banner. They are not beds: every
     * settler recruited beyond the founders needs a real free bed, and the
     * founders themselves move into beds as homes appear. Must equal
     * {@code SettlementManager.FOUNDER_COUNT} (pinned by a unit test).
     */
    public static final int FOUNDER_PLACES = 4;

    /**
     * How many people this settlement can hold (owner decision 26 Sep):
     * the founders always fit by the Banner, and beds raise that only once
     * there are more of them than founders. Not {@code 4 + beds}: founders
     * claim beds as homes appear, and admission needs a real free bed, so an
     * additive total would show room that recruitment then refuses.
     */
    public int capacity() {
        return Math.max(FOUNDER_PLACES, validBedCount());
    }

    /**
     * Beds one House may count (owner decision 26 Sep): 4, 6 with the
     * Townhouses node, 8 with Manors. Kept current by the tech tree
     * (CommonsEffects.refreshHousing, every 2 s on a live server); 0 means
     * "not known yet" and counts every bed, as before the cap existed.
     * Not saved: it is derived from the settlement's learned nodes.
     */
    public int houseBedCap;

    /** Beds of this home that count toward capacity (House cap applied). */
    public int countedBeds(Building b) {
        return houseBedCap > 0 && b.type == com.hearthstead.building.BuildingType.HOUSE
            ? Math.min(houseBedCap, b.beds.size()) : b.beds.size();
    }

    public int validBedCount() {
        int beds = 0;
        for (Building b : buildings) {
            if (b.valid && b.type.housesResidents()) {
                beds += countedBeds(b);
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

    /**
     * Returns whether every corner of an inclusive axis-aligned box belongs
     * to this settlement's spherical claim.
     *
     * <p>Checking only {@code min} and {@code max} is insufficient for a
     * sphere: those two opposite corners may be inside while a mixed X/Z/Y
     * corner is outside. Work Zones use this exact all-eight-corner test both
     * before commit and again when persisted state is loaded.
     */
    public boolean insideBox(BlockPos min, BlockPos max) {
        if (min == null || max == null
            || min.getX() > max.getX()
            || min.getY() > max.getY()
            || min.getZ() > max.getZ()) {
            return false;
        }
        int[] xs = {min.getX(), max.getX()};
        int[] ys = {min.getY(), max.getY()};
        int[] zs = {min.getZ(), max.getZ()};
        for (int x : xs) {
            for (int y : ys) {
                for (int z : zs) {
                    if (!inside(new BlockPos(x, y, z))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    // ------------------------------------------------ building Blessings ---

    /**
     * Invalidates building-zone Blessings after a plaque changes a building's
     * bounds, validity, identity or ranks. The next lookup rebuilds once on
     * the server thread; unchanged hot lookups never scan {@link #buildings}.
     */
    public void invalidateBuildingBlessingIndex() {
        buildingBlessingIndex.invalidate();
        buildingBlessingRevision++;
    }

    /** Primitive revision read; lets raiders notice edits without a scan. */
    public long buildingBlessingRevision() {
        return buildingBlessingRevision;
    }

    /**
     * Strongest valid building rank covering one block coordinate. The hot
     * path is allocation-free and examines only the fixed-cap candidate
     * bucket for this X/Z chunk. Overlapping buildings use max, never sum.
     */
    public int strongestBuildingBlessingAt(int x, int y, int z,
                                           BlessingId blessing) {
        return buildingBlessingIndex.strongestAt(buildings, x, y, z, blessing);
    }

    /** Diagnostics pinned by GameTests; never used to drive gameplay. */
    public int lastBuildingBlessingCandidateChecks() {
        return buildingBlessingIndex.lastCandidateChecks;
    }

    /** Diagnostics pinned by GameTests; unchanged hot reads must not rebuild. */
    public long buildingBlessingIndexRebuilds() {
        return buildingBlessingIndex.rebuilds;
    }

    /** Diagnostics pinned by GameTests; proves per-raider lookup throttling. */
    public long buildingBlessingIndexLookups() {
        return buildingBlessingIndex.lookups;
    }

    /** Last rebuild's fail-closed per-Blessing bucket overflows. */
    public int buildingBlessingOverflowedBuckets() {
        return buildingBlessingIndex.overflowedBuckets;
    }

    /** Last rebuild's explicitly rejected giant/corrupt building bounds. */
    public int buildingBlessingRejectedOversizedBuildings() {
        return buildingBlessingIndex.rejectedOversizedBuildings;
    }

    /** True when whole-index bounds failed and all building effects fail closed. */
    public boolean buildingBlessingIndexGloballyDisabled() {
        return buildingBlessingIndex.globallyDisabled;
    }

    /** Hard bound for one hot chunk lookup, exposed for QA assertions. */
    public static int maxBuildingBlessingCandidatesPerChunk() {
        return BuildingBlessingIndex.MAX_CANDIDATES_PER_CHUNK;
    }

    /** Whole-settlement record bound, exposed for adversarial QA fixtures. */
    public static int maxBuildingsInBlessingIndex() {
        return BuildingBlessingIndex.MAX_BUILDING_RECORDS;
    }

    public static int maxChunksPerBlessedBuilding() {
        return BuildingBlessingIndex.MAX_INDEXED_CHUNKS_PER_BUILDING;
    }

    public static int maxChunkBucketsInBlessingIndex() {
        return BuildingBlessingIndex.MAX_CHUNK_BUCKETS;
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
        boolean removed = settlers.removeIf(r -> r.entityId.equals(entityId));
        if (removed) {
            employmentAuthorizations.clear(entityId);
        }
        return removed;
    }

    /** Replaces the immutable recruitment authority and refreshes legacy UI/test mirrors. */
    public void applyRecruitment(RecruitmentTransaction next) {
        if (next == null || !id.equals(next.settlementId())) {
            next = RecruitmentTransaction.quarantined(id, 0, 0, null,
                RecruitmentTransaction.TerminalReason.MALFORMED_SAVE);
        }
        recruitment = next;
        recruitProgress = next.progress();
        recruitQualifiedSeconds = next.qualifiedSeconds();
        recruitTarget = next.lockedTarget();
        recruitCycle = next.cycle();
        travelerId = next.travelerId();
        travelerSinceGameTime = next.status()
            == RecruitmentTransaction.Status.WAITING_ADMISSION
                ? next.arrivedTick() : next.spawnedTick();
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        if (!members.isEmpty()) {
            ListTag memberList = new ListTag();
            for (UUID member : members) {
                memberList.add(NbtUtils.createUUID(member));
            }
            tag.put("Members", memberList);
        }
        if (mayorId != null) {
            tag.putUUID("MayorId", mayorId);
        }
        if (mayorCourierWarehouseId != null) {
            tag.putUUID("MayorCourierWarehouseId", mayorCourierWarehouseId);
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
        tag.put("RecruitmentTransaction", recruitment.writeNbt());
        tag.putLong("LastTavernVisitorDay", lastTavernVisitorDay);
        if (tavernVisitorDepartureOrigin != null) {
            tag.put("TavernVisitorDepartureOrigin", NbtUtils.writeBlockPos(tavernVisitorDepartureOrigin));
        }
        tag.putLong("AlertUntil", alertUntilGameTime);
        if (alertPos != null) {
            tag.put("AlertPos", NbtUtils.writeBlockPos(alertPos));
        }
        ListTag list = new ListTag();
        for (SettlerRecord r : settlers) {
            CompoundTag rt = new CompoundTag();
            rt.putUUID("EntityId", r.entityId);
            rt.putString("Name", r.name);
            rt.putByte("Profession", r.profession.id());
            rt.put("BedClaimProjection", r.bedClaim.writeNbt());
            list.add(rt);
        }
        tag.put("Settlers", list);
        tag.put("EquipmentRequestQueue", equipmentRequestQueue.writeNbt());
        ListTag buildingList = new ListTag();
        for (Building b : buildings) {
            buildingList.add(b.writeNbt());
        }
        tag.put("Buildings", buildingList);
        tag.putInt("RaidProfileWireId", raidProfile.wireId());
        tag.putString("RaidProfile", raidProfile.id());
        tag.put("RaidLifecycle", raidLifecycle.writeNbt());
        tag.put("RecurringRaidRun", recurringRaidRun.writeNbt());
        tag.put("RaidCoinRewards", raidCoinRewards.writeNbt());
        tag.put("BlessingState", blessingState.writeNbt());
        tag.putInt("RepairDiscountProgress", repairDiscountProgress);
        tag.put("GuardOrders", guardOrders.writeNbt());
        tag.put("BannerTeams", bannerTeams.writeNbt());
        tag.put("PatrolRoutes", patrolRoutes.writeNbt());
        tag.put("FoundingJourney", foundingJourney.writeNbt());
        tag.put("JourneyV3", journeyState.writeNbt());
        tag.put("FirstRaidReadiness", firstRaidReadiness.writeNbt());
        tag.put("EmploymentAuthorizations",
            employmentAuthorizations.writeNbt());
        tag.put("EmploymentReturns", employmentReturns.writeNbt());
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
        for (Tag member : tag.getList("Members", Tag.TAG_INT_ARRAY)) {
            try {
                s.members.add(NbtUtils.loadUUID(member));
            } catch (IllegalArgumentException malformed) {
                // a damaged entry only drops that one member
            }
        }
        s.raidCoinRewards = com.hearthstead.settlement.raid.RaidCoinRewards.readNbt(tag);
        // Old saves have no persisted tally. Malformed values cannot grant a waiver.
        int repairProgress = tag.contains("RepairDiscountProgress", Tag.TAG_INT)
            ? tag.getInt("RepairDiscountProgress") : 0;
        s.repairDiscountProgress = repairProgress >= 0 && repairProgress < 4
            ? repairProgress : 0;
        s.radius = tag.getInt("Radius");
        if (s.radius <= 0) {
            s.radius = DEFAULT_RADIUS;
        }
        // The Mayor office is retired (Guildmaster, 26 Sep): old MayorId /
        // MayorSince / MourningUntil / MayorCourierWarehouseId keys are read
        // as nothing, so no seat, settling clock or mourning survives a load.
        // The roster rows are retired below via MayorRetirement.scrub.
        if (sourceVersion >= 7) {
            Tag rawRecruitment = tag.get("RecruitmentTransaction");
            s.applyRecruitment(rawRecruitment instanceof CompoundTag recruitmentTag
                ? RecruitmentTransaction.readOrQuarantine(recruitmentTag, s.id)
                : RecruitmentTransaction.quarantined(s.id, 0, 0, null,
                    RecruitmentTransaction.TerminalReason.MALFORMED_SAVE));
        } else {
            readRecruitment(tag, sourceVersion, s);
            UUID legacyTraveler = tag.hasUUID("TravelerId")
                ? tag.getUUID("TravelerId") : null;
            // V0-v6 never persisted an exact tavern identity or an explicit
            // arrival commit. Keeping its loose TravelerId would let a legacy
            // entity join for free, while preserving partial clocks would
            // silently lock them to whichever tavern happens to load first.
            // Both migrate fail-closed: no free recruit and no fabricated lock.
            s.applyRecruitment(legacyTraveler == null
                ? RecruitmentTransaction.fresh(s.id, s.recruitCycle, 0)
                : RecruitmentTransaction.quarantined(s.id, s.recruitCycle, 0,
                    legacyTraveler,
                    RecruitmentTransaction.TerminalReason.LEGACY_UNVERIFIABLE));
        }
        s.lastTavernVisitorDay = tag.contains("LastTavernVisitorDay", Tag.TAG_LONG)
            ? tag.getLong("LastTavernVisitorDay") : -1L;
        s.tavernVisitorDepartureOrigin = tag.contains("TavernVisitorDepartureOrigin", Tag.TAG_INT_ARRAY)
            ? NbtUtils.readBlockPos(tag, "TavernVisitorDepartureOrigin").orElse(null) : null;
        s.alertUntilGameTime = tag.getLong("AlertUntil");
        if (tag.contains("AlertPos")) {
            s.alertPos = NbtUtils.readBlockPos(tag, "AlertPos").orElse(null);
        }
        ListTag list = tag.getList("Settlers", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag rt = list.getCompound(i);
            s.settlers.add(new SettlerRecord(rt.getUUID("EntityId"), rt.getString("Name"),
                Profession.byId(rt.getByte("Profession"))));
            s.settlers.get(s.settlers.size()-1).bedClaim = ResidentBedClaim.readNbt(rt.get("BedClaimProjection"));
        }
        com.hearthstead.settlement.guildmaster.MayorRetirement.scrub(s);
        if (tag.get("EquipmentRequestQueue") instanceof CompoundTag queueTag) {
            s.equipmentRequestQueue = EquipmentRequestQueue.readNbt(queueTag);
        }
        Tag rawBuildings = tag.get("Buildings");
        ListTag buildingList;
        if (sourceVersion >= Building.TARGET_BLESSINGS_SCHEMA_VERSION) {
            if (!(rawBuildings instanceof ListTag currentBuildings)) {
                throw new SettlementSavedData.DataVersionException(
                    "Current Hearthstead settlement is missing a valid Buildings list");
            }
            for (Tag rawBuilding : currentBuildings) {
                if (!(rawBuilding instanceof CompoundTag)) {
                    throw new SettlementSavedData.DataVersionException(
                        "Current Hearthstead Buildings must contain compounds");
                }
            }
            buildingList = currentBuildings;
        } else {
            // v0-v2 predate permanent building-target ledgers. Their historic
            // missing/wrong-type behaviour remains an empty migration only.
            buildingList = tag.getList("Buildings", Tag.TAG_COMPOUND);
        }
        Set<UUID> buildingIds = new HashSet<>();
        for (int i = 0; i < buildingList.size(); i++) {
            Building building = Building.readNbt(buildingList.getCompound(i),
                sourceVersion);
            if (!buildingIds.add(building.id)) {
                throw new SettlementSavedData.DataVersionException(
                    "Hearthstead settlement " + s.id
                        + " contains duplicate building id " + building.id);
            }
            building.validateWorkZoneOwner(s);
            s.buildings.add(building);
        }
        // A stale helper binding cannot turn an arbitrary building into a
        // Warehouse authority after a save was edited or a plaque dissolved.
        if (s.mayorCourierWarehouseId != null && s.buildings.stream().noneMatch(
                building -> building != null
                    && s.mayorCourierWarehouseId.equals(building.id)
                    && building.type == com.hearthstead.building.BuildingType.WAREHOUSE)) {
            s.mayorCourierWarehouseId = null;
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
            // V0's one pending plan is already owned by RaidLifecycle's
            // explicit first-raid bridge. It must not also become a recurring
            // bridge and be resolved/logged twice.
            s.recurringRaidRun = new RecurringRaidRun();
            s.blessingState = new BlessingState();
            s.guardOrder = new GuardOrder();
            s.guardOrders = GuardOrderBook.fresh();
            s.foundingJourney = FoundingJourney.skipped();
            s.firstRaidReadiness = FirstRaidReadiness.fresh();
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
            Tag rawBlessingState = tag.get("BlessingState");
            if (rawBlessingState == null) {
                // Every versioned settlement schema already owned an
                // authoritative reward ledger. Only the explicit v0 branch
                // above predates it; missing data here is corruption and must
                // never reset issuance history or mint replacement offers.
                s.blessingState = BlessingState.quarantinedEmpty();
            } else if (rawBlessingState instanceof CompoundTag blessingTag) {
                s.blessingState = BlessingState.readNbt(blessingTag,
                    sourceVersion);
            } else {
                // A present but wrongly typed ledger is corruption, not an
                // invitation to mint replacement offers.
                s.blessingState = BlessingState.quarantinedEmpty();
            }
            if (sourceVersion < 8) {
                Tag rawLegacyGuardOrder = tag.get("GuardOrder");
                GuardOrder legacy = rawLegacyGuardOrder
                        instanceof CompoundTag legacyTag
                    ? GuardOrder.tryReadLegacyNbt(legacyTag) : null;
                if (legacy == null) {
                    // Every v1-v7 writer persisted this field, including an
                    // empty order. Missing, wrongly typed or malformed state
                    // is integrity loss and may never be treated as fresh.
                    s.guardOrder = new GuardOrder();
                    s.guardOrders = GuardOrderBook.quarantined(
                        "malformed_legacy_guard_order");
                } else {
                    s.guardOrder = legacy;
                    s.guardOrders = GuardOrderBook.pendingLegacy(legacy);
                }
            } else if (tag.get("GuardOrders") instanceof CompoundTag ordersTag) {
                s.guardOrders = GuardOrderBook.readNbt(ordersTag);
            } else {
                s.guardOrders = GuardOrderBook.quarantined(
                    "missing_current_guard_orders");
            }
            readRecurringRaidState(tag, sourceVersion, s, malformedPendingRaid);
            if (sourceVersion < 4) {
                // Established worlds with a completed/active/no first raid do
                // not replay onboarding. A legacy SCHEDULED raid is different:
                // the new readiness gate would otherwise strand its original
                // rolled dates forever, so it receives the explicit, honest
                // qualification path and must complete it before that plan can
                // be authored. No milestone or evidence is grandfathered.
                s.foundingJourney = s.raidLifecycle.firstState()
                    == FirstRaidState.SCHEDULED
                    ? FoundingJourney.fresh() : FoundingJourney.skipped();
            } else if (tag.get("FoundingJourney") instanceof CompoundTag journeyTag) {
                s.foundingJourney = FoundingJourney.readNbt(journeyTag);
            } else {
                // Missing or wrongly typed current authority never resets to
                // a fresh journey that could replay onboarding transitions.
                s.foundingJourney = FoundingJourney.quarantined();
            }
            if (sourceVersion < 4) {
                // Legacy settlements have no evidence, so they are not ready.
                // Keep an empty, valid ledger rather than labelling legitimate
                // old data corrupt; SKIPPED Journey remains an independent
                // hard gate unless a still-scheduled legacy first raid was
                // given the explicit fresh qualification path just above.
                s.firstRaidReadiness = FirstRaidReadiness.fresh();
            } else if (tag.get("FirstRaidReadiness")
                    instanceof CompoundTag readinessTag) {
                s.firstRaidReadiness = FirstRaidReadiness.readNbt(readinessTag);
            } else {
                // Current-schema absence/wrong type is corruption and may not
                // reset to a ledger capable of recording replacement proof.
                s.firstRaidReadiness = FirstRaidReadiness.quarantined();
            }
        }
        s.journeyState = readJourneyState(tag, sourceVersion, s.id);
        Tag rawEmploymentAuthorizations = tag.get("EmploymentAuthorizations");
        if (rawEmploymentAuthorizations == null) {
            s.employmentAuthorizations = EmploymentAuthorizationLedger
                .migrateLegacy(s);
        } else if (rawEmploymentAuthorizations
                instanceof CompoundTag authorizationTag) {
            s.employmentAuthorizations = EmploymentAuthorizationLedger
                .readNbt(authorizationTag, s.id);
        } else {
            s.employmentAuthorizations = EmploymentAuthorizationLedger
                .quarantined();
        }
        Tag rawEmploymentReturns = tag.get("EmploymentReturns");
        if (rawEmploymentReturns == null) {
            // No earlier save could contain a Fire-return obligation.
            s.employmentReturns = new PendingPlayerDeliveryLedger();
        } else if (rawEmploymentReturns instanceof CompoundTag returnTag) {
            s.employmentReturns = PendingPlayerDeliveryLedger.readNbt(returnTag);
        } else {
            s.employmentReturns = PendingPlayerDeliveryLedger.readNbt(new CompoundTag());
        }
        s.bannerTeams = com.hearthstead.settlement.guard.BannerTeamBook.readNbt(
            tag.contains("BannerTeams", Tag.TAG_COMPOUND) ? tag.getCompound("BannerTeams") : null);
        s.patrolRoutes = com.hearthstead.settlement.guard.patrol.PatrolRouteBook.readNbt(
            tag.contains("PatrolRoutes", Tag.TAG_COMPOUND) ? tag.getCompound("PatrolRoutes") : null);
        return s;
    }

    private static JourneyState readJourneyState(CompoundTag settlementTag,
                                                 int sourceVersion,
                                                 UUID settlementId) {
        if (sourceVersion >= 6) {
            return settlementTag.get("JourneyV3") instanceof CompoundTag journeyTag
                ? JourneyState.readNbt(journeyTag, settlementId)
                : JourneyState.quarantined(settlementId,
                    "missing_current_journey_v3");
        }
        // Worlds predating the shipped onboarding are established worlds.
        // Never wake them into a 45-step tutorial or infer transactions.
        if (sourceVersion < 4) {
            return JourneyState.skipped(settlementId);
        }
        if (!(settlementTag.get("FoundingJourney") instanceof CompoundTag legacy)) {
            return JourneyState.quarantined(settlementId,
                "missing_legacy_founding_journey");
        }
        JourneyEvidence founded = new JourneyEvidence(
            JourneyIds.FJ_010_FOUND_HEARTH,
            JourneyEvent.SETTLEMENT_FOUNDED_COMMITTED,
            JourneyTransactionIds.forRevision("legacy_founding", settlementId,
                settlementId, 0L),
            0L, settlementId,
            Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
            Optional.empty(), JourneySource.MIGRATION, JourneyOutcome.NONE);
        return JourneyMigration.migrateLegacy(legacy, settlementId, false,
            List.of(founded)).state();
    }

    /**
     * DataVersion 3 adds an auditable recurring-raid ledger. V1/v2 had only
     * PendingRaid and a loaded-AABB completion path, so an in-flight recurring
     * plan may continue once through an explicit no-reward bridge; it may
     * never be upgraded into a sealed participant capture after the fact.
     */
    private static void readRecurringRaidState(CompoundTag tag, int sourceVersion,
                                               Settlement settlement,
                                               boolean malformedPendingRaid) {
        if (sourceVersion < 3) {
            migratePreV3RecurringRaid(settlement, malformedPendingRaid);
            return;
        }

        if (tag.contains("RecurringRaidRun", Tag.TAG_COMPOUND)) {
            settlement.recurringRaidRun = RecurringRaidRun.readNbt(
                tag.getCompound("RecurringRaidRun"));
        } else {
            // A v3 ledger may not disappear into a pristine serial counter:
            // that could replay an already-processed recurring reward.
            settlement.recurringRaidRun = RecurringRaidRun.blocked();
        }

        boolean firstOwnsPending = settlement.raidLifecycle.isAuthoredFirstRaidActive()
            || settlement.raidLifecycle.isLegacyBridgeActive();
        if (firstOwnsPending) {
            if (!settlement.recurringRaidRun.isEmpty()) {
                settlement.recurringRaidRun.block();
            }
            if (malformedPendingRaid) {
                settlement.raidLifecycle.markIntegrityLost();
            }
            return;
        }

        if (settlement.raidLifecycle.firstState() != FirstRaidState.COMPLETED) {
            if (!settlement.recurringRaidRun.isEmpty() || settlement.pendingRaid != null
                || malformedPendingRaid) {
                settlement.recurringRaidRun.block();
            }
            return;
        }

        if (settlement.recurringRaidRun.isBlocked()) {
            return;
        }
        RaidPlanRelation relation = relationToRecurringPlan(settlement);
        if (malformedPendingRaid) {
            if (settlement.recurringRaidRun.isActive()) {
                // The sealed ledger remains completion authority, but a
                // damaged compatibility mirror permanently disarms reward.
                settlement.recurringRaidRun.markIntegrityLost();
            } else {
                settlement.recurringRaidRun.block();
            }
        } else if (relation == RaidPlanRelation.MISMATCH) {
            if (settlement.recurringRaidRun.isActive()) {
                settlement.recurringRaidRun.markIntegrityLost();
            } else {
                settlement.recurringRaidRun.block();
            }
        } else if (relation == RaidPlanRelation.UNEXPECTED_PENDING) {
            settlement.recurringRaidRun.block();
        }
    }

    private static void migratePreV3RecurringRaid(Settlement settlement,
                                                   boolean malformedPendingRaid) {
        if (settlement.raidLifecycle.firstState() == FirstRaidState.COMPLETED) {
            if (malformedPendingRaid) {
                settlement.recurringRaidRun = RecurringRaidRun.blocked();
            } else if (settlement.pendingRaid != null) {
                settlement.recurringRaidRun =
                    RecurringRaidRun.migrateLegacy(settlement.pendingRaid);
            } else {
                settlement.recurringRaidRun = new RecurringRaidRun();
            }
            return;
        }
        // An authored/v0 ACTIVE first raid owns PendingRaid as its mirror.
        // Any other pre-v3 pending shape is unauditable and must not AABB-close.
        boolean firstOwnsPending = settlement.raidLifecycle.isAuthoredFirstRaidActive()
            || settlement.raidLifecycle.isLegacyBridgeActive();
        settlement.recurringRaidRun = firstOwnsPending
            ? new RecurringRaidRun()
            : (settlement.pendingRaid != null || malformedPendingRaid
                ? RecurringRaidRun.blocked() : new RecurringRaidRun());
        if (malformedPendingRaid && firstOwnsPending) {
            settlement.raidLifecycle.markIntegrityLost();
        }
    }

    private enum RaidPlanRelation {
        MATCH,
        MISMATCH,
        UNEXPECTED_PENDING
    }

    private static RaidPlanRelation relationToRecurringPlan(Settlement settlement) {
        if (settlement.recurringRaidRun.isActive()
            || settlement.recurringRaidRun.isLegacyBridgeActive()) {
            if (settlement.pendingRaid == null) {
                // Missing mirror is recoverable from the authoritative run.
                return RaidPlanRelation.MATCH;
            }
            return settlement.recurringRaidRun.plan().filter(settlement.pendingRaid::equals)
                .isPresent() ? RaidPlanRelation.MATCH : RaidPlanRelation.MISMATCH;
        }
        return settlement.pendingRaid == null
            ? RaidPlanRelation.MATCH : RaidPlanRelation.UNEXPECTED_PENDING;
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
        int minimum = RecruitmentPolicy.minimumForOrdinaryTarget(settlement.id, cycle, target);
        if (minimum <= 0
            || progress < 0 || progress > target
            || qualified < 0
            || qualified > minimum) {
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

    /**
     * ArrayList with one extra invariant: every structural mutation dirties
     * the runtime building-Blessing index. Existing code may keep using the
     * public List API, while removals from plaque dissolution and additions
     * during NBT load cannot leave a stale combat zone behind.
     */
    private final class BlessingTrackedBuildingList extends ArrayList<Building> {
        @Override
        public boolean add(Building building) {
            boolean changed = super.add(building);
            if (changed) {
                invalidateBuildingBlessingIndex();
            }
            return changed;
        }

        @Override
        public void add(int index, Building element) {
            super.add(index, element);
            invalidateBuildingBlessingIndex();
        }

        @Override
        public boolean addAll(Collection<? extends Building> additions) {
            boolean changed = super.addAll(additions);
            if (changed) {
                invalidateBuildingBlessingIndex();
            }
            return changed;
        }

        @Override
        public boolean addAll(int index, Collection<? extends Building> additions) {
            boolean changed = super.addAll(index, additions);
            if (changed) {
                invalidateBuildingBlessingIndex();
            }
            return changed;
        }

        @Override
        public Building set(int index, Building element) {
            Building previous = super.set(index, element);
            invalidateBuildingBlessingIndex();
            return previous;
        }

        @Override
        public void replaceAll(UnaryOperator<Building> operator) {
            if (isEmpty()) {
                super.replaceAll(operator);
                return;
            }
            // ArrayList.replaceAll mutates its backing array directly rather
            // than routing through set(). A finally block also covers an
            // operator that throws after partially replacing the list.
            try {
                super.replaceAll(operator);
            } finally {
                invalidateBuildingBlessingIndex();
            }
        }

        @Override
        public List<Building> subList(int fromIndex, int toIndex) {
            // ArrayList.SubList can mutate the root backing array without
            // dispatching through every override above. No Hearthstead caller
            // needs a mutable slice, so expose a live read-only view and keep
            // structural writes on the tracked root List API.
            return Collections.unmodifiableList(
                super.subList(fromIndex, toIndex));
        }

        @Override
        public Building remove(int index) {
            Building removed = super.remove(index);
            invalidateBuildingBlessingIndex();
            return removed;
        }

        @Override
        public boolean remove(Object candidate) {
            boolean changed = super.remove(candidate);
            if (changed) {
                invalidateBuildingBlessingIndex();
            }
            return changed;
        }

        @Override
        public boolean removeAll(Collection<?> candidates) {
            boolean changed = super.removeAll(candidates);
            if (changed) {
                invalidateBuildingBlessingIndex();
            }
            return changed;
        }

        @Override
        public boolean retainAll(Collection<?> retained) {
            boolean changed = super.retainAll(retained);
            if (changed) {
                invalidateBuildingBlessingIndex();
            }
            return changed;
        }

        @Override
        public boolean removeIf(Predicate<? super Building> filter) {
            boolean changed = super.removeIf(filter);
            if (changed) {
                invalidateBuildingBlessingIndex();
            }
            return changed;
        }

        @Override
        public void clear() {
            if (isEmpty()) {
                return;
            }
            super.clear();
            invalidateBuildingBlessingIndex();
        }

        @Override
        protected void removeRange(int fromIndex, int toIndex) {
            if (fromIndex == toIndex) {
                return;
            }
            super.removeRange(fromIndex, toIndex);
            invalidateBuildingBlessingIndex();
        }
    }

    /**
     * Lazy X/Z-chunk index for permanent building Blessings.
     *
     * <p>Rebuilds are cold-path work caused only by load/survey/bind/dissolve.
     * The live combat path performs one primitive-long map lookup and at most
     * {@value #MAX_CANDIDATES_PER_CHUNK} direct bounding-box/rank reads. A
     * malformed giant bound and pathological overlap are skipped/capped at
     * rebuild time, so corrupted NBT cannot turn a raider tick into an
     * unbounded scan or an unbounded allocation burst. A per-Blessing bucket
     * overflow disables that Blessing for the affected chunk instead of
     * choosing a partial, order-dependent winner; diagnostics expose both
     * overflow and oversized-bound rejection to QA. More than 512 building
     * records or 2048 generated chunk buckets disables the complete index,
     * again fail-closed, so total cold-path memory is bounded as well.
     */
    private static final class BuildingBlessingIndex {
        private static final int MAX_INDEXED_CHUNKS_PER_BUILDING = 64;
        private static final int MAX_CANDIDATES_PER_CHUNK = 32;
        private static final int MAX_BUILDING_RECORDS = 512;
        private static final int MAX_CHUNK_BUCKETS = 2048;
        private static final BlessingId[] BLESSINGS = BlessingId.values();

        private final Long2ObjectOpenHashMap<Bucket> byChunk =
            new Long2ObjectOpenHashMap<>();
        private boolean dirty = true;
        private long rebuilds;
        private long lookups;
        private int lastCandidateChecks;
        private int overflowedBuckets;
        private int rejectedOversizedBuildings;
        private boolean globallyDisabled;

        private void invalidate() {
            dirty = true;
        }

        private int strongestAt(List<Building> buildings, int x, int y, int z,
                                BlessingId blessing) {
            lookups++;
            if (blessing == null) {
                lastCandidateChecks = 0;
                return 0;
            }
            if (dirty) {
                rebuild(buildings);
            }
            if (globallyDisabled) {
                lastCandidateChecks = 0;
                return 0;
            }
            Bucket bucket = byChunk.get(chunkKey(x >> 4, z >> 4));
            if (bucket == null) {
                lastCandidateChecks = 0;
                return 0;
            }

            int strongest = 0;
            int checked = 0;
            int blessingIndex = blessing.ordinal();
            if (bucket.overflowed[blessingIndex]) {
                // Partial candidate sets would make the winning building
                // depend on list order. Disable this pathological chunk for
                // this Blessing instead: deterministic and fail-closed.
                lastCandidateChecks = 0;
                return 0;
            }
            for (int i = 0; i < bucket.sizes[blessingIndex]; i++) {
                Building building = bucket.buildings[blessingIndex][i];
                checked++;
                if (!building.valid || building.bounds == null
                    || x < building.bounds.minX() || x > building.bounds.maxX()
                    || y < building.bounds.minY() || y > building.bounds.maxY()
                    || z < building.bounds.minZ() || z > building.bounds.maxZ()) {
                    continue;
                }
                strongest = Math.max(strongest,
                    building.blessingRank(blessing));
                if (strongest >= TargetBlessingState.MAX_RANK) {
                    break;
                }
            }
            lastCandidateChecks = checked;
            return Math.min(TargetBlessingState.MAX_RANK, strongest);
        }

        private void rebuild(List<Building> buildings) {
            byChunk.clear();
            overflowedBuckets = 0;
            rejectedOversizedBuildings = 0;
            globallyDisabled = buildings.size() > MAX_BUILDING_RECORDS;
            if (globallyDisabled) {
                dirty = false;
                rebuilds++;
                return;
            }
            rebuild:
            for (Building building : buildings) {
                if (building == null || !building.valid || building.bounds == null
                    || !hasAnyBlessing(building)) {
                    continue;
                }
                int minChunkX = building.bounds.minX() >> 4;
                int maxChunkX = building.bounds.maxX() >> 4;
                int minChunkZ = building.bounds.minZ() >> 4;
                int maxChunkZ = building.bounds.maxZ() >> 4;
                long width = (long) maxChunkX - minChunkX + 1L;
                long depth = (long) maxChunkZ - minChunkZ + 1L;
                if (width <= 0L || depth <= 0L
                    || width > MAX_INDEXED_CHUNKS_PER_BUILDING
                    || depth > MAX_INDEXED_CHUNKS_PER_BUILDING
                    || width * depth > MAX_INDEXED_CHUNKS_PER_BUILDING) {
                    rejectedOversizedBuildings++;
                    continue;
                }
                for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
                    for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                        long key = chunkKey(chunkX, chunkZ);
                        Bucket bucket = byChunk.get(key);
                        if (bucket == null) {
                            if (byChunk.size() >= MAX_CHUNK_BUCKETS) {
                                globallyDisabled = true;
                                break rebuild;
                            }
                            bucket = new Bucket();
                            byChunk.put(key, bucket);
                        }
                        for (BlessingId blessing : BLESSINGS) {
                            if (building.blessingRank(blessing) > 0) {
                                if (bucket.add(building, blessing)) {
                                    overflowedBuckets++;
                                }
                            }
                        }
                    }
                }
            }
            if (globallyDisabled) {
                // Never retain a partial, insertion-order-dependent index.
                byChunk.clear();
                overflowedBuckets = 0;
            }
            dirty = false;
            rebuilds++;
        }

        private static boolean hasAnyBlessing(Building building) {
            for (BlessingId blessing : BLESSINGS) {
                if (building.blessingRank(blessing) > 0) {
                    return true;
                }
            }
            return false;
        }

        private static long chunkKey(int chunkX, int chunkZ) {
            return (chunkX & 0xffffffffL) | ((chunkZ & 0xffffffffL) << 32);
        }

        private static final class Bucket {
            private final Building[][] buildings = new Building
                [BLESSINGS.length][MAX_CANDIDATES_PER_CHUNK];
            private final int[] sizes = new int[BLESSINGS.length];
            private final boolean[] overflowed =
                new boolean[BLESSINGS.length];

            /** @return true only for the first overflow of this Blessing row. */
            private boolean add(Building building, BlessingId blessing) {
                int blessingIndex = blessing.ordinal();
                if (overflowed[blessingIndex]) {
                    return false;
                }
                if (sizes[blessingIndex] < MAX_CANDIDATES_PER_CHUNK) {
                    buildings[blessingIndex][sizes[blessingIndex]++] = building;
                    return false;
                }
                overflowed[blessingIndex] = true;
                return true;
            }
        }
    }

    public static class SettlerRecord {
        public final UUID entityId;
        public String name;
        public Profession profession;
        public ResidentBedClaim bedClaim = ResidentBedClaim.unknown();

        public SettlerRecord(UUID entityId, String name, Profession profession) {
            this.entityId = entityId;
            this.name = name;
            this.profession = profession;
        }
    }
}
