package com.hearthstead.event;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.network.BuilderActionPayload;
import com.hearthstead.network.BuilderNetwork;
import com.hearthstead.network.BuilderPayloads;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.builder.BuildJobs;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;

import java.util.ArrayList;

/**
 * BUILDER lane wiring: payload registration and the budgeted periodic upkeep
 * (every 40 ticks per level: queue statuses, the raid rush, site snapshots).
 * With the feature switched off the upkeep does nothing and no data changes.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class BuilderEvents {

    public static final int UPKEEP_INTERVAL = 40;

    private BuilderEvents() {
    }

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar(ModBusEvents.NETWORK_PROTOCOL);
        registrar.playToServer(BuilderActionPayload.TYPE, BuilderActionPayload.CODEC, (payload, context) ->
            context.enqueueWork(() -> {
                if (context.player() instanceof ServerPlayer player) {
                    BuilderNetwork.handle(player, payload);
                }
            }));
        registrar.playToClient(BuilderPayloads.Catalog.TYPE, BuilderPayloads.Catalog.CODEC, (payload, context) ->
            context.enqueueWork(() -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                () -> () -> com.hearthstead.client.builder.BuilderClientState.acceptCatalog(payload))));
        registrar.playToClient(BuilderPayloads.Preview.TYPE, BuilderPayloads.Preview.CODEC, (payload, context) ->
            context.enqueueWork(() -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                () -> () -> com.hearthstead.client.builder.BuilderClientState.acceptPreview(payload))));
        registrar.playToClient(BuilderPayloads.Validation.TYPE, BuilderPayloads.Validation.CODEC, (payload, context) ->
            context.enqueueWork(() -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                () -> () -> com.hearthstead.client.builder.BuilderClientState.acceptValidation(payload))));
        registrar.playToClient(BuilderPayloads.Sites.TYPE, BuilderPayloads.Sites.CODEC, (payload, context) ->
            context.enqueueWork(() -> ModBusEvents.runClientOnly(FMLEnvironment.dist,
                () -> () -> com.hearthstead.client.builder.BuildSitesClient.accept(payload))));
    }

    /** QA film setup (op only): /builderfilm -- see BuilderFilm. */
    @SubscribeEvent
    public static void onCommands(net.neoforged.neoforge.event.RegisterCommandsEvent event) {
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("builderfilm")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> com.hearthstead.settlement.builder.BuilderFilm.setup(ctx.getSource())));
        // QA: how every Builder spent his working hours (owner: "no long breaks").
        event.getDispatcher().register(net.minecraft.commands.Commands.literal("builderstats")
            .requires(src -> src.hasPermission(2))
            .executes(ctx -> builderStats(ctx.getSource())));
    }

    private static int builderStats(net.minecraft.commands.CommandSourceStack source) {
        var all = com.hearthstead.entity.ai.BuilderUtilisation.all();
        if (all.isEmpty()) {
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal("builderstats: no Builder sampled yet"),
                true);
            return 0;
        }
        all.forEach((id, stats) -> {
            String line = "builderstats " + id + ": " + stats.describe();
            com.hearthstead.Hearthstead.LOGGER.info(line);
            source.sendSuccess(() -> net.minecraft.network.chat.Component.literal(line), true);
        });
        return all.size();
    }

    /** A player breaking or building into a recorded rung cell takes it over. */
    @SubscribeEvent
    public static void onBreak(net.neoforged.neoforge.event.level.BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level
            && event.getState().is(net.minecraft.world.level.block.Blocks.LADDER)) {
            BuildJobs.forgetRung(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onPlace(net.neoforged.neoforge.event.level.BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level
            && event.getEntity() instanceof net.minecraft.world.entity.player.Player) {
            BuildJobs.forgetRung(level, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel sampled && HearthsteadServerConfig.builderEnabled()) {
            com.hearthstead.entity.ai.BuilderUtilisation.sample(sampled);
        }
        if (!(event.getLevel() instanceof ServerLevel level)
            || level.getGameTime() % UPKEEP_INTERVAL != 0L
            || !HearthsteadServerConfig.builderEnabled()) {
            return;
        }
        SettlementSavedData data = SettlementSavedData.existing(level);
        if (data == null || data.settlements.isEmpty()) {
            return;
        }
        for (Settlement settlement : new ArrayList<>(data.settlements.values())) {
            BuildJobs.tick(level, settlement);
            BuilderNetwork.broadcast(level, settlement);
        }
    }
}
