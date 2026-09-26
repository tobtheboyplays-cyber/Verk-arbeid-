package com.hearthstead.client.fx;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlock;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.fx.FxEffect;
import com.hearthstead.registry.ModParticles;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Atmosphere, derived entirely on the client (no network): embers from the
 * Banner's lantern at night, fireflies over fields and by the tavern on warm
 * clear nights, candle and lantern motes inside the tavern, dust motes in
 * workshops while someone works, steam over the brewer's kettle and the
 * cook's pot, and a splash where a fisher's line lands and bites.
 *
 * <p>Anvil sparks, flour and wood chips are NOT here: they fire on the clip's
 * own contact frame from {@code assets/hearthstead/motion/particles.json}.
 * Chimney smoke, butterflies and birds belong to the living-village lane
 * ({@code AmbientClient}) and are not duplicated.
 *
 * <p>Cost: one block-entity scan of the 7x7 chunks round the player every two
 * seconds, a few block reads per tick, and one settler query every five
 * ticks. Everything is budgeted through {@link FxClient}.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class FxAtmosphere {
    private static final int SCAN_RADIUS_CHUNKS = 3;
    private static final double SETTLER_RANGE = 24.0D;
    private static final int FIREFLY_CAP = 18;
    private static final Set<SettlerActivity> DUSTY = EnumSet.of(SettlerActivity.WORK_SAW,
        SettlerActivity.WORK_PLANE, SettlerActivity.WORK_WEAVE, SettlerActivity.WORK_KNEAD,
        SettlerActivity.WORK_SCRAPE, SettlerActivity.WORK_FLETCH, SettlerActivity.WORK_CHISEL,
        SettlerActivity.WORK_HAMMER, SettlerActivity.WORK_OVEN, SettlerActivity.SORTING);

    private record Banner(BlockPos pos, Direction facing) {
    }

    private static final List<Banner> BANNERS = new ArrayList<>();
    private static final List<BlockPos> TAVERNS = new ArrayList<>();
    /** Fisher entity id -> last seen cycle tick. */
    private static final Int2IntOpenHashMap FISH_CYCLE = new Int2IntOpenHashMap();
    /** Fisher entity id -> packed water surface position for this cast. */
    private static final Int2LongOpenHashMap FISH_WATER = new Int2LongOpenHashMap();
    private static final long[] FIREFLY_BORN = new long[FIREFLY_CAP * 2];
    private static int fireflyCursor;
    private static int ticks;

    private FxAtmosphere() {
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null || mc.isPaused()) {
            if (level == null) {
                BANNERS.clear();
                TAVERNS.clear();
                FISH_CYCLE.clear();
                FISH_WATER.clear();
            }
            return;
        }
        ticks++;
        try {
            if (ticks % 40 == 1) {
                scan(level, mc.player.blockPosition());
            }
            if (ticks % 2 == 0) {
                boolean night = isNight(level);
                if (night && !level.isRaining()) {
                    embers(level);
                    fireflies(level, mc.player.blockPosition());
                }
                tavernLights(level);
            }
            if (ticks % 5 == 0) {
                settlers(level, mc);
            }
        } catch (RuntimeException failure) {
            Hearthstead.LOGGER.debug("FX atmosphere tick failed", failure);
        }
    }

    static boolean isNight(ClientLevel level) {
        long t = level.getDayTime() % 24000L;
        return t > 12800L && t < 23200L;
    }

    // --------------------------------------------------------------- scan ---

    private static void scan(ClientLevel level, BlockPos around) {
        BANNERS.clear();
        TAVERNS.clear();
        int cx = around.getX() >> 4;
        int cz = around.getZ() >> 4;
        for (int dx = -SCAN_RADIUS_CHUNKS; dx <= SCAN_RADIUS_CHUNKS; dx++) {
            for (int dz = -SCAN_RADIUS_CHUNKS; dz <= SCAN_RADIUS_CHUNKS; dz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(cx + dx, cz + dz, false);
                if (chunk == null) {
                    continue;
                }
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    if (be instanceof HearthBlockEntity) {
                        BlockState state = be.getBlockState();
                        Direction facing = state.hasProperty(HearthBlock.FACING)
                            ? state.getValue(HearthBlock.FACING) : Direction.NORTH;
                        BANNERS.add(new Banner(be.getBlockPos().immutable(), facing));
                    } else if (be instanceof PlaqueBlockEntity plaque && plaque.type() == BuildingType.TAVERN) {
                        TAVERNS.add(be.getBlockPos().immutable());
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------- embers ---

    /** A slow trickle of embers off the Banner's lantern at night. */
    private static void embers(ClientLevel level) {
        RandomSource r = level.random;
        for (Banner banner : BANNERS) {
            BlockPos pos = banner.pos();
            if (!FxClient.inRange(pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                    FxEffect.BANNER_EMBER.range())) {
                continue;
            }
            if (FxClient.count(1, false) == 0 || r.nextFloat() > 0.3F) {
                continue;
            }
            double[] lantern = FxRecipes.BannerCloth.lantern(pos, banner.facing());
            FxRecipes.p(ModParticles.EMBER.get(), lantern[0] + (r.nextDouble() - 0.5D) * 0.08D, lantern[1],
                lantern[2] + (r.nextDouble() - 0.5D) * 0.08D, (r.nextDouble() - 0.5D) * 0.01D,
                0.02D + r.nextDouble() * 0.015D, (r.nextDouble() - 0.5D) * 0.01D, null);
        }
    }

    // ---------------------------------------------------------- fireflies ---

    private static int liveFireflies(long now) {
        int live = 0;
        for (long born : FIREFLY_BORN) {
            if (born > 0L && now - born < 140L) {
                live++;
            }
        }
        return live;
    }

    /**
     * Fireflies over crops, grass and flowers, only inside a settlement (a
     * Banner in scan range), on clear nights in temperate biomes -- the
     * summer-night feel without a season system. Extra samples by the tavern.
     */
    private static void fireflies(ClientLevel level, BlockPos player) {
        if (BANNERS.isEmpty()) {
            return;
        }
        long now = level.getGameTime();
        int cap = Math.max(0, FxClient.count(FIREFLY_CAP, false));
        if (liveFireflies(now) >= cap) {
            return;
        }
        RandomSource r = level.random;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        int samples = 3 + TAVERNS.size();
        for (int i = 0; i < samples; i++) {
            BlockPos base = i >= 3 && !TAVERNS.isEmpty() ? TAVERNS.get(i - 3) : player;
            int radius = i >= 3 ? 8 : 18;
            int x = base.getX() + r.nextInt(radius * 2 + 1) - radius;
            int z = base.getZ() + r.nextInt(radius * 2 + 1) - radius;
            int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            if (Math.abs(y - player.getY()) > 12) {
                continue;
            }
            probe.set(x, y - 1, z);
            BlockState ground = level.getBlockState(probe);
            BlockState at = level.getBlockState(probe.move(Direction.UP));
            boolean green = ground.is(Blocks.GRASS_BLOCK) || ground.is(Blocks.FARMLAND) || ground.is(BlockTags.CROPS)
                || at.is(BlockTags.CROPS) || at.is(BlockTags.FLOWERS) || at.is(Blocks.SHORT_GRASS)
                || at.is(Blocks.TALL_GRASS);
            if (!green) {
                continue;
            }
            float temperature = level.getBiome(probe).value().getBaseTemperature();
            if (temperature < 0.3F || temperature > 1.2F) {
                continue;
            }
            FxParticle fly = FxRecipes.p(ModParticles.FIREFLY.get(), x + r.nextDouble(), y + 0.4D + r.nextDouble() * 1.4D,
                z + r.nextDouble(), 0.0D, 0.0D, 0.0D, null);
            if (fly != null) {
                FIREFLY_BORN[fireflyCursor++ % FIREFLY_BORN.length] = now;
            }
            return; // at most one new firefly per sample tick
        }
    }

    // -------------------------------------------------------- tavern lights ---

    /** Warm flicker motes over lanterns, candles and torches inside the tavern. */
    private static void tavernLights(ClientLevel level) {
        if (TAVERNS.isEmpty()) {
            return;
        }
        RandomSource r = level.random;
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (BlockPos tavern : TAVERNS) {
            if (!FxClient.inRange(tavern.getX(), tavern.getY(), tavern.getZ(), FxEffect.CANDLE_MOTE.range() + 8.0D)) {
                continue;
            }
            for (int i = 0; i < 8; i++) {
                probe.set(tavern.getX() + r.nextInt(15) - 7, tavern.getY() + r.nextInt(6) - 1,
                    tavern.getZ() + r.nextInt(15) - 7);
                BlockState state = level.getBlockState(probe);
                double flame;
                if (state.is(Blocks.LANTERN) || state.is(Blocks.SOUL_LANTERN)) {
                    flame = 0.45D;
                } else if (state.is(BlockTags.CANDLES) && state.hasProperty(CandleBlock.LIT)
                    && state.getValue(CandleBlock.LIT)) {
                    flame = 0.6D;
                } else if (state.is(Blocks.TORCH) || state.is(Blocks.WALL_TORCH)) {
                    flame = 0.75D;
                } else {
                    continue;
                }
                if (r.nextFloat() < 0.5F && FxClient.count(1, false) > 0) {
                    FxParticle mote = FxRecipes.p(ModParticles.MOTE.get(),
                        probe.getX() + 0.5D + (r.nextDouble() - 0.5D) * 0.15D, probe.getY() + flame,
                        probe.getZ() + 0.5D + (r.nextDouble() - 0.5D) * 0.15D,
                        0.0D, 0.008D, 0.0D, FxRecipes.AMBER);
                    if (mote != null) {
                        mote.scaleSize(0.55F).life(24 + r.nextInt(16)).opacity(0.8F);
                    }
                }
                break;
            }
        }
    }

    // ------------------------------------------------------------ settlers ---

    private static void settlers(ClientLevel level, Minecraft mc) {
        List<SettlerEntity> near = level.getEntitiesOfClass(SettlerEntity.class,
            mc.player.getBoundingBox().inflate(SETTLER_RANGE, 12.0D, SETTLER_RANGE), SettlerEntity::isAlive);
        RandomSource r = level.random;
        int dust = 0;
        int steam = 0;
        for (SettlerEntity settler : near) {
            SettlerActivity activity = settler.getActivity();
            if (activity == SettlerActivity.WORK_FISH) {
                fisher(level, settler);
                continue;
            }
            FISH_CYCLE.remove(settler.getId());
            boolean moving = settler.walkAnimation.speed() > 0.15F;
            if (moving) {
                continue;
            }
            boolean brewing = (settler.getProfession() == Profession.BREWER && activity == SettlerActivity.WORK_STOKE)
                || activity == SettlerActivity.WORK_STIR;
            if (brewing && steam < 6 && r.nextFloat() < 0.6F && FxClient.count(1, false) > 0) {
                steam++;
                float yaw = settler.yBodyRot * Mth.DEG_TO_RAD;
                double fx = -Mth.sin(yaw);
                double fz = Mth.cos(yaw);
                FxRecipes.p(ModParticles.STEAM.get(), settler.getX() + fx * 0.9D + (r.nextDouble() - 0.5D) * 0.2D,
                    settler.getY() + 1.0D, settler.getZ() + fz * 0.9D + (r.nextDouble() - 0.5D) * 0.2D,
                    0.0D, 0.02D, 0.0D, null);
            }
            if (DUSTY.contains(activity) && dust < 10 && r.nextFloat() < 0.45F
                && !level.canSeeSky(settler.blockPosition().above()) && FxClient.count(1, false) > 0) {
                dust++;
                FxRecipes.p(ModParticles.DUST_MOTE.get(), settler.getX() + (r.nextDouble() - 0.5D) * 3.0D,
                    settler.getY() + 0.4D + r.nextDouble() * 1.8D, settler.getZ() + (r.nextDouble() - 0.5D) * 3.0D,
                    0.0D, 0.0D, 0.0D, null);
            }
        }
        if (FISH_CYCLE.size() > 64) {
            FISH_CYCLE.clear();
            FISH_WATER.clear();
        }
    }

    /** Cycle ticks (FisherWorkGoal / SettlerModel#applyFishingPose): line lands ~22, bite ~246. */
    private static final int LINE_LANDS = 22;
    private static final int BITE = 246;

    private static boolean crossed(int before, int now, int mark) {
        if (before < 0) {
            return now >= mark && now < mark + 8;
        }
        return now >= before ? before < mark && mark <= now : before < mark || mark <= now;
    }

    private static void fisher(ClientLevel level, SettlerEntity settler) {
        int id = settler.getId();
        int cycle = settler.getFisherCycleTick();
        int before = FISH_CYCLE.containsKey(id) ? FISH_CYCLE.get(id) : -1;
        FISH_CYCLE.put(id, cycle);
        if (cycle < 0 || !FxClient.inRange(settler.getX(), settler.getY(), settler.getZ(), FxEffect.FISH_SPLASH.range())) {
            return;
        }
        boolean lands = crossed(before, cycle, LINE_LANDS);
        boolean bite = crossed(before, cycle, BITE);
        if (!lands && !bite) {
            return;
        }
        if (lands || !FISH_WATER.containsKey(id)) {
            BlockPos water = findWater(level, settler);
            if (water == null) {
                FISH_WATER.remove(id);
                return;
            }
            FISH_WATER.put(id, water.asLong());
        }
        BlockPos water = BlockPos.of(FISH_WATER.get(id));
        RandomSource r = level.random;
        double x = water.getX() + 0.5D;
        double y = water.getY() + 1.0D;
        double z = water.getZ() + 0.5D;
        int n = FxClient.count(bite ? 12 : 6, false);
        for (int i = 0; i < n; i++) {
            FxClient.vanilla(ParticleTypes.SPLASH, x + (r.nextDouble() - 0.5D) * 0.5D, y,
                z + (r.nextDouble() - 0.5D) * 0.5D, (r.nextDouble() - 0.5D) * 0.2D, 0.2D, (r.nextDouble() - 0.5D) * 0.2D);
        }
        if (bite) {
            for (int i = 0; i < FxClient.count(4, false); i++) {
                FxClient.vanilla(ParticleTypes.BUBBLE, x + (r.nextDouble() - 0.5D) * 0.3D, y - 0.3D,
                    z + (r.nextDouble() - 0.5D) * 0.3D, 0.0D, 0.1D, 0.0D);
            }
        }
    }

    /** The water surface 3..6 blocks in front of the fisher, or null. */
    private static BlockPos findWater(ClientLevel level, SettlerEntity settler) {
        float yaw = settler.yBodyRot * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw);
        double fz = Mth.cos(yaw);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (double d : new double[] {4.0D, 3.0D, 5.0D, 6.0D, 2.5D}) {
            int x = Mth.floor(settler.getX() + fx * d);
            int z = Mth.floor(settler.getZ() + fz * d);
            for (int y = Mth.floor(settler.getY()) + 1; y >= Mth.floor(settler.getY()) - 5; y--) {
                probe.set(x, y, z);
                if (level.getFluidState(probe).is(FluidTags.WATER)
                    && level.getBlockState(probe.above()).isAir()) {
                    return probe.immutable();
                }
            }
        }
        return null;
    }
}
