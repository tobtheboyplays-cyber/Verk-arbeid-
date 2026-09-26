package com.hearthstead.settlement.state;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;

/**
 * Server-authoritative onboarding state for a settlement's first real
 * production loop: found the Hearth, link a Lumber Camp, hire its lumberer,
 * confirm that camp's physical lumber zone, then store an actual log.
 *
 * <p>The state is monotonic and event-driven. It performs no world scans and
 * no timer polling; the real link, hire and inventory-insertion paths each
 * offer one narrowly validated transition. Directly constructed settlements
 * default to {@link Phase#SKIPPED}, so test/admin fixtures and migrated worlds
 * never wake up inside onboarding by accident. Only successful real founding
 * explicitly calls {@link #fresh()}.
 */
public final class FoundingJourney {
    public static final int DATA_VERSION = 2;
    public static final int MAX_REVISION = 1_000_000;

    public enum Phase {
        BUILD_LUMBER_CAMP(0, "build_lumber_camp"),
        HIRE_LUMBERER(1, "hire_lumberer"),
        // Added in schema v2 without reusing or renumbering any v1 wire id.
        SET_LUMBER_ZONE(5, "set_lumber_zone"),
        DELIVER_FIRST_LOG(2, "deliver_first_log"),
        COMPLETE(3, "complete"),
        SKIPPED(4, "skipped"),
        QUARANTINED(-1, "quarantined");

        private final int wireId;
        private final String id;

        Phase(int wireId, String id) {
            this.wireId = wireId;
            this.id = id;
        }

        public int wireId() {
            return wireId;
        }

        public String id() {
            return id;
        }

