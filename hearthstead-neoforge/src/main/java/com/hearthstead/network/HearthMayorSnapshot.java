package com.hearthstead.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import com.hearthstead.settlement.request.RequestBlocker;
import com.hearthstead.settlement.request.RequestPriority;
import com.hearthstead.settlement.request.RequestState;
import com.hearthstead.settlement.request.RequestType;
import com.hearthstead.settlement.raid.FirstRaidReadinessService;
import com.hearthstead.settlement.raid.RaidLogEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everything the hearth screen's Mayor tab draws, resolved on the server and
 * sent as one message -- {@link PlaqueSnapshot}'s discipline exactly: the
 * client never decides who is eligible, what a candidate would bring, or how
 * long the seat has been settling or mourning. It renders what it is told.
 *
 * <p>{@code revision} rides back with {@link HearthMayorAction} so a click
 * made against a seat that has since changed hands -- someone else was
 * appointed, the mayor died and mourning began -- is refused and the screen
 * refreshed rather than applied to a settlement that has moved on.
 *
 * <p>{@code mayorSince} and {@code mourningUntil} travel as the absolute
 * game ticks {@code Settlement} itself stores them as, not as a "ticks
 * remaining" figure frozen at send time -- a duration goes stale the instant
 * it is drawn. The client already has a synced clock
 * ({@code Minecraft.getInstance().level.getGameTime()}), so it subtracts
 * locally every frame and the countdown is never wrong without the server
 * needing to repush it every second.
 */
