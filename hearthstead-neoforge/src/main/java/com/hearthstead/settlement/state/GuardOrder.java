package com.hearthstead.settlement.state;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One revisioned order owned by one exact Guard.
 *
 * <p>The four original mode wire ids remain frozen. Player-authored Stand and
 * Tower modes are appended, never aliases for an old id. A bound order also
 * carries the settlement, Guard, dimension, issuer and workplace identities
 * needed to reject a copied or stale record after reload.
 */
public final class GuardOrder {
    public static final int DATA_VERSION = 2;
    public static final int MIN_PATROL_POINTS = 2;
    public static final int MAX_PATROL_POINTS = 8;
    public static final int MIN_LEASH_RADIUS = 2;
    public static final int MAX_LEASH_RADIUS = 32;
    public static final int DEFAULT_LEASH_RADIUS = 8;
    public static final int MIN_FACING_ARC = 30;
    public static final int MAX_FACING_ARC = 180;
    public static final int DEFAULT_FACING_ARC = 90;

    public enum Mode {
        NONE(0, "none"),
        /** Legacy settlement-global mode retained only for save/wire safety. */
        RALLY_HERE(1, "rally_here"),
        /** Legacy settlement-global mode retained only for save/wire safety. */
        DEFEND_HEARTH(2, "defend_hearth"),
        PATROL_ROUTE(3, "patrol_route"),
        STAND_POST(4, "stand_post"),
        TOWER_POST(5, "tower_post");

        private final int wireId;
        private final String id;

        Mode(int wireId, String id) {
            this.wireId = wireId;
            this.id = id;
        }

        public int wireId() {
            return wireId;
        }

        public String id() {
            return id;
        }

        public static Optional<Mode> tryFromWireId(int wireId) {
            for (Mode mode : values()) {
                if (mode.wireId == wireId) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }

        public static Optional<Mode> tryFromId(String id) {
            if (id == null) {
                return Optional.empty();
            }
            for (Mode mode : values()) {
                if (mode.id.equals(id)) {
                    return Optional.of(mode);
                }
            }
            return Optional.empty();
        }
    }

    public enum Traversal {
        LOOP(0, "loop"),
        PING_PONG(1, "ping_pong");

        private final int wireId;
        private final String id;

        Traversal(int wireId, String id) {
            this.wireId = wireId;
            this.id = id;
        }

        public int wireId() {
            return wireId;
        }

        public String id() {
            return id;
        }

        public static Optional<Traversal> tryFromWireId(int wireId) {
            return wireId == LOOP.wireId ? Optional.of(LOOP)
                : wireId == PING_PONG.wireId ? Optional.of(PING_PONG)
                : Optional.empty();
        }
    }

    private UUID settlementId;
    private UUID guardId;
    private ResourceLocation dimension;
    private UUID issuerId;
    private UUID linkedBuildingId;
    private boolean migratedLegacy;
    private Mode mode = Mode.NONE;
    private BlockPos pos;
    private Direction facing = Direction.NORTH;
    private int leashRadius = DEFAULT_LEASH_RADIUS;
    private int facingArc = DEFAULT_FACING_ARC;
    private Traversal traversal = Traversal.LOOP;
    private long untilGameTime;
    private long issuedGameTime;
    private long updatedGameTime;
    private int revision;
    private final List<BlockPos> patrolPoints = new ArrayList<>();

    /** Legacy/test-only unbound state. Runtime authority uses {@link #bound}. */
    public GuardOrder() {
    }

    public static GuardOrder bound(UUID settlementId, UUID guardId,
                                   ResourceLocation dimension) {
        if (settlementId == null || guardId == null || dimension == null) {
            throw new IllegalArgumentException("Guard order identity is required");
        }
        GuardOrder order = new GuardOrder();
        order.setIdentity(settlementId, guardId, dimension);
        return order;
    }

    public boolean bound() {
        return settlementId != null && guardId != null && dimension != null;
    }

