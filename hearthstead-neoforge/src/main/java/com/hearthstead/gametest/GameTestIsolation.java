package com.hearthstead.gametest;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.conversation.ConversationConfig;
import com.hearthstead.conversation.parley.RaidParley;
import com.hearthstead.entity.combat.role.RoleCombat;
import com.hearthstead.entity.combat.role.RoleWorld;
import com.hearthstead.entity.path.RoadNavigation;
import com.hearthstead.event.worldevent.WorldEventConfig;
import com.hearthstead.event.worldevent.WorldEventDirector;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.TavernBard;
import com.hearthstead.settlement.development.ExtendedTrades;
import com.hearthstead.settlement.economy.EconomyConfig;
import com.hearthstead.settlement.guard.FieldOrders;
import com.hearthstead.settlement.summon.PlayerSummons;
import com.hearthstead.util.ItemSpill;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * GameTest-server-only isolation between batches (W3b 26 Sep, BH-23).
 *
 * <p>Vanilla leaks a lot from one batch into the next in a full suite run:
 * mock players are never logged out (132 joined, 0 left in W3b), a FAILED
 * test's entities are never discarded (succeed() discards only on a pass, and
 * only inside the bounds), fixture settlements stay in the saved data, and the
 * daylight cycle keeps running. Settlers from earlier tests then keep
 * working, spend the shared per-tick path budgets, and count as "a player
 * near" or "the settlement at this position" for the next batch. Tests that
 * passed alone failed only in the full run.
 *
 * <p>Before each batch (after its structures are placed, before any test
 * function runs) this resets the world to a known state: no mock players, no
 * leftover entities, no fixture settlements, no test overrides, the gamerules
 * as the server started, clear weather and the next morning. Tests inside one
 * batch still run in parallel and must keep their own per-test hygiene.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class GameTestIsolation {
    /** Profile name GameTestHelper.makeMockServerPlayerInLevel uses. */
    static final String MOCK_PLAYER_NAME = "test-mock-player";
    /** Time of day every batch starts at: early morning, a full working day ahead. */
    static final long BATCH_MORNING = 1000L;

    private static boolean installed;
    /** Snapshotted at the first batch reset (levels and server config are loaded by then). */
    private static GameRules startingRules;
    private static Boolean builderEnabledAtStart;
    private static Boolean logisticsEnabledAtStart;

    private GameTestIsolation() {
    }

    /**
     * AboutToStart, not Started: GameTestServer builds its batches (and
     * captures each batch's before-batch function) in initServer, right after
     * this event and before ServerStarted. W7a 07:57 proved a Started hook
     * installs too late (no batch ever ran the reset).
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        if (event.getServer() instanceof GameTestServer server) {
            install(server);
        }
    }

    private static synchronized void install(MinecraftServer server) {
        if (installed) {
            return;
        }
        Map<String, Consumer<ServerLevel>> before;
        try {
            before = ObfuscationReflectionHelper.getPrivateValue(GameTestRegistry.class, null,
                "BEFORE_BATCH_FUNCTIONS");
        } catch (RuntimeException e) {
            Hearthstead.LOGGER.warn("HEARTHSTEAD_GAMETEST_ISOLATION unavailable: {}", e.toString());
            return;
        }
        if (before == null) {
            return;
        }
        Set<String> batches = new TreeSet<>();
        for (TestFunction function : GameTestRegistry.getAllTestFunctions()) {
            batches.add(function.batchName());
        }
        Consumer<ServerLevel> reset = GameTestIsolation::resetBetweenBatches;
        for (String batch : batches) {
            Consumer<ServerLevel> own = before.get(batch);
            before.put(batch, own == null ? reset : reset.andThen(own));
        }
        installed = true;
        Hearthstead.LOGGER.info("HEARTHSTEAD_GAMETEST_ISOLATION installed for {} batches", batches.size());
    }

    /**
     * A player a test made: the vanilla mock player, or any player placed on an
     * in-memory EmbeddedChannel (some tests build their own named ServerPlayer).
     */
    static boolean isTestPlayer(ServerPlayer player) {
        if (MOCK_PLAYER_NAME.equals(player.getGameProfile().getName())) {
            return true;
        }
        try {
            return player.connection != null
                && player.connection.getConnection().channel() instanceof io.netty.channel.embedded.EmbeddedChannel;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Runs once per batch, before any of its tests start. */
    static void resetBetweenBatches(ServerLevel anyLevel) {
        MinecraftServer server = anyLevel.getServer();
        if (startingRules == null) {
            startingRules = server.getGameRules().copy();
            builderEnabledAtStart = HearthsteadServerConfig.BUILDER_ENABLED.get();
            logisticsEnabledAtStart = HearthsteadServerConfig.LOGISTICS_UPGRADES_ENABLED.get();
        }
        int players = 0;
        for (ServerPlayer player : List.copyOf(server.getPlayerList().getPlayers())) {
            if (isTestPlayer(player)) {
                server.getPlayerList().remove(player);
                players++;
            }
        }
        int entities = 0;
        int settlements = 0;
        for (ServerLevel level : server.getAllLevels()) {
            List<Entity> leftovers = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (!(entity instanceof Player) && !entity.isRemoved()) {
                    leftovers.add(entity);
                }
            }
            for (Entity entity : leftovers) {
                if (!entity.isRemoved()) {
                    entity.discard();
                    entities++;
                }
            }
            SettlementSavedData data = SettlementSavedData.existing(level);
            if (data != null && !data.settlements.isEmpty()) {
                List<UUID> ids = List.copyOf(data.settlements.keySet());
                // A Hearth whose settlement vanished re-founds a NEW settlement
                // (4 founders) a second later (HearthBlockEntity.serverTick), which
                // polluted later batches (W19a alarm_bell: FOUNDING_COMMITTED
                // mid-batch). Old test arenas are dead: remove their Hearths too.
                // Iterate a copy: removing a Hearth mutates data.settlements (CME, W19+).
                for (com.hearthstead.settlement.Settlement old : List.copyOf(data.settlements.values())) {
                    if (old != null && old.center != null && level.hasChunkAt(old.center)
                        && level.getBlockState(old.center).is(com.hearthstead.registry.ModBlocks.HEARTH.get())) {
                        level.removeBlock(old.center, false);
                    }
                }
                data.settlements.clear();
                data.setDirty();
                // Removing a Hearth disbands its settlement and can drop its stock:
                // sweep whatever that spawned, so nothing leaks into the batch.
                List<Entity> spawnedByRemoval = new ArrayList<>();
                for (Entity entity : level.getAllEntities()) {
                    if (!(entity instanceof Player) && !entity.isRemoved()) {
                        spawnedByRemoval.add(entity);
                    }
                }
                for (Entity entity : spawnedByRemoval) {
                    if (!entity.isRemoved()) {
                        entity.discard();
                        entities++;
                    }
                }
                for (UUID id : ids) {
                    WorldEventDirector.settlementRemoved(level, id);
                }
                settlements += ids.size();
            }
            RoadNavigation.resetExpandedBudgetForTests(level);
        }
        // Transient registries and every test override back to production.
        WorldEventDirector.resetTransientForTests();
        RaidParley.clear();
        RoleWorld.resetForTests(server);
        FieldOrders.resetForTests(server);
        PlayerSummons.resetForTests(server);
        TavernBard.resetForTests();
        RoleCombat.overrideEnabledForTests(null);
        ExtendedTrades.overrideForTests(null);
        WorldEventConfig.overrideMasterForTests(null);
        WorldEventConfig.overrideTypeForTests(null, null);
        ConversationConfig.overrideForTests(null, null);
        EconomyConfig.testOverride = null;
        com.hearthstead.settlement.gear.GearGate.setEnabledOverrideForTest(null);
        ItemSpill.refuseForTests(false);
        com.hearthstead.entity.combat.captain.CaptainConfig.overrideEnabledForTests(null);
        com.hearthstead.entity.combat.captain.CaptainConfig.overrideExtraLoadoutsForTests(null);
        // Every GameTest mock player is a real first join (placeNewPlayer), so with the
        // production default each one would arrive with 8 bread and break every
        // "exactly this much food was paid" check. Off on the GameTest server;
        // StarterKitGameTests switch it on explicitly.
        com.hearthstead.settlement.StarterKitConfig.overrideForTests(0);
        // HearthsteadGameTests.foundingSpawnsSettlers sets this without restoring it.
        com.hearthstead.settlement.SettlementManager.ignoreFoundingDistance = false;
        if (HearthsteadServerConfig.BUILDER_ENABLED.get() != builderEnabledAtStart.booleanValue()) {
            HearthsteadServerConfig.BUILDER_ENABLED.set(builderEnabledAtStart);
        }
        if (HearthsteadServerConfig.LOGISTICS_UPGRADES_ENABLED.get() != logisticsEnabledAtStart.booleanValue()) {
            HearthsteadServerConfig.LOGISTICS_UPGRADES_ENABLED.set(logisticsEnabledAtStart);
        }
        if (startingRules != null) {
            server.getGameRules().assignFrom(startingRules, server);
        }
        ServerLevel overworld = server.overworld();
        long dayTime = overworld.getDayTime();
        long day = Math.floorDiv(dayTime, 24000L);
        long timeOfDay = Math.floorMod(dayTime, 24000L);
        overworld.setDayTime((timeOfDay < BATCH_MORNING ? day : day + 1) * 24000L + BATCH_MORNING);
        overworld.setWeatherParameters(6000, 0, false, false);
        Hearthstead.LOGGER.info("HEARTHSTEAD_GAMETEST_ISOLATION batch reset: mockPlayers={} entities={} settlements={}",
            players, entities, settlements);
    }
}
