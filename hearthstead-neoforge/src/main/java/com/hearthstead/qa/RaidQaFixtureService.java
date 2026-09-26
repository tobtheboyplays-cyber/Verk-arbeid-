package com.hearthstead.qa;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlock;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.block.PlaqueItemData;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.GroundCollectionSession;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.menu.SettlerInventoryMenu;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.FoundingJourneyProgress;
import com.hearthstead.settlement.Mayor;
import com.hearthstead.settlement.PendingPlayerDeliveryLedger;
import com.hearthstead.settlement.RecruitmentTransaction;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.DevelopmentQuests;
import com.hearthstead.settlement.development.JobEmblemCatalog;
import com.hearthstead.settlement.equipment.EquipmentRequests;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.equipment.WorkplaceStorage;
import com.hearthstead.settlement.guard.GuardAssignmentService;
import com.hearthstead.settlement.journey.JourneyOutcome;
import com.hearthstead.settlement.journey.JourneyServerHooks;
import com.hearthstead.settlement.journey.JourneyIds;
import com.hearthstead.settlement.raid.FirstRaidReadinessService;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidLogEntry;
import com.hearthstead.settlement.raid.RaidPlan;
import com.hearthstead.settlement.request.RequestLedger;
import com.hearthstead.settlement.request.RequestLedgerSavedData;
import com.hearthstead.settlement.request.RequestLedgerService;
import com.hearthstead.settlement.request.RequestPriority;
import com.hearthstead.settlement.request.RequestRecord;
import com.hearthstead.settlement.request.RequestState;
import com.hearthstead.settlement.request.RequestType;
import com.hearthstead.settlement.state.FoundingJourney;
import com.hearthstead.settlement.state.FirstRaidState;
import com.hearthstead.settlement.state.GuardOrder;
import com.hearthstead.settlement.work.WorkerProvenanceSavedData;
import com.hearthstead.settlement.work.WorkerProvenanceService;
import com.hearthstead.settlement.work.WorkerStackProvenance;
import com.hearthstead.settlement.work.WorkerStorageAuthority;
import com.hearthstead.settlement.workzone.WorkZone;
import com.hearthstead.settlement.workzone.WorkZoneService;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.WallTorchBlock;
import net.minecraft.world.level.block.WallSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.DoorHingeSide;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.UUID;

/**
 * Permission-two native QA fixture author.
 *
 * <p>The fixture may create physical QA inputs, but every durable gameplay
 * fact is authored by the same production service used by normal play. It
 * never writes Journey, readiness, recruitment, employment, provenance or
 * building registries directly. The explicit recurring-ui action is the one
 * marker-bound visual setup exception: it calls the existing Journey hook,
 * never the normal player command path, and cannot grant a raid reward.
 */
public final class RaidQaFixtureService {
    private RaidQaFixtureService() {
    }

    public enum Stage {
        INVALID_SITE,
        FOUNDED_PHYSICAL_SHELLS,
        LUMBERER_HIRED,
        LUMBER_ZONE_COMMITTED,
        LUMBER_PRODUCTION_STORED,
        WAREHOUSE_REGISTERED,
        COURIER_HIRED,
        COURIER_DELIVERY_COMMITTED,
        CULTIVATED_GROUND_UNLOCKED,
        FARMHOUSE_REGISTERED,
        FARMER_HIRED,
        FARM_ZONE_COMMITTED,
        WAITING_NATURAL_CROP,
        HOME_UNLOCKED,
        HOMES_REGISTERED,
        HOSPITALITY_UNLOCKED,
        TAVERN_REGISTERED,
        WAITING_FIRST_TRAVELER,
        FOUR_RESIDENTS,
        FIRST_WATCH_UNLOCKED,
        BARRACKS_REGISTERED,
        GUARD_HIRED,
        GUARD_LOADOUT_DELIVERED,
        GUARD_READY,
        ARM_THE_WATCH_UNLOCKED,
        WAITING_SECOND_TRAVELER,
        FIVE_RESIDENTS,
        WATCHTOWER_REGISTERED,
        ARCHER_HIRED,
        ARCHER_LOADOUT_DELIVERED,
        READY_BEFORE_FIRST_RAID,
        RAID_STARTED,
        DOMAIN_READY,
        EXECUTION_READY
    }

    public record Result(Stage stage, BlockPos origin, Settlement settlement,
                         int physicalBedHeads, int liveMembers,
                         FirstRaidReadinessService.Report domain,
                         FirstRaidReadinessService.Report execution,
                         String blocker, String detail) {
        public boolean ready() {
            return stage == Stage.READY_BEFORE_FIRST_RAID
                && execution != null && execution.ready();
        }
    }

    public enum StartOutcome {
        STARTED,
        ALREADY_STARTED,
        BLOCKED,
        INVALID_MARKER
    }

    public record StartResult(StartOutcome outcome, Result fixture,
                              int spawned, String detail) {
        public boolean started() {
            return outcome == StartOutcome.STARTED
                || outcome == StartOutcome.ALREADY_STARTED;
        }
    }

    /** Narrow, marker-bound setup modes for recurring-status UI observation. */
    public enum RecurringUiMode {
        WARNING,
        RECOVERY
    }

    /** Result contains only display-safe, server-observed status fields. */
    public record RecurringUiResult(boolean ready, String blocker,
                                    String detail, String firstState,
                                    String recurring, long plannedNight,
                                    long cooldown, boolean aftermath) {
    }

    /** Creates an isolated physical fixture, then reports the honest gate. */
    public static Result prepare(ServerLevel level, ServerPlayer actor,
                                 BlockPos origin) {
        return prepare(level, actor, origin, RaidQaFixtureMarker.Mode.ISOLATED);
    }

    /**
     * Creates the same marker-bound first-raid fixture on a compact assisted
     * foundation in normal terrain. It never supplies a readiness fact.
     */
    public static Result prepareGrounded(ServerLevel level, ServerPlayer actor,
                                         BlockPos origin) {
        return prepare(level, actor, origin,
            RaidQaFixtureMarker.Mode.GROUNDED_ASSISTED);
    }

    private static Result prepare(ServerLevel level, ServerPlayer actor,
                                  BlockPos origin, RaidQaFixtureMarker.Mode mode) {
        if (level == null || actor == null || origin == null
            || !level.getServer().isSameThread()) {
            return new Result(Stage.INVALID_SITE, origin, null, 0, 0,
                null, null, "invalid_context", "invalid_context");
        }
        RaidQaFixtureMarker marker = RaidQaFixtureMarker.get(level);
        if (marker.quarantined()) {
            return new Result(Stage.INVALID_SITE, origin, null, 0, 0,
                null, null, "raidqa_marker_quarantined",
                "persisted_fixture_identity_failed_validation");
        }
        RaidQaFixtureMarker.Entry marked = marker.entry(actor.getUUID());
        if (marked != null) {
            if (marked.mode() != mode) {
                return new Result(Stage.INVALID_SITE, marked.origin(), null, 0, 0,
                    null, null, "raidqa_fixture_mode_mismatch",
                    "marker_requires_" + marked.mode().name().toLowerCase());
            }
            origin = marked.origin();
        }
        BlockPos hearthPos = origin.above();
        if (level.getBlockEntity(hearthPos) instanceof HearthBlockEntity existing
            && existing.getSettlementId() != null) {
            if (marked == null) {
                return new Result(Stage.INVALID_SITE, origin, null, 0, 0,
                    null, null, "unmarked_existing_hearth",
                    "fixture_refuses_unowned_or_legacy_hearth");
            }
            Settlement settlement = SettlementManager.byId(level,
                existing.getSettlementId());
            if (settlement == null) {
                return new Result(Stage.INVALID_SITE, origin, null, 0, 0,
                    null, null, "missing_settlement",
                    "existing_hearth_has_no_settlement");
            }
            if (marked != null && !marked.settlementId().equals(settlement.id)) {
                return new Result(Stage.INVALID_SITE, origin, settlement, 0, 0,
                    null, null, "raidqa_marker_mismatch",
                    "marked_settlement_does_not_own_hearth");
            }
            if (!marker.bind(actor.getUUID(), settlement.id, origin, mode)) {
                return new Result(Stage.INVALID_SITE, origin, settlement, 0, 0,
                    null, null, "raidqa_marker_bind_refused",
                    "persisted_fixture_identity_changed");
            }
            String step = authorFoundingAndFirstBuilding(level, actor, origin,
                settlement, existing);
            Progress progress = "lumber_camp_registered".equals(step)
                ? advanceLumberAndWarehouse(level, actor, origin, settlement,
                    existing)
                : new Progress(Stage.FOUNDED_PHYSICAL_SHELLS, step, step);
            return observe(level, origin, settlement, progress.stage(),
                progress.blocker(), "idempotent_reobserve;" + progress.detail());
        }
        if (marked != null) {
            return new Result(Stage.INVALID_SITE, origin, null, 0, 0,
                null, null, "raidqa_fixture_missing",
                "marked_hearth_is_no_longer_physical");
        }
        int[][] groundedExteriorFeet = null;
        List<GroundedVegetation> groundedVegetation = List.of();
        String groundedSearchDetail = null;
        if (mode == RaidQaFixtureMarker.Mode.GROUNDED_ASSISTED) {
            GroundedSiteSearch grounded = findGroundedSite(level, origin);
            groundedSearchDetail = grounded.detail();
            origin = grounded.origin();
            if (origin == null) {
                return new Result(Stage.INVALID_SITE, null, null, 0, 0,
                    null, null, "grounded_site_unavailable",
                    "no_dry_compact_village_site_within_128;" + grounded.detail());
            }
            groundedExteriorFeet = grounded.exteriorFeet();
            groundedVegetation = grounded.vegetation();
        } else if (!clearSite(level, origin)) {
            return new Result(Stage.INVALID_SITE, origin, null, 0, 0,
                null, null, "site_unavailable", "site_not_empty_or_loaded");
        }
        hearthPos = origin.above();
        if (mode == RaidQaFixtureMarker.Mode.GROUNDED_ASSISTED) {
            if (!groundedFloor(level, origin, groundedExteriorFeet,
                    groundedVegetation)) {
                return new Result(Stage.INVALID_SITE, origin, null, 0, 0,
                    null, null, "grounded_clearance_changed",
                    "preflight_snapshot_changed_before_authoring");
            }
        } else {
            floor(level, origin);
        }
        level.setBlockAndUpdate(hearthPos, ModBlocks.HEARTH.get().defaultBlockState());
        Settlement settlement = SettlementManager.tryFound(level, hearthPos);
        if (settlement == null) {
            return new Result(Stage.INVALID_SITE, origin, null, 0, 0,
                null, null, "founding_refused", "production_founding_refused");
        }
        if (level.getBlockEntity(hearthPos) instanceof HearthBlockEntity hearth) {
            hearth.bindSettlement(settlement.id);
        }
        if (!marker.bind(actor.getUUID(), settlement.id, origin, mode)) {
            return new Result(Stage.INVALID_SITE, origin, settlement, 0, 0,
                null, null, "raidqa_marker_bind_refused",
                "fresh_fixture_identity_not_persisted");
        }

        // Physical shells are intentionally not registered as Buildings here:
        // registration must later occur by learned Build Plan + Plaque use.
        room(level, origin.offset(3, 0, 3), 5, Shell.HOUSE_ONE);
        room(level, origin.offset(12, 0, 3), 5, Shell.HOUSE_TWO);
        room(level, origin.offset(20, 0, 3), 7, Shell.LUMBER);
        room(level, origin.offset(3, 0, 13), 7, Shell.FARM);
        // Match the production-valid Warehouse fixture: a 7x7 shell with
        // four storage blocks and two independently counted lights.
        room(level, origin.offset(12, 0, 13), 7, Shell.WAREHOUSE);
        room(level, origin.offset(22, 0, 13), 8, Shell.TAVERN);
        room(level, origin.offset(3, 0, 24), 8, Shell.BARRACKS);
        room(level, origin.offset(WATCHTOWER_OFFSET.getX(),
            WATCHTOWER_OFFSET.getY(), WATCHTOWER_OFFSET.getZ()), 7,
            Shell.WATCHTOWER);

        HearthBlockEntity hearth = level.getBlockEntity(hearthPos)
            instanceof HearthBlockEntity found ? found : null;
        String step = authorFoundingAndFirstBuilding(level, actor, origin,
            settlement, hearth);
        Progress progress = "lumber_camp_registered".equals(step)
            ? advanceLumberAndWarehouse(level, actor, origin, settlement, hearth)
            : new Progress(Stage.FOUNDED_PHYSICAL_SHELLS, step, step);
        String searchDetail = mode == RaidQaFixtureMarker.Mode.GROUNDED_ASSISTED
            ? groundedSearchDetail + ';' : "";
        return observe(level, origin, settlement, progress.stage(),
            progress.blocker(), "physical_shells_created;" + searchDetail + progress.detail());
    }

    /** Read-only marker-bound observer; never falls back to a nearby settlement. */
    public static Result status(ServerLevel level, ServerPlayer actor) {
        if (level == null || actor == null || !level.getServer().isSameThread()) {
            return new Result(Stage.INVALID_SITE, null, null, 0, 0, null,
                null, "invalid_context", "status_requires_live_player");
        }
        RaidQaFixtureMarker marker = RaidQaFixtureMarker.get(level);
        RaidQaFixtureMarker.Entry entry = marker.entry(actor.getUUID());
        if (marker.quarantined() || entry == null) {
            return new Result(Stage.INVALID_SITE, null, null, 0, 0, null,
                null, marker.quarantined() ? "raidqa_marker_quarantined"
                    : "raidqa_marker_missing", "no_owned_fixture");
        }
        Settlement settlement = SettlementManager.byId(level,
            entry.settlementId());
        if (settlement == null
            || !(level.getBlockEntity(entry.origin().above())
                instanceof HearthBlockEntity hearth)
            || !entry.settlementId().equals(hearth.getSettlementId())
            || !settlement.center.equals(entry.origin().above())) {
            return new Result(Stage.INVALID_SITE, entry.origin(), settlement,
                0, 0, null, null, "raidqa_marker_mismatch",
                "marked_hearth_or_settlement_missing");
        }
        FirstRaidState state = settlement.raidLifecycle.firstState();
        Stage reached = state == FirstRaidState.ACTIVE
            ? Stage.RAID_STARTED
            : state == FirstRaidState.SCHEDULED
                && settlement.raidLifecycle.queuedPlan().isPresent()
                ? Stage.READY_BEFORE_FIRST_RAID
                : inferredStage(level, settlement);
        return observe(level, entry.origin(), settlement, reached, "none",
            "marker_bound_status;raid_state=" + state.name().toLowerCase());
    }