    public Optional<UUID> settlementId() { return Optional.ofNullable(settlementId); }
    public Optional<UUID> guardId() { return Optional.ofNullable(guardId); }
    public Optional<ResourceLocation> dimension() { return Optional.ofNullable(dimension); }
    /** Empty only for a truthfully migrated v7 command with no player id. */
    public Optional<UUID> issuerId() { return Optional.ofNullable(issuerId); }
    public Optional<UUID> linkedBuildingId() { return Optional.ofNullable(linkedBuildingId); }
    public boolean migratedLegacy() { return migratedLegacy; }
    public Mode mode() { return mode; }
    public Optional<BlockPos> pos() { return Optional.ofNullable(pos); }
    public Direction facing() { return facing; }
    public int leashRadius() { return leashRadius; }
    public int facingArc() { return facingArc; }
    public Traversal traversal() { return traversal; }
    public long untilGameTime() { return untilGameTime; }
    public long issuedGameTime() { return issuedGameTime; }
    public long updatedGameTime() { return updatedGameTime; }
    public int revision() { return revision; }
    public boolean revisionSaturated() { return revision == Integer.MAX_VALUE; }

    /** Immutable, wire-safe view in the exact order this Guard will walk it. */
    public List<BlockPos> patrolPoints() { return List.copyOf(patrolPoints); }

    public boolean activeAt(long gameTime) {
        return mode != Mode.NONE && pos != null && gameTime < untilGameTime;
    }

    public Mode modeAt(long gameTime) {
        return activeAt(gameTime) ? mode : Mode.NONE;
    }

    /** Strict player-issued stand command. */
    public boolean issueStand(BlockPos newPos, Direction newFacing,
                              int newLeashRadius, UUID issuer,
                              UUID buildingId, long now) {
        if (!canAuthor(issuer, buildingId, now) || newPos == null
            || newFacing == null || !newFacing.getAxis().isHorizontal()
            || newLeashRadius < MIN_LEASH_RADIUS
            || newLeashRadius > MAX_LEASH_RADIUS) {
            return false;
        }
        mode = Mode.STAND_POST;
        pos = newPos.immutable();
        facing = newFacing;
        leashRadius = newLeashRadius;
        facingArc = DEFAULT_FACING_ARC;
        traversal = Traversal.LOOP;
        untilGameTime = Long.MAX_VALUE;
        commitAuthor(issuer, buildingId, now);
        return true;
    }

    /** Strict player-issued watchtower command. */
    public boolean issueTower(BlockPos postCell, Direction newFacing,
                              int newFacingArc, UUID issuer,
                              UUID watchtowerBuildingId, long now) {
        if (!canAuthor(issuer, watchtowerBuildingId, now) || postCell == null
            || newFacing == null || !newFacing.getAxis().isHorizontal()
            || newFacingArc < MIN_FACING_ARC
            || newFacingArc > MAX_FACING_ARC) {
            return false;
        }
        mode = Mode.TOWER_POST;
        pos = postCell.immutable();
        facing = newFacing;
        facingArc = newFacingArc;
        leashRadius = DEFAULT_LEASH_RADIUS;
        traversal = Traversal.LOOP;
        untilGameTime = Long.MAX_VALUE;
        commitAuthor(issuer, watchtowerBuildingId, now);
        return true;
    }

    /** Adds one unique physical point to this Guard's bounded route draft. */
    public boolean appendPatrolPoint(BlockPos point, UUID issuer,
                                     UUID buildingId, long now) {
        if (!canAuthor(issuer, buildingId, now) || point == null
            || patrolPoints.size() >= MAX_PATROL_POINTS
            || patrolPoints.contains(point)) {
            return false;
        }
        patrolPoints.add(point.immutable());
        if (mode == Mode.PATROL_ROUTE) {
            pos = patrolPoints.getFirst();
        }
        commitAuthor(issuer, buildingId, now);
        return true;
    }

    public boolean removeLastPatrolPoint(UUID issuer, UUID buildingId,
                                         long now) {
        if (!canAuthor(issuer, buildingId, now) || patrolPoints.isEmpty()) {
            return false;
        }
        patrolPoints.removeLast();
        if (mode == Mode.PATROL_ROUTE) {
            if (patrolPoints.size() < MIN_PATROL_POINTS) {
                clearActive();
            } else {
                pos = patrolPoints.getFirst();
            }
        }
        commitAuthor(issuer, buildingId, now);
        return true;
    }

