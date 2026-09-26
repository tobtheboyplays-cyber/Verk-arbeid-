package com.hearthstead.block;

import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.building.Requirement;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.registry.ModBlockEntities;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.BlessingPresentation;
import com.hearthstead.settlement.FoundingJourneyProgress;
import com.hearthstead.settlement.RoomScanner;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.TargetBlessingState;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.Block;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The plaque's brain: it surveys the room it hangs in, decides whether that
 * room satisfies its building type, and keeps a link to the resulting
 * building.
 *
 * <p>It deliberately stores almost nothing. The building id, the type and the
 * last survey are here; residents, workers and building geometry live in the
 * settlement. A plaque that cached its own resident list would be a second
 * source of truth, and the two would drift the first time a settler died
 * while the chunk was unloaded.
 *
 * <p>D-006: a hung plaque starts {@link PlaqueState#EMPTY} and does nothing
 * — no UI, no survey — until a Build Plan item is fitted into it. The fitted
 * plan is itself a real, conserved item: it is stored here, saved and loaded
 * like the rest of this entity's state, and comes back out exactly as it
 * went in (INV-3) when the plan is extracted or the plaque is broken.
 */
public class PlaqueBlockEntity extends BlockEntity {

    /** How often a hung plaque re-checks its room, in ticks (10 s). */
    private static final int SURVEY_INTERVAL = 200;
    /** A just-fitted plan gets three short retries after its synchronous reading. */
    private static final int INITIAL_SURVEY_RETRIES = 3;
    private static final int INITIAL_SURVEY_RETRY_DELAY = 2;
    /** Runtime-only sentinel: the first tick assigns this plaque its position phase. */
    private static final long SURVEY_UNSCHEDULED = Long.MIN_VALUE;

    private BuildingType type = BuildingType.HOUSE;
    private PlaqueState state = PlaqueState.EMPTY;
    /** The Build Plan currently fitted; {@link ItemStack#EMPTY} while {@link PlaqueState#EMPTY}. */
    private ItemStack insertedPlan = ItemStack.EMPTY;
    @Nullable
    private UUID buildingId;
    /**
     * City identity chosen when this Build Plan is fitted.  It is independent
     * of {@link Settlement#radius}: radius remains the local settlement
     * boundary for encounters and scoped systems, while a finished building
     * keeps the city it was explicitly linked to after the player builds
     * beyond that boundary.
     */
    @Nullable
    private UUID settlementId;
    /**
     * Bumped on every server-side change. A screen sends the revision it was
     * drawn from, so a click made against a stale view is refused instead of
     * quietly acting on outdated information.
     */
    private int revision;
    private long nextSurveyTick = SURVEY_UNSCHEDULED;
    /** Runtime-only retries for a new plan; never revive from disk or on chunk load. */
    private int initialSurveyRetriesRemaining;
    private List<Requirement.Status> lastSurvey = List.of();
    /**
     * Why the LAST outright scan failure happened — {@code null} whenever
     * the room passed its geometric checks, whatever the plaque's state is
     * otherwise. Derived exactly like {@link #lastSurvey}: recomputed by
     * every {@link #survey}, put on the wire, never saved. Before this, a
     * room that failed to enclose or roof produced an empty
     * {@link #lastSurvey} and the sheet fell back to the bare state name
     * ("No room found") with nothing about why — {@link RoomScanner.Result}
     * had already computed the reason, it just never left this class.
     */
    @Nullable
    private Component lastScanReason;

    /**
     * Who is in the building this plaque declares, and how many fit.
     *
     * <p>Derived, exactly like {@link #lastSurvey}: recomputed by every
     * {@link #survey}, put on the wire so the sheet can be drawn, and NEVER
     * written by {@link #saveAdditional}. A plaque that saved an occupant
     * count would be wrong the first time a settler died in an unloaded
     * chunk, and D-006 says the plaque reads the settlement rather than
     * keeping its own copy of it.
     */
    private int occupants;
    private int capacity;

    /**
     * Live courier diagnosis for this building. Like the survey projection,
     * it is derived from real containers, sent to clients and never saved.
     */
    private StopReason logisticsStopReason = StopReason.NONE;
    /** One transient heartbeat per courier; never persisted. */
    private final Map<UUID, LogisticsReport> logisticsReports = new HashMap<>();
    private long nextLogisticsPruneTick = Long.MAX_VALUE;
    private long lastLogisticsMaintenanceTick = Long.MIN_VALUE;
    private boolean logisticsAggregateDirty;
    private static final int LOGISTICS_REPORT_TTL = 100;
    /** Structural telemetry: bounded maintenance, never gameplay state. */
    private int logisticsPrunePasses;
    private int logisticsAggregatePasses;

    /** How many times a real room scan has been attempted — test telemetry for W3. */
    private int scanAttempts;
    /** Runtime-only structural telemetry: changed surveys committed to the BE. */
    private int surveyCommits;
    /** Runtime-only evidence counter; never persisted or consulted by gameplay. */
    private int buildingLinkTelemetryEmissions;
    /** Runtime-only structural telemetry: BE update packets authored by surveys. */
    private int surveyUpdatePackets;
    /** Runtime-only structural telemetry: real bed-assignment passes requested by links. */
    private int bedAssignmentAttempts;
    /**
     * Disk saves omit derived survey/occupancy/logistics projection. The first
     * server tick cheaply hydrates the renderer-facing occupancy and repairs
     * the lamp, while the expensive room scan keeps its position phase.
     */
    private boolean loadProjectionHydrationPending;
    /** Runtime-only structural telemetry for the one-shot load contract. */
    private int loadProjectionHydrations;
    private int loadProjectionBlockStatePublishes;
    private int loadProjectionExplicitPublishes;

    /**
     * How many CONSECUTIVE failed surveys a standing building forgives
     * before it actually dissolves.
     *
     * <p>Found live (20260825T183505Z, "Eira took up work at the Bakery"):
     * placing bakery blocks nudged a re-survey of the neighbouring
     * warehouse mid-edit; one transiently failed scan unlinked it, fired
     * its courier and wiped the worker roster — and the very next hire
     * command legally re-hired her elsewhere. One bad reading must never
     * fire the staff: a player patching a wall, a raid knocking one block
     * out, or a half-finished renovation all read as "temporarily broken",
     * not "gone". At the ~10s survey cadence three failures is about half a
     * minute of sustained brokenness — long enough to be real, short enough
     * that a raid hole left open genuinely costs the building.
     *
     * <p>Deliberate acts stay immediate: breaking the plaque or pulling its
     * plan calls {@link #dissolveBuilding} directly, no grace.
     */
    private static final int GRACE_SURVEYS = 3;

    /** Consecutive failed surveys while linked; reset by any healthy one. */
    private int failedSurveys;
    /** How many times this plaque's screen has been opened — test telemetry for W4. */
    private int screenOpens;
    static final int BLESSING_RUNE_CONTACT_DELAY_TICKS = 4;
    /** Rare, runtime-only rune beats; at most nine APPLIED ranks can queue. */
    @Nullable
    private ArrayDeque<ScheduledBlessingCue> pendingBlessingCues;

    public PlaqueBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.PLAQUE.get(), pos, state);
    }

    // ------------------------------------------------------------- state ---

    public BuildingType type() {
        return type;
    }

    public PlaqueState state() {
        return state;
    }

    public int revision() {
        return revision;
    }

    public List<Requirement.Status> lastSurvey() {
        return lastSurvey;
    }

    /** Why the last outright scan failure happened; {@code null} when N/A. */
    @Nullable
    public Component lastScanReason() {
        return lastScanReason;
    }

    /** Settlers in the building now — 0 unless it is registered. */
    public int occupants() {
        return occupants;
    }

    /** How many the building holds — 0 unless it is registered. */
    public int capacity() {
        return capacity;
    }

    public StopReason logisticsStopReason() {
        return logisticsStopReason;
    }

    /**
     * Multiplexes the already-authored lamp: a valid building stays valid,
     * while green/amber/red now says whether its current goods flow is
     * operating, waiting, or blocked. The targeted courier's overhead text
     * remains the non-colour channel that explains the exact reason.
     */
    public void setLogisticsStopReason(ServerLevel level, UUID courierId,
                                       StopReason reason) {
        StopReason next = java.util.Objects.requireNonNull(reason);
        java.util.Objects.requireNonNull(courierId);
        long now = level.getGameTime();
        long expiresAt = now + LOGISTICS_REPORT_TTL;
        LogisticsReport report = logisticsReports.get(courierId);
        if (next == StopReason.NONE) {
            if (report != null) {
                logisticsReports.remove(courierId);
                logisticsAggregateDirty = true;
            }
            if (logisticsReports.isEmpty()) {
                nextLogisticsPruneTick = Long.MAX_VALUE;
            }
            return;
        }
        if (report == null) {
            logisticsReports.put(courierId, new LogisticsReport(next, expiresAt));
            logisticsAggregateDirty = true;
        } else {
            if (report.reason() != next) {
                report.setReason(next);
                logisticsAggregateDirty = true;
            }
            // The once-per-second steady heartbeat mutates this scalar in
            // place: no record allocation and no aggregate scan per courier.
            report.setExpiresAtTick(expiresAt);
        }
        nextLogisticsPruneTick = Math.min(nextLogisticsPruneTick, expiresAt);
    }

    @Nullable
    public UUID buildingId() {
        return buildingId;
    }

    public ItemStack insertedPlan() {
        return insertedPlan.copy();
    }

    // --------------------------------------------------------- Blessings ---

    /**
     * Binds one permanent Blessing rank to the building declared by this
     * physical plaque.
     *
     * <p>The saved {@link Building} is the sole authority. The block entity
     * stores no duplicate ledger, and resolution happens only for this player
     * action -- never from a tick or global block-entity scan. Only APPLIED
     * dirties the settlement save; MAXED and INVALID are observational and
     * therefore safe for the seal item to treat as "do not consume".
     */
    public TargetBlessingState.ApplyResult applyBlessing(BlessingId blessing) {
        return applyBlessing(blessing, 1);
    }

    public TargetBlessingState.ApplyResult applyBlessing(BlessingId blessing, int rankUnits) {
        if (!(level instanceof ServerLevel serverLevel) || isRemoved()
            || buildingId == null) {
            return TargetBlessingState.ApplyResult.INVALID;
        }
        Settlement owner = settlementFor(serverLevel);
        Building target = blessingTarget(owner);
        if (target == null) {
            return TargetBlessingState.ApplyResult.INVALID;
        }
        TargetBlessingState.ApplyResult result = target.applyBlessing(blessing, rankUnits);
        if (result == TargetBlessingState.ApplyResult.APPLIED) {
            owner.invalidateBuildingBlessingIndex();
            SettlementSavedData.get(serverLevel).setDirty();
            float yaw = getBlockState().getValue(PlaqueBlock.FACING).toYRot();
            presentBlessingOnset(serverLevel, blessing, yaw);
            if (pendingBlessingCues == null) {
                pendingBlessingCues = new ArrayDeque<>();
            }
            pendingBlessingCues.addLast(new ScheduledBlessingCue(blessing,
                serverLevel.getGameTime() + BLESSING_RUNE_CONTACT_DELAY_TICKS));
            // Only APPLIED reaches this point, after the sole building ledger
            // and SavedData dirty flag agree. Existing exact viewers receive
            // an UPDATE; no broadcast can open a screen.
            com.hearthstead.network.InspectionViewers.refreshPlaque(
                serverLevel, this);
        }
        return result;
    }

    /** Rank on this plaque's current valid building; zero for any invalid link. */
    public int blessingRank(BlessingId blessing) {
        if (!(level instanceof ServerLevel serverLevel) || isRemoved()
            || buildingId == null) {
            return 0;
        }
        Building target = blessingTarget(settlementFor(serverLevel));
        return target == null ? 0 : target.blessingRank(blessing);
    }

    /**
     * Non-mutating resolver for seal use. In particular, unlike the regular
     * survey resolver, a failed lookup must not orphan/rewrite the plaque:
     * INVALID and MAXED are strict no-mutation results.
    */
    @Nullable
    private Building blessingTarget(@Nullable Settlement settlement) {
        if (settlement == null || buildingId == null) {
            return null;
        }
        Building found = null;
        for (Building building : settlement.buildings) {
            if (building == null || building.id == null) {
                return null;
            }
            if (building.id.equals(buildingId)) {
                if (found != null) {
                    return null;
                }
                found = building;
            }
        }
        return found != null && found.valid
            && worldPosition.equals(found.plaquePos) ? found : null;
    }

    public int scanAttempts() {
        return scanAttempts;
    }

    public int screenOpenCount() {
        return screenOpens;
    }

    int surveyCommitCountForTest() {
        return surveyCommits;
    }

    int buildingLinkTelemetryCountForTest() {
        return buildingLinkTelemetryEmissions;
    }

    int surveyUpdatePacketCountForTest() {
        return surveyUpdatePackets;
    }

    int bedAssignmentAttemptCountForTest() {
        return bedAssignmentAttempts;
    }

    boolean loadProjectionHydrationPendingForTest() {
        return loadProjectionHydrationPending;
    }

    int loadProjectionHydrationCountForTest() {
        return loadProjectionHydrations;
    }

    int loadProjectionBlockStatePublishCountForTest() {
        return loadProjectionBlockStatePublishes;
    }

    int loadProjectionExplicitPublishCountForTest() {
        return loadProjectionExplicitPublishes;
    }

    int loadProjectionPublishCountForTest() {
        return loadProjectionBlockStatePublishes
            + loadProjectionExplicitPublishes;
    }

    int logisticsPrunePassCountForTest() {
        return logisticsPrunePasses;
    }

    int logisticsAggregatePassCountForTest() {
        return logisticsAggregatePasses;
    }

    int logisticsReportCountForTest() {
        return logisticsReports.size();
    }

    void tickLogisticsForTest(ServerLevel level, long now) {
        tickLogisticsMaintenance(level, now);
    }

    long nextSurveyTickForTest() {
        return nextSurveyTick;
    }

    static int surveyIntervalForTest() {
        return SURVEY_INTERVAL;
    }

    int initialSurveyRetriesRemainingForTest() {
        return initialSurveyRetriesRemaining;
    }

    static int initialSurveyRetriesForTest() {
        return INITIAL_SURVEY_RETRIES;
    }

    static int initialSurveyRetryDelayForTest() {
        return INITIAL_SURVEY_RETRY_DELAY;
    }

    /**
     * Announce this plaque to the level's registry so nearby block changes can
     * find it. Done on load rather than on placement so plaques that come back
     * with a chunk are known too.
     */
    @Override
    public void onLoad() {
        super.onLoad();
        if (level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).buildingManager
                .registerPlaque(worldPosition);
        }
    }

    @Override
    public void setRemoved() {
        if (level instanceof ServerLevel serverLevel) {
            SettlementSavedData.get(serverLevel).buildingManager
                .forgetPlaque(worldPosition);
        }
        super.setRemoved();
    }

    // --------------------------------------------------------------- plan ---

    /**
     * Fits a Build Plan. Only valid while {@link PlaqueState#EMPTY} — a
     * plaque that already has one must be extracted first. Reads the type off
     * the plan, stores a single copy of it, and starts the surveyor: this is
     * the moment D-006 says the plaque begins working.
     *
     * @return whether the plan was accepted
     */
    public boolean insertPlan(ServerLevel level, ItemStack plan) {
        if (state != PlaqueState.EMPTY || plan.isEmpty()) {
            return false;
        }
        type = PlaqueItemData.buildingType(plan);
        // Resolve one persisted city identity at the physical plan-fit
        // boundary.  This consults only the finite same-dimension settlement
        // registry; it does not scan terrain, chunks, or an expanding radius.
        Settlement settlement = settlementFor(level);
        settlementId = settlement == null ? null : settlement.id;
        insertedPlan = plan.copyWithCount(1);
        state = PlaqueState.PLAN_INSERTED_UNLINKED;
        setChanged();
        survey(level);
        // A finished room can still be observed before the server has applied
        // the plaque/plan interaction's neighbour and block-entity updates.
        // Only an incomplete first reading gets short retries. A confirmed
        // valid building resumes the cheap position phase immediately.
        initialSurveyRetriesRemaining = state == PlaqueState.LINKED_VALID
            ? 0 : INITIAL_SURVEY_RETRIES;
        nextSurveyTick = initialSurveyRetriesRemaining > 0
            ? level.getGameTime() + INITIAL_SURVEY_RETRY_DELAY
            : firstSurveyTick(level.getGameTime(), worldPosition);
        return true;
    }

    /**
     * Pulls the fitted plan back out, dissolving whatever it declared exactly
     * as breaking the plaque does. Returns the exact item that was fitted —
     * same component, same count — so this conserves it (INV-3);
     * {@link ItemStack#EMPTY} if nothing was fitted.
     */
    public ItemStack extractPlan(ServerLevel level, @Nullable Player remover) {
        if (state == PlaqueState.EMPTY || insertedPlan.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack out = insertedPlan.copy();
        if (!dissolveBuilding(level, remover)) {
            return ItemStack.EMPTY;
        }
        insertedPlan = ItemStack.EMPTY;
        settlementId = null;
        initialSurveyRetriesRemaining = 0;
        lastSurvey = List.of();
        lastScanReason = null;
        clearLogisticsRuntime();
        occupants = 0;
        capacity = 0;
        state = PlaqueState.EMPTY;
        updateGlow(level);
        revision++;
        setChanged();
        return out;
    }

    // ------------------------------------------------------------ survey ---

    public static void serverTick(net.minecraft.world.level.Level level, BlockPos pos,
                                  BlockState state, PlaqueBlockEntity plaque) {
        if (level instanceof ServerLevel serverLevel) {
            long now = serverLevel.getGameTime();
            plaque.tickBlessingContactCues(serverLevel, now);
            plaque.tickLogisticsMaintenance(serverLevel, now);
            if (plaque.loadProjectionHydrationPending) {
                plaque.hydrateLoadedProjection(serverLevel);
            }
            if (plaque.nextSurveyTick == SURVEY_UNSCHEDULED) {
                plaque.nextSurveyTick = firstSurveyTick(now, pos);
            }
            if (now >= plaque.nextSurveyTick) {
                plaque.survey(serverLevel);
                if (plaque.initialSurveyRetriesRemaining > 0
                    && plaque.state != PlaqueState.LINKED_VALID) {
                    plaque.initialSurveyRetriesRemaining--;
                    plaque.nextSurveyTick = plaque.initialSurveyRetriesRemaining > 0
                        ? now + INITIAL_SURVEY_RETRY_DELAY
                        : firstSurveyTick(now, pos);
                } else {
                    plaque.initialSurveyRetriesRemaining = 0;
                    plaque.nextSurveyTick = now + SURVEY_INTERVAL;
                }
            }
        }
    }

    /**
     * Spreads newly loaded plaques across the whole ten-second cadence.
     *
     * <p>The old zero default made every plaque loaded together scan on the
     * same server tick and then remain locked together forever. The offset is
     * stable for one world position, requires no registry/cache, and keeps the
     * existing one-scan-per-{@value #SURVEY_INTERVAL}-ticks contract after the
     * first phased scan.
     */
    private static long firstSurveyTick(long now, BlockPos pos) {
        long positionHash = pos.getX() * 31L + pos.getY() * 17L + pos.getZ() * 13L;
        long offset = Math.floorMod(positionHash, (long) SURVEY_INTERVAL);
        return now + 1L + offset;
    }

    /**
     * Rebuilds only the cheap, already-authoritative projection omitted from
     * disk NBT. This deliberately performs no {@link RoomScanner} scan: one
     * hundred plaques loaded together therefore do one bounded lookup each,
     * not one hundred same-tick flood fills. The real survey is scheduled by
     * {@link #firstSurveyTick}; incomplete/unlinked plaques truthfully render
     * their state until that phased measurement arrives.
     */
    private void hydrateLoadedProjection(ServerLevel level) {
        loadProjectionHydrationPending = false;
        loadProjectionHydrations++;
        int previousOccupants = occupants;
        int previousCapacity = capacity;
        boolean exactLinkLoaded = countLoadedOccupancy(level);
        boolean projectionChanged = occupants != previousOccupants
            || capacity != previousCapacity;
        boolean blockStatePublished = reconcileLoadedBlockState(level,
            exactLinkLoaded);
        if (blockStatePublished) {
            loadProjectionBlockStatePublishes++;
        }
        if (projectionChanged && !blockStatePublished) {
            // Derived projection is never persisted and therefore must not
            // bump revision, dirty the chunk or dirty SettlementSavedData.
            // It does need one BE packet if the client-facing values changed.
            loadProjectionExplicitPublishes++;
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(),
                Block.UPDATE_CLIENTS);
        }
    }

    /**
     * Rebuilds the disk-omitted occupancy projection without changing link
     * identity. A missing saved building is a material survey transition,
     * not a cheap hydration fact: {@link #building(ServerLevel)} deliberately
     * orphans that stale id, so calling it here would publish a new identity
     * without the survey revision/dirty commit which owns that transition.
     */
    private boolean countLoadedOccupancy(ServerLevel level) {
        occupants = 0;
        capacity = 0;
        if (state != PlaqueState.LINKED_VALID) {
            return false;
        }
        Settlement settlement = settlementFor(level);
        Building building = exactBuilding(settlement);
        if (building == null || !building.valid
            || !worldPosition.equals(building.plaquePos)
            || building.type != type) {
            return false;
        }
        capacity = com.hearthstead.settlement.techtree.effects.CommonsEffects.capacityOf(level, settlement, type, building);
        occupants = com.hearthstead.settlement.BuildingManager
            .occupantsOf(level, settlement, building);
        return true;
    }

    /**
     * Reconciles only state that can be reconstructed without RoomScanner.
     *
     * <p>For a valid building every red/amber lamp is transient courier
     * diagnosis, so clearing the runtime reports makes GREEN authoritative.
     * For an incomplete/unlinked building, however, amber may be the last
     * persisted requirement-progress truth. Keep that colour until the phased
     * scan and repair only an impossible REGISTERED flag. A flags=3 state
     * update is sufficient for both block state and this BE's update packet:
     * vanilla {@code ChunkHolder#broadcastChanges} follows its block packet
     * with {@code getUpdatePacket()} for blocks which have a block entity.
     */
    private boolean reconcileLoadedBlockState(ServerLevel level,
                                              boolean exactLinkLoaded) {
        if (state == PlaqueState.LINKED_VALID) {
            // A persisted valid state is not enough to reinterpret a red or
            // amber lamp as transient courier truth. If its exact settlement
            // building disappeared, preserve the complete physical state
            // until the phased survey owns the orphan/relink transition.
            return exactLinkLoaded && updateGlow(level);
        }
        BlockState current = getBlockState();
        if (current.getValue(PlaqueBlock.REGISTERED)) {
            return level.setBlock(worldPosition,
                current.setValue(PlaqueBlock.REGISTERED, false), 3);
        }
        return false;
    }

    void tickBlessingContactCues(ServerLevel level, long now) {
        if (pendingBlessingCues == null) {
            return;
        }
        float yaw = getBlockState().getValue(PlaqueBlock.FACING).toYRot();
        while (!pendingBlessingCues.isEmpty()
            && pendingBlessingCues.peekFirst().dueTick() <= now) {
            ScheduledBlessingCue cue = pendingBlessingCues.removeFirst();
            presentBlessingContact(level, cue.blessing(), yaw);
        }
        if (pendingBlessingCues.isEmpty()) {
            pendingBlessingCues = null;
        }
    }

    /** Instance-bound emission seam for exact, allocation-free GameTest observation. */
    protected void presentBlessingOnset(ServerLevel level, BlessingId blessing,
                                        float yaw) {
        BlessingPresentation.plaqueRuneOnset(level, blessing,
            worldPosition.getX() + 0.5D, worldPosition.getY() + 0.55D,
            worldPosition.getZ() + 0.5D, yaw);
    }

    /** Instance-bound emission seam for exact authored-contact tick observation. */
    protected void presentBlessingContact(ServerLevel level, BlessingId blessing,
                                          float yaw) {
        BlessingPresentation.bindingContact(level, blessing,
            worldPosition.getX() + 0.5D, worldPosition.getY() + 0.55D,
            worldPosition.getZ() + 0.5D, yaw, SoundSource.BLOCKS);
    }

    int pendingBlessingCueCount() {
        return pendingBlessingCues == null ? 0 : pendingBlessingCues.size();
    }

    private record ScheduledBlessingCue(BlessingId blessing, long dueTick) {
    }

    /**
     * Look at the room and update everything that follows from it. Safe to
     * call often: it is a bounded flood fill plus a requirement tally. A
     * blank plaque (W3) refuses outright — no plan means nothing to look
     * for, and no room scan ever runs for one.
     */
    public void survey(ServerLevel level) {
        if (state == PlaqueState.EMPTY) {
            return;
        }
        scanAttempts++;
        RoomScanner.Result result = surveyRoom(level);
        com.hearthstead.settlement.work.FishingGrounds.Result fishing = null;
        if (type == BuildingType.FISHERY && result != null) {
            fishing = com.hearthstead.settlement.work.FishingGrounds.scan(level, worldPosition);
            java.util.Map<net.minecraft.world.level.block.Block, Integer> counts = new java.util.HashMap<>(result.blockCounts());
            counts.put(net.minecraft.world.level.block.Blocks.WATER, fishing.waterCount());
            counts.put(com.hearthstead.registry.ModBlocks.FISHERS_CHAIR.get(), fishing.ready() ? 1 : 0);
            result = new RoomScanner.Result(result.bounds(), result.volume(), result.beds(), result.doors(),
                result.lights(), result.furnishingScore(), result.enclosed(), result.skyLeak(), counts,
                result.leakPos(), result.skyLeakPos(), result.connectedAleTaps(), result.floorCounts(),
                result.lowStairStep());
        }
        PlaqueState previous = state;
        List<Requirement.Status> previousSurvey = lastSurvey;
        Component previousScanReason = lastScanReason;
        UUID previousBuildingId = buildingId;
        StopReason previousLogisticsStopReason = logisticsStopReason;
        int previousOccupants = occupants;
        int previousCapacity = capacity;
        int previousFailedSurveys = failedSurveys;
        boolean settlementChanged = false;
        BuildingLinkCommit buildingLinkCommit = null;

        if (result == null || !result.enclosed() || result.skyLeak()
            || result.volume() > RoomScanner.MAX_HOME_VOLUME) {
            // Computed every survey, grace or not, so the sheet and the
            // screen always explain the CURRENT scan rather than a stale
            // one from before the grace window opened.
            Component reason = result == null
                ? Component.translatable("hearthstead.plaque.scan.no_interior")
                : result.geometryFailure();
            if (!java.util.Objects.equals(reason, lastScanReason)) {
                // Fires once per NEW reason, not every 10s survey tick: the
                // exact moment the owner hit at 5:27 (fit a Build Plan, get
                // "No room found") is state == previous == UNLINKED already
                // (insertPlan sets the state before this scan even runs), so
                // announce()'s state != previous gate would never catch it.
                announceScanReason(level, reason);
            }
            lastScanReason = reason;
            if (!graceHolds(level, PlaqueState.PLAN_INSERTED_UNLINKED)) {
                lastSurvey = List.of();
                settlementChanged = unlink(level,
                    PlaqueState.PLAN_INSERTED_UNLINKED);
            }
        } else {
            // Non-blocking notes on a valid room: the fishing grounds first,
            // then a stair settlers cannot climb for lack of headroom.
            lastScanReason = fishing != null && !fishing.ready()
                ? Component.translatable("hearthstead.fisher.scan." + fishing.blocker())
                : result.stairWarning();
            List<Requirement.Status> statuses = new ArrayList<>();
            boolean allMet = true;
            for (Requirement requirement : type.requirements()) {
                Requirement.Status status = requirement.measure(result);
                statuses.add(status);
                allMet &= status.met();
            }
            lastSurvey = List.copyOf(statuses);
            if (allMet) {
                failedSurveys = 0;
                LinkResult linkResult = link(level, result);
                settlementChanged = linkResult.changed();
                buildingLinkCommit = linkResult.commit();
                settlementChanged |= applyChecklistLevel(level, result);
            } else if (!graceHolds(level, PlaqueState.LINKED_INCOMPLETE)) {
                settlementChanged = unlink(level, PlaqueState.LINKED_INCOMPLETE);
            }
        }

        countOccupancy(level);
        updateGlow(level);
        if (state != previous) {
            announce(level, previous);
        }
        boolean changed = settlementChanged
            || state != previous
            || !lastSurvey.equals(previousSurvey)
            || !java.util.Objects.equals(lastScanReason, previousScanReason)
            || !java.util.Objects.equals(buildingId, previousBuildingId)
            || logisticsStopReason != previousLogisticsStopReason
            || occupants != previousOccupants
            || capacity != previousCapacity
            || failedSurveys != previousFailedSurveys;
        if (changed) {
            int revisionBefore = revision;
            commitSurveyChange(level);
            if (buildingLinkCommit != null) {
                emitBuildingLinkCommitted(level, buildingLinkCommit,
                    revisionBefore);
            }
        }
    }

    /**
     * Builder lane (checklist levels, owner 26 Sep): the building's level is
     * what this same scan found in the room, and the unmet items of the next
     * level ride along for the plaque, the Banner screen and Upgrade Orders.
     * Never tech-capped here -- readers such as the warehouse apply their own
     * cap. Only a registered (linked) building has a level at all.
     */
    private boolean applyChecklistLevel(ServerLevel level, RoomScanner.Result result) {
        Building building = building(level);
        if (building == null) {
            return false;
        }
        int checklist = Math.max(1,
            com.hearthstead.building.BuildingLevels.levelOf(type, result));
        List<com.hearthstead.building.BuildingLevelChecklist.Gap> gap =
            com.hearthstead.building.BuildingLevels.gap(type, result, checklist);
        boolean changed = building.level != checklist
            || !gap.equals(building.nextLevelGap);
        int levelBefore = building.level;
        building.level = checklist;
        building.nextLevelGap = gap;
        if (checklist > levelBefore) {
            com.hearthstead.fx.FxHooks.buildingLevelUp(level, worldPosition, type,
                building.bounds, checklist);
        }
        if (changed) {
            com.hearthstead.settlement.SettlementSavedData.get(level).setDirty();
        }
        return changed;
    }

    /** Persists and publishes one materially changed survey, never a heartbeat. */
    private void commitSurveyChange(ServerLevel level) {
        revision++;
        surveyCommits++;
        setChanged();
        // setChanged() alone marks the chunk dirty for SAVING; it does not
        // resend the block. Without this the sheet would only refresh when
        // the chunk reloaded, so a bed placed in front of the player would
        // not tick its line over until they walked away and back.
        surveyUpdatePackets++;
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(),
            Block.UPDATE_CLIENTS);
    }

    /**
     * Re-reads occupancy from the settlement. Only a registered building has
     * any: an unfinished room holds nobody, whatever was standing in it.
     */
    private void countOccupancy(ServerLevel level) {
        occupants = 0;
        capacity = 0;
        if (state != PlaqueState.LINKED_VALID) {
            return;
        }
        Building building = building(level);
        Settlement settlement = settlementFor(level);
        if (building == null || settlement == null) {
            return;
        }
        capacity = com.hearthstead.settlement.techtree.effects.CommonsEffects.capacityOf(level, settlement, type, building);
        occupants = com.hearthstead.settlement.BuildingManager
            .occupantsOf(level, settlement, building);
    }

    /**
     * Finds the room this plaque speaks for. Players hang plaques inside the
     * room and outside beside the door in equal measure, so both are tried
     * rather than documented: in front first, then through the wall behind.
     *
     * <p>A candidate only wins outright when it is a room by D-004's own
     * measure — enclosed, roofed, AND within {@code MAX_HOME_VOLUME} — not
     * merely enclosed and roofed. Without the volume bound, a wrong-direction
     * seed that happens to flood into some far larger enclosed space (a cave,
     * a courtyard, a GameTest arena under its own barrier ceiling) would win
     * the short-circuit before the correct candidate is ever tried, then get
     * rejected downstream for being oversized — reporting no room found at
     * all even though a later candidate would have found the real one.
     */
    @Nullable
    private RoomScanner.Result surveyRoom(ServerLevel level) {
        Direction facing = getBlockState().getValue(PlaqueBlock.FACING);
        BlockPos[] candidates = {
            worldPosition.relative(facing),                       // hung inside
            worldPosition.relative(facing.getOpposite(), 2),      // hung outside, thin wall
            worldPosition.relative(facing.getOpposite(), 3),      // ...thick wall
        };
        // A candidate only wins OUTRIGHT when it is a room by D-004's measure
        // AND satisfies every one of the plan's requirements. Geometry alone
        // is not enough: hung on the outside of an underground room, the
        // "hung inside" candidate normalizes backward into the plaque's own
        // one-cell niche — enclosed by rock on every side, roofed by rock,
        // volume 1 — which is a geometrically perfect "room" that can never
        // satisfy any plan. Short-circuiting on it reported the owner's
        // buried warehouse as LINKED_INCOMPLETE with a survey measured
        // against a single air cell (storage 0/4, floor_space 1/25) while
        // the real room sat one candidate later, through the wall.
        //
        // When no candidate wins outright, the one whose survey comes
        // CLOSEST wins the right to explain itself: most requirements met
        // first, geometric validity as the tiebreak. That preference order
        // is what puts the actionable diagnosis on the sheet — a leaking
        // real room (all furniture found, one hole) explains the player's
        // actual mistake; a sprawl or a niche explains nothing.
        RoomScanner.Result best = null;
        int bestScore = -1;
        for (BlockPos seed : candidates) {
            RoomScanner.Result result = RoomScanner.scan(level, seed);
            if (result == null) {
                continue;
            }
            boolean geometric = result.enclosed() && !result.skyLeak()
                && result.volume() <= RoomScanner.MAX_HOME_VOLUME;
            int met = 0;
            for (com.hearthstead.building.Requirement requirement : type.requirements()) {
                if (requirement.measure(result).met()) {
                    met++;
                }
            }
            if (geometric && met == type.requirements().size()) {
                return result; // a real, complete room wins immediately
            }
            int score = met * 2 + (geometric ? 1 : 0);
            if (score > bestScore) {
                best = result;
                bestScore = score;
            }
        }
        // Work-yard lane: an open-air trade (a woodcutter's camp, a masons'
        // yard, an open forge) may register as a bounded yard with a covered
        // tool shelter once no ROOM candidate won. Rooms are tried first, so
        // every building that registers as a room today still does.
        if (type.validationMode() == BuildingType.ValidationMode.YARD_OR_ROOM) {
            for (BlockPos seed : candidates) {
                com.hearthstead.settlement.YardScanner.Yard yard =
                    com.hearthstead.settlement.YardScanner.scan(level, seed);
                if (yard == null) {
                    continue;
                }
                RoomScanner.Result result = yard.result();
                boolean geometric = result.enclosed() && result.volume() <= RoomScanner.MAX_HOME_VOLUME;
                int met = 0;
                for (com.hearthstead.building.Requirement requirement : type.requirements()) {
                    if (requirement.measure(result).met()) {
                        met++;
                    }
                }
                if (geometric && met == type.requirements().size()) {
                    return result;
                }
                int score = met * 2 + (geometric ? 1 : 0);
                if (score > bestScore) {
                    best = result;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    // -------------------------------------------------------------- link ---

    private LinkResult link(ServerLevel level, RoomScanner.Result result) {
        Settlement settlement = settlementFor(level);
        if (settlement == null) {
            state = PlaqueState.PLAN_INSERTED_UNLINKED;
            return LinkResult.UNCHANGED;
        }
        // Duplicate/corrupt authority must fail closed. A missing row is a
        // different case: the plaque's complete physical survey is exactly
        // the authority that may replace a building lost during disk repair.
        // Clearing that stale id here makes the new identity one explicit,
        // revisioned link transaction rather than an eager load-time rewrite.
        if (buildingId != null) {
            int matches = buildingIdMatches(settlement, buildingId);
            if (matches < 0 || matches > 1) {
                state = PlaqueState.ORPHANED;
                return LinkResult.UNCHANGED;
            }
            if (matches == 0) {
                buildingId = null;
                state = PlaqueState.ORPHANED;
            }
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        boolean settlementIdentityChanged = !settlement.id.equals(settlementId);
        settlementId = settlement.id;
        int buildingCountBefore = settlement.buildings.size();
        boolean plaqueWasLinkedValid = state == PlaqueState.LINKED_VALID;
        Building building = building(level);
        boolean isNew = building == null;
        if (isNew) {
            building = new Building(UUID.randomUUID(), type, worldPosition,
                result.beds().isEmpty() ? worldPosition : result.beds().get(0),
                result.bounds());
            settlement.buildings.add(building);
            buildingId = building.id;
        }
        boolean wasValid = building.valid;
        BlockPos nextAnchor = result.beds().isEmpty()
            ? worldPosition : result.beds().get(0);
        boolean typeChanged = building.type != type;
        boolean bedsChanged = !building.beds.equals(result.beds());
        boolean materialChanged = isNew || typeChanged
            || !java.util.Objects.equals(building.plaquePos, worldPosition)
            || !java.util.Objects.equals(building.anchor, nextAnchor)
            || !sameBounds(building.bounds, result.bounds())
            || building.interiorVolume != result.volume()
            || bedsChanged
            || building.doorCount != result.doors()
            || building.lightSources != result.lights()
            || building.furnishingScore != result.furnishingScore()
            || !building.valid;
        boolean blessingSpatialChanged = !isNew
            && (!building.valid || !worldPosition.equals(building.plaquePos)
                || !sameBounds(building.bounds, result.bounds()));
        if (materialChanged) {
            building.type = type;
            building.plaquePos = worldPosition;
            building.anchor = nextAnchor;
            building.bounds = result.bounds();
            building.interiorVolume = result.volume();
            building.beds.clear();
            building.beds.addAll(result.beds());
            building.doorCount = result.doors();
            building.lightSources = result.lights();
            building.furnishingScore = result.furnishingScore();
            building.valid = true;
        }
        building.lastValidatedGameTime = level.getGameTime();
        // A new building was invalidated by the tracked list add. Existing
        // healthy plaques survey every ten seconds, so invalidate those only
        // when the zone geometry/validity actually changed; identical surveys
        // must not force periodic index rebuilds during a raid.
        if (blessingSpatialChanged) {
            settlement.invalidateBuildingBlessingIndex();
        }
        state = PlaqueState.LINKED_VALID;
        if (shouldNotifyJourneyOfBuildingUpdate(type, isNew, wasValid,
                typeChanged, bedsChanged)) {
            FoundingJourneyProgress.noteLumberCampLinked(level, settlement, building);
        }
        if (materialChanged || settlementIdentityChanged) {
            data.setDirty();
        }

        if (type.housesResidents()
            && (isNew || !wasValid || bedsChanged || typeChanged)) {
            bedAssignmentAttempts++;
            data.buildingManager.assignFreeBeds(level, settlement, building);
        }
        // A grace-held building remains valid in the settlement ledger while
        // its Plaque truthfully shows LINKED_INCOMPLETE. Returning to a valid
        // physical room is still a persisted Plaque revalidation, even when
        // the building geometry itself did not change.
        BuildingLinkCommit commit = materialChanged || settlementIdentityChanged
            || !plaqueWasLinkedValid
            ? new BuildingLinkCommit(settlement.id, building.id, type.id(),
                buildingCountBefore, settlement.buildings.size())
            : null;
        return new LinkResult(materialChanged || settlementIdentityChanged, commit);
    }

    /**
     * A healthy home's capacity change is Journey evidence even though its
     * plaque never became invalid. This keeps the physical fifth-bed step on
     * the normal periodic survey path instead of requiring a destructive
     * unlink/relink cycle.
     */
    static boolean shouldNotifyJourneyOfBuildingUpdate(BuildingType type,
                                                        boolean isNew,
                                                        boolean wasValid,
                                                        boolean typeChanged,
                                                        boolean bedsChanged) {
        return isNew || !wasValid || typeChanged
            || (type != null && type.housesResidents() && bedsChanged);
    }

    /**
     * Emits only after both halves of the link transaction are durable: the
     * settlement ledger was dirtied in {@link #link} and the plaque revision
     * was incremented/dirtied by {@link #commitSurveyChange}. Identical
     * periodic surveys never produce a {@link BuildingLinkCommit}.
     */
    private void emitBuildingLinkCommitted(ServerLevel level,
                                           BuildingLinkCommit commit,
                                           int revisionBefore) {
        if (AuthorityTelemetry.emit(level,
            AuthorityTelemetry.Event.BUILDING_LINK_COMMITTED,
            AuthorityTelemetry.Result.COMMITTED,
            AuthorityTelemetry.Fields.state(commit.settlementId(),
                "building:" + commit.buildingId(), revisionBefore, revision,
                commit.buildingCountBefore(), commit.buildingCountAfter(),
                "type:" + commit.buildingTypeId()))) {
            buildingLinkTelemetryEmissions++;
        }
    }

    private record LinkResult(boolean changed,
                              @Nullable BuildingLinkCommit commit) {
        private static final LinkResult UNCHANGED = new LinkResult(false, null);
    }

    private record BuildingLinkCommit(UUID settlementId, UUID buildingId,
                                      String buildingTypeId,
                                      int buildingCountBefore,
                                      int buildingCountAfter) {
    }

    private static boolean sameBounds(@Nullable
                                      net.minecraft.world.level.levelgen.structure.BoundingBox left,
                                      net.minecraft.world.level.levelgen.structure.BoundingBox right) {
        return left != null
            && left.minX() == right.minX() && left.minY() == right.minY()
            && left.minZ() == right.minZ() && left.maxX() == right.maxX()
            && left.maxY() == right.maxY() && left.maxZ() == right.maxZ();
    }

    /** Drops the link without destroying the building's memory of its people. */
    /**
     * A standing building rides out {@value #GRACE_SURVEYS} bad readings.
     *
     * <p>Returns true while the grace window shields a currently valid
     * building: the sheet and glow drop to the given state so the player can
     * SEE something is wrong, but the building object, its workers and its
     * bed claims all stay — see {@link #GRACE_SURVEYS} for the live failure
     * this prevents. A plaque with no valid building has nothing to shield.
     */
    private boolean graceHolds(ServerLevel level, PlaqueState shownState) {
        Building building = building(level);
        if (building == null || !building.valid) {
            failedSurveys = 0;
            return false;
        }
        failedSurveys++;
        if (failedSurveys > GRACE_SURVEYS) {
            failedSurveys = 0;
            return false;
        }
        state = shownState == PlaqueState.PLAN_INSERTED_UNLINKED
            ? PlaqueState.LINKED_INCOMPLETE : shownState;
        return true;
    }

    private boolean unlink(ServerLevel level, PlaqueState newState) {
        Building building = building(level);
        boolean settlementChanged = false;
        if (building != null && (building.valid || !building.workers.isEmpty())) {
            Settlement owner = settlementFor(level);
            // A raid may physically scar its Barracks or Watchtower before
            // its sealed participants resolve. Keep the existing armed post
            // and its player order through that one combat window; the Plaque
            // still truthfully shows the failed scan, and the ordinary unlink
            // runs on the next survey after RAID_RESOLVED. This does not apply
            // to a missing plaque or an explicit player dissolve.
            boolean deferMartialTeardown = owner != null
                && Employment.defersMartialUnlinkForActiveFirstRaid(owner, building);
            if (!deferMartialTeardown) {
                // Keep the one saved job relationship while this workplace's
                // recorded raid damage is repairable. It is still invalid:
                // ordinary logistics must wait for a successful real survey.
                boolean retainWorkers = owner != null
                    && Employment.retainsWorkersForRaidRepair(level, owner, building);
                if (!retainWorkers && owner != null
                    && !Employment.freeWorkers(level, owner, building)) {
                    return false;
                }
                building.valid = false;
                releaseResidents(level, building);
                if (owner != null) {
                    owner.invalidateBuildingBlessingIndex();
                }
                SettlementSavedData.get(level).setDirty();
                settlementChanged = true;
            }
        }
        clearLogisticsRuntime();
        state = newState;
        return settlementChanged;
    }

    /** Breaking the plaque, or extracting its plan, dissolves the building outright. */
    public boolean dissolveBuilding(ServerLevel level,
                                    @Nullable Player breaker) {
        Settlement settlement = settlementFor(level);
        Building building = building(level);
        if (settlement == null || building == null) {
            return true;
        }
        // Employment teardown owns request cancellation, current emblem
        // authorization and quiver conservation. It is deliberately before
        // bed/link/plan mutation so an unloaded worker or failed visible drop
        // leaves the entire plaque authority intact for a later retry.
        if (!Employment.freeWorkers(level, settlement, building)) {
            return false;
        }
        releaseResidents(level, building);
        settlement.buildings.remove(building);
        buildingId = null;
        clearLogisticsRuntime();
        state = PlaqueState.PLAN_INSERTED_UNLINKED;
        SettlementSavedData.get(level).setDirty();
        if (breaker != null) {
            breaker.displayClientMessage(Component.translatable(
                "hearthstead.plaque.dissolved", type.displayName()), false);
        }
        return true;
    }

    /** Everyone housed here loses their bed, and feels it. */
    private void releaseResidents(ServerLevel level, Building building) {
        Settlement settlement = settlementFor(level);
        if (settlement == null) {
            return;
        }
        for (SettlerEntity settler : SettlementManager.loadedMembers(level, settlement)) {
            BlockPos bed = settler.getClaimedBed();
            if (bed != null && building.beds.contains(bed)) {
                settler.releaseBed();
                settler.addMorale(-6.0F);
            }
        }
    }

    @Nullable
    public Building building(ServerLevel level) {
        if (buildingId == null) {
            return null;
        }
        Settlement settlement = settlementFor(level);
        if (settlement == null) {
            return null;
        }
        Building building = exactBuilding(settlement);
        if (building != null) {
            return building;
        }
        buildingId = null;
        state = PlaqueState.ORPHANED;
        return null;
    }

    /** Exact saved-id lookup with no orphaning, revision or persistence side effect. */
    @Nullable
    private Building exactBuilding(@Nullable Settlement settlement) {
        if (settlement == null || buildingId == null) {
            return null;
        }
        Building found = null;
        for (Building building : settlement.buildings) {
            if (building == null || building.id == null) {
                return null;
            }
            if (building.id.equals(buildingId)) {
                if (found != null) {
                    return null;
                }
                found = building;
            }
        }
        return found;
    }

    /** Returns -1 for malformed/ambiguous rows, otherwise the exact id count. */
    private static int buildingIdMatches(Settlement settlement,
                                         UUID expectedId) {
        int matches = 0;
        for (Building building : settlement.buildings) {
            if (building == null || building.id == null) {
                return -1;
            }
            if (building.id.equals(expectedId) && ++matches > 1) {
                return matches;
            }
        }
        return matches;
    }

    @Nullable
    public Settlement settlementFor(ServerLevel level) {
        SettlementSavedData data = SettlementSavedData.get(level);
        // A fitted plan or an existing building must never be re-owned merely
        // because a second city is founded nearer to this plaque later.
        if (settlementId != null) {
            return data.settlements.get(settlementId);
        }
        // Old plaque disk rows did not persist their city. An exact valid
        // legacy building row proves the owner; ambiguity and malformed data
        // fail closed. A genuinely absent old row retains the pre-city-id
        // in-radius resolver so the existing complete-survey repair path can
        // create one new, revisioned building identity.
        LegacyOwnerResolution legacy = legacyBuildingOwner(data);
        if (legacy.invalid()) {
            return null;
        }
        if (legacy.owner() != null) {
            return legacy.owner();
        }
        boolean legacyRecovery = buildingId != null;
        // A blank/new plan selects the closest registered city in this
        // dimension without requiring this position to lie inside its radius.
        // The scan is bounded by the already-loaded settlement registry, not
        // by world distance or chunks, and the chosen UUID is persisted above.
        // A missing legacy building is different: it retains the old local
        // radius boundary before its full survey performs disk repair.
        Settlement nearest = null;
        double best = Double.MAX_VALUE;
        for (Settlement settlement : data.settlements.values()) {
            if (legacyRecovery && !settlement.inside(worldPosition)) {
                continue;
            }
            double distance = settlement.center.distSqr(worldPosition);
            if (distance < best) {
                best = distance;
                nearest = settlement;
            }
        }
        return nearest;
    }

    /** Exact pre-city-id migration lookup with explicit absent/invalid states. */
    private LegacyOwnerResolution legacyBuildingOwner(SettlementSavedData data) {
        if (buildingId == null) {
            return LegacyOwnerResolution.NONE;
        }
        Settlement owner = null;
        for (Settlement settlement : data.settlements.values()) {
            int matches = buildingIdMatches(settlement, buildingId);
            if (matches < 0 || matches > 1) {
                return LegacyOwnerResolution.INVALID;
            }
            if (matches == 0) {
                continue;
            }
            Building building = exactBuilding(settlement);
            if (building == null || !worldPosition.equals(building.plaquePos)
                || building.type != type || owner != null) {
                return LegacyOwnerResolution.INVALID;
            }
            owner = settlement;
        }
        return owner == null ? LegacyOwnerResolution.NONE
            : new LegacyOwnerResolution(owner, false);
    }

    private record LegacyOwnerResolution(Settlement owner, boolean invalid) {
        private static final LegacyOwnerResolution NONE =
            new LegacyOwnerResolution(null, false);
        private static final LegacyOwnerResolution INVALID =
            new LegacyOwnerResolution(null, true);
    }
    // ------------------------------------------------------ presentation ---

    private boolean updateGlow(ServerLevel level) {
        boolean anyProgress = false;
        for (Requirement.Status status : lastSurvey) {
            anyProgress |= status.met() || status.partial();
        }
        PlaqueBlock.Glow glow;
        if (state == PlaqueState.LINKED_VALID) {
            glow = logisticsStopReason == StopReason.NONE
                ? PlaqueBlock.Glow.GREEN
                : logisticsStopReason.isWaiting()
                    ? PlaqueBlock.Glow.AMBER : PlaqueBlock.Glow.RED;
        } else {
            glow = PlaqueBlock.Glow.forState(state, anyProgress);
        }
        BlockState current = getBlockState();
        boolean registered = state == PlaqueState.LINKED_VALID;
        if (current.getValue(PlaqueBlock.GLOW) != glow
            || current.getValue(PlaqueBlock.REGISTERED) != registered) {
            return level.setBlock(worldPosition, current
                .setValue(PlaqueBlock.GLOW, glow)
                .setValue(PlaqueBlock.REGISTERED, registered), 3);
        }
        return false;
    }

    /** Red outranks amber; equal-severity ties use stable wire-id order. */
    private void refreshLogisticsAggregate(ServerLevel level) {
        StopReason aggregate = StopReason.NONE;
        for (LogisticsReport report : logisticsReports.values()) {
            StopReason candidate = report.reason();
            int candidateSeverity = candidate.isWaiting() ? 1 : 2;
            int aggregateSeverity = aggregate == StopReason.NONE ? 0
                : aggregate.isWaiting() ? 1 : 2;
            if (candidateSeverity > aggregateSeverity
                || (candidateSeverity == aggregateSeverity
                    && candidate.wireId() < aggregate.wireId())) {
                aggregate = candidate;
            }
        }
        if (logisticsStopReason == aggregate) {
            return;
        }
        logisticsStopReason = aggregate;
        updateGlow(level);
        // Two amber reasons share a block state, so the block-entity packet
        // must still carry the changed text reason to the client.
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(),
            Block.UPDATE_CLIENTS);
    }

    /**
     * Coalesces every courier heartbeat into at most one prune and one full
     * aggregate per plaque per server tick. Empty/unchanged plaques return
     * before creating an iterator; report changes reach the lamp next tick at
     * the latest, while each courier keeps its own independent TTL.
     */
    private void tickLogisticsMaintenance(ServerLevel level, long now) {
        boolean expiryDue = !logisticsReports.isEmpty()
            && now >= nextLogisticsPruneTick;
        if ((!logisticsAggregateDirty && !expiryDue)
            || lastLogisticsMaintenanceTick == now) {
            return;
        }
        lastLogisticsMaintenanceTick = now;
        boolean expired = false;
        if (expiryDue) {
            logisticsPrunePasses++;
            nextLogisticsPruneTick = Long.MAX_VALUE;
            java.util.Iterator<Map.Entry<UUID, LogisticsReport>> iterator =
                logisticsReports.entrySet().iterator();
            while (iterator.hasNext()) {
                LogisticsReport report = iterator.next().getValue();
                if (report.expiresAtTick() <= now) {
                    iterator.remove();
                    expired = true;
                } else {
                    nextLogisticsPruneTick = Math.min(nextLogisticsPruneTick,
                        report.expiresAtTick());
                }
            }
        }
        if (logisticsReports.isEmpty()) {
            nextLogisticsPruneTick = Long.MAX_VALUE;
        }
        if (logisticsAggregateDirty || expired) {
            logisticsAggregatePasses++;
            refreshLogisticsAggregate(level);
            logisticsAggregateDirty = false;
        }
    }

    private void clearLogisticsRuntime() {
        logisticsReports.clear();
        logisticsStopReason = StopReason.NONE;
        logisticsAggregateDirty = false;
        nextLogisticsPruneTick = Long.MAX_VALUE;
        lastLogisticsMaintenanceTick = Long.MIN_VALUE;
    }

    private void announce(ServerLevel level, PlaqueState previous) {
        if (state == PlaqueState.LINKED_VALID) {
            level.sendParticles(net.minecraft.core.particles.ParticleTypes.HAPPY_VILLAGER,
                worldPosition.getX() + 0.5, worldPosition.getY() + 0.5,
                worldPosition.getZ() + 0.5, 12, 0.4, 0.4, 0.4, 0.02);
            level.playSound(null, worldPosition,
                com.hearthstead.registry.ModSounds.PROFESSION_ASSIGNED.get(),
                SoundSource.BLOCKS, 0.8F, 1.15F);
        } else if (previous == PlaqueState.LINKED_VALID) {
            level.playSound(null, worldPosition,
                net.minecraft.sounds.SoundEvents.ITEM_FRAME_REMOVE_ITEM,
                SoundSource.BLOCKS, 0.7F, 0.8F);
        }
    }

    /** How far a player may stand and still be told why a scan just failed. */
    private static final double SCAN_REASON_RADIUS_SQ = 12.0 * 12.0;

    /**
     * Tells whoever is nearby WHY the scan just failed — almost always the
     * player who is standing right there having just fitted a Build Plan.
     * "No room found" told the owner nothing at 5:27; this is the fix: the
     * same {@link RoomScanner.Result#geometryFailure()} sentence that now
     * also sits on the sheet and the plaque screen, said out loud once, the
     * moment it becomes true, instead of only on request.
     */
    private void announceScanReason(ServerLevel level, Component reason) {
        double cx = worldPosition.getX() + 0.5;
        double cy = worldPosition.getY() + 0.5;
        double cz = worldPosition.getZ() + 0.5;
        for (ServerPlayer nearby : level.players()) {
            if (nearby.distanceToSqr(cx, cy, cz) <= SCAN_REASON_RADIUS_SQ) {
                nearby.displayClientMessage(reason, false);
            }
        }
    }

    public void openScreen(ServerPlayer player) {
        screenOpens++;
        com.hearthstead.network.PlaqueNetwork.openFor(player, this);
    }

    // --------------------------------------------------------------- nbt ---

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putString("Type", type.id());
        tag.putString("State", state.id());
        tag.putInt("Revision", revision);
        tag.putInt("FailedSurveys", failedSurveys);
        if (buildingId != null) {
            tag.putUUID("Building", buildingId);
        }
        if (settlementId != null) {
            tag.putUUID("Settlement", settlementId);
        }
        if (!insertedPlan.isEmpty()) {
            tag.put("Plan", insertedPlan.saveOptional(provider));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        // Wire projection is all-or-nothing. A legacy/malformed tag carrying
        // only one of these keys must fail closed into server hydration, not
        // masquerade as a complete 0/0 client snapshot.
        boolean hasWireProjection = tag.contains(SURVEY_KEY, Tag.TAG_LIST)
            && tag.contains(OCCUPANTS_KEY, Tag.TAG_INT)
            && tag.contains(CAPACITY_KEY, Tag.TAG_INT)
            && tag.contains(LOGISTICS_STOP_KEY, Tag.TAG_BYTE);
        // Contact VFX are transient feedback, not world state. A chunk reload
        // must never replay a seal that was already bound.
        pendingBlessingCues = null;
        initialSurveyRetriesRemaining = 0;
        type = BuildingType.byId(tag.getString("Type"));
        buildingId = tag.hasUUID("Building") ? tag.getUUID("Building") : null;
        settlementId = tag.hasUUID("Settlement") ? tag.getUUID("Settlement") : null;
        state = PlaqueState.byId(tag.getString("State"), buildingId != null);
        revision = tag.getInt("Revision");
        failedSurveys = tag.getInt("FailedSurveys");
        clearLogisticsRuntime();
        logisticsStopReason = hasWireProjection
            ? StopReason.fromWireId(tag.getByte(LOGISTICS_STOP_KEY))
            : StopReason.NONE;
        // Always clear the old instance projection first. Disk tags omit all
        // of it by design; retaining the pre-reload Java fields would be just
        // as stale as persisting a second authority in NBT.
        lastSurvey = List.of();
        lastScanReason = null;
        occupants = 0;
        capacity = 0;
        if (hasWireProjection) {
            List<Requirement.Status> restored = new ArrayList<>();
            ListTag list = tag.getList(SURVEY_KEY, Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                Requirement requirement = type.requirementById(entry.getString("Id"));
                if (requirement != null) {
                    restored.add(new Requirement.Status(requirement,
                        entry.getInt("Have"), entry.getInt("Needed")));
                }
            }
            lastSurvey = List.copyOf(restored);
            occupants = tag.getInt(OCCUPANTS_KEY);
            capacity = tag.getInt(CAPACITY_KEY);
        }
        insertedPlan = tag.contains("Plan")
            ? ItemStack.parseOptional(provider, tag.getCompound("Plan"))
            : ItemStack.EMPTY;
        // EMPTY while holding a Building id is contradictory after a load
        // (W2 refined #3) — a Building id means this was linked to something,
        // so resolve toward LINKED_VALID and let the next survey correct it.
        if (state == PlaqueState.EMPTY && buildingId != null) {
            state = PlaqueState.LINKED_VALID;
        }
        if (state == PlaqueState.EMPTY) {
            settlementId = null;
        }
        loadProjectionHydrationPending = !hasWireProjection;
        if (loadProjectionHydrationPending) {
            nextSurveyTick = SURVEY_UNSCHEDULED;
        }
    }

    /** Key for the surveyed requirement list. Wire-only, never on disk. */
    private static final String SURVEY_KEY = "Survey";
    /** Keys for the occupancy line. Wire-only, never on disk — see the fields. */
    private static final String OCCUPANTS_KEY = "Occupants";
    private static final String CAPACITY_KEY = "Capacity";
    /** Courier stop reason. Wire-only and recomputed from real containers. */
    private static final String LOGISTICS_STOP_KEY = "LogisticsStop";

    /**
     * The client needs the type, the state AND the survey to draw the sheet.
     *
     * <p>The survey rides the update tag but is deliberately NOT written by
     * {@code saveAdditional}: it is derived data, recomputed by every
     * {@link #survey}, so persisting it would be a second copy of something
     * the world already knows. Without it on the wire, though, the block
     * renders identically in every state -- which is exactly what it did
     * before this, and why a player had to run a command to learn whether
     * their room passed.
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, provider);
        ListTag list = new ListTag();
        for (Requirement.Status status : lastSurvey) {
            CompoundTag entry = new CompoundTag();
            entry.putString("Id", status.requirement().id());
            entry.putInt("Have", status.have());
            entry.putInt("Needed", status.needed());
            list.add(entry);
        }
        tag.put(SURVEY_KEY, list);
        tag.putInt(OCCUPANTS_KEY, occupants);
        tag.putInt(CAPACITY_KEY, capacity);
        tag.putByte(LOGISTICS_STOP_KEY, (byte) logisticsStopReason.wireId());
        return tag;
    }

    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private static final class LogisticsReport {
        private StopReason reason;
        private long expiresAtTick;

        private LogisticsReport(StopReason reason, long expiresAtTick) {
            this.reason = reason;
            this.expiresAtTick = expiresAtTick;
        }

        private StopReason reason() {
            return reason;
        }

        private void setReason(StopReason reason) {
            this.reason = reason;
        }

        private long expiresAtTick() {
            return expiresAtTick;
        }

        private void setExpiresAtTick(long expiresAtTick) {
            this.expiresAtTick = expiresAtTick;
        }
    }
}
