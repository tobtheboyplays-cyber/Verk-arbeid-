package com.hearthstead.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * A request from a plaque screen. Requests, not commands: the server decides
 * whether it happens.
 *
 * <p>{@code revision} is the version of the snapshot the player was looking at
 * when they clicked. If the building has changed since — someone else assigned
 * that settler, the room was breached, the plaque was re-surveyed — the
 * request is refused and the screen refreshed rather than applied to a world
 * that has moved on.
 */
public record PlaqueAction(BlockPos pos, UUID buildingId, UUID sessionId,
                           Kind kind, UUID target, int revision,
                           long employmentRevision)
    implements CustomPacketPayload {

    public static final UUID NO_BUILDING = new UUID(0L, 0L);

    public enum Kind {
        ASSIGN(0),
        EVICT(1),
        REFRESH(2),
        /** Call one currently-employed worker to the plaque's front. {@code target} is theirs. */
        SUMMON(3),
        /** Release this exact inspection session; never mutates the building. */
        CLOSE(4),
        /** Release one exact workplace worker and return their existing Job Emblem. */
        FIRE(5),
        /** Unknown future/hostile wire values are inert. */
        UNKNOWN(-1);

        private final int wireId;

        Kind(int wireId) {
            this.wireId = wireId;
        }

        int wireId() {
            return wireId;
        }

        static Kind fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> ASSIGN;
                case 1 -> EVICT;
                case 2 -> REFRESH;
                case 3 -> SUMMON;
                case 4 -> CLOSE;
                case 5 -> FIRE;
                default -> UNKNOWN;
            };
        }
    }

    public static final Type<PlaqueAction> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "plaque_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlaqueAction> CODEC =
        StreamCodec.of(PlaqueAction::write, PlaqueAction::read);

    public PlaqueAction {
        buildingId = buildingId == null ? NO_BUILDING : buildingId;
        sessionId = sessionId == null ? NO_BUILDING : sessionId;
        kind = kind == null ? Kind.UNKNOWN : kind;
        target = target == null ? NO_BUILDING : target;
        employmentRevision = Math.max(0L, employmentRevision);
    }

    /** Existing non-Staff actions carry no employment relation token. */
    public PlaqueAction(BlockPos pos, UUID buildingId, UUID sessionId,
                        Kind kind, UUID target, int revision) {
        this(pos, buildingId, sessionId, kind, target, revision, 0L);
    }

    private static void write(RegistryFriendlyByteBuf buf, PlaqueAction action) {
        buf.writeBlockPos(action.pos);
        buf.writeUUID(action.buildingId);
        buf.writeUUID(action.sessionId);
        buf.writeVarInt(action.kind.wireId());
        buf.writeUUID(action.target);
        buf.writeVarInt(action.revision);
        buf.writeVarLong(action.employmentRevision);
    }

    private static PlaqueAction read(RegistryFriendlyByteBuf buf) {
        return new PlaqueAction(buf.readBlockPos(), buf.readUUID(), buf.readUUID(),
            Kind.fromWireId(buf.readVarInt()), buf.readUUID(), buf.readVarInt(),
            buf.readVarLong());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
