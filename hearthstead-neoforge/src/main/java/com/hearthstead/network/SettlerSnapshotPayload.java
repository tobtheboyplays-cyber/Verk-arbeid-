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
 *
 * <p>{@code bagItemIds} and {@code bagCounts} are the settler's carried bag
 * (see {@code SettlerEntity#bag}, {@code SettlerEntity#BAG_SIZE} slots),
 * one entry per slot in slot order, empty slots sent as id 0 / count 0 —
 * these are real, physically carried items (chest truth), never a display
 * fiction. Sent as registry ids and counts rather than whole
 * {@link net.minecraft.world.item.ItemStack}s because {@code ItemStack} has
 * no {@code equals}/{@code hashCode} of its own, which would make this
 * record's generated equality (used by the settler-sheet round-trip
 * GameTests) compare bag slots by object identity instead of by content.
 */
public record SettlerSnapshotPayload(int entityId, int revision, boolean canManage,
                                     List<Integer> attributeValues, int knackOrdinal,
                                     List<Integer> traitOrdinals, List<Integer> bagItemIds,
                                     List<Integer> bagCounts, String employerBuildingId,
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
        buf.writeVarInt(snapshot.bagItemIds.size());
        for (int id : snapshot.bagItemIds) {
            buf.writeVarInt(id);
        }
        buf.writeVarInt(snapshot.bagCounts.size());
        for (int count : snapshot.bagCounts) {
            buf.writeVarInt(count);
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
        int bagIdCount = buf.readVarInt();
        List<Integer> bagItemIds = new ArrayList<>(bagIdCount);
        for (int i = 0; i < bagIdCount; i++) {
            bagItemIds.add(buf.readVarInt());
        }
        int bagCountCount = buf.readVarInt();
        List<Integer> bagCounts = new ArrayList<>(bagCountCount);
        for (int i = 0; i < bagCountCount; i++) {
            bagCounts.add(buf.readVarInt());
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
            List.copyOf(bagItemIds), List.copyOf(bagCounts),
            employerBuildingId, guardWatchNight, isMayor, mayorSettling, mourning, boonKey,
            refusal);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
