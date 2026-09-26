package com.hearthstead.entity;

/**
 * Stable wire identity for a portable container placed during field work.
 *
 * <p>The mechanic is deliberately container-agnostic: the first complete
 * vertical uses {@link #SACK}, while later jobs may add a basket or crate
 * without duplicating pathing, ownership, transfer, persistence, or recovery
 * rules. Values are append-only because the byte id is synced and saved.
 */
public enum WorkContainerKind {
    NONE(0),
    SACK(1);

    private static final WorkContainerKind[] BY_ID = values();
    private final byte id;

    WorkContainerKind(int id) {
        this.id = (byte) id;
    }

    public byte id() {
        return id;
    }

    public static WorkContainerKind byId(int id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : NONE;
    }
}