    public boolean issuePatrol(Traversal newTraversal, UUID issuer,
                               UUID buildingId, long now) {
        if (!canAuthor(issuer, buildingId, now) || newTraversal == null
            || !validPatrol(patrolPoints, true)) {
            return false;
        }
        mode = Mode.PATROL_ROUTE;
        pos = patrolPoints.getFirst();
        traversal = newTraversal;
        leashRadius = DEFAULT_LEASH_RADIUS;
        facingArc = DEFAULT_FACING_ARC;
        untilGameTime = Long.MAX_VALUE;
        commitAuthor(issuer, buildingId, now);
        return true;
    }

    public boolean toggleTraversal(UUID issuer, UUID buildingId, long now) {
        if (!canAuthor(issuer, buildingId, now)
            || patrolPoints.size() < MIN_PATROL_POINTS) return false;
        traversal = traversal == Traversal.LOOP
            ? Traversal.PING_PONG : Traversal.LOOP;
        commitAuthor(issuer, buildingId, now);
        return true;
    }

    public boolean clear(UUID issuer, UUID buildingId, long now) {
        if (!canAuthor(issuer, buildingId, now) || mode == Mode.NONE) {
            return false;
        }
        clearActive();
        commitAuthor(issuer, buildingId, now);
        return true;
    }

    /**
     * Legacy API retained for v0-v7 tests and migration only. It cannot author
     * the new Stand/Tower modes and is never called by the network path.
     */
    public boolean issue(Mode newMode, BlockPos newPos, long newUntilGameTime) {
        if (bound() || newMode == null || revisionSaturated()) {
            return false;
        }
        if (newMode == Mode.NONE) {
            if (mode == Mode.NONE) return false;
            clearInternalLegacy();
            return true;
        }
        if (newMode == Mode.PATROL_ROUTE || newMode == Mode.STAND_POST
            || newMode == Mode.TOWER_POST || newPos == null
            || newUntilGameTime <= 0L) {
            return false;
        }
        mode = newMode;
        pos = newPos.immutable();
        untilGameTime = newUntilGameTime;
        revision++;
        return true;
    }

    public boolean appendPatrolPoint(BlockPos point) {
        if (bound() || revisionSaturated() || point == null
            || patrolPoints.size() >= MAX_PATROL_POINTS
            || patrolPoints.contains(point)) return false;
        patrolPoints.add(point.immutable());
        if (mode == Mode.PATROL_ROUTE) pos = patrolPoints.getFirst();
        revision++;
        return true;
    }

    public boolean removeLastPatrolPoint() {
        if (bound() || revisionSaturated() || patrolPoints.isEmpty()) return false;
        patrolPoints.removeLast();
        if (mode == Mode.PATROL_ROUTE) {
            if (patrolPoints.size() < MIN_PATROL_POINTS) {
                mode = Mode.NONE;
                pos = null;
                untilGameTime = 0L;
            } else pos = patrolPoints.getFirst();
        }
        revision++;
        return true;
    }

    public boolean issuePatrol(long newUntilGameTime) {
        if (bound() || revisionSaturated() || newUntilGameTime <= 0L
            || !validPatrol(patrolPoints, true)) return false;
        mode = Mode.PATROL_ROUTE;
        pos = patrolPoints.getFirst();
        untilGameTime = newUntilGameTime;
        revision++;
        return true;
    }

    public boolean clear() { return issue(Mode.NONE, null, 0L); }

    /** Clears an expired legacy command; strict player orders never expire. */
    public boolean expireIfNeeded(long gameTime) {
        if (mode == Mode.NONE || gameTime < untilGameTime || revisionSaturated()) {
            return false;
        }
        if (bound()) {
            clearActive();
            revision++;
            updatedGameTime = Math.max(updatedGameTime, gameTime);
            return true;
        }
        clearInternalLegacy();
        return true;
    }

