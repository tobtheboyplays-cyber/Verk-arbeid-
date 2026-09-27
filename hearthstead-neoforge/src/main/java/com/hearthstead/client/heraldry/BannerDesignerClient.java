package com.hearthstead.client.heraldry;

import com.hearthstead.block.HearthBlockEntity;
import com.hearthstead.heraldry.BannerDesignPayloads;
import com.hearthstead.heraldry.VillageDesign;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

/** Client side of the Banner designer. */
public final class BannerDesignerClient {
    private BannerDesignerClient() {
    }

    public static void open(BannerDesignPayloads.Open payload) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        if (payload.mode() == BannerDesignPayloads.Open.REFRESH) {
            if (mc.screen instanceof BannerDesignerScreen screen && screen.pos().equals(payload.pos())) {
                screen.refresh(payload.design(), payload.changedBy(), payload.name());
            }
            return;
        }
        boolean placed = payload.mode() == BannerDesignPayloads.Open.PLACED;
        if (mc.screen == null || mc.screen instanceof BannerDesignerScreen || !placed) {
            mc.setScreen(new BannerDesignerScreen(payload.pos(), payload.design(), placed, payload.name()));
            return;
        }
        // Something else is open right after placing (never cover it): say where the designer lives.
        mc.player.displayClientMessage(Component.translatable("hearthstead.heraldry.later_hint"), false);
    }

    /** The Banner screen's Heraldry button: ask the server to open the designer here. */
    public static void requestOpen(BlockPos pos) {
        PacketDistributor.sendToServer(new BannerDesignPayloads.Action(pos, BannerDesignPayloads.Action.OPEN,
            VillageDesign.FOUNDING, ""));
    }

    /** What the Banner at {@code pos} flies on this client, or null when it is not loaded here. */
    @Nullable
    public static VillageDesign designAt(BlockPos pos) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || pos == null || !mc.level.isLoaded(pos)
            || !(mc.level.getBlockEntity(pos) instanceof HearthBlockEntity banner)) {
            return null;
        }
        return banner.effectiveDesign();
    }
}
