package com.hearthstead.logistics;

import net.minecraft.network.chat.Component;

/**
 * A stable, player-facing explanation for why a logistics trip cannot move.
 *
 * <p>The wire ids are an explicit protocol. They are packed into the low
 * three bits of a settler's synced logistics byte, so neither declaration
 * order nor enum ordinal may ever become network state. Unknown values fail
 * closed to {@link #NONE}: a damaged or newer packet may hide a diagnostic,
 * but it may never invent the wrong one or crash a client.
 */
public enum StopReason {
    NONE(0, null),
    WAITING_INPUT(1, "hearthstead.logistics.stop.waiting_input"),
    CHEST_FULL(2, "hearthstead.logistics.stop.chest_full"),
    HEARTH_FULL(3, "hearthstead.logistics.stop.hearth_full"),
    NO_WAREHOUSE_SPACE(4, "hearthstead.logistics.stop.no_warehouse_space"),
    RESERVED_BY_OTHER(5, "hearthstead.logistics.stop.reserved_by_other"),
    RESTING_AFTER_FAIL(6, "hearthstead.logistics.stop.resting_after_fail"),
    NO_PATH(7, "hearthstead.logistics.stop.no_path");

    private static final StopReason[] BY_WIRE_ID = new StopReason[8];

    static {
        for (StopReason reason : values()) {
            if (reason.wireId < 0 || reason.wireId >= BY_WIRE_ID.length
                || BY_WIRE_ID[reason.wireId] != null) {
                throw new IllegalStateException("Invalid logistics stop wire id: "
                    + reason + "=" + reason.wireId);
            }
            BY_WIRE_ID[reason.wireId] = reason;
        }
    }

    private final int wireId;
    private final String translationKey;

    StopReason(int wireId, String translationKey) {
        this.wireId = wireId;
        this.translationKey = translationKey;
    }

    public int wireId() {
        return wireId;
    }

    public static StopReason fromWireId(int wireId) {
        StopReason decoded = wireId >= 0 && wireId < BY_WIRE_ID.length
            ? BY_WIRE_ID[wireId] : null;
        return decoded == null ? NONE : decoded;
    }

    public Component displayName() {
        return translationKey == null ? Component.empty()
            : Component.translatable(translationKey);
    }

    /** Amber means waiting; red means the player must change the world. */
    public boolean isWaiting() {
        return this == WAITING_INPUT || this == RESERVED_BY_OTHER
            || this == RESTING_AFTER_FAIL;
    }
}
