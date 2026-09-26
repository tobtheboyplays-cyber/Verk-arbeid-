package com.hearthstead.settlement.journey;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Deterministic transaction IDs for persisted server revisions and recovery. */
public final class JourneyTransactionIds {
    public static final int MAX_DOMAIN_LENGTH = 64;

    public static UUID forRevision(String domain, UUID settlementId,
                                   UUID subjectId, long revision) {
        Objects.requireNonNull(settlementId, "settlementId");
        Objects.requireNonNull(subjectId, "subjectId");
        if (!safeDomain(domain) || revision < 0L) {
            throw new IllegalArgumentException("Invalid Journey transaction identity");
        }
        String seed = "hearthstead-journey-v2|" + domain + "|"
            + settlementId + "|" + subjectId + "|" + revision;
        return UUID.nameUUIDFromBytes(seed.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean safeDomain(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_DOMAIN_LENGTH) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= '0' && c <= '9')
                && c != '_' && c != '-') {
                return false;
            }
        }
        return true;
    }

    private JourneyTransactionIds() {
    }
}
