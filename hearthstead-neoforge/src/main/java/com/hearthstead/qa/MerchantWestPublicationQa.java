package com.hearthstead.qa;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.event.EarlyCoinMerchant;
import com.hearthstead.registry.ModBlocks;
import com.hearthstead.settlement.Mayor;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.util.QaTrace;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.WanderingTrader;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** One disposable fixed-input publication diagnostic; never a progression fixture. */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class MerchantWestPublicationQa {
    private static final UUID OWNER = UUID.fromString("bb043586-d529-4542-b8a1-1a3f0963a595");
    private static final BlockPos SITE = new BlockPos(2, 88, 25);
    private static final String OWNER_TAG = "HearthsteadEarlyMerchantSettlement";
    private static final String TARGET_TAG = "HearthsteadMerchantArrivalTarget";
    private static final Map<ServerLevel, Probe> PROBES = new WeakHashMap<>();
    private static final long LIMIT_NANOS = 180_000_000_000L;
    private MerchantWestPublicationQa() { }

    public static int command(CommandSourceStack source, String action) {
        try {
            ServerLevel level = source.getLevel();
            var world = level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            require(QaTrace.ENABLED && source.getEntity() == null && source.hasPermission(4)
                && level.getServer().isSameThread() && level.dimension() == Level.OVERWORLD
                && world.getFileName().toString().equals("world")
                && world.getParent().getFileName().toString().equals("playtest")
                && Files.readString(world.getParent().resolve(".hsqa-instance-owned"))
                    .trim().equals("hsqa-instance-v1:playtest"), "owned_trace_enabled_console_only");
            if (action.equals("prepare")) prepare(level, world);
            else {
                Probe probe = PROBES.get(level);
                require(probe != null, "no_owned_probe");
                sample(level, probe);
                if (action.equals("capture")) {
                    Hearthstead.LOGGER.info("HSQA_MERCHANT_WEST_CAPTURE merchant={} finished={} arrivedWithin180={} elapsedNanos={} terminal={} current={}",
                        probe.merchant, probe.finished, probe.arrived, System.nanoTime() - probe.started,
                        probe.terminal, snapshot(level, probe.merchant));
                } else if (action.equals("assert")) {
                    require(probe.finished && probe.arrived, "arrival_not_observed_within180_seconds");
                } else throw new IllegalStateException("unknown_action");
            }
            source.sendSuccess(() -> Component.literal("HSQA_MERCHANT_WEST_" + action.toUpperCase(java.util.Locale.ROOT) + "_PASS"), true);
            return 1;
        } catch (Exception failure) {
            source.sendFailure(Component.literal("HSQA_MERCHANT_WEST_REFUSED " + failure.getMessage()));
            return 0;
        }
    }

    private static void prepare(ServerLevel level, java.nio.file.Path world) throws Exception {
        require(!PROBES.containsKey(level) && level.getSeed() == 20260907L
            && level.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator generator
            && generator.stable(NoiseGeneratorSettings.OVERWORLD), "fresh_exact_normal_seed_required");
        var existing = SettlementSavedData.existing(level);
        require((existing == null || existing.settlements.isEmpty())
            && !Files.exists(world.resolve("data/hearthstead_early_merchants.dat"))
            && level.getServer().getPlayerList().getPlayerCount() == 1
            && level.players().size() == 1
            && level.players().getFirst().position().distanceToSqr(2.5, 88, 23.5) <= 1.0,
            "empty_settlement_history_and_one_observer_required");
        require(level.getBlockState(SITE).isAir() && level.getBlockState(SITE.above()).isAir()
            && level.getBlockState(SITE.below()).is(Blocks.GRASS_BLOCK)
            && level.getEntitiesOfClass(SettlerEntity.class, new AABB(SITE).inflate(96)).isEmpty()
            && level.getEntitiesOfClass(WanderingTrader.class, new AABB(SITE).inflate(96)).isEmpty(),
            "unmodified_founding_site_required");
        for (int index = 0; index < 32; index++) {
            double angle = index * Math.PI / 16.0;
            BlockPos column = SITE.offset((int)Math.round(Math.cos(angle) * 56), 0,
                (int)Math.round(Math.sin(angle) * 56));
            require(level.hasChunkAt(column), "publication_column_not_loaded_" + index);
        }
        require(level.getLevelData() instanceof ServerLevelData, "overworld_clock_required");
        // Mark before any fixture mutation: a partial setup can never be retried in place.
        Probe probe = new Probe();
        PROBES.put(level, probe);
        ((ServerLevelData)level.getLevelData()).setGameTime(1200L);
        level.setDayTime(1000L);
        require(level.setBlockAndUpdate(SITE, ModBlocks.HEARTH.get().defaultBlockState())
            && level.getBlockEntity(SITE) instanceof HearthBlockEntity, "fixture_hearth_failed");
        HearthBlockEntity hearth = (HearthBlockEntity) level.getBlockEntity(SITE);
        Settlement village = new Settlement(OWNER, "West publication diagnostic", SITE);
        village.radius = 48;
        var founders = new ArrayList<SettlerEntity>();
        for (int i = 0; i < 4; i++) {
            SettlerEntity founder = SettlementManager.spawnSettler(level, village, false);
            require(founder != null, "fixture_founder_failed_" + i);
            founders.add(founder);
        }
        var data = SettlementSavedData.get(level);
        data.settlements.put(OWNER, village);
        hearth.bindSettlement(OWNER);
        require(Mayor.appoint(level, village, founders.get(3)) == null, "fixture_mayor_failed");
        data.setDirty();
        int rotation = (int)Math.floorMod(level.getGameTime() / 1000 + OWNER.hashCode(), 32L);
        require(rotation == 15 && level.getGameTime() == 1200, "publication_inputs_changed");
        probe.started = System.nanoTime();
        require(EarlyCoinMerchant.visit(level, village), "real_production_publication_refused");
        var traders = level.getEntitiesOfClass(WanderingTrader.class, new AABB(SITE).inflate(80),
            trader -> trader.getPersistentData().hasUUID(OWNER_TAG)
                && trader.getPersistentData().getUUID(OWNER_TAG).equals(OWNER));
        require(traders.size() == 1, "exact_single_production_merchant_required");
        WanderingTrader trader = traders.getFirst();
        probe.merchant = trader.getUUID();
        BlockPos target = BlockPos.of(trader.getPersistentData().getLong(TARGET_TAG));
        var path = trader.getNavigation().getPath();
        Hearthstead.LOGGER.info("HSQA_MERCHANT_WEST_PUBLICATION settlement={} rotation={} time={} target={} restriction={} restrictRadius={} snapshot={}",
            OWNER, rotation, level.getGameTime(), target.toShortString(), trader.getRestrictCenter(),
            trader.getRestrictRadius(), snapshot(level, probe.merchant));
        require(trader.position().distanceToSqr(-53.5, 78, 25.5) <= 0.000001,
            "different_publication_route_not_original_west");
        require(path != null && path.canReach() && path.getTarget().equals(target)
            && trader.getRestrictCenter().equals(target) && trader.getRestrictRadius() == 16.0F,
            "production_path_or_restriction_missing");
        probe.armed = true;
        // No merchant NBT, position, health, AI, target, path, attribute or priority changes follow.
    }

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post event) {
        if (!QaTrace.ENABLED || !(event.getLevel() instanceof ServerLevel level)) return;
        Probe probe = PROBES.get(level);
        if (probe != null) sample(level, probe);
    }

    private static void sample(ServerLevel level, Probe probe) {
        if (!probe.armed || probe.finished) return;
        long elapsed = System.nanoTime() - probe.started;
        var entity = level.getEntity(probe.merchant);
        boolean arrived = entity instanceof WanderingTrader trader && trader.isAlive()
            && trader.getPersistentData().getBoolean("HearthsteadMerchantArrived")
            && trader.position().distanceToSqr(2.5, 88, 25.5) <= 100.0;
        if (elapsed >= LIMIT_NANOS || arrived) {
            probe.finished = true;
            probe.arrived = arrived && elapsed < LIMIT_NANOS;
            probe.terminal = "elapsedNanos=" + elapsed + ' ' + snapshot(level, probe.merchant);
            Hearthstead.LOGGER.info("HSQA_MERCHANT_WEST_TERMINAL merchant={} arrivedWithin180={} {}",
                probe.merchant, probe.arrived, probe.terminal);
        }
    }

    private static String snapshot(ServerLevel level, UUID id) {
        if (id == null || !(level.getEntity(id) instanceof WanderingTrader trader)) return "merchant_missing";
        var path = trader.getNavigation().getPath();
        return "tick=" + level.getGameTime() + " pos=" + trader.position()
            + " target=" + trader.getPersistentData().getLong(TARGET_TAG)
            + " path=" + (path == null ? "none" : path.getTarget() + "/reachable=" + path.canReach()
                + "/next=" + path.getNextNodeIndex() + "/count=" + path.getNodeCount())
            + " restriction=" + trader.getRestrictCenter() + '/' + trader.getRestrictRadius()
            + " nbt=" + trader.saveWithoutId(new CompoundTag());
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalStateException(reason);
    }

    private static final class Probe {
        UUID merchant;
        long started;
        boolean armed, finished, arrived;
        String terminal = "not_observed";
    }
}
