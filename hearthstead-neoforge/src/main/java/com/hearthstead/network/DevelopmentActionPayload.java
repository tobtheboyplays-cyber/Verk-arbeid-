package com.hearthstead.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/** A request against one server-authored Hearth Development snapshot. */
public record DevelopmentActionPayload(BlockPos hearthPos, UUID settlementId, UUID mayorId,
                                       View view, Kind kind, int targetWireId,
                                       int revision)
    implements CustomPacketPayload {

    public enum View {
        TECH(0),
        EMBLEM_SHOP(1),
        UNKNOWN(-1);

        private final int wireId;

        View(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        static View fromWireId(int id) {
            return switch (id) {
                case 0 -> TECH;
                case 1 -> EMBLEM_SHOP;
                default -> UNKNOWN;
            };
        }
    }

    public enum Kind {
        UNLOCK_NODE(0),
        BUY_EMBLEM(1),
        REFRESH(2),
        /** Open the appointed Mayor's ordinary settler sheet from the shop. */
        INSPECT_MAYOR(3),
        UNKNOWN(-1);

        private final int wireId;

        Kind(int wireId) {
            this.wireId = wireId;
        }

        int wireId() {
            return wireId;
        }

        static Kind fromWireId(int id) {
            return switch (id) {
                case 0 -> UNLOCK_NODE;
                case 1 -> BUY_EMBLEM;
                case 2 -> REFRESH;
                case 3 -> INSPECT_MAYOR;
                default -> UNKNOWN;
            };
        }
    }

    public static final Type<DevelopmentActionPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "development_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DevelopmentActionPayload> CODEC =
        StreamCodec.of(DevelopmentActionPayload::write, DevelopmentActionPayload::read);

    public DevelopmentActionPayload {
        hearthPos = hearthPos == null ? BlockPos.ZERO : hearthPos.immutable();
        settlementId = settlementId == null ? HearthMayorAction.NO_ID : settlementId;
        mayorId = mayorId == null ? HearthMayorAction.NO_ID : mayorId;
        view = view == null ? View.UNKNOWN : view;
        kind = kind == null ? Kind.UNKNOWN : kind;
    }

    private static void write(RegistryFriendlyByteBuf buf, DevelopmentActionPayload action) {
        BlockPos.STREAM_CODEC.encode(buf, action.hearthPos);
        UUIDUtil.STREAM_CODEC.encode(buf, action.settlementId);
        UUIDUtil.STREAM_CODEC.encode(buf, action.mayorId);
        buf.writeVarInt(action.view.wireId());
        buf.writeVarInt(action.kind.wireId());
        buf.writeVarInt(action.targetWireId);
        buf.writeVarInt(action.revision);
    }

    private static DevelopmentActionPayload read(RegistryFriendlyByteBuf buf) {
        return new DevelopmentActionPayload(BlockPos.STREAM_CODEC.decode(buf),
            UUIDUtil.STREAM_CODEC.decode(buf), UUIDUtil.STREAM_CODEC.decode(buf),
            View.fromWireId(buf.readVarInt()),
            Kind.fromWireId(buf.readVarInt()), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
