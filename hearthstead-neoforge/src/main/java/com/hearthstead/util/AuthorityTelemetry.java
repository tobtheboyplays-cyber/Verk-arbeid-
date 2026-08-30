package com.hearthstead.util;

import com.hearthstead.Hearthstead;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.regex.Pattern;

/**
 * Sparse, read-only evidence emitted by ordinary authoritative transactions.
 *
 * <p>The logger is deliberately not a QA command or a second state store. A
 * caller supplies values it has already committed (or the equal before/after
 * values of an explicit refusal), and this class writes one bounded line only
 * while Minecraft's genuine server thread owns the call. It never reads a
 * client claim, scans the world, mutates gameplay state, or writes a file.
 * Consequently the integrated single-player Log4j line itself is useful
 * native evidence without changing the transaction it observes.
 *
 * <p>Version 1 is a fixed set of fifteen key/value fields. String fields use a
 * conservative token alphabet and are truncated before formatting, so player
 * names, translation text, newlines and control characters can never forge a
 * second log record. Item conservation is explicit: {@code item_after -
 * item_before} must equal {@code item_expected_delta}.
 */
public final class AuthorityTelemetry {
    public static final String MARKER = "HEARTHSTEAD_AUTHORITY_V1";
    public static final int MAX_LINE_LENGTH = 768;
    public static final int MAX_TOKEN_LENGTH = 96;
    private static final int MAX_RECORDS_PER_SERVER_TICK = 24;
    private static final Pattern CANONICAL_LONG = Pattern.compile(
        "-?(?:0|[1-9][0-9]*)");
    private static final String CANONICAL_UUID =
        "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private static final String RAID_OBJECTIVE =
        "(?:korn|blod|brann|losepenger)";
    private static final Pattern RAID_WARNING_TARGET = Pattern.compile(
        "first_raid_warning:(0|[1-9][0-9]*):captain:(" + CANONICAL_UUID + ")");
    private static final Pattern RAID_WARNING_REASON = Pattern.compile(
        "persisted_plan:(" + RAID_OBJECTIVE
            + "):approach_bits:(0|[1-9][0-9]*)");
    private static final Pattern RAID_AFTERMATH_TARGET = Pattern.compile(
        "raid_aftermath:(0|[1-9][0-9]*):report:([0-9a-f]{64})");
    private static final Pattern RAID_AFTERMATH_REASON = Pattern.compile(
        "report_viewed:(?:held|lost):(" + RAID_OBJECTIVE
            + "):stolen:(0|[1-9][0-9]*):hurt:(0|[1-9][0-9]*)"
            + ":stage:(?:rolig|uro|varsel|beleiring)");
    private static final Pattern RAID_RESOLVED_TARGET = Pattern.compile(
        "(?:first_raid|recurring_raid):(0|[1-9][0-9]*)");
    private static final Pattern RAID_RESOLVED_REASON = Pattern.compile(
        "settlement_(?:held|hit|lost)");
    private static final long MAX_REPORTED_RAID_NIGHT = 10_000_000L;
    private static final long MAX_REPORTED_RAID_COUNT = 1_000_000L;

    private static final Map<MinecraftServer, TickBudget> BUDGETS =
        new WeakHashMap<>();

