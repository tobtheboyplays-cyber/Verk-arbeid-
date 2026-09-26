package com.hearthstead.client.builder;

import com.hearthstead.network.BuilderPayloads;
import net.minecraft.client.Minecraft;

/** Screen routing for the Builder's Plan. */
public final class BuilderPlanScreens {

    private BuilderPlanScreens() {
    }

    /** Opens the plan screen and asks the server for fresh data. */
    public static void openCatalog() {
        BuilderPlacement.cancel();
        Minecraft.getInstance().setScreen(new BuilderPlanScreen());
        BuilderClientState.requestCatalog();
    }

    /** A new catalog arrived: an open plan screen rebuilds itself on its next tick. */
    public static void refresh() {
    }

    /** A validation arrived: open the confirm sheet for it. */
    public static void showValidation(BuilderPayloads.Validation validation) {
        Minecraft.getInstance().setScreen(new BuilderConfirmScreen(validation));
    }
}
