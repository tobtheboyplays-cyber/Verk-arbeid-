package com.hearthstead.settlement.request;

import java.util.Optional;

/** Concrete, persisted reason why an otherwise-live request cannot advance. */
public enum RequestBlocker {
    NONE(0, "none"),
    NO_STOCK(1, "no_stock"),
    RESERVED_BY_OTHER(2, "reserved_by_other"),
    BAG_FULL(3, "bag_full"),
    TARGET_FULL(4, "target_full"),
    SOURCE_UNLOADED(5, "source_unloaded"),
    TARGET_UNLOADED(6, "target_unloaded"),
    NO_PATH(7, "no_path"),
    SOURCE_INVALID(8, "source_invalid"),
    TARGET_INVALID(9, "target_invalid"),
    COURIER_UNAVAILABLE(10, "courier_unavailable"),
    CROSS_SETTLEMENT(11, "cross_settlement"),
    FINGERPRINT_MISMATCH(12, "fingerprint_mismatch"),
    LEASE_EXPIRED(13, "lease_expired"),
    RETURNED_TO_SOURCE(14, "returned_to_source"),
    MALFORMED(15, "malformed"),
    /** Existing equipment rows expose intent, not a fabricated output trace. */
    EQUIPMENT_ADAPTER_LIMITED(16, "equipment_adapter_limited");

    private final int wireId;
    private final String id;

    RequestBlocker(int wireId, String id) {
        this.wireId = wireId;
        this.id = id;
    }

    public int wireId() {
        return wireId;
    }

    public String id() {
        return id;
    }

    public static Optional<RequestBlocker> fromWireId(int wireId) {
        for (RequestBlocker value : values()) {
            if (value.wireId == wireId) {
                return Optional.of(value);
            }
        }
        return Optional.empty();
    }
}