    /** Stable event vocabulary. Renaming an entry is a release-contract change. */
    public enum Event {
        FOUNDING_COMMITTED,
        DEVELOPMENT_NODE_COMMITTED,
        PLAN_UNLOCK_COMMITTED,
        DOCTRINE_COMMITTED,
        EMBLEM_PURCHASED,
        MAYOR_APPOINTED,
        EMPLOYMENT_AUTO_HIRED,
        EQUIPMENT_REQUEST_OPENED,
        EQUIPMENT_REQUEST_CLAIMED,
        EQUIPMENT_ITEM_PICKED_UP,
        EQUIPMENT_ITEM_DELIVERED,
        REQUEST_LEDGER_VIEWED,
        OUTPUT_PICKUP_REQUEST_OPENED,
        OUTPUT_PICKUP_RESERVED,
        OUTPUT_PICKUP_PICKED_UP,
        OUTPUT_PICKUP_DELIVERED,
        OUTPUT_PICKUP_SATISFIED,
        OUTPUT_PICKUP_BLOCKED,
        COURIER_ROUTE_CLAIMED,
        COURIER_ITEM_PICKED_UP,
        COURIER_ITEM_DELIVERED,
        RECRUITMENT_QUALIFICATION_STARTED,
        TRAVELER_ARRIVED_AT_TAVERN,
        TRAVELER_ADMITTED,
        RECRUITMENT_COMMITTED,
        GUARD_ORDER_COMMITTED,
        RAID_READINESS_COMMITTED,
        RAID_WARNING_COMMITTED,
        RAID_STARTED,
        RAID_RESOLVED,
        RAID_REWARD_ISSUED,
        RAID_AFTERMATH_VIEWED,
        BLESSING_OFFER_COMMITTED,
        BLESSING_BOUND_SETTLER,
        BLESSING_BOUND_BUILDING,
        WORK_ZONE_PREVIEWED,
        WORK_ZONE_CANCELLED,
        WORK_ZONE_COMMITTED,
        LUMBER_TREE_COMMITTED,
        FARM_SEED_PLANTED_COMMITTED,
        FARM_HARVEST_COMMITTED,
        WORKPLACE_OUTPUT_COMMITTED,
        /** target=building:&lt;uuid&gt;; reason=type:&lt;building_id&gt;. */
        BUILDING_LINK_COMMITTED,
        /** target=settler:&lt;uuid&gt;:slot:&lt;n&gt;; reason=player_to_settler|settler_to_player. */
        SETTLER_INVENTORY_TRANSFER_COMMITTED,
        /** target=guard:&lt;uuid&gt;; reason=combat_xp:&lt;source_id&gt;. */
        GUARD_XP_COMMITTED,
        /** target=attacker:&lt;uuid&gt;/victim:&lt;uuid&gt;; reason=attack:&lt;action_uuid&gt;. */
        MELEE_CONTACT_COMMITTED,
        /** target=guard:&lt;uuid&gt;/attacker:&lt;uuid&gt;; reason=block:&lt;action_uuid&gt;. */
        SHIELD_BLOCK_COMMITTED,
        /** target=archer:&lt;uuid&gt;/victim:&lt;uuid&gt;; reason=projectile:&lt;uuid&gt;. */
        ARCHER_CONTACT_COMMITTED,
        STATE_LOAD_SUMMARY,
        AUTHORITY_REJECTED
    }

    public enum Result {
        COMMITTED,
        REJECTED,
        OBSERVED
    }

    /**
     * Immutable values captured by the transaction owner. The two generic
     * count fields describe the primary authoritative collection (population,
     * revision-owned list, patrol points, request rows, and so on). Item
     * fields describe the exact physical item boundary when one exists.
     */
    public record Fields(@Nullable UUID settlementId, String target,
                         long revisionBefore, long revisionAfter,
                         long countBefore, long countAfter,
                         String item, long itemBefore, long itemAfter,
                         long itemExpectedDelta, String reason) {
        public Fields {
            target = token(target, "none");
            item = token(item, "none");
            reason = token(reason, "none");
        }

        public static Fields state(@Nullable UUID settlementId, String target,
                                   long revisionBefore, long revisionAfter,
                                   long countBefore, long countAfter,
                                   String reason) {
            return new Fields(settlementId, target, revisionBefore,
                revisionAfter, countBefore, countAfter, "none", 0L, 0L,
                0L, reason);
        }

        public static Fields items(@Nullable UUID settlementId, String target,
                                   long revisionBefore, long revisionAfter,
                                   long countBefore, long countAfter,
                                   String item, long itemBefore, long itemAfter,
                                   long itemExpectedDelta, String reason) {
            return new Fields(settlementId, target, revisionBefore,
                revisionAfter, countBefore, countAfter, item, itemBefore,
                itemAfter, itemExpectedDelta, reason);
        }

        public boolean itemConserved() {
            return itemAfter - itemBefore == itemExpectedDelta;
        }
    }

    /** Parsed representation used by validators and contract tests. */
    public record Parsed(Event event, Result result, String settlement,
                         String target, long revisionBefore,
                         long revisionAfter, long countBefore, long countAfter,
                         String item, long itemBefore, long itemAfter,
                         long itemExpectedDelta, boolean itemConserved,
                         String reason, long tick) {
    }

