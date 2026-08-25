package com.hearthstead.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * A request from the hearth screen's Mayor tab. A request, not a command --
 * {@link PlaqueAction}'s discipline exactly: the server decides whether it
 * happens, re-checking the settlement and the candidate from scratch.
 *
 * <p>Unlike {@code PlaqueAction} this carries no block position. The
 * settlement is instead re-resolved from the acting player's own position on
 * every request, which is safe because the hearth screen only stays open
 * ({@code HearthMenu#stillValid}) while the player is near the hearth that
 * opened it -- so "the settlement here" is always "the settlement whose
 * screen is open."
 *
 * <p>{@code revision} is the revision the player was looking at when they
 * clicked. If the seat has changed since -- someone else was appointed, the
 * mayor died and mourning began -- the request is refused and the screen
 * refreshed rather than applied to a settlement that has moved on.
 */
public record HearthMayorAction(Kind kind, UUID target, int revision)
    implements CustomPacketPayload {

    public enum Kind {
        APPOINT,
        REFRESH
    }

    public static final Type<HearthMayorAction> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "hearth_mayor_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, HearthMayorAction> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT.map(i -> Kind.values()[Math.floorMod(i, Kind.values().length)],
                kind -> kind.ordinal()), HearthMayorAction::kind,
            UUIDUtil.STREAM_CODEC, HearthMayorAction::target,
            ByteBufCodecs.VAR_INT, HearthMayorAction::revision,
            HearthMayorAction::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
