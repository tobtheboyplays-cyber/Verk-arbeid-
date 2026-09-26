package com.hearthstead.settlement.workzone;

import com.mojang.authlib.GameProfile;
import com.hearthstead.Hearthstead;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.FarmerWorkGoal;
import com.hearthstead.entity.ai.GroundCollectionSession;
import com.hearthstead.entity.ai.LumbererWorkGoal;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.FoundingJourneyProgress;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.state.FoundingJourney;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/** Adversarial server-side contracts for the persisted exact 3D Work Zone. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public final class WorkZoneGameTests {

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_persistence")
    public void restartRoundTripPreservesExactBoundsAndRevision(
            GameTestHelper helper) {
        Settlement original = settlement(helper, new BlockPos(5, 2, 5),
            "Restart Zone");
        Building camp = building(original, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(2, 2, 2)));
        WorkZone zone = zone(helper, original, camp, WorkZone.Type.LUMBER,
            new BlockPos(1, 1, 1), new BlockPos(9, 11, 10), 1);
        helper.assertTrue(camp.commitWorkZone(0, zone),
            "restart fixture must commit revision one");

        SettlementSavedData disk = new SettlementSavedData();
        disk.settlements.put(original.id, original);
        CompoundTag root = disk.save(new CompoundTag(),
            helper.getLevel().registryAccess());
        Settlement loaded = SettlementSavedData.load(root,
            helper.getLevel().registryAccess()).settlements.get(original.id);
        Building loadedCamp = loaded == null ? null
            : loaded.buildings.stream().filter(b -> b.id.equals(camp.id))
                .findFirst().orElse(null);

        helper.assertTrue(loadedCamp != null
                && !loadedCamp.workZoneQuarantined()
                && loadedCamp.workZoneRevision() == 1
                && zone.equals(loadedCamp.workZone().orElse(null)),
            "restart must preserve exact identities, 3D bounds and revision");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_persistence")
    public void malformedCurrentStateQuarantinesWithoutRevisionReset(
            GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(2, 2, 2), "Malformed");
        Building camp = building(s, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        WorkZone zone = zone(helper, s, camp, WorkZone.Type.LUMBER,
            BlockPos.ZERO, new BlockPos(2, 3, 2), 1);
        helper.assertTrue(camp.commitWorkZone(0, zone), "setup commit failed");
        CompoundTag tag = camp.writeNbt();
        tag.getCompound("WorkZone").putInt("Revision", 9);

        Building loaded = Building.readNbt(tag,
            SettlementSavedData.CURRENT_DATA_VERSION);
        helper.assertTrue(loaded.workZoneQuarantined()
                && loaded.workZone().isEmpty()
                && loaded.workZoneRevision() == 1
                && !loaded.commitWorkZone(1, WorkZone.between(s.id, loaded.id,
                    WorkZone.Type.LUMBER,
                    helper.getLevel().dimension().location(), BlockPos.ZERO,
                    BlockPos.ZERO, 2)),
            "present malformed state must remain fail-closed, never reset/free");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_persistence")
    public void crossSettlementOwnerQuarantinesOnRootLoad(GameTestHelper helper) {
        Settlement owner = settlement(helper, new BlockPos(2, 2, 2), "Owner");
        Building camp = building(owner, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        WorkZone forged = WorkZone.between(UUID.randomUUID(), camp.id,
            WorkZone.Type.LUMBER, helper.getLevel().dimension().location(),
            helper.absolutePos(BlockPos.ZERO),
            helper.absolutePos(new BlockPos(2, 3, 2)), 1);
        helper.assertTrue(camp.commitWorkZone(0, forged),
            "standalone Building intentionally lacks its parent identity");

        Settlement loaded = Settlement.readNbt(owner.writeNbt(),
            SettlementSavedData.CURRENT_DATA_VERSION);
        Building loadedCamp = loaded.buildings.getFirst();
        helper.assertTrue(loadedCamp.workZoneQuarantined()
                && loadedCamp.workZone().isEmpty()
                && loadedCamp.workZoneRevision() == 1,
            "root settlement decode must quarantine a foreign owner identity");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_revision")
    public void twoPlayersAtSameRevisionOnlyFirstCommitWins(GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(2, 2, 2), "Race");
        Building camp = building(s, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        WorkZone first = zone(helper, s, camp, WorkZone.Type.LUMBER,
            BlockPos.ZERO, new BlockPos(2, 3, 2), 1);
        WorkZone replay = zone(helper, s, camp, WorkZone.Type.LUMBER,
            new BlockPos(1, 1, 1), new BlockPos(3, 4, 3), 1);

        helper.assertTrue(camp.commitWorkZone(0, first)
                && !camp.commitWorkZone(0, replay)
                && camp.workZoneRevision() == 1
                && first.equals(camp.workZone().orElse(null)),
            "same expected revision must conserve one exact committed state");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_no_mutation")
    public void previewValidationAndDiscardMutateNothing(GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(4, 3, 4), "Preview");
        Building camp = building(s, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        plantNaturalTree(helper, new BlockPos(3, 1, 3));
        WorkZone candidate = zone(helper, s, camp, WorkZone.Type.LUMBER,
            new BlockPos(1, 1, 1), new BlockPos(6, 7, 6), 1);
        ItemStack held = new ItemStack(ModItems.WORK_SCEPTER.get());

        helper.assertTrue(WorkZoneService.validateCandidate(helper.getLevel(),
                s, camp, candidate) == WorkZoneService.Result.APPLIED,
            "server preview fixture should validate");
        // Discard/cancel: intentionally do not invoke the sole commit endpoint.
        helper.assertTrue(camp.workZoneRevision() == 0
                && camp.workZone().isEmpty() && held.getCount() == 1
                && helper.getBlockState(new BlockPos(3, 2, 3)).is(Blocks.OAK_LOG),
            "preview/cancel must consume no item, revision or world block");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_height_retry")
    public void rejectedFarmHeightRetainsRetryStageThenValidHeightCommits(
            GameTestHelper helper) {
        Settlement settlement = settlement(helper, new BlockPos(7, 2, 7),
            "Height Retry");
        Building farm = building(settlement, BuildingType.FARMHOUSE,
            helper.absolutePos(new BlockPos(2, 1, 2)));
        SettlerEntity farmer = worker(helper, settlement, farm, Profession.FARMER,
            new BlockPos(3, 1, 3));
        helper.setBlock(new BlockPos(4, 1, 4), Blocks.FARMLAND);

        WorkZone invalidHeight = zone(helper, settlement, farm, WorkZone.Type.FARM,
            new BlockPos(3, 1, 3), new BlockPos(6, 1, 6), 1);
        WorkZone validHeight = zone(helper, settlement, farm, WorkZone.Type.FARM,
            new BlockPos(3, 1, 3), new BlockPos(6, 2, 6), 1);

        helper.assertTrue(WorkZoneService.validateCandidate(helper.getLevel(),
                settlement, farm, invalidHeight) == WorkZoneService.Result.FARM_HEIGHT_REQUIRED
                && WorkZoneService.rejectionStage(false, true, true)
                    == com.hearthstead.network.WorkZoneSnapshotPayload.Stage.CORNER_TWO,
            "a rejected height must retain both corners and return the client to the height step");
        helper.assertTrue(WorkZoneService.validateCandidate(helper.getLevel(),
                settlement, farm, validHeight) == WorkZoneService.Result.APPLIED
                && WorkZoneService.commitValidated(helper.getLevel(), settlement, farm,
                    farmer, validHeight) == WorkZoneService.Result.APPLIED
                && validHeight.equals(farm.workZone().orElse(null))
                && farm.workZoneRevision() == 1,
            "the next valid height must preview/commit normally without replacing either corner");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_no_mutation")
    public void staleCandidateFailsWithoutMutation(GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(2, 2, 2), "Stale");
        Building farm = building(s, BuildingType.FARMHOUSE,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        WorkZone existing = zone(helper, s, farm, WorkZone.Type.FARM,
            BlockPos.ZERO, new BlockPos(2, 2, 2), 1);
        helper.assertTrue(farm.commitWorkZone(0, existing), "setup commit failed");
        WorkZone stale = zone(helper, s, farm, WorkZone.Type.FARM,
            new BlockPos(1, 1, 1), new BlockPos(2, 2, 2), 1);

        helper.assertTrue(WorkZoneService.validateCandidate(helper.getLevel(),
                s, farm, stale) == WorkZoneService.Result.STALE
                && existing.equals(farm.workZone().orElse(null))
                && farm.workZoneRevision() == 1,
            "stale validation must leave exact committed state untouched");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_identity")
    public void crossSettlementCandidateFailsWithoutMutation(GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(2, 2, 2), "Local");
        Building farm = building(s, BuildingType.FARMHOUSE,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        WorkZone foreign = WorkZone.between(UUID.randomUUID(), farm.id,
            WorkZone.Type.FARM, helper.getLevel().dimension().location(),
            helper.absolutePos(BlockPos.ZERO),
            helper.absolutePos(new BlockPos(2, 2, 2)), 1);

        assertRejectedWithoutState(helper, s, farm, foreign,
            WorkZoneService.Result.WRONG_SETTLEMENT);
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_identity")
    public void crossDimensionCandidateFailsWithoutMutation(GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(2, 2, 2), "Dimension");
        Building farm = building(s, BuildingType.FARMHOUSE,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        WorkZone foreign = WorkZone.between(s.id, farm.id, WorkZone.Type.FARM,
            ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"),
            helper.absolutePos(BlockPos.ZERO),
            helper.absolutePos(new BlockPos(2, 2, 2)), 1);

        assertRejectedWithoutState(helper, s, farm, foreign,
            WorkZoneService.Result.WRONG_DIMENSION);
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_bounds")
    public void linkedOutlyingWorkZonePassesWithoutWideningItsLimits(
            GameTestHelper helper) {
        BlockPos center = helper.absolutePos(new BlockPos(2, 2, 2));
        Settlement s = settlementAt(helper, center, "Outlying", 1);
        BlockPos outlying = center.offset(4, 0, 0);
        Building farm = building(s, BuildingType.FARMHOUSE, outlying);
        helper.getLevel().setBlockAndUpdate(outlying, Blocks.FARMLAND.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(outlying.above(), Blocks.WHEAT.defaultBlockState());
        WorkZone zone = WorkZone.between(s.id, farm.id, WorkZone.Type.FARM,
            helper.getLevel().dimension().location(), outlying,
            outlying.offset(1, 1, 1), 1);

        helper.assertTrue(!s.insideBox(zone.min(), zone.max()),
            "fixture: the bounded farm zone must sit outside the city radius");
        helper.assertTrue(zone.withinPersistentLimits()
                && WorkZoneService.validateCandidate(helper.getLevel(), s, farm, zone)
                    == WorkZoneService.Result.APPLIED,
            "an exact linked building may keep its bounded work zone outside the city radius");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_bounds")
    public void oversizedWidthFailsWithoutMutation(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(2, 2, 2));
        Settlement s = settlementAt(helper, base.offset(24, 0, 0), "Wide", 160);
        Building farm = building(s, BuildingType.FARMHOUSE, base);
        WorkZone wide = WorkZone.between(s.id, farm.id, WorkZone.Type.FARM,
            helper.getLevel().dimension().location(), base, base.offset(48, 0, 0), 1);

        assertRejectedWithoutState(helper, s, farm, wide,
            WorkZoneService.Result.OVERSIZE);
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_bounds")
    public void oversizedHeightFailsWithoutMutation(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(2, 2, 2));
        Settlement s = settlementAt(helper, base.offset(0, 32, 0), "Tall", 160);
        Building farm = building(s, BuildingType.FARMHOUSE, base);
        WorkZone tall = WorkZone.between(s.id, farm.id, WorkZone.Type.FARM,
            helper.getLevel().dimension().location(), base, base.offset(0, 64, 0), 1);

        assertRejectedWithoutState(helper, s, farm, tall,
            WorkZoneService.Result.OVERSIZE);
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_bounds")
    public void oversizedVolumeFailsWithoutMutation(GameTestHelper helper) {
        BlockPos base = helper.absolutePos(new BlockPos(2, 2, 2));
        Settlement s = settlementAt(helper, base.offset(15, 8, 15),
            "Volume", 160);
        Building farm = building(s, BuildingType.FARMHOUSE, base);
        WorkZone volume = WorkZone.between(s.id, farm.id, WorkZone.Type.FARM,
            helper.getLevel().dimension().location(), base,
            base.offset(31, 16, 31), 1); // 32*17*32 > synchronous budget

        assertRejectedWithoutState(helper, s, farm, volume,
            WorkZoneService.Result.OVERSIZE);
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_bounds")
    public void worldBorderFailureDoesNotLoadOrMutate(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        int x = (int) Math.floor(level.getWorldBorder().getMaxX()) + 2;
        BlockPos outside = new BlockPos(x, 64, 0);
        Settlement s = settlementAt(helper, outside, "Border", 32);
        Building farm = building(s, BuildingType.FARMHOUSE,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        WorkZone zone = WorkZone.between(s.id, farm.id, WorkZone.Type.FARM,
            level.dimension().location(), outside, outside, 1);

        assertRejectedWithoutState(helper, s, farm, zone,
            WorkZoneService.Result.WORLD_BORDER);
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_chunks")
    public void unloadedChunkFailureNeverForceLoads(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos unloaded = null;
        for (int chunkDistance = 16; chunkDistance <= 2048; chunkDistance *= 2) {
            int chunkX = (origin.getX() >> 4) + chunkDistance;
            int chunkZ = origin.getZ() >> 4;
            if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                unloaded = new BlockPos((chunkX << 4) + 1, 64,
                    (chunkZ << 4) + 1);
                break;
            }
        }
        if (unloaded == null) {
            helper.fail("could not locate an already-unloaded chunk for the contract");
            return;
        }
        int chunkX = unloaded.getX() >> 4;
        int chunkZ = unloaded.getZ() >> 4;
        Settlement s = settlementAt(helper, unloaded, "Unloaded", 32);
        Building farm = building(s, BuildingType.FARMHOUSE,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        WorkZone zone = WorkZone.between(s.id, farm.id, WorkZone.Type.FARM,
            level.dimension().location(), unloaded, unloaded.offset(1, 1, 1), 1);

        helper.assertTrue(!level.getChunkSource().hasChunk(chunkX, chunkZ),
            "precondition: candidate chunk must start unloaded");
        WorkZoneService.Result result = WorkZoneService.validateCandidate(
            level, s, farm, zone);
        helper.assertTrue(result == WorkZoneService.Result.UNLOADED
                && !level.getChunkSource().hasChunk(chunkX, chunkZ)
                && farm.workZoneRevision() == 0 && farm.workZone().isEmpty(),
            "validation must reject without acquiring or mutating the chunk");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_reach")
    public void bothCornerGatesUseDynamicVanillaBlockReach(
            GameTestHelper helper) {
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(),
            helper.getLevel(), new GameProfile(UUID.randomUUID(),
                "work-zone-reach"), ClientInformation.createDefault());
        BlockPos feet = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos corner = feet.offset(4, 0, 0);
        player.setPos(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D);
        var reach = player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
        helper.assertTrue(reach != null, "vanilla block reach attribute missing");

        reach.setBaseValue(2.0D);
        boolean shortReach = WorkZoneService.physicalReach(player, corner);
        double shortRayDistance = WorkZoneService.blockReachDistance(player);
        reach.setBaseValue(8.0D);
        boolean extendedReach = WorkZoneService.physicalReach(player, corner);
        double extendedRayDistance = WorkZoneService.blockReachDistance(player);

        helper.assertTrue(!shortReach && extendedReach
                && shortRayDistance == 2.0D && extendedRayDistance == 8.0D,
            "shared first/second-corner preflight and ray must follow the live vanilla attribute");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_chunks")
    public void liveRuntimeGuardRejectsUnloadedWithoutForceLoad(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos unloaded = null;
        for (int chunkDistance = 16; chunkDistance <= 2048; chunkDistance *= 2) {
            int chunkX = (origin.getX() >> 4) + chunkDistance;
            int chunkZ = origin.getZ() >> 4;
            if (!level.getChunkSource().hasChunk(chunkX, chunkZ)) {
                unloaded = new BlockPos((chunkX << 4) + 1, 64,
                    (chunkZ << 4) + 1);
                break;
            }
        }
        if (unloaded == null) {
            helper.fail("could not locate an unloaded runtime target");
            return;
        }
        int chunkX = unloaded.getX() >> 4;
        int chunkZ = unloaded.getZ() >> 4;
        WorkZone zone = WorkZone.between(UUID.randomUUID(), UUID.randomUUID(),
            WorkZone.Type.FARM, level.dimension().location(), unloaded,
            unloaded, 1);

        helper.assertTrue(!WorkZoneService.livePositionAllowed(level, zone,
                unloaded) && !level.getChunkSource().hasChunk(chunkX, chunkZ),
            "AI live preflight must reject without acquiring the target chunk");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_bounds")
    public void liveRuntimeGuardHonoursMovedWorldBorder(
            GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos target = helper.absolutePos(new BlockPos(2, 1, 2));
        WorkZone zone = WorkZone.between(UUID.randomUUID(), UUID.randomUUID(),
            WorkZone.Type.FARM, level.dimension().location(), target, target, 1);
        WorldBorder border = level.getWorldBorder();
        double oldX = border.getCenterX();
        double oldZ = border.getCenterZ();
        double oldSize = border.getSize();
        boolean rejected;
        try {
            border.setCenter(target.getX() + 1_000.0D,
                target.getZ() + 1_000.0D);
            border.setSize(2.0D);
            rejected = !WorkZoneService.livePositionAllowed(level, zone, target);
        } finally {
            border.setCenter(oldX, oldZ);
            border.setSize(oldSize);
        }

        helper.assertTrue(rejected,
            "a border move after commit must stop later AI reads/mutations");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_ai")
    public void lumberCollectionContactRejectsOutsideExactZone(
            GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(5, 2, 5),
            "Collection Bounds");
        Building camp = building(s, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(2, 1, 2)));
        WorkZone exact = zone(helper, s, camp, WorkZone.Type.LUMBER,
            new BlockPos(1, 1, 1), new BlockPos(6, 7, 6), 1);
        BlockPos inside = helper.absolutePos(new BlockPos(6, 2, 6));
        BlockPos outside = helper.absolutePos(new BlockPos(7, 2, 6));

        helper.assertTrue(LumbererWorkGoal.collectionTargetAllowed(
                helper.getLevel(), exact, inside)
                && !LumbererWorkGoal.collectionTargetAllowed(
                    helper.getLevel(), exact, outside),
            "selection, travel and contact must share exact 3D item authority");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_ai")
    public void movedPersistedDropOutsideZoneIsNeverRecoveredOrMutated(
            GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(5, 2, 5),
            "Moved Persisted Drop");
        Building camp = building(s, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(2, 1, 2)));
        SettlerEntity lumberer = worker(helper, s, camp, Profession.LUMBERER,
            new BlockPos(4, 1, 4));
        WorkZone exact = zone(helper, s, camp, WorkZone.Type.LUMBER,
            new BlockPos(1, 1, 1), new BlockPos(6, 7, 6), 1);
        BlockPos source = helper.absolutePos(new BlockPos(4, 2, 4));
        GroundCollectionSession original = new GroundCollectionSession(lumberer,
            stack -> stack.is(Items.OAK_LOG), 8);
        helper.assertTrue(original.spawnPhysical(helper.getLevel(), source,
                new ItemStack(Items.OAK_LOG)),
            "fixture must persist one owned physical drop");
        ItemEntity item = helper.getLevel().getEntitiesOfClass(ItemEntity.class,
            new AABB(source).inflate(1.0D), candidate ->
                candidate.getItem().is(Items.OAK_LOG)).stream()
            .findFirst().orElse(null);
        helper.assertTrue(item != null, "owned physical fixture item missing");
        BlockPos movedOutside = helper.absolutePos(new BlockPos(10, 2, 10));
        item.setPos(movedOutside.getX() + 0.5D, movedOutside.getY() + 0.5D,
            movedOutside.getZ() + 0.5D);
        item.setNoPickUpDelay();

        GroundCollectionSession restarted = new GroundCollectionSession(lumberer,
            stack -> stack.is(Items.OAK_LOG), 8);
        AABB exactBounds = new AABB(exact.min().getX(), exact.min().getY(),
            exact.min().getZ(), exact.max().getX() + 1.0D,
            exact.max().getY() + 1.0D, exact.max().getZ() + 1.0D);
        int recovered = restarted.recoverOwned(helper.getLevel(), exactBounds,
            exact::contains,
            pos -> LumbererWorkGoal.collectionTargetAllowed(
                helper.getLevel(), exact, pos));

        helper.assertTrue(recovered == 0 && restarted.trackedCount() == 0
                && item.isAlive() && item.getItem().getCount() == 1
                && !item.hasPickUpDelay(),
            "moved persisted item outside exact live zone must not be re-indexed, re-claimed or mutated");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_contents")
    public void lumberZoneWithoutNaturalTreeFailsClosed(GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(4, 3, 4), "No Tree");
        Building camp = building(s, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        WorkZone empty = zone(helper, s, camp, WorkZone.Type.LUMBER,
            new BlockPos(1, 1, 1), new BlockPos(6, 7, 6), 1);
        assertRejectedWithoutState(helper, s, camp, empty,
            WorkZoneService.Result.NO_NATURAL_TREE);
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_contents")
    public void farmZoneWithoutFieldFailsClosed(GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(4, 3, 4), "No Field");
        Building farm = building(s, BuildingType.FARMHOUSE,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        WorkZone empty = zone(helper, s, farm, WorkZone.Type.FARM,
            new BlockPos(1, 1, 1), new BlockPos(6, 7, 6), 1);
        assertRejectedWithoutState(helper, s, farm, empty,
            WorkZoneService.Result.NO_FIELD);
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_contents")
    public void naturalTreeProofIsAcceptedWithoutMutation(GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(4, 3, 4), "Tree");
        Building camp = building(s, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        plantNaturalTree(helper, new BlockPos(3, 1, 3));
        WorkZone candidate = zone(helper, s, camp, WorkZone.Type.LUMBER,
            new BlockPos(1, 1, 1), new BlockPos(6, 7, 6), 1);

        helper.assertTrue(WorkZoneService.validateCandidate(helper.getLevel(),
                s, camp, candidate) == WorkZoneService.Result.APPLIED
                && camp.workZoneRevision() == 0 && camp.workZone().isEmpty(),
            "natural tree proves eligibility but validation alone must not commit");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_contents")
    public void physicalFieldProofIsAcceptedWithoutMutation(GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(4, 3, 4), "Field");
        Building farm = building(s, BuildingType.FARMHOUSE,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        helper.setBlock(new BlockPos(3, 1, 3), Blocks.FARMLAND);
        WorkZone candidate = zone(helper, s, farm, WorkZone.Type.FARM,
            new BlockPos(1, 1, 1), new BlockPos(6, 4, 6), 1);

        helper.assertTrue(WorkZoneService.validateCandidate(helper.getLevel(),
                s, farm, candidate) == WorkZoneService.Result.APPLIED
                && farm.workZoneRevision() == 0 && farm.workZone().isEmpty(),
            "farmland proves eligibility but validation alone must not commit");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_progression")
    public void preTimberRightsTargetFailsWithoutMutation(GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(2, 2, 2), "Locked");
        Development.revisionOf(helper.getLevel(), s); // initialize before camp
        Building camp = building(s, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(1, 1, 1)));

        helper.assertTrue(WorkZoneService.validateTarget(helper.getLevel(), s,
                camp, WorkZone.Type.LUMBER) == WorkZoneService.Result.TECH_LOCKED
                && camp.workZoneRevision() == 0 && camp.workZone().isEmpty(),
            "a physical camp cannot spoof settlement-scoped Timber Rights");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_progression")
    public void grandfatheredTimberKnowledgeAllowsExactTarget(GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(2, 2, 2), "Learned");
        Building camp = building(s, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        // Initialize after the real persisted building: this is the explicit
        // shipped-world grandfather path, not a fresh-play recipe shortcut.
        Development.revisionOf(helper.getLevel(), s);

        helper.assertTrue(WorkZoneService.validateTarget(helper.getLevel(), s,
                camp, WorkZone.Type.LUMBER) == WorkZoneService.Result.APPLIED,
            "legitimate Timber Rights knowledge must open the registered camp");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_identity")
    public void duplicateBuildingIdsFailClosed(GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(2, 2, 2), "Duplicate");
        UUID duplicated = UUID.randomUUID();
        UUID workerId = UUID.randomUUID();
        Building first = buildingWithId(s, duplicated, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        buildingWithId(s, duplicated, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(2, 1, 2)));
        first.workers.add(workerId);

        helper.assertTrue(WorkZoneService.validateTarget(helper.getLevel(), s,
                first, WorkZone.Type.LUMBER)
                    == WorkZoneService.Result.WRONG_WORKPLACE
                && Employment.employerOf(s, workerId) == null
                && first.workZoneRevision() == 0 && first.workZone().isEmpty(),
            "ambiguous persistent identity must not select a target or employer");
        helper.succeed();
    }

    @GameTest(template = "empty5", timeoutTicks = 100,
        batch = "work_zone_persistence")
    public void levelAwareLoadQuarantinesWrongDimensionBeforeWorkerUse(
            GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(2, 2, 2),
            "Wrong Dimension");
        Building camp = building(s, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        WorkZone wrong = WorkZone.between(s.id, camp.id, WorkZone.Type.LUMBER,
            ResourceLocation.fromNamespaceAndPath("minecraft", "the_nether"),
            helper.absolutePos(new BlockPos(1, 1, 1)),
            helper.absolutePos(new BlockPos(3, 3, 3)), 1);
        helper.assertTrue(camp.commitWorkZone(0, wrong),
            "wrong-dimension persistence fixture must commit before load gate");
        WorkZoneService.Result beforeLoad = WorkZoneService.validateTarget(
            helper.getLevel(), s, camp, WorkZone.Type.LUMBER);

        SettlementSavedData.get(helper.getLevel());
        helper.assertTrue(beforeLoad == WorkZoneService.Result.CORRUPT
                && camp.workZoneQuarantined() && camp.workZone().isEmpty()
                && camp.workZoneRevision() == 1,
            "selection fails closed and level-aware SavedData access quarantines without revision reset");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_journey")
    public void journeyAdvancesOnlyFromExactCommittedLumberZone(
            GameTestHelper helper) {
        Settlement s = settlement(helper, new BlockPos(4, 3, 4), "Journey");
        Building camp = building(s, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(1, 1, 1)));
        s.foundingJourney = FoundingJourney.fresh();
        helper.assertTrue(s.foundingJourney.noteLumberCampLinked()
                && s.foundingJourney.noteLumbererHired()
                && s.foundingJourney.phase()
                    == FoundingJourney.Phase.SET_LUMBER_ZONE,
            "fixture must stop at the mandatory zone phase");
        plantNaturalTree(helper, new BlockPos(3, 1, 3));
        WorkZone candidate = zone(helper, s, camp, WorkZone.Type.LUMBER,
            new BlockPos(1, 1, 1), new BlockPos(6, 7, 6), 1);

        helper.assertTrue(!FoundingJourneyProgress.noteWorkZoneCommitted(
                helper.getLevel(), s, camp, candidate)
                && s.foundingJourney.revision() == 2,
            "preview/uncommitted candidate must not teach the log step");
        helper.assertTrue(WorkZoneService.validateCandidate(helper.getLevel(),
                s, camp, candidate) == WorkZoneService.Result.APPLIED
                && camp.commitWorkZone(0, candidate)
                && FoundingJourneyProgress.noteWorkZoneCommitted(
                    helper.getLevel(), s, camp, candidate)
                && s.foundingJourney.phase()
                    == FoundingJourney.Phase.DELIVER_FIRST_LOG
                && s.foundingJourney.revision() == 3
                && !FoundingJourneyProgress.noteWorkZoneCommitted(
                    helper.getLevel(), s, camp, candidate),
            "only the exact compare-and-committed Lumber zone advances once");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_ai")
    public void lumbererWithoutZoneIsTruthfullyIdle(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        Settlement s = settlement(helper, new BlockPos(4, 2, 4), "Idle Lumber");
        Building camp = building(s, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(2, 1, 2)));
        SettlerEntity lumberer = worker(helper, s, camp, Profession.LUMBERER,
            new BlockPos(4, 1, 4));
        LumbererWorkGoal goal = new LumbererWorkGoal(lumberer);

        helper.assertTrue(!goal.canUse()
                && lumberer.logisticsStopReason() == StopReason.NO_WORK_ZONE
                && camp.workZoneRevision() == 0 && camp.workZone().isEmpty(),
            "no-zone must be a truthful idle state, not legacy fallback work");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_ai")
    public void farmerWithoutZoneIsTruthfullyIdle(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        Settlement s = settlement(helper, new BlockPos(4, 2, 4), "Idle Farm");
        Building farm = building(s, BuildingType.FARMHOUSE,
            helper.absolutePos(new BlockPos(2, 1, 2)));
        SettlerEntity farmer = worker(helper, s, farm, Profession.FARMER,
            new BlockPos(4, 1, 4));
        FarmerWorkGoal goal = new FarmerWorkGoal(farmer);

        helper.assertTrue(!goal.canUse()
                && farmer.logisticsStopReason() == StopReason.NO_WORK_ZONE
                && farm.workZoneRevision() == 0 && farm.workZone().isEmpty(),
            "no-zone farmer must expose NO_WORK_ZONE and mutate nothing");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_ai")
    public void lumbererCannotClaimTreeOutsideExactBounds(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        Settlement s = settlement(helper, new BlockPos(5, 2, 5), "Bound Lumber");
        Building camp = building(s, BuildingType.LUMBER_CAMP,
            helper.absolutePos(new BlockPos(2, 1, 2)));
        SettlerEntity lumberer = worker(helper, s, camp, Profession.LUMBERER,
            new BlockPos(4, 1, 4));
        lumberer.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_AXE));
        WorkZone exact = zone(helper, s, camp, WorkZone.Type.LUMBER,
            new BlockPos(1, 1, 1), new BlockPos(6, 7, 6), 1);
        helper.assertTrue(camp.commitWorkZone(0, exact), "zone setup failed");
        plantNaturalTree(helper, new BlockPos(11, 1, 11));
        LumbererWorkGoal goal = new LumbererWorkGoal(lumberer);

        helper.assertTrue(!goal.canUse()
                && lumberer.logisticsStopReason() == StopReason.NO_VALID_TARGET
                && helper.getBlockState(new BlockPos(11, 2, 11)).is(Blocks.OAK_LOG),
            "outside tree may not be searched, claimed or mutated");
        helper.succeed();
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "work_zone_ai")
    public void farmerCannotHarvestCropOutsideExactBounds(GameTestHelper helper) {
        helper.getLevel().setDayTime(2000);
        Settlement s = settlement(helper, new BlockPos(5, 2, 5), "Bound Farm");
        Building farm = building(s, BuildingType.FARMHOUSE,
            helper.absolutePos(new BlockPos(2, 1, 2)));
        SettlerEntity farmer = worker(helper, s, farm, Profession.FARMER,
            new BlockPos(4, 1, 4));
        farmer.setItemSlot(EquipmentSlot.MAINHAND,
            new ItemStack(Items.IRON_HOE));
        WorkZone exact = zone(helper, s, farm, WorkZone.Type.FARM,
            new BlockPos(1, 1, 1), new BlockPos(6, 5, 6), 1);
        helper.assertTrue(farm.commitWorkZone(0, exact), "zone setup failed");
        helper.setBlock(new BlockPos(11, 1, 11), Blocks.FARMLAND);
        helper.setBlock(new BlockPos(11, 2, 11),
            Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, 7));
        FarmerWorkGoal goal = new FarmerWorkGoal(farmer);

        helper.assertTrue(!goal.canUse()
                && farmer.logisticsStopReason() == StopReason.NO_VALID_TARGET
                && helper.getBlockState(new BlockPos(11, 2, 11))
                    .getValue(CropBlock.AGE) == 7,
            "outside crop may not be searched, claimed, harvested or replanted");
        helper.succeed();
    }

    private static void assertRejectedWithoutState(GameTestHelper helper,
                                                   Settlement settlement,
                                                   Building building,
                                                   WorkZone candidate,
                                                   WorkZoneService.Result expected) {
        WorkZoneService.Result actual = WorkZoneService.validateCandidate(
            helper.getLevel(), settlement, building, candidate);
        helper.assertTrue(actual == expected
                && building.workZoneRevision() == 0
                && building.workZone().isEmpty(),
            "expected " + expected + " with exact no-mutation, got " + actual);
        helper.succeed();
    }

    private static Settlement settlement(GameTestHelper helper,
                                         BlockPos centerRelative, String name) {
        return settlementAt(helper, helper.absolutePos(centerRelative), name, 96);
    }

    private static Settlement settlementAt(GameTestHelper helper, BlockPos center,
                                           String name, int radius) {
        Settlement settlement = new Settlement(UUID.randomUUID(), name, center);
        settlement.radius = radius;
        SettlementSavedData data = SettlementSavedData.get(helper.getLevel());
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        return settlement;
    }

    private static Building building(Settlement settlement, BuildingType type,
                                     BlockPos plaque) {
        return buildingWithId(settlement, UUID.randomUUID(), type, plaque);
    }

    private static Building buildingWithId(Settlement settlement, UUID id,
                                           BuildingType type, BlockPos plaque) {
        Building building = new Building(id, type, plaque, plaque,
            new BoundingBox(plaque));
        building.valid = true;
        settlement.buildings.add(building);
        return building;
    }

    private static WorkZone zone(GameTestHelper helper, Settlement settlement,
                                 Building building, WorkZone.Type type,
                                 BlockPos firstRelative, BlockPos secondRelative,
                                 int revision) {
        return WorkZone.between(settlement.id, building.id, type,
            helper.getLevel().dimension().location(),
            helper.absolutePos(firstRelative), helper.absolutePos(secondRelative),
            revision);
    }

    private static void plantNaturalTree(GameTestHelper helper,
                                         BlockPos dirtRelative) {
        helper.setBlock(dirtRelative, Blocks.DIRT);
        BlockPos base = dirtRelative.above();
        helper.setBlock(base, Blocks.OAK_LOG);
        helper.setBlock(base.above(), Blocks.OAK_LOG);
        BlockPos crown = base.above();
        helper.setBlock(crown.north(), Blocks.OAK_LEAVES);
        helper.setBlock(crown.south(), Blocks.OAK_LEAVES);
        helper.setBlock(crown.east(), Blocks.OAK_LEAVES);
        helper.setBlock(crown.west(), Blocks.OAK_LEAVES);
    }

    private static SettlerEntity worker(GameTestHelper helper,
                                        Settlement settlement,
                                        Building employer,
                                        Profession profession,
                                        BlockPos relative) {
        SettlerEntity worker = helper.spawn(ModEntities.SETTLER.get(), relative);
        worker.setSettlerName("Zone Worker");
        worker.bindTo(settlement.id, settlement.center);
        worker.setEnergy(100.0F);
        worker.assignProfession(profession);
        settlement.putRecord(worker.getUUID(), "Zone Worker", profession);
        employer.workers.add(worker.getUUID());
        return worker;
    }
}
