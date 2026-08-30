package com.hearthstead.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A request from the research screen. A request, not a command: the server
 * re-resolves the study from {@code pos} and decides whether it happens,
 * exactly {@link PlaqueAction}'s own discipline.
 *
 * <p>{@code pos} is the lectern the screen was opened from — the study's
 * identity, the same way a plaque's own position is its. {@code revision} is
 * the value the player's {@link ResearchSnapshotPayload} carried when they
 * pressed the button; a stale one (someone else started or cancelled the
 * project while the screen was open) is refused and a fresh snapshot sent
 * back instead of applied.
 */
public record ResearchActionPayload(BlockPos pos, Kind kind, int projectOrdinal, int revision)
    implements CustomPacketPayload {

    public enum Kind {
        /** Pay {@code projectOrdinal}'s costs and begin it. */
        START(0),
        /** Give up the active project, refunding half its domain sample. */
        CANCEL(1),
        /** Re-send the snapshot with no staleness check, the same meaning
         *  {@code PlaqueAction.Kind.REFRESH} has. */
        REFRESH(2),
        /** Unknown future/corrupt wire value. Always inert. */
        UNKNOWN(-1);

        private final int wireId;

        Kind(int wireId) {
            this.wireId = wireId;
        }

        public int wireId() {
            return wireId;
        }

        public static Kind fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> START;
                case 1 -> CANCEL;
                case 2 -> REFRESH;
                default -> UNKNOWN;
            };
        }
    }

    public static final Type<ResearchActionPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "research_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ResearchActionPayload> CODEC =
        StreamCodec.of(ResearchActionPayload::write, ResearchActionPayload::read);

    public ResearchActionPayload {
        pos = pos == null ? BlockPos.ZERO : pos.immutable();
        kind = kind == null ? Kind.UNKNOWN : kind;
    }

    private static void write(RegistryFriendlyByteBuf buf,
                              ResearchActionPayload action) {
        BlockPos.STREAM_CODEC.encode(buf, action.pos);
        buf.writeVarInt(action.kind.wireId());
        buf.writeVarInt(action.projectOrdinal);
        buf.writeVarInt(action.revision);
    }

    private static ResearchActionPayload read(RegistryFriendlyByteBuf buf) {
        return new ResearchActionPayload(BlockPos.STREAM_CODEC.decode(buf),
            Kind.fromWireId(buf.readVarInt()), buf.readVarInt(), buf.readVarInt());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