        @Nullable
        public static Phase fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> BUILD_LUMBER_CAMP;
                case 1 -> HIRE_LUMBERER;
                case 2 -> DELIVER_FIRST_LOG;
                case 3 -> COMPLETE;
                case 4 -> SKIPPED;
                case 5 -> SET_LUMBER_ZONE;
                case -1 -> QUARANTINED;
                default -> null;
            };
        }
    }

    private Phase phase;
    private int revision;

    /** Fixture/admin-safe default: never creates surprise active onboarding. */
    public FoundingJourney() {
        this(Phase.SKIPPED, 0);
    }

    private FoundingJourney(Phase phase, int revision) {
        this.phase = phase;
        this.revision = revision;
    }

    /** The only state assigned after a fully successful real founding. */
    public static FoundingJourney fresh() {
        return new FoundingJourney(Phase.BUILD_LUMBER_CAMP, 0);
    }

    /** Explicit migration/default state for worlds that predate onboarding. */
    public static FoundingJourney skipped() {
        return new FoundingJourney(Phase.SKIPPED, 0);
    }

    /** Present-but-invalid current data. No transition can leave this state. */
    public static FoundingJourney quarantined() {
        return new FoundingJourney(Phase.QUARANTINED, 0);
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized int revision() {
        return revision;
    }

    public synchronized boolean active() {
        return phase == Phase.BUILD_LUMBER_CAMP
            || phase == Phase.HIRE_LUMBERER
            || phase == Phase.SET_LUMBER_ZONE
            || phase == Phase.DELIVER_FIRST_LOG;
    }

    public synchronized boolean complete() {
        return phase == Phase.COMPLETE;
    }

    public synchronized boolean quarantinedState() {
        return phase == Phase.QUARANTINED;
    }

    public synchronized boolean noteLumberCampLinked() {
        return advance(Phase.BUILD_LUMBER_CAMP, Phase.HIRE_LUMBERER);
    }

    public synchronized boolean noteLumbererHired() {
        return advance(Phase.HIRE_LUMBERER, Phase.SET_LUMBER_ZONE);
    }

    public synchronized boolean noteLumberZoneCommitted() {
        return advance(Phase.SET_LUMBER_ZONE, Phase.DELIVER_FIRST_LOG);
    }

    public synchronized boolean noteFirstLogDelivered() {
        return advance(Phase.DELIVER_FIRST_LOG, Phase.COMPLETE);
    }

    /** Deliberate player choice; terminal, idempotent and never reversible. */
    public synchronized boolean skip() {
        if (!active() || revision >= MAX_REVISION) {
            return false;
        }
        phase = Phase.SKIPPED;
        revision++;
        return true;
    }

    private boolean advance(Phase expected, Phase next) {
        if (phase != expected || revision >= MAX_REVISION) {
            return false;
        }
        phase = next;
        revision++;
        return true;
    }

    public synchronized CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putInt("PhaseWireId", phase.wireId());
        tag.putString("Phase", phase.id());
        tag.putInt("Revision", revision);
        return tag;
    }

    /** Strict current-schema decoder with one explicit, fail-closed v1 migration. */
    public static FoundingJourney readNbt(@Nullable CompoundTag tag) {
        if (tag == null
            || !tag.contains("DataVersion", Tag.TAG_INT)
            || !tag.contains("PhaseWireId", Tag.TAG_INT)
            || !tag.contains("Phase", Tag.TAG_STRING)
            || !tag.contains("Revision", Tag.TAG_INT)) {
            return quarantined();
        }
        int version = tag.getInt("DataVersion");
        if (version == 1) {
            return migrateV1(tag);
        }
        if (version != DATA_VERSION) {
            return quarantined();
        }
        Phase decoded = Phase.fromWireId(tag.getInt("PhaseWireId"));
        int decodedRevision = tag.getInt("Revision");
        if (decoded == null
            || !decoded.id().equals(tag.getString("Phase"))
            || decodedRevision < 0 || decodedRevision > MAX_REVISION
            || !revisionMatchesPhase(decoded, decodedRevision)) {
            return quarantined();
        }
        if (decoded == Phase.QUARANTINED) {
            return quarantined();
        }
        return new FoundingJourney(decoded, decodedRevision);
    }

    /**
     * V1 had no zone phase. An in-progress first-log quest is intentionally
     * moved back to SET_LUMBER_ZONE; completion remains terminal. Wire ids are
     * decoded using the old table before any v2 meaning is considered.
     */
    private static FoundingJourney migrateV1(CompoundTag tag) {
        int wire = tag.getInt("PhaseWireId");
        String id = tag.getString("Phase");
        int revision = tag.getInt("Revision");
        if (revision < 0 || revision > MAX_REVISION) {
            return quarantined();
        }
        return switch (wire) {
            case 0 -> "build_lumber_camp".equals(id) && revision == 0
                ? new FoundingJourney(Phase.BUILD_LUMBER_CAMP, 0) : quarantined();
            case 1 -> "hire_lumberer".equals(id) && revision == 1
                ? new FoundingJourney(Phase.HIRE_LUMBERER, 1) : quarantined();
            case 2 -> "deliver_first_log".equals(id) && revision == 2
                ? new FoundingJourney(Phase.SET_LUMBER_ZONE, 2) : quarantined();
            case 3 -> "complete".equals(id) && revision == 3
                ? new FoundingJourney(Phase.COMPLETE, 4) : quarantined();
            case 4 -> "skipped".equals(id) && revision <= 3
                ? new FoundingJourney(Phase.SKIPPED, revision) : quarantined();
            default -> quarantined();
        };
    }

    /**
     * Only states reachable through the monotonic transition methods are
     * accepted. Without this check a syntactically valid tag could claim, for
     * example, BUILD_LUMBER_CAMP at revision 900000 and permanently strand
     * both progress and the menu's optimistic lock.
     */
    private static boolean revisionMatchesPhase(Phase phase, int revision) {
        return switch (phase) {
            case BUILD_LUMBER_CAMP -> revision == 0;
            case HIRE_LUMBERER -> revision == 1;
            case SET_LUMBER_ZONE -> revision == 2;
            case DELIVER_FIRST_LOG -> revision == 3;
            case COMPLETE -> revision == 4;
            // Zero is the migration/default state; 1..4 are deliberate skips
            // from each active phase.
            case SKIPPED -> revision >= 0 && revision <= 4;
            case QUARANTINED -> false;
        };
    }
}
