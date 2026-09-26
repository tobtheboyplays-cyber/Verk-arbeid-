package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server projection of one settlement's patrol routes (PATROL ROUTES lane):
 * the routes, who walks each right now (leader first), and the guards a
 * member may pick. Sent to a player holding the Patrol Map, to a player with
 * that settlement's Banner map open, and as the reply to a route action.
 * Display only: the client never decides anything from it.
 */
public record PatrolSnapshotPayload(UUID settlementId, String dimension, int revision, int selectedRoute,
                                    boolean open, List<Route> routes, List<Guard> guards)
    implements CustomPacketPayload {
    public static final int MAX_ROUTES = 8;
    public static final int MAX_POINTS = 16;
    public static final int MAX_GUARDS = 64;
    public static final int MAX_SQUAD = 8;
    public static final int NO_ROUTE = -1;

    public record Route(int id, String name, int color, boolean loop, int formation, int perShift,
                        List<BlockPos> waypoints, List<UUID> picked, List<UUID> squad, int target,
                        boolean valid) {
        public Route {
            name = name == null ? "" : name;
            waypoints = List.copyOf(waypoints == null ? List.of() : waypoints);
            picked = List.copyOf(picked == null ? List.of() : picked);
            squad = List.copyOf(squad == null ? List.of() : squad);
        }
    }

    /** A guard a member may pick: name, trade, watch, whether on watch now, and why busy. */
    public record Guard(UUID id, String name, int professionId, boolean nightWatch, boolean onWatch,
                        boolean busy) {
        public Guard {
            name = name == null ? "" : name;
        }
    }

    public static final Type<PatrolSnapshotPayload> TYPE = new Type<>(Hearthstead.id("patrol_snapshot"));
    public static final StreamCodec<RegistryFriendlyByteBuf, PatrolSnapshotPayload> CODEC =
        StreamCodec.of(PatrolSnapshotPayload::write, PatrolSnapshotPayload::read);

    public PatrolSnapshotPayload {
        dimension = dimension == null ? "" : dimension;
        routes = List.copyOf(routes == null ? List.of() : routes);
        guards = List.copyOf(guards == null ? List.of() : guards);
    }

    private static void write(RegistryFriendlyByteBuf buf, PatrolSnapshotPayload p) {
        UUIDUtil.STREAM_CODEC.encode(buf, p.settlementId);
        buf.writeUtf(p.dimension, 128);
        buf.writeVarInt(p.revision);
        buf.writeVarInt(p.selectedRoute + 1);
        buf.writeBoolean(p.open);
        int routes = Math.min(MAX_ROUTES, p.routes.size());
        buf.writeVarInt(routes);
        for (int i = 0; i < routes; i++) {
            Route r = p.routes.get(i);
            buf.writeVarInt(r.id());
            buf.writeUtf(r.name(), 64);
            buf.writeVarInt(r.color());
            buf.writeBoolean(r.loop());
            buf.writeVarInt(r.formation());
            buf.writeVarInt(r.perShift());
            int points = Math.min(MAX_POINTS, r.waypoints().size());
            buf.writeVarInt(points);
            for (int k = 0; k < points; k++) BlockPos.STREAM_CODEC.encode(buf, r.waypoints().get(k));
            writeIds(buf, r.picked(), MAX_GUARDS);
            writeIds(buf, r.squad(), MAX_SQUAD);
            buf.writeVarInt(r.target() + 1);
            buf.writeBoolean(r.valid());
        }
        int guards = Math.min(MAX_GUARDS, p.guards.size());
        buf.writeVarInt(guards);
        for (int i = 0; i < guards; i++) {
            Guard g = p.guards.get(i);
            UUIDUtil.STREAM_CODEC.encode(buf, g.id());
            buf.writeUtf(g.name(), 64);
            buf.writeVarInt(g.professionId());
            buf.writeBoolean(g.nightWatch());
            buf.writeBoolean(g.onWatch());
            buf.writeBoolean(g.busy());
        }
    }

    private static PatrolSnapshotPayload read(RegistryFriendlyByteBuf buf) {
        UUID settlement = UUIDUtil.STREAM_CODEC.decode(buf);
        String dimension = buf.readUtf(128);
        int revision = buf.readVarInt();
        int selected = buf.readVarInt() - 1;
        boolean open = buf.readBoolean();
        int routes = bounded(buf.readVarInt(), MAX_ROUTES);
        List<Route> routeList = new ArrayList<>(routes);
        for (int i = 0; i < routes; i++) {
            int id = buf.readVarInt();
            String name = buf.readUtf(64);
            int color = buf.readVarInt();
            boolean loop = buf.readBoolean();
            int formation = buf.readVarInt();
            int perShift = buf.readVarInt();
            int points = bounded(buf.readVarInt(), MAX_POINTS);
            List<BlockPos> waypoints = new ArrayList<>(points);
            for (int k = 0; k < points; k++) waypoints.add(BlockPos.STREAM_CODEC.decode(buf));
            List<UUID> picked = readIds(buf, MAX_GUARDS);
            List<UUID> squad = readIds(buf, MAX_SQUAD);
            int target = buf.readVarInt() - 1;
            boolean valid = buf.readBoolean();
            routeList.add(new Route(id, name, color, loop, formation, perShift, waypoints, picked, squad, target,
                valid));
        }
        int guards = bounded(buf.readVarInt(), MAX_GUARDS);
        List<Guard> guardList = new ArrayList<>(guards);
        for (int i = 0; i < guards; i++) {
            guardList.add(new Guard(UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(64), buf.readVarInt(),
                buf.readBoolean(), buf.readBoolean(), buf.readBoolean()));
        }
        return new PatrolSnapshotPayload(settlement, dimension, revision, selected, open, routeList, guardList);
    }

    private static void writeIds(RegistryFriendlyByteBuf buf, List<UUID> ids, int max) {
        int n = Math.min(max, ids.size());
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) UUIDUtil.STREAM_CODEC.encode(buf, ids.get(i));
    }

    private static List<UUID> readIds(RegistryFriendlyByteBuf buf, int max) {
        int n = bounded(buf.readVarInt(), max);
        List<UUID> ids = new ArrayList<>(n);
        for (int i = 0; i < n; i++) ids.add(UUIDUtil.STREAM_CODEC.decode(buf));
        return ids;
    }

    private static int bounded(int n, int max) {
        if (n < 0 || n > max) throw new IllegalArgumentException("patrol snapshot count out of range: " + n);
        return n;
    }

    /** The route with this id, or null. */
    public Route route(int id) {
        for (Route r : routes) {
            if (r.id() == id) return r;
        }
        return null;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
