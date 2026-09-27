package com.hearthstead.qa;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Terrain preflight only: it must not author a floor until every cell passes. */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaidQaGroundedSiteGameTests {

    // Keep the wide terrain-only fixture above other batches' physical shells.
    private static final int FLOOR_Y = 80;

    private static boolean surveyed(int x, int z) {
        return x >= 0 && x <= 31 && z >= 0 && z <= 31
            || x >= -48 && x < 0 && z >= 0 && z <= 2
            || x >= 22 && x <= 24 && z >= -48 && z < 0
            || x > 31 && x <= 48 && z >= 10 && z <= 12
            || x >= 22 && x <= 24 && z > 31 && z <= 48
            || x == -49 && z >= -1 && z <= 3
            || z == -49 && x >= 21 && x <= 25
            || x == 49 && z >= 9 && z <= 13
            || z == 49 && x >= 21 && x <= 25;
    }

    private static BlockPos absolute(GameTestHelper helper, int x, int y, int z) {
        return helper.absolutePos(new BlockPos(x, y, z));
    }

    private static void dryPlateau(GameTestHelper helper) {
        for (int x = -49; x <= 49; x++) for (int z = -49; z <= 49; z++) {
            if (!surveyed(x, z)) continue;
            for (int y = FLOOR_Y - 4; y <= FLOOR_Y + 8; y++) {
                helper.getLevel().setBlockAndUpdate(absolute(helper, x, y, z),
                    Blocks.AIR.defaultBlockState());
            }
            helper.getLevel().setBlockAndUpdate(absolute(helper, x, FLOOR_Y, z),
                Blocks.GRASS_BLOCK.defaultBlockState());
        }
    }

    private static void setNaturalSupport(GameTestHelper helper, int x, int z,
                                          int supportY) {
        for (int y = FLOOR_Y - 4; y <= FLOOR_Y + 8; y++) {
            helper.getLevel().setBlockAndUpdate(absolute(helper, x, y, z),
                Blocks.AIR.defaultBlockState());
        }
        helper.getLevel().setBlockAndUpdate(absolute(helper, x, supportY, z),
            Blocks.GRASS_BLOCK.defaultBlockState());
    }

    private static int feet(GameTestHelper helper, BlockPos pos) {
        return helper.getLevel().getHeight(
            net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
            pos.getX(), pos.getZ());
    }

    /** Leaves the final natural route rows one full support block lower. */
    private static void lowerEntryApronsOneStep(GameTestHelper helper) {
        for (int x = -49; x <= 49; x++) for (int z = -49; z <= 49; z++) {
            boolean apron = x == -49 && z >= -1 && z <= 3
                || z == -49 && x >= 21 && x <= 25
                || x == 49 && z >= 9 && z <= 13
                || z == 49 && x >= 21 && x <= 25;
            if (!apron) continue;
            helper.getLevel().setBlockAndUpdate(absolute(helper, x, FLOOR_Y, z),
                Blocks.AIR.defaultBlockState());
            helper.getLevel().setBlockAndUpdate(absolute(helper, x, FLOOR_Y - 1, z),
                Blocks.GRASS_BLOCK.defaultBlockState());
            for (int y = FLOOR_Y + 1; y <= FLOOR_Y + 6; y++) {
                helper.getLevel().setBlockAndUpdate(absolute(helper, x, y, z),
                    Blocks.AIR.defaultBlockState());
            }
        }
    }

    private static BlockPos probeWithoutWriting(GameTestHelper helper, BlockPos hint) {
        var before = new java.util.LinkedHashMap<BlockPos, net.minecraft.world.level.block.state.BlockState>();
        for (int x = -49; x <= 49; x++) for (int z = -49; z <= 49; z++) {
            if (!surveyed(x, z)) continue;
            for (int y = FLOOR_Y - 1; y <= FLOOR_Y + 8; y++) {
                BlockPos pos = absolute(helper, x, y, z);
                before.put(pos, helper.getLevel().getBlockState(pos));
            }
        }
        BlockPos result = RaidQaFixtureService.groundedSiteAt(helper.getLevel(), hint);
        for (var entry : before.entrySet()) {
            helper.assertTrue(helper.getLevel().getBlockState(entry.getKey()).equals(entry.getValue()),
                "terrain survey must preserve every checked cell: " + entry.getKey());
        }
        return result;
    }

    @GameTest(template = "empty16", timeoutTicks = 100,
        batch = "raidqa_grounded_preflight")
    public void groundedPreflightRejectsWetObstructedAndSteepSitesWithoutWriting(
            GameTestHelper helper) {
        BlockPos hint = absolute(helper, 0, FLOOR_Y + 1, 0);
        BlockPos westPath = absolute(helper, -48, FLOOR_Y, 1);
        BlockPos westApron = absolute(helper, -49, FLOOR_Y, 1);

        dryPlateau(helper);
        BlockPos zeroStepOrigin = probeWithoutWriting(helper, hint);
        helper.assertTrue(zeroStepOrigin != null
                && zeroStepOrigin.equals(absolute(helper, 0, FLOOR_Y, 0)),
            "grounded origin must be the natural support plane, not heightmap feet");
        RaidQaFixtureService.groundedFloor(helper.getLevel(), zeroStepOrigin);
        helper.assertTrue(helper.getLevel().getBlockState(westPath).is(Blocks.POLISHED_ANDESITE),
            "the established west path must remain at its authored relative floor position");
        helper.assertTrue(feet(helper, westPath) == feet(helper, westApron),
            "a common-height natural apron must meet the authored path without a step");

        dryPlateau(helper);
        lowerEntryApronsOneStep(helper);
        BlockPos oneStepOrigin = probeWithoutWriting(helper, hint);
        helper.assertTrue(oneStepOrigin != null
                && oneStepOrigin.equals(absolute(helper, 0, FLOOR_Y, 0)),
            "one-lower aprons must retain the shared natural support-plane origin");
        RaidQaFixtureService.groundedFloor(helper.getLevel(), oneStepOrigin);
        helper.assertTrue(helper.getLevel().getBlockState(westPath).is(Blocks.POLISHED_ANDESITE),
            "the established west path must remain at its authored relative floor position");
        helper.assertTrue(helper.getLevel().getBlockState(
                absolute(helper, -49, FLOOR_Y - 1, 1)).is(Blocks.GRASS_BLOCK),
            "the one-lower west apron must keep its real natural support");
        helper.assertTrue(feet(helper, westPath) == feet(helper, westApron) + 1,
            "a one-lower natural apron must meet the authored path at exactly one step");

        // The west lane's north shoulder rises two blocks, but its centre strip
        // still connects the actual west gate to the natural apron at <=1 steps.
        // A route proof must accept this safely walkable three-wide road.
        dryPlateau(helper);
        for (int x = -48; x <= -1; x++) setNaturalSupport(helper, x, 0, FLOOR_Y + 2);
        BlockPos shoulderOrigin = probeWithoutWriting(helper, hint);
        helper.assertTrue(shoulderOrigin != null,
            "a two-level lane shoulder must not reject its separate safe centre route");
        RaidQaFixtureService.groundedFloor(helper.getLevel(), shoulderOrigin);
        helper.assertTrue(helper.getLevel().getBlockState(
                absolute(helper, -24, FLOOR_Y + 2, 0)).is(Blocks.COBBLESTONE)
                && helper.getLevel().getBlockState(westPath).is(Blocks.POLISHED_ANDESITE),
            "the bumpy shoulder and the centre strip must author at their own captured supports");
        // This wide 99x99 survey is deliberately sequential: empty16 test structures share
        // the GameTest server world, so a second same-batch method could overlap its terrain.
        dryPlateau(helper);
        BlockPos untouched = absolute(helper, 4, FLOOR_Y, 4);
        helper.assertTrue(probeWithoutWriting(helper, hint) != null,
            "a dry compact plateau with level natural entry aprons should pass");
        helper.assertTrue(helper.getLevel().getBlockState(untouched).is(Blocks.GRASS_BLOCK),
            "a passing no-write survey must preserve the absolute terrain cell");

        // A Settler is 1.95 blocks tall. This canopy begins above its two
        // walking blocks on a natural exterior lane, so it must not reject an
        // otherwise dry, one-step route or be cleared by the read-only probe.
        dryPlateau(helper);
        BlockPos laneCanopy = absolute(helper, -10, FLOOR_Y + 3, 1);
        helper.getLevel().setBlockAndUpdate(laneCanopy, Blocks.OAK_LEAVES.defaultBlockState());
        helper.assertTrue(probeWithoutWriting(helper, hint) != null,
            "a leaf canopy above two walking blocks in an exterior lane must pass");
        helper.assertTrue(helper.getLevel().getBlockState(laneCanopy).is(Blocks.OAK_LEAVES),
            "the accepted exterior canopy must remain natural and unmodified");

        // A generated-style tree may be cleared only in the compound. Its log
        // base is rooted in dirt-tagged soil and its four leaves are explicitly
        // non-persistent; the probe itself must remain no-write.
        dryPlateau(helper);
        BlockPos compoundLog = absolute(helper, 8, FLOOR_Y + 1, 8);
        helper.getLevel().setBlockAndUpdate(compoundLog, Blocks.OAK_LOG.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(compoundLog.above(), Blocks.OAK_LOG.defaultBlockState());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            helper.getLevel().setBlockAndUpdate(compoundLog.above(2).offset(dx, 0, dz),
                Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, false));
        }
        BlockPos treeOrigin = probeWithoutWriting(helper, hint);
        helper.assertTrue(treeOrigin != null,
            "a bounded natural tree in the authored compound must pass preflight");
        helper.assertTrue(helper.getLevel().getBlockState(compoundLog).is(Blocks.OAK_LOG),
            "tree preflight must not clear a compound log before every cell passes");
        RaidQaFixtureService.groundedFloor(helper.getLevel(), treeOrigin);
        helper.assertTrue(helper.getLevel().getBlockState(compoundLog).isAir(),
            "compound authoring may clear the fully enclosed proven natural trunk");

        // An exterior lane may trim only low natural leaves. It must reject a
        // trunk entirely so no upper logs can remain floating over the route.
        dryPlateau(helper);
        BlockPos lowLaneLeaf = absolute(helper, -10, FLOOR_Y + 1, 1);
        BlockPos highLaneCanopy = absolute(helper, -10, FLOOR_Y + 3, 1);
        helper.getLevel().setBlockAndUpdate(lowLaneLeaf,
            Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false));
        helper.getLevel().setBlockAndUpdate(highLaneCanopy,
            Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, false));
        BlockPos leafOrigin = probeWithoutWriting(helper, hint);
        helper.assertTrue(leafOrigin != null,
            "a non-persistent low leaf in an authored lane must pass preflight");
        RaidQaFixtureService.groundedFloor(helper.getLevel(), leafOrigin);
        helper.assertTrue(helper.getLevel().getBlockState(lowLaneLeaf).isAir(),
            "the exterior lane may clear only its low walk-clearance leaf");
        helper.assertTrue(helper.getLevel().getBlockState(highLaneCanopy).is(Blocks.OAK_LEAVES),
            "canopy above the two-block exterior walk clearance must remain untouched");

        dryPlateau(helper);
        BlockPos exteriorTrunk = absolute(helper, -10, FLOOR_Y + 1, 1);
        helper.getLevel().setBlockAndUpdate(exteriorTrunk, Blocks.OAK_LOG.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(exteriorTrunk.above(), Blocks.OAK_LOG.defaultBlockState());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            helper.getLevel().setBlockAndUpdate(exteriorTrunk.above(2).offset(dx, 0, dz),
                Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, false));
        }
        helper.assertTrue(probeWithoutWriting(helper, hint) == null,
            "a natural trunk in an exterior lane must reject before it can float over a road");
        helper.assertTrue(helper.getLevel().getBlockState(exteriorTrunk).is(Blocks.OAK_LOG),
            "exterior-trunk rejection must preserve the natural tree exactly");

        // A player log touching an otherwise valid compound trunk is not part
        // of the straight same-column proof. Reject and preserve both blocks.
        dryPlateau(helper);
        BlockPos attachedTrunk = absolute(helper, 8, FLOOR_Y + 1, 8);
        helper.getLevel().setBlockAndUpdate(attachedTrunk, Blocks.OAK_LOG.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(attachedTrunk.above(), Blocks.OAK_LOG.defaultBlockState());
        BlockPos attachedFacade = attachedTrunk.offset(1, 0, 0);
        helper.getLevel().setBlockAndUpdate(attachedFacade, Blocks.OAK_LOG.defaultBlockState());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            helper.getLevel().setBlockAndUpdate(attachedTrunk.above(2).offset(dx, 0, dz),
                Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, false));
        }
        helper.assertTrue(probeWithoutWriting(helper, hint) == null,
            "a player log attached to a natural trunk must reject before authoring");
        helper.assertTrue(helper.getLevel().getBlockState(attachedTrunk).is(Blocks.OAK_LOG)
                && helper.getLevel().getBlockState(attachedFacade).is(Blocks.OAK_LOG),
            "attached-log rejection must preserve both natural trunk and player facade");

        // A trunk taller than the compound's existing six-block roof envelope
        // is rejected rather than leaving upper logs floating over the shell.
        dryPlateau(helper);
        BlockPos tallTrunk = absolute(helper, 14, FLOOR_Y + 1, 14);
        for (int rise = 0; rise < 7; rise++) {
            helper.getLevel().setBlockAndUpdate(tallTrunk.above(rise),
                Blocks.OAK_LOG.defaultBlockState());
        }
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            helper.getLevel().setBlockAndUpdate(tallTrunk.above(7).offset(dx, 0, dz),
                Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, false));
        }
        helper.assertTrue(probeWithoutWriting(helper, hint) == null,
            "a trunk above the compound roof envelope must reject before authoring");
        helper.assertTrue(helper.getLevel().getBlockState(tallTrunk.above(6)).is(Blocks.OAK_LOG),
            "tall-trunk rejection must preserve the upper natural log exactly");

        // A log without the rooted, non-persistent-leaf proof is an unknown
        // structure and must reject without clearing or flattening anything.
        dryPlateau(helper);
        BlockPos foreignLog = absolute(helper, 8, FLOOR_Y + 1, 8);
        helper.getLevel().setBlockAndUpdate(foreignLog, Blocks.OAK_LOG.defaultBlockState());
        helper.assertTrue(probeWithoutWriting(helper, hint) == null,
            "an unproven log post in the compound must reject before authoring");
        helper.assertTrue(helper.getLevel().getBlockState(foreignLog).is(Blocks.OAK_LOG),
            "unknown log structure rejection must preserve the exact obstruction");

        dryPlateau(helper);
        BlockPos persistentLeaf = absolute(helper, 9, FLOOR_Y + 1, 9);
        helper.getLevel().setBlockAndUpdate(persistentLeaf,
            Blocks.OAK_LEAVES.defaultBlockState().setValue(LeavesBlock.PERSISTENT, true));
        helper.assertTrue(probeWithoutWriting(helper, hint) == null,
            "a persistent leaf in authored clearance must reject as a preserved obstruction");
        helper.assertTrue(helper.getLevel().getBlockState(persistentLeaf)
                .getValue(LeavesBlock.PERSISTENT),
            "persistent-leaf rejection must preserve the exact block state");

        // The natural apron is surveyed for walkability but never belongs to
        // the authoring footprint, so even a proven tree there is not clearable.
        dryPlateau(helper);
        BlockPos apronLog = absolute(helper, -49, FLOOR_Y + 1, 1);
        helper.getLevel().setBlockAndUpdate(apronLog, Blocks.OAK_LOG.defaultBlockState());
        helper.getLevel().setBlockAndUpdate(apronLog.above(), Blocks.OAK_LOG.defaultBlockState());
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            helper.getLevel().setBlockAndUpdate(apronLog.above(2).offset(dx, 0, dz),
                Blocks.OAK_LEAVES.defaultBlockState()
                    .setValue(LeavesBlock.PERSISTENT, false));
        }
        helper.assertTrue(probeWithoutWriting(helper, hint) == null,
            "a natural tree in the un-authored entry apron must reject, never clear");
        helper.assertTrue(helper.getLevel().getBlockState(apronLog).is(Blocks.OAK_LOG),
            "apron rejection must preserve the natural tree outside the footprint");

        dryPlateau(helper);
        BlockPos wet = absolute(helper, 3, FLOOR_Y, 3);
        helper.getLevel().setBlockAndUpdate(wet, Blocks.WATER.defaultBlockState());
        helper.assertTrue(probeWithoutWriting(helper, hint) == null,
            "water in one authored support cell must reject before foundation authoring");
        helper.assertTrue(helper.getLevel().getBlockState(untouched).is(Blocks.GRASS_BLOCK),
            "wet rejection must leave unrelated terrain untouched");
        helper.getLevel().setBlockAndUpdate(wet, Blocks.GRASS_BLOCK.defaultBlockState());

        BlockPos obstructed = absolute(helper, 4, FLOOR_Y + 1, 4);
        helper.getLevel().setBlockAndUpdate(obstructed, Blocks.CHEST.defaultBlockState());
        helper.assertTrue(probeWithoutWriting(helper, hint) == null,
            "a block entity in authored clearance must reject before foundation authoring");
        helper.assertTrue(helper.getLevel().getBlockState(untouched).is(Blocks.GRASS_BLOCK),
            "obstruction rejection must leave neighbouring terrain untouched");
        helper.getLevel().setBlockAndUpdate(obstructed, Blocks.AIR.defaultBlockState());

        BlockPos steep = absolute(helper, 5, FLOOR_Y + 1, 5);
        for (int rise = 0; rise < 5; rise++) {
            helper.getLevel().setBlockAndUpdate(steep.above(rise),
                Blocks.GRASS_BLOCK.defaultBlockState());
        }
        helper.assertTrue(probeWithoutWriting(helper, hint) == null,
            "a five-level compound rise must reject instead of grading beyond four levels");
        helper.assertTrue(helper.getLevel().getBlockState(untouched).is(Blocks.GRASS_BLOCK),
            "steep rejection must leave neighbouring terrain untouched");

        dryPlateau(helper);
        setNaturalSupport(helper, 5, 5, FLOOR_Y - 3);
        for (int z = 0; z <= 2; z++) setNaturalSupport(helper, -48, z, FLOOR_Y - 1);
        for (int z = -1; z <= 3; z++) setNaturalSupport(helper, -49, z, FLOOR_Y - 2);
        BlockPos gradedOrigin = probeWithoutWriting(helper, hint);
        helper.assertTrue(gradedOrigin != null
                && gradedOrigin.equals(absolute(helper, 0, FLOOR_Y, 0)),
            "a four-or-less-level compound with one-step exterior route changes must pass");
        RaidQaFixtureService.groundedFloor(helper.getLevel(), gradedOrigin);
        BlockPos gradedCompound = absolute(helper, 5, FLOOR_Y, 5);
        BlockPos compoundFillOne = absolute(helper, 5, FLOOR_Y - 1, 5);
        BlockPos compoundFillTwo = absolute(helper, 5, FLOOR_Y - 2, 5);
        BlockPos nearWestLane = absolute(helper, -1, FLOOR_Y, 1);
        BlockPos westLane = absolute(helper, -48, FLOOR_Y - 1, 1);
        BlockPos steppedApron = absolute(helper, -49, FLOOR_Y - 2, 1);
        helper.assertTrue(helper.getLevel().getBlockState(gradedCompound).is(Blocks.STONE_BRICKS),
            "the low compound column must grade to the common maximum support plane");
        helper.assertTrue(helper.getLevel().getBlockState(compoundFillOne).is(Blocks.COBBLESTONE),
            "the first low compound support gap must receive a physical fill");
        helper.assertTrue(helper.getLevel().getBlockState(compoundFillTwo).is(Blocks.COBBLESTONE),
            "the second low compound support gap must receive a physical fill");
        helper.assertTrue(helper.getLevel().getBlockState(westLane).is(Blocks.POLISHED_ANDESITE),
            "the exterior lane must be authored at its captured natural support plane");
        helper.assertTrue(helper.getLevel().getBlockState(steppedApron).is(Blocks.GRASS_BLOCK),
            "the natural apron must remain outside grounded authoring");
        helper.assertTrue(Math.abs(feet(helper, gradedCompound) - feet(helper, nearWestLane)) <= 1
                && Math.abs(feet(helper, nearWestLane) - feet(helper, westLane)) <= 1
                && Math.abs(feet(helper, westLane) - feet(helper, steppedApron)) <= 1,
            "the authored compound, exterior lane and apron must form only walkable zero-or-one steps");

        dryPlateau(helper);
        // This two-block rise spans every west-lane column, so no route can
        // reach the apron; it must still reject even though shoulders are no
        // longer required to be individually step-safe.
        for (int z = 0; z <= 2; z++) setNaturalSupport(helper, -24, z, FLOOR_Y + 2);
        helper.assertTrue(probeWithoutWriting(helper, hint) == null,
            "a two-block full-width exterior-lane barrier must reject before authoring");
        helper.assertTrue(helper.getLevel().getBlockState(untouched).is(Blocks.GRASS_BLOCK),
            "full-width lane-barrier rejection must leave neighbouring terrain untouched");

        dryPlateau(helper);
        BlockPos outsideFootprint = absolute(helper, 32, FLOOR_Y + 1, 4);
        helper.getLevel().setBlockAndUpdate(outsideFootprint, Blocks.OAK_LOG.defaultBlockState());
        BlockPos outsideOrigin = probeWithoutWriting(helper, hint);
        helper.assertTrue(outsideOrigin != null,
            "natural foliage outside every surveyed footprint must not affect selection");
        RaidQaFixtureService.groundedFloor(helper.getLevel(), outsideOrigin);
        helper.assertTrue(helper.getLevel().getBlockState(outsideFootprint).is(Blocks.OAK_LOG),
            "grounded authoring must preserve an outside-footprint tree log exactly");
        helper.succeed();
    }
}
