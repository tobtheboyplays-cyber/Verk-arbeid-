package com.hearthstead.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
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
public record PlaqueAction(BlockPos pos, Kind kind, UUID target, int revision)
    implements CustomPacketPayload {

    public enum Kind {
        ASSIGN,
        EVICT,
        REFRESH,
        /** Call one currently-employed worker to the plaque's front. {@code target} is theirs. */
        SUMMON
    }

    public static final Type<PlaqueAction> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "plaque_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, PlaqueAction> CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, PlaqueAction::pos,
            ByteBufCodecs.VAR_INT.map(i -> Kind.values()[Math.floorMod(i, Kind.values().length)],
                kind -> kind.ordinal()), PlaqueAction::kind,
            UUIDUtil.STREAM_CODEC, PlaqueAction::target,
            ByteBufCodecs.VAR_INT, PlaqueAction::revision,
            PlaqueAction::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
