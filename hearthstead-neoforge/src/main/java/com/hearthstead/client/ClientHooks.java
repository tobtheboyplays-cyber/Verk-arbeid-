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

    /**
     * A plaque snapshot arrived. If its screen is already open this refreshes
     * it in place — an assignment should visibly land, not reopen the window
     * and lose the player's place in the list.
     */
    public static void showPlaque(com.hearthstead.network.PlaqueSnapshot snapshot) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof com.hearthstead.client.screen.PlaqueScreen open) {
            open.update(snapshot);
        } else {
            mc.setScreen(new com.hearthstead.client.screen.PlaqueScreen(snapshot));
        }
    }

    /**
     * A storage snapshot arrived. Refreshes in place if the view is already
     * open, so a second sneak-use updates rather than reopening.
     */
    public static void showStorage(com.hearthstead.network.StorageIndexPayload payload) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof com.hearthstead.client.screen.StorageScreen open) {
            open.update(payload);
        } else {
            mc.setScreen(new com.hearthstead.client.screen.StorageScreen(payload));
        }
    }

    /** A settler snapshot arrived; only an already-open sheet consumes it. */
    public static void showSettlerSnapshot(com.hearthstead.network.SettlerSnapshotPayload snapshot) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof com.hearthstead.client.screen.SettlerScreen open) {
            open.update(snapshot);
        }
    }

    /**
     * A mayor-seat snapshot arrived. Update-only: the hearth screen is opened
     * by the normal container flow, never by this payload.
     */
    public static void showHearthMayor(com.hearthstead.network.HearthMayorSnapshot snapshot) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof com.hearthstead.client.screen.HearthScreen open) {
            open.updateMayor(snapshot);
        }
    }

    private ClientHooks() {
    }
}