    /** Exact identity match required by AI, readiness and mutation services. */
    public boolean ownedBy(UUID settlement, UUID guard,
                           ResourceLocation expectedDimension) {
        return bound() && settlementId.equals(settlement)
            && guardId.equals(guard) && dimension.equals(expectedDimension);
    }

    public CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", DATA_VERSION);
        tag.putInt("ModeWireId", mode.wireId());
        tag.putString("Mode", mode.id());
        tag.putInt("Revision", revision);
        if (bound()) {
            tag.putUUID("Settlement", settlementId);
            tag.putUUID("Guard", guardId);
            tag.putString("Dimension", dimension.toString());
            tag.putBoolean("MigratedLegacy", migratedLegacy);
            if (issuerId != null) tag.putUUID("Issuer", issuerId);
            if (linkedBuildingId != null) tag.putUUID("Building", linkedBuildingId);
            tag.putLong("IssuedAt", issuedGameTime);
            tag.putLong("UpdatedAt", updatedGameTime);
        }
        if (mode != Mode.NONE && pos != null) {
            tag.put("Pos", NbtUtils.writeBlockPos(pos));
            tag.putLong("Until", untilGameTime);
            tag.putInt("Facing", facing.get3DDataValue());
            tag.putInt("LeashRadius", leashRadius);
            tag.putInt("FacingArc", facingArc);
            tag.putInt("TraversalWireId", traversal.wireId());
            tag.putString("Traversal", traversal.id());
        }
        if (!patrolPoints.isEmpty()) {
            ListTag points = new ListTag();
            for (BlockPos point : patrolPoints) points.add(NbtUtils.writeBlockPos(point));
            tag.put("PatrolPoints", points);
        }
        return tag;
    }

    /** Compatibility decoder for standalone v7 tests and the legacy field. */
    public static GuardOrder readNbt(CompoundTag tag) {
        GuardOrder decoded = decode(tag, false);
        return decoded == null ? new GuardOrder() : decoded;
    }

    /** Strict nullable decoder used by the v1-v7 settlement migration seam. */
    @Nullable
    public static GuardOrder tryReadLegacyNbt(@Nullable CompoundTag tag) {
        GuardOrder decoded = decode(tag, false);
        return decoded != null && !decoded.bound() ? decoded : null;
    }

    /** Strict decoder for a current per-Guard entry. */
    @Nullable
    public static GuardOrder readBoundNbt(@Nullable CompoundTag tag) {
        GuardOrder decoded = decode(tag, true);
        return decoded != null && decoded.bound() ? decoded : null;
    }

    /**
     * Moves a v7 global command to one already-proven unique Guard. The absent
     * issuer is deliberately preserved as absent rather than fabricated.
     */
    public GuardOrder migrateTo(UUID settlement, UUID guard,
                                ResourceLocation targetDimension,
                                UUID buildingId, long now) {
        if (bound() || settlement == null || guard == null
            || targetDimension == null || buildingId == null) {
            throw new IllegalArgumentException("Invalid legacy Guard migration");
        }
        GuardOrder migrated = bound(settlement, guard, targetDimension);
        migrated.mode = mode;
        migrated.pos = pos == null ? null : pos.immutable();
        migrated.facing = facing;
        migrated.leashRadius = leashRadius;
        migrated.facingArc = facingArc;
        migrated.traversal = traversal;
        migrated.untilGameTime = untilGameTime;
        migrated.revision = revision;
        migrated.patrolPoints.addAll(patrolPoints);
        migrated.linkedBuildingId = buildingId;
        migrated.issuedGameTime = Math.max(0L, now);
        migrated.updatedGameTime = Math.max(0L, now);
        migrated.migratedLegacy = true;
        return migrated;
    }

    public boolean hasLegacyContent() {
        return mode != Mode.NONE || !patrolPoints.isEmpty() || revision != 0;
    }

    private boolean canAuthor(UUID issuer, UUID buildingId, long now) {
        return bound() && issuer != null && buildingId != null && now >= 0L
            && !revisionSaturated();
    }

