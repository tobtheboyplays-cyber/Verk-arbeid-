package com.hearthstead.command;

import com.hearthstead.Hearthstead;
import com.hearthstead.revive.ReviveService;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Op-only QA for the co-op downed/revive feature (filming, playtests):
 * <pre>
 *   /hsrevive down [player]         put a survival player down now (no raid needed)
 *   /hsrevive revive [player]       revive them immediately
 *   /hsrevive bleedout [player]     let them bleed out now
 *   /hsrevive timings bleed revive  shorter timers in ticks (-1 = config)
 * </pre>
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class ReviveCommand {
    private ReviveCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hsrevive")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("down")
                .executes(ctx -> down(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> down(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
            .then(Commands.literal("revive")
                .executes(ctx -> revive(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> revive(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
            .then(Commands.literal("bleedout")
                .executes(ctx -> bleed(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                .then(Commands.argument("player", EntityArgument.player())
                    .executes(ctx -> bleed(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))))
            .then(Commands.literal("timings")
                .then(Commands.argument("bleedTicks", IntegerArgumentType.integer(-1, 20 * 600))
                    .then(Commands.argument("reviveTicks", IntegerArgumentType.integer(-1, 20 * 60))
                        .executes(ctx -> {
                            ReviveService.overrideTimingsForTest(
                                IntegerArgumentType.getInteger(ctx, "bleedTicks"),
                                IntegerArgumentType.getInteger(ctx, "reviveTicks"), -1);
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                "Revive timings overridden (-1 = config)"), true);
                            return 1;
                        })))));
    }

    private static int down(CommandSourceStack source, ServerPlayer player) {
        boolean ok = ReviveService.forceDown(player);
        source.sendSuccess(() -> Component.literal(ok ? player.getGameProfile().getName() + " is down"
            : "Could not down " + player.getGameProfile().getName()
                + " (already down, creative/spectator or dead)"), true);
        return ok ? 1 : 0;
    }

    private static int revive(CommandSourceStack source, ServerPlayer player) {
        boolean ok = ReviveService.forceRevive(player, source.getEntity());
        source.sendSuccess(() -> Component.literal(ok ? "Revived " + player.getGameProfile().getName()
            : player.getGameProfile().getName() + " is not down"), true);
        return ok ? 1 : 0;
    }

    private static int bleed(CommandSourceStack source, ServerPlayer player) {
        if (!ReviveService.isDowned(player)) {
            source.sendFailure(Component.literal(player.getGameProfile().getName() + " is not down"));
            return 0;
        }
        ReviveService.bleedOut(player);
        return 1;
    }
}