public record HearthMayorSnapshot(int revision, boolean hasMayor, UUID mayorId,
                                  String mayorName, String boonKey, long mayorSince,
                                  boolean mourning, long mourningUntil,
                                  List<Candidate> candidates, List<Resident> residents, int residentTotal, boolean mayManage,
                                  RecruitmentCard recruitment,
                                  RequestView requests,
                                  ReadinessView readiness,
                                  RecurringStatusView recurringStatus,
                                  AftermathView aftermath)
    implements CustomPacketPayload {

    private static final int MAX_MAYOR_CANDIDATES = 64;
    /** Bounded persisted settlement roster; a client cannot request an unlimited cast. */
    public static final int MAX_RESIDENTS = 100;

    public HearthMayorSnapshot {
        mayorId = mayorId == null ? HearthMayorAction.NO_ID : mayorId;
        mayorName = mayorName == null ? "" : mayorName;
        boonKey = boonKey == null ? "" : boonKey;
        candidates = candidates == null ? List.of()
            : List.copyOf(candidates.subList(0,
                Math.min(MAX_MAYOR_CANDIDATES, candidates.size())));
        residents = residents == null ? List.of()
            : List.copyOf(residents.subList(0, Math.min(MAX_RESIDENTS, residents.size())));
        residentTotal = Math.max(residents.size(), residentTotal);
        recruitment = recruitment == null ? RecruitmentCard.empty() : recruitment;
        requests = requests == null ? RequestView.closed() : requests;
        readiness = readiness == null ? ReadinessView.closed() : readiness;
        recurringStatus = recurringStatus == null
            ? RecurringStatusView.closed() : recurringStatus;
        aftermath = aftermath == null ? AftermathView.closed() : aftermath;
    }

    public static final Type<HearthMayorSnapshot> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "hearth_mayor_snapshot"));

    /**
     * Someone who could take the seat, and the boon they would eventually
     * bring -- {@code Mayor.boonOf} is one boon per key attribute, so this is
     * never ambiguous. {@code knack} is that attribute's own player-facing
     * 0-100 score. The client names the exact attribute and prints the exact
     * number; it must not collapse the choice into pips or a hidden score.
     */
    public record Candidate(UUID id, String name, String professionId, String boonKey,
                            int knack) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Candidate> CODEC =
            StreamCodec.of((buf, c) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, c.id());
                buf.writeUtf(c.name());
                buf.writeUtf(c.professionId());
                buf.writeUtf(c.boonKey());
                buf.writeVarInt(c.knack());
            }, buf -> new Candidate(
                UUIDUtil.STREAM_CODEC.decode(buf),
                buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt()));
    }

    /**
     * One persisted settlement member. Name and profession come from the
     * settlement record; activity and runtime entity id are emitted only for a loaded live entity.
     * An unloaded record uses runtime entity id -1 and never causes a chunk/entity lookup beyond getEntity.
     */
    public record Resident(UUID id, int runtimeEntityId, String name, String professionId,
                           String statusKey, boolean loaded) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Resident> CODEC =
            StreamCodec.of((buf, resident) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, resident.id());
                buf.writeVarInt(resident.runtimeEntityId());
                buf.writeUtf(resident.name());
                buf.writeUtf(resident.professionId());
                buf.writeUtf(resident.statusKey());
                buf.writeBoolean(resident.loaded());
            }, buf -> new Resident(UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarInt(),
                buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readBoolean()));
    }

    /** One bounded, entirely server-authored natural-recruit candidate card. */
    public record RecruitmentCard(boolean present, UUID travelerId, String name,
                                  int revision, int statusWireId,
                                  int blockerWireId, int freeBeds,
                                  int readyFood, int requiredFood,
                                  long patienceUntil,
                                  List<CostLine> costLines,
                                  boolean mayAdmit,
                                  boolean mayDismiss,
                                  int quoteVersion, int firstAttribute, int firstValue,
                                  int secondAttribute, int secondValue, int aptitudePremium, int quoteDiscountPercent) {
        public static final int MAX_COST_LINES = 4;

        public RecruitmentCard {
            if (quoteVersion < -1 || quoteVersion > 2 || aptitudePremium < 0 || aptitudePremium > 2
                    || quoteDiscountPercent < 0 || quoteDiscountPercent > 50
                    || quoteVersion >= 1 && (firstAttribute < 0 || firstAttribute >= 8
                        || secondAttribute < 0 || secondAttribute >= 8 || firstAttribute == secondAttribute
                        || firstValue < 1 || firstValue > 15 || secondValue < 1 || secondValue > firstValue
                        || aptitudePremium != com.hearthstead.settlement.RecruitmentQuote.premiumFor(firstValue + secondValue))
                    || quoteVersion == -1 && (firstAttribute != -1 || secondAttribute != -1
                        || firstValue != 0 || secondValue != 0 || aptitudePremium != 0)
                    || quoteVersion == 0 && (aptitudePremium != 0
                        || firstAttribute == -1 && (secondAttribute != -1 || firstValue != 0 || secondValue != 0)
                        || firstAttribute != -1 && (firstAttribute < 0 || firstAttribute >= 8
                            || secondAttribute < 0 || secondAttribute >= 8 || firstAttribute == secondAttribute
                            || firstValue < 1 || firstValue > 99 || secondValue < 1 || secondValue > firstValue))) {
                throw new IllegalArgumentException("invalid recruitment aptitude projection");
            }
            travelerId = travelerId == null ? HearthMayorAction.NO_ID : travelerId;
            name = name == null ? "" : name;
            costLines = costLines == null ? List.of()
                : List.copyOf(costLines.subList(0,
                    Math.min(MAX_COST_LINES, costLines.size())));
        }

        public static RecruitmentCard empty() {
            return new RecruitmentCard(false, HearthMayorAction.NO_ID, "", -1,
                -1, -1, 0, 0, 0, 0L, List.of(), false, false, -1, -1, 0, -1, 0, 0, 0);
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, RecruitmentCard> CODEC =
            StreamCodec.of((buf, card) -> {
                buf.writeVarInt(1); // Recruitment card wire version.
                buf.writeBoolean(card.present());
                UUIDUtil.STREAM_CODEC.encode(buf, card.travelerId());
                buf.writeUtf(card.name(), 64);
                buf.writeVarInt(card.revision());
                buf.writeVarInt(card.statusWireId());
                buf.writeVarInt(card.blockerWireId());
                buf.writeVarInt(card.freeBeds());
                buf.writeVarInt(card.readyFood());
                buf.writeVarInt(card.requiredFood());
                buf.writeLong(card.patienceUntil());
                buf.writeVarInt(card.costLines().size());
                for (CostLine line : card.costLines()) {
                    CostLine.CODEC.encode(buf, line);
                }
                buf.writeBoolean(card.mayAdmit());
                buf.writeBoolean(card.mayDismiss());
                buf.writeVarInt(card.quoteVersion()); buf.writeVarInt(card.firstAttribute());
                buf.writeVarInt(card.firstValue()); buf.writeVarInt(card.secondAttribute());
                buf.writeVarInt(card.secondValue()); buf.writeVarInt(card.aptitudePremium());
                buf.writeVarInt(card.quoteDiscountPercent());
            }, buf -> {
                if (buf.readVarInt() != 1) throw new IllegalArgumentException("unsupported recruitment card version");
                boolean present = buf.readBoolean();
                UUID travelerId = UUIDUtil.STREAM_CODEC.decode(buf);
                String name = buf.readUtf(64);
                int revision = buf.readVarInt();
                int status = buf.readVarInt();
                int blocker = buf.readVarInt();
                int freeBeds = buf.readVarInt();
                int readyFood = buf.readVarInt();
                int requiredFood = buf.readVarInt();
                long patienceUntil = buf.readLong();
                int count = buf.readVarInt();
                if (count < 0 || count > MAX_COST_LINES) {
                    throw new IllegalArgumentException("unbounded recruitment cost lines");
                }
                List<CostLine> lines = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    lines.add(CostLine.CODEC.decode(buf));
                }
                return new RecruitmentCard(present, travelerId, name, revision,
                    status, blocker, freeBeds, readyFood, requiredFood,
                    patienceUntil, List.copyOf(lines), buf.readBoolean(),
                    buf.readBoolean(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                    buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
            });
    }

    /** Translation key and exact discounted amount chosen by the server. */
    public record CostLine(String translationKey, int count) {
        public CostLine {
            translationKey = translationKey == null ? "" : translationKey;
            count = Math.max(0, count);
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, CostLine> CODEC =
            StreamCodec.of((buf, line) -> {
                buf.writeUtf(line.translationKey(), 128);
                buf.writeVarInt(line.count());
            }, buf -> new CostLine(buf.readUtf(128), buf.readVarInt()));
    }

    /**
     * One exact, read-only Request Ledger projection. {@code open} is false
     * on ordinary Mayor refreshes, which lets the same packet remain the only
     * Hearth snapshot type without pretending an unopened ledger was viewed.
     */
    public record RequestView(boolean open, UUID settlementId, int containerId,
                              long generatedTick,
                              long typedRevision, long equipmentRevision,
                              boolean quarantined, String quarantineReason,
                              boolean truncated, List<RequestRow> rows) {
        public static final int WIRE_VERSION = 3;
        public static final int MAX_ROWS = 64;

        public RequestView {
            settlementId = settlementId == null
                ? HearthMayorAction.NO_ID : settlementId;
            generatedTick = Math.max(0L, generatedTick);
            typedRevision = Math.max(0L, typedRevision);
            equipmentRevision = Math.max(0L, equipmentRevision);
            quarantineReason = bounded(quarantineReason, 96, "none");
            rows = rows == null ? List.of() : List.copyOf(rows);
            if (rows.size() > MAX_ROWS) {
                throw new IllegalArgumentException("unbounded Hearth request rows");
            }
            if (open && (HearthMayorAction.NO_ID.equals(settlementId)
                    || containerId < 0)) {
                throw new IllegalArgumentException("open request view has no Hearth session");
            }
            if (!open && (!HearthMayorAction.NO_ID.equals(settlementId)
                    || containerId != -1 || generatedTick != 0L || typedRevision != 0L
                    || equipmentRevision != 0L || quarantined || truncated
                    || !rows.isEmpty())) {
                throw new IllegalArgumentException("closed request view carries data");
            }
        }

        public static RequestView closed() {
            return new RequestView(false, HearthMayorAction.NO_ID, -1,
                0L, 0L, 0L, false, "none", false, List.of());
        }

        /** The response belongs only to the exact live Hearth container. */
        public boolean matches(UUID expectedSettlementId,
                               int expectedContainerId) {
            return open && settlementId.equals(expectedSettlementId)
                && containerId == expectedContainerId;
        }

        /**
         * Fail-closed client cache ordering. A delayed packet may neither
         * reopen a closed projection nor roll either authoritative revision
         * backwards at the same/newer server tick.
         */
        public boolean acceptsAfter(RequestView previous) {
            if (!open) {
                return false;
            }
            if (previous == null || !previous.open) {
                return true;
            }
            return settlementId.equals(previous.settlementId)
                && containerId == previous.containerId
                && generatedTick >= previous.generatedTick
                && typedRevision >= previous.typedRevision
                && equipmentRevision >= previous.equipmentRevision;
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestView> CODEC =
            StreamCodec.of((buf, view) -> {
                buf.writeVarInt(WIRE_VERSION);
                buf.writeBoolean(view.open());
                UUIDUtil.STREAM_CODEC.encode(buf, view.settlementId());
                buf.writeVarInt(view.containerId());
                buf.writeVarLong(view.generatedTick());
                buf.writeVarLong(view.typedRevision());
                buf.writeVarLong(view.equipmentRevision());
                buf.writeBoolean(view.quarantined());
                buf.writeUtf(view.quarantineReason(), 96);
                buf.writeBoolean(view.truncated());
                buf.writeVarInt(view.rows().size());
                for (RequestRow row : view.rows()) {
                    RequestRow.CODEC.encode(buf, row);
                }
            }, buf -> {
                int version = buf.readVarInt();
                if (version != WIRE_VERSION) {
                    throw new IllegalArgumentException(
                        "unsupported Hearth request view version " + version);
                }
                boolean open = buf.readBoolean();
                UUID settlementId = UUIDUtil.STREAM_CODEC.decode(buf);
                int containerId = buf.readVarInt();
                long generatedTick = buf.readVarLong();
                long typedRevision = buf.readVarLong();
                long equipmentRevision = buf.readVarLong();
                boolean quarantined = buf.readBoolean();
                String quarantineReason = buf.readUtf(96);
                boolean truncated = buf.readBoolean();
                int count = buf.readVarInt();
                if (count < 0 || count > MAX_ROWS) {
                    throw new IllegalArgumentException(
                        "unbounded Hearth request rows");
                }
                List<RequestRow> rows = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    rows.add(RequestRow.CODEC.decode(buf));
                }
                return new RequestView(open, settlementId, containerId,
                    generatedTick, typedRevision, equipmentRevision,
                    quarantined, quarantineReason, truncated,
                    List.copyOf(rows));
            });
    }

    /**
     * A bounded player-facing row. Labels are server-selected display data;
     * stable wire ids still identify every authoritative domain value.
     */
    public record RequestRow(UUID requestId, int typeWireId, int stateWireId,
                             int priorityWireId, String requesterName,
                             String professionId, String sourceNameKey,
                             BlockPos sourcePos, String targetNameKey,
                             BlockPos targetPos, UUID courierId,
                             String courierName, String itemId,
                             int requestedCount, int movedCount,
                             int deliveredCount, long ageTicks,
                             int blockerWireId, int physicalOwnerWireId,
                             boolean stockAvailable, boolean targetExact,
                             boolean fullTransportTrace,
                             boolean equipmentAdapter, int equipmentReasonWireId,
                             boolean awaitingSource) {
        private static final int MAX_NAME = 64;
        private static final int MAX_KEY = 128;
        private static final int MAX_ITEM = 96;

        public RequestRow {
            requestId = requireUuid(requestId, "request");
            if (RequestType.fromWireId(typeWireId).isEmpty()
                || RequestState.fromWireId(stateWireId).isEmpty()
                || RequestPriority.fromWireId(priorityWireId).isEmpty()
                || RequestBlocker.fromWireId(blockerWireId).isEmpty()
                || physicalOwnerWireId < 0 || physicalOwnerWireId > 3
                || equipmentReasonWireId < -1 || equipmentReasonWireId > 2
                || equipmentReasonWireId >= 0
                    && typeWireId != RequestType.EQUIPMENT.wireId()) {
                throw new IllegalArgumentException("unknown request row wire id");
            }
            requesterName = bounded(requesterName, MAX_NAME, "Unknown");
            professionId = bounded(professionId, MAX_NAME, "none");
            sourceNameKey = bounded(sourceNameKey, MAX_KEY,
                "hearthstead.request.location.unknown");
            sourcePos = sourcePos == null ? BlockPos.ZERO : sourcePos.immutable();
            targetNameKey = bounded(targetNameKey, MAX_KEY,
                "hearthstead.request.location.unknown");
            targetPos = targetPos == null ? BlockPos.ZERO : targetPos.immutable();
            courierId = courierId == null ? HearthMayorAction.NO_ID : courierId;
            courierName = bounded(courierName, MAX_NAME, "");
            itemId = bounded(itemId, MAX_ITEM, "minecraft:air");
            if (awaitingSource && (equipmentReasonWireId < 0
                    || !equipmentAdapter || stateWireId != RequestState.OPEN.wireId()
                    || !HearthMayorAction.NO_ID.equals(courierId)
                    || movedCount != 0 || deliveredCount != 0 || stockAvailable
                    || targetExact || fullTransportTrace || physicalOwnerWireId != 3
                    || blockerWireId != RequestBlocker.EQUIPMENT_ADAPTER_LIMITED.wireId())) {
                throw new IllegalArgumentException("contradictory unsourced equipment row");
            }
            if (requestedCount <= 0 || requestedCount > 64
                || movedCount < 0 || movedCount > requestedCount
                || deliveredCount < 0 || deliveredCount > movedCount
                || ageTicks < -1L) {
                throw new IllegalArgumentException("request row count bounds");
            }
        }

        public boolean hasCourier() {
            return !HearthMayorAction.NO_ID.equals(courierId);
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, RequestRow> CODEC =
            StreamCodec.of((buf, row) -> {
                UUIDUtil.STREAM_CODEC.encode(buf, row.requestId());
                buf.writeVarInt(row.typeWireId());
                buf.writeVarInt(row.stateWireId());
                buf.writeVarInt(row.priorityWireId());
                buf.writeUtf(row.requesterName(), MAX_NAME);
                buf.writeUtf(row.professionId(), MAX_NAME);
                buf.writeUtf(row.sourceNameKey(), MAX_KEY);
                BlockPos.STREAM_CODEC.encode(buf, row.sourcePos());
                buf.writeUtf(row.targetNameKey(), MAX_KEY);
                BlockPos.STREAM_CODEC.encode(buf, row.targetPos());
                UUIDUtil.STREAM_CODEC.encode(buf, row.courierId());
                buf.writeUtf(row.courierName(), MAX_NAME);
                buf.writeUtf(row.itemId(), MAX_ITEM);
                buf.writeVarInt(row.requestedCount());
                buf.writeVarInt(row.movedCount());
                buf.writeVarInt(row.deliveredCount());
                buf.writeVarLong(row.ageTicks());
                buf.writeVarInt(row.blockerWireId());
                buf.writeVarInt(row.physicalOwnerWireId());
                buf.writeBoolean(row.stockAvailable());
                buf.writeBoolean(row.targetExact());
                buf.writeBoolean(row.fullTransportTrace());
                buf.writeBoolean(row.equipmentAdapter());
                buf.writeVarInt(row.equipmentReasonWireId());
                buf.writeBoolean(row.awaitingSource());
            }, buf -> new RequestRow(
                UUIDUtil.STREAM_CODEC.decode(buf), buf.readVarInt(),
                buf.readVarInt(), buf.readVarInt(), buf.readUtf(MAX_NAME),
                buf.readUtf(MAX_NAME), buf.readUtf(MAX_KEY),
                BlockPos.STREAM_CODEC.decode(buf), buf.readUtf(MAX_KEY),
                BlockPos.STREAM_CODEC.decode(buf),
                UUIDUtil.STREAM_CODEC.decode(buf), buf.readUtf(MAX_NAME),
                buf.readUtf(MAX_ITEM), buf.readVarInt(), buf.readVarInt(),
                buf.readVarInt(), buf.readVarLong(), buf.readVarInt(),
                buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
                buf.readBoolean(), buf.readBoolean(), buf.readVarInt(),
                buf.readBoolean()));
    }

    /**
     * One bounded, server-authored first-raid checklist generation. The
     * client may render blocker wire ids and echo the opaque session id plus
     * action revision; it never supplies readiness facts. A successful
     * declaration remains open as a committed receipt so the player sees a
     * stable result while the Journey advances.
     */
    public record ReadinessView(boolean open, long generation,
                                UUID sessionId, int actionRevision,
                                long domainRevision, boolean ready,
                                boolean committed, int inspectedSettlers,
                                int housingCapacity, int warehouseContainers,
                                int availableReadyMeals,
                                int requiredReadyMeals,
                                int requestActiveRows,
                                int requestBlockedRows,
                                List<Integer> blockerWireIds) {
        public static final int WIRE_VERSION = 1;
        public static final int MAX_BLOCKERS =
            FirstRaidReadinessService.MAX_BLOCKERS;

        public ReadinessView {
            generation = Math.max(0L, generation);
            sessionId = sessionId == null ? HearthMayorAction.NO_ID : sessionId;
            domainRevision = Math.max(0L, domainRevision);
            inspectedSettlers = nonNegative(inspectedSettlers);
            housingCapacity = nonNegative(housingCapacity);
            warehouseContainers = nonNegative(warehouseContainers);
            availableReadyMeals = nonNegative(availableReadyMeals);
            requiredReadyMeals = nonNegative(requiredReadyMeals);
            requestActiveRows = nonNegative(requestActiveRows);
            requestBlockedRows = Math.min(nonNegative(requestBlockedRows),
                requestActiveRows);
            blockerWireIds = blockerWireIds == null ? List.of()
                : List.copyOf(blockerWireIds);
            if (blockerWireIds.size() > MAX_BLOCKERS) {
                throw new IllegalArgumentException(
                    "unbounded first-raid readiness blockers");
            }
            boolean[] seen = new boolean[MAX_BLOCKERS];
            for (int wireId : blockerWireIds) {
                FirstRaidReadinessService.Blocker blocker =
                    FirstRaidReadinessService.Blocker.fromWireId(wireId);
                if (blocker == null || wireId < 0 || wireId >= seen.length
                    || seen[wireId]) {
                    throw new IllegalArgumentException(
                        "invalid first-raid readiness blocker wire id");
                }
                seen[wireId] = true;
            }
            if (!open) {
                if (generation != 0L
                    || !HearthMayorAction.NO_ID.equals(sessionId)
                    || actionRevision != 0 || domainRevision != 0L || ready
                    || committed || inspectedSettlers != 0
                    || housingCapacity != 0 || warehouseContainers != 0
                    || availableReadyMeals != 0 || requiredReadyMeals != 0
                    || requestActiveRows != 0 || requestBlockedRows != 0
                    || !blockerWireIds.isEmpty()) {
                    throw new IllegalArgumentException(
                        "closed first-raid readiness view carries data");
                }
            } else if (generation <= 0L || domainRevision <= 0L
                    || ready != blockerWireIds.isEmpty()
                    || committed && (!ready
                        || !HearthMayorAction.NO_ID.equals(sessionId))
                    || !committed
                        && HearthMayorAction.NO_ID.equals(sessionId)) {
                throw new IllegalArgumentException(
                    "inconsistent first-raid readiness view");
            }
        }

        public static ReadinessView closed() {
            return new ReadinessView(false, 0L, HearthMayorAction.NO_ID, 0,
                0L, false, false, 0, 0, 0, 0, 0, 0, 0, List.of());
        }

        /** Delayed packets cannot roll the visible checklist backwards. */
        public boolean acceptsAfter(ReadinessView previous) {
            if (!open) {
                return false;
            }
            if (previous == null || !previous.open) {
                return true;
            }
            return generation > previous.generation
                && (committed || !previous.committed);
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, ReadinessView> CODEC =
            StreamCodec.of((buf, view) -> {
                buf.writeVarInt(WIRE_VERSION);
                buf.writeBoolean(view.open());
                buf.writeVarLong(view.generation());
                UUIDUtil.STREAM_CODEC.encode(buf, view.sessionId());
                buf.writeVarInt(view.actionRevision());
                buf.writeVarLong(view.domainRevision());
                buf.writeBoolean(view.ready());
                buf.writeBoolean(view.committed());
                buf.writeVarInt(view.inspectedSettlers());
                buf.writeVarInt(view.housingCapacity());
                buf.writeVarInt(view.warehouseContainers());
                buf.writeVarInt(view.availableReadyMeals());
                buf.writeVarInt(view.requiredReadyMeals());
                buf.writeVarInt(view.requestActiveRows());
                buf.writeVarInt(view.requestBlockedRows());
                buf.writeVarInt(view.blockerWireIds().size());
                for (int wireId : view.blockerWireIds()) {
                    buf.writeVarInt(wireId);
                }
            }, buf -> {
                int version = buf.readVarInt();
                if (version != WIRE_VERSION) {
                    throw new IllegalArgumentException(
                        "unsupported first-raid readiness version " + version);
                }
                boolean open = buf.readBoolean();
                long generation = buf.readVarLong();
                UUID sessionId = UUIDUtil.STREAM_CODEC.decode(buf);
                int actionRevision = buf.readVarInt();
                long domainRevision = buf.readVarLong();
                boolean ready = buf.readBoolean();
                boolean committed = buf.readBoolean();
                int inspectedSettlers = buf.readVarInt();
                int housingCapacity = buf.readVarInt();
                int warehouseContainers = buf.readVarInt();
                int availableReadyMeals = buf.readVarInt();
                int requiredReadyMeals = buf.readVarInt();
                int requestActiveRows = buf.readVarInt();
                int requestBlockedRows = buf.readVarInt();
                int count = buf.readVarInt();
                if (count < 0 || count > MAX_BLOCKERS) {
                    throw new IllegalArgumentException(
                        "unbounded first-raid readiness blockers");
                }
                List<Integer> blockers = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    blockers.add(buf.readVarInt());
                }
                return new ReadinessView(open, generation, sessionId,
                    actionRevision, domainRevision, ready, committed,
                    inspectedSettlers, housingCapacity, warehouseContainers,
                    availableReadyMeals, requiredReadyMeals, requestActiveRows,
                    requestBlockedRows, List.copyOf(blockers));
            });

        private static int nonNegative(int value) {
            return Math.max(0, value);
        }
    }

    /**
     * Compact server-authored state for the next recurring raid. The cooldown
     * is already a server-measured remaining-tick value so the client never
     * derives a deadline from its local clock.
     */
    public record RecurringStatusView(int statusWireId, long plannedNight,
                                      long cooldownRemainingTicks) {
        public static final int WIRE_VERSION = 1;

        public RecurringStatusView {
            Status status = Status.fromWireId(statusWireId);
            if (status == null) {
                throw new IllegalArgumentException("unknown recurring raid status");
            }
            boolean planned = status == Status.WARNED
                || status == Status.QUEUED || status == Status.ACTIVE;
            if (planned && (plannedNight < 0L || cooldownRemainingTicks != 0L)
                || status == Status.RECOVERING
                    && (plannedNight != -1L || cooldownRemainingTicks <= 0L)
                || (status == Status.NONE || status == Status.BLOCKED)
                    && (plannedNight != -1L || cooldownRemainingTicks != 0L)) {
                throw new IllegalArgumentException("malformed recurring raid status");
            }
        }

        public static RecurringStatusView closed() {
            return new RecurringStatusView(Status.NONE.wireId(), -1L, 0L);
        }

        public Status status() {
            return Status.fromWireId(statusWireId);
        }

        public static final StreamCodec<RegistryFriendlyByteBuf,
                RecurringStatusView> CODEC = StreamCodec.of((buf, view) -> {
                    buf.writeVarInt(WIRE_VERSION);
                    buf.writeVarInt(view.statusWireId());
                    buf.writeVarLong(view.plannedNight());
                    buf.writeVarLong(view.cooldownRemainingTicks());
                }, buf -> {
                    int version = buf.readVarInt();
                    if (version != WIRE_VERSION) {
                        throw new IllegalArgumentException(
                            "unsupported recurring raid status version " + version);
                    }
                    return new RecurringStatusView(buf.readVarInt(),
                        buf.readVarLong(), buf.readVarLong());
                });

        public enum Status {
            NONE(0, "none"),
            RECOVERING(1, "recovering"),
            WARNED(2, "warned"),
            QUEUED(3, "queued"),
            ACTIVE(4, "active"),
            BLOCKED(5, "blocked");

            private final int wireId;
            private final String id;

            Status(int wireId, String id) {
                this.wireId = wireId;
                this.id = id;
            }

            public int wireId() {
                return wireId;
            }

            public String id() {
                return id;
            }

            public static Status fromWireId(int wireId) {
                for (Status status : values()) {
                    if (status.wireId == wireId) {
                        return status;
                    }
                }
                return null;
            }
        }
    }

    /**
     * One immutable, bounded projection of exactly the newest persisted raid
     * report. The server chooses the report and both status wire ids. The
     * client may translate those known ids, but it never scans history or
     * infers whether a reward exists.
     */
    public record AftermathView(boolean present, boolean held, long night,
                                String captainName, String objectiveId,
                                int itemsStolen, int settlersHurt,
                                String threatStageId,
                                int rewardStatusWireId, int offerSerial,
                                int roadAheadWireId) {
        public static final int WIRE_VERSION = 1;

        public AftermathView {
            captainName = captainName == null ? "" : captainName;
            objectiveId = objectiveId == null ? "" : objectiveId;
            threatStageId = threatStageId == null ? "" : threatStageId;
            if (!present) {
                if (held || night != -1L || !captainName.isEmpty()
                    || !objectiveId.isEmpty() || itemsStolen != 0
                    || settlersHurt != 0 || !threatStageId.isEmpty()
                    || rewardStatusWireId != -1 || offerSerial != 0
                    || roadAheadWireId != -1) {
                    throw new IllegalArgumentException(
                        "closed raid aftermath carries data");
                }
            } else {
                RaidLogEntry entry = new RaidLogEntry(night, captainName,
                    objectiveId, held, itemsStolen, settlersHurt,
                    threatStageId);
                RewardStatus reward = RewardStatus.fromWireId(
                    rewardStatusWireId);
                RoadAhead road = RoadAhead.fromWireId(roadAheadWireId);
                if (!RaidLogEntry.isValid(entry) || reward == null
                    || road == null || offerSerial < 0
                    || offerSerial > 1_000_000
                    || (reward == RewardStatus.OFFER_PENDING)
                        != (offerSerial > 0)
                    || road != expectedRoad(held, reward)) {
                    throw new IllegalArgumentException(
                        "malformed raid aftermath view");
                }
            }
        }

        public static AftermathView closed() {
            return new AftermathView(false, false, -1L, "", "", 0, 0,
                "", -1, 0, -1);
        }

        public RewardStatus rewardStatus() {
            return RewardStatus.fromWireId(rewardStatusWireId);
        }

        public RoadAhead roadAhead() {
            return RoadAhead.fromWireId(roadAheadWireId);
        }

        public static RoadAhead expectedRoad(boolean held,
                                             RewardStatus reward) {
            if (reward == RewardStatus.OFFER_PENDING) {
                return held ? RoadAhead.CLAIM_REWARD
                    : RoadAhead.RECOVER_AND_CLAIM;
            }
            if (!held) {
                return RoadAhead.RECOVER;
            }
            return reward == RewardStatus.UNAVAILABLE
                ? RoadAhead.REWARD_UNAVAILABLE : RoadAhead.FORTIFY;
        }

        public static final StreamCodec<RegistryFriendlyByteBuf, AftermathView>
                CODEC = StreamCodec.of((buf, view) -> {
                    buf.writeVarInt(WIRE_VERSION);
                    buf.writeBoolean(view.present());
                    if (!view.present()) {
                        return;
                    }
                    buf.writeBoolean(view.held());
                    buf.writeVarLong(view.night());
                    buf.writeUtf(view.captainName(),
                        RaidLogEntry.MAX_CAPTAIN_NAME);
                    buf.writeUtf(view.objectiveId(), 32);
                    buf.writeVarInt(view.itemsStolen());
                    buf.writeVarInt(view.settlersHurt());
                    buf.writeUtf(view.threatStageId(), 32);
                    buf.writeVarInt(view.rewardStatusWireId());
                    buf.writeVarInt(view.offerSerial());
                    buf.writeVarInt(view.roadAheadWireId());
                }, buf -> {
                    int version = buf.readVarInt();
                    if (version != WIRE_VERSION) {
                        throw new IllegalArgumentException(
                            "unsupported raid aftermath version " + version);
                    }
                    if (!buf.readBoolean()) {
                        return closed();
                    }
                    return new AftermathView(true, buf.readBoolean(),
                        buf.readVarLong(),
                        buf.readUtf(RaidLogEntry.MAX_CAPTAIN_NAME),
                        buf.readUtf(32), buf.readVarInt(), buf.readVarInt(),
                        buf.readUtf(32), buf.readVarInt(), buf.readVarInt(),
                        buf.readVarInt());
                });

        public enum RewardStatus {
            NO_OFFER(0, "none"),
            OFFER_PENDING(1, "pending"),
            ALL_CLAIMED(2, "claimed"),
            UNAVAILABLE(3, "unavailable");

            private final int wireId;
            private final String id;

            RewardStatus(int wireId, String id) {
                this.wireId = wireId;
                this.id = id;
            }

            public int wireId() {
                return wireId;
            }

            public String id() {
                return id;
            }

            public static RewardStatus fromWireId(int wireId) {
                for (RewardStatus status : values()) {
                    if (status.wireId == wireId) {
                        return status;
                    }
                }
                return null;
            }
        }

        public enum RoadAhead {
            CLAIM_REWARD(0, "claim_reward"),
            RECOVER_AND_CLAIM(1, "recover_and_claim"),
            RECOVER(2, "recover"),
            FORTIFY(3, "fortify"),
            REWARD_UNAVAILABLE(4, "reward_unavailable");

            private final int wireId;
            private final String id;

            RoadAhead(int wireId, String id) {
                this.wireId = wireId;
                this.id = id;
            }

            public int wireId() {
                return wireId;
            }

            public String id() {
                return id;
            }

            public static RoadAhead fromWireId(int wireId) {
                for (RoadAhead road : values()) {
                    if (road.wireId == wireId) {
                        return road;
                    }
                }
                return null;
            }
        }
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, HearthMayorSnapshot> CODEC =
        StreamCodec.of(HearthMayorSnapshot::write, HearthMayorSnapshot::read);

    private static void write(RegistryFriendlyByteBuf buf, HearthMayorSnapshot snapshot) {
        buf.writeVarInt(snapshot.revision);
        buf.writeBoolean(snapshot.hasMayor);
        UUIDUtil.STREAM_CODEC.encode(buf, snapshot.mayorId);
        buf.writeUtf(snapshot.mayorName);
        buf.writeUtf(snapshot.boonKey);
        buf.writeLong(snapshot.mayorSince);
        buf.writeBoolean(snapshot.mourning);
        buf.writeLong(snapshot.mourningUntil);
        buf.writeVarInt(snapshot.candidates.size());
        for (Candidate candidate : snapshot.candidates) {
            Candidate.CODEC.encode(buf, candidate);
        }
        buf.writeVarInt(snapshot.residents.size());
        for (Resident resident : snapshot.residents) {
            Resident.CODEC.encode(buf, resident);
        }
        buf.writeVarInt(snapshot.residentTotal);
        buf.writeBoolean(snapshot.mayManage);
        RecruitmentCard.CODEC.encode(buf, snapshot.recruitment);
        RequestView.CODEC.encode(buf, snapshot.requests);
        ReadinessView.CODEC.encode(buf, snapshot.readiness);
        RecurringStatusView.CODEC.encode(buf, snapshot.recurringStatus);
        AftermathView.CODEC.encode(buf, snapshot.aftermath);
    }

    private static HearthMayorSnapshot read(RegistryFriendlyByteBuf buf) {
        int revision = buf.readVarInt();
        boolean hasMayor = buf.readBoolean();
        UUID mayorId = UUIDUtil.STREAM_CODEC.decode(buf);
        String mayorName = buf.readUtf();
        String boonKey = buf.readUtf();
        long mayorSince = buf.readLong();
        boolean mourning = buf.readBoolean();
        long mourningUntil = buf.readLong();
        int candidateCount = buf.readVarInt();
        if (candidateCount < 0 || candidateCount > MAX_MAYOR_CANDIDATES) {
            throw new IllegalArgumentException("unbounded mayor candidates");
        }
        List<Candidate> candidates = new ArrayList<>(candidateCount);
        for (int i = 0; i < candidateCount; i++) {
            candidates.add(Candidate.CODEC.decode(buf));
        }
        int residentCount = buf.readVarInt();
        if (residentCount < 0 || residentCount > MAX_RESIDENTS) {
            throw new IllegalArgumentException("unbounded settlement residents");
        }
        List<Resident> residents = new ArrayList<>(residentCount);
        for (int i = 0; i < residentCount; i++) {
            residents.add(Resident.CODEC.decode(buf));
        }
        int residentTotal = buf.readVarInt();
        if (residentTotal < residentCount) {
            throw new IllegalArgumentException("resident total below bounded page");
        }
        boolean mayManage = buf.readBoolean();
        return new HearthMayorSnapshot(revision, hasMayor, mayorId, mayorName, boonKey,
            mayorSince, mourning, mourningUntil, List.copyOf(candidates), List.copyOf(residents),
            residentTotal, mayManage, RecruitmentCard.CODEC.decode(buf), RequestView.CODEC.decode(buf),
            ReadinessView.CODEC.decode(buf),
            RecurringStatusView.CODEC.decode(buf), AftermathView.CODEC.decode(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    private static UUID requireUuid(UUID id, String field) {
        if (id == null || HearthMayorAction.NO_ID.equals(id)) {
            throw new IllegalArgumentException(field + " UUID");
        }
        return id;
    }

    private static String bounded(String value, int max, String fallback) {
        String safe = value == null || value.isBlank() ? fallback : value;
        if (safe.length() > max || safe.indexOf('\n') >= 0
            || safe.indexOf('\r') >= 0 || safe.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("unbounded snapshot text");
        }
        return safe;
    }
}
