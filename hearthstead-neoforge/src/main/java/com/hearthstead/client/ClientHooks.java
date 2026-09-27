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

    /** BUILDER lane: the Resource Scroll -- the linked Builder's site (or all sites). */
    public static void openBuilderSites(java.util.UUID builder) {
        com.hearthstead.client.builder.BuilderPlanScreen.openSites(builder);
    }

    /** BUILDER lane: the Builder's Plan screen. */
    public static void openBuilderPlan() {
        com.hearthstead.client.builder.BuilderPlanClient.open();
    }

    /** Right-click with a Building Plan: the style picker, or the pending placement's panel. */
    public static void openBuildingPlan(String typeId) {
        com.hearthstead.client.builder.PlanPlacement.openFor(typeId);
    }

    public static void openSettlerScreen(int entityId) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level != null
            && mc.level.getEntity(entityId) instanceof com.hearthstead.entity.SettlerEntity settler) {
            leaveContainerScreen(mc);
            mc.setScreen(new com.hearthstead.client.screen.SettlerScreen(settler));
        }
    }

    /**
     * An explicit OPEN may create the plaque screen. UPDATE can only refresh
     * the exact plaque already visible, so a co-op broadcast never opens a
     * window, steals another plaque's sheet or loses the player's list place.
     */
    public static void showPlaque(com.hearthstead.network.PlaqueSnapshot snapshot) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        boolean matching = mc.screen instanceof com.hearthstead.client.screen.PlaqueScreen open
            && open.acceptsSnapshot(snapshot);
        if (matching) {
            var open = (com.hearthstead.client.screen.PlaqueScreen) mc.screen;
            open.update(snapshot);
        } else if (snapshot.delivery()
                == com.hearthstead.network.PlaqueSnapshot.Delivery.OPEN) {
            leaveContainerScreen(mc);
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
            leaveContainerScreen(mc);
            mc.setScreen(new com.hearthstead.client.screen.StorageScreen(payload));
        }
    }

    /** A settler snapshot arrived; only an already-open sheet consumes it. */
    public static void showSettlerSnapshot(com.hearthstead.network.SettlerSnapshotPayload snapshot) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof com.hearthstead.client.screen.SettlerScreen open
            && open.acceptsSnapshot(snapshot)) {
            open.update(snapshot);
        } else if (mc.screen
            instanceof com.hearthstead.client.screen.EquipmentRequestListScreen child
            && child.acceptsParentSnapshot(snapshot)) {
            child.updateParentSnapshot(snapshot);
        } else if (mc.screen
            instanceof com.hearthstead.client.screen.GuardOrderScreen child
            && child.acceptsParentSnapshot(snapshot)) {
            child.updateParentSnapshot(snapshot);
        }
    }

    /** A request list can refresh only the exact Courier view already open. */
    public static void showEquipmentRequestList(
            com.hearthstead.network.EquipmentRequestListPayload snapshot) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen
            instanceof com.hearthstead.client.screen.EquipmentRequestListScreen open
            && open.accepts(snapshot)) {
            open.update(snapshot);
        }
    }

    /** Guard-order replies can update only the exact already-open child tab. */
    public static void showGuardOrder(
            com.hearthstead.network.GuardOrderSnapshotPayload snapshot) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof com.hearthstead.client.screen.GuardOrderScreen open
            && open.accepts(snapshot)) {
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

    public static void showResearchSnapshot(com.hearthstead.network.ResearchSnapshotPayload snapshot) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof com.hearthstead.client.screen.ResearchScreen open) {
            open.update(snapshot);
        } else {
            leaveContainerScreen(mc);
            mc.setScreen(new com.hearthstead.client.screen.ResearchScreen(snapshot));
        }
    }

    /** Hearth Development and Mayor Emblems are distinct owner-facing views. */
    public static void showDevelopmentSnapshot(
            com.hearthstead.network.DevelopmentSnapshotPayload snapshot) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        if (snapshot.view()
                == com.hearthstead.network.DevelopmentActionPayload.View.TECH) {
            if (mc.screen instanceof com.hearthstead.client.screen.DevelopmentScreen open
                && open.accepts(snapshot)) {
                open.update(snapshot);
            } else {
                leaveContainerScreen(mc);
                mc.setScreen(new com.hearthstead.client.screen.DevelopmentScreen(snapshot));
            }
            return;
        }
        if (snapshot.view()
                == com.hearthstead.network.DevelopmentActionPayload.View.EMBLEM_SHOP) {
            if (mc.screen instanceof com.hearthstead.client.screen.EmblemShopScreen open
                && open.accepts(snapshot)) {
                open.update(snapshot);
            } else {
                leaveContainerScreen(mc);
                mc.setScreen(new com.hearthstead.client.screen.EmblemShopScreen(snapshot));
            }
        }
    }

    /**
     * Handles the three distinct Blessing delivery modes.
     *
     * <p>Only OPEN can create a screen. UPDATE and RESULT are consumed only
     * while the exact settlement-and-session Blessing screen is still visible.
     * This makes delayed replies harmless after Escape or reopen and prevents
     * a co-op update from opening UI for a player on another screen.
     */
    public static void showBlessingSnapshot(
            com.hearthstead.network.BlessingSnapshotPayload snapshot) {
        var mc = net.minecraft.client.Minecraft.getInstance();
        boolean matchingOpenScreen =
            mc.screen instanceof com.hearthstead.client.screen.BlessingScreen open
                && open.isInspecting(snapshot.settlementId(), snapshot.sessionId());

        if (snapshot.delivery()
                == com.hearthstead.network.BlessingSnapshotPayload.Delivery.OPEN) {
            if (!snapshot.mayOpenScreen()) {
                return;
            }
            if (matchingOpenScreen) {
                ((com.hearthstead.client.screen.BlessingScreen) mc.screen)
                    .update(snapshot);
            } else {
                leaveContainerScreen(mc);
                mc.setScreen(new com.hearthstead.client.screen.BlessingScreen(snapshot));
            }
            return;
        }

        if (!matchingOpenScreen) {
            // The player closed or replaced the view. Never resurrect it from
            // an action reply or another viewer's co-op refresh.
            return;
        }

        ((com.hearthstead.client.screen.BlessingScreen) mc.screen)
            .update(snapshot);
    }

    /** Routes server-authored Work Scepter state to the world preview/UI. */
    public static void showWorkZone(
            com.hearthstead.network.WorkZoneSnapshotPayload snapshot) {
        com.hearthstead.client.workzone.WorkZoneClient.accept(snapshot);
    }

    private ClientHooks() {
    }
    /**
     * Super-QA Q-011: a plain screen (Tech Tree, Stores, settler sheet, ...)
     * opened on top of a container screen such as the Banner must close that
     * container on the server too. Otherwise the server keeps the Banner menu
     * open, and the next vanilla inventory clicks are ignored (container-id
     * mismatch) until the player walks out of reach.
     */
    private static void leaveContainerScreen(net.minecraft.client.Minecraft mc) {
        if (mc.player != null
            && mc.screen instanceof net.minecraft.client.gui.screens.inventory.AbstractContainerScreen<?>
            && mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
    }
}
