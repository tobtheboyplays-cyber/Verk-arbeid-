package com.hearthstead.network;

import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Trait;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.TargetBlessingState;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
public record SettlerSnapshotPayload(int entityId, UUID settlerId, UUID sessionId,
                                     int revision, boolean canManage,
                                     List<Integer> attributeValues, int knackOrdinal,
                                     List<Integer> traitOrdinals, List<Integer> bagItemIds,
                                     List<Integer> bagCounts, String employerBuildingId,
                                     boolean guardWatchNight, boolean isMayor,
                                     boolean mayorSettling, boolean mourning, String boonKey,
                                     int wardenOathBlessingRank,
                                     int hearthwardBlessingRank,
                                     int thornedRoadsBlessingRank,
                                     int requestedItemId,
                                     int requestReasonOrdinal,
                                     Delivery delivery,
                                     Optional<Component> refusal)
    implements CustomPacketPayload {

    private static final int MAX_SNAPSHOT_TEXT = 64;

    /**
     * The Blessing projection has an invariant fixed shape: exactly three
     * scalar target ranks, clamped to the persisted target ledger's I–III
     * range. It never carries a list whose size a client could influence.
     */
    public SettlerSnapshotPayload {
        settlerId = settlerId == null ? new UUID(0L, 0L) : settlerId;
        sessionId = sessionId == null ? new UUID(0L, 0L) : sessionId;
        attributeValues = boundedAttributes(attributeValues);
        knackOrdinal = Attribute.byOrdinal(knackOrdinal)
            .map(Attribute::ordinal).orElse(-1);
        traitOrdinals = boundedTraits(traitOrdinals);
        bagItemIds = boundedBagValues(bagItemIds, false, "bag item ids");
        bagCounts = boundedBagValues(bagCounts, true, "bag counts");
        employerBuildingId = boundedText(employerBuildingId);
        boonKey = boundedText(boonKey);
        wardenOathBlessingRank = boundedBlessingRank(wardenOathBlessingRank);
        hearthwardBlessingRank = boundedBlessingRank(hearthwardBlessingRank);
        thornedRoadsBlessingRank = boundedBlessingRank(thornedRoadsBlessingRank);
        requestedItemId = Math.max(-1, requestedItemId);
        requestReasonOrdinal = requestReasonOrdinal >= 0
            && requestReasonOrdinal < EquipmentRequest.Reason.values().length
                ? requestReasonOrdinal : -1;
        // A malformed/null mode must never gain screen-opening authority.
        delivery = delivery == null ? Delivery.UPDATE : delivery;
        refusal = refusal == null ? Optional.empty() : refusal;
    }

    /** Source-compatible, fail-closed constructor: opening is always explicit. */
    public SettlerSnapshotPayload(int entityId, UUID settlerId, UUID sessionId,
                                  int revision, boolean canManage,
                                  List<Integer> attributeValues, int knackOrdinal,
                                  List<Integer> traitOrdinals, List<Integer> bagItemIds,
                                  List<Integer> bagCounts, String employerBuildingId,
                                  boolean guardWatchNight, boolean isMayor,
                                  boolean mayorSettling, boolean mourning, String boonKey,
                                  int wardenOathBlessingRank,
                                  int hearthwardBlessingRank,
                                  int thornedRoadsBlessingRank,
                                  Optional<Component> refusal) {
        this(entityId, settlerId, sessionId, revision, canManage,
            attributeValues, knackOrdinal,
            traitOrdinals, bagItemIds, bagCounts, employerBuildingId,
            guardWatchNight, isMayor, mayorSettling, mourning, boonKey,
            wardenOathBlessingRank, hearthwardBlessingRank,
            thornedRoadsBlessingRank, -1, -1, Delivery.UPDATE, refusal);
    }

    public enum Delivery {
        /** Authored only by the player's explicit interaction. */
        OPEN,
        /** May refresh an exact existing sheet; can never create one. */
        UPDATE;

        static Delivery read(int wire) {
            return wire == OPEN.ordinal() ? OPEN : UPDATE;
        }
    }

    public static final Type<SettlerSnapshotPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "settler_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SettlerSnapshotPayload> CODEC =
        StreamCodec.of(SettlerSnapshotPayload::write, SettlerSnapshotPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, SettlerSnapshotPayload snapshot) {
        buf.writeVarInt(snapshot.entityId);
        buf.writeUUID(snapshot.settlerId);
        buf.writeUUID(snapshot.sessionId);
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
        buf.writeUtf(snapshot.employerBuildingId, MAX_SNAPSHOT_TEXT);
        buf.writeBoolean(snapshot.guardWatchNight);
        buf.writeBoolean(snapshot.isMayor);
        buf.writeBoolean(snapshot.mayorSettling);
        buf.writeBoolean(snapshot.mourning);
        buf.writeUtf(snapshot.boonKey, MAX_SNAPSHOT_TEXT);
        buf.writeByte(snapshot.wardenOathBlessingRank);
        buf.writeByte(snapshot.hearthwardBlessingRank);
        buf.writeByte(snapshot.thornedRoadsBlessingRank);
        // +1 keeps the absent sentinel compact on the VarInt wire.
        buf.writeVarInt(snapshot.requestedItemId + 1);
        buf.writeByte(snapshot.requestReasonOrdinal + 1);
        buf.writeByte(snapshot.delivery.ordinal());
        ComponentSerialization.OPTIONAL_STREAM_CODEC.encode(buf, snapshot.refusal);
    }

    private static SettlerSnapshotPayload read(RegistryFriendlyByteBuf buf) {
        int entityId = buf.readVarInt();
        UUID settlerId = buf.readUUID();
        UUID sessionId = buf.readUUID();
        int revision = buf.readVarInt();
        boolean canManage = buf.readBoolean();
        int attrCount = requireExactCount(buf.readVarInt(), Attribute.COUNT,
            "settler attributes");
        List<Integer> attributeValues = new ArrayList<>(attrCount);
        for (int i = 0; i < attrCount; i++) {
            attributeValues.add(clampAttribute(buf.readVarInt()));
        }
        int knackOrdinal = buf.readVarInt();
        int traitCount = requireCount(buf.readVarInt(), 0, Trait.ALL.length,
            "settler traits");
        List<Integer> traitOrdinals = new ArrayList<>(traitCount);
        for (int i = 0; i < traitCount; i++) {
            traitOrdinals.add(buf.readVarInt());
        }
        int bagIdCount = requireExactCount(buf.readVarInt(),
            SettlerEntity.BAG_SIZE, "settler bag item ids");
        List<Integer> bagItemIds = new ArrayList<>(bagIdCount);
        for (int i = 0; i < bagIdCount; i++) {
            bagItemIds.add(buf.readVarInt());
        }
        int bagCountCount = requireExactCount(buf.readVarInt(),
            SettlerEntity.BAG_SIZE, "settler bag counts");
        List<Integer> bagCounts = new ArrayList<>(bagCountCount);
        for (int i = 0; i < bagCountCount; i++) {
            bagCounts.add(buf.readVarInt());
        }
        String employerBuildingId = buf.readUtf(MAX_SNAPSHOT_TEXT);
        boolean guardWatchNight = buf.readBoolean();
        boolean isMayor = buf.readBoolean();
        boolean mayorSettling = buf.readBoolean();
        boolean mourning = buf.readBoolean();
        String boonKey = buf.readUtf(MAX_SNAPSHOT_TEXT);
        int wardenOathBlessingRank = buf.readUnsignedByte();
        int hearthwardBlessingRank = buf.readUnsignedByte();
        int thornedRoadsBlessingRank = buf.readUnsignedByte();
        int requestedItemId = buf.readVarInt() - 1;
        int requestReasonOrdinal = buf.readUnsignedByte() - 1;
        Delivery delivery = Delivery.read(buf.readUnsignedByte());
        Optional<Component> refusal = ComponentSerialization.OPTIONAL_STREAM_CODEC.decode(buf);
        return new SettlerSnapshotPayload(entityId, settlerId, sessionId,
            revision, canManage,
            List.copyOf(attributeValues), knackOrdinal, List.copyOf(traitOrdinals),
            List.copyOf(bagItemIds), List.copyOf(bagCounts),
            employerBuildingId, guardWatchNight, isMayor, mayorSettling, mourning, boonKey,
            wardenOathBlessingRank, hearthwardBlessingRank, thornedRoadsBlessingRank,
            requestedItemId, requestReasonOrdinal, delivery, refusal);
    }

    /** Constant-time view used by the inspection screen and packet tests. */
    public int blessingRank(BlessingId blessing) {
        if (blessing == null) {
            return 0;
        }
        return switch (blessing) {
            case WARDEN_OATH -> wardenOathBlessingRank;
            case HEARTHWARD -> hearthwardBlessingRank;
            case THORNED_ROADS -> thornedRoadsBlessingRank;
        };
    }

    private static int boundedBlessingRank(int rank) {
        return Math.max(0, Math.min(TargetBlessingState.MAX_RANK, rank));
    }

    private static List<Integer> boundedAttributes(List<Integer> values) {
        requireListSize(values, Attribute.COUNT, "settler attributes");
        List<Integer> safe = new ArrayList<>(Attribute.COUNT);
        for (Integer value : values) {
            if (value == null) {
                throw new IllegalArgumentException("null settler attribute");
            }
            safe.add(clampAttribute(value));
        }
        return List.copyOf(safe);
    }

    private static List<Integer> boundedTraits(List<Integer> ordinals) {
        if (ordinals == null || ordinals.size() > Trait.ALL.length) {
            throw new IllegalArgumentException("settler traits out of range");
        }
        HashSet<Integer> unique = new HashSet<>(ordinals.size());
        for (Integer ordinal : ordinals) {
            if (ordinal == null || ordinal < 0 || ordinal >= Trait.ALL.length
                || !unique.add(ordinal)) {
                throw new IllegalArgumentException("invalid settler trait ordinal");
            }
        }
        return List.copyOf(ordinals);
    }

    private static List<Integer> boundedBagValues(List<Integer> values,
                                                   boolean counts,
                                                   String field) {
        requireListSize(values, SettlerEntity.BAG_SIZE, field);
        List<Integer> safe = new ArrayList<>(SettlerEntity.BAG_SIZE);
        for (Integer value : values) {
            if (value == null) {
                throw new IllegalArgumentException("null " + field);
            }
            safe.add(counts ? Math.max(0, value) : value);
        }
        return List.copyOf(safe);
    }

    private static void requireListSize(List<?> values, int expected,
                                        String field) {
        if (values == null || values.size() != expected) {
            throw new IllegalArgumentException(field + " must contain exactly "
                + expected + " entries");
        }
    }

    static int requireExactCount(int count, int expected, String field) {
        if (count != expected) {
            throw new IllegalArgumentException(field + " count out of range: "
                + count);
        }
        return count;
    }

    static int requireCount(int count, int minimum, int maximum, String field) {
        if (count < minimum || count > maximum) {
            throw new IllegalArgumentException(field + " count out of range: "
                + count);
        }
        return count;
    }

    private static int clampAttribute(int value) {
        return Math.max(0, Math.min(99, value));
    }

    private static String boundedText(String value) {
        String safe = value == null ? "" : value;
        if (safe.length() > MAX_SNAPSHOT_TEXT || safe.indexOf('\n') >= 0
            || safe.indexOf('\r') >= 0 || safe.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("unbounded settler snapshot text");
        }
        return safe;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
