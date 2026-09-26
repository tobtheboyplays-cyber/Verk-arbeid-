package com.hearthstead.command;

import com.hearthstead.Hearthstead;
import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.network.TechTreeNetwork;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementSavedData;
import com.hearthstead.settlement.development.TechTree;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
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
 * QA / filming hooks for the v3 tech tree (op level 2):
 * {@code /hstech open} walks you to the nearest Banner and opens the tree;
 * {@code /hstech grant <node>} learns a node and everything it needs, free,
 * to stage a mid-game tree for stills.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class TechTreeCommand {
    private TechTreeCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hstech")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("open").executes(TechTreeCommand::open))
            .then(Commands.literal("grant")
                .then(Commands.argument("node", StringArgumentType.word())
                    .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                        TechTreeData.get().nodes().stream().map(TechNodeDef::id), builder))
                    .executes(ctx -> grant(ctx, StringArgumentType.getString(ctx, "node"))))));
    }

    private static Settlement nearest(ServerLevel level, ServerPlayer player) {
        Settlement best = null;
        double bestD = Double.MAX_VALUE;
        for (Settlement s : SettlementSavedData.get(level).settlements.values()) {
            double d = player.blockPosition().distSqr(s.center);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        return best;
    }

    private static int open(CommandContext<CommandSourceStack> ctx) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Settlement settlement = nearest(level, player);
        if (settlement == null || !(level.getBlockEntity(settlement.center) instanceof HearthBlockEntity hearth)) {
            ctx.getSource().sendFailure(Component.literal("No loaded Banner nearby."));
            return 0;
        }
        player.teleportTo(settlement.center.getX() + 2.5D, settlement.center.getY(), settlement.center.getZ() + 0.5D);
        TechTreeNetwork.open(player, settlement, hearth);
        return 1;
    }

    private static int grant(CommandContext<CommandSourceStack> ctx, String node)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        Settlement settlement = nearest(player.serverLevel(), player);
        if (settlement == null) {
            ctx.getSource().sendFailure(Component.literal("No settlement."));
            return 0;
        }
        int n = TechTree.qaGrant(player.serverLevel(), settlement, node);
        ctx.getSource().sendSuccess(() -> Component.literal("Granted " + n + " node(s) for " + node), false);
        return n;
    }
}
