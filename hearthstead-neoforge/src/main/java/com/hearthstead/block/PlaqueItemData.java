package com.hearthstead.block;

import com.hearthstead.building.BuildingType;
import com.hearthstead.registry.ModComponents;
import net.minecraft.world.item.ItemStack;

/**
 * Reads and writes the building type carried by a plaque item stack.
 *
 * <p>One item, many dedications: the architect sells the same plaque item
 * stamped for a house, a warehouse or a lumber camp. Keeping that in a data
 * component means one block, one model pipeline and one recipe path, instead
 * of a registry entry per building type that would have to grow every time a
 * new building is designed.
 */
public final class PlaqueItemData {

    public static String typeOf(ItemStack stack) {
        String id = stack.get(ModComponents.BUILDING_TYPE.get());
        return id == null ? BuildingType.HOUSE.id() : id;
    }

    public static BuildingType buildingType(ItemStack stack) {
        return BuildingType.byId(typeOf(stack));
    }

    public static ItemStack stamped(ItemStack stack, BuildingType type) {
        stack.set(ModComponents.BUILDING_TYPE.get(), type.id());
        return stack;
    }

    private PlaqueItemData() {
    }
}