    private void commitAuthor(UUID issuer, UUID buildingId, long now) {
        issuerId = issuer;
        linkedBuildingId = buildingId;
        migratedLegacy = false;
        if (revision == 0) issuedGameTime = now;
        updatedGameTime = now;
        revision++;
    }

    private void setIdentity(UUID settlement, UUID guard,
                             ResourceLocation targetDimension) {
        settlementId = settlement;
        guardId = guard;
        dimension = targetDimension;
    }

    private void clearActive() {
        mode = Mode.NONE;
        pos = null;
        untilGameTime = 0L;
    }

    private void clearInternalLegacy() {
        clearActive();
        revision++;
    }

    @Nullable
    private static GuardOrder decode(@Nullable CompoundTag tag,
                                     boolean requireBound) {
        if (tag == null || !tag.contains("ModeWireId", Tag.TAG_INT)
            || !tag.contains("Mode", Tag.TAG_STRING)
            || !tag.contains("Revision", Tag.TAG_INT)) return null;
        Optional<Mode> decodedMode = Mode.tryFromWireId(tag.getInt("ModeWireId"));
        int decodedRevision = tag.getInt("Revision");
        if (decodedMode.isEmpty()
            || !decodedMode.get().id().equals(tag.getString("Mode"))
            || decodedRevision < 0) return null;

        boolean claimsBound = tag.hasUUID("Settlement") || tag.hasUUID("Guard")
            || tag.contains("Dimension");
        GuardOrder order = new GuardOrder();
        if (requireBound || claimsBound) {
            if (!tag.contains("DataVersion", Tag.TAG_INT)
                || tag.getInt("DataVersion") != DATA_VERSION
                || !tag.hasUUID("Settlement") || !tag.hasUUID("Guard")
                || !tag.contains("Dimension", Tag.TAG_STRING)
                || !tag.contains("MigratedLegacy", Tag.TAG_BYTE)
                || !tag.contains("IssuedAt", Tag.TAG_LONG)
                || !tag.contains("UpdatedAt", Tag.TAG_LONG)) return null;
            ResourceLocation decodedDimension = ResourceLocation.tryParse(
                tag.getString("Dimension"));
            long issuedAt = tag.getLong("IssuedAt");
            long updatedAt = tag.getLong("UpdatedAt");
            boolean migrated = tag.getBoolean("MigratedLegacy");
            if (decodedDimension == null || issuedAt < 0L
                || updatedAt < issuedAt
                || migrated && tag.hasUUID("Issuer")
                || !migrated && decodedRevision > 0
                    && !tag.hasUUID("Issuer")
                || decodedRevision > 0 && !tag.hasUUID("Building")) return null;
            order.setIdentity(tag.getUUID("Settlement"), tag.getUUID("Guard"),
                decodedDimension);
            order.issuerId = tag.hasUUID("Issuer") ? tag.getUUID("Issuer") : null;
            order.linkedBuildingId = tag.hasUUID("Building")
                ? tag.getUUID("Building") : null;
            order.migratedLegacy = migrated;
            order.issuedGameTime = issuedAt;
            order.updatedGameTime = updatedAt;
        }

        List<BlockPos> decodedPoints = decodePatrolPoints(tag);
        if (decodedPoints == null) return null;
        order.patrolPoints.addAll(decodedPoints);
        order.revision = decodedRevision;
        order.mode = decodedMode.get();
        if (requireBound) {
            if (decodedRevision == 0 && (order.mode != Mode.NONE
                    || !order.patrolPoints.isEmpty() || order.migratedLegacy
                    || order.issuerId != null || order.linkedBuildingId != null
                    || order.issuedGameTime != 0L
                    || order.updatedGameTime != 0L)
                || decodedRevision > 0 && order.linkedBuildingId == null
                || order.migratedLegacy && (order.mode == Mode.STAND_POST
                    || order.mode == Mode.TOWER_POST)
                || !order.migratedLegacy && (order.mode == Mode.RALLY_HERE
                    || order.mode == Mode.DEFEND_HEARTH)) {
                return null;
            }
        }
        if (order.mode == Mode.NONE) {
            if (requireBound && (tag.contains("Pos") || tag.contains("Until")
                || tag.contains("Facing") || tag.contains("LeashRadius")
                || tag.contains("FacingArc")
                || tag.contains("TraversalWireId")
                || tag.contains("Traversal"))) return null;
            return order;
        }
        if (!tag.contains("Pos", Tag.TAG_INT_ARRAY)
            || !tag.contains("Until", Tag.TAG_LONG)) return null;
        BlockPos decodedPos = NbtUtils.readBlockPos(tag, "Pos").orElse(null);
        long decodedUntil = tag.getLong("Until");
        if (decodedPos == null || decodedUntil <= 0L) return null;
        if (order.mode == Mode.PATROL_ROUTE
            && (!validPatrol(order.patrolPoints, true)
                || !decodedPos.equals(order.patrolPoints.getFirst()))) return null;
        if (requireBound && (!tag.contains("Facing", Tag.TAG_INT)
                || !tag.contains("LeashRadius", Tag.TAG_INT)
                || !tag.contains("FacingArc", Tag.TAG_INT)
                || !tag.contains("TraversalWireId", Tag.TAG_INT)
                || !tag.contains("Traversal", Tag.TAG_STRING))) {
            return null;
        }
        if (tag.contains("Facing", Tag.TAG_INT)) {
            int facingWire = tag.getInt("Facing");
            if (facingWire < 0 || facingWire > 5) return null;
            Direction decodedFacing = Direction.from3DDataValue(facingWire);
            if (!decodedFacing.getAxis().isHorizontal()) return null;
            order.facing = decodedFacing;
        } else if (order.mode == Mode.STAND_POST
            || order.mode == Mode.TOWER_POST) return null;
        int decodedLeash = tag.contains("LeashRadius", Tag.TAG_INT)
            ? tag.getInt("LeashRadius") : DEFAULT_LEASH_RADIUS;
        int decodedArc = tag.contains("FacingArc", Tag.TAG_INT)
            ? tag.getInt("FacingArc") : DEFAULT_FACING_ARC;
        Traversal decodedTraversal = tag.contains("TraversalWireId", Tag.TAG_INT)
            ? Traversal.tryFromWireId(tag.getInt("TraversalWireId")).orElse(null)
            : Traversal.LOOP;
        if (decodedLeash < MIN_LEASH_RADIUS || decodedLeash > MAX_LEASH_RADIUS
            || decodedArc < MIN_FACING_ARC || decodedArc > MAX_FACING_ARC
            || decodedTraversal == null
            || tag.contains("Traversal", Tag.TAG_STRING)
                && !decodedTraversal.id().equals(tag.getString("Traversal"))) return null;
        order.pos = decodedPos.immutable();
        order.untilGameTime = decodedUntil;
        order.leashRadius = decodedLeash;
        order.facingArc = decodedArc;
        order.traversal = decodedTraversal;
        return order;
    }

    @Nullable
    private static List<BlockPos> decodePatrolPoints(CompoundTag tag) {
        if (!tag.contains("PatrolPoints")) return List.of();
        if (!tag.contains("PatrolPoints", Tag.TAG_LIST)) return null;
        ListTag raw = tag.getList("PatrolPoints", Tag.TAG_INT_ARRAY);
        if (raw.size() > MAX_PATROL_POINTS) return null;
        List<BlockPos> decoded = new ArrayList<>(raw.size());
        for (int i = 0; i < raw.size(); i++) {
            int[] xyz = raw.getIntArray(i);
            if (xyz.length != 3) return null;
            decoded.add(new BlockPos(xyz[0], xyz[1], xyz[2]));
        }
        return validPatrol(decoded, false) ? decoded : null;
    }

    private static boolean validPatrol(List<BlockPos> points,
                                       boolean requireMinimum) {
        if (points == null || points.size() > MAX_PATROL_POINTS
            || requireMinimum && points.size() < MIN_PATROL_POINTS) return false;
        return new HashSet<>(points).size() == points.size();
    }
}
