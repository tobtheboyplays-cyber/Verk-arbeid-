package com.hearthstead.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

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
public record SettlerActionPayload(int entityId, Kind kind, int revision)
    implements CustomPacketPayload {

    public enum Kind {
        /** Leave whatever building currently employs this settler. */
        DISMISS,
        /** Take the settlement's mayoral seat. */
        APPOINT
    }

    public static final Type<SettlerActionPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "settler_action"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SettlerActionPayload> CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, SettlerActionPayload::entityId,
            ByteBufCodecs.VAR_INT.map(i -> Kind.values()[Math.floorMod(i, Kind.values().length)],
                kind -> kind.ordinal()), SettlerActionPayload::kind,
            ByteBufCodecs.VAR_INT, SettlerActionPayload::revision,
            SettlerActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
