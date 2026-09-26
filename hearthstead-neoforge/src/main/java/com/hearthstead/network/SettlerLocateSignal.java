package com.hearthstead.network;

import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;

/**
 * Non-moving, server-authored feedback for the Overview's Locate action.
 *
 * <p>Vanilla's glowing flag is global entity state with no owner token.
 * Hearthstead's {@code Summons} system already owns that flag while a worker
 * is walking to a call. Reusing it here would let an expiring Locate erase a
 * live Summons outline (or vice versa), so this intentionally uses the safe
 * fallback: durable coordinates in chat plus a quiet confirmation cue. It
 * never writes navigation, activity, entity data or settlement state.
 */
public final class SettlerLocateSignal {

    public static void send(ServerPlayer player, SettlerEntity settler) {
        BlockPos pos = settler.blockPosition();
        player.displayClientMessage(Component.translatable(
            "hearthstead.settler.locate.result", settler.getSettlerName(),
            pos.getX(), pos.getY(), pos.getZ()), false);
        player.serverLevel().playSound(null, player.blockPosition(),
            SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.PLAYERS,
            0.35F, 1.45F);
    }

    private SettlerLocateSignal() {
    }
}
