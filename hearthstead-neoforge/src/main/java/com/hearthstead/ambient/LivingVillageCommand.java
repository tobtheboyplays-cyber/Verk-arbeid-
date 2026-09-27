package com.hearthstead.ambient;

import com.hearthstead.Hearthstead;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * QA / filming hooks for the living village (op level 2):
 * {@code /hsalive cheer} plays the won-raid cheer at the settlement you stand
 * in, {@code /hsalive mourn} a mourning moment where you stand, and
 * {@code /hsalive regreet} lets every settler greet you again today.
 */
@EventBusSubscriber(modid = Hearthstead.MODID)
public final class LivingVillageCommand {
    private LivingVillageCommand() {
    }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("hsalive")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("cheer").executes(LivingVillageCommand::cheer))
            .then(Commands.literal("mourn").executes(LivingVillageCommand::mourn))
            .then(Commands.literal("regreet").executes(context -> {
                LivingVillage.GREETED.clear();
                context.getSource().sendSuccess(() -> Component.literal("Settlers will greet you again."), false);
                return 1;
            })));
    }

    private static int cheer(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        Settlement settlement = SettlementManager.at(level, net.minecraft.core.BlockPos.containing(
            context.getSource().getPosition()));
        if (settlement == null) {
            context.getSource().sendFailure(Component.literal("Stand inside a settlement."));
            return 0;
        }
        int n = LivingVillage.onRaidHeld(level, settlement);
        // Same pairing as a real won raid: the FX lane's burst above the Banner.
        com.hearthstead.fx.FxHooks.raidWon(level, settlement);
        context.getSource().sendSuccess(() -> Component.literal(n + " settlers cheered."), false);
        return n;
    }

    private static int mourn(CommandContext<CommandSourceStack> context) {
        ServerLevel level = context.getSource().getLevel();
        Vec3 pos = context.getSource().getPosition();
        Settlement settlement = SettlementManager.at(level, net.minecraft.core.BlockPos.containing(pos));
        if (settlement == null) {
            context.getSource().sendFailure(Component.literal("Stand inside a settlement."));
            return 0;
        }
        int n = LivingVillage.mourn(level, settlement, "Aldric", pos, level.getGameTime());
        context.getSource().sendSuccess(() -> Component.literal(n + " settlers mourned."), false);
        return n;
    }
}
