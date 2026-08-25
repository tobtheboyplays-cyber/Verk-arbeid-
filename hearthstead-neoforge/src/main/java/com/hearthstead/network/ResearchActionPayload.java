package com.hearthstead.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
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
        START,
        /** Give up the active project, refunding half its domain sample. */
        CANCEL,
        /** Re-send the snapshot with no staleness check, the same meaning
         *  {@code PlaqueAction.Kind.REFRESH} has. */
        REFRESH
    }

    public static final Type<ResearchActionPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "research_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ResearchActionPayload> CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, ResearchActionPayload::pos,
            ByteBufCodecs.VAR_INT.map(i -> Kind.values()[Math.floorMod(i, Kind.values().length)],
                kind -> kind.ordinal()), ResearchActionPayload::kind,
            ByteBufCodecs.VAR_INT, ResearchActionPayload::projectOrdinal,
            ByteBufCodecs.VAR_INT, ResearchActionPayload::revision,
            ResearchActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
