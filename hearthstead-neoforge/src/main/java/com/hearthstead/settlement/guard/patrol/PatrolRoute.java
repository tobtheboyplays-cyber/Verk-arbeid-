package com.hearthstead.settlement.guard.patrol;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.Tag;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * One player-authored patrol route of a settlement: numbered waypoints, an
 * optional closing leg (loop), the squad's formation, and who walks it --
 * individually picked guards and/or "N guards per shift" from the free pool.
 * Shared by every member of the settlement (co-op: equal rights).
 */
public final class PatrolRoute {
    public enum Formation {
        /** Single file: right for a wall walk. */
        COLUMN(0, "Column"),
        /** Two abreast: open ground and roads. */
        PAIRS(1, "Pairs");

        private final int wireId;
        private final String label;

        Formation(int wireId, String label) {
            this.wireId = wireId;
            this.label = label;
        }

        public int wireId() {
            return wireId;
        }

        public String label() {
            return label;
        }

        public static Formation byWireId(int id) {
            return id == PAIRS.wireId ? PAIRS : COLUMN;
        }
    }

    public final int id;
    private String name;
    private boolean named;
    private int color;
    private final List<BlockPos> waypoints = new ArrayList<>();
    private boolean loop;
    private Formation formation = Formation.COLUMN;
    private int perShift;
    private final Set<UUID> members = new LinkedHashSet<>();
    private int revision;

    public PatrolRoute(int id, String name, int color) {
        this.id = id;
        this.name = name == null ? "Patrol" : name;
        this.color = Math.floorMod(color, PatrolPalette.COUNT);
    }

    public String name() {
        return name;
    }

    /** True once a player chose the name; auto names follow the waypoints until then. */
    public boolean named() {
        return named;
    }

    public int color() {
        return color;
    }

    public List<BlockPos> waypoints() {
        return List.copyOf(waypoints);
    }

    public int size() {
        return waypoints.size();
    }

    public boolean loop() {
        return loop && waypoints.size() >= PatrolRules.MIN_LOOP_WAYPOINTS;
    }

    /** The stored flag, even while too short to be a real loop. */
    public boolean loopFlag() {
        return loop;
    }

    public Formation formation() {
        return formation;
    }

    public int perShift() {
        return perShift;
    }

    public Set<UUID> members() {
        return Set.copyOf(members);
    }

    /** Picked members in pick order. */
    public List<UUID> memberOrder() {
        return List.copyOf(members);
    }

    public int revision() {
        return revision;
    }

    public boolean walkable() {
        return waypoints.size() >= PatrolRules.MIN_WAYPOINTS;
    }

    // ------------------------------------------------------------ edits ---

    void rename(String next, boolean byPlayer) {
        name = next;
        named = named || byPlayer;
        revision++;
    }

    void append(BlockPos pos) {
        waypoints.add(pos.immutable());
        revision++;
    }

    void remove(int index) {
        waypoints.remove(index);
        if (waypoints.size() < PatrolRules.MIN_LOOP_WAYPOINTS) loop = false;
        revision++;
    }

    void setLoop(boolean loop) {
        this.loop = loop;
        revision++;
    }

    void setFormation(Formation formation) {
        this.formation = formation == null ? Formation.COLUMN : formation;
        revision++;
    }

    void setPerShift(int n) {
        perShift = Math.max(0, Math.min(PatrolRules.MAX_PER_SHIFT, n));
        revision++;
    }

    boolean addMember(UUID id) {
        boolean added = members.add(id);
        if (added) revision++;
        return added;
    }

    boolean removeMember(UUID id) {
        boolean removed = members.remove(id);
        if (removed) revision++;
        return removed;
    }

    // -------------------------------------------------------------- nbt ---

    CompoundTag writeNbt() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Id", id);
        tag.putString("Name", name);
        tag.putBoolean("Named", named);
        tag.putInt("Color", color);
        ListTag points = new ListTag();
        for (BlockPos p : waypoints) points.add(LongTag.valueOf(p.asLong()));
        tag.put("Waypoints", points);
        tag.putBoolean("Loop", loop);
        tag.putInt("Formation", formation.wireId());
        tag.putInt("PerShift", perShift);
        ListTag picked = new ListTag();
        for (UUID member : members) {
            CompoundTag row = new CompoundTag();
            row.putUUID("Id", member);
            picked.add(row);
        }
        tag.put("Members", picked);
        tag.putInt("Revision", revision);
        return tag;
    }

    /** Null when the row is malformed (the book then drops just this route). */
    @Nullable
    static PatrolRoute readNbt(CompoundTag tag) {
        if (tag == null || !tag.contains("Id", Tag.TAG_INT)) return null;
        int id = tag.getInt("Id");
        if (id <= 0) return null;
        String name = PatrolRules.cleanName(tag.getString("Name"));
        PatrolRoute route = new PatrolRoute(id, name == null ? "Patrol" : name, tag.getInt("Color"));
        route.named = tag.getBoolean("Named") && name != null;
        ListTag points = tag.getList("Waypoints", Tag.TAG_LONG);
        for (int i = 0; i < points.size() && i < PatrolRules.MAX_WAYPOINTS; i++) {
            route.waypoints.add(BlockPos.of(((LongTag) points.get(i)).getAsLong()));
        }
        route.loop = tag.getBoolean("Loop");
        route.formation = Formation.byWireId(tag.getInt("Formation"));
        route.perShift = Math.max(0, Math.min(PatrolRules.MAX_PER_SHIFT, tag.getInt("PerShift")));
        ListTag picked = tag.getList("Members", Tag.TAG_COMPOUND);
        for (int i = 0; i < picked.size() && route.members.size() < 64; i++) {
            CompoundTag row = picked.getCompound(i);
            if (row.hasUUID("Id")) route.members.add(row.getUUID("Id"));
        }
        route.revision = Math.max(0, tag.getInt("Revision"));
        return route;
    }
}
