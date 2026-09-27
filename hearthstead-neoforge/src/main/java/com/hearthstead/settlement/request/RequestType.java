package com.hearthstead.settlement.request;

import java.util.Optional;

/** Stable request kinds. Wire ids are save/network contracts, never ordinals. */
public enum RequestType {
    EQUIPMENT(0, "equipment"),
    MATERIAL_INPUT(1, "material_input"),
    FOOD(2, "food"),
    AMMUNITION(3, "ammunition"),
    OUTPUT_PICKUP(4, "output_pickup"),
    REPAIR_MATERIAL(5, "repair_material"),
    /** M1 crafting order: a workshop makes what the Warehouse lacks. */
    CRAFT_ORDER(6, "craft_order");

    private final int wireId;
    private final String id;

    RequestType(int wireId, String id) {
        this.wireId = wireId;
        this.id = id;
    }

    public int wireId() {
        return wireId;
    }

    public String id() {
        return id;
    }

    public static Optional<RequestType> fromWireId(int wireId) {
        for (RequestType value : values()) {
            if (value.wireId == wireId) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }

    public static Optional<RequestType> fromId(String id) {
        if (id != null) {
            for (RequestType value : values()) {
                if (value.id.equals(id)) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }
}
