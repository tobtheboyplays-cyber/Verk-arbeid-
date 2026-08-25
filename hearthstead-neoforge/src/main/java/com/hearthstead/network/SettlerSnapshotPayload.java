package com.hearthstead.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Everything the settler screen draws that is not already on the entity's
 * synced data.
 *
 * <p>Name, profession, activity, hunger, energy and morale are all synced
 * fields on {@code SettlerEntity} already and the screen reads them straight
 * off the entity every frame, the way the old card did. This carries the
 * rest: the five attributes and traits, which are rolled once and kept
 * server-side only (rolling them again on the client would roll a
 * <i>different</i> settler), and the settler's place in the settlement, which
 * only the server can see — who employs them, what shift a guard stands,
 * whether they hold the mayoral seat.
 *
 * <p>{@code revision} is recomputed from the settler's live employer and the
 * settlement's mayor/mourning state every time a {@link SettlerActionPayload}
 * comes back — the same staleness guard {@code PlaqueAction} uses, so a click
 * made against a view the world has already moved past is refused rather than
 * applied.
 *
 * <p>{@code refusal}, when present, is the sentence the last action failed
 * with — {@code Mayor.appoint}'s own refusal, or one composed here — sent as
 * a real {@link Component} so it renders in the player's language and is
 * shown on the screen itself (D-014: never a silent no-op).
 */
public record SettlerSnapshotPayload(int entityId, int revision, boolean canManage,
                                     List<Integer> attributeValues, int knackOrdinal,
                                     List<Integer> traitOrdinals, String employerBuildingId,
                                     boolean guardWatchNight, boolean isMayor,
                                     boolean mayorSettling, boolean mourning, String boonKey,
                                     Optional<Component> refusal)
    implements CustomPacketPayload {

    public static final Type<SettlerSnapshotPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "settler_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SettlerSnapshotPayload> CODEC =
        StreamCodec.of(SettlerSnapshotPayload::write, SettlerSnapshotPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SettlerSnapshotPayload snapshot) {
        buf.writeVarInt(snapshot.entityId);
        buf.writeVarInt(snapshot.revision);
        buf.writeBoolean(snapshot.canManage);
        buf.writeVarInt(snapshot.attributeValues.size());
        for (int value : snapshot.attributeValues) {
            buf.writeVarInt(value);
        }
        buf.writeVarInt(snapshot.knackOrdinal);
        buf.writeVarInt(snapshot.traitOrdinals.size());
        for (int ordinal : snapshot.traitOrdinals) {
            buf.writeVarInt(ordinal);
        }
        buf.writeUtf(snapshot.employerBuildingId);
        buf.writeBoolean(snapshot.guardWatchNight);
        buf.writeBoolean(snapshot.isMayor);
        buf.writeBoolean(snapshot.mayorSettling);
        buf.writeBoolean(snapshot.mourning);
        buf.writeUtf(snapshot.boonKey);
        ComponentSerialization.OPTIONAL_STREAM_CODEC.encode(buf, snapshot.refusal);
    }

    private static SettlerSnapshotPayload read(RegistryFriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        int revision = buf.readVarInt();
        boolean canManage = buf.readBoolean();
        int attrCount = buf.readVarInt();
        List<Integer> attributeValues = new ArrayList<>(attrCount);
        for (int i = 0; i < attrCount; i++) {
            attributeValues.add(buf.readVarInt());
        }
        int knackOrdinal = buf.readVarInt();
        int traitCount = buf.readVarInt();
        List<Integer> traitOrdinals = new ArrayList<>(traitCount);
        for (int i = 0; i < traitCount; i++) {
            traitOrdinals.add(buf.readVarInt());
        }
        String employerBuildingId = buf.readUtf();
        boolean guardWatchNight = buf.readBoolean();
        boolean isMayor = buf.readBoolean();
        boolean mayorSettling = buf.readBoolean();
        boolean mourning = buf.readBoolean();
        String boonKey = buf.readUtf();
        Optional<Component> refusal = ComponentSerialization.OPTIONAL_STREAM_CODEC.decode(buf);
        return new SettlerSnapshotPayload(entityId, revision, canManage,
            List.copyOf(attributeValues), knackOrdinal, List.copyOf(traitOrdinals),
            employerBuildingId, guardWatchNight, isMayor, mayorSettling, mourning, boonKey,
            refusal);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
