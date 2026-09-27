package com.hearthstead.settlement.builder;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.ByteArrayTag;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One construction site: an explicit, ordered list of steps and how far the
 * Builder has got.
 *
 * <p><b>Self-contained on purpose.</b> The step list is frozen when the
 * player confirms (after rotation/mirror), so a blueprint file changing in a
 * later update can never corrupt a half-built house, and a reload resumes at
 * exactly the same step. States are stored through a palette; positions as
 * packed longs.
 *
 * <p><b>Exact-once bookkeeping.</b> {@link #done} marks steps that are
 * finished (placed, cleared, or found already satisfied -- never charged
 * twice); {@link #placed} marks the subset THIS job placed with an item, which
 * is exactly what a Dismantle may take back and refund. Neither bit is ever
 * set anywhere except {@code BuildExecutor}, in the same server tick as the
 * world change it records.
 */
public final class BuildJob {

    public enum Kind {
        BLUEPRINT, DEFENSE_LINE, UPGRADE, DISMANTLE, BARRICADE;

        public static Kind byOrdinal(int ordinal) {
            Kind[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : BLUEPRINT;
        }
    }

    public enum State {
        ACTIVE, COMPLETE, CANCELLED;

        public static State byOrdinal(int ordinal) {
            State[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : ACTIVE;
        }
    }

    /** Step flag: remove what stands here (natural terrain, or overwrite). */
    public static final byte F_CLEAR = 1;
    /** Step flag: fit the typed build plan into the plaque standing here. */
    public static final byte F_FIT_PLAN = 2;
    /** Step flag: take fluid out of a cell planned empty. */
    public static final byte F_DRAIN = 4;
    /** Step flag: soil/cobble under the foundation. */
    public static final byte F_FILL = 8;
    /** Step flag: the cell held a player block when planned (needs overwrite). */
    public static final byte F_PLAYER = 16;
    /** Step flag: temporary scaffolding (placed, then taken down at FINISH). */
    public static final byte F_SCAFFOLD = 32;
    /** Step flag: pour a water source here (a fishery basin, a well). */
    public static final byte F_POUR = 64;
    /**
     * Step flag (with {@link #F_POUR}): this pour costs a water bucket (the
     * first two cells of each connected body; the rest are scooped from the
     * source they make -- the infinite-source rule).
     */
    public static final byte F_POUR_PAID = (byte) 0x80;

    public static final int MAX_STEPS = 4096;

    public final UUID id;
    public final UUID settlementId;
    public final Kind kind;
    /** Blueprint id, line kind ("palisade"/"stone"/"barricade"), or upgraded building id. */
    public final String sourceId;
    public String label;
    public final BlockPos anchor;
    public final int rotation;
    public final boolean mirror;
    public BoundingBox bounds;
    @Nullable
    public final UUID owner;
    public final long createdAt;
    /** Building type id the FIT_PLAN step stamps, or null. */
    @Nullable
    public String fitPlanType;
    /** Defense segment kind: palisade / stone / barricade, or null. */
    @Nullable
    public String segment;
    /** For DISMANTLE: the job being taken down. For UPGRADE: the building. */
    @Nullable
    public UUID targetId;
    public final List<BlockPos> gates = new ArrayList<>();
    /** Temporary ladder rungs this job hung (BuilderScaffold); taken down before it completes. */
    public final List<BlockPos> scaffold = new ArrayList<>();

    private final List<BlockState> palette;
    private final long[] positions;
    private final int[] states;
    private final byte[] phases;
    private final byte[] flags;
    /** Companion half (door upper, bed head): packed pos, or Long.MIN_VALUE. */
    private final long[] companionPos;
    private final int[] companionState;

    final BitSet done;
    final BitSet placed;
    final BitSet skipped;
    /** Player-block steps waiting for "allow overwrite" or the player. */
    final BitSet blocked;

    public State state = State.ACTIVE;
    public boolean paused;
    public boolean rush;
    public boolean allowOverwrite;
    /** Queue position; lower works first. */
    public int order;
    /** One second pass over skipped steps is allowed before completion. */
    public boolean secondPass;
    /** Game time this job was first seen with every step finished (watchdog, not saved). */
    public transient long exhaustedSince;
    public BuildStatus status = BuildStatus.QUEUED;
    public List<String> statusArgs = List.of();

    // Runtime only: the lease a Builder holds, and per-step retry pacing.
    @Nullable
    public transient UUID claimant;
    public transient long leaseUntil;
    final transient Map<Integer, Integer> attempts = new HashMap<>();
    final transient Map<Integer, Long> retryAt = new HashMap<>();
    private transient int cursor;

    BuildJob(UUID id, UUID settlementId, Kind kind, String sourceId, String label,
             BlockPos anchor, int rotation, boolean mirror, BoundingBox bounds,
             @Nullable UUID owner, long createdAt, List<BlockState> palette,
             long[] positions, int[] states, byte[] phases, byte[] flags,
             long[] companionPos, int[] companionState) {
        int n = positions.length;
        if (n > MAX_STEPS || states.length != n || phases.length != n || flags.length != n
            || companionPos.length != n || companionState.length != n) {
            throw new IllegalArgumentException("malformed build job step arrays");
        }
        this.id = id;
        this.settlementId = settlementId;
        this.kind = kind;
        this.sourceId = sourceId;
        this.label = label;
        this.anchor = anchor.immutable();
        this.rotation = rotation;
        this.mirror = mirror;
        this.bounds = bounds;
        this.owner = owner;
        this.createdAt = createdAt;
        this.palette = List.copyOf(palette);
        this.positions = positions;
        this.states = states;
        this.phases = phases;
        this.flags = flags;
        this.companionPos = companionPos;
        this.companionState = companionState;
        this.done = new BitSet(n);
        this.placed = new BitSet(n);
        this.skipped = new BitSet(n);
        this.blocked = new BitSet(n);
    }

    // ------------------------------------------------------------ steps ---

    public int size() {
        return positions.length;
    }

    public BlockPos pos(int i) {
        return BlockPos.of(positions[i]);
    }

    public BlockState state(int i) {
        return palette.get(states[i]);
    }

    public BuildPhase phase(int i) {
        return BuildPhase.byOrdinal(phases[i]);
    }

    public byte flags(int i) {
        return flags[i];
    }

    public boolean hasFlag(int i, byte flag) {
        return (flags[i] & flag) != 0;
    }

    @Nullable
    public BlockPos companionPos(int i) {
        return companionPos[i] == Long.MIN_VALUE ? null : BlockPos.of(companionPos[i]);
    }

    @Nullable
    public BlockState companionState(int i) {
        return companionState[i] < 0 ? null : palette.get(companionState[i]);
    }

    public boolean isDone(int i) {
        return done.get(i);
    }

    public boolean isPlaced(int i) {
        return placed.get(i);
    }

    public boolean isSkipped(int i) {
        return skipped.get(i);
    }

    public boolean isBlocked(int i) {
        return blocked.get(i);
    }

    public int doneCount() {
        return done.cardinality();
    }

    public int skippedCount() {
        return skipped.cardinality();
    }

    public int blockedCount() {
        return blocked.cardinality();
    }

    /** Fraction finished, 0..1 (skipped steps count as not finished). */
    public float progress() {
        return size() == 0 ? 1.0F : done.cardinality() / (float) size();
    }

    /** First step that is neither done nor skipped (cached, monotone). */
    public int cursor() {
        int i = done.nextClearBit(cursor);
        cursor = i;
        return i;
    }

    /** Whether every step is finished, skipped for good, or blocked. */
    public boolean exhausted() {
        for (int i = cursor(); i < size(); i++) {
            if (!done.get(i) && !skipped.get(i) && !blocked.get(i)) {
                return false;
            }
        }
        return true;
    }

    /** Records a finished step. Only {@code BuildExecutor} calls this. */
    void markDone(int i, boolean placedWithItem) {
        done.set(i);
        if (placedWithItem) {
            placed.set(i);
        }
        skipped.clear(i);
        blocked.clear(i);
        attempts.remove(i);
        retryAt.remove(i);
    }

    /** A step taken back by a Dismantle: no longer placed by this job. */
    void markRemoved(int i) {
        placed.clear(i);
    }

    void markSkipped(int i) {
        skipped.set(i);
    }

    // Retry pacing, safe to call from the goal: none of these changes the
    // world or the exact-once bits (done/placed).

    /** Failed tries at step i so far (0 when none). */
    public int tries(int i) {
        return attempts.getOrDefault(i, 0);
    }

    /** Records one failed try at step i; returns the tries so far. */
    public int noteFailure(int i) {
        return attempts.merge(i, 1, Integer::sum);
    }

    /** Leaves step i alone until {@code tick}. */
    public void deferStep(int i, long tick) {
        retryAt.put(i, tick);
    }

    /** Whether step i is resting until later. */
    public boolean deferred(int i, long now) {
        Long at = retryAt.get(i);
        return at != null && at > now;
    }

    /** Last failure reason per step (runtime diagnostics for sites and tests). */
    final transient Map<Integer, String> why = new HashMap<>();

    public void noteWhy(int i, String reason) {
        why.put(i, reason);
    }

    /** "x,y,z block reason; ..." for every skipped step (diagnostics). */
    /** Where the skipped blocks are ("x y z" of the first few), for the player's "needs a hand" line. */
    public String skippedWhere(int max) {
        StringBuilder out = new StringBuilder();
        int n = 0;
        for (int i = skipped.nextSetBit(0); i >= 0 && n < max; i = skipped.nextSetBit(i + 1)) {
            BlockPos p = pos(i);
            if (n++ > 0) {
                out.append(", ");
            }
            out.append(p.getX()).append(' ').append(p.getY()).append(' ').append(p.getZ());
        }
        if (skippedCount() > max) {
            out.append(", ...");
        }
        return out.toString();
    }

    public String skipReport() {
        StringBuilder out = new StringBuilder();
        for (int i = skipped.nextSetBit(0); i >= 0; i = skipped.nextSetBit(i + 1)) {
            BlockPos p = pos(i);
            out.append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(' ')
                .append(Blueprint.idOf(state(i))).append(' ').append(phase(i).key()).append(' ')
                .append(why.getOrDefault(i, "?")).append("; ");
        }
        return out.toString();
    }

    /** Gives up on step i for this pass (the stuck guard). */
    public void skipStep(int i) {
        skipped.set(i);
        attempts.remove(i);
        retryAt.remove(i);
    }

    void markBlocked(int i) {
        blocked.set(i);
    }

    void clearBlocked() {
        blocked.clear();
    }

    /** Gives every skipped step one more try (the end-of-job second pass). */
    void retrySkipped() {
        skipped.clear();
        attempts.clear();
        retryAt.clear();
        cursor = 0;
    }

    /** Failed owned ladder removals remain obligations even after a cleanup pass finishes. */
    boolean hasSkippedScaffold() {
        if (kind != Kind.DISMANTLE) return false;
        for (int i = skipped.nextSetBit(0); i >= 0; i = skipped.nextSetBit(i + 1)) {
            if (phase(i) == BuildPhase.DISMANTLE && hasFlag(i, F_SCAFFOLD)) return true;
        }
        return false;
    }

    /** Retry only owned ladder removals; leave ordinary skipped work and finished bits alone. */
    void retrySkippedScaffold() {
        if (kind != Kind.DISMANTLE) return;
        for (int i = skipped.nextSetBit(0); i >= 0; i = skipped.nextSetBit(i + 1)) {
            if (phase(i) == BuildPhase.DISMANTLE && hasFlag(i, F_SCAFFOLD)) {
                skipped.clear(i);
                attempts.remove(i);
                retryAt.remove(i);
            }
        }
        cursor = 0;
    }

    /** Number of distinct y levels among a phase's steps, and the index of y. */
    public int[] layerOf(int step) {
        BuildPhase phase = phase(step);
        java.util.TreeSet<Integer> ys = new java.util.TreeSet<>();
        for (int i = 0; i < size(); i++) {
            if (phase(i) == phase) {
                ys.add(pos(i).getY());
            }
        }
        int y = pos(step).getY();
        return new int[]{ys.headSet(y).size() + 1, ys.size()};
    }

    public int countPhase(BuildPhase phase, boolean onlyDone) {
        int n = 0;
        for (int i = 0; i < size(); i++) {
            if (phase(i) == phase && (!onlyDone || done.get(i))) {
                n++;
            }
        }
        return n;
    }

    public boolean workable() {
        return state == State.ACTIVE && !paused;
    }

    public void setStatus(BuildStatus next, Object... args) {
        List<String> list = new ArrayList<>(args.length);
        for (Object a : args) {
            list.add(String.valueOf(a));
        }
        status = next;
        statusArgs = List.copyOf(list);
    }

    // -------------------------------------------------------- persistence ---

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putUUID("Settlement", settlementId);
        tag.putString("KindName", kind.name());
        tag.putString("Source", sourceId);
        tag.putString("Label", label);
        tag.putLong("Anchor", anchor.asLong());
        tag.putInt("Rotation", rotation);
        tag.putBoolean("Mirror", mirror);
        tag.putIntArray("Bounds", new int[]{bounds.minX(), bounds.minY(), bounds.minZ(),
            bounds.maxX(), bounds.maxY(), bounds.maxZ()});
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }
        tag.putLong("Created", createdAt);
        if (fitPlanType != null) {
            tag.putString("FitPlan", fitPlanType);
        }
        if (segment != null) {
            tag.putString("Segment", segment);
        }
        if (targetId != null) {
            tag.putUUID("Target", targetId);
        }
        long[] gatePacked = new long[gates.size()];
        for (int i = 0; i < gates.size(); i++) {
            gatePacked[i] = gates.get(i).asLong();
        }
        tag.put("Gates", new LongArrayTag(gatePacked));
        long[] rungs = new long[scaffold.size()];
        for (int i = 0; i < rungs.length; i++) {
            rungs[i] = scaffold.get(i).asLong();
        }
        tag.put("Scaffold", new LongArrayTag(rungs));
        ListTag paletteTag = new ListTag();
        for (BlockState s : palette) {
            paletteTag.add(NbtUtils.writeBlockState(s));
        }
        tag.put("Palette", paletteTag);
        tag.put("Pos", new LongArrayTag(positions));
        tag.put("States", new IntArrayTag(states));
        tag.put("Phases", new ByteArrayTag(phases));
        tag.put("Flags", new ByteArrayTag(flags));
        tag.put("CompanionPos", new LongArrayTag(companionPos));
        tag.put("CompanionState", new IntArrayTag(companionState));
        tag.put("Done", new LongArrayTag(done.toLongArray()));
        tag.put("Placed", new LongArrayTag(placed.toLongArray()));
        tag.put("Skipped", new LongArrayTag(skipped.toLongArray()));
        tag.put("Blocked", new LongArrayTag(blocked.toLongArray()));
        tag.putString("StateName", state.name());
        tag.putBoolean("Paused", paused);
        tag.putBoolean("Rush", rush);
        tag.putBoolean("AllowOverwrite", allowOverwrite);
        tag.putInt("Order", order);
        tag.putBoolean("SecondPass", secondPass);
        tag.putString("StatusKey", status.key());
        ListTag args = new ListTag();
        for (String a : statusArgs) {
            args.add(StringTag.valueOf(a));
        }
        tag.put("StatusArgs", args);
        return tag;
    }

    /** Strict load: a malformed job is dropped (logged by the caller), never half-read. */
    public static BuildJob load(CompoundTag tag, HolderGetter<Block> blocks) {
        ListTag paletteTag = tag.getList("Palette", Tag.TAG_COMPOUND);
        List<BlockState> palette = new ArrayList<>(paletteTag.size());
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(blocks, paletteTag.getCompound(i)));
        }
        int[] b = tag.getIntArray("Bounds");
        if (b.length != 6) {
            throw new IllegalArgumentException("bounds");
        }
        int[] states = tag.getIntArray("States");
        int[] companionState = tag.getIntArray("CompanionState");
        for (int s : states) {
            if (s < 0 || s >= palette.size()) {
                throw new IllegalArgumentException("palette index");
            }
        }
        for (int s : companionState) {
            if (s >= palette.size()) {
                throw new IllegalArgumentException("companion palette index");
            }
        }
        BuildJob job = new BuildJob(tag.getUUID("Id"), tag.getUUID("Settlement"),
            byName(Kind.class, tag.getString("KindName"), Kind.byOrdinal(tag.getInt("Kind"))),
            tag.getString("Source"), tag.getString("Label"),
            BlockPos.of(tag.getLong("Anchor")), tag.getInt("Rotation"), tag.getBoolean("Mirror"),
            new BoundingBox(b[0], b[1], b[2], b[3], b[4], b[5]),
            tag.hasUUID("Owner") ? tag.getUUID("Owner") : null, tag.getLong("Created"),
            palette, tag.getLongArray("Pos"), states, tag.getByteArray("Phases"),
            tag.getByteArray("Flags"), tag.getLongArray("CompanionPos"), companionState);
        job.fitPlanType = tag.contains("FitPlan") ? tag.getString("FitPlan") : null;
        job.segment = tag.contains("Segment") ? tag.getString("Segment") : null;
        job.targetId = tag.hasUUID("Target") ? tag.getUUID("Target") : null;
        for (long g : tag.getLongArray("Gates")) {
            job.gates.add(BlockPos.of(g));
        }
        for (long r : tag.getLongArray("Scaffold")) {
            job.scaffold.add(BlockPos.of(r));
        }
        job.done.or(BitSet.valueOf(tag.getLongArray("Done")));
        job.placed.or(BitSet.valueOf(tag.getLongArray("Placed")));
        job.skipped.or(BitSet.valueOf(tag.getLongArray("Skipped")));
        job.blocked.or(BitSet.valueOf(tag.getLongArray("Blocked")));
        // Bits beyond the step list would be corrupt: trim rather than trust.
        int n = job.size();
        job.done.clear(n, Math.max(n, job.done.length()));
        job.placed.clear(n, Math.max(n, job.placed.length()));
        job.skipped.clear(n, Math.max(n, job.skipped.length()));
        job.blocked.clear(n, Math.max(n, job.blocked.length()));
        job.state = byName(State.class, tag.getString("StateName"), State.byOrdinal(tag.getInt("State")));
        job.paused = tag.getBoolean("Paused");
        job.rush = tag.getBoolean("Rush");
        job.allowOverwrite = tag.getBoolean("AllowOverwrite");
        job.order = tag.getInt("Order");
        job.secondPass = tag.getBoolean("SecondPass");
        job.status = tag.contains("StatusKey") ? BuildStatus.byKey(tag.getString("StatusKey"))
            : BuildStatus.byOrdinal(tag.getInt("Status"));
        ListTag args = tag.getList("StatusArgs", Tag.TAG_STRING);
        List<String> list = new ArrayList<>(args.size());
        for (int i = 0; i < args.size(); i++) {
            list.add(args.getString(i));
        }
        job.statusArgs = List.copyOf(list);
        return job;
    }

    /** Saves carry enum names (bug hunter: an inserted value must never shift old saves). */
    static <E extends Enum<E>> E byName(Class<E> type, String name, E fallback) {
        if (name == null || name.isEmpty()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, name);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }

    // ------------------------------------------------------------ builder ---

    /** Accumulates steps in any order, then freezes them in build order. */
    public static final class Builder {
        private final List<BlockState> palette = new ArrayList<>();
        private final Map<BlockState, Integer> index = new HashMap<>();
        private final List<long[]> rows = new ArrayList<>();

        private int stateIndex(BlockState state) {
            Integer existing = index.get(state);
            if (existing != null) {
                return existing;
            }
            int i = palette.size();
            palette.add(state);
            index.put(state, i);
            return i;
        }

        /** Adds one step. Returns false when the job is already at its cap. */
        public boolean add(BlockPos pos, BlockState state, BuildPhase phase, int flags,
                           @Nullable BlockPos companion, @Nullable BlockState companionState) {
            if (rows.size() >= MAX_STEPS) {
                return false;
            }
            rows.add(new long[]{pos.asLong(), stateIndex(state), phase.ordinal(), flags,
                companion == null ? Long.MIN_VALUE : companion.asLong(),
                companionState == null ? -1 : stateIndex(companionState)});
            return true;
        }

        public int size() {
            return rows.size();
        }

        public boolean full() {
            return rows.size() >= MAX_STEPS;
        }

        public BuildJob build(UUID id, UUID settlementId, Kind kind, String sourceId, String label,
                              BlockPos anchor, int rotation, boolean mirror,
                              @Nullable UUID owner, long now) {
            rows.sort((a, b) -> {
                BlockPos pa = BlockPos.of(a[0]);
                BlockPos pb = BlockPos.of(b[0]);
                return BuildOrder.ORDER.compare(
                    new BuildOrder.Key(BuildPhase.byOrdinal((int) a[2]), pa.getX(), pa.getY(), pa.getZ()),
                    new BuildOrder.Key(BuildPhase.byOrdinal((int) b[2]), pb.getX(), pb.getY(), pb.getZ()));
            });
            int n = rows.size();
            long[] pos = new long[n];
            int[] st = new int[n];
            byte[] ph = new byte[n];
            byte[] fl = new byte[n];
            long[] cp = new long[n];
            int[] cs = new int[n];
            int minX = anchor.getX(), minY = anchor.getY(), minZ = anchor.getZ();
            int maxX = minX, maxY = minY, maxZ = minZ;
            for (int i = 0; i < n; i++) {
                long[] r = rows.get(i);
                pos[i] = r[0];
                st[i] = (int) r[1];
                ph[i] = (byte) r[2];
                fl[i] = (byte) r[3];
                cp[i] = r[4];
                cs[i] = (int) r[5];
                BlockPos p = BlockPos.of(r[0]);
                minX = Math.min(minX, p.getX());
                minY = Math.min(minY, p.getY());
                minZ = Math.min(minZ, p.getZ());
                maxX = Math.max(maxX, p.getX());
                maxY = Math.max(maxY, p.getY());
                maxZ = Math.max(maxZ, p.getZ());
            }
            return new BuildJob(id, settlementId, kind, sourceId, label, anchor, rotation, mirror,
                new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ), owner, now,
                palette, pos, st, ph, fl, cp, cs);
        }
    }
}
