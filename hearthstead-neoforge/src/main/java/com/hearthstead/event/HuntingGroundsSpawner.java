package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.ai.HunterWorkGoal;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.work.HuntingGroundsPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnPlacements;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.biome.MobSpawnSettings;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/**
 * Hunting grounds: while a Hunter is employed at a Hunter's Lodge, wild game
 * slowly returns to natural open ground around it, so a working hunter is a
 * sustainable food source rather than a one-time clearance of the chunk.
 *
 * <h2>Cheap by construction</h2>
 * One check per level every {@value #CHECK_INTERVAL_TICKS} ticks; per Lodge at
 * most one spawn per {@code hunting.spawnIntervalSeconds}; at most
 * {@value #ATTEMPTS} random columns per due Lodge. Every column is read only
 * when its chunk is already loaded and entity-ticking -- this never loads or
 * generates a chunk.
 *
 * <h2>Never on the player's builds</h2>
 * A column qualifies only when ALL hold: inside the Lodge's hunting box;
 * outside the village core ({@code hunting.villageCoreRadius} around the
 * Hearth) and at least {@value #BUILDING_MARGIN} blocks from every building
 * of every settlement in the level; the surface is the heightmap top with
 * open sky above by the MOTION_BLOCKING heightmap (so never inside walls or under a roof); two collision-free,
 * fluid-free blocks for the animal; natural ground (grass/dirt family, or
 * sand/snow for rabbits) and daylight -- vanilla's own per-species spawn
 * rules ({@link SpawnPlacements#checkSpawnRules}); the species is one the
 * biome naturally spawns; and no player within
 * {@code hunting.minPlayerDistance} blocks.
 *
 * <h2>Cap</h2>
 * Nothing spawns while {@code hunting.gameCap} or more wild game animals
 * already live in the Lodge's hunting box (the same count the Hunter's
 * population floor reads). See {@link HuntingGroundsPolicy} for why the
 * spawner tops up the most numerous local species.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class HuntingGroundsSpawner {
    public static final int CHECK_INTERVAL_TICKS = 100;
    public static final int ATTEMPTS = 8;
    public static final int BUILDING_MARGIN = 4;
    /** Persistent marker on spawned game (diagnostics and GameTests only). */
    public static final String SPAWNED_TAG = "HearthsteadHuntingGround";

    /** Transient per-lodge last spawn time; a restart at worst allows one early spawn. */
    private static final Map<ServerLevel, Map<UUID, Long>> LAST_SPAWN = new WeakHashMap<>();

    public enum Outcome { DISABLED, NO_HUNTER, NOT_DUE, AT_CAP, NO_SPOT, SPAWNED }

    private HuntingGroundsSpawner() {
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)
            || level.getGameTime() % CHECK_INTERVAL_TICKS != 0
            || !HearthsteadServerConfig.huntingGrounds()
            // GameTest worlds pack many unrelated fixtures side by side; a
            // background spawn would change other tests' animal counts.
            // Spawner GameTests call tryLodge/attemptAt explicitly.
            || level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) {
            return;
        }
        SettlementSavedData data = SettlementSavedData.existing(level);
        if (data == null || data.settlements.isEmpty()) {
            return;
        }
        for (Settlement settlement : List.copyOf(data.settlements.values())) {
            for (Building building : List.copyOf(settlement.buildings)) {
                if (building.type == BuildingType.HUNTERS_LODGE) {
                    tryLodge(level, data, settlement, building, level.getRandom(), false);
                }
            }
        }
    }

    /**
     * One bounded attempt for one Lodge. {@code ignoreInterval} exists for
     * GameTests; production always honours the per-lodge interval.
     */
    public static Outcome tryLodge(ServerLevel level, SettlementSavedData data, Settlement settlement,
                                   Building lodge, RandomSource random, boolean ignoreInterval) {
        if (!HearthsteadServerConfig.huntingGrounds()) {
            return Outcome.DISABLED;
        }
        if (lodge == null || !lodge.valid || lodge.anchor == null
            || lodge.type != BuildingType.HUNTERS_LODGE
            || !level.hasChunkAt(lodge.anchor) || !hasEmployedHunter(level, lodge)) {
            return Outcome.NO_HUNTER;
        }
        long now = level.getGameTime();
        Map<UUID, Long> times = LAST_SPAWN.computeIfAbsent(level, ignored -> new java.util.HashMap<>());
        long last = times.getOrDefault(lodge.id, Long.MIN_VALUE);
        if (!ignoreInterval && !HuntingGroundsPolicy.due(now, last,
                HearthsteadServerConfig.huntingSpawnIntervalTicks())) {
            return Outcome.NOT_DUE;
        }
        Map<EntityType<?>, Integer> counts = HunterWorkGoal.wildGameCounts(level, settlement, lodge.anchor);
        int live = 0;
        for (int n : counts.values()) {
            live += n;
        }
        int cap = HearthsteadServerConfig.huntingGameCap();
        if (!HuntingGroundsPolicy.mayAdd(counts, live, cap, HunterWorkGoal.MIN_SPECIES_POPULATION)) {
            return Outcome.AT_CAP;
        }
        // Over the cap only to make one herd huntable: always top up the
        // largest herd, never a random variety pick.
        boolean topUpOnly = live >= cap;
        List<double[]> players = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator()) {
                players.add(new double[] {player.getX(), player.getY(), player.getZ()});
            }
        }
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            BlockPos spot = candidate(level, data, lodge, random, players);
            if (spot == null) {
                continue;
            }
            EntityType<?> species = HuntingGroundsPolicy.chooseSpecies(biomeGame(level, spot), counts,
                topUpOnly ? 100 : random.nextInt(100), random.nextInt(Integer.MAX_VALUE));
            if (species != null && attemptAt(level, data, lodge, spot, species, random, players)) {
                return Outcome.SPAWNED;
            }
        }
        return Outcome.NO_SPOT;
    }

    /**
     * Spawns one {@code species} at exactly {@code spot} when every placement
     * guard and vanilla's own species spawn rule pass; records the Lodge's
     * spawn time on success. Public for GameTests (deterministic positions).
     */
    public static boolean attemptAt(ServerLevel level, SettlementSavedData data, Building lodge,
                                    BlockPos spot, EntityType<?> species, RandomSource random,
                                    List<double[]> players) {
        if (species == null || !HunterWorkGoal.isWildGameType(species)
            || !level.hasChunkAt(spot) || !placementAllowed(level, data, spot, players)
            || !SpawnPlacements.isSpawnPositionOk(species, level, spot)
            || !SpawnPlacements.checkSpawnRules(species, level, MobSpawnType.NATURAL, spot, random)
            || !level.noCollision(species.getSpawnAABB(spot.getX() + 0.5D, spot.getY(),
                spot.getZ() + 0.5D))) {
            return false;
        }
        if (!spawn(level, species, spot, random)) {
            return false;
        }
        LAST_SPAWN.computeIfAbsent(level, ignored -> new java.util.HashMap<>())
            .put(lodge.id, level.getGameTime());
        return true;
    }

    private static boolean hasEmployedHunter(ServerLevel level, Building lodge) {
        for (UUID worker : lodge.workers) {
            if (level.getEntity(worker) instanceof SettlerEntity settler && settler.isAlive()
                && settler.getProfession() == Profession.HUNTER) {
                return true;
            }
        }
        return false;
    }

    /** One random surface column in the hunting box that passes every placement guard. */
    @Nullable
    public static BlockPos candidate(ServerLevel level, SettlementSavedData data, Building lodge,
                                     RandomSource random, List<double[]> players) {
        int radius = HunterWorkGoal.HUNT_RADIUS;
        int x = lodge.anchor.getX() + random.nextInt(radius * 2 + 1) - radius;
        int z = lodge.anchor.getZ() + random.nextInt(radius * 2 + 1) - radius;
        BlockPos column = new BlockPos(x, lodge.anchor.getY(), z);
        // Loaded AND entity-ticking: never a chunk load, never a frozen animal.
        if (!level.hasChunkAt(column) || !level.isPositionEntityTicking(column)) {
            return null;
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
        if (Math.abs(y - lodge.anchor.getY()) > HunterWorkGoal.VERTICAL_BAND) {
            return null;
        }
        BlockPos pos = new BlockPos(x, y, z);
        if (!placementAllowed(level, data, pos, players)) {
            return null;
        }
        return pos;
    }

    /** Every non-species guard for one exact feet position (see class doc). */
    public static boolean placementAllowed(ServerLevel level, SettlementSavedData data, BlockPos pos,
                                           List<double[]> players) {
        int core = HearthsteadServerConfig.huntingVillageCoreRadius();
        for (Settlement settlement : data.settlements.values()) {
            if (settlement.center != null && HuntingGroundsPolicy.insideCore(pos.getX(), pos.getZ(),
                    settlement.center.getX(), settlement.center.getZ(), core)) {
                return false;
            }
            for (Building building : settlement.buildings) {
                BoundingBox b = building.bounds;
                if (b != null && HuntingGroundsPolicy.nearBox(pos.getX(), pos.getY(), pos.getZ(),
                        b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ(), BUILDING_MARGIN)) {
                    return false;
                }
                if (building.anchor != null && building.anchor.closerThan(pos, BUILDING_MARGIN)) {
                    return false;
                }
            }
        }
        if (!HuntingGroundsPolicy.outOfSight(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
                players, HearthsteadServerConfig.huntingMinPlayerDistance())) {
            return false;
        }
        // Open sky: nothing motion-blocking above the feet (the heightmap is
        // updated synchronously with every block change, unlike sky light).
        if (level.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ()) > pos.getY()) {
            return false;
        }
        for (BlockPos body : new BlockPos[] {pos, pos.above()}) {
            BlockState state = level.getBlockState(body);
            if (!state.getCollisionShape(level, body).isEmpty() || !state.getFluidState().isEmpty()) {
                return false;
            }
        }
        BlockState ground = level.getBlockState(pos.below());
        return ground.is(net.minecraft.tags.BlockTags.DIRT)
            || ground.is(net.minecraft.tags.BlockTags.ANIMALS_SPAWNABLE_ON)
            || ground.is(net.minecraft.tags.BlockTags.RABBITS_SPAWNABLE_ON);
    }

    /** Wild-game species this biome naturally spawns, in biome order. */
    public static List<EntityType<?>> biomeGame(ServerLevel level, BlockPos pos) {
        List<EntityType<?>> out = new ArrayList<>();
        for (MobSpawnSettings.SpawnerData spawner : level.getBiome(pos).value().getMobSettings()
                .getMobs(MobCategory.CREATURE).unwrap()) {
            if (HunterWorkGoal.isWildGameType(spawner.type) && !out.contains(spawner.type)) {
                out.add(spawner.type);
            }
        }
        return out;
    }

    private static boolean spawn(ServerLevel level, EntityType<?> species, BlockPos pos, RandomSource random) {
        if (!(species.create(level) instanceof Animal animal)) {
            return false;
        }
        animal.moveTo(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D,
            random.nextFloat() * 360.0F, 0.0F);
        net.neoforged.neoforge.event.EventHooks.finalizeMobSpawn(animal, level,
            level.getCurrentDifficultyAt(pos), MobSpawnType.NATURAL, null);
        // Wild game returns as adults: a hunting ground of babies feeds nobody.
        animal.setAge(0);
        animal.getPersistentData().putBoolean(SPAWNED_TAG, true);
        return level.addFreshEntity(animal);
    }

    /** GameTest hook: forget interval history for one lodge. */
    public static void resetInterval(ServerLevel level, UUID lodgeId) {
        Map<UUID, Long> times = LAST_SPAWN.get(level);
        if (times != null) {
            times.remove(lodgeId);
        }
    }
}
