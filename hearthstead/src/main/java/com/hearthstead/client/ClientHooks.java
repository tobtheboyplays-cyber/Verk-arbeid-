package com.hearthstead.client;

/**
 * Client-only entry points, referenced from common code strictly through
 * {@code DistExecutor.unsafeRunWhenOn(Dist.CLIENT, ...)} lambdas so this
 * class never loads on a dedicated server.
 */
public final class ClientHooks {

    public static void openHandbook() {
        net.minecraft.client.Minecraft.getInstance().setScreen(
            new com.hearthstead.client.screen.HandbookScreen());
    }

    public static void openSettlerScreen(int entityId) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null
            && mc.level.getEntity(entityId) instanceof com.hearthstead.entity.SettlerEntity settler) {
            mc.setScreen(new com.hearthstead.client.screen.SettlerScreen(settler));
        }
    }

    private ClientHooks() {
    }
}
