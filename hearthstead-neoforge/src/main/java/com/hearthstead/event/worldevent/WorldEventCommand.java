package com.hearthstead.event.worldevent;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * <pre>
 *   /hsevent start &lt;id&gt;       (op 2) start an event now at the nearest settlement (QA)
 *   /hsevent stop             (op 2) end the nearest settlement's event and clean up
 *   /hsevent status           (op 2) today's plan, the running event, last outcome
 *   /hsevent respond &lt;event&gt; &lt;answer&gt;   (anyone) the chat-button answer path
 * </pre>
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class WorldEventCommand {
    private WorldEventCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hsevent")
            .then(Commands.literal("start").requires(source -> source.hasPermission(2))
                .then(Commands.argument("id", StringArgumentType.word())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                        java.util.Arrays.stream(WorldEventType.values()).map(WorldEventType::id), builder))
                    .executes(ctx -> start(ctx.getSource(), StringArgumentType.getString(ctx, "id")))))
            .then(Commands.literal("stop").requires(source -> source.hasPermission(2))
                .executes(ctx -> stop(ctx.getSource())))
            .then(Commands.literal("status").requires(source -> source.hasPermission(2))
                .executes(ctx -> status(ctx.getSource())))
            .then(Commands.literal("respond")
                .then(Commands.argument("event", StringArgumentType.word())
                    .then(Commands.argument("answer", StringArgumentType.word())
                        .executes(ctx -> respond(ctx.getSource(), StringArgumentType.getString(ctx, "event"),
                            StringArgumentType.getString(ctx, "answer")))))));
    }

    @Nullable
    private static Settlement nearest(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        SettlementSavedData data = SettlementSavedData.existing(level);
        if (data == null) return null;
        Settlement best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Settlement settlement : data.settlements.values()) {
            double d = settlement.center.distToCenterSqr(source.getPosition());
            if (d < bestDistance) { bestDistance = d; best = settlement; }
        }
        return best;
    }

    private static int start(CommandSourceStack source, String id) {
        WorldEventType type = WorldEventType.byId(id);
        if (type == null) {
            source.sendFailure(Component.literal("Unknown event '" + id + "'."));
            return 0;
        }
        Settlement settlement = nearest(source);
        if (settlement == null) {
            source.sendFailure(Component.literal("No settlement in this dimension."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        WorldEventSavedData.Row row = WorldEventSavedData.get(level).row(settlement.id);
        if (row != null && row.active != null) {
            source.sendFailure(Component.literal(settlement.name + " already has a running event: "
                + row.active.type.id() + " (use /hsevent stop)."));
            return 0;
        }
        WorldEventHandler handler = WorldEventDirector.handler(type);
        if (!handler.available(level, settlement)) {
            source.sendFailure(Component.literal("'" + type.id() + "' needs something " + settlement.name
                + " does not have yet (fields, livestock, a tavern, enough settlers)."));
            return 0;
        }
        if (!WorldEventDirector.start(level, settlement, type, true)) {
            source.sendFailure(Component.literal("'" + type.id() + "' could not find room to start. Try again nearby."));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Started " + type.id() + " at " + settlement.name + "."), true);
        return 1;
    }

    private static int stop(CommandSourceStack source) {
        Settlement settlement = nearest(source);
        WorldEventSavedData.Row row = settlement == null ? null : WorldEventSavedData.get(source.getLevel()).row(settlement.id);
        if (row == null || row.active == null) {
            source.sendFailure(Component.literal("No running event nearby."));
            return 0;
        }
        String id = row.active.type.id();
        WorldEventDirector.finish(source.getLevel(), settlement, "stopped", null);
        source.sendSuccess(() -> Component.literal("Stopped " + id + " and cleaned up its actors."), true);
        return 1;
    }

    private static int status(CommandSourceStack source) {
        Settlement settlement = nearest(source);
        if (settlement == null) {
            source.sendFailure(Component.literal("No settlement in this dimension."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        WorldEventSavedData.Row row = WorldEventSavedData.get(level).row(settlement.id);
        String plan = row == null ? "never planned" : "day " + row.plannedDay + " -> "
            + (row.plannedType == null ? "quiet/consumed" : row.plannedType.id() + " at " + row.plannedStart);
        String running = row == null || row.active == null ? "none"
            : row.active.type.id() + " " + row.active.eligibleTicks + "/" + row.active.type.budgetTicks()
                + " ticks, actors=" + row.active.actors.size();
        String last = row == null ? "none" : row.lastOutcome;
        source.sendSuccess(() -> Component.literal(settlement.name + ": enabled=" + WorldEventConfig.enabled()
            + " frequency=" + WorldEventConfig.frequency() + " raidQuiet=" + WorldEventDirector.raidQuiet(level, settlement)
            + "\n plan: " + plan + "\n running: " + running + "\n last: " + last
            + "\n available: " + WorldEventDirector.available(level, settlement)), false);
        return 1;
    }

    private static int respond(CommandSourceStack source, String eventId, String answer) {
        ServerPlayer player = source.getPlayer();
        if (player == null) return 0;
        UUID id;
        try {
            id = UUID.fromString(eventId);
        } catch (IllegalArgumentException bad) {
            source.sendFailure(Component.literal("That moment has passed."));
            return 0;
        }
        WorldEventVisitors.Result result = WorldEventVisitors.choose(player, id, null, answer);
        if (!result.accepted()) {
            player.displayClientMessage(result.message(), true);
            return 0;
        }
        return 1;
    }
}
