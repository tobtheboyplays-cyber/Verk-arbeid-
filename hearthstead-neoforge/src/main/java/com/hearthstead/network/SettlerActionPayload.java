package com.hearthstead.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * A request from the settler screen. A request, not a command: the server
 * decides whether it happens (see {@link SettlerNetwork}).
 *
 * <p>{@code revision} is the value the player's {@link SettlerSnapshotPayload}
 * carried when they pressed the button. If the settler's employer or the
 * settlement's mayor has changed since — someone else dismissed them,
 * appointed a different mayor, the settlement started mourning — the request
 * is refused and a fresh snapshot sent back, the same guard
 * {@code PlaqueAction} uses against a click made on a view the world has
 * already moved past.
 */
public record SettlerActionPayload(int entityId, UUID settlerId, UUID sessionId,
                                   Kind kind, int revision)
    implements CustomPacketPayload {

    public static final UUID NO_SETTLER = new UUID(0L, 0L);

    public enum Kind {
        /** Leave whatever building currently employs this settler. */
        DISMISS(0),
        /** Take the settlement's mayoral seat. */
        APPOINT(1),
        /** Release this exact inspection session; never mutates the settler. */
        CLOSE(2),
        /** Open the entity-backed physical bag container. */
        OPEN_INVENTORY(3),
        /** Begin the Work Scepter's authoritative three-click zone flow. */
        EDIT_WORK_ZONE(4),
        /** Inspect the exact persisted plaque of this settler's employer. */
        OPEN_WORKPLACE(5),
        /** Report this exact settler's current server-authored coordinates. */
        LOCATE(6),
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
                case 0 -> DISMISS;
                case 1 -> APPOINT;
                case 2 -> CLOSE;
                case 3 -> OPEN_INVENTORY;
                case 4 -> EDIT_WORK_ZONE;
                case 5 -> OPEN_WORKPLACE;
                case 6 -> LOCATE;
                default -> UNKNOWN;
            };
        }
    }

    public static final Type<SettlerActionPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "settler_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SettlerActionPayload> CODEC =
        StreamCodec.of(SettlerActionPayload::write, SettlerActionPayload::read);

    public SettlerActionPayload {
        settlerId = settlerId == null ? NO_SETTLER : settlerId;
        sessionId = sessionId == null ? NO_SETTLER : sessionId;
        kind = kind == null ? Kind.UNKNOWN : kind;
    }

    private static void write(RegistryFriendlyByteBuf buf,
                              SettlerActionPayload payload) {
        buf.writeVarInt(payload.entityId);
        buf.writeUUID(payload.settlerId);
        buf.writeUUID(payload.sessionId);
        buf.writeVarInt(payload.kind == null ? Kind.UNKNOWN.wireId()
            : payload.kind.wireId());
        buf.writeVarInt(payload.revision);
    }

    private static SettlerActionPayload read(RegistryFriendlyByteBuf buf) {
        return new SettlerActionPayload(buf.readVarInt(), buf.readUUID(),
            buf.readUUID(), Kind.fromWireId(buf.readVarInt()), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
