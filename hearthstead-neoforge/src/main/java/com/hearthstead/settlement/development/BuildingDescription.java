package com.hearthstead.settlement.development;

import com.hearthstead.building.BuildingType;
import net.minecraft.network.chat.Component;

/**
 * The one short-description key shared by Build Plans and Development nodes.
 * Longer plaque/handbook copy may add detail, but must start from this same
 * catalogue rather than inventing a contradictory second summary.
 */
public final class BuildingDescription {
    public static String key(BuildingType type) {
        return "hearthstead.building.benefit." + type.id();
    }

    public static Component shortDescription(BuildingType type) {
        return Component.translatable(key(type));
    }

    private BuildingDescription() {
    }
}
