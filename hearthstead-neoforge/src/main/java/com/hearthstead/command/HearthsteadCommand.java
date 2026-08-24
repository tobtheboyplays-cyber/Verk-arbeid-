package com.hearthstead.command;

import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.SettlementSavedData;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

public final class HearthsteadCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hearthstead")
            .then(Commands.literal("demo").executes(ctx -> demo(ctx.getSource())))
            .then(Commands.literal("info").executes(ctx -> info(ctx.getSource())))
            .then(Commands.literal("recruit").requires(src -> src.hasPermission(2))
                .executes(ctx -> recruit(ctx.getSource())))
            .then(Commands.literal("scan").requires(src -> src.hasPermission(2))
                .then(Commands.argument("pos",
                        net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                    .executes(ctx -> scan(ctx.getSource(),
                        net.minecraft.commands.arguments.coordinates.BlockPosArgument
                            .getLoadedBlockPos(ctx, "pos"))))));
    }

    /** Everything needed to try the whole loop in five minutes. */
    private static int demo(CommandSourceStack source) {
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("hearthstead.command.player_only"));
            return 0;
        }
        give(player, new ItemStack(ModItems.HEARTH.get()));
        give(player, new ItemStack(ModItems.WRIT_FARMER.get()));
        give(player, new ItemStack(ModItems.WRIT_LUMBERER.get()));
        give(player, new ItemStack(ModItems.WRIT_GUARD.get()));
        give(player, new ItemStack(ModItems.HANDBOOK.get()));
        give(player, new ItemStack(Items.BREAD, 32));
        give(player, new ItemStack(Items.WHEAT_SEEDS, 32));
        give(player, new ItemStack(Items.OAK_SAPLING, 8));
        give(player, new ItemStack(Items.IRON_HOE));
        give(player, new ItemStack(ModItems.SETTLER_SPAWN_EGG.get(), 4));
        player.displayClientMessage(
            Component.translatable("hearthstead.command.demo_given"), false);
        player.displayClientMessage(
            Component.translatable("hearthstead.command.demo_hint"), false);
        return 1;
    }

    private static void give(ServerPlayer player, ItemStack stack) {
        if (!player.getInventory().add(stack)) {
            player.drop(stack, false);
        }
    }

    private static int info(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        Settlement nearest = null;
        double bestDist = Double.MAX_VALUE;
        for (Settlement s : SettlementSavedData.get(level).settlements.values()) {
            double dist = s.center.distSqr(net.minecraft.core.BlockPos.containing(
                source.getPosition()));
            if (dist < bestDist) {
                bestDist = dist;
                nearest = s;
            }
        }
        if (nearest == null) {
            source.sendSuccess(() ->
                Component.translatable("hearthstead.command.no_settlement"), true);
            return 0;
        }
        Settlement s = nearest;
        // true (broadcastToAdmins), matching scan()/recruit() just below: info()
        // is the same kind of admin/diagnostic read they are, and there is no
        // reason for it alone to suppress console/log visibility when issued
        // by a player rather than the console -- proven live (20260824T114931Z)
        // that with this at false, a player-issued `hearthstead info` produces
        // no server-log trace at all, silently defeating any log-based check.
        source.sendSuccess(() -> Component.translatable("hearthstead.command.info",
            s.name, s.population(), s.capacity(), s.employed(), s.foodCache,
            s.moraleCache, s.radius), true);
        source.sendSuccess(() -> Component.translatable("hearthstead.command.info_homes",
            s.validHomeCount(), s.validBedCount()), true);
        return 1;
    }

    /** Re-surveys the plaque at a position — the admin/testing hook. */
    private static int scan(CommandSourceStack source, net.minecraft.core.BlockPos pos) {
        ServerLevel level = source.getLevel();
        if (!(level.getBlockEntity(pos)
            instanceof com.hearthstead.block.PlaqueBlockEntity plaque)) {
            source.sendFailure(Component.translatable("hearthstead.command.no_plaque",
                pos.getX(), pos.getY(), pos.getZ()));
            return 0;
        }
        plaque.survey(level);
        source.sendSuccess(() -> Component.translatable("hearthstead.command.scan_done",
            plaque.type().displayName(),
            Component.translatable("hearthstead.plaque.state." + plaque.state().id())), true);
        return 1;
    }

    private static int recruit(CommandSourceStack source) {
        ServerLevel level = source.getLevel();
        for (Settlement s : SettlementSavedData.get(level).settlements.values()) {
            s.recruitProgress = Math.max(0, s.recruitTarget - 1);
        }
        SettlementManager.data(level).setDirty();
        source.sendSuccess(() ->
            Component.translatable("hearthstead.command.recruit_forced"), true);
        return 1;
    }

    private HearthsteadCommand() {
    }
}
