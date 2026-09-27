package com.hearthstead.client.builder;

/**
 * Client entry point of the Builder's Plan (BUILDER lane). Opens the catalog
 * screen; the placement ghost and line tool live beside it.
 */
public final class BuilderPlanClient {

    private BuilderPlanClient() {
    }

    /** Opens the Builder's Plan screen (catalog / defense / upgrades / sites). */
    public static void open() {
        BuilderPlanScreens.openCatalog();
    }
}
