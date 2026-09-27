package com.hearthstead.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Client to server: "my Banner screen's live map is (no longer) showing".
 *
 * <p>Same identity discipline as {@link HearthMayorAction}: the banner
 * position, settlement UUID and container id echo the exact server-opened
 * {@code HearthMenu}. They grant nothing by themselves; the server resolves
 * the still-open menu and the live banner block entity again, on receipt and
 * on every broadcast, and drops the subscription the moment either fails.
 *
 * <p>{@code focus} names the one settler whose detail line (level, carried
 * bag) the map card is showing; {@link #NO_FOCUS} clears it. It is only ever
 * resolved inside the subscribed settlement's own roster.
 */
public record RealmMapRequestPayload(BlockPos hearthPos, UUID settlementId, int containerId,
                                     Kind kind, UUID focus) implements CustomPacketPayload {

    public static final UUID NO_FOCUS = new UUID(0L, 0L);

    public enum Kind {
        SUBSCRIBE(0),
        UNSUBSCRIBE(1),
        FOCUS(2),
        /** Unknown future or corrupt wire value; always inert. */
        UNKNOWN(-1);

        private final int wireId;

        Kind(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Kind fromWireId(int id) {
            return switch (id) {
                case 0 -> SUBSCRIBE;
                case 1 -> UNSUBSCRIBE;
                case 2 -> FOCUS;
                default -> UNKNOWN;
            };
        }
    }

    public static final Type<RealmMapRequestPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "realm_map_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, RealmMapRequestPayload> CODEC =
        StreamCodec.of(RealmMapRequestPayload::write, RealmMapRequestPayload::read);

    public RealmMapRequestPayload {
        hearthPos = hearthPos == null ? BlockPos.ZERO : hearthPos.immutable();
        settlementId = settlementId == null ? NO_FOCUS : settlementId;
        kind = kind == null ? Kind.UNKNOWN : kind;
        focus = focus == null ? NO_FOCUS : focus;
    }

    private static void write(RegistryFriendlyByteBuf buf, RealmMapRequestPayload p) {
        BlockPos.STREAM_CODEC.encode(buf, p.hearthPos);
        UUIDUtil.STREAM_CODEC.encode(buf, p.settlementId);
        buf.writeVarInt(p.containerId);
        buf.writeVarInt(p.kind.wireId());
        UUIDUtil.STREAM_CODEC.encode(buf, p.focus);
    }

    private static RealmMapRequestPayload read(RegistryFriendlyByteBuf buf) {
        return new RealmMapRequestPayload(BlockPos.STREAM_CODEC.decode(buf),
            UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarInt(),
            Kind.fromWireId(buf.readVarInt()), UUIDUtil.STREAM_CODEC.decode(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