    /**
     * Emits one record iff this is Minecraft's genuine server thread and its
     * bounded per-tick telemetry budget is still available.
     *
     * @return true only when a line was actually handed to Log4j
     */
    public static boolean emit(ServerLevel level, Event event, Result result,
                               Fields fields) {
        if (level == null || event == null || result == null || fields == null) {
            return false;
        }
        MinecraftServer server = level.getServer();
        if (server == null || !server.isSameThread()) {
            return false;
        }
        long tick = level.getGameTime();
        String line;
        try {
            line = format(event, result, fields, tick);
        } catch (RuntimeException invalidObservation) {
            return false;
        }
        if (!reserve(server, tick, line)) {
            return false;
        }
        try {
            Hearthstead.LOGGER.info(line);
            return true;
        } catch (RuntimeException loggingFailure) {
            // Evidence must never turn an already-valid game transaction into
            // a failure. The native gate will simply lack this required line.
            return false;
        }
    }

    /** Pure formatter; package-independent validators may reuse it in tests. */
    public static String format(Event event, Result result, Fields fields,
                                long tick) {
        if (event == null || result == null || fields == null
            || !validSemantics(event, result, fields, tick)) {
            throw new IllegalArgumentException(
                "invalid authority telemetry semantics");
        }
        String settlement = fields.settlementId() == null
            ? "none" : fields.settlementId().toString();
        String line = MARKER
            + " event=" + event.name()
            + " result=" + result.name()
            + " settlement=" + settlement
            + " target=" + fields.target()
            + " revision_before=" + fields.revisionBefore()
            + " revision_after=" + fields.revisionAfter()
            + " count_before=" + fields.countBefore()
            + " count_after=" + fields.countAfter()
            + " item=" + fields.item()
            + " item_before=" + fields.itemBefore()
            + " item_after=" + fields.itemAfter()
            + " item_expected_delta=" + fields.itemExpectedDelta()
            + " item_conserved=" + fields.itemConserved()
            + " reason=" + fields.reason()
            + " tick=" + tick;
        if (line.length() > MAX_LINE_LENGTH) {
            throw new IllegalArgumentException("authority telemetry line exceeds "
                + MAX_LINE_LENGTH + " characters");
        }
        return line;
    }

    /**
     * Strict parser for the versioned payload (with or without a Log4j prefix).
     * Unknown, duplicate, missing, overlong, malformed or conservation-false
     * records fail closed.
     */
    public static Optional<Parsed> parse(String line) {
        if (line == null || line.length() > 4_096
            || line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            return Optional.empty();
        }
        int marker = line.indexOf(MARKER);
        if (marker < 0) {
            return Optional.empty();
        }
        String payload = line.substring(marker);
        if (payload.length() > MAX_LINE_LENGTH) {
            return Optional.empty();
        }
        String[] parts = payload.split(" ", -1);
        if (parts.length != 16 || !MARKER.equals(parts[0])) {
            return Optional.empty();
        }
        String[] keys = {
            "event", "result", "settlement", "target",
            "revision_before", "revision_after", "count_before",
            "count_after", "item", "item_before", "item_after",
            "item_expected_delta", "item_conserved", "reason", "tick"
        };
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 1; i < parts.length; i++) {
            int equals = parts[i].indexOf('=');
            if (equals <= 0 || equals == parts[i].length() - 1) {
                return Optional.empty();
            }
            String key = parts[i].substring(0, equals);
            String value = parts[i].substring(equals + 1);
            if (!keys[i - 1].equals(key)
                || values.putIfAbsent(key, value) != null
                || !value.equals(token(value, "none"))) {
                return Optional.empty();
            }
        }
        try {
            Event event = Event.valueOf(values.get("event"));
            Result result = Result.valueOf(values.get("result"));
            for (String numeric : new String[]{
                    "revision_before", "revision_after", "count_before",
                    "count_after", "item_before", "item_after",
                    "item_expected_delta", "tick"}) {
                if (!CANONICAL_LONG.matcher(values.get(numeric)).matches()) {
                    return Optional.empty();
                }
            }
            long revisionBefore = Long.parseLong(values.get("revision_before"));
            long revisionAfter = Long.parseLong(values.get("revision_after"));
            long countBefore = Long.parseLong(values.get("count_before"));
            long countAfter = Long.parseLong(values.get("count_after"));
            long itemBefore = Long.parseLong(values.get("item_before"));
            long itemAfter = Long.parseLong(values.get("item_after"));
            long itemExpectedDelta = Long.parseLong(
                values.get("item_expected_delta"));
            boolean conserved = switch (values.get("item_conserved")) {
                case "true" -> true;
                case "false" -> false;
                default -> throw new IllegalArgumentException("not a boolean");
            };
            long tick = Long.parseLong(values.get("tick"));
            String settlement = values.get("settlement");
            if (!"none".equals(settlement)) {
                UUID parsedSettlement = UUID.fromString(settlement);
                if (!parsedSettlement.toString().equals(settlement)) {
                    return Optional.empty();
                }
            }
            // Revisions are opaque server-authored values. Most domains use
            // monotonic non-negative counters, while the Mayor menu's legacy
            // revision is an Objects.hash fingerprint and may legitimately be
            // negative. Counts, item cardinalities and ticks may never be.
            if (countBefore < 0 || countAfter < 0 || itemBefore < 0
                || itemAfter < 0 || tick < 0) {
                return Optional.empty();
            }
            Fields fields = new Fields(
                "none".equals(settlement) ? null : UUID.fromString(settlement),
                values.get("target"), revisionBefore, revisionAfter,
                countBefore, countAfter, values.get("item"), itemBefore,
                itemAfter, itemExpectedDelta, values.get("reason"));
            if (!conserved || !validSemantics(event, result, fields, tick)) {
                return Optional.empty();
            }
            return Optional.of(new Parsed(event, result,
                settlement, values.get("target"),
                revisionBefore, revisionAfter, countBefore, countAfter,
                values.get("item"), itemBefore, itemAfter, itemExpectedDelta,
                true, values.get("reason"), tick));
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }

