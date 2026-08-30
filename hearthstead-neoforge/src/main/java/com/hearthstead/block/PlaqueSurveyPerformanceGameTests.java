package com.hearthstead.block;

import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.building.PlaqueSheet;
import com.hearthstead.building.PlaqueState;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Structural performance contracts for periodic plaque surveying. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class PlaqueSurveyPerformanceGameTests {

    /**
     * Drives the real block-entity scheduler at the supported matrix scales.
     * This is deliberately a query/schedule contract, not a stopwatch test.
     */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "plaque_survey_position_stagger_matrix_1_25_50_100")
    public void positionPhaseSpreadsOneHundredPlaquesAcrossSurveyTicks(
            GameTestHelper helper) {
        long now = helper.getLevel().getGameTime();
        BlockState plaqueState = ModBlocks.PLAQUE.get().defaultBlockState();
        for (int scale : new int[]{1, 25, 50, 100}) {
            Set<Long> dueTicks = new HashSet<>();
            for (int index = 0; index < scale; index++) {
                BlockPos pos = helper.absolutePos(new BlockPos(
                    2 + index % 10, 1, 2 + index / 10));
                PlaqueBlockEntity plaque = new PlaqueBlockEntity(pos, plaqueState);
                CompoundTag disk = new CompoundTag();
                disk.putString("Type", BuildingType.HOUSE.id());
                disk.putString("State", PlaqueState.EMPTY.id());
                plaque.loadAdditional(disk, helper.getLevel().registryAccess());
                helper.assertTrue(plaque.loadProjectionHydrationPendingForTest(),
                    "scale " + scale + ": disk load must request one hydration");
                PlaqueBlockEntity.serverTick(helper.getLevel(), pos, plaqueState, plaque);
                long due = plaque.nextSurveyTickForTest();
                helper.assertTrue(due > now
                        && due <= now + PlaqueBlockEntity.surveyIntervalForTest(),
                    "scale " + scale + ": first phased survey must land inside "
                        + "the next cadence, got delta " + (due - now));
                helper.assertTrue(plaque.scanAttempts() == 0,
                    "scale " + scale + ": load hydration must run zero RoomScanner scans");
                helper.assertTrue(plaque.loadProjectionHydrationCountForTest() == 1
                        && !plaque.loadProjectionHydrationPendingForTest()
                        && plaque.loadProjectionPublishCountForTest() == 0
                        && plaque.loadProjectionBlockStatePublishCountForTest() == 0
                        && plaque.loadProjectionExplicitPublishCountForTest() == 0,
                    "scale " + scale + ": each disk-loaded idle plaque must hydrate "
                        + "exactly once with no empty projection packet");
                PlaqueBlockEntity.serverTick(helper.getLevel(), pos, plaqueState, plaque);
                helper.assertTrue(plaque.loadProjectionHydrationCountForTest() == 1
                        && plaque.scanAttempts() == 0,
                    "scale " + scale + ": repeated same-tick tick must be silent");
                dueTicks.add(due);
            }
            helper.assertTrue(dueTicks.size() == scale,
                "scale " + scale + ": the deterministic 10x10 plaque matrix "
                    + "must occupy " + scale + " separate survey ticks, got "
                    + dueTicks.size());
        }
        helper.succeed();
    }

    /**
     * A disk reload clears every derived field. Its first server tick must
     * repair renderer occupancy and a stale courier lamp without running the
     * staggered room scan or creating a persistent dirty/revision heartbeat.
     */
    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "plaque_reload_projection_hydrates_without_room_scan")
    public void diskReloadHydratesOccupancyAndRepairsTransientGlowOnce(
            GameTestHelper helper) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Reloadstead",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 20;
        data.settlements.put(settlement.id, settlement);

        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildHut(helper, hutOrigin);
        BlockPos plaqueRel = hutOrigin.offset(1, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        BlockPos plaquePos = helper.absolutePos(plaqueRel);
        if (!(helper.getLevel().getBlockEntity(plaquePos)
            instanceof PlaqueBlockEntity plaque)) {
            helper.fail("fixture: plaque block entity missing");
            return;
        }
        ItemStack plan = PlaqueItemData.stamped(
            new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.HOUSE);
        helper.assertTrue(plaque.insertPlan(helper.getLevel(), plan),
            "fixture: complete reload house must register");
        Building building = plaque.building(helper.getLevel());
        helper.assertTrue(building != null && building.valid
                && !building.beds.isEmpty(),
            "fixture: reload house must expose its authoritative bed");

        SettlerEntity resident = helper.spawn(ModEntities.SETTLER.get(),
            hutOrigin.offset(2, 1, 1));
        resident.setSettlerName("Load resident");
        resident.bindTo(settlement.id, settlement.center);
        settlement.putRecord(resident.getUUID(), resident.getSettlerName(),
            Profession.NONE);
        resident.claimBed(building.beds.getFirst());
        plaque.survey(helper.getLevel());
        helper.assertTrue(plaque.occupants() == 1 && plaque.capacity() > 0,
            "fixture: pre-save projection must see one resident");

        plaque.setLogisticsStopReason(helper.getLevel(), UUID.randomUUID(),
            StopReason.NO_WAREHOUSE_SPACE);
        plaque.tickLogisticsForTest(helper.getLevel(),
            helper.getLevel().getGameTime());
        helper.assertTrue(plaque.logisticsStopReason()
                == StopReason.NO_WAREHOUSE_SPACE
                && helper.getBlockState(plaqueRel).getValue(PlaqueBlock.GLOW)
                    == PlaqueBlock.Glow.RED,
            "fixture: transient courier diagnosis must leave a red saved block state");

        CompoundTag disk = plaque.saveWithoutMetadata(
            helper.getLevel().registryAccess());
        helper.assertTrue(!disk.contains("Survey")
                && !disk.contains("Occupants")
                && !disk.contains("Capacity")
                && !disk.contains("LogisticsStop"),
            "disk must retain no stale derived projection");
        int savedRevision = plaque.revision();
        int commitsBefore = plaque.surveyCommitCountForTest();
        int surveyPacketsBefore = plaque.surveyUpdatePacketCountForTest();
        int scansBefore = plaque.scanAttempts();
        int hydrationsBefore = plaque.loadProjectionHydrationCountForTest();
        int hydrationPublishesBefore = plaque.loadProjectionPublishCountForTest();
        int blockPublishesBefore =
            plaque.loadProjectionBlockStatePublishCountForTest();
        int explicitPublishesBefore =
            plaque.loadProjectionExplicitPublishCountForTest();
        data.setDirty(false);

        // Exercise the same protected NBT load hook used by a server chunk
        // reload while retaining the physically saved red block state.
        plaque.loadAdditional(disk, helper.getLevel().registryAccess());
        helper.assertTrue(plaque.lastSurvey().isEmpty()
                && plaque.occupants() == 0 && plaque.capacity() == 0
                && plaque.logisticsStopReason() == StopReason.NONE
                && plaque.loadProjectionHydrationPendingForTest()
                && helper.getBlockState(plaqueRel).getValue(PlaqueBlock.GLOW)
                    == PlaqueBlock.Glow.RED,
            "disk load must clear stale Java projection before one-shot hydration");

        long now = helper.getLevel().getGameTime();
        PlaqueBlockEntity.serverTick(helper.getLevel(), plaquePos,
            helper.getBlockState(plaqueRel), plaque);
        helper.assertTrue(plaque.loadProjectionHydrationCountForTest()
                == hydrationsBefore + 1
                && !plaque.loadProjectionHydrationPendingForTest()
                && plaque.scanAttempts() == scansBefore,
            "first loaded tick must hydrate once with zero RoomScanner scans");
        helper.assertTrue(plaque.occupants() == 1 && plaque.capacity() > 0
                && plaque.lastSurvey().isEmpty(),
            "cheap hydration must restore current occupancy without inventing "
                + "or persisting requirement measurements");
        helper.assertTrue(plaque.logisticsStopReason() == StopReason.NONE
                && helper.getBlockState(plaqueRel).getValue(PlaqueBlock.GLOW)
                    == PlaqueBlock.Glow.GREEN
                && helper.getBlockState(plaqueRel).getValue(PlaqueBlock.REGISTERED),
            "first loaded tick must reconcile transient NONE to valid green truth");
        helper.assertTrue(plaque.revision() == savedRevision
                && plaque.surveyCommitCountForTest() == commitsBefore
                && plaque.surveyUpdatePacketCountForTest() == surveyPacketsBefore
                && plaque.loadProjectionPublishCountForTest()
                    == hydrationPublishesBefore + 1
                && plaque.loadProjectionBlockStatePublishCountForTest()
                    == blockPublishesBefore + 1
                && plaque.loadProjectionExplicitPublishCountForTest()
                    == explicitPublishesBefore
                && !data.isDirty(),
            "stale-red plus projection hydration must coalesce into the one "
                + "flags=3 blockstate/BE publish, without explicit duplicate, "
                + "revision, survey commit or SavedData dirty state");
        helper.assertTrue(plaque.nextSurveyTickForTest() > now
                && plaque.nextSurveyTickForTest()
                    <= now + PlaqueBlockEntity.surveyIntervalForTest(),
            "the real room scan must remain position-staggered after hydration");

        PlaqueSheet sheet = PlaqueSheet.of(plaque.type(), plaque.state(),
            plaque.lastSurvey(), plaque.occupants(), plaque.capacity());
        helper.assertTrue(sheet.lines().size() == 1
                && sheet.lines().getFirst().id().equals(PlaqueSheet.OCCUPANCY_ID),
            "the first renderer-facing sheet must be truthful occupancy, not 0/0");
        CompoundTag wire = plaque.getUpdateTag(helper.getLevel().registryAccess());
        helper.assertTrue(wire.getInt("Occupants") == 1
                && wire.getInt("Capacity") == plaque.capacity()
                && wire.getByte("LogisticsStop") == StopReason.NONE.wireId(),
            "the first wire projection must match the reconciled renderer state");

        // Reload once more from the same disk while the physical lamp is
        // already green. With no blockstate packet to carry the BE tag, this
        // path must author exactly one explicit projection update instead.
        int greenHydrationsBefore = plaque.loadProjectionHydrationCountForTest();
        int greenPublishesBefore = plaque.loadProjectionPublishCountForTest();
        int greenBlockPublishesBefore =
            plaque.loadProjectionBlockStatePublishCountForTest();
        int greenExplicitPublishesBefore =
            plaque.loadProjectionExplicitPublishCountForTest();
        plaque.loadAdditional(disk, helper.getLevel().registryAccess());
        helper.assertTrue(plaque.loadProjectionHydrationPendingForTest()
                && plaque.occupants() == 0 && plaque.capacity() == 0
                && helper.getBlockState(plaqueRel).getValue(PlaqueBlock.GLOW)
                    == PlaqueBlock.Glow.GREEN,
            "second disk load must expose the green/no-projection publication path");
        PlaqueBlockEntity.serverTick(helper.getLevel(), plaquePos,
            helper.getBlockState(plaqueRel), plaque);
        helper.assertTrue(plaque.loadProjectionHydrationCountForTest()
                == greenHydrationsBefore + 1
                && plaque.loadProjectionPublishCountForTest()
                    == greenPublishesBefore + 1
                && plaque.loadProjectionBlockStatePublishCountForTest()
                    == greenBlockPublishesBefore
                && plaque.loadProjectionExplicitPublishCountForTest()
                    == greenExplicitPublishesBefore + 1,
            "green plus changed projection must author one explicit BE update only");

        PlaqueBlockEntity.serverTick(helper.getLevel(), plaquePos,
            helper.getBlockState(plaqueRel), plaque);
        helper.assertTrue(plaque.loadProjectionHydrationCountForTest()
                == greenHydrationsBefore + 1
                && plaque.loadProjectionPublishCountForTest()
                    == greenPublishesBefore + 1
                && plaque.scanAttempts() == scansBefore,
            "later ticks before the phased survey must create no hydration heartbeat");
        helper.succeed();
    }

    /**
     * Cheap load hydration may observe a deleted building, but the phased
     * room survey alone owns the resulting orphan/relink transition.
     */
    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "plaque_reload_missing_building_defers_identity_transition")
    public void diskReloadMissingBuildingDefersIdentityMutationToSurvey(
            GameTestHelper helper) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Lostlink",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 20;
        data.settlements.put(settlement.id, settlement);

        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildHut(helper, hutOrigin);
        BlockPos plaqueRel = hutOrigin.offset(1, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        BlockPos plaquePos = helper.absolutePos(plaqueRel);
        if (!(helper.getLevel().getBlockEntity(plaquePos)
            instanceof PlaqueBlockEntity plaque)) {
            helper.fail("fixture: missing-building plaque block entity missing");
            return;
        }
        ItemStack plan = PlaqueItemData.stamped(
            new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.HOUSE);
        helper.assertTrue(plaque.insertPlan(helper.getLevel(), plan),
            "fixture: complete house must register before its building is lost");
        Building original = plaque.building(helper.getLevel());
        UUID originalId = plaque.buildingId();
        helper.assertTrue(original != null && originalId != null
                && plaque.state() == PlaqueState.LINKED_VALID,
            "fixture: plaque must begin with one exact valid building identity");

        plaque.setLogisticsStopReason(helper.getLevel(), UUID.randomUUID(),
            StopReason.NO_WAREHOUSE_SPACE);
        plaque.tickLogisticsForTest(helper.getLevel(),
            helper.getLevel().getGameTime());
        helper.assertTrue(helper.getBlockState(plaqueRel)
                .getValue(PlaqueBlock.GLOW) == PlaqueBlock.Glow.RED
                && helper.getBlockState(plaqueRel)
                    .getValue(PlaqueBlock.REGISTERED),
            "fixture: persisted linked plaque must start red and registered");

        CompoundTag disk = plaque.saveWithoutMetadata(
            helper.getLevel().registryAccess());
        int savedRevision = plaque.revision();
        int commitsBefore = plaque.surveyCommitCountForTest();
        int packetsBefore = plaque.surveyUpdatePacketCountForTest();
        int scansBefore = plaque.scanAttempts();
        int publishesBefore = plaque.loadProjectionPublishCountForTest();
        int hydrationsBefore = plaque.loadProjectionHydrationCountForTest();
        String[] rejectedShapes = {
            "missing UUID", "invalid building", "wrong plaque position",
            "wrong building type"
        };
        for (int variant = 0; variant < rejectedShapes.length; variant++) {
            if (variant == 0) {
                helper.assertTrue(settlement.buildings.remove(original)
                        && settlement.buildings.isEmpty(),
                    "fixture: authoritative building UUID must genuinely be absent");
            } else {
                if (!settlement.buildings.contains(original)) {
                    settlement.buildings.add(original);
                }
                original.valid = variant != 1;
                original.plaquePos = variant == 2
                    ? plaquePos.offset(1, 0, 0) : plaquePos;
                original.type = variant == 3
                    ? BuildingType.WAREHOUSE : BuildingType.HOUSE;
            }
            data.setDirty(false);

            plaque.loadAdditional(disk, helper.getLevel().registryAccess());
            PlaqueBlockEntity.serverTick(helper.getLevel(), plaquePos,
                helper.getBlockState(plaqueRel), plaque);
            helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID
                    && originalId.equals(plaque.buildingId()),
                rejectedShapes[variant]
                    + " hydration must not orphan or replace saved identity");
            helper.assertTrue(helper.getBlockState(plaqueRel)
                    .getValue(PlaqueBlock.GLOW) == PlaqueBlock.Glow.RED
                    && helper.getBlockState(plaqueRel)
                        .getValue(PlaqueBlock.REGISTERED),
                rejectedShapes[variant]
                    + " must preserve the complete persisted physical state");
            helper.assertTrue(plaque.revision() == savedRevision
                    && plaque.surveyCommitCountForTest() == commitsBefore
                    && plaque.surveyUpdatePacketCountForTest() == packetsBefore
                    && plaque.scanAttempts() == scansBefore
                    && plaque.loadProjectionHydrationCountForTest()
                        == hydrationsBefore + variant + 1
                    && plaque.loadProjectionPublishCountForTest() == publishesBefore
                    && !data.isDirty(),
                rejectedShapes[variant]
                    + " hydration must emit no false identity packet, revision "
                    + "or persistence mutation");
        }

        helper.assertTrue(settlement.buildings.remove(original),
            "fixture: real survey transition must begin from an absent exact UUID");
        data.setDirty(false);

        plaque.survey(helper.getLevel());
        UUID replacementId = plaque.buildingId();
        helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID
                && replacementId != null && !replacementId.equals(originalId)
                && settlement.buildings.size() == 1
                && settlement.buildings.getFirst().id.equals(replacementId),
            "the later real survey must perform one truthful orphan/relink transition");
        helper.assertTrue(plaque.revision() == savedRevision + 1
                && plaque.surveyCommitCountForTest() == commitsBefore + 1
                && plaque.surveyUpdatePacketCountForTest() == packetsBefore + 1
                && plaque.scanAttempts() == scansBefore + 1
                && data.isDirty(),
            "the real identity replacement must commit, persist and publish exactly once");
        helper.succeed();
    }

    /** Requirement-progress glow is not reconstructible without RoomScanner. */
    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "plaque_reload_incomplete_preserves_persisted_progress_glow")
    public void incompleteDiskReloadKeepsAmberUntilItsPhasedSurvey(
            GameTestHelper helper) {
        BlockPos plaqueRel = new BlockPos(5, 2, 5);
        helper.setBlock(plaqueRel.south(), Blocks.STONE_BRICKS);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.GLOW, PlaqueBlock.Glow.AMBER)
            .setValue(PlaqueBlock.REGISTERED, false));
        BlockPos plaquePos = helper.absolutePos(plaqueRel);
        if (!(helper.getLevel().getBlockEntity(plaquePos)
            instanceof PlaqueBlockEntity plaque)) {
            helper.fail("fixture: incomplete plaque block entity missing");
            return;
        }

        CompoundTag disk = new CompoundTag();
        disk.putString("Type", BuildingType.HOUSE.id());
        disk.putString("State", PlaqueState.LINKED_INCOMPLETE.id());
        // One stray/legacy wire key is not a complete wire projection and
        // must not suppress server-side hydration.
        disk.put("Survey", new net.minecraft.nbt.ListTag());
        disk.put("Plan", PlaqueItemData.stamped(
            new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.HOUSE)
            .saveOptional(helper.getLevel().registryAccess()));
        plaque.loadAdditional(disk, helper.getLevel().registryAccess());
        helper.assertTrue(plaque.loadProjectionHydrationPendingForTest(),
            "Survey-only malformed wire shape must fail closed into hydration");
        int scansBefore = plaque.scanAttempts();
        int publishesBefore = plaque.loadProjectionPublishCountForTest();
        long now = helper.getLevel().getGameTime();
        PlaqueBlockEntity.serverTick(helper.getLevel(), plaquePos,
            helper.getBlockState(plaqueRel), plaque);

        helper.assertTrue(plaque.state() == PlaqueState.LINKED_INCOMPLETE
                && helper.getBlockState(plaqueRel).getValue(PlaqueBlock.GLOW)
                    == PlaqueBlock.Glow.AMBER
                && !helper.getBlockState(plaqueRel)
                    .getValue(PlaqueBlock.REGISTERED),
            "incomplete load must preserve persisted partial/amber progress truth");
        helper.assertTrue(plaque.scanAttempts() == scansBefore
                && plaque.loadProjectionHydrationCountForTest() == 1
                && plaque.loadProjectionPublishCountForTest() == publishesBefore,
            "incomplete load hydration must run zero RoomScanner and zero publish work");
        helper.assertTrue(plaque.nextSurveyTickForTest() > now,
            "incomplete requirement re-measurement must remain phased");
        helper.succeed();
    }

    /**
     * One stable healthy survey is observational; one real room change is
     * published exactly once and the following identical survey is silent.
     */
    @GameTest(template = "empty16", timeoutTicks = 200,
        batch = "plaque_survey_no_unchanged_dirty_packet_or_bed_heartbeat")
    public void identicalHealthySurveyIsSilentAndRealChangePublishesOnce(
            GameTestHelper helper) {
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Phasestead",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 20;
        data.settlements.put(settlement.id, settlement);

        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildHut(helper, hutOrigin);
        BlockPos plaqueRel = hutOrigin.offset(1, 2, -1);
        helper.setBlock(plaqueRel, ModBlocks.PLAQUE.get().defaultBlockState()
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        BlockPos plaquePos = helper.absolutePos(plaqueRel);
        if (!(helper.getLevel().getBlockEntity(plaquePos)
            instanceof PlaqueBlockEntity plaque)) {
            helper.fail("fixture: plaque block entity missing");
            return;
        }
        ItemStack plan = PlaqueItemData.stamped(
            new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.HOUSE);
        helper.assertTrue(plaque.insertPlan(helper.getLevel(), plan),
            "fixture: the house plan must fit");
        Building building = plaque.building(helper.getLevel());
        helper.assertTrue(plaque.state() == PlaqueState.LINKED_VALID
                && building != null && building.valid,
            "fixture: the complete hut must register a valid house");

        int stableRevision = plaque.revision();
        int stableCommits = plaque.surveyCommitCountForTest();
        int stablePackets = plaque.surveyUpdatePacketCountForTest();
        int stableBedAssignments = plaque.bedAssignmentAttemptCountForTest();
        data.setDirty(false);
        plaque.survey(helper.getLevel());
        helper.assertTrue(plaque.revision() == stableRevision
                && plaque.surveyCommitCountForTest() == stableCommits,
            "an identical healthy survey must not bump revision or call setChanged");
        helper.assertTrue(plaque.surveyUpdatePacketCountForTest() == stablePackets,
            "an identical healthy survey must author zero block-entity packets");
        helper.assertTrue(!data.isDirty(),
            "an identical healthy survey must not dirty SettlementSavedData");
        helper.assertTrue(plaque.bedAssignmentAttemptCountForTest()
                == stableBedAssignments,
            "an identical healthy survey must not run assignFreeBeds");

        int oldLights = building.lightSources;
        BlockPos extraTorch = helper.absolutePos(hutOrigin.offset(3, 1, 3));
        helper.getLevel().setBlock(extraTorch, Blocks.TORCH.defaultBlockState(),
            Block.UPDATE_CLIENTS);
        int beforeChangeRevision = plaque.revision();
        int beforeChangeCommits = plaque.surveyCommitCountForTest();
        int beforeChangePackets = plaque.surveyUpdatePacketCountForTest();
        int beforeChangeBeds = plaque.bedAssignmentAttemptCountForTest();
        data.setDirty(false);
        plaque.survey(helper.getLevel());
        helper.assertTrue(building.lightSources == oldLights + 1,
            "the changed room must publish its newly measured light count");
        helper.assertTrue(plaque.revision() == beforeChangeRevision + 1
                && plaque.surveyCommitCountForTest() == beforeChangeCommits + 1,
            "one real survey change must commit and bump revision exactly once");
        helper.assertTrue(plaque.surveyUpdatePacketCountForTest()
                == beforeChangePackets + 1,
            "one real survey change must author exactly one BE update packet");
        helper.assertTrue(data.isDirty(),
            "one real persistent building change must dirty SettlementSavedData");
        helper.assertTrue(plaque.bedAssignmentAttemptCountForTest() == beforeChangeBeds,
            "a light-only change must not run a needless bed-assignment pass");

        int changedRevision = plaque.revision();
        int changedCommits = plaque.surveyCommitCountForTest();
        int changedPackets = plaque.surveyUpdatePacketCountForTest();
        data.setDirty(false);
        plaque.survey(helper.getLevel());
        helper.assertTrue(plaque.revision() == changedRevision
                && plaque.surveyCommitCountForTest() == changedCommits
                && plaque.surveyUpdatePacketCountForTest() == changedPackets
                && !data.isDirty(),
            "the first survey after the real update must be silent again");
        helper.succeed();
    }

    /**
     * One hundred same-tick courier reports must produce one aggregate pass,
     * not one full-map pass per courier. Stable heartbeats and an empty idle
     * plaque do no maintenance; the shared TTL still expires every report.
     */
    @GameTest(template = "empty16", timeoutTicks = 300,
        batch = "plaque_logistics_coalesced_100_heartbeats")
    public void courierHeartbeatsCoalesceToOneBoundedMaintenancePass(
            GameTestHelper helper) {
        BlockState plaqueState = ModBlocks.PLAQUE.get().defaultBlockState();
        PlaqueBlockEntity idle = new PlaqueBlockEntity(
            helper.absolutePos(new BlockPos(1, 1, 1)), plaqueState);
        long idleStart = helper.getLevel().getGameTime();
        for (int tick = 0; tick < 200; tick++) {
            idle.tickLogisticsForTest(helper.getLevel(), idleStart + tick);
        }
        helper.assertTrue(idle.logisticsReportCountForTest() == 0
                && idle.logisticsPrunePassCountForTest() == 0
                && idle.logisticsAggregatePassCountForTest() == 0,
            "an empty unchanged plaque must perform zero prune/aggregate passes");

        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        Settlement settlement = new Settlement(UUID.randomUUID(), "Courierphase",
            helper.absolutePos(new BlockPos(8, 1, 8)));
        settlement.radius = 20;
        data.settlements.put(settlement.id, settlement);

        BlockPos hutOrigin = new BlockPos(6, 0, 6);
        buildHut(helper, hutOrigin);
        BlockPos plaqueRel = hutOrigin.offset(1, 2, -1);
        helper.setBlock(plaqueRel, plaqueState
            .setValue(PlaqueBlock.FACING, Direction.NORTH));
        BlockPos plaquePos = helper.absolutePos(plaqueRel);
        if (!(helper.getLevel().getBlockEntity(plaquePos)
            instanceof PlaqueBlockEntity plaque)) {
            helper.fail("fixture: plaque block entity missing");
            return;
        }
        ItemStack plan = PlaqueItemData.stamped(
            new ItemStack(ModItems.BUILD_PLAN.get()), BuildingType.HOUSE);
        helper.assertTrue(plaque.insertPlan(helper.getLevel(), plan)
                && plaque.state() == PlaqueState.LINKED_VALID,
            "fixture: the complete hut must register a valid house");

        UUID[] couriers = new UUID[100];
        long now = helper.getLevel().getGameTime();
        int aggregateBefore = plaque.logisticsAggregatePassCountForTest();
        int pruneBefore = plaque.logisticsPrunePassCountForTest();
        for (int index = 0; index < couriers.length; index++) {
            couriers[index] = new UUID(0L, index + 1L);
            plaque.setLogisticsStopReason(helper.getLevel(), couriers[index],
                index == couriers.length - 1
                    ? StopReason.NO_WAREHOUSE_SPACE
                    : StopReason.WAITING_INPUT);
        }
        helper.assertTrue(plaque.logisticsReportCountForTest() == 100,
            "every courier heartbeat must retain its own TTL report");
        helper.assertTrue(plaque.logisticsAggregatePassCountForTest() == aggregateBefore
                && plaque.logisticsPrunePassCountForTest() == pruneBefore,
            "report ingestion must not scan the full courier map");

        plaque.tickLogisticsForTest(helper.getLevel(), now);
        plaque.tickLogisticsForTest(helper.getLevel(), now);
        helper.assertTrue(plaque.logisticsAggregatePassCountForTest()
                == aggregateBefore + 1
                && plaque.logisticsPrunePassCountForTest() == pruneBefore,
            "100 reports and repeated same-tick maintenance must aggregate exactly once");
        helper.assertTrue(plaque.logisticsStopReason()
                == StopReason.NO_WAREHOUSE_SPACE
                && helper.getBlockState(plaqueRel).getValue(PlaqueBlock.GLOW)
                    == PlaqueBlock.Glow.RED,
            "the coalesced result must preserve red-over-amber severity");

        helper.runAfterDelay(60, () -> {
            long renewedAt = helper.getLevel().getGameTime();
            helper.assertTrue(renewedAt - now >= 60L,
                "heartbeat renewal must use genuinely advanced game time");
            for (int index = 0; index < couriers.length; index++) {
                plaque.setLogisticsStopReason(helper.getLevel(), couriers[index],
                    index == couriers.length - 1
                        ? StopReason.NO_WAREHOUSE_SPACE
                        : StopReason.WAITING_INPUT);
            }
            plaque.tickLogisticsForTest(helper.getLevel(), renewedAt);
            helper.assertTrue(plaque.logisticsAggregatePassCountForTest()
                    == aggregateBefore + 1
                    && plaque.logisticsPrunePassCountForTest() == pruneBefore,
                "unchanged later-tick heartbeats must renew TTL with zero aggregate/prune");

            helper.runAfterDelay(60, () -> {
                long afterOriginalTtl = helper.getLevel().getGameTime();
                helper.assertTrue(afterOriginalTtl > now + 100L
                        && afterOriginalTtl < renewedAt + 100L,
                    "observation must be past the original TTL but before renewed TTL");
                helper.assertTrue(plaque.logisticsReportCountForTest() == 100
                        && plaque.logisticsStopReason()
                            == StopReason.NO_WAREHOUSE_SPACE,
                    "real later-tick heartbeats must keep all reports alive past "
                        + "their original expiry");
                helper.assertTrue(plaque.logisticsPrunePassCountForTest()
                        == pruneBefore + 1
                        && plaque.logisticsAggregatePassCountForTest()
                            == aggregateBefore + 1,
                    "the conservative old deadline may prune once, but must not "
                        + "re-aggregate unchanged renewed reports");

                helper.runAfterDelay(60, () -> {
                    long afterRenewedTtl = helper.getLevel().getGameTime();
                    helper.assertTrue(afterRenewedTtl > renewedAt + 100L,
                        "final observation must be past the renewed TTL");
                    helper.assertTrue(plaque.logisticsReportCountForTest() == 0
                            && plaque.logisticsPrunePassCountForTest()
                                == pruneBefore + 2
                            && plaque.logisticsAggregatePassCountForTest()
                                == aggregateBefore + 2,
                        "one final bounded pass must expire all 100 renewed reports");
                    helper.assertTrue(plaque.logisticsStopReason() == StopReason.NONE
                            && helper.getBlockState(plaqueRel)
                                .getValue(PlaqueBlock.GLOW)
                                == PlaqueBlock.Glow.GREEN,
                        "the renewed TTL expiry must restore the valid green lamp");
                    helper.succeed();
                });
            });
        });
    }

    /** Builds the same enclosed, roofed 5x5 house shape used by the core tests. */
    private static void buildHut(GameTestHelper helper, BlockPos origin) {
        for (int x = 0; x <= 4; x++) {
            for (int z = 0; z <= 4; z++) {
                boolean wall = x == 0 || z == 0 || x == 4 || z == 4;
                for (int y = 1; y <= 3; y++) {
                    if (wall) {
                        helper.setBlock(origin.offset(x, y, z), Blocks.STONE_BRICKS);
                    }
                }
                helper.setBlock(origin.offset(x, 4, z), Blocks.STONE_BRICKS);
                helper.setBlock(origin.offset(x, 0, z), Blocks.STONE_BRICKS);
            }
        }
        helper.setBlock(origin.offset(2, 1, 0), Blocks.OAK_DOOR.defaultBlockState());
        helper.setBlock(origin.offset(2, 2, 0), Blocks.OAK_DOOR.defaultBlockState()
            .setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        helper.setBlock(origin.offset(2, 1, 2), Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.FOOT));
        helper.setBlock(origin.offset(2, 1, 3), Blocks.RED_BED.defaultBlockState()
            .setValue(BedBlock.FACING, Direction.NORTH)
            .setValue(BedBlock.PART, BedPart.HEAD));
        helper.setBlock(origin.offset(1, 2, 1), Blocks.TORCH);
    }
}
