package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.raid.RaidCaptain;
import com.hearthstead.settlement.raid.RaidDirector;
import com.hearthstead.settlement.raid.RaidFootingSearch;
import com.hearthstead.settlement.raid.RaidObjective;
import com.hearthstead.settlement.raid.RaidPlan;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Raids must form up on real terrain (owner's server world, 26 Sep: a lake
 * around half of Elmfield and hills 15-26 blocks above its Banner held the
 * authored first raid on 55 of 72 approach bearings). Each terrain below is
 * built high above the flat test world so only its own blocks exist, and
 * each is one the pre-fix search provably could not use (asserted on the
 * warned column with the legacy {@link RaidDirector#standableNear}).
 *
 * <p>The three terrains run in sequence inside one test: their rings are
 * ~130 blocks across and would shadow each other's heightmaps if the batch
 * ran them side by side.
 */
@GameTestHolder(Hearthstead.MODID)
@PrefixGameTestTemplate(false)
public class RaidSpawnTerrainGameTests {

    private static final BlockPos SKY_CENTER = new BlockPos(8, 100, 8);
    private static final int CLAIM = 24;
    /** The warned bearing; every terrain makes it unusable for the old rule. */
    private static final float APPROACH = 20.0F;
    /** The one dry road across the water, opposite the warned bearing. */
    private static final float CAUSEWAY = 200.0F;
    private static final int OUTER = RaidDirector.spawnMaxDistance(CLAIM)
        + RaidDirector.CAPTAIN_EXTRA_REACH + 2;

    private enum Terrain { WATER_SURROUNDED, FOREST_CANOPY, HILLS }

    @GameTest(template = "empty16", timeoutTicks = 400, batch = "raid_spawn_terrain")
    public void bandsFormUpOnWaterForestAndHills(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Settlement settlement = new Settlement(UUID.randomUUID(), "Terrengvik",
            helper.absolutePos(SKY_CENTER));
        settlement.radius = CLAIM;
        for (int i = 0; i < 6; i++) {
            settlement.putRecord(UUID.randomUUID(), "Terrengvik " + i, Profession.NONE);
        }
        SettlementSavedData data = SettlementSavedData.get(level);
        data.settlements.put(settlement.id, settlement);
        data.setDirty();
        List<ChunkPos> forced = forceArea(level, settlement.center,
            RaidDirector.spawnMaxDistance(CLAIM) + RaidFootingSearch.FAR_REACH + 4);
        List<RaiderEntity> all = new ArrayList<>();
        try {
            for (Terrain terrain : Terrain.values()) {
                Map<BlockPos, BlockState> laid = build(level, settlement.center, terrain);
                try {
                    BlockPos warned = RaidDirector.formUpAt(settlement.center, APPROACH,
                        RaidDirector.spawnMinDistance(CLAIM));
                    helper.assertTrue(RaidDirector.standableNear(level, warned) == null,
                        terrain + ": fixture must defeat the pre-fix +-12 search on the "
                            + "warned column " + warned);
                    RaidCaptain captain = RaidDirector.pickCaptain(settlement,
                        level.getRandom());
                    RaidPlan plan = new RaidPlan(captain.id(), RaidObjective.BLOD,
                        APPROACH, 40L);

                    List<RaiderEntity> band = RaidDirector.spawnBand(level, settlement, plan);
                    all.addAll(band);
                    helper.assertTrue(band.size() >= RaidDirector.MIN_BAND,
                        terrain + ": a recurring band must form up, got " + band.size());
                    assertPlacements(helper, level, settlement, terrain, band);
                    discard(band);

                    List<RaiderEntity> first = RaidDirector.spawnFirstBandForQa(level,
                        settlement, plan);
                    all.addAll(first);
                    helper.assertTrue(first.size() == RaidDirector.FIRST_RAID_BAND_SIZE,
                        terrain + ": the authored first raid needs all five slots, got "
                            + first.size());
                    assertPlacements(helper, level, settlement, terrain, first);
                    discard(first);
                } finally {
                    for (BlockPos pos : laid.keySet()) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 2);
                    }
                }
            }
        } finally {
            discard(all);
            for (ChunkPos pos : forced) {
                level.setChunkForced(pos.x, pos.z, false);
            }
            data.settlements.remove(settlement.id);
            data.setDirty();
        }
        helper.succeed();
    }

    private static void assertPlacements(GameTestHelper helper, ServerLevel level,
                                         Settlement settlement, Terrain terrain,
                                         List<RaiderEntity> band) {
        for (RaiderEntity raider : band) {
            BlockPos at = raider.blockPosition();
            BlockState floor = level.getBlockState(at.below());
            String where = terrain + " raider at " + at + " on " + floor;
            helper.assertTrue(RaidDirector.outsideClaim(settlement, at)
                    && !settlement.inside(at), where + " is inside the claim");
            helper.assertTrue(level.getFluidState(at).isEmpty()
                    && level.getFluidState(at.above()).isEmpty()
                    && level.getFluidState(at.below()).isEmpty(),
                where + " stands in or on water");
            helper.assertTrue(!floor.is(BlockTags.LEAVES), where + " stands on the canopy");
            helper.assertTrue(at.getY() >= level.getHeight(
                    Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, at.getX(), at.getZ()),
                where + " is under cover, not on open ground");
            if (terrain == Terrain.WATER_SURROUNDED) {
                helper.assertTrue(floor.is(Blocks.COBBLESTONE),
                    where + " must be on the only dry road");
            }
        }
    }

    /** Lays the terrain in the ring from the claim edge out; returns what it set. */
    private static Map<BlockPos, BlockState> build(ServerLevel level, BlockPos center,
                                                   Terrain terrain) {
        Map<BlockPos, BlockState> laid = new LinkedHashMap<>();
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState()
            .setValue(LeavesBlock.PERSISTENT, true);
        int y0 = center.getY();
        double causewayRad = Math.toRadians(CAUSEWAY);
        // Unit vector of formUpAt's bearing convention: x = -sin, z = cos.
        double ux = -Math.sin(causewayRad);
        double uz = Math.cos(causewayRad);
        for (int dx = -OUTER; dx <= OUTER; dx++) {
            for (int dz = -OUTER; dz <= OUTER; dz++) {
                int sq = dx * dx + dz * dz;
                if (sq <= (CLAIM + 1) * (CLAIM + 1) || sq > OUTER * OUTER) {
                    continue;
                }
                int x = center.getX() + dx;
                int z = center.getZ() + dz;
                double d = Math.sqrt(sq);
                switch (terrain) {
                    case WATER_SURROUNDED -> {
                        double along = dx * ux + dz * uz;
                        double across = Math.abs(dx * uz - dz * ux);
                        boolean road = along > 0 && across <= 1.5D;
                        put(level, laid, new BlockPos(x, y0 - 2, z), Blocks.STONE.defaultBlockState());
                        put(level, laid, new BlockPos(x, y0 - 1, z), road
                            ? Blocks.COBBLESTONE.defaultBlockState()
                            : Blocks.WATER.defaultBlockState());
                    }
                    case FOREST_CANOPY -> {
                        // A lowland forest 15 below the Banner, its canopy
                        // inside the old +-12 window: the old scan found only
                        // leaves to stand on or leaves at head height.
                        put(level, laid, new BlockPos(x, y0 - 15, z),
                            Blocks.GRASS_BLOCK.defaultBlockState());
                        put(level, laid, new BlockPos(x, y0 - 10, z), leaves);
                        put(level, laid, new BlockPos(x, y0 - 9, z), leaves);
                        if (Math.floorMod(x, 5) == 0 && Math.floorMod(z, 5) == 0) {
                            for (int y = y0 - 14; y <= y0 - 11; y++) {
                                put(level, laid, new BlockPos(x, y, z),
                                    Blocks.OAK_LOG.defaultBlockState());
                            }
                        }
                    }
                    case HILLS -> {
                        int top = y0 + 14 + (int) ((d - CLAIM) / 3.0D);
                        put(level, laid, new BlockPos(x, top - 1, z), Blocks.DIRT.defaultBlockState());
                        put(level, laid, new BlockPos(x, top, z),
                            Blocks.GRASS_BLOCK.defaultBlockState());
                    }
                }
            }
        }
        return laid;
    }

    private static void put(ServerLevel level, Map<BlockPos, BlockState> laid,
                            BlockPos pos, BlockState state) {
        level.setBlock(pos, state, 2);
        laid.put(pos, state);
    }

    private static void discard(List<RaiderEntity> band) {
        for (RaiderEntity raider : band) {
            if (raider != null && !raider.isRemoved()) {
                raider.discard();
            }
        }
    }

    private static List<ChunkPos> forceArea(ServerLevel level, BlockPos center, int reach) {
        List<ChunkPos> forced = new ArrayList<>();
        Set<Long> already = new LinkedHashSet<>();
        for (long packed : level.getForcedChunks()) {
            already.add(packed);
        }
        int minX = SectionPos.blockToSectionCoord(center.getX() - reach);
        int maxX = SectionPos.blockToSectionCoord(center.getX() + reach);
        int minZ = SectionPos.blockToSectionCoord(center.getZ() - reach);
        int maxZ = SectionPos.blockToSectionCoord(center.getZ() + reach);
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                ChunkPos pos = new ChunkPos(x, z);
                if (!already.contains(pos.toLong())) {
                    level.setChunkForced(x, z, true);
                    forced.add(pos);
                }
            }
        }
        return forced;
    }
}