    /**
     * Assists only a marker-owned prepared settlement into a persisted visual
     * first-raid aftermath, then applies one real recurring lifecycle mode.
     * It is deliberately a QA-only display setup: no raid is spawned and the
     * synthetic terminal is a HIT, so it cannot mint the first-raid reward.
     */
    public static RecurringUiResult recurringUi(ServerLevel level,
                                                  ServerPlayer actor,
                                                  RecurringUiMode mode) {
        if (level == null || actor == null || mode == null
            || !level.getServer().isSameThread()) {
            return recurringBlocked("invalid_context", "player_and_mode_required",
                "unknown");
        }
        Result fixture = status(level, actor);
        Settlement settlement = fixture.settlement();
        if (settlement == null) {
            return recurringBlocked(fixture.blocker(), fixture.detail(), "unknown");
        }
        FirstRaidState firstState = settlement.raidLifecycle.firstState();
        if (firstState == FirstRaidState.SCHEDULED) {
            if (!fixture.ready() || !seedVisualFirstRaid(level, settlement)) {
                return recurringBlocked("prepared_fixture_required",
                    "requires_ready_marker_bound_first_raid_fixture",
                    firstState.name().toLowerCase(java.util.Locale.ROOT));
            }
        } else if (firstState != FirstRaidState.COMPLETED
            || !RaidDirector.firstResolutionReceiptReady(settlement)) {
            return recurringBlocked("visual_baseline_invalid",
                "requires_marker_bound_completed_first_raid_baseline",
                firstState.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (!settlement.recurringRaidRun.isEmpty() || settlement.pendingRaid != null) {
            return recurringBlocked("recurring_run_not_empty",
                "fixture_refuses_live_or_legacy_raid",
                settlement.raidLifecycle.firstState().name().toLowerCase(
                    java.util.Locale.ROOT));
        }

        long gameTime = level.getGameTime();
        long plannedNight = -1L;
        long cooldown = 0L;
        String recurring;
        if (mode == RecurringUiMode.WARNING) {
            RaidPlan warned = settlement.raidLifecycle.recurringWarnedPlan()
                .orElse(null);
            if (warned == null) {
                if (settlement.raidLifecycle.recurringCoolingDown(gameTime)) {
                    return recurringBlocked("cooldown_active",
                        "recovery_must_expire_before_warning",
                        settlement.raidLifecycle.firstState().name().toLowerCase(
                            java.util.Locale.ROOT));
                }
                long warningNight = Math.max(0L, Math.floorDiv(level.getDayTime(),
                    com.hearthstead.settlement.state.RaidLifecycle.DAY_LENGTH));
                // Keep the display plan tied to an actual captain in this
                // isolated fixture's roster, using the production planner.
                RaidPlan proposed = RaidDirector.planRaid(level, settlement,
                    warningNight + 1L);
                if (!settlement.raidLifecycle.commitRecurringWarning(proposed,
                        gameTime, warningNight)) {
                    return recurringBlocked("warning_commit_refused",
                        "lifecycle_rejected_visual_warning",
                        settlement.raidLifecycle.firstState().name().toLowerCase(
                            java.util.Locale.ROOT));
                }
                warned = proposed;
            }
            plannedNight = warned.night();
            recurring = "warned";
        } else {
            settlement.raidLifecycle.recordRecurringRecovery(gameTime, false);
            cooldown = settlement.raidLifecycle.recurringCooldownUntilGameTime()
                - gameTime;
            if (cooldown <= 0L || settlement.raidLifecycle.recurringWarnedPlan()
                    .isPresent()) {
                return recurringBlocked("recovery_commit_refused",
                    "lifecycle_rejected_visual_recovery",
                    settlement.raidLifecycle.firstState().name().toLowerCase(
                        java.util.Locale.ROOT));
            }
            recurring = "recovering";
        }
        SettlementSavedData.get(level).setDirty();
        boolean aftermath = !settlement.raidLog.isEmpty()
            && RaidLogEntry.isValid(settlement.raidLog.getLast());
        String first = settlement.raidLifecycle.firstState().name()
            .toLowerCase(java.util.Locale.ROOT);
        Hearthstead.LOGGER.info("HSQA_RECURRING_UI state={} fixture=VISUAL_ONLY "
                + "firstState={} recurring={} plannedNight={} cooldown={} aftermath={}",
            mode.name(), first, recurring, plannedNight, cooldown, aftermath);
        return new RecurringUiResult(true, "none", "visual_only", first,
            recurring, plannedNight, cooldown, aftermath);
    }

    private static RecurringUiResult recurringBlocked(String blocker, String detail,
                                                       String firstState) {
        return new RecurringUiResult(false, blocker, detail, firstState,
            "none", -1L, 0L, false);
    }

    /** Builds one no-reward visual terminal only from the existing prepared plan. */
    private static boolean seedVisualFirstRaid(ServerLevel level,
                                                Settlement settlement) {
        if (settlement == null || settlement.journeyState == null
            || !settlement.journeyState.completedThrough(
                JourneyIds.FJ_600_RECEIVE_FIRST_WARNING)) {
            return false;
        }
        RaidPlan plan = settlement.raidLifecycle.queuedPlan().orElse(null);
        if (plan == null || !settlement.raidLifecycle.beginFirstRaid(plan)) {
            return false;
        }
        UUID participant = stableVisualId("first_raid_participant", settlement.id,
            plan.night());
        RaidLogEntry aftermath = new RaidLogEntry(plan.night(), "QA visual captain",
            plan.objective().id(), false, 0, 0, "rolig");
        if (!RaidLogEntry.isValid(aftermath)
            || !settlement.raidLifecycle.recordParticipant(participant)
            || !settlement.raidLifecycle.sealParticipants()
            || !settlement.raidLifecycle.recordTerminalParticipant(participant)
            || !settlement.raidLifecycle.completeFirstRaid(JourneyOutcome.HIT,
                aftermath)) {
            return false;
        }
        settlement.pendingRaid = null;
        settlement.raidLog.add(aftermath);
        while (settlement.raidLog.size() > RaidDirector.MAX_RAID_LOG) {
            settlement.raidLog.remove(0);
        }
        if (!JourneyServerHooks.noteFirstRaidResolved(level, settlement, plan,
                JourneyOutcome.HIT)) {
            return false;
        }
        return RaidDirector.firstResolutionReceiptReady(settlement);
    }

    private static UUID stableVisualId(String domain, UUID settlementId, long value) {
        return UUID.nameUUIDFromBytes(("hearthstead:raidqa:" + domain + ":"
            + settlementId + ":" + value).getBytes(StandardCharsets.UTF_8));
    }

    /** The only fixture start edge; every target and gameplay gate is re-read. */
    public static StartResult start(ServerLevel level, ServerPlayer actor) {
        Result fixture = status(level, actor);
        if (fixture.settlement() == null) {
            return new StartResult(StartOutcome.INVALID_MARKER, fixture, 0,
                fixture.blocker());
        }
        Settlement settlement = fixture.settlement();
        if (settlement.raidLifecycle.firstState() == FirstRaidState.ACTIVE) {
            return new StartResult(StartOutcome.ALREADY_STARTED, fixture,
                settlement.raidLifecycle.participants().size(),
                "raid_already_active");
        }
        if (!fixture.ready()
            || settlement.raidLifecycle.firstState() != FirstRaidState.SCHEDULED
            || settlement.raidLifecycle.queuedPlan().isEmpty()) {
            return new StartResult(StartOutcome.BLOCKED, fixture, 0,
                "fixture_not_ready_before_first_raid");
        }
        List<com.hearthstead.entity.RaiderEntity> spawned =
            openRaidGateAndStart(level, fixture.origin(), settlement);
        Result after = status(level, actor);
        if (spawned.size() != RaidDirector.FIRST_RAID_BAND_SIZE
            || settlement.raidLifecycle.firstState() != FirstRaidState.ACTIVE) {
            return new StartResult(StartOutcome.BLOCKED, after,
                spawned.size(), "production_raid_start_refused");
        }
        return new StartResult(StartOutcome.STARTED, after, spawned.size(),
            "production_first_raid_active");
    }

    private static List<com.hearthstead.entity.RaiderEntity> openRaidGateAndStart(
            ServerLevel level, BlockPos origin, Settlement settlement) {
        BlockPos gate = origin.offset(0, 1, 1);
        BlockState before = level.getBlockState(gate);
        if (!before.is(Blocks.OAK_FENCE_GATE)) return List.of();
        level.setBlockAndUpdate(gate, before.setValue(FenceGateBlock.OPEN,
            true));
        List<com.hearthstead.entity.RaiderEntity> spawned =
            RaidDirector.startQueuedFirstRaid(level, settlement,
                settlement.raidLifecycle.firstAttackNight());
        if (spawned.size() != RaidDirector.FIRST_RAID_BAND_SIZE) {
            level.setBlockAndUpdate(gate, before);
        }
        return spawned;
    }

    private static Stage inferredStage(ServerLevel level,
            Settlement settlement) {
        if (worker(level, settlement, building(settlement,
                BuildingType.WATCHTOWER), Profession.ARCHER) != null) {
            return Stage.ARCHER_HIRED;
        }
        if (building(settlement, BuildingType.WATCHTOWER) != null) {
            return Stage.WATCHTOWER_REGISTERED;
        }
        if (settlement.population() >= 5) return Stage.FIVE_RESIDENTS;
        if (worker(level, settlement, building(settlement,
                BuildingType.BARRACKS), Profession.GUARD) != null) {
            return Stage.GUARD_HIRED;
        }
        if (building(settlement, BuildingType.BARRACKS) != null) {
            return Stage.BARRACKS_REGISTERED;
        }
        if (settlement.population() >= 4) return Stage.FOUR_RESIDENTS;
        if (building(settlement, BuildingType.TAVERN) != null) {
            return Stage.TAVERN_REGISTERED;
        }
        return Stage.FOUNDED_PHYSICAL_SHELLS;
    }

    private record Progress(Stage stage, String blocker, String detail) {
        private static Progress blocked(Stage stage, String blocker) {
            return new Progress(stage, blocker, "blocked:" + blocker);
        }

        private static Progress reached(Stage stage, String detail) {
            return new Progress(stage, "none", detail);
        }
    }

    private static String authorFoundingAndFirstBuilding(ServerLevel level,
            ServerPlayer actor, BlockPos origin, Settlement settlement,
            HearthBlockEntity hearth) {
        if (hearth == null) return "hearth_missing";
        List<SettlerEntity> founders = SettlementManager.loadedMembers(level,
            settlement);
        if (founders.isEmpty()) return "founders_unloaded";
        SettlerEntity mayor = Mayor.find(level, settlement);
        if (mayor == null || mayor.getProfession() != Profession.MAYOR
                || !mayor.getUUID().equals(settlement.mayorId)) return "mayor_blocked";
        if (!settlement.journeyState.isCompleted(
                JourneyIds.FJ_030_APPOINT_MAYOR)) {
            JourneyServerHooks.noteMayorAppointed(actor, settlement, mayor);
        }
        if (!settlement.journeyState.isCompleted(
                JourneyIds.FJ_020_OPEN_JOURNEY)) {
            actor.setPos(settlement.center.getX() + 0.5D,
                settlement.center.getY() + 0.5D,
                settlement.center.getZ() + 0.5D);
            actor.openMenu(hearth, buffer -> {
                buffer.writeBlockPos(settlement.center);
                buffer.writeUUID(settlement.id);
                buffer.writeUtf(settlement.name);
            });
            if (!(actor.containerMenu instanceof HearthMenu)) {
                actor.closeContainer();
                return "journey_open_blocked";
            }
            JourneyServerHooks.noteJourneyViewOpened(actor, settlement);
            actor.closeContainer();
        }
        boolean journeyOpenObserved = settlement.journeyState.isCompleted(
            JourneyIds.FJ_020_OPEN_JOURNEY);
        if (!journeyOpenObserved) return "journey_open_blocked";
        if (!settlement.journeyState.isCompleted(
                JourneyIds.FJ_030_APPOINT_MAYOR)) return "mayor_blocked";
        Development.revisionOf(level, settlement);
        Development.Result shelter = purchase(level, actor, hearth, settlement,
            DevelopmentNode.SHELTER);
        if (shelter != Development.Result.APPLIED
            && shelter != Development.Result.ALREADY_UNLOCKED) {
            return "shelter_blocked:" + shelter;
        }
        Development.Result timber = purchase(level, actor, hearth, settlement,
            DevelopmentNode.TIMBER_RIGHTS);
        if (timber != Development.Result.APPLIED
            && timber != Development.Result.ALREADY_UNLOCKED) {
            return "timber_blocked:" + timber;
        }
        return fitLearnedPlan(level, actor, settlement,
            origin.offset(20, 0, 3), 7, BuildingType.LUMBER_CAMP)
            ? "lumber_camp_registered" : "lumber_camp_registration_blocked";
    }

    /**
     * Advances the first production rung without projecting any result into
     * settlement state. Every durable transition below is owned by the same
     * production service as the ordinary player interaction.
     */
    private static Progress advanceLumberAndWarehouse(ServerLevel level,
            ServerPlayer actor, BlockPos origin, Settlement settlement,
            HearthBlockEntity hearth) {
        if (hearth == null) {
            return Progress.blocked(Stage.FOUNDED_PHYSICAL_SHELLS,
                "hearth_missing");
        }
        Building camp = building(settlement, BuildingType.LUMBER_CAMP);
        if (camp == null || !camp.valid) {
            return Progress.blocked(Stage.FOUNDED_PHYSICAL_SHELLS,
                "lumber_camp_missing_or_invalid");
        }

        SettlerEntity lumberer = worker(level, settlement, camp,
            Profession.LUMBERER);
        if (lumberer == null) {
            SettlerEntity candidate = unassignedFounder(level, settlement,
                false);
            if (candidate == null) {
                return Progress.blocked(Stage.FOUNDED_PHYSICAL_SHELLS,
                    "no_unassigned_lumberer_candidate");
            }
            String hired = purchaseAndHire(level, actor, hearth, settlement,
                candidate, camp, Profession.LUMBERER);
            if (hired != null) {
                return Progress.blocked(Stage.FOUNDED_PHYSICAL_SHELLS, hired);
            }
            lumberer = candidate;
        }

        if (!openSettlerInventory(actor, lumberer)) {
            return Progress.blocked(Stage.LUMBERER_HIRED,
                "lumberer_inventory_view_blocked");
        }
        if (!equipFromWorkplace(level, settlement, camp, lumberer,
                Items.IRON_AXE)) {
            return Progress.blocked(Stage.LUMBERER_HIRED,
                "lumberer_axe_equip_blocked");
        }

        BlockPos root = origin.offset(28, 1, 6);
        if (camp.workZone().isEmpty()) {
            placeNaturalTree(level, root);
            WorkZone zone = WorkZone.between(settlement.id, camp.id,
                WorkZone.Type.LUMBER, level.dimension().location(),
                root.offset(-4, -1, -4), root.offset(3, 6, 4),
                camp.workZoneRevision() + 1);
            WorkZoneService.Result committed = WorkZoneService.commitValidated(
                level, settlement, camp, lumberer, zone);
            if (committed != WorkZoneService.Result.APPLIED) {
                return Progress.blocked(Stage.LUMBERER_HIRED,
                    "lumber_zone_" + committed.name().toLowerCase());
            }
        }

        boolean foundingStored = settlement.foundingJourney.phase()
            == FoundingJourney.Phase.COMPLETE;
        boolean developmentStored = DevelopmentQuests.complete(level,
            settlement, hearth, Development.of(level, settlement),
            DevelopmentNode.STORES_AND_ROADS);
        if (!foundingStored || !developmentStored) {
            StoredSlot alreadyStored = storedLogs(level, camp, 1);
            ItemStack output = alreadyStored != null
                    && hasLumberReceipt(level, settlement, camp,
                        alreadyStored.stack().getItem(), 1)
                ? alreadyStored.stack().copyWithCount(1) : ItemStack.EMPTY;
            if (output.isEmpty()) {
                placeNaturalTree(level, root);
                output = performLumberWork(level, origin, settlement, camp,
                    lumberer, root);
                if (output.isEmpty()) {
                    return Progress.blocked(Stage.LUMBER_ZONE_COMMITTED,
                        "lumber_production_refused");
                }
            }
            // These two authorities are independent.  Always offer the same
            // committed physical receipt to both, then judge their observable
            // monotone postconditions; a retry may legitimately return false
            // from one hook because that authority already advanced.
            FoundingJourneyProgress.noteLogStored(level, settlement, camp,
                lumberer, output, output.getCount());
            DevelopmentQuests.noteLumberLogsStored(level, settlement, camp,
                lumberer, output, output.getCount());
            boolean foundingObserved = settlement.foundingJourney.phase()
                == FoundingJourney.Phase.COMPLETE;
            boolean developmentObserved = DevelopmentQuests.complete(level,
                settlement, hearth, Development.of(level, settlement),
                DevelopmentNode.STORES_AND_ROADS);
            if (!foundingObserved || !developmentObserved) {
                return Progress.blocked(Stage.LUMBER_ZONE_COMMITTED,
                    "lumber_receipt_postcondition_missing");
            }
        }

        Development.Result stores = purchase(level, actor, hearth, settlement,
            DevelopmentNode.STORES_AND_ROADS);
        if (stores != Development.Result.APPLIED
            && stores != Development.Result.ALREADY_UNLOCKED) {
            return Progress.blocked(Stage.LUMBER_PRODUCTION_STORED,
                "stores_and_roads_" + stores.name().toLowerCase());
        }
        if (!fitLearnedPlan(level, actor, settlement,
                origin.offset(12, 0, 13), 7, BuildingType.WAREHOUSE)) {
            return Progress.blocked(Stage.LUMBER_PRODUCTION_STORED,
                "warehouse_registration_refused");
        }
        Building warehouse = building(settlement, BuildingType.WAREHOUSE);
        if (warehouse == null || !warehouse.valid
            || !settlement.buildings.contains(warehouse)) {
            return Progress.blocked(Stage.LUMBER_PRODUCTION_STORED,
                "warehouse_registration_not_authoritative");
        }
        return advanceCourierAndFarm(level, actor, origin, settlement, hearth,
            camp, warehouse);
    }

    /**
     * Continues the real tutorial trunk from Warehouse registration through
     * one naturally grown crop.  The method may create physical QA inputs,
     * but every durable edge is committed by the production authority that
     * owns the corresponding player action.
     */
    private static Progress advanceCourierAndFarm(ServerLevel level,
            ServerPlayer actor, BlockPos origin, Settlement settlement,
            HearthBlockEntity hearth, Building camp, Building warehouse) {
        SettlerEntity courier = worker(level, settlement, warehouse,
            Profession.COURIER);
        if (courier == null) {
            SettlerEntity candidate = unassignedFounder(level, settlement,
                false);
            if (candidate == null) {
                return Progress.blocked(Stage.WAREHOUSE_REGISTERED,
                    "no_unassigned_courier_founder");
            }
            String hired = purchaseAndHire(level, actor, hearth, settlement,
                candidate, warehouse, Profession.COURIER);
            if (hired != null) {
                return Progress.blocked(Stage.WAREHOUSE_REGISTERED, hired);
            }
            courier = candidate;
        }
        if (!RequestLedgerService.validCourier(level, settlement, courier)) {
            return Progress.blocked(Stage.COURIER_HIRED,
                "courier_employment_not_authoritative");
        }
        String ledgerViewed = ensureRequestLedgerViewed(actor, settlement,
            hearth);
        if (ledgerViewed != null) {
            return Progress.blocked(Stage.COURIER_HIRED, ledgerViewed);
        }

        boolean cultivated = questComplete(level, settlement, hearth,
            DevelopmentNode.CULTIVATED_GROUND);
        if (!cultivated) {
            CourierDelivery delivery = completeCampToWarehouseDelivery(level,
                settlement, camp, warehouse, courier);
            if (delivery.request() == null) {
                return Progress.blocked(Stage.COURIER_HIRED,
                    delivery.blocker());
            }
            RequestRecord request = delivery.request();
            if (request.state() != RequestState.SATISFIED
                || !request.hasFullTransportTrace()
                || request.deliveredCount() != request.fingerprint().count()) {
                return Progress.blocked(Stage.COURIER_HIRED,
                    "courier_delivery_not_terminal");
            }
            DevelopmentQuests.noteCourierDelivery(level, settlement,
                courier, camp, warehouse, request.deliveredCount());
            cultivated = questComplete(level, settlement, hearth,
                DevelopmentNode.CULTIVATED_GROUND);
            if (!cultivated) {
                return Progress.blocked(Stage.COURIER_DELIVERY_COMMITTED,
                    "courier_delivery_quest_incomplete");
            }
        }

        Development.Result cultivatedPurchase = purchase(level, actor, hearth,
            settlement, DevelopmentNode.CULTIVATED_GROUND);
        if (cultivatedPurchase != Development.Result.APPLIED
            && cultivatedPurchase != Development.Result.ALREADY_UNLOCKED) {
            return Progress.blocked(Stage.COURIER_DELIVERY_COMMITTED,
                "cultivated_ground_" + cultivatedPurchase.name().toLowerCase());
        }
        if (!fitLearnedPlan(level, actor, settlement,
                origin.offset(3, 0, 13), 7, BuildingType.FARMHOUSE)) {
            return Progress.blocked(Stage.CULTIVATED_GROUND_UNLOCKED,
                "farmhouse_registration_refused");
        }
        Building farmhouse = building(settlement, BuildingType.FARMHOUSE);
        if (farmhouse == null || !farmhouse.valid
            || !settlement.buildings.contains(farmhouse)) {
            return Progress.blocked(Stage.CULTIVATED_GROUND_UNLOCKED,
                "farmhouse_registration_not_authoritative");
        }

        SettlerEntity farmer = worker(level, settlement, farmhouse,
            Profession.FARMER);
        if (farmer == null) {
            // Four founders include a dedicated Mayor and three actual workers.
            // The final unassigned worker becomes Farmer; the Mayor never works.
            SettlerEntity candidate = unassignedFounder(level, settlement,
                false);
            if (candidate == null) {
                return Progress.blocked(Stage.FARMHOUSE_REGISTERED,
                    "no_unassigned_farmer_founder");
            }
            String hired = purchaseAndHire(level, actor, hearth, settlement,
                candidate, farmhouse, Profession.FARMER);
            if (hired != null) {
                return Progress.blocked(Stage.FARMHOUSE_REGISTERED, hired);
            }
            farmer = candidate;
        }
        if (!openSettlerInventory(actor, farmer)) {
            return Progress.blocked(Stage.FARMER_HIRED,
                "farmer_inventory_view_blocked");
        }
        if (!equipFromWorkplace(level, settlement, farmhouse, farmer,
                Items.IRON_HOE)) {
            return Progress.blocked(Stage.FARMER_HIRED,
                "farmer_hoe_equip_blocked");
        }

        // Keep the irrigation border clear of the Barracks doorstep at (4,1,22).
        BlockPos fieldMin = origin.offset(5, 1, 20);
        // Resume the persisted plot, including unfinished fixtures from older layouts.
        BlockPos cropPos = farmhouse.workZone().map(zone -> zone.min().above())
            .orElseGet(() -> fieldMin.offset(0, 1, 1));
        if (farmhouse.workZone().isEmpty()) {
            placePhysicalField(level, fieldMin);
            WorkZone zone = WorkZone.between(settlement.id, farmhouse.id,
                WorkZone.Type.FARM, level.dimension().location(),
                cropPos.below(), cropPos,
                farmhouse.workZoneRevision() + 1);
            WorkZoneService.Result committed = WorkZoneService.commitValidated(
                level, settlement, farmhouse, farmer, zone);
            if (committed != WorkZoneService.Result.APPLIED) {
                return Progress.blocked(Stage.FARMER_HIRED,
                    "farmer_zone_" + committed.name().toLowerCase());
            }
        }

        // HOME's own quest is foundation readiness, which is intentionally
        // already true in a newly founded settlement. It cannot prove that
        // this fixture has planted, harvested, and delivered its first crop.
        // The Journey receipt is the durable, physical completion edge for
        // that loop and is also the idempotent replay boundary.
        boolean cropDelivered = settlement.journeyState.isCompleted(
            JourneyIds.FJ_380_WAREHOUSE_RECEIVES_CROP);
        if (!cropDelivered) {
            BlockState cropState = level.getBlockState(cropPos);
            if (cropState.isAir()) {
                String planted = plantPhysicalWheat(level, settlement,
                    farmhouse, farmer, cropPos);
                if (planted != null) {
                    return Progress.blocked(Stage.FARM_ZONE_COMMITTED, planted);
                }
                cropState = level.getBlockState(cropPos);
            }
            if (!(cropState.getBlock() instanceof CropBlock crop)) {
                return Progress.blocked(Stage.FARM_ZONE_COMMITTED,
                    "farm_crop_position_changed");
            }
            if (!crop.isMaxAge(cropState)) {
                return Progress.reached(Stage.WAITING_NATURAL_CROP,
                    "physical_wheat_planted_age=" + crop.getAge(cropState)
                        + ";waiting_for_natural_random_ticks");
            }
            String harvested = harvestAndStorePhysicalCrop(level, settlement,
                farmhouse, farmer, cropPos);
            if (harvested != null) {
                return Progress.blocked(Stage.FARM_ZONE_COMMITTED, harvested);
            }
            if (!settlement.journeyState.isCompleted(
                    JourneyIds.FJ_370_FARMHOUSE_STORES_CROP)) {
                return Progress.blocked(Stage.FARM_ZONE_COMMITTED,
                    "farm_crop_receipt_not_observed");
            }

            CourierDelivery cropDelivery = completeCropToWarehouseDelivery(level,
                settlement, farmhouse, warehouse, courier);
            if (cropDelivery.request() == null) {
                return Progress.blocked(Stage.FARM_ZONE_COMMITTED,
                    cropDelivery.blocker());
            }
            if (!settlement.journeyState.isCompleted(
                    JourneyIds.FJ_380_WAREHOUSE_RECEIVES_CROP)) {
                return Progress.blocked(Stage.FARM_ZONE_COMMITTED,
                    "crop_warehouse_receipt_not_observed");
            }
        }
        // FJ-360 through FJ-380 already prove one physical seed was withdrawn,
        // planted, harvested, stored and courier-delivered. An empty field after
        // that terminal first loop belongs to the live Farmer's ordinary replant;
        // fixture code must not start a second seed withdrawal while that worker
        // may still be settling its own physical output.

        Development.Result homePurchase = purchase(level, actor, hearth,
            settlement, DevelopmentNode.HOME);
        if (homePurchase != Development.Result.APPLIED
            && homePurchase != Development.Result.ALREADY_UNLOCKED) {
            return Progress.blocked(Stage.FARM_ZONE_COMMITTED,
                "home_" + homePurchase.name().toLowerCase());
        }
        return advanceHomesAndTravelers(level, actor, origin, settlement,
            hearth);
    }

    /**
     * Continues only after the physical crop receipt unlocked Home. Natural
     * recruitment clocks and traveler movement remain owned by the Hearth
     * heartbeat and Traveler AI; prepare merely observes, funds physical QA
     * inputs, and performs the explicit production admission transaction.
     */
    private static Progress advanceHomesAndTravelers(ServerLevel level,
            ServerPlayer actor, BlockPos origin, Settlement settlement,
            HearthBlockEntity hearth) {
        boolean firstHome = fitLearnedPlan(level, actor, settlement,
            origin.offset(3, 0, 3), 5, BuildingType.HOUSE);
        boolean secondHome = fitLearnedPlan(level, actor, settlement,
            origin.offset(12, 0, 3), 5, BuildingType.HOUSE);
        if (!firstHome || !secondHome) {
            return Progress.blocked(Stage.HOME_UNLOCKED,
                "home_registration_refused");
        }
        long exactHomes = settlement.buildings.stream()
            .filter(building -> building != null && building.valid
                && building.type == BuildingType.HOUSE)
            .count();
        if (exactHomes != 2L || settlement.validBedCount() != 6) {
            return Progress.blocked(Stage.HOME_UNLOCKED,
                "six_physical_beds_not_authoritative");
        }

        Development.Result hospitality = purchase(level, actor, hearth,
            settlement, DevelopmentNode.HOSPITALITY);
        if (hospitality != Development.Result.APPLIED
            && hospitality != Development.Result.ALREADY_UNLOCKED) {
            return Progress.blocked(Stage.HOMES_REGISTERED,
                "hospitality_" + hospitality.name().toLowerCase());
        }
        if (!fitLearnedPlan(level, actor, settlement,
                origin.offset(22, 0, 13), 8, BuildingType.TAVERN)) {
            return Progress.blocked(Stage.HOSPITALITY_UNLOCKED,
                "tavern_registration_refused");
        }
        Building tavern = building(settlement, BuildingType.TAVERN);
        if (tavern == null || !tavern.valid
            || !settlement.buildings.contains(tavern)) {
            return Progress.blocked(Stage.HOSPITALITY_UNLOCKED,
                "tavern_registration_not_authoritative");
        }
        int population = settlement.population();
        if (population < 4 || population > 6) {
            return Progress.blocked(Stage.TAVERN_REGISTERED,
                "fixture_population_out_of_bounds");
        }
        if (!ensureAtHearth(hearth, Items.BREAD, 64)
            || !ensureAtHearth(hearth, Items.OAK_PLANKS, 32)) {
            return Progress.blocked(Stage.TAVERN_REGISTERED,
                "traveler_admission_inputs_unavailable");
        }
        if (population == 4) {
            return advanceOneTraveler(actor, settlement, population);
        }
        return advanceFirstWatch(level, actor, origin, settlement, hearth,
            warehouseFor(settlement), tavern, population);
    }

    private static Progress advanceOneTraveler(ServerPlayer actor,
            Settlement settlement, int population) {
        RecruitmentTransaction transaction = settlement.recruitment;
        if (transaction == null || transaction.revisionSaturated()
            || transaction.status() == RecruitmentTransaction.Status.QUARANTINED
            || transaction.status() == RecruitmentTransaction.Status.UNKNOWN) {
            return Progress.blocked(population == 4
                    ? Stage.TAVERN_REGISTERED : Stage.FOUR_RESIDENTS,
                "recruitment_authority_invalid");
        }
        Stage waitingStage = population == 4
            ? Stage.WAITING_FIRST_TRAVELER : Stage.WAITING_SECOND_TRAVELER;
        if (transaction.status()
                != RecruitmentTransaction.Status.WAITING_ADMISSION) {
            return Progress.reached(waitingStage,
                "natural_recruitment_status="
                    + transaction.status().name().toLowerCase()
                    + ";progress=" + transaction.progress()
                    + ";target=" + transaction.lockedTarget());
        }
        UUID travelerId = transaction.travelerId();
        if (travelerId == null) {
            return Progress.blocked(waitingStage,
                "waiting_traveler_identity_missing");
        }
        // Explicit QA funding follows the already saved candidate quote. Never
        // reroll aptitude, advance arrival, replace prior goods or fund per tick.
        var quote = transaction.quote();
        boolean coinQuote = quote != null && quote.version() == 2;
        int coinsBefore = -1;
        if (coinQuote) {
            if (quote.legacyPending() || !transaction.transactionId().equals(quote.transactionId())
                || !travelerId.equals(quote.travelerId())
                || !(actor.serverLevel().getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth)
                || !settlement.id.equals(hearth.getSettlementId())
                || !ensureAtHearth(hearth, com.hearthstead.registry.ModItems.GOLD_COIN.get(), quote.coins())) {
                return Progress.blocked(waitingStage, "traveler_quoted_coins_unavailable");
            }
            coinsBefore = physicalAdmissionCoins(actor, settlement, hearth);
        }
        // A waiting visitor may be walking from the door to a Tavern chair.
        // Funding above is explicit and idempotent; keep every real policy and
        // identity failure terminal, but wait for physical contact before admission.
        if (SettlementManager.candidateBlocker(actor.serverLevel(), settlement, actor)
                == com.hearthstead.settlement.RecruitmentPolicy.Blocker.NONE
            && SettlementManager.candidateMayDismiss(actor.serverLevel(), settlement)
            && !SettlementManager.candidateMayAdmit(actor.serverLevel(), settlement, actor)) {
            return Progress.reached(waitingStage, "traveler_waiting_for_physical_admission");
        }
        SettlementManager.AdmissionResult admitted =
            SettlementManager.admitWaitingTraveler(actor, settlement,
                travelerId, transaction.revision());
        if (admitted != SettlementManager.AdmissionResult.COMMITTED) {
            return Progress.blocked(waitingStage,
                "traveler_admission_" + admitted.name().toLowerCase());
        }
        if (coinQuote && (!(actor.serverLevel().getBlockEntity(settlement.center) instanceof HearthBlockEntity paidHearth)
            || coinsBefore - physicalAdmissionCoins(actor, settlement, paidHearth) != quote.coins()
            || settlement.recruitment.admissionReceipt() == null)) {
            return Progress.blocked(waitingStage, "traveler_quoted_coin_debit_mismatch");
        }
        int after = settlement.population();
        if (after != population + 1
            || settlement.record(travelerId) == null) {
            return Progress.blocked(waitingStage,
                "traveler_admission_postcondition_missing");
        }
        return Progress.reached(after == 6
                ? Stage.FIVE_RESIDENTS : Stage.FOUR_RESIDENTS,
            "natural_traveler_admitted;population=" + after);
    }

    private static int physicalAdmissionCoins(ServerPlayer actor, Settlement settlement,
            HearthBlockEntity hearth) {
        var treasury = com.hearthstead.settlement.CoinTreasury.open(
            actor.serverLevel(), settlement, hearth, actor);
        int total = 0;
        for (int slot = 0; slot < treasury.getSlots(); slot++) {
            ItemStack stack = treasury.getStackInSlot(slot);
            if (stack.is(com.hearthstead.registry.ModItems.GOLD_COIN.get())) total += stack.getCount();
        }
        return total;
    }

    private static Building warehouseFor(Settlement settlement) {
        return building(settlement, BuildingType.WAREHOUSE);
    }

    /** Builds and proves the two defender loops without ever starting combat. */
    private static Progress advanceFirstWatch(ServerLevel level,
            ServerPlayer actor, BlockPos origin, Settlement settlement,
            HearthBlockEntity hearth, Building warehouse, Building tavern,
            int population) {
        if (warehouse == null || !warehouse.valid) {
            return Progress.blocked(Stage.FOUR_RESIDENTS,
                "warehouse_missing_before_first_watch");
        }
        Development.Result firstWatch = purchase(level, actor, hearth,
            settlement, DevelopmentNode.FIRST_WATCH);
        if (firstWatch != Development.Result.APPLIED
            && firstWatch != Development.Result.ALREADY_UNLOCKED) {
            return Progress.blocked(Stage.FOUR_RESIDENTS,
                "first_watch_" + firstWatch.name().toLowerCase());
        }
        if (!fitLearnedPlan(level, actor, settlement,
                origin.offset(3, 0, 24), 8, BuildingType.BARRACKS)) {
            return Progress.blocked(Stage.FIRST_WATCH_UNLOCKED,
                "barracks_registration_refused");
        }
        Building barracks = building(settlement, BuildingType.BARRACKS);
        if (barracks == null || !barracks.valid) {
            return Progress.blocked(Stage.FIRST_WATCH_UNLOCKED,
                "barracks_registration_not_authoritative");
        }
        SettlerEntity guard = worker(level, settlement, barracks,
            Profession.GUARD);
        if (guard == null) {
            SettlerEntity candidate = unassignedFounder(level, settlement,
                false);
            if (candidate == null) {
                return Progress.blocked(Stage.BARRACKS_REGISTERED,
                    "no_unassigned_guard_candidate");
            }
            if (!candidate.getMainHandItem().isEmpty()) {
                return Progress.blocked(Stage.BARRACKS_REGISTERED,
                    "guard_candidate_started_with_tool");
            }
            String hired = purchaseAndHire(level, actor, hearth, settlement,
                candidate, barracks, Profession.GUARD);
            if (hired != null) {
                return Progress.blocked(Stage.BARRACKS_REGISTERED, hired);
            }
            guard = candidate;
        }
        String sword = deliverRequestedEquipment(level, settlement, warehouse,
            barracks, courierWorker(level, settlement, warehouse), guard,
            Items.IRON_SWORD);
        if (sword != null) {
            if (sword.startsWith("courier_busy_")) {
                return Progress.reached(Stage.GUARD_HIRED,
                    "waiting_for_" + sword);
            }
            return Progress.blocked(Stage.GUARD_HIRED, "guard_sword_" + sword);
        }
        String armor = deliverWarehouseOutput(level, settlement, warehouse,
            barracks, courierWorker(level, settlement, warehouse),
            Items.LEATHER_CHESTPLATE, 1);
        if (armor != null) {
            if (armor.startsWith("courier_busy_")) {
                return Progress.reached(Stage.GUARD_HIRED,
                    "waiting_for_" + armor);
            }
            return Progress.blocked(Stage.GUARD_HIRED, "guard_armor_" + armor);
        }
        if (!equipDelivered(level, settlement, barracks, guard)) {
            return Progress.blocked(Stage.GUARD_LOADOUT_DELIVERED,
                "guard_sword_equip_refused");
        }
        if (!issueDefenderOrder(level, actor, settlement, guard, barracks,
                false)) {
            return Progress.blocked(Stage.GUARD_LOADOUT_DELIVERED,
                "guard_stand_order_refused");
        }
        Development.Result arm = purchase(level, actor, hearth, settlement,
            DevelopmentNode.ARM_THE_WATCH);
        if (arm != Development.Result.APPLIED
            && arm != Development.Result.ALREADY_UNLOCKED) {
            return Progress.blocked(Stage.GUARD_READY,
                "arm_the_watch_" + arm.name().toLowerCase());
        }
        if (population == 5) {
            return advanceOneTraveler(actor, settlement, population);
        }
        if (population != 6) {
            return Progress.blocked(Stage.ARM_THE_WATCH_UNLOCKED,
                "second_traveler_population_invalid");
        }

        if (!fitLearnedPlan(level, actor, settlement,
                origin.offset(WATCHTOWER_OFFSET.getX(),
                    WATCHTOWER_OFFSET.getY(), WATCHTOWER_OFFSET.getZ()), 7,
                BuildingType.WATCHTOWER)) {
            return Progress.blocked(Stage.FIVE_RESIDENTS,
                "watchtower_registration_refused");
        }
        Building watchtower = building(settlement, BuildingType.WATCHTOWER);
        if (watchtower == null || !watchtower.valid) {
            return Progress.blocked(Stage.FIVE_RESIDENTS,
                "watchtower_registration_not_authoritative");
        }
        SettlerEntity archer = worker(level, settlement, watchtower,
            Profession.ARCHER);
        if (archer == null) {
            SettlerEntity candidate = unassignedFounder(level, settlement,
                false);
            if (candidate == null) {
                return Progress.blocked(Stage.WATCHTOWER_REGISTERED,
                    "no_unassigned_archer_candidate");
            }
            if (!candidate.getMainHandItem().isEmpty()) {
                return Progress.blocked(Stage.WATCHTOWER_REGISTERED,
                    "archer_candidate_started_with_tool");
            }
            String hired = purchaseAndHire(level, actor, hearth, settlement,
                candidate, watchtower, Profession.ARCHER);
            if (hired != null) {
                return Progress.blocked(Stage.WATCHTOWER_REGISTERED, hired);
            }
            archer = candidate;
        }
        SettlerEntity courier = courierWorker(level, settlement, warehouse);
        String bow = deliverRequestedEquipment(level, settlement, warehouse,
            watchtower, courier, archer, Items.BOW);
        if (bow != null) {
            if (bow.startsWith("courier_busy_")) {
                return Progress.reached(Stage.ARCHER_HIRED,
                    "waiting_for_" + bow);
            }
            return Progress.blocked(Stage.ARCHER_HIRED, "archer_bow_" + bow);
        }
        String arrows = deliverWarehouseOutput(level, settlement, warehouse,
            watchtower, courier, Items.ARROW,
            FirstRaidReadinessService.MIN_FIRST_RAID_ARROWS);
        if (arrows != null) {
            if (arrows.startsWith("courier_busy_")) {
                return Progress.reached(Stage.ARCHER_HIRED,
                    "waiting_for_" + arrows);
            }
            return Progress.blocked(Stage.ARCHER_HIRED,
                "archer_arrows_" + arrows);
        }
        if (!equipDelivered(level, settlement, watchtower, archer)) {
            return Progress.blocked(Stage.ARCHER_LOADOUT_DELIVERED,
                "archer_bow_equip_refused");
        }
        if (!issueDefenderOrder(level, actor, settlement, archer, watchtower,
                true)) {
            return Progress.blocked(Stage.ARCHER_LOADOUT_DELIVERED,
                "archer_tower_order_refused");
        }
        FirstRaidReadinessService.Report declaration =
            FirstRaidReadinessService.assessDomain(level, settlement);
        if (settlement.raidLifecycle.firstState() == FirstRaidState.PREPARING
                && !declaration.ready()) {
            return Progress.blocked(Stage.ARCHER_LOADOUT_DELIVERED,
                "domain_readiness_" + blockerIds(declaration));
        }
        if (settlement.raidLifecycle.firstState() == FirstRaidState.PREPARING
            && !RaidDirector.commitFirstRaidReadiness(level, settlement)) {
            return Progress.blocked(Stage.DOMAIN_READY,
                "raid_readiness_commit_refused");
        }
        if (settlement.raidLifecycle.firstState() == FirstRaidState.SCHEDULED
            && settlement.raidLifecycle.queuedPlan().isEmpty()
            && !RaidDirector.queueFirstWarningIfDue(level, settlement,
                settlement.raidLifecycle.firstWarningNight())) {
            return Progress.blocked(Stage.EXECUTION_READY,
                "first_warning_queue_refused");
        }
        FirstRaidReadinessService.Report execution =
            FirstRaidReadinessService.assessExecution(level, settlement);
        return execution.ready()
            ? Progress.reached(Stage.READY_BEFORE_FIRST_RAID,
                "production_readiness_and_warning_committed;raid_not_started")
            : Progress.blocked(Stage.EXECUTION_READY,
                "execution_readiness_" + blockerIds(execution));
    }

    private static SettlerEntity courierWorker(ServerLevel level,
            Settlement settlement, Building warehouse) {
        return warehouse == null ? null : worker(level, settlement, warehouse,
            Profession.COURIER);
    }

    private static String blockerIds(FirstRaidReadinessService.Report report) {
        return report == null ? "missing"
            : report.blockers().stream().map(blocker -> blocker.id())
                .collect(java.util.stream.Collectors.joining("+"));
    }

    private record CourierDelivery(RequestRecord request, String blocker) {
        private static CourierDelivery blocked(String blocker) {
            return new CourierDelivery(null, blocker);
        }
    }

    private record StoredSlot(BlockPos pos, int slot, ItemStack stack) {
        private StoredSlot {
            pos = pos.immutable();
            stack = stack.copy();
        }
    }

    private static boolean questComplete(ServerLevel level,
            Settlement settlement, HearthBlockEntity hearth,
            DevelopmentNode node) {
        return DevelopmentQuests.complete(level, settlement, hearth,
            Development.of(level, settlement), node);
    }

    private static String ensureRequestLedgerViewed(ServerPlayer actor,
            Settlement settlement, HearthBlockEntity hearth) {
        if (settlement.journeyState.isCompleted(
                JourneyIds.FJ_230_OPEN_REQUEST_LEDGER)) return null;
        actor.setPos(settlement.center.getX() + 0.5D,
            settlement.center.getY() + 0.5D,
            settlement.center.getZ() + 0.5D);
        actor.openMenu(hearth, buffer -> {
            buffer.writeBlockPos(settlement.center);
            buffer.writeUUID(settlement.id);
            buffer.writeUtf(settlement.name);
        });
        try {
            var snapshot = RequestLedgerService.snapshotForHearth(actor,
                settlement);
            if (snapshot.isEmpty()) return "request_ledger_snapshot_refused";
            RequestLedgerService.noteSnapshotSent(actor, settlement,
                snapshot.get());
            return settlement.journeyState.isCompleted(
                    JourneyIds.FJ_230_OPEN_REQUEST_LEDGER)
                ? null : "request_ledger_view_not_observed";
        } finally {
            actor.closeContainer();
        }
    }

    /**
     * Opens or resumes one exact production-ledger route.  The permanent
     * Lumber receipt is checked before a fresh request can name the ordinary
     * (transit marker already stripped) physical logs in Camp storage.
     */
    private static CourierDelivery completeCampToWarehouseDelivery(
            ServerLevel level, Settlement settlement, Building camp,
            Building warehouse, SettlerEntity courier) {
        RequestLedgerSavedData saved = RequestLedgerSavedData.existing(level);
        RequestLedger ledger = saved == null ? null
            : saved.existing(settlement.id);
        RequestRecord terminal = matchingRequest(ledger, camp, warehouse,
            courier, Items.OAK_LOG, 2, true);
        if (terminal != null) {
            return new CourierDelivery(terminal, "none");
        }

        RequestRecord request = matchingRequest(ledger, camp, warehouse,
            courier, Items.OAK_LOG, 2, false);
        if (request == null) {
            StoredSlot source = storedLogs(level, camp, 2);
            if (source == null) {
                return CourierDelivery.blocked("camp_provenance_logs_missing");
            }
            if (!hasLumberReceipt(level, settlement, camp,
                    source.stack().getItem(), 2)) {
                return CourierDelivery.blocked(
                    "camp_logs_lack_lumber_receipt");
            }
            BlockPos target = WorkerStorageAuthority.loadedContainers(level,
                    warehouse).stream()
                .min(Comparator.comparingDouble(source.pos()::distSqr))
                .orElse(null);
            if (target == null) {
                return CourierDelivery.blocked("warehouse_storage_missing");
            }
            RequestLedgerService.Decision opened = RequestLedgerService
                .openOutputPickup(level, settlement, camp, source.pos(),
                    source.slot(), warehouse, target, 2,
                    RequestPriority.URGENT);
            if (!opened.accepted() || opened.request() == null) {
                return CourierDelivery.blocked("courier_request_open_"
                    + opened.outcome().name().toLowerCase() + "_"
                    + opened.blocker().id());
            }
            request = opened.request();
        }

        if (request.courierId() == null
            && request.effectiveState() == RequestState.OPEN) {
            RequestLedgerService.Decision reserved = RequestLedgerService
                .reserve(level, settlement, request.id(), courier);
            if (!reserved.accepted() || reserved.request() == null) {
                return CourierDelivery.blocked("courier_request_claim_"
                    + reserved.outcome().name().toLowerCase() + "_"
                    + reserved.blocker().id());
            }
            request = reserved.request();
        }
        if (!Objects.equals(request.courierId(), courier.getUUID())) {
            return CourierDelivery.blocked("courier_request_owned_by_other");
        }

        RequestLedgerService.Route route = RequestLedgerService.routeForCourier(
            level, settlement, courier);
        if (route.request() == null || !route.request().id().equals(request.id())) {
            return CourierDelivery.blocked("courier_route_identity_mismatch");
        }
        if (route.phase() == RequestLedgerService.RoutePhase.TO_SOURCE) {
            standBeside(courier, route.request().sourceContainer());
            RequestLedgerService.Decision picked = RequestLedgerService.pickup(
                level, settlement, request.id(), courier);
            if (!picked.accepted()) {
                return CourierDelivery.blocked("courier_pickup_"
                    + picked.outcome().name().toLowerCase() + "_"
                    + picked.blocker().id());
            }
            route = RequestLedgerService.routeForCourier(level, settlement,
                courier);
        }
        if (route.phase() == RequestLedgerService.RoutePhase.TO_TARGET) {
            standBeside(courier, route.request().targetContainer());
            RequestLedgerService.Decision delivered = RequestLedgerService
                .deliver(level, settlement, request.id(), courier);
            if (delivered.outcome() != RequestLedgerService.Outcome.SATISFIED
                || delivered.request() == null) {
                return CourierDelivery.blocked("courier_delivery_"
                    + delivered.outcome().name().toLowerCase() + "_"
                    + delivered.blocker().id());
            }
            return new CourierDelivery(delivered.request(), "none");
        }
        if (route.phase() == RequestLedgerService.RoutePhase.COMPLETE
            && route.request().state() == RequestState.SATISFIED) {
            return new CourierDelivery(route.request(), "none");
        }
        return CourierDelivery.blocked("courier_route_"
            + route.phase().name().toLowerCase() + "_"
            + route.blocker().id());
    }

    private static CourierDelivery completeCropToWarehouseDelivery(
            ServerLevel level, Settlement settlement, Building farmhouse,
            Building warehouse, SettlerEntity courier) {
        if (farmhouse == null || warehouse == null || courier == null
            || !RequestLedgerService.validCourier(level, settlement, courier)) {
            return CourierDelivery.blocked("crop_route_invalid_authority");
        }
        RequestLedgerSavedData saved = RequestLedgerSavedData.existing(level);
        RequestLedger ledger = saved == null ? null
            : saved.existing(settlement.id);
        StoredSlot crop = storedCrop(level, farmhouse);
        net.minecraft.world.item.Item item = crop == null ? Items.WHEAT
            : crop.stack().getItem();
        RequestRecord terminal = matchingRequest(ledger, farmhouse, warehouse,
            courier, item, 1, true);
        if (terminal != null) return new CourierDelivery(terminal, "none");
        RequestRecord request = matchingRequest(ledger, farmhouse, warehouse,
            courier, item, 1, false);
        if (request == null) {
            if (crop == null) {
                return CourierDelivery.blocked("farm_crop_output_missing");
            }
            BlockPos target = WorkerStorageAuthority.loadedContainers(level,
                    warehouse).stream()
                .min(Comparator.comparingDouble(crop.pos()::distSqr))
                .orElse(null);
            if (target == null) {
                return CourierDelivery.blocked("warehouse_storage_missing");
            }
            RequestLedgerService.Decision opened = RequestLedgerService
                .openOutputPickup(level, settlement, farmhouse, crop.pos(),
                    crop.slot(), warehouse, target, 1, RequestPriority.HIGH);
            if (!opened.accepted() || opened.request() == null) {
                return CourierDelivery.blocked("crop_request_open_"
                    + opened.outcome().name().toLowerCase() + "_"
                    + opened.blocker().id());
            }
            request = opened.request();
        }
        if (request.courierId() == null
            && request.effectiveState() == RequestState.OPEN) {
            RequestLedgerService.Decision reserved = RequestLedgerService
                .reserve(level, settlement, request.id(), courier);
            if (!reserved.accepted() || reserved.request() == null) {
                return CourierDelivery.blocked("crop_request_claim_"
                    + reserved.outcome().name().toLowerCase() + "_"
                    + reserved.blocker().id());
            }
            request = reserved.request();
        }
        if (!Objects.equals(request.courierId(), courier.getUUID())) {
            return CourierDelivery.blocked("crop_request_owned_by_other");
        }
        RequestLedgerService.Route route = RequestLedgerService.routeForCourier(
            level, settlement, courier);
        if (route.request() == null
            || !route.request().id().equals(request.id())) {
            return CourierDelivery.blocked("crop_route_identity_mismatch");
        }
        if (route.phase() == RequestLedgerService.RoutePhase.TO_SOURCE) {
            standBeside(courier, request.sourceContainer());
            RequestLedgerService.Decision picked = RequestLedgerService.pickup(
                level, settlement, request.id(), courier);
            if (!picked.accepted()) {
                return CourierDelivery.blocked("crop_pickup_"
                    + picked.outcome().name().toLowerCase() + "_"
                    + picked.blocker().id());
            }
            route = RequestLedgerService.routeForCourier(level, settlement,
                courier);
        }
        if (route.phase() == RequestLedgerService.RoutePhase.TO_TARGET) {
            standBeside(courier, request.targetContainer());
            RequestLedgerService.Decision delivered = RequestLedgerService
                .deliver(level, settlement, request.id(), courier);
            if (delivered.outcome() != RequestLedgerService.Outcome.SATISFIED
                || delivered.request() == null) {
                return CourierDelivery.blocked("crop_delivery_"
                    + delivered.outcome().name().toLowerCase() + "_"
                    + delivered.blocker().id());
            }
            return new CourierDelivery(delivered.request(), "none");
        }
        return route.phase() == RequestLedgerService.RoutePhase.COMPLETE
                && route.request().state() == RequestState.SATISFIED
            ? new CourierDelivery(route.request(), "none")
            : CourierDelivery.blocked("crop_route_"
                + route.phase().name().toLowerCase() + "_"
                + route.blocker().id());
    }

    /** Resumes one genuine EquipmentRequest through exact chest/bag evidence. */
    private static String deliverRequestedEquipment(ServerLevel level,
            Settlement settlement, Building warehouse, Building workplace,
            SettlerEntity courier, SettlerEntity worker,
            net.minecraft.world.item.Item supplied) {
        if (warehouse == null || workplace == null || courier == null
            || worker == null
            || !RequestLedgerService.validCourier(level, settlement, courier)) {
            return "invalid_authority";
        }
        var requirement = EquipmentRequests.requirementFor(
            worker.getProfession());
        if (requirement == null || !requirement.matches(
                new ItemStack(supplied))) {
            return "wrong_requirement";
        }
        if (requirement.serviceable(worker.getMainHandItem())) return null;
        // Publish the real missing-tool request before yielding to cargo that
        // is already in flight. CourierWorkGoal selects OPEN equipment work
        // ahead of a new warehouse-consolidation loop once it has committed
        // this physical bag; waiting to create the row until one transient
        // empty-bag tick made the fixture repeatedly miss that handoff.
        EquipmentRequest request = EquipmentRequests.requestFor(workplace,
            worker.getUUID());
        if (request == null) {
            request = EquipmentRequests.refreshFor(level, settlement,
                workplace, worker);
        }
        if (request == null) return "request_missing";
        String priorLedgerRoute = completeOwnedLedgerRoute(level, settlement,
            courier);
        if (priorLedgerRoute != null) {
            if (priorLedgerRoute.startsWith("blocked_")) {
                return priorLedgerRoute.substring("blocked_".length());
            }
            return "courier_busy_" + priorLedgerRoute;
        }
        if (!courier.bag.isEmpty()) {
            // Food and other non-ledger Courier work owns the same physical
            // bag. Let normal AI finish it rather than overwriting, dropping,
            // or manufacturing around that transport state. The already-open
            // request is then selected ahead of the next consolidation route.
            return "courier_busy_non_ledger_bag";
        }
        if (request.status() == EquipmentRequest.Status.DELIVERED) return null;
        if (request.status() == EquipmentRequest.Status.OPEN
            && !EquipmentRequests.claim(level, settlement, request.id(),
                courier.getUUID())) {
            return "claim_refused";
        }
        if (!Objects.equals(request.claimedBy(), courier.getUUID())) {
            return "claimed_by_other";
        }
        if (request.traceStage() == EquipmentRequest.TraceStage.NONE) {
            WorkerStorageAuthority.Source source = WorkerStorageAuthority.find(
                level, warehouse, stack -> requirement.serviceable(stack),
                courier.blockPosition());
            if (source == null) {
                ItemStack remainder = WorkplaceStorage.insert(level, warehouse,
                    new ItemStack(supplied));
                if (!remainder.isEmpty()) return "warehouse_full";
                source = WorkerStorageAuthority.find(level, warehouse,
                    stack -> requirement.serviceable(stack),
                    courier.blockPosition());
            }
            BlockPos target = WorkerStorageAuthority.nearestLoadedContainer(
                level, workplace, source == null ? worker.blockPosition()
                    : source.pos());
            if (source == null || target == null
                || !EquipmentRequests.bindRoute(level, settlement,
                    request.id(), courier, warehouse, source.pos(),
                    source.slot(), workplace, target,
                    source.observed().copyWithCount(1))) {
                return "route_bind_refused";
            }
        }
        EquipmentRequests.CourierRoute route = EquipmentRequests.routeForCourier(
            level, settlement, courier);
        if (route == null || !route.request().id().equals(request.id())) {
            return "route_identity_mismatch";
        }
        if (route.stage() == EquipmentRequest.TraceStage.SOURCE) {
            if (!(level.getBlockEntity(route.sourceContainer())
                    instanceof Container source)) return "source_missing";
            int bagSlot = firstEmptySlot(courier.bag);
            if (bagSlot < 0) return "courier_bag_full";
            ItemStack moved = source.removeItem(route.request().sourceSlot(), 1);
            source.setChanged();
            if (moved.isEmpty() || !requirement.serviceable(moved)) {
                return "source_item_changed";
            }
            courier.bag.setItem(bagSlot, moved);
            if (!EquipmentRequests.markPickedUp(level, settlement,
                    request.id(), courier)) {
                return "pickup_commit_refused";
            }
            route = EquipmentRequests.routeForCourier(level, settlement,
                courier);
        }
        if (route == null
            || route.stage() != EquipmentRequest.TraceStage.COURIER_BAG
            || !(level.getBlockEntity(route.targetContainer())
                instanceof Container target)) {
            return "target_route_missing";
        }
        int bagSlot = firstMatchingSlot(courier.bag, requirement);
        int targetSlot = firstEmptySlot(target);
        if (bagSlot < 0 || targetSlot < 0) return "target_full_or_bag_missing";
        ItemStack delivered = courier.bag.removeItem(bagSlot, 1);
        target.setItem(targetSlot, delivered);
        target.setChanged();
        return EquipmentRequests.markDelivered(level, settlement, request.id(),
            courier, route.targetContainer()) ? null : "delivery_commit_refused";
    }

    /**
     * Finishes a typed production-ledger route the fixture Courier already
     * owns before starting an equipment trace. Normal AI commonly selects a
     * warehouse consolidation during the crop/traveler wait; the fixture must
     * conserve and complete it, not erase it or compete for the same bag.
     */
    private static String completeOwnedLedgerRoute(ServerLevel level,
            Settlement settlement, SettlerEntity courier) {
        RequestLedgerService.Route route = RequestLedgerService.routeForCourier(
            level, settlement, courier);
        if (route.request() == null) {
            return route.outcome() == RequestLedgerService.Outcome.QUARANTINED
                ? "blocked_ledger_quarantined" : null;
        }
        if (route.phase() == RequestLedgerService.RoutePhase.TO_SOURCE) {
            standBeside(courier, route.request().sourceContainer());
            RequestLedgerService.Decision pickup = RequestLedgerService.pickup(
                level, settlement, route.request().id(), courier);
            if (!pickup.accepted()) {
                return "blocked_ledger_pickup_"
                    + pickup.outcome().name().toLowerCase() + "_"
                    + pickup.blocker().id();
            }
            route = RequestLedgerService.routeForCourier(level, settlement,
                courier);
        }
        if (route.request() != null
            && route.phase() == RequestLedgerService.RoutePhase.TO_TARGET) {
            standBeside(courier, route.request().targetContainer());
            RequestLedgerService.Decision delivered = RequestLedgerService
                .deliver(level, settlement, route.request().id(), courier);
            if (!delivered.accepted()) {
                return "blocked_ledger_delivery_"
                    + delivered.outcome().name().toLowerCase() + "_"
                    + delivered.blocker().id();
            }
            route = RequestLedgerService.routeForCourier(level, settlement,
                courier);
        }
        if (route.request() == null
            || route.phase() == RequestLedgerService.RoutePhase.COMPLETE) {
            return courier.bag.isEmpty() ? null : "ledger_terminal_bag_owned";
        }
        return route.phase() == RequestLedgerService.RoutePhase.BLOCKED
            ? "blocked_ledger_" + route.blocker().id()
            : "ledger_" + route.phase().name().toLowerCase() + "_"
                + route.blocker().id();
    }

    /** Strict worker contact after the Courier's TARGET receipt exists. */
    private static boolean equipDelivered(ServerLevel level,
            Settlement settlement, Building workplace, SettlerEntity worker) {
        var requirement = EquipmentRequests.requirementFor(
            worker.getProfession());
        if (requirement == null) return false;
        if (requirement.serviceable(worker.getMainHandItem())) return true;
        BlockPos source = WorkplaceStorage.nearestMatchingContainer(level,
            workplace, requirement, worker.blockPosition());
        if (source == null) return false;
        standBeside(worker, source);
        return EquipmentRequests.equipFromWorkplaceAt(level, settlement,
            worker, source) && requirement.serviceable(worker.getMainHandItem());
    }

    /**
     * Carries a bounded non-tool supply through the Courier's physical bag.
     * The production output ledger intentionally accepts only workplace to
     * Warehouse routes, so armour and ammunition use the normal conservative
     * storage transactions rather than forging an invalid reverse request.
     */
    private static String deliverWarehouseOutput(ServerLevel level,
            Settlement settlement, Building warehouse, Building target,
            SettlerEntity courier, net.minecraft.world.item.Item item,
            int count) {
        if (warehouse == null || target == null || courier == null
            || count <= 0) return "invalid_authority";
        String priorLedgerRoute = completeOwnedLedgerRoute(level, settlement,
            courier);
        if (priorLedgerRoute != null) {
            return priorLedgerRoute.startsWith("blocked_")
                ? priorLedgerRoute.substring("blocked_".length())
                : "courier_busy_" + priorLedgerRoute;
        }
        while (countInBuilding(level, target, item) < count) {
            int bagSlot = firstItemSlot(courier.bag, item);
            if (bagSlot < 0) {
                if (!courier.bag.isEmpty()) {
                    return "courier_busy_non_supply_bag";
                }
                int missing = count - countInBuilding(level, target, item);
                WorkerStorageAuthority.Source source =
                    WorkerStorageAuthority.find(level, warehouse,
                        stack -> stack.is(item) && stack.getCount() >= 1,
                        courier.blockPosition());
                if (source == null) {
                    ItemStack remainder = WorkplaceStorage.insert(level,
                        warehouse, new ItemStack(item, missing));
                    if (!remainder.isEmpty()) return "warehouse_full";
                    source = WorkerStorageAuthority.find(level, warehouse,
                        stack -> stack.is(item) && stack.getCount() >= 1,
                        courier.blockPosition());
                }
                if (source == null) return "warehouse_source_missing";
                standBeside(courier, source.pos());
                WorkerStorageAuthority.Transfer transfer =
                    WorkerStorageAuthority.transferOneToBag(level, warehouse,
                        source, courier.bag, ItemStack::copy);
                if (transfer == null || !transfer.conserved()) {
                    return "warehouse_pickup_refused";
                }
                bagSlot = firstItemSlot(courier.bag, item);
            }
            BlockPos destination = WorkerStorageAuthority.nearestLoadedContainer(
                level, target, courier.blockPosition());
            if (bagSlot < 0 || destination == null) {
                return "route_container_missing";
            }
            standBeside(courier, destination);
            ItemStack carried = courier.bag.getItem(bagSlot).copy();
            WorkerStorageAuthority.Insert inserted = WorkerStorageAuthority
                .insertAt(level, target, destination, carried,
                    carried.getCount(), false);
            if (!inserted.conserved() || inserted.inserted() <= 0) {
                return "target_insert_refused";
            }
            courier.bag.setItem(bagSlot, inserted.remainder());
        }
        // The target receipt is item-local. Once it contains exactly the
        // requested supply, unrelated Courier cargo must not fail a replay.
        return countInBuilding(level, target, item) == count
            ? null : "physical_count_mismatch";
    }

    private static int firstItemSlot(Container container,
            net.minecraft.world.item.Item item) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).is(item)) return slot;
        }
        return -1;
    }

    private static boolean issueDefenderOrder(ServerLevel level,
            ServerPlayer actor, Settlement settlement, SettlerEntity defender,
            Building employer, boolean tower) {
        GuardAssignmentService.Validation existing = GuardAssignmentService
            .validate(level, settlement, defender, true);
        GuardOrder current = existing.order().orElse(null);
        GuardOrder.Mode expected = tower ? GuardOrder.Mode.TOWER_POST
            : GuardOrder.Mode.STAND_POST;
        // The ordinary first raid approaches the Warehouse's west entry. Put the
        // Guard on the standable approach cell rather than inside the Barracks:
        // a Guard's persisted eight-block leash must cover that real breach lane.
        // The Archer keeps the existing Watchtower post below.
        BlockPos post = tower ? highestStandablePost(level, employer, false)
            : warehouseApproachPost(level,
                building(settlement, BuildingType.WAREHOUSE));
        if (post == null) post = highestStandablePost(level, employer, false);
        if (post == null) return false;
        // A valid mode alone is insufficient for fixture replay: older fixture
        // saves may hold a Barracks Stand post that does not cover the authored
        // Warehouse approach. Preserve only the exact authored destination.
        if (current != null && current.mode() == expected
                && current.pos().filter(post::equals).isPresent()) {
            var journeyStep = tower
                ? JourneyIds.FJ_559B_SET_TOWER_POST
                : JourneyIds.FJ_550_SET_GUARD_ORDER;
            if (!settlement.journeyState.isCompleted(journeyStep)) {
                JourneyServerHooks.noteGuardAssignmentCommitted(actor,
                    settlement, defender, current, current.revision());
            }
            return GuardAssignmentService.validate(level, settlement, defender,
                false).valid();
        }
        standAt(defender, post);
        GuardOrder order = settlement.guardOrders.orderForMutation(
            settlement.id, defender.getUUID(), level.dimension().location())
            .orElse(null);
        if (order == null) return false;
        boolean changed = tower
            ? order.issueTower(post, Direction.NORTH,
                GuardOrder.DEFAULT_FACING_ARC, actor.getUUID(), employer.id,
                level.getGameTime())
            : order.issueStand(post, Direction.NORTH,
                GuardOrder.DEFAULT_LEASH_RADIUS, actor.getUUID(), employer.id,
                level.getGameTime());
        if (!changed) return false;
        SettlementSavedData.get(level).setDirty();
        GuardAssignmentService.Validation validation = GuardAssignmentService
            .validate(level, settlement, defender, false);
        JourneyServerHooks.noteGuardAssignmentCommitted(actor, settlement,
            defender, order, order.revision());
        return validation.valid() && order.mode() == expected;
    }

    /**
     * The normal fixture's Warehouse has an authored western entry lane.
     * It only selects a bounded, loaded, standable post; the existing setup
     * below remains the sole owner of defender placement and order mutation.
     */
    private static BlockPos warehouseApproachPost(ServerLevel level,
            Building warehouse) {
        if (warehouse == null || warehouse.bounds == null) return null;
        // The west exterior line has two walkable alley cells between the
        // Farmhouse wall and this Warehouse wall. -3 is the Farmhouse's solid
        // east wall in the normal fixture; -2 is the first supported open cell.
        BlockPos post = new BlockPos(warehouse.bounds.minX() - 2,
            warehouse.bounds.minY() + 1, warehouse.bounds.minZ() + 2);
        return level.isLoaded(post)
            && level.getBlockState(post).isAir()
            && level.getBlockState(post.above()).isAir()
            && level.getBlockState(post.below()).isFaceSturdy(level,
                post.below(), Direction.UP) ? post.immutable() : null;
    }

    private static BlockPos highestStandablePost(ServerLevel level,
            Building building, boolean highest) {
        if (building == null || building.bounds == null) return null;
        int start = highest ? building.bounds.maxY() : building.bounds.minY();
        int end = highest ? building.bounds.minY() : building.bounds.maxY();
        int step = highest ? -1 : 1;
        for (int y = start; highest ? y >= end : y <= end; y += step) {
            for (int x = building.bounds.minX(); x <= building.bounds.maxX(); x++) {
                for (int z = building.bounds.minZ(); z <= building.bounds.maxZ(); z++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (building.contains(pos) && level.isLoaded(pos)
                        && level.getBlockState(pos).isAir()
                        && level.getBlockState(pos.above()).isAir()
                        && level.getBlockState(pos.below()).isFaceSturdy(level,
                            pos.below(), Direction.UP)) return pos.immutable();
                }
            }
        }
        return null;
    }

    private static int firstEmptySlot(Container container) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (container.getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private static int firstMatchingSlot(Container container,
            com.hearthstead.settlement.equipment.EquipmentRequirement requirement) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            if (requirement.serviceable(container.getItem(slot))) return slot;
        }
        return -1;
    }

    private static int countInBuilding(ServerLevel level, Building building,
            net.minecraft.world.item.Item item) {
        int count = 0;
        for (BlockPos pos : WorkerStorageAuthority.loadedContainers(level,
                building)) {
            if (!(level.getBlockEntity(pos) instanceof Container container)) {
                continue;
            }
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                if (container.getItem(slot).is(item)) {
                    count += container.getItem(slot).getCount();
                }
            }
        }
        return count;
    }

    private static RequestRecord matchingRequest(RequestLedger ledger,
            Building source, Building target, SettlerEntity courier,
            net.minecraft.world.item.Item item, int count, boolean terminal) {
        if (ledger == null || ledger.quarantined()) return null;
        List<RequestRecord> rows = terminal
            ? ledger.terminalHistory() : ledger.active();
        return rows.stream()
            .filter(row -> row.type() == RequestType.OUTPUT_PICKUP)
            .filter(row -> row.sourceBuildingId().equals(source.id)
                && row.targetBuildingId().equals(target.id))
            .filter(row -> BuiltInRegistries.ITEM.getKey(item)
                    .equals(row.fingerprint().itemId())
                && row.fingerprint().count() == count)
            .filter(row -> !terminal
                || row.state() == RequestState.SATISFIED
                    && Objects.equals(row.courierId(), courier.getUUID())
                    && row.hasFullTransportTrace())
            .max(Comparator.comparingLong(RequestRecord::updatedAt))
            .orElse(null);
    }

    private static StoredSlot storedLogs(ServerLevel level, Building camp,
            int minimum) {
        for (BlockPos pos : WorkerStorageAuthority.loadedContainers(level,
                camp)) {
            Container container = container(level, pos);
            if (container == null) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.is(ItemTags.LOGS) && stack.getCount() >= minimum
                    && !WorkerStackProvenance.hasTransitMarker(stack)) {
                    return new StoredSlot(pos, slot, stack);
                }
            }
        }
        return null;
    }

    private static StoredSlot storedCrop(ServerLevel level,
            Building farmhouse) {
        for (BlockPos pos : WorkerStorageAuthority.loadedContainers(level,
                farmhouse)) {
            Container container = container(level, pos);
            if (container == null) continue;
            for (int slot = 0; slot < container.getContainerSize(); slot++) {
                ItemStack stack = container.getItem(slot);
                if (!stack.isEmpty() && isFarmHarvest(stack)
                    && !WorkerStackProvenance.hasTransitMarker(stack)) {
                    return new StoredSlot(pos, slot, stack);
                }
            }
        }
        return null;
    }

    private static boolean hasLumberReceipt(ServerLevel level,
            Settlement settlement, Building camp,
            net.minecraft.world.item.Item item, int minimum) {
        WorkerProvenanceSavedData data = WorkerProvenanceSavedData.existing(level);
        if (data == null || data.quarantined()) return false;
        var itemId = BuiltInRegistries.ITEM.getKey(item);
        int receipts = 0;
        for (WorkerProvenanceSavedData.ActionView action
                : data.actionsForSettlement(settlement.id)) {
            if (action.kind() != WorkerProvenanceSavedData.Kind.LUMBER_TREE
                || !action.zone().buildingId().equals(camp.id)) {
                continue;
            }
            for (WorkerProvenanceSavedData.DepositReceipt receipt
                    : data.receiptsFor(action.id())) {
                if (receipt.settlementId().equals(settlement.id)
                    && receipt.buildingId().equals(camp.id)
                    && receipt.itemId().equals(itemId)) {
                    receipts += receipt.count();
                    if (receipts >= minimum) return true;
                }
            }
        }
        return false;
    }

    private static void placePhysicalField(ServerLevel level,
            BlockPos minimum) {
        // Keep the fixture's real crop viable through the full day/night
        // cycle. This is an ordinary physical field light, not a crop-state
        // or clock shortcut; natural CropBlock random ticks still own every
        // age transition.
        BlockPos lightBase = minimum.offset(-1, 0, 1);
        level.setBlockAndUpdate(lightBase, Blocks.COBBLESTONE.defaultBlockState());
        level.setBlockAndUpdate(lightBase.above(), Blocks.TORCH.defaultBlockState());
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                BlockPos soil = minimum.offset(x, 0, z);
                BlockState state = x == 1 && z == 1
                    ? Blocks.WATER.defaultBlockState()
                    : Blocks.FARMLAND.defaultBlockState().setValue(
                        net.minecraft.world.level.block.FarmBlock.MOISTURE, 7);
                level.setBlockAndUpdate(soil, state);
                level.setBlockAndUpdate(soil.above(), Blocks.AIR.defaultBlockState());
                level.setBlockAndUpdate(soil.above(2), Blocks.AIR.defaultBlockState());
            }
        }
    }

    /** Returns null after one exact tagged seed became an age-zero crop. */
    private static String plantPhysicalWheat(ServerLevel level,
            Settlement settlement, Building farmhouse, SettlerEntity farmer,
            BlockPos cropPos) {
        WorkerProvenanceSavedData.ActionView active =
            WorkerProvenanceService.resumableFarmPlant(level, settlement,
                farmhouse, farmer);
        UUID actionId;
        if (active != null) {
            if (active.planned().size() != 1
                || !active.planned().getFirst().equals(cropPos)) {
                return "farmer_seed_action_targets_other_plot";
            }
            actionId = active.id();
        } else {
            WorkerStorageAuthority.Source existingSeed =
                WorkerStorageAuthority.find(level, farmhouse,
                    stack -> stack.is(Items.WHEAT_SEEDS),
                    farmer.blockPosition());
            if (existingSeed == null) {
                ItemStack remainder = WorkplaceStorage.insert(level, farmhouse,
                    new ItemStack(Items.WHEAT_SEEDS, 8));
                if (!remainder.isEmpty()) {
                    return "farmhouse_seed_storage_full";
                }
            }
            WorkerStorageAuthority.Source source = WorkerStorageAuthority.find(
                level, farmhouse, stack -> stack.is(Items.WHEAT_SEEDS),
                farmer.blockPosition());
            if (source == null) return "farmhouse_seed_source_missing";
            standBeside(farmer, source.pos());
            actionId = WorkerProvenanceService.supplyOneSeedAt(level,
                settlement, farmhouse, farmer, source.pos(),
                stack -> stack.is(Items.WHEAT_SEEDS), cropPos);
            if (actionId == null) return "farmer_seed_withdrawal_refused";
        }

        int seedSlot = farmSeedSlot(level, settlement, farmhouse, farmer,
            actionId);
        if (seedSlot < 0) return "farmer_tagged_seed_missing";
        standAt(farmer, cropPos);
        if (!WorkerProvenanceService.prepareFarmPlant(level, settlement,
                farmhouse, farmer, actionId, cropPos)) {
            return "farmer_plant_prepare_refused";
        }
        ItemStack consumed = farmer.bag.getItem(seedSlot).copy();
        farmer.bag.setItem(seedSlot, ItemStack.EMPTY);
        CropBlock wheat = (CropBlock) Blocks.WHEAT;
        boolean placed = level.setBlock(cropPos, wheat.getStateForAge(0),
            Block.UPDATE_ALL);
        boolean committed = placed && WorkerProvenanceService.commitFarmPlant(
            level, settlement, farmhouse, farmer, actionId, cropPos,
            BuiltInRegistries.BLOCK.getKey(Blocks.WHEAT));
        if (!committed) {
            if (placed && level.getBlockState(cropPos).is(Blocks.WHEAT)) {
                level.removeBlock(cropPos, false);
            }
            ItemStack remainder = farmer.bag.addItem(consumed);
            return remainder.isEmpty() ? "farmer_plant_commit_refused"
                : "farmer_plant_rollback_bag_full";
        }
        return null;
    }

    private static int farmSeedSlot(ServerLevel level, Settlement settlement,
            Building farmhouse, SettlerEntity farmer, UUID actionId) {
        for (int slot = 0; slot < farmer.bag.getContainerSize(); slot++) {
            if (WorkerProvenanceService.farmSeedAction(level, settlement,
                    farmhouse, farmer, farmer.bag.getItem(slot))
                .filter(actionId::equals).isPresent()) {
                return slot;
            }
        }
        return -1;
    }

    /** Returns null after every stamped physical drop reaches Farm storage. */
    private static String harvestAndStorePhysicalCrop(ServerLevel level,
            Settlement settlement, Building farmhouse, SettlerEntity farmer,
            BlockPos cropPos) {
        BlockState state = level.getBlockState(cropPos);
        if (!(state.getBlock() instanceof CropBlock crop)
            || !crop.isMaxAge(state)) {
            return "farm_crop_not_mature";
        }
        standAt(farmer, cropPos);
        UUID actionId = WorkerProvenanceService.beginFarmHarvest(level,
            settlement, farmhouse, farmer, cropPos);
        if (actionId == null) return "farmer_harvest_begin_refused";
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack raw : Block.getDrops(state, level, cropPos, null)) {
            if (raw.isEmpty()) continue;
            ItemStack stamped = raw.copy();
            if (!WorkerProvenanceService.stampOutput(level, settlement,
                    farmhouse, farmer, actionId, stamped, cropPos)) {
                return "farmer_harvest_stamp_refused";
            }
            drops.add(stamped);
        }
        if (drops.isEmpty()) return "farmer_harvest_no_drops";
        List<ItemStack> bagBefore = snapshot(farmer.bag);
        if (!WorkerStorageAuthority.storeAllInBag(farmer.bag, drops)) {
            return "farmer_harvest_bag_full";
        }
        if (!level.removeBlock(cropPos, false)) {
            restore(farmer.bag, bagBefore);
            return "farmer_harvest_crop_changed";
        }
        if (!WorkerProvenanceService.commitFarmHarvest(level, settlement,
                farmhouse, farmer, actionId, cropPos, drops)) {
            if (level.getBlockState(cropPos).isAir()) {
                level.setBlock(cropPos, state, Block.UPDATE_ALL);
            }
            restore(farmer.bag, bagBefore);
            return "farmer_harvest_commit_refused";
        }

        BlockPos storage = WorkerStorageAuthority.nearestLoadedContainer(level,
            farmhouse, farmer.blockPosition());
        if (storage == null) return "farmhouse_harvest_storage_missing";
        standBeside(farmer, storage);
        boolean cropReceipt = false;
        for (int slot = 0; slot < farmer.bag.getContainerSize(); slot++) {
            ItemStack stack = farmer.bag.getItem(slot);
            WorkerStackProvenance.Transit transit =
                WorkerStackProvenance.readTransit(stack).orElse(null);
            if (transit == null
                || transit.kind() != WorkerStackProvenance.TransitKind.FARM_CROP
                || !transit.actionId().equals(actionId)) {
                continue;
            }
            ItemStack offered = stack.copy();
            WorkerProvenanceService.DepositResult deposited =
                WorkerProvenanceService.depositOutput(level, settlement,
                    farmhouse, farmer, storage, offered);
            int inserted = deposited.inserted(offered.getCount());
            if (inserted <= 0 || deposited.receipt() == null) {
                return "farmer_harvest_deposit_refused";
            }
            farmer.bag.setItem(slot, deposited.remainder());
            if (isFarmHarvest(offered)) {
                cropReceipt |= DevelopmentQuests.noteFarmCropsStored(level,
                    settlement, farmhouse, farmer, offered, inserted);
            }
        }
        return cropReceipt ? null : "farmer_harvest_crop_receipt_missing";
    }

    private static boolean isFarmHarvest(ItemStack stack) {
        return stack.is(Items.WHEAT) || stack.is(Items.CARROT)
            || stack.is(Items.POTATO) || stack.is(Items.BEETROOT)
            || stack.is(Items.MELON_SLICE) || stack.is(Items.PUMPKIN)
            || stack.is(Items.SUGAR_CANE);
    }

    private static List<ItemStack> snapshot(Container container) {
        List<ItemStack> snapshot = new ArrayList<>(container.getContainerSize());
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            snapshot.add(container.getItem(slot).copy());
        }
        return snapshot;
    }

    private static void restore(Container container, List<ItemStack> snapshot) {
        for (int slot = 0; slot < snapshot.size(); slot++) {
            container.setItem(slot, snapshot.get(slot).copy());
        }
        container.setChanged();
    }

    private static Container container(ServerLevel level, BlockPos pos) {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        return blockEntity instanceof Container container ? container : null;
    }

    private static Building building(Settlement settlement, BuildingType type) {
        for (Building building : settlement.buildings) {
            if (building != null && building.type == type && building.valid) {
                return building;
            }
        }
        return null;
    }

    private static SettlerEntity worker(ServerLevel level,
            Settlement settlement, Building workplace, Profession profession) {
        if (workplace == null) return null;
        for (SettlerEntity member : SettlementManager.loadedMembers(level,
                settlement)) {
            if (member.isAlive() && member.getProfession() == profession
                && workplace.workers.contains(member.getUUID())
                && Employment.employerOf(settlement, member.getUUID())
                    == workplace) {
                return member;
            }
        }
        return null;
    }

    private static SettlerEntity unassignedFounder(ServerLevel level,
            Settlement settlement, boolean mayorAllowed) {
        return SettlementManager.loadedMembers(level, settlement).stream()
            .filter(SettlerEntity::isAlive)
            .filter(member -> member.getProfession() == Profession.NONE)
            .filter(member -> mayorAllowed
                || !member.getUUID().equals(settlement.mayorId))
            .filter(member -> Employment.employerOf(settlement,
                member.getUUID()) == null)
            .sorted(Comparator.comparing(member -> member.getUUID().toString()))
            .findFirst().orElse(null);
    }

    /** Returns a blocker id, or null after the physical emblem is consumed. */
    private static String purchaseAndHire(ServerLevel level, ServerPlayer actor,
            HearthBlockEntity hearth, Settlement settlement,
            SettlerEntity settler, Building workplace,
            Profession profession) {
        JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
        if (entry == null) return "emblem_not_in_catalog";
        int selectedBefore = actor.getInventory().selected;
        int empty = emptyHotbarSlot(actor);
        if (empty < 0) return "empty_hotbar_slot_required";
        for (DevelopmentNode.Cost cost : entry.costs()) {
            put(hearth, cost.item(), cost.count());
        }
        boolean instantBefore = actor.getAbilities().instabuild;
        try {
            actor.getInventory().selected = empty;
            actor.getAbilities().instabuild = false;
            actor.onUpdateAbilities();
            actor.setPos(settler.getX(), settler.getY(), settler.getZ());
            Development.EmblemPurchase purchase = Development.purchaseEmblem(
                level, settlement, hearth, profession,
                Development.revisionOf(level, settlement), actor);
            if (!purchase.applied() || purchase.deliveryId() == null) {
                return "emblem_purchase_" + purchase.result().name().toLowerCase();
            }
            PendingPlayerDeliveryLedger.DeliveryResult delivered =
                Development.deliverPending(level, settlement, actor,
                    purchase.deliveryId());
            if (delivered.outcome()
                    != PendingPlayerDeliveryLedger.Outcome.MAIN_HAND
                || !(actor.getMainHandItem().getItem() instanceof JobEmblemItem)) {
                return "emblem_delivery_" + delivered.outcome().name().toLowerCase();
            }
            ItemStack emblem = actor.getMainHandItem();
            ((JobEmblemItem) emblem.getItem()).interactLivingEntity(emblem,
                actor, settler, InteractionHand.MAIN_HAND);
            return workplace.workers.contains(settler.getUUID())
                    && settler.getProfession() == profession
                    && actor.getMainHandItem().isEmpty()
                ? null : "emblem_handoff_refused";
        } finally {
            actor.getAbilities().instabuild = instantBefore;
            actor.onUpdateAbilities();
            actor.getInventory().selected = selectedBefore;
        }
    }

    private static int emptyHotbarSlot(ServerPlayer actor) {
        for (int slot = 0; slot < 9; slot++) {
            if (actor.getInventory().getItem(slot).isEmpty()) return slot;
        }
        return -1;
    }

    private static boolean openSettlerInventory(ServerPlayer actor,
            SettlerEntity settler) {
        int selectedBefore = actor.getInventory().selected;
        int empty = emptyHotbarSlot(actor);
        if (empty < 0) return false;
        ItemStack offhand = actor.getOffhandItem().copy();
        boolean sneaking = actor.isShiftKeyDown();
        try {
            actor.closeContainer();
            actor.getInventory().selected = empty;
            actor.setItemInHand(InteractionHand.OFF_HAND, ItemStack.EMPTY);
            actor.setPos(settler.getX(), settler.getY(), settler.getZ());
            actor.setShiftKeyDown(true);
            settler.interact(actor, InteractionHand.MAIN_HAND);
            return actor.containerMenu instanceof SettlerInventoryMenu;
        } finally {
            actor.setShiftKeyDown(sneaking);
            actor.closeContainer();
            actor.setItemInHand(InteractionHand.OFF_HAND, offhand);
            actor.getInventory().selected = selectedBefore;
        }
    }

    private static boolean equipFromWorkplace(ServerLevel level,
            Settlement settlement, Building workplace, SettlerEntity worker,
            net.minecraft.world.item.Item tool) {
        var requirement = EquipmentRequests.requirementFor(
            worker.getProfession());
        if (requirement == null) return false;
        if (requirement.serviceable(worker.getMainHandItem())) return true;
        if (!WorkplaceStorage.hasMatching(level, workplace, requirement)
            && !WorkplaceStorage.insert(level, workplace,
                new ItemStack(tool)).isEmpty()) {
            return false;
        }
        BlockPos source = WorkplaceStorage.nearestMatchingContainer(level,
            workplace, requirement, worker.blockPosition());
        if (source == null) return false;
        standBeside(worker, source);
        return EquipmentRequests.equipFromWorkplaceAt(level, settlement,
            worker, source) && requirement.serviceable(worker.getMainHandItem());
    }

    private static void placeNaturalTree(ServerLevel level, BlockPos root) {
        BlockPos top = root.above();
        level.setBlockAndUpdate(root.below(), Blocks.DIRT.defaultBlockState());
        level.setBlockAndUpdate(root, Blocks.OAK_LOG.defaultBlockState());
        level.setBlockAndUpdate(top, Blocks.OAK_LOG.defaultBlockState());
        for (BlockPos leaf : List.of(top.north(), top.south(), top.east(),
                top.west())) {
            level.setBlockAndUpdate(leaf, Blocks.OAK_LEAVES.defaultBlockState());
        }
    }

    private static ItemStack performLumberWork(ServerLevel level,
            BlockPos origin, Settlement settlement, Building camp,
            SettlerEntity lumberer, BlockPos root) {
        WorkZone zone = camp.workZone().orElse(null);
        List<BlockPos> logs = List.of(root, root.above());
        if (zone == null) return ItemStack.EMPTY;
        standAt(lumberer, root);
        UUID action = WorkerProvenanceService.beginLumberTree(level, settlement,
            camp, lumberer, zone, root, logs);
        if (action == null) return ItemStack.EMPTY;
        GroundCollectionSession collection = new GroundCollectionSession(
            lumberer, stack -> stack.is(ItemTags.LOGS), logs.size());
        int carried = 0;
        for (BlockPos log : List.of(root.above(), root)) {
            standAt(lumberer, log);
            ItemStack output = new ItemStack(Items.OAK_LOG);
            if (!WorkerProvenanceService.prepareLumberLog(level, settlement,
                    camp, lumberer, action, log)
                || !WorkerProvenanceService.stampOutput(level, settlement,
                    camp, lumberer, action, output, log)) {
                return ItemStack.EMPTY;
            }
            UUID transfer = collection.queuePhysical(level, log, output);
            if (transfer == null) return ItemStack.EMPTY;
            level.setBlockAndUpdate(log, Blocks.AIR.defaultBlockState());
            if (!collection.materializeQueued(level, log, transfer)) {
                return ItemStack.EMPTY;
            }
            ItemEntity physical = level.getEntity(transfer)
                instanceof ItemEntity item ? item : null;
            if (physical == null || !physical.isAlive()
                || WorkerStackProvenance.readTransit(physical.getItem())
                    .filter(receipt -> receipt.actionId().equals(action))
                    .isEmpty()
                || !WorkerProvenanceService.completeLumberLog(level,
                    settlement, camp, lumberer, action, log,
                    physical.getItem())) {
                return ItemStack.EMPTY;
            }
            standAt(lumberer, log);
            collection.select(physical);
            if (!collection.hasClearPickupLine(level, physical)
                || collection.takeOneToOffhand(level, 4.0D)
                    != GroundCollectionSession.PickupResult.PICKED
                || collection.stowOne()
                    != GroundCollectionSession.StowResult.STOWED) {
                return ItemStack.EMPTY;
            }
            carried++;
        }
        BlockPos top = root.above();
        for (BlockPos leaf : List.of(top.north(), top.south(), top.east(),
                top.west())) {
            level.setBlockAndUpdate(leaf, Blocks.AIR.defaultBlockState());
        }
        if (!WorkerProvenanceService.commitLumberTree(level, settlement, camp,
                lumberer, action)) {
            return ItemStack.EMPTY;
        }
        BlockPos storage = origin.offset(23, 1, 6);
        standBeside(lumberer, storage);
        int deposited = 0;
        while (deposited < carried) {
            int slot = firstTaggedSlot(lumberer.bag, Items.OAK_LOG, action);
            if (slot < 0) return ItemStack.EMPTY;
            ItemStack carriedStack = lumberer.bag.getItem(slot);
            WorkerProvenanceService.DepositResult result =
                WorkerProvenanceService.depositOutput(level, settlement, camp,
                    lumberer, storage, carriedStack);
            lumberer.bag.setItem(slot, result.remainder());
            if (!result.remainder().isEmpty() || result.receipt() == null) {
                return ItemStack.EMPTY;
            }
            deposited++;
        }
        return new ItemStack(Items.OAK_LOG, deposited);
    }

    private static int firstTaggedSlot(Container container,
            net.minecraft.world.item.Item item, UUID action) {
        for (int slot = 0; slot < container.getContainerSize(); slot++) {
            ItemStack stack = container.getItem(slot);
            if (stack.is(item) && WorkerStackProvenance.readTransit(stack)
                    .filter(receipt -> receipt.actionId().equals(action))
                    .isPresent()) {
                return slot;
            }
        }
        return -1;
    }

    private static void standAt(SettlerEntity settler, BlockPos target) {
        settler.moveTo(target.getX() + 0.5D, target.getY(),
            target.getZ() + 0.5D, settler.getYRot(), settler.getXRot());
        settler.getNavigation().stop();
    }

    private static void standBeside(SettlerEntity settler, BlockPos target) {
        settler.moveTo(target.getX() + 1.5D, target.getY(),
            target.getZ() + 0.5D, settler.getYRot(), settler.getXRot());
        settler.getNavigation().stop();
    }

    private static void put(HearthBlockEntity hearth,
            net.minecraft.world.item.Item item, int count) {
        int present = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(item)) present += stack.getCount();
        }
        ItemStack remaining = new ItemStack(item, Math.max(0, count - present));
        for (int slot = 0; slot < hearth.getInventory().getSlots()
                && !remaining.isEmpty(); slot++) {
            ItemStack current = hearth.getInventory().getStackInSlot(slot);
            if (current.isEmpty()) {
                hearth.getInventory().setStackInSlot(slot, remaining.copy());
                remaining = ItemStack.EMPTY;
            } else if (ItemStack.isSameItemSameComponents(current, remaining)) {
                int moved = Math.min(remaining.getCount(),
                    current.getMaxStackSize() - current.getCount());
                current.grow(moved);
                remaining.shrink(moved);
            }
        }
    }

    private static boolean ensureAtHearth(HearthBlockEntity hearth,
            net.minecraft.world.item.Item item, int count) {
        put(hearth, item, count);
        int present = 0;
        for (int slot = 0; slot < hearth.getInventory().getSlots(); slot++) {
            ItemStack stack = hearth.getInventory().getStackInSlot(slot);
            if (stack.is(item)) present += stack.getCount();
        }
        return present >= count;
    }

    private static Development.Result purchase(ServerLevel level,
            ServerPlayer actor, HearthBlockEntity hearth,
            Settlement settlement, DevelopmentNode node) {
        if (Development.of(level, settlement).unlocked(node)) {
            return Development.Result.ALREADY_UNLOCKED;
        }
        for (DevelopmentNode.Cost cost : node.costs()) {
            ItemStack remaining = new ItemStack(cost.item(), cost.count());
            for (int slot = 0; slot < hearth.getInventory().getSlots()
                    && !remaining.isEmpty(); slot++) {
                ItemStack current = hearth.getInventory().getStackInSlot(slot);
                if (current.isEmpty()) {
                    hearth.getInventory().setStackInSlot(slot, remaining.copy());
                    remaining = ItemStack.EMPTY;
                } else if (ItemStack.isSameItemSameComponents(current, remaining)) {
                    int moved = Math.min(remaining.getCount(),
                        current.getMaxStackSize() - current.getCount());
                    current.grow(moved);
                    remaining.shrink(moved);
                }
            }
            if (!remaining.isEmpty()) return Development.Result.MATERIALS;
        }
        return Development.purchaseNode(level, settlement, hearth, node,
            Development.revisionOf(level, settlement), actor);
    }

    private static boolean fitLearnedPlan(ServerLevel level, ServerPlayer actor,
            Settlement settlement, BlockPos roomOrigin, int size,
            BuildingType type) {
        // These physical fixtures mirror the production journey rooms: the
        // plaque hangs one block from the west wall. Resolve by exact plaque
        // identity so a second House of the same type is never mistaken for
        // an idempotent replay of the first.
        BlockPos plaquePos = fixturePlaquePos(roomOrigin, size, type);
        List<Building> atExpectedPlaque = settlement.buildings.stream()
            .filter(registered -> registered != null
                && registered.type == type
                && plaquePos.equals(registered.plaquePos))
            .toList();
        if (!atExpectedPlaque.isEmpty()) {
            if (atExpectedPlaque.size() != 1) return false;
            Building already = atExpectedPlaque.getFirst();
            return level.getBlockEntity(plaquePos)
                    instanceof PlaqueBlockEntity existing
                && exactLinkedPlaque(level, settlement, existing, already,
                    type);
        }
        BlockState current = level.getBlockState(plaquePos);
        if (!current.isAir() && !current.is(ModBlocks.PLAQUE.get())) {
            return false;
        }
        if (current.is(ModBlocks.PLAQUE.get())) {
            if (!(level.getBlockEntity(plaquePos)
                    instanceof PlaqueBlockEntity occupied)
                || occupied.state() != PlaqueState.EMPTY) {
                return false;
            }
        } else {
            level.setBlockAndUpdate(plaquePos, ModBlocks.PLAQUE.get()
                .defaultBlockState().setValue(PlaqueBlock.FACING,
                    fixturePlaqueFacing(type)));
        }
        ensurePlaqueDoorstep(level, plaquePos);
        if (!(level.getBlockEntity(plaquePos) instanceof PlaqueBlockEntity plaque)) {
            return false;
        }
        ItemStack plan = PlaqueItemData.stamped(
            new ItemStack(ModItems.BUILD_PLAN.get()), type);
        int selectedBefore = actor.getInventory().selected;
        int empty = emptyHotbarSlot(actor);
        if (empty < 0) return false;
        boolean instantBefore = actor.getAbilities().instabuild;
        try {
            actor.getInventory().selected = empty;
            actor.setItemInHand(InteractionHand.MAIN_HAND, plan);
            actor.getAbilities().instabuild = false;
            actor.onUpdateAbilities();
            actor.setPos(plaquePos.getX() + 0.5D, plaquePos.getY() + 0.5D,
                plaquePos.getZ() + 0.5D);
            level.getBlockState(plaquePos).useItemOn(actor.getMainHandItem(), level,
                actor, InteractionHand.MAIN_HAND, new BlockHitResult(
                    Vec3.atCenterOf(plaquePos), Direction.NORTH, plaquePos, false));
            Building linked = plaque.building(level);
            return actor.getMainHandItem().isEmpty()
                && exactLinkedPlaque(level, settlement, plaque, linked, type);
        } finally {
            actor.getAbilities().instabuild = instantBefore;
            actor.onUpdateAbilities();
            actor.getInventory().selected = selectedBefore;
        }
    }

    /** The compact tower uses its free south facade; its north face abuts
     * the Warehouse so the ordinary first raid reaches the same defended lane. */
    private static BlockPos fixturePlaquePos(BlockPos roomOrigin, int size,
                                              BuildingType type) {
        return type == BuildingType.WATCHTOWER
            ? roomOrigin.offset(1, 2, size) : roomOrigin.offset(1, 2, -1);
    }

    private static Direction fixturePlaqueFacing(BuildingType type) {
        return type == BuildingType.WATCHTOWER ? Direction.SOUTH : Direction.NORTH;
    }

    /**
     * A fixture plaque is a real wall-hung block, so its facade is resolved
     * from the placed block state's actual facing rather than an assumption
     * made while the room shell was built. The target cell is deliberately
     * made a conventional two-block-high doorstep with solid footing, exactly
     * matching {@link SettlementManager#travelerTavernApproach}'s production
     * walkability rule. This creates fixture terrain only; it never changes
     * the immutable plaque anchor used by recruitment/admission.
     */
    private static void ensurePlaqueDoorstep(ServerLevel level,
                                             BlockPos plaquePos) {
        BlockState plaque = level.getBlockState(plaquePos);
        if (!(plaque.getBlock() instanceof PlaqueBlock)
            || !plaque.hasProperty(PlaqueBlock.FACING)) {
            return;
        }
        BlockPos feet = plaquePos.relative(plaque.getValue(PlaqueBlock.FACING))
            .below();
        level.setBlockAndUpdate(feet.below(),
            Blocks.SPRUCE_PLANKS.defaultBlockState());
        level.setBlockAndUpdate(feet, Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(feet.above(), Blocks.AIR.defaultBlockState());
    }

    private static boolean exactLinkedPlaque(ServerLevel level,
            Settlement settlement, PlaqueBlockEntity plaque, Building linked,
            BuildingType type) {
        return plaque != null && linked != null
            && plaque.state() == PlaqueState.LINKED_VALID
            && plaque.settlementFor(level) == settlement
            && plaque.building(level) == linked
            && Objects.equals(plaque.buildingId(), linked.id)
            && linked.valid && linked.type == type
            && linked.plaquePos.equals(plaque.getBlockPos())
            && settlement.buildings.stream().anyMatch(registered ->
                registered == linked && registered.id.equals(linked.id));
    }

    private static Result observe(ServerLevel level, BlockPos origin,
                                  Settlement settlement, Stage reached,
                                  String blocker, String prefix) {
        FirstRaidReadinessService.Report domain =
            FirstRaidReadinessService.assessDomain(level, settlement);
        FirstRaidReadinessService.Report execution =
            FirstRaidReadinessService.assessExecution(level, settlement);
        int beds = countBedHeads(level, origin);
        int members = new LinkedHashSet<>(SettlementManager.loadedMembers(level,
            settlement).stream().filter(entity -> entity.isAlive())
            .map(entity -> entity.getUUID()).toList()).size();
        Stage stage = domain.ready()
            ? readinessStage(true, execution.ready()) : reached;
        String detail = prefix + ";" + (domain.ready() ? execution.ready()
            ? "ready" : "execution_blocked:" + execution.blockers()
            : "domain_blocked:" + domain.blockers());
        logWorkerTransitDiagnostic(level, settlement, domain, execution);
        return new Result(stage, origin, settlement, beds, members, domain,
            execution, blocker == null ? "none" : blocker, detail);
    }

    private static final int MAX_WORKER_TRANSIT_DIAGNOSTIC_ROWS = 8;
    private static final int MAX_WORKER_TRANSIT_DIAGNOSTIC_RESIDENTS = 100;

    /**
     * Emits a bounded, read-only explanation only after the existing raid
     * readiness projection has already reported an unresolved worker transit.
     */
    private static void logWorkerTransitDiagnostic(ServerLevel level,
            Settlement settlement, FirstRaidReadinessService.Report domain,
            FirstRaidReadinessService.Report execution) {
        if (level == null || settlement == null || level.getServer() == null
            || !level.getServer().isSameThread()
            || !hasWorkerTransitBlocker(domain, execution)) {
            return;
        }
        WorkerProvenanceSavedData persisted =
            WorkerProvenanceSavedData.existing(level);
        if (persisted == null || persisted.quarantined()
            || !persisted.readableIn(level.dimension().location())) {
            return;
        }

        int remainders = 0;
        for (WorkerProvenanceSavedData.ActionView action
                : persisted.actionsForSettlement(settlement.id)) {
            if (remainders >= MAX_WORKER_TRANSIT_DIAGNOSTIC_ROWS) {
                break;
            }
            if (!action.workTerminal()
                && action.phase() != WorkerProvenanceSavedData.Phase.RETIRED) {
                continue;
            }
            for (var row : action.produced().entrySet()) {
                int produced = row.getValue();
                int deposited = action.deposited().getOrDefault(row.getKey(), 0);
                int remaining = action.remaining(row.getKey());
                if (remaining <= 0) {
                    continue;
                }
                Hearthstead.LOGGER.info("HSQA_WORKER_TRANSIT event=persisted_remainder"
                        + " settlement={} action={} worker={} kind={} item={}"
                        + " produced={} deposited={} remaining={}",
                    settlement.id, action.id(), action.workerId(), action.kind(),
                    row.getKey(), produced, deposited, remaining);
                if (++remainders >= MAX_WORKER_TRANSIT_DIAGNOSTIC_ROWS) {
                    break;
                }
            }
        }

        int marked = 0;
        int residents = 0;
        for (Settlement.SettlerRecord record : settlement.settlers) {
            if (++residents > MAX_WORKER_TRANSIT_DIAGNOSTIC_RESIDENTS
                || marked >= MAX_WORKER_TRANSIT_DIAGNOSTIC_ROWS) {
                break;
            }
            if (record == null || record.entityId == null) {
                continue;
            }
            Entity entity = level.getEntity(record.entityId);
            if (!(entity instanceof SettlerEntity settler)
                || !settler.isAlive()
                || settler.isTraveler()
                || !settlement.id.equals(settler.getSettlementId())
                || !Objects.equals(settlement.center, settler.getHearthPos())) {
                continue;
            }
            for (int slot = 0; slot < settler.bag.getContainerSize()
                    && marked < MAX_WORKER_TRANSIT_DIAGNOSTIC_ROWS; slot++) {
                if (logWorkerTransitStack(settlement, settler, "bag:" + slot,
                        settler.bag.getItem(slot))) {
                    marked++;
                }
            }
            if (marked < MAX_WORKER_TRANSIT_DIAGNOSTIC_ROWS
                && logWorkerTransitStack(settlement, settler, "main_hand",
                    settler.getMainHandItem())) {
                marked++;
            }
            if (marked < MAX_WORKER_TRANSIT_DIAGNOSTIC_ROWS
                && logWorkerTransitStack(settlement, settler, "off_hand",
                    settler.getOffhandItem())) {
                marked++;
            }
        }
    }

    private static boolean hasWorkerTransitBlocker(
            FirstRaidReadinessService.Report domain,
            FirstRaidReadinessService.Report execution) {
        return domain != null && domain.blockers().contains(
            FirstRaidReadinessService.Blocker.WORKER_TRANSIT_UNRESOLVED)
            || execution != null && execution.blockers().contains(
                FirstRaidReadinessService.Blocker.WORKER_TRANSIT_UNRESOLVED);
    }

    private static boolean logWorkerTransitStack(Settlement settlement,
            SettlerEntity settler, String location, ItemStack stack) {
        if (!WorkerStackProvenance.hasTransitMarker(stack)) {
            return false;
        }
        WorkerStackProvenance.Transit transit =
            WorkerStackProvenance.readTransit(stack).orElse(null);
        Hearthstead.LOGGER.info("HSQA_WORKER_TRANSIT event=marked_stack"
                + " settlement={} carrier={} location={} item={} count={}"
                + " action={} kind={} worker={}",
            settlement.id, settler.getUUID(), location,
            BuiltInRegistries.ITEM.getKey(stack.getItem()), stack.getCount(),
            transit == null ? "malformed" : transit.actionId(),
            transit == null ? "malformed" : transit.kind(),
            transit == null ? "malformed" : transit.workerId());
        return true;
    }
    static Stage readinessStage(boolean domainReady, boolean executionReady) {
        if (!domainReady) return Stage.FOUNDED_PHYSICAL_SHELLS;
        return executionReady ? Stage.READY_BEFORE_FIRST_RAID : Stage.DOMAIN_READY;
    }

    private enum Shell { HOUSE_ONE, HOUSE_TWO, LUMBER, FARM, WAREHOUSE,
        TAVERN, BARRACKS, WATCHTOWER }

    static final int STAGING_RADIUS = 64;
    private static final int GROUNDED_SEARCH_RADIUS = 128;
    private static final int GROUNDED_SEARCH_STEP = 16;
    /** Only the compact 32x32 compound may be graded across four support levels. */
    private static final int GROUNDED_COMPOUND_MAX_HEIGHT_DELTA = 4;
    /** Six blocks remain required where the authored compound may build roofs. */
    private static final int GROUNDED_CLEARANCE_HEIGHT = 6;
    /** Settlers are 1.95 blocks tall; exterior lanes and natural aprons only walk. */
    private static final int GROUNDED_WALK_CLEARANCE_HEIGHT = 2;
    /** Bound a natural-tree proof so a fixture survey cannot become a world scan. */
    private static final int GROUNDED_TREE_DESCENT_LIMIT = 24;
    private static final int GROUNDED_TREE_MIN_LEAVES = 4;
    private static final int[][] GROUNDED_WALK_STEPS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
    /** The tower stays physically separate from the Warehouse but close enough
     * for its ordinary KORN approach to enter the existing eight-block post ring. */
    private static final BlockPos WATCHTOWER_OFFSET = new BlockPos(13, 0, 20);

    private static boolean clearSite(ServerLevel level, BlockPos origin) {
        // Ordinary travelers start outside the 48-block settlement at radius
        // 56 (radius + 8). Validate the entire 64-block staging footprint
        // before placing its continuous floor and existing visitor routes.
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-STAGING_RADIUS, 0, -STAGING_RADIUS),
                origin.offset(STAGING_RADIUS, 9, STAGING_RADIUS))) {
            if (!level.isLoaded(pos) || (!level.getBlockState(pos).isAir()
                    && !level.getBlockState(pos).canBeReplaced())) {
                return false;
            }
        }
        return true;
    }

    private enum GroundedRejection {
        WORLD_BORDER("world_border"),
        BUILD_HEIGHT("build_height"),
        FLUID("fluid"),
        SUPPORT("support"),
        CLEARANCE("clearance"),
        SPAN("span"),
        ADJACENCY("adjacency"),
        APRON("apron"),
        COMMON_ROOF("common_roof"),
        EMPTY("empty");

        private final String key;

        GroundedRejection(String key) {
            this.key = key;
        }
    }

    private record GroundedVegetation(BlockPos pos, BlockState state) {
    }

    /** Natural feet and the top of a proven same-column trunk, if any. */
    private record GroundedColumn(int feetY, int trunkTopY) {
        private boolean naturalTree() {
            return trunkTopY != Integer.MIN_VALUE;
        }
    }

    private record GroundedColumnResult(GroundedColumn column,
                                        GroundedRejection rejection) {
        private static GroundedColumnResult accepted(int feetY) {
            return new GroundedColumnResult(new GroundedColumn(feetY,
                Integer.MIN_VALUE), null);
        }

        private static GroundedColumnResult naturalTree(int feetY,
                                                         int trunkTopY) {
            return new GroundedColumnResult(new GroundedColumn(feetY,
                trunkTopY), null);
        }

        private static GroundedColumnResult rejected(GroundedRejection rejection) {
            return new GroundedColumnResult(null, rejection);
        }
    }

    private record GroundedSurvey(BlockPos origin, int[][] exteriorFeet,
                                  List<GroundedVegetation> vegetation,
                                  GroundedRejection rejection) {
        private static GroundedSurvey accepted(BlockPos origin, int[][] exteriorFeet,
                                               List<GroundedVegetation> vegetation) {
            return new GroundedSurvey(origin, exteriorFeet, List.copyOf(vegetation),
                null);
        }

        private static GroundedSurvey rejected(GroundedRejection rejection) {
            return new GroundedSurvey(null, null, List.of(), rejection);
        }
    }

    /**
     * The 16-block lattice is intentionally cheap first. Only its first four
     * route-shape near misses receive the bounded local pass below: at most
     * 289 coarse plus 4 * 64 refinement probes.
     */
    private static final int GROUNDED_REFINEMENT_NEAR_MISS_LIMIT = 4;
    private static final int GROUNDED_REFINEMENT_RADIUS = 7;
    private static final int GROUNDED_REFINEMENT_STEP = 2;

    /** Compact, first-cause counts for one bounded no-write terrain scan. */
    private record GroundedSiteSearch(GroundedSurvey accepted, int[] rejections,
                                      int coarseCandidates, int refinementCandidates) {
        private BlockPos origin() {
            return accepted == null ? null : accepted.origin();
        }

        private int[][] exteriorFeet() {
            return accepted == null ? null : accepted.exteriorFeet();
        }

        private List<GroundedVegetation> vegetation() {
            return accepted == null ? List.of() : accepted.vegetation();
        }

        private String detail() {
            int candidates = coarseCandidates + refinementCandidates;
            StringBuilder detail = new StringBuilder("preflight_candidates=")
                .append(candidates)
                .append(";coarse_candidates=").append(coarseCandidates)
                .append(";refinement_candidates=").append(refinementCandidates);
            if (accepted != null) {
                BlockPos acceptedOrigin = accepted.origin();
                detail.append(";accepted_site=")
                    .append(acceptedOrigin.getX()).append(',')
                    .append(acceptedOrigin.getY()).append(',')
                    .append(acceptedOrigin.getZ());
            }
            for (GroundedRejection rejection : GroundedRejection.values()) {
                int count = rejections[rejection.ordinal()];
                if (count > 0) detail.append(';').append(rejection.key).append('=').append(count);
            }
            return detail.toString();
        }
    }

    /**
     * A bounded, read-only normal-terrain survey. It covers only the compact
     * compound and the four existing approach lanes; the real raid spawn
     * annulus remains natural terrain for RaidDirector to validate.
     *
     * <p>After all 289 coarse lattice points fail, refine only the first four
     * {@link GroundedRejection#ADJACENCY} or {@link GroundedRejection#APRON}
     * near misses. Those are the only first causes that say a small origin
     * translation can change the route geometry; support, fluid, span and
     * clearance failures remain rejected without extra probing.
     */
    private static GroundedSiteSearch findGroundedSite(ServerLevel level, BlockPos hint) {
        int[] rejections = new int[GroundedRejection.values().length];
        List<BlockPos> refinementSeeds = new ArrayList<>(
            GROUNDED_REFINEMENT_NEAR_MISS_LIMIT);
        LinkedHashSet<BlockPos> considered = new LinkedHashSet<>();
        int coarseCandidates = 0;
        int refinementCandidates = 0;
        for (int radius = 0; radius <= GROUNDED_SEARCH_RADIUS;
                radius += GROUNDED_SEARCH_STEP) {
            if (radius == 0) {
                coarseCandidates++;
                GroundedSurvey found = considerGroundedSite(level, hint, rejections,
                    refinementSeeds, considered);
                if (found.origin() != null) {
                    return new GroundedSiteSearch(found, rejections,
                        coarseCandidates, refinementCandidates);
                }
                continue;
            }
            for (int x = -radius; x <= radius; x += GROUNDED_SEARCH_STEP) {
                coarseCandidates++;
                GroundedSurvey north = considerGroundedSite(level,
                    hint.offset(x, 0, -radius), rejections, refinementSeeds, considered);
                if (north.origin() != null) {
                    return new GroundedSiteSearch(north, rejections,
                        coarseCandidates, refinementCandidates);
                }
                coarseCandidates++;
                GroundedSurvey south = considerGroundedSite(level,
                    hint.offset(x, 0, radius), rejections, refinementSeeds, considered);
                if (south.origin() != null) {
                    return new GroundedSiteSearch(south, rejections,
                        coarseCandidates, refinementCandidates);
                }
            }
            for (int z = -radius + GROUNDED_SEARCH_STEP; z < radius;
                    z += GROUNDED_SEARCH_STEP) {
                coarseCandidates++;
                GroundedSurvey west = considerGroundedSite(level,
                    hint.offset(-radius, 0, z), rejections, refinementSeeds, considered);
                if (west.origin() != null) {
                    return new GroundedSiteSearch(west, rejections,
                        coarseCandidates, refinementCandidates);
                }
                coarseCandidates++;
                GroundedSurvey east = considerGroundedSite(level,
                    hint.offset(radius, 0, z), rejections, refinementSeeds, considered);
                if (east.origin() != null) {
                    return new GroundedSiteSearch(east, rejections,
                        coarseCandidates, refinementCandidates);
                }
            }
        }
        for (BlockPos seed : refinementSeeds) {
            for (int x = -GROUNDED_REFINEMENT_RADIUS; x <= GROUNDED_REFINEMENT_RADIUS;
                    x += GROUNDED_REFINEMENT_STEP) {
                for (int z = -GROUNDED_REFINEMENT_RADIUS; z <= GROUNDED_REFINEMENT_RADIUS;
                        z += GROUNDED_REFINEMENT_STEP) {
                    BlockPos candidate = seed.offset(x, 0, z);
                    if (!withinGroundedSearchBounds(hint, candidate)
                            || considered.contains(candidate)) {
                        continue;
                    }
                    refinementCandidates++;
                    GroundedSurvey found = considerGroundedSite(level, candidate,
                        rejections, null, considered);
                    if (found.origin() != null) {
                        return new GroundedSiteSearch(found, rejections,
                            coarseCandidates, refinementCandidates);
                    }
                }
            }
        }
        return new GroundedSiteSearch(null, rejections,
            coarseCandidates, refinementCandidates);
    }

    private static boolean withinGroundedSearchBounds(BlockPos hint, BlockPos candidate) {
        return Math.abs(candidate.getX() - hint.getX()) <= GROUNDED_SEARCH_RADIUS
            && Math.abs(candidate.getZ() - hint.getZ()) <= GROUNDED_SEARCH_RADIUS;
    }

    private static GroundedSurvey considerGroundedSite(ServerLevel level, BlockPos hint,
            int[] rejections, List<BlockPos> refinementSeeds,
            LinkedHashSet<BlockPos> considered) {
        considered.add(hint.immutable());
        GroundedSurvey survey = surveyedGroundedSiteAt(level, hint);
        if (survey.origin() != null) return survey;
        GroundedRejection rejection = survey.rejection();
        rejections[rejection.ordinal()]++;
        if (refinementSeeds != null && (rejection == GroundedRejection.ADJACENCY
                || rejection == GroundedRejection.APRON)
                && refinementSeeds.size() < GROUNDED_REFINEMENT_NEAR_MISS_LIMIT) {
            refinementSeeds.add(hint.immutable());
        }
        return survey;
    }

    /** Existing package-visible read-only probe retained for the terrain GameTest. */
    static BlockPos groundedSiteAt(ServerLevel level, BlockPos hint) {
        return surveyedGroundedSiteAt(level, hint).origin();
    }

    /** No world write occurs before every authored foundation/path cell passes. */
    private static GroundedSurvey surveyedGroundedSiteAt(ServerLevel level, BlockPos hint) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        int[][] heights = new int[99][99];
        GroundedColumn[][] columns = new GroundedColumn[99][99];
        for (int x = -49; x <= 49; x++) for (int z = -49; z <= 49; z++) {
            if (!groundedSurveyCell(x, z)) continue;
            int worldX = hint.getX() + x;
            int worldZ = hint.getZ() + z;
            BlockPos probe = new BlockPos(worldX, hint.getY(), worldZ);
            if (!level.getWorldBorder().isWithinBounds(probe)) {
                return GroundedSurvey.rejected(GroundedRejection.WORLD_BORDER);
            }
            level.getChunkAt(probe);
            int heightmapFeet = level.getHeight(
                net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                worldX, worldZ);
            if (heightmapFeet <= level.getMinBuildHeight()) {
                return GroundedSurvey.rejected(GroundedRejection.BUILD_HEIGHT);
            }
            GroundedColumnResult result = surveyGroundedColumn(level, worldX,
                worldZ, heightmapFeet, groundedCompoundCell(x, z));
            if (result.column() == null) {
                return GroundedSurvey.rejected(result.rejection());
            }
            GroundedColumn column = result.column();
            heights[x + 49][z + 49] = column.feetY();
            columns[x + 49][z + 49] = column;
            if (groundedCompoundCell(x, z)) {
                minY = Math.min(minY, column.feetY());
                maxY = Math.max(maxY, column.feetY());
                if (maxY - minY > GROUNDED_COMPOUND_MAX_HEIGHT_DELTA) {
                    return GroundedSurvey.rejected(GroundedRejection.SPAN);
                }
            }
        }
        if (minY == Integer.MAX_VALUE) return GroundedSurvey.rejected(GroundedRejection.EMPTY);
        if (maxY + GROUNDED_CLEARANCE_HEIGHT - 1 >= level.getMaxBuildHeight()) {
            return GroundedSurvey.rejected(GroundedRejection.BUILD_HEIGHT);
        }
        for (int entrance = 0; entrance < 4; entrance++) {
            GroundedRejection routeFailure = groundedEntranceRouteFailure(heights, maxY, entrance);
            if (routeFailure != null) return GroundedSurvey.rejected(routeFailure);
        }
        List<GroundedVegetation> vegetation = new ArrayList<>();
        for (int x = -49; x <= 49; x++) for (int z = -49; z <= 49; z++) {
            if (!groundedSurveyCell(x, z)) continue;
            GroundedColumn column = columns[x + 49][z + 49];
            boolean authored = authoredGroundCell(x, z);
            int clearanceTop = groundedCompoundCell(x, z)
                ? maxY + GROUNDED_CLEARANCE_HEIGHT - 1
                : column.feetY() + GROUNDED_WALK_CLEARANCE_HEIGHT - 1;
            GroundedRejection rejection = validateGroundedClearance(level,
                hint.getX() + x, hint.getZ() + z, column, clearanceTop,
                maxY, groundedCompoundCell(x, z), authored, vegetation);
            if (rejection != null) return GroundedSurvey.rejected(rejection);
        }
        // Heightmap Y is feet above proven natural soil. The compact compound
        // grades to maxY - 1; every exterior lane keeps its captured soil plane.
        return GroundedSurvey.accepted(new BlockPos(hint.getX(), maxY - 1,
            hint.getZ()), snapshotExteriorLaneFeet(heights), vegetation);
    }

    /**
     * Descend only through air, replaceables and a proven natural tree. A log
     * is never a support plane: it must lead to full natural soil and a bounded
     * non-persistent-leaf canopy proof before this survey can clear it.
     */
    private static GroundedColumnResult surveyGroundedColumn(ServerLevel level,
            int x, int z, int heightmapFeet, boolean mayClearVegetation) {
        BlockPos topSupport = new BlockPos(x, heightmapFeet - 1, z);
        BlockState topState = level.getBlockState(topSupport);
        if (!level.getFluidState(topSupport).isEmpty()) {
            return GroundedColumnResult.rejected(GroundedRejection.FLUID);
        }
        if (level.getBlockEntity(topSupport) != null
                || !topState.isCollisionShapeFullBlock(level, topSupport)) {
            return GroundedColumnResult.rejected(GroundedRejection.SUPPORT);
        }
        if (topState.is(BlockTags.DIRT)) {
            return GroundedColumnResult.accepted(heightmapFeet);
        }
        if (!mayClearVegetation || !topState.is(BlockTags.LOGS)
                || !isGroundedNaturalTree(level, topSupport)) {
            return GroundedColumnResult.rejected(GroundedRejection.SUPPORT);
        }
        int minimumY = Math.max(level.getMinBuildHeight(),
            heightmapFeet - GROUNDED_TREE_DESCENT_LIMIT);
        for (int y = heightmapFeet - 1; y >= minimumY; y--) {
            BlockPos current = new BlockPos(x, y, z);
            BlockState state = level.getBlockState(current);
            if (!level.getFluidState(current).isEmpty()) {
                return GroundedColumnResult.rejected(GroundedRejection.FLUID);
            }
            if (level.getBlockEntity(current) != null) {
                return GroundedColumnResult.rejected(GroundedRejection.SUPPORT);
            }
            if (state.is(BlockTags.DIRT)) {
                if (!state.isCollisionShapeFullBlock(level, current)) {
                    return GroundedColumnResult.rejected(GroundedRejection.SUPPORT);
                }
                return GroundedColumnResult.naturalTree(y + 1,
                    heightmapFeet - 1);
            }
            if (state.is(BlockTags.LOGS) || isGroundedNaturalLeaf(state)
                    || state.isAir() || state.canBeReplaced()) {
                continue;
            }
            return GroundedColumnResult.rejected(GroundedRejection.SUPPORT);
        }
        return GroundedColumnResult.rejected(GroundedRejection.SUPPORT);
    }

    /**
     * A fail-closed natural-tree proof accepts only a straight, contiguous
     * trunk in this exact x/z column. A side log is a possible player facade
     * or appended column, so it rejects the site instead of being cleared.
     */
    private static boolean isGroundedNaturalTree(ServerLevel level, BlockPos firstLog) {
        List<BlockPos> trunk = new ArrayList<>();
        BlockPos first = firstLog.immutable();
        boolean rooted = false;
        for (int depth = 0; depth < GROUNDED_TREE_DESCENT_LIMIT; depth++) {
            BlockPos current = first.below(depth);
            BlockState state = level.getBlockState(current);
            if (state.is(BlockTags.LOGS)) {
                trunk.add(current);
                continue;
            }
            if (!state.is(BlockTags.DIRT)
                    || !state.isCollisionShapeFullBlock(level, current)
                    || level.getBlockEntity(current) != null
                    || !level.getFluidState(current).isEmpty()) {
                return false;
            }
            rooted = true;
            break;
        }
        if (!rooted) return false;
        int leaves = 0;
        for (BlockPos trunkLog : trunk) {
            for (int dx = -1; dx <= 1; dx++) for (int dy = -1; dy <= 1; dy++)
                    for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dy == 0 && dz == 0) continue;
                BlockPos next = trunkLog.offset(dx, dy, dz);
                BlockState state = level.getBlockState(next);
                if (state.is(BlockTags.LOGS)
                        && (next.getX() != first.getX() || next.getZ() != first.getZ())) {
                    return false;
                }
                // Real canopy leaves must reach the measured top log; leaves
                // only below it cannot certify a player-appended top column.
                if (next.getY() >= first.getY() && isGroundedNaturalLeaf(state)) {
                    leaves++;
                }
            }
        }
        return leaves >= GROUNDED_TREE_MIN_LEAVES;
    }

    private static boolean isGroundedNaturalLeaf(BlockState state) {
        return state.getBlock() instanceof LeavesBlock
            && !state.getValue(LeavesBlock.PERSISTENT);
    }

    /** Validate and snapshot the only natural foliage the later author may clear. */
    private static GroundedRejection validateGroundedClearance(ServerLevel level,
            int worldX, int worldZ, GroundedColumn column, int clearanceTop,
            int commonRoofFeet, boolean compound, boolean mayClearVegetation,
            List<GroundedVegetation> vegetation) {
        if (column.naturalTree() && (!compound || column.trunkTopY() > clearanceTop)) {
            return compound ? GroundedRejection.COMMON_ROOF
                : GroundedRejection.CLEARANCE;
        }
        for (int y = column.feetY(); y <= clearanceTop; y++) {
            BlockPos pos = new BlockPos(worldX, y, worldZ);
            BlockState state = level.getBlockState(pos);
            if (!level.getFluidState(pos).isEmpty()) return GroundedRejection.FLUID;
            if (level.getBlockEntity(pos) != null) return GroundedRejection.CLEARANCE;
            if (state.is(BlockTags.LEAVES)) {
                if (mayClearVegetation && isGroundedNaturalLeaf(state)) {
                    vegetation.add(new GroundedVegetation(pos.immutable(), state));
                    continue;
                }
                return clearanceTop >= commonRoofFeet && y >= commonRoofFeet
                    ? GroundedRejection.COMMON_ROOF : GroundedRejection.CLEARANCE;
            }
            if (state.isAir() || state.canBeReplaced()) continue;
            if (mayClearVegetation && state.is(BlockTags.LOGS)
                    && column.naturalTree()) {
                vegetation.add(new GroundedVegetation(pos.immutable(), state));
                continue;
            }
            return clearanceTop >= commonRoofFeet && y >= commonRoofFeet
                ? GroundedRejection.COMMON_ROOF : GroundedRejection.CLEARANCE;
        }
        return null;
    }

    private static boolean groundedCompoundCell(int x, int z) {
        return x >= 0 && x <= 31 && z >= 0 && z <= 31;
    }

    private static boolean groundedExteriorLaneCell(int x, int z) {
        return x >= -48 && x < 0 && z >= 0 && z <= 2
            || x >= 22 && x <= 24 && z >= -48 && z < 0
            || x > 31 && x <= 48 && z >= 10 && z <= 12
            || x >= 22 && x <= 24 && z > 31 && z <= 48;
    }

    private static boolean authoredGroundCell(int x, int z) {
        return groundedCompoundCell(x, z) || groundedExteriorLaneCell(x, z);
    }

    /** One natural row beyond each route end prevents a raised re-entry lip. */
    private static boolean groundedEntryApronCell(int x, int z) {
        return x == -49 && z >= -1 && z <= 3
            || z == -49 && x >= 21 && x <= 25
            || x == 49 && z >= 9 && z <= 13
            || z == 49 && x >= 21 && x <= 25;
    }

    private static boolean groundedSurveyCell(int x, int z) {
        return authoredGroundCell(x, z) || groundedEntryApronCell(x, z);
    }

    private static boolean groundedWalkOrApronCell(int x, int z) {
        return authoredGroundCell(x, z) || groundedEntryApronCell(x, z);
    }

    /**
     * Proves one physical <=1-step route from each compound gate to its own
     * natural entry apron. Every surveyed lane cell remains dry, supported
     * and clear; a rough shoulder cannot veto the distinct walkable strip.
     */
    private static GroundedRejection groundedEntranceRouteFailure(int[][] heights,
                                                                    int compoundFeet,
                                                                    int entrance) {
        BlockPos gate = switch (entrance) {
            case 0 -> new BlockPos(0, 0, 1);
            case 1 -> new BlockPos(23, 0, 0);
            case 2 -> new BlockPos(31, 0, 11);
            case 3 -> new BlockPos(23, 0, 31);
            default -> throw new IllegalArgumentException("unknown_grounded_entrance");
        };
        boolean[][] visited = new boolean[99][99];
        ArrayDeque<BlockPos> pending = new ArrayDeque<>();
        pending.add(gate);
        visited[gate.getX() + 49][gate.getZ() + 49] = true;
        boolean reachedLaneEnd = false;
        while (!pending.isEmpty()) {
            BlockPos current = pending.removeFirst();
            int x = current.getX();
            int z = current.getZ();
            if (groundedEntryApronCell(x, z)) return null;
            if (groundedExteriorLaneEndCell(x, z, entrance)) reachedLaneEnd = true;
            int currentFeet = groundedWalkingFeet(heights, compoundFeet, x, z);
            for (int[] step : GROUNDED_WALK_STEPS) {
                int nextX = x + step[0];
                int nextZ = z + step[1];
                if (nextX < -49 || nextX > 49 || nextZ < -49 || nextZ > 49
                        || visited[nextX + 49][nextZ + 49]
                        || !groundedEntranceRouteCell(nextX, nextZ, entrance)) continue;
                int nextFeet = groundedWalkingFeet(heights, compoundFeet, nextX, nextZ);
                if (Math.abs(currentFeet - nextFeet) > 1) continue;
                visited[nextX + 49][nextZ + 49] = true;
                pending.addLast(new BlockPos(nextX, 0, nextZ));
            }
        }
        return reachedLaneEnd ? GroundedRejection.APRON : GroundedRejection.ADJACENCY;
    }

    private static boolean groundedEntranceRouteCell(int x, int z, int entrance) {
        return switch (entrance) {
            case 0 -> x == 0 && z == 1 || x >= -48 && x < 0 && z >= 0 && z <= 2
                || x == -49 && z >= -1 && z <= 3;
            case 1 -> x == 23 && z == 0 || x >= 22 && x <= 24 && z >= -48 && z < 0
                || z == -49 && x >= 21 && x <= 25;
            case 2 -> x == 31 && z == 11 || x > 31 && x <= 48 && z >= 10 && z <= 12
                || x == 49 && z >= 9 && z <= 13;
            case 3 -> x == 23 && z == 31 || x >= 22 && x <= 24 && z > 31 && z <= 48
                || z == 49 && x >= 21 && x <= 25;
            default -> false;
        };
    }

    private static boolean groundedExteriorLaneEndCell(int x, int z, int entrance) {
        return switch (entrance) {
            case 0 -> x == -48 && z >= 0 && z <= 2;
            case 1 -> z == -48 && x >= 22 && x <= 24;
            case 2 -> x == 48 && z >= 10 && z <= 12;
            case 3 -> z == 48 && x >= 22 && x <= 24;
            default -> false;
        };
    }
    private static int groundedWalkingFeet(int[][] heights, int compoundFeet,
                                            int x, int z) {
        return groundedCompoundCell(x, z) ? compoundFeet : heights[x + 49][z + 49];
    }

    private static int[][] snapshotExteriorLaneFeet(int[][] heights) {
        int[][] exteriorFeet = new int[99][99];
        for (int x = -48; x <= 48; x++) for (int z = -48; z <= 48; z++) {
            if (groundedExteriorLaneCell(x, z)) exteriorFeet[x + 49][z + 49]
                = heights[x + 49][z + 49];
        }
        return exteriorFeet;
    }

    private static void floor(ServerLevel level, BlockPos origin) {
        // Continuous ground supports the first-raid band, which forms up
        // outside the 48-block claim (radius + 8..24 = 56..72; followers
        // step inward along their bearing to this 64-block disk's edge), and
        // actual outside travelers (radius + 8 = 56). The inner disk
        // also closes unsupported holes beside the raised Hearth; ordinary
        // visitor doors allow residents to leave the inner square.
        for (int x = -STAGING_RADIUS; x <= STAGING_RADIUS; x++)
                for (int z = -STAGING_RADIUS; z <= STAGING_RADIUS; z++) {
            int radiusSq = x * x + z * z;
            if (radiusSq <= STAGING_RADIUS * STAGING_RADIUS) {
                level.setBlockAndUpdate(origin.offset(x, 0, z),
                    Blocks.COBBLESTONE.defaultBlockState());
            }
        }
        authorCompound(level, origin);
    }

    /**
     * Test-only entry point: resurvey first, then author only when the same
     * natural origin remains accepted. Production passes its original survey.
     */
    static boolean groundedFloor(ServerLevel level, BlockPos origin) {
        GroundedSurvey survey = surveyedGroundedSiteAt(level, origin);
        return survey.origin() != null && survey.origin().equals(origin)
            && groundedFloor(level, origin, survey.exteriorFeet(),
                survey.vegetation());
    }

    /** The preflight snapshot is captured before any foundation, wall or door write. */
    private static boolean groundedFloor(ServerLevel level, BlockPos origin,
            int[][] exteriorFeet, List<GroundedVegetation> vegetation) {
        // The server thread cannot interleave a world mutation here. Still
        // fail closed if an unexpected caller changed a snapshotted foliage cell.
        for (GroundedVegetation entry : vegetation) {
            if (!level.getBlockState(entry.pos()).equals(entry.state())) return false;
        }
        for (GroundedVegetation entry : vegetation) {
            level.setBlockAndUpdate(entry.pos(), Blocks.AIR.defaultBlockState());
        }
        for (int x = 0; x <= 31; x++) for (int z = 0; z <= 31; z++) {
            for (int dy = 1; dy <= GROUNDED_COMPOUND_MAX_HEIGHT_DELTA; dy++) {
                BlockPos support = origin.offset(x, -dy, z);
                BlockState state = level.getBlockState(support);
                if (!state.isAir() && !state.canBeReplaced()) break;
                level.setBlockAndUpdate(support, Blocks.COBBLESTONE.defaultBlockState());
            }
        }
        authorCompound(level, origin, exteriorFeet);
        return true;
    }

    private static void authorCompound(ServerLevel level, BlockPos origin) {
        for (int x = 0; x <= 31; x++) for (int z = 0; z <= 31; z++) {
            level.setBlockAndUpdate(origin.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState());
            boolean hearthEntry = x == 0 && z == 1;
            if ((x == 0 || x == 31 || z == 0 || z == 31)
                    && !hearthEntry) {
                // The isolated fixture is commonly raised above the shared
                // GameTest floor. Keep ordinary settler navigation physical:
                // a perimeter wall prevents an employed founder (including
                // the Mayor/Farmer) from walking off and entering the normal
                // production death/mourning path during a natural wait.
                // The Hearth placed at (0,1,0) replaces this corner block and
                // is itself a full collision barrier.
                level.setBlockAndUpdate(origin.offset(x, 1, z),
                    Blocks.COBBLESTONE_WALL.defaultBlockState());
            }
        }
        for (int x = -48; x <= 0; x++) {
            for (int z = 0; z <= 2; z++) {
                level.setBlockAndUpdate(origin.offset(x, 0, z),
                    z == 1 ? Blocks.POLISHED_ANDESITE.defaultBlockState()
                        : Blocks.COBBLESTONE.defaultBlockState());
            }
        }
        // The controlled west raid gate remains sealed until `start()` opens
        // it. Visitors use ordinary wooden doors on the four approachable
        // perimeter sides: SettlerDoorGoal can physically open and close
        // them during normal travel, while the raid's explicit breach gate
        // keeps its pre-raid contract.  The settlement's real edge-spawn
        // spawn radius is 56. Multiple ordinary entrances keep the final
        // approach short after bounded outside travel, without opening the
        // raid gate or weakening any physical arrival requirement.
        level.setBlockAndUpdate(origin.offset(0, 1, 1),
            Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.FACING, Direction.EAST)
                .setValue(FenceGateBlock.OPEN, false));
        placeDoor(level, origin.offset(0, 1, 2), DoorHingeSide.LEFT,
            Direction.EAST);
        placeDoor(level, origin.offset(23, 1, 0), DoorHingeSide.LEFT,
            Direction.NORTH);
        placeDoor(level, origin.offset(31, 1, 11), DoorHingeSide.LEFT,
            Direction.EAST);
        placeDoor(level, origin.offset(23, 1, 31), DoorHingeSide.LEFT,
            Direction.SOUTH);
        // A readable central route connects the Hearth vestibule to every
        // workplace front without narrowing any container approach cell.
        for (int x = 1; x <= 29; x++) {
            level.setBlockAndUpdate(origin.offset(x, 0, 2),
                Blocks.POLISHED_ANDESITE.defaultBlockState());
        }
        for (int z = 2; z <= 30; z++) {
            level.setBlockAndUpdate(origin.offset(2, 0, z),
                Blocks.POLISHED_ANDESITE.defaultBlockState());
        }
        authorTownRoads(level, origin);
        // Three-wide radial lanes make each visitor door a real continuation
        // of the settlement floor rather than a decorative break in the
        // perimeter.  They deliberately stop at the doors; no lane opens the
        // controlled west fence gate before the first raid starts.
        radialLane(level, origin, 22, 24, -48, 0, true);
        radialLane(level, origin, 31, 48, 10, 12, true);
        radialLane(level, origin, 22, 24, 31, 48, true);
    }

    /** Grounded variant: compound uses its common plane; lanes use the snapshot. */
    private static void authorCompound(ServerLevel level, BlockPos origin,
                                       int[][] exteriorFeet) {
        for (int x = 0; x <= 31; x++) for (int z = 0; z <= 31; z++) {
            level.setBlockAndUpdate(origin.offset(x, 0, z), Blocks.STONE_BRICKS.defaultBlockState());
            boolean hearthEntry = x == 0 && z == 1;
            if ((x == 0 || x == 31 || z == 0 || z == 31) && !hearthEntry) {
                level.setBlockAndUpdate(origin.offset(x, 1, z),
                    Blocks.COBBLESTONE_WALL.defaultBlockState());
            }
        }
        for (int z = 0; z <= 2; z++) {
            level.setBlockAndUpdate(origin.offset(0, 0, z),
                z == 1 ? Blocks.POLISHED_ANDESITE.defaultBlockState()
                    : Blocks.COBBLESTONE.defaultBlockState());
        }
        level.setBlockAndUpdate(origin.offset(0, 1, 1),
            Blocks.OAK_FENCE_GATE.defaultBlockState()
                .setValue(FenceGateBlock.FACING, Direction.EAST)
                .setValue(FenceGateBlock.OPEN, false));
        placeDoor(level, origin.offset(0, 1, 2), DoorHingeSide.LEFT,
            Direction.EAST);
        placeDoor(level, origin.offset(23, 1, 0), DoorHingeSide.LEFT,
            Direction.NORTH);
        placeDoor(level, origin.offset(31, 1, 11), DoorHingeSide.LEFT,
            Direction.EAST);
        placeDoor(level, origin.offset(23, 1, 31), DoorHingeSide.LEFT,
            Direction.SOUTH);
        for (int x = 1; x <= 29; x++) {
            level.setBlockAndUpdate(origin.offset(x, 0, 2),
                Blocks.POLISHED_ANDESITE.defaultBlockState());
        }
        for (int z = 2; z <= 30; z++) {
            level.setBlockAndUpdate(origin.offset(2, 0, z),
                Blocks.POLISHED_ANDESITE.defaultBlockState());
        }
        authorTownRoads(level, origin);
        radialLane(level, origin, exteriorFeet, -48, -1, 0, 2, true);
        // The established west gate route has a polished center and cobble
        // shoulders. Both shoulders must use the same captured feet heights.
        for (int x = -48; x <= -1; x++) for (int z = 0; z <= 2; z += 2) {
            int feet = exteriorFeet[x + 49][z + 49];
            level.setBlockAndUpdate(new BlockPos(origin.getX() + x, feet - 1,
                origin.getZ() + z), Blocks.COBBLESTONE.defaultBlockState());
        }
        radialLane(level, origin, exteriorFeet, 22, 24, -48, -1, true);
        radialLane(level, origin, exteriorFeet, 32, 48, 10, 12, true);
        radialLane(level, origin, exteriorFeet, 22, 24, 32, 48, true);
    }

    /** Builds one three-wide physical visitor lane on the y=0 staging floor. */
    private static void radialLane(ServerLevel level, BlockPos origin,
                                   int first, int last, int sideFirst,
                                   int sideLast, boolean xWide) {
        for (int wide = first; wide <= last; wide++) {
            for (int longAxis = sideFirst; longAxis <= sideLast; longAxis++) {
                BlockPos floor = xWide ? origin.offset(wide, 0, longAxis)
                    : origin.offset(longAxis, 0, wide);
                level.setBlockAndUpdate(floor,
                    Blocks.POLISHED_ANDESITE.defaultBlockState());
            }
        }
    }

    /** Writes an exterior lane at the natural feet plane captured before grading. */
    private static void radialLane(ServerLevel level, BlockPos origin,
                                   int[][] exteriorFeet, int first, int last,
                                   int sideFirst, int sideLast, boolean xWide) {
        for (int wide = first; wide <= last; wide++) {
            for (int longAxis = sideFirst; longAxis <= sideLast; longAxis++) {
                int x = xWide ? wide : longAxis;
                int z = xWide ? longAxis : wide;
                if (!groundedExteriorLaneCell(x, z)) continue;
                int feet = exteriorFeet[x + 49][z + 49];
                level.setBlockAndUpdate(new BlockPos(origin.getX() + x, feet - 1,
                    origin.getZ() + z), Blocks.POLISHED_ANDESITE.defaultBlockState());
            }
        }
    }

    /**
     * Keeps the existing entrance cells and outer raid lanes intact while making
     * the inner compound read as one village street. Every added road lies in
     * an already-authored, empty y=0 compound cell immediately before a door;
     * it cannot alter a room, container approach, tree work zone, or natural-
     * height grounded exterior lane.
     */
    private static void authorTownRoads(ServerLevel level, BlockPos origin) {
        // Farmhouse, Warehouse and Tavern share the east-west market street.
        paveTownRoad(level, origin, 2, 26, 12);
        // The south branch meets the Barracks front, then skirts west of the tower.
        paveTownRoad(level, origin, 2, 12, 23);
        paveTownRoadColumn(level, origin, 12, 23, 28);
        // The final branch reaches the Watchtower plaque doorstep from its south.
        paveTownRoad(level, origin, 12, 16, 28);
    }

    private static void paveTownRoad(ServerLevel level, BlockPos origin,
                                     int firstX, int lastX, int z) {
        for (int x = firstX; x <= lastX; x++) {
            level.setBlockAndUpdate(origin.offset(x, 0, z),
                (x - firstX) % 5 == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                    : Blocks.POLISHED_ANDESITE.defaultBlockState());
        }
    }
    private static void paveTownRoadColumn(ServerLevel level, BlockPos origin,
                                           int x, int firstZ, int lastZ) {
        for (int z = firstZ; z <= lastZ; z++) {
            level.setBlockAndUpdate(origin.offset(x, 0, z),
                (z - firstZ) % 5 == 0 ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                    : Blocks.POLISHED_ANDESITE.defaultBlockState());
        }
    }
    /** Seeded owned-client geometry; registration still comes from the physical survey. */
    static Building prepareClientRoom(ServerLevel level, Settlement settlement,
                                      BlockPos origin, BuildingType type) {
        Shell shell = switch (type) {
            case LUMBER_CAMP -> Shell.LUMBER;
            case FARMHOUSE -> Shell.FARM;
            case WAREHOUSE -> Shell.WAREHOUSE;
            case BARRACKS -> Shell.BARRACKS;
            case WATCHTOWER -> Shell.WATCHTOWER;
            case HOUSE -> Shell.HOUSE_TWO;
            default -> throw new IllegalArgumentException("unsupported_client_room_" + type.id());
        };
        int size = type == BuildingType.HOUSE ? 5 : type == BuildingType.BARRACKS ? 8 : 7;
        room(level, origin, size, shell);
        BlockPos pos = fixturePlaquePos(origin, size, type);
        level.setBlockAndUpdate(pos, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, fixturePlaqueFacing(type)));
        ensurePlaqueDoorstep(level, pos);
        if (!(level.getBlockEntity(pos) instanceof PlaqueBlockEntity plaque)
                || !plaque.insertPlan(level, PlaqueItemData.stamped(
                    new ItemStack(ModItems.BUILD_PLAN.get()), type))) {
            throw new IllegalStateException("client_room_plan_refused_" + type.id());
        }
        Building building = plaque.building(level);
        if (!exactLinkedPlaque(level, settlement, plaque, building, type)) {
            throw new IllegalStateException("client_room_survey_refused_" + type.id());
        }
        return building;
    }

    private static void room(ServerLevel level, BlockPos origin, int size,
                             Shell shell) {
        BlockState wallMaterial = switch (shell) {
            case HOUSE_ONE, HOUSE_TWO, TAVERN ->
                Blocks.SPRUCE_PLANKS.defaultBlockState();
            case LUMBER -> Blocks.STRIPPED_OAK_LOG.defaultBlockState();
            case FARM -> Blocks.DARK_OAK_PLANKS.defaultBlockState();
            case WAREHOUSE, WATCHTOWER ->
                Blocks.STONE_BRICKS.defaultBlockState();
            case BARRACKS -> Blocks.COBBLESTONE.defaultBlockState();
        };
        BlockState roofMaterial = switch (shell) {
            case HOUSE_ONE, HOUSE_TWO, LUMBER ->
                Blocks.SPRUCE_PLANKS.defaultBlockState();
            case FARM, TAVERN -> Blocks.BRICKS.defaultBlockState();
            case WAREHOUSE -> Blocks.POLISHED_ANDESITE.defaultBlockState();
            case BARRACKS, WATCHTOWER ->
                Blocks.STONE_BRICKS.defaultBlockState();
        };
        // Every surveyed shell must also be an actual navigable building.  In
        // the raised GameTest world the template below is deliberately empty;
        // walls and furnishings alone can validate as a room while leaving an
        // interior target with no standable floor.  A real traveler then has
        // no path to the locked Tavern.  Give each building its own physical
        // floor before placing its walls, door and furniture so the showcase
        // proves the same walk a player will see in the prepared world.
        BlockState floorMaterial = switch (shell) {
            case HOUSE_ONE, HOUSE_TWO, LUMBER, FARM, TAVERN ->
                Blocks.SPRUCE_PLANKS.defaultBlockState();
            case WAREHOUSE, WATCHTOWER ->
                Blocks.STONE_BRICKS.defaultBlockState();
            case BARRACKS -> Blocks.COBBLESTONE.defaultBlockState();
        };
        // Keep the original single ground-floor room volume. The Watchtower
        // aperture below is a real line through the adjacent Warehouse wall,
        // not a raised deck, second room, or defender relocation.
        int wallTop = 3;
        int roofY = 4;
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) {
            level.setBlockAndUpdate(origin.offset(x, 0, z), floorMaterial);
            boolean wall = x == 0 || z == 0 || x == size - 1 || z == size - 1;
            for (int y = 1; y <= wallTop; y++) level.setBlockAndUpdate(
                origin.offset(x, y, z), wall ? wallMaterial
                    : Blocks.AIR.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(x, roofY, z), roofMaterial);
        }
        int doorX = size / 2;
        boolean wideDoor = shell == Shell.WAREHOUSE || shell == Shell.BARRACKS;
        if (shell == Shell.WATCHTOWER) {
            placeDoor(level, origin.offset(doorX, 1, size - 1),
                DoorHingeSide.LEFT, Direction.SOUTH);
        } else {
            placeDoor(level, origin.offset(doorX, 1, 0), DoorHingeSide.LEFT);
        }
        if (wideDoor) {
            placeDoor(level, origin.offset(doorX + 1, 1, 0),
                DoorHingeSide.RIGHT);
        }
        dressRoomExterior(level, origin, size, shell, doorX, wideDoor);
        if (shell == Shell.WATCHTOWER) {
            // An open iron shutter retains the surveyed room boundary.
            // Keep its hinged panel east of the ground-floor firing lane.
            openPoweredIronDoor(level, origin.offset(2, 1, 0),
                DoorHingeSide.RIGHT, Direction.NORTH);
            level.setBlockAndUpdate(origin.offset(2, 3, 0),
                Blocks.REDSTONE_BLOCK.defaultBlockState());
        }
        // A plaque is wall-hung at y+2. Rooms without beds (including the
        // Tavern) truthfully use that plaque as their persisted arrival
        // anchor. Give its exterior a one-block physical stoop: the traveler
        // can stand at the anchor's feet elevation rather than trying to path
        // to a thin sign hanging over an empty apron. This is visible village
        // architecture, not a changed recruitment target or an AI bypass.
        level.setBlockAndUpdate(origin.offset(1, 2,
                shell == Shell.WAREHOUSE ? 4 : 1),
            Blocks.WALL_TORCH.defaultBlockState().setValue(
                WallTorchBlock.FACING, Direction.EAST));
        if (shell == Shell.HOUSE_ONE) houseBeds(level, origin, true);
        if (shell == Shell.HOUSE_TWO) houseBeds(level, origin, false);
        if (shell == Shell.HOUSE_ONE || shell == Shell.HOUSE_TWO) {
            level.setBlockAndUpdate(origin.offset(0, 2, 2),
                Blocks.GLASS.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(size - 1, 2, 2),
                Blocks.GLASS.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(2, 2, size - 1),
                Blocks.GLASS.defaultBlockState());
        }
        if (shell == Shell.LUMBER) {
            level.setBlockAndUpdate(origin.offset(1, 1, 2), Blocks.CRAFTING_TABLE.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(3, 1, 3), Blocks.CHEST.defaultBlockState());
            // Open south awning: log stacks, chopping block and a roofed work
            // silhouette remain outside the surveyed room and out of its door.
            for (int x = 1; x <= 5; x++) {
                level.setBlockAndUpdate(origin.offset(x, 3, 7),
                    Blocks.SPRUCE_SLAB.defaultBlockState());
            }
            level.setBlockAndUpdate(origin.offset(1, 1, 8),
                Blocks.OAK_LOG.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(2, 1, 8),
                Blocks.OAK_LOG.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(4, 1, 8),
                Blocks.OAK_WOOD.defaultBlockState());
        } else if (shell == Shell.FARM) {
            level.setBlockAndUpdate(origin.offset(1, 1, 2), Blocks.COMPOSTER.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(3, 1, 3), Blocks.CHEST.defaultBlockState());
        } else if (shell == Shell.WAREHOUSE) {
            // Restore the canonical Warehouse storage layout. The south pair
            // of open physical doors is an aperture only; it does not move
            // stock, alter container ownership, or choose a raider target.
            for (BlockPos p : List.of(origin.offset(1, 1, 1),
                    origin.offset(3, 1, 1))) {
                level.setBlockAndUpdate(p, Blocks.CHEST.defaultBlockState());
            }
            for (BlockPos p : List.of(origin.offset(1, 1, 3),
                    origin.offset(3, 1, 3))) {
                level.setBlockAndUpdate(p, Blocks.BARREL.defaultBlockState());
            }
            openPoweredIronDoor(level, origin.offset(2, 1, size - 1),
                DoorHingeSide.RIGHT, Direction.SOUTH);
            level.setBlockAndUpdate(origin.offset(2, 3, size - 1),
                Blocks.REDSTONE_BLOCK.defaultBlockState());
            openDoor(level, origin.offset(3, 1, size - 1),
                DoorHingeSide.LEFT, Direction.SOUTH);
            // The existing Tower Post watches the ordinary Warehouse approach
            // through this paired, powered iron shutter. RoomScanner keeps
            // every DoorBlock as a boundary, while SettlerDoorGoal operates
            // only wooden doors. The header sources are above eye-height, so
            // they retain an ordinary arrow corridor without leaking the room,
            // moving stock, or changing a raid target.
            openPoweredIronDoor(level, origin.offset(2, 1, 0),
                DoorHingeSide.LEFT, Direction.NORTH);
            openPoweredIronDoor(level, origin.offset(3, 1, 0),
                DoorHingeSide.RIGHT, Direction.NORTH);
            level.setBlockAndUpdate(origin.offset(2, 3, 0),
                Blocks.REDSTONE_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(3, 3, 0),
                Blocks.REDSTONE_BLOCK.defaultBlockState());
            // Warehouse survey authority requires two physical light blocks.
            // The common room torch above is the first; this is the second.
            level.setBlockAndUpdate(origin.offset(4, 2, 5),
                Blocks.WALL_TORCH.defaultBlockState().setValue(
                    WallTorchBlock.FACING, Direction.NORTH));
        } else if (shell == Shell.TAVERN) {
            level.setBlockAndUpdate(origin.offset(2,1,2), Blocks.BELL.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(4,1,2), Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(4,1,4), Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(5,1,2),
                com.hearthstead.registry.ModBlocks.ALE_TAP.get().defaultBlockState().setValue(
                    com.hearthstead.block.AleTapBlock.FACING, Direction.EAST));
            level.setBlockAndUpdate(origin.offset(3,2,6),
                Blocks.WALL_TORCH.defaultBlockState().setValue(
                    WallTorchBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(origin.offset(6,2,3),
                Blocks.WALL_TORCH.defaultBlockState().setValue(
                    WallTorchBlock.FACING, Direction.WEST));
            level.setBlockAndUpdate(origin.offset(2, 1, 5),
                Blocks.SPRUCE_SLAB.defaultBlockState());
        } else if (shell == Shell.BARRACKS) {
            barracksBeds(level, origin);
            level.setBlockAndUpdate(origin.offset(3,1,3),
                Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(6,1,5),
                Blocks.BARREL.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(1,2,4),
                Blocks.WALL_TORCH.defaultBlockState().setValue(
                    WallTorchBlock.FACING, Direction.EAST));
            level.setBlockAndUpdate(origin.offset(6,2,4),
                Blocks.WALL_TORCH.defaultBlockState().setValue(
                    WallTorchBlock.FACING, Direction.WEST));
            level.setBlockAndUpdate(origin.offset(1,1,5),
                Blocks.SMITHING_TABLE.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(3,1,6),
                Blocks.TARGET.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(0,2,4),
                Blocks.GLASS.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(size - 1,2,4),
                Blocks.GLASS.defaultBlockState());
        } else if (shell == Shell.WATCHTOWER) {
            level.setBlockAndUpdate(origin.offset(3, 1, 3),
                Blocks.BARREL.defaultBlockState());
            // Keep the original short ladder and roof silhouette. The north
            // powered shutter shares height with the Warehouse south aperture.
            for (int y = 1; y <= 3; y++) {
                level.setBlockAndUpdate(origin.offset(5, y, 2),
                    Blocks.LADDER.defaultBlockState().setValue(
                        LadderBlock.FACING, Direction.WEST));
            }
            openPoweredIronDoor(level, origin.offset(1, 1, 0),
                DoorHingeSide.LEFT, Direction.NORTH);
            level.setBlockAndUpdate(origin.offset(1, 3, 0),
                Blocks.REDSTONE_BLOCK.defaultBlockState());
            level.setBlockAndUpdate(origin.offset(5, 1, 3),
                Blocks.LADDER.defaultBlockState().setValue(
                    LadderBlock.FACING, Direction.WEST));
            level.setBlockAndUpdate(origin.offset(1, 2, 1),
                Blocks.WALL_TORCH.defaultBlockState().setValue(
                    WallTorchBlock.FACING, Direction.EAST));
            level.setBlockAndUpdate(origin.offset(1, 2, 5),
                Blocks.WALL_TORCH.defaultBlockState().setValue(
                    WallTorchBlock.FACING, Direction.EAST));
            level.setBlockAndUpdate(origin.offset(5, 2, 1),
                Blocks.WALL_TORCH.defaultBlockState().setValue(
                    WallTorchBlock.FACING, Direction.WEST));
            level.setBlockAndUpdate(origin.offset(5, 2, 5),
                Blocks.WALL_TORCH.defaultBlockState().setValue(
                    WallTorchBlock.FACING, Direction.WEST));
            level.setBlockAndUpdate(origin.offset(0, 2, 3),
                Blocks.GLASS.defaultBlockState());
            // Keep the southern entry's upper door half intact.
            level.setBlockAndUpdate(origin.offset(4, 2, size - 1),
                Blocks.GLASS.defaultBlockState());
            for (int x = 0; x < size; x += 2) {
                level.setBlockAndUpdate(origin.offset(x, 5, 0),
                    Blocks.COBBLESTONE_WALL.defaultBlockState());
                level.setBlockAndUpdate(origin.offset(x, 5, size - 1),
                    Blocks.COBBLESTONE_WALL.defaultBlockState());
            }
            for (int z = 2; z < size - 1; z += 2) {
                level.setBlockAndUpdate(origin.offset(0, 5, z),
                    Blocks.COBBLESTONE_WALL.defaultBlockState());
                level.setBlockAndUpdate(origin.offset(size - 1, 5, z),
                    Blocks.COBBLESTONE_WALL.defaultBlockState());
            }
        }
    }

    /**
     * Applies a compact, role-readable facade without moving any fixture
     * interaction cell. The existing room remains a single roofed volume:
     * floor y=0, clear walking y=1..2, wall/trim y=3 and a roof no higher than
     * y=5. The real plaque support at x=1 is intentionally left solid.
     */
    private static void dressRoomExterior(ServerLevel level, BlockPos origin,
                                          int size, Shell shell, int doorX,
                                          boolean wideDoor) {
        BlockState foundation = switch (shell) {
            case HOUSE_ONE, HOUSE_TWO -> Blocks.MOSSY_COBBLESTONE.defaultBlockState();
            case LUMBER, FARM -> Blocks.COBBLESTONE.defaultBlockState();
            case WAREHOUSE, WATCHTOWER -> Blocks.DEEPSLATE_BRICKS.defaultBlockState();
            case TAVERN -> Blocks.MUD_BRICKS.defaultBlockState();
            case BARRACKS -> Blocks.STONE_BRICKS.defaultBlockState();
        };
        BlockState infill = switch (shell) {
            case HOUSE_ONE -> Blocks.WHITE_TERRACOTTA.defaultBlockState();
            case HOUSE_TWO -> Blocks.TERRACOTTA.defaultBlockState();
            case LUMBER -> Blocks.STRIPPED_OAK_WOOD.defaultBlockState();
            case FARM -> Blocks.DARK_OAK_PLANKS.defaultBlockState();
            case WAREHOUSE -> Blocks.STONE_BRICKS.defaultBlockState();
            case TAVERN -> Blocks.SPRUCE_PLANKS.defaultBlockState();
            case BARRACKS -> Blocks.COBBLESTONE.defaultBlockState();
            case WATCHTOWER -> Blocks.STONE_BRICKS.defaultBlockState();
        };
        BlockState frame = switch (shell) {
            case HOUSE_ONE, HOUSE_TWO, BARRACKS -> Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState();
            case LUMBER -> Blocks.STRIPPED_OAK_LOG.defaultBlockState();
            case FARM -> Blocks.DARK_OAK_LOG.defaultBlockState();
            case WAREHOUSE, WATCHTOWER -> Blocks.POLISHED_ANDESITE.defaultBlockState();
            case TAVERN -> Blocks.DARK_OAK_LOG.defaultBlockState();
        };
        for (int x = 0; x < size; x++) for (int z = 0; z < size; z++) {
            if (x != 0 && z != 0 && x != size - 1 && z != size - 1) continue;
            boolean frontDoor = shell != Shell.WATCHTOWER && z == 0
                && (x == doorX || wideDoor && x == doorX + 1);
            boolean towerDoor = shell == Shell.WATCHTOWER && z == size - 1
                && x == doorX;
            for (int y = 1; y <= 3; y++) {
                if ((frontDoor || towerDoor) && y <= 2) continue;
                BlockState block = y == 1 ? foundation : y == 2 ? infill : frame;
                level.setBlockAndUpdate(origin.offset(x, y, z), block);
            }
        }
        // Preserve the load-bearing plaque backs: ordinary plaques attach to
        // (1,2,0); the tower's south plaque attaches to (1,2,size-1).
        if (shell != Shell.WATCHTOWER) {
            level.setBlockAndUpdate(origin.offset(1, 2, 0), infill);
            level.setBlockAndUpdate(origin.offset(size - 2, 2, 0),
                Blocks.GLASS_PANE.defaultBlockState());
        } else {
            level.setBlockAndUpdate(origin.offset(1, 2, size - 1), infill);
            level.setBlockAndUpdate(origin.offset(size - 2, 2, size - 1),
                Blocks.IRON_BARS.defaultBlockState());
        }
        BlockState crest = switch (shell) {
            case HOUSE_ONE -> Blocks.LIGHT_BLUE_WOOL.defaultBlockState();
            case HOUSE_TWO -> Blocks.YELLOW_WOOL.defaultBlockState();
            case LUMBER -> Blocks.OAK_LOG.defaultBlockState();
            case FARM -> Blocks.HAY_BLOCK.defaultBlockState();
            case WAREHOUSE -> Blocks.CHISELED_STONE_BRICKS.defaultBlockState();
            case TAVERN -> Blocks.RED_WOOL.defaultBlockState();
            case BARRACKS -> Blocks.TARGET.defaultBlockState();
            case WATCHTOWER -> Blocks.IRON_BARS.defaultBlockState();
        };
        int crestX = doorX == size - 2 ? 1 : size - 2;
        BlockPos crestPos = shell == Shell.WATCHTOWER
            ? origin.offset(crestX, 3, size - 1) : origin.offset(crestX, 3, 0);
        level.setBlockAndUpdate(crestPos, crest);
        placeFacadeSign(level, origin, size, shell, doorX, frame);
        if (shell != Shell.WATCHTOWER) dressGabledRoof(level, origin, size, shell);
    }

    /** A physical, glowing English sign makes the purpose readable before a player opens its plaque. */
    private static void placeFacadeSign(ServerLevel level, BlockPos origin,
                                        int size, Shell shell, int doorX,
                                        BlockState frame) {
        boolean tower = shell == Shell.WATCHTOWER;
        Direction facing = tower ? Direction.SOUTH : Direction.NORTH;
        BlockPos signPos = tower ? origin.offset(doorX, 5, size - 1)
            : origin.offset(doorX, 5, 0);
        // A sign needs a real backing block. It sits on the roof fascia, so
        // the room wall and the two-block walking corridor stay untouched.
        level.setBlockAndUpdate(signPos.relative(facing.getOpposite()), frame);
        level.setBlockAndUpdate(signPos, Blocks.SPRUCE_WALL_SIGN.defaultBlockState()
            .setValue(WallSignBlock.FACING, facing));
        if (level.getBlockEntity(signPos) instanceof SignBlockEntity sign) {
            String label = switch (shell) {
                case HOUSE_ONE -> "BUNKHOUSE";
                case HOUSE_TWO -> "COTTAGE";
                case LUMBER -> "LUMBER";
                case FARM -> "FARM";
                case WAREHOUSE -> "WAREHOUSE";
                case TAVERN -> "TAVERN";
                case BARRACKS -> "BARRACKS";
                case WATCHTOWER -> "WATCHTOWER";
            };
            sign.updateText(text -> text.setMessage(0, Component.literal(label))
                .setColor(DyeColor.WHITE).setHasGlowingText(true), true);
            sign.updateText(text -> text.setMessage(0, Component.literal(label))
                .setColor(DyeColor.WHITE).setHasGlowingText(true), false);
            level.sendBlockUpdated(signPos, sign.getBlockState(),
                sign.getBlockState(), 3);
        }
    }
    /** Low eaves plus a one-block ridge give each non-tower roof a readable silhouette. */
    private static void dressGabledRoof(ServerLevel level, BlockPos origin,
                                        int size, Shell shell) {
        BlockState eave = switch (shell) {
            case HOUSE_ONE, HOUSE_TWO, LUMBER -> Blocks.SPRUCE_STAIRS.defaultBlockState();
            case FARM -> Blocks.DARK_OAK_STAIRS.defaultBlockState();
            case WAREHOUSE -> Blocks.STONE_BRICK_STAIRS.defaultBlockState();
            case TAVERN -> Blocks.BRICK_STAIRS.defaultBlockState();
            case BARRACKS -> Blocks.COBBLESTONE_STAIRS.defaultBlockState();
            case WATCHTOWER -> throw new IllegalArgumentException("tower_has_no_gable");
        };
        BlockState ridge = switch (shell) {
            case HOUSE_ONE, HOUSE_TWO, LUMBER -> Blocks.SPRUCE_SLAB.defaultBlockState();
            case FARM -> Blocks.DARK_OAK_SLAB.defaultBlockState();
            case WAREHOUSE -> Blocks.STONE_BRICK_SLAB.defaultBlockState();
            case TAVERN -> Blocks.BRICK_SLAB.defaultBlockState();
            case BARRACKS -> Blocks.COBBLESTONE_SLAB.defaultBlockState();
            case WATCHTOWER -> throw new IllegalArgumentException("tower_has_no_ridge");
        };
        for (int x = 0; x < size; x++) {
            level.setBlockAndUpdate(origin.offset(x, 4, 0),
                eave.setValue(StairBlock.FACING, Direction.NORTH));
            level.setBlockAndUpdate(origin.offset(x, 4, size - 1),
                eave.setValue(StairBlock.FACING, Direction.SOUTH));
            for (int z = (size - 1) / 2; z <= size / 2; z++) {
                level.setBlockAndUpdate(origin.offset(x, 5, z), ridge);
            }
        }
    }
    private static void placeDoor(ServerLevel level, BlockPos lower,
            DoorHingeSide hinge) {
        placeDoor(level, lower, hinge, Direction.NORTH);
    }

    private static void placeDoor(ServerLevel level, BlockPos lower,
            DoorHingeSide hinge, Direction facing) {
        BlockState base = Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HINGE, hinge)
            .setValue(DoorBlock.FACING, facing);
        level.setBlockAndUpdate(lower,
            base.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        level.setBlockAndUpdate(lower.above(),
            base.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void openDoor(ServerLevel level, BlockPos lower,
            DoorHingeSide hinge, Direction facing) {
        placeDoor(level, lower, hinge, facing);
        level.setBlockAndUpdate(lower, level.getBlockState(lower)
            .setValue(DoorBlock.OPEN, true));
        level.setBlockAndUpdate(lower.above(), level.getBlockState(lower.above())
            .setValue(DoorBlock.OPEN, true));
    }

    /**
     * A powered, permanently open fixture shutter. Unlike wood, the ordinary
     * settler door goal cannot claim or close iron; header power also makes
     * the state stable across routine neighbour updates.
     */
    private static void openPoweredIronDoor(ServerLevel level, BlockPos lower,
                                            DoorHingeSide hinge, Direction facing) {
        BlockState base = Blocks.IRON_DOOR.defaultBlockState()
            .setValue(DoorBlock.HINGE, hinge)
            .setValue(DoorBlock.FACING, facing)
            .setValue(DoorBlock.POWERED, true)
            .setValue(DoorBlock.OPEN, true);
        level.setBlockAndUpdate(lower,
            base.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        level.setBlockAndUpdate(lower.above(),
            base.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
    }

    private static void beds(ServerLevel level, BlockPos origin, int count) {
        BlockPos[][] positions = {
            {origin.offset(1, 1, 1), origin.offset(1, 1, 2)},
            {origin.offset(2, 1, 1), origin.offset(2, 1, 2)},
            {origin.offset(3, 1, 1), origin.offset(3, 1, 2)},
            {origin.offset(1, 1, 3), origin.offset(2, 1, 3)}
        };
        Direction[] directions = {
            Direction.SOUTH, Direction.SOUTH, Direction.SOUTH, Direction.EAST
        };
        for (int i = 0; i < Math.min(count, positions.length); i++) {
            BlockPos foot = positions[i][0];
            BlockPos head = positions[i][1];
            level.setBlockAndUpdate(foot, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, directions[i])
                .setValue(BedBlock.PART, BedPart.FOOT));
            level.setBlockAndUpdate(head, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, directions[i])
                .setValue(BedBlock.PART, BedPart.HEAD));
        }
    }

    private static void houseBeds(ServerLevel level, BlockPos origin,
            boolean bunkhouse) {
        List<BlockPos[]> pairs = bunkhouse
            ? List.of(new BlockPos[] {origin.offset(1,1,1), origin.offset(1,1,2)},
                new BlockPos[] {origin.offset(3,1,1), origin.offset(3,1,2)},
                new BlockPos[] {origin.offset(1,1,3), origin.offset(2,1,3)})
            : List.of(new BlockPos[] {origin.offset(1,1,1), origin.offset(1,1,2)},
                new BlockPos[] {origin.offset(3,1,1), origin.offset(3,1,2)},
                new BlockPos[] {origin.offset(1,1,3), origin.offset(2,1,3)});
        for (int i = 0; i < pairs.size(); i++) {
            BlockPos foot = pairs.get(i)[0];
            BlockPos head = pairs.get(i)[1];
            Direction facing = i == 2
                ? Direction.EAST : Direction.SOUTH;
            level.setBlockAndUpdate(foot, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, facing)
                .setValue(BedBlock.PART, BedPart.FOOT));
            level.setBlockAndUpdate(head, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, facing)
                .setValue(BedBlock.PART, BedPart.HEAD));
        }
    }

    private static void barracksBeds(ServerLevel level, BlockPos origin) {
        for (int x : new int[] {1, 2, 5, 6}) {
            BlockPos foot = origin.offset(x, 1, 1);
            BlockPos head = origin.offset(x, 1, 2);
            level.setBlockAndUpdate(foot, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.FOOT));
            level.setBlockAndUpdate(head, Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.SOUTH)
                .setValue(BedBlock.PART, BedPart.HEAD));
        }
    }

    private static int countBedHeads(ServerLevel level, BlockPos origin) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(origin,
                origin.offset(31, 5, 31))) {
            if (level.getBlockState(pos).is(Blocks.RED_BED)
                && level.getBlockState(pos).getValue(BedBlock.PART) == BedPart.HEAD) count++;
        }
        return count;
    }
}
