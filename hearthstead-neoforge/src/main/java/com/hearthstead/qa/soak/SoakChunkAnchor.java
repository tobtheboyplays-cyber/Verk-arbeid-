package com.hearthstead.qa.soak;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.world.chunk.RegisterTicketControllersEvent;
import net.neoforged.neoforge.common.world.chunk.TicketController;

/**
 * QA-only chunk anchor for headless soak servers.
 *
 * <p>A dedicated server with nobody online random-ticks nothing: vanilla
 * {@code /forceload} keeps entities ticking but crops never grow, so every
 * farmer looks idle for a reason that cannot happen in real play. NeoForge's
 * ticking tickets ({@code forceChunk(..., ticking = true)}) are the supported
 * way to give a chunk full player-equivalent ticking. Nothing uses this
 * unless an operator runs {@code /hearthstead soakqa anchor ...}.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, bus = EventBusSubscriber.Bus.MOD)
public final class SoakChunkAnchor {
    public static final TicketController CONTROLLER = new TicketController(
        ResourceLocation.fromNamespaceAndPath(Hearthstead.MODID, "soak_anchor"));
    private static final BlockPos OWNER = BlockPos.ZERO;

    private SoakChunkAnchor() {
    }

    @SubscribeEvent
    public static void register(RegisterTicketControllersEvent event) {
        event.register(CONTROLLER);
    }

    /** Adds or removes ticking tickets for every chunk in the block rectangle. */
    public static int apply(ServerLevel level, int x1, int z1, int x2, int z2, boolean add) {
        int cx1 = Math.min(x1, x2) >> 4;
        int cx2 = Math.max(x1, x2) >> 4;
        int cz1 = Math.min(z1, z2) >> 4;
        int cz2 = Math.max(z1, z2) >> 4;
        int n = 0;
        for (int cx = cx1; cx <= cx2; cx++) {
            for (int cz = cz1; cz <= cz2; cz++) {
                if (CONTROLLER.forceChunk(level, OWNER, cx, cz, add, true)) {
                    n++;
                }
            }
        }
        return n;
    }
}