    /** Mirrors the external native validator's fail-closed V1 semantics. */
    private static boolean validSemantics(Event event, Result result,
                                          Fields fields, long tick) {
        if (fields.countBefore() < 0 || fields.countAfter() < 0
            || fields.itemBefore() < 0 || fields.itemAfter() < 0
            || tick < 0 || !fields.itemConserved()) {
            return false;
        }
        boolean rejected = result == Result.REJECTED;
        if ((event == Event.AUTHORITY_REJECTED) != rejected) {
            return false;
        }
        if (rejected) {
            return fields.revisionBefore() == fields.revisionAfter()
                && fields.countBefore() == fields.countAfter()
                && fields.itemBefore() == fields.itemAfter()
                && fields.itemExpectedDelta() == 0L;
        }
        return switch (event) {
            case RAID_WARNING_COMMITTED -> validRaidWarning(result, fields);
            case RAID_RESOLVED -> validRaidResolved(result, fields);
            case RAID_AFTERMATH_VIEWED -> validRaidAftermath(result, fields);
            default -> true;
        };
    }

    private static boolean validRaidResolved(Result result, Fields fields) {
        if (result != Result.COMMITTED || fields.settlementId() == null
            || !RAID_RESOLVED_TARGET.matcher(fields.target()).matches()
            || !RAID_RESOLVED_REASON.matcher(fields.reason()).matches()
            || !"none".equals(fields.item())
            || fields.itemBefore() != 0L || fields.itemAfter() != 0L
            || fields.itemExpectedDelta() != 0L
            || fields.revisionBefore() < 0L
            || fields.revisionBefore() > 100L
            || fields.revisionAfter() < 0L
            || fields.revisionAfter() > 100L
            || fields.countBefore() > 9L || fields.countAfter() != 0L) {
            return false;
        }
        long expectedPressure = "settlement_held".equals(fields.reason())
            ? Math.min(100L, fields.revisionBefore() + 12L)
            : Math.max(0L, fields.revisionBefore() - 8L);
        return fields.revisionAfter() == expectedPressure;
    }

    private static boolean validRaidWarning(Result result, Fields fields) {
        if (!validSingleStepRaidCommit(result, fields)) {
            return false;
        }
        java.util.regex.Matcher target = RAID_WARNING_TARGET.matcher(
            fields.target());
        java.util.regex.Matcher reason = RAID_WARNING_REASON.matcher(
            fields.reason());
        if (!target.matches() || !reason.matches()
            || canonicalNonNegativeLong(target.group(1), Long.MAX_VALUE) < 0L) {
            return false;
        }
        try {
            int bits = Integer.parseUnsignedInt(reason.group(2));
            float approach = Float.intBitsToFloat(bits);
            return Float.isFinite(approach)
                && approach >= -180.0F && approach < 180.0F;
        } catch (NumberFormatException invalidBits) {
            return false;
        }
    }

