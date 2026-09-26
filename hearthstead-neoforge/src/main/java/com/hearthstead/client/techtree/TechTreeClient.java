package com.hearthstead.client.techtree;

import com.hearthstead.client.screen.TechTreeScreen;
import com.hearthstead.network.TechTreeSnapshotPayload;
import net.minecraft.client.Minecraft;

/** Client entry for tech tree snapshots: open the screen or refresh it in place. */
public final class TechTreeClient {
    private TechTreeClient() {
    }

    public static void accept(TechTreeSnapshotPayload snapshot) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof TechTreeScreen open && open.accepts(snapshot)) {
            open.update(snapshot);
        } else if (snapshot.openScreen()) {
            if (mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu) {
                mc.player.closeContainer();
            }
            mc.setScreen(new TechTreeScreen(snapshot));
        }
    }
}