    private static boolean validRaidAftermath(Result result, Fields fields) {
        if (!validSingleStepRaidCommit(result, fields)) {
            return false;
        }
        java.util.regex.Matcher target = RAID_AFTERMATH_TARGET.matcher(
            fields.target());
        java.util.regex.Matcher reason = RAID_AFTERMATH_REASON.matcher(
            fields.reason());
        if (!target.matches() || !reason.matches()) {
            return false;
        }
        return canonicalNonNegativeLong(target.group(1),
                MAX_REPORTED_RAID_NIGHT) >= 0L
            && canonicalNonNegativeLong(reason.group(2),
                MAX_REPORTED_RAID_COUNT) >= 0L
            && canonicalNonNegativeLong(reason.group(3),
                MAX_REPORTED_RAID_COUNT) >= 0L;
    }

    private static boolean validSingleStepRaidCommit(Result result,
                                                     Fields fields) {
        return result == Result.COMMITTED
            && fields.settlementId() != null
            && fields.revisionBefore() >= 0L
            && fields.revisionBefore() < Long.MAX_VALUE
            && fields.revisionAfter() == fields.revisionBefore() + 1L
            && fields.countBefore() >= 0L
            && fields.countBefore() < Long.MAX_VALUE
            && fields.countAfter() == fields.countBefore() + 1L
            && "none".equals(fields.item())
            && fields.itemBefore() == 0L
            && fields.itemAfter() == 0L
            && fields.itemExpectedDelta() == 0L;
    }

    private static long canonicalNonNegativeLong(String value, long maximum) {
        try {
            long parsed = Long.parseLong(value);
            return parsed >= 0L && parsed <= maximum ? parsed : -1L;
        } catch (NumberFormatException invalid) {
            return -1L;
        }
    }

    /** True only when {@code raw} would survive the V1 token boundary exactly. */
    public static boolean isCanonicalToken(@Nullable String raw) {
        return raw != null && !raw.isBlank() && raw.length() <= MAX_TOKEN_LENGTH
            && raw.equals(token(raw, "none"));
    }

    /**
     * Canonical length-prefixed SHA-256 for exact facts which cannot all fit in
     * one V1 token. Field boundaries are encoded, so ["ab", "c"] and
     * ["a", "bc"] can never share the same byte stream.
     */
    public static String fingerprint(String domain, String... exactValues) {
        if (domain == null || domain.isBlank() || exactValues == null) {
            throw new IllegalArgumentException("fingerprint inputs are required");
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateFingerprint(digest, domain);
            for (String value : exactValues) {
                if (value == null) {
                    throw new IllegalArgumentException(
                        "fingerprint values may not be null");
                }
                updateFingerprint(digest, value);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void updateFingerprint(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static boolean reserve(MinecraftServer server, long tick,
                                   String line) {
        TickBudget budget = BUDGETS.get(server);
        if (budget == null || budget.tick != tick) {
            budget = new TickBudget(tick);
            BUDGETS.put(server, budget);
        }
        if (budget.lines.containsKey(line)
            || budget.lines.size() >= MAX_RECORDS_PER_SERVER_TICK) {
            return false;
        }
        budget.lines.put(line, Boolean.TRUE);
        return true;
    }

    private static String token(@Nullable String raw, String fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String value = raw.trim();
        StringBuilder safe = new StringBuilder(Math.min(value.length(),
            MAX_TOKEN_LENGTH));
        for (int i = 0; i < value.length()
                && safe.length() < MAX_TOKEN_LENGTH; i++) {
            char c = value.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                || (c >= '0' && c <= '9') || c == '_' || c == '-'
                || c == '.' || c == ':' || c == '/' || c == '@'
                || c == '+') {
                safe.append(c);
            } else {
                safe.append('_');
            }
        }
        return safe.isEmpty() ? fallback : safe.toString();
    }

    private static final class TickBudget {
        private final long tick;
        private final Map<String, Boolean> lines = new LinkedHashMap<>();

        private TickBudget(long tick) {
            this.tick = tick;
        }
    }

    private AuthorityTelemetry() {
    }
}
