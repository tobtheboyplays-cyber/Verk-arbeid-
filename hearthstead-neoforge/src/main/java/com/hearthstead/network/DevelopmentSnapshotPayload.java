package com.hearthstead.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Immutable server truth for either the Hearth tech map or Mayor emblem shop. */
public record DevelopmentSnapshotPayload(BlockPos hearthPos, UUID settlementId, UUID mayorId,
                                         int mayorEntityId,
                                         DevelopmentActionPayload.View view,
                                         int revision, int activeDoctrineWireId,
                                         long doctrineChosenAt, int availableCoins,
                                         List<NodeView> nodes,
                                         List<EmblemView> emblems,
                                         Optional<Component> feedback,
                                         List<UpgradeView> upgrades)
    implements CustomPacketPayload {

    /**
     * Server truth for one post-raid upgrade (Chronicle era IV). The status
     * reuses {@code Development.NodeStatus} wire ids: OWNED, AVAILABLE or
     * LOCKED; {@code reasonKey} is empty exactly when the purchase would be
     * applied now.
     */
    public record UpgradeView(int upgradeWireId, int statusWireId, String reasonKey,
                              int coinCost) {
        public UpgradeView {
            reasonKey = reasonKey == null ? "" : reasonKey;
            coinCost = Math.max(0, coinCost);
        }

        static final StreamCodec<RegistryFriendlyByteBuf, UpgradeView> CODEC =
            StreamCodec.of((buf, view) -> {
                buf.writeVarInt(view.upgradeWireId);
                buf.writeVarInt(view.statusWireId);
                buf.writeUtf(view.reasonKey, 160);
                buf.writeVarInt(view.coinCost);
            }, buf -> new UpgradeView(buf.readVarInt(), buf.readVarInt(),
                buf.readUtf(160), buf.readVarInt()));
    }

    public record QuestView(int objectiveWireId, int progress, int target) {
        static final StreamCodec<RegistryFriendlyByteBuf, QuestView> CODEC =
            StreamCodec.of((buf, view) -> {
                buf.writeVarInt(view.objectiveWireId);
                buf.writeVarInt(view.progress);
                buf.writeVarInt(view.target);
            }, buf -> new QuestView(buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));
    }

    public record NodeView(int nodeWireId, int statusWireId, String reasonKey,
                           List<QuestView> quests) {
        public NodeView {
            quests = List.copyOf(quests == null ? List.of() : quests);
        }

        static final StreamCodec<RegistryFriendlyByteBuf, NodeView> CODEC =
            StreamCodec.of((buf, view) -> {
                buf.writeVarInt(view.nodeWireId);
                buf.writeVarInt(view.statusWireId);
                buf.writeUtf(view.reasonKey, 160);
                buf.writeVarInt(view.quests.size());
                for (QuestView quest : view.quests) {
                    QuestView.CODEC.encode(buf, quest);
                }
            }, buf -> {
                int node = buf.readVarInt();
                int status = buf.readVarInt();
                String reason = buf.readUtf(160);
                int count = Math.min(8, Math.max(0, buf.readVarInt()));
                ArrayList<QuestView> quests = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    quests.add(QuestView.CODEC.decode(buf));
                }
                return new NodeView(node, status, reason, quests);
            });
    }

    public record EmblemView(int professionWireId, boolean available,
                             String reasonKey) {
        static final StreamCodec<RegistryFriendlyByteBuf, EmblemView> CODEC =
            StreamCodec.of((buf, view) -> {
                buf.writeVarInt(view.professionWireId);
                buf.writeBoolean(view.available);
                buf.writeUtf(view.reasonKey, 160);
            }, buf -> new EmblemView(buf.readVarInt(), buf.readBoolean(), buf.readUtf(160)));
    }

    public static final Type<DevelopmentSnapshotPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "development_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, DevelopmentSnapshotPayload> CODEC =
        StreamCodec.of(DevelopmentSnapshotPayload::write, DevelopmentSnapshotPayload::read);

    /** Older local fixtures have no wallet observation; -1 means unavailable, not zero. */
    public DevelopmentSnapshotPayload(BlockPos hearthPos, UUID settlementId, UUID mayorId,
            int mayorEntityId, DevelopmentActionPayload.View view, int revision,
            int activeDoctrineWireId, long doctrineChosenAt, List<NodeView> nodes,
            List<EmblemView> emblems, Optional<Component> feedback) {
        this(hearthPos, settlementId, mayorId, mayorEntityId, view, revision,
            activeDoctrineWireId, doctrineChosenAt, -1, nodes, emblems, feedback,
            List.of());
    }

    /** Snapshot without post-raid upgrade rows (emblem shop, older fixtures). */
    public DevelopmentSnapshotPayload(BlockPos hearthPos, UUID settlementId, UUID mayorId,
            int mayorEntityId, DevelopmentActionPayload.View view, int revision,
            int activeDoctrineWireId, long doctrineChosenAt, int availableCoins,
            List<NodeView> nodes, List<EmblemView> emblems,
            Optional<Component> feedback) {
        this(hearthPos, settlementId, mayorId, mayorEntityId, view, revision,
            activeDoctrineWireId, doctrineChosenAt, availableCoins, nodes, emblems,
            feedback, List.of());
    }

    public DevelopmentSnapshotPayload {
        hearthPos = hearthPos == null ? BlockPos.ZERO : hearthPos.immutable();
        settlementId = settlementId == null ? HearthMayorAction.NO_ID : settlementId;
        mayorId = mayorId == null ? HearthMayorAction.NO_ID : mayorId;
        mayorEntityId = Math.max(-1, mayorEntityId);
        view = view == null ? DevelopmentActionPayload.View.UNKNOWN : view;
        availableCoins = Math.max(-1, availableCoins);
        nodes = List.copyOf(nodes == null ? List.of() : nodes);
        emblems = List.copyOf(emblems == null ? List.of() : emblems);
        feedback = feedback == null ? Optional.empty() : feedback;
        upgrades = List.copyOf(upgrades == null ? List.of() : upgrades);
    }

    private static void write(RegistryFriendlyByteBuf buf, DevelopmentSnapshotPayload snapshot) {
        BlockPos.STREAM_CODEC.encode(buf, snapshot.hearthPos);
        UUIDUtil.STREAM_CODEC.encode(buf, snapshot.settlementId);
        UUIDUtil.STREAM_CODEC.encode(buf, snapshot.mayorId);
        // +1 keeps the absent sentinel compact and rejects negative ids.
        buf.writeVarInt(snapshot.mayorEntityId + 1);
        buf.writeVarInt(snapshot.view.wireId());
        buf.writeVarInt(snapshot.revision);
        buf.writeVarInt(snapshot.activeDoctrineWireId);
        buf.writeLong(snapshot.doctrineChosenAt);
        buf.writeVarInt(snapshot.availableCoins);
        buf.writeVarInt(snapshot.nodes.size());
        for (NodeView view : snapshot.nodes) {
            NodeView.CODEC.encode(buf, view);
        }
        buf.writeVarInt(snapshot.emblems.size());
        for (EmblemView view : snapshot.emblems) {
            EmblemView.CODEC.encode(buf, view);
        }
        ComponentSerialization.OPTIONAL_STREAM_CODEC.encode(buf, snapshot.feedback);
        buf.writeVarInt(snapshot.upgrades.size());
        for (UpgradeView view : snapshot.upgrades) {
            UpgradeView.CODEC.encode(buf, view);
        }
    }

    private static DevelopmentSnapshotPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = BlockPos.STREAM_CODEC.decode(buf);
        UUID settlementId = UUIDUtil.STREAM_CODEC.decode(buf);
        UUID mayorId = UUIDUtil.STREAM_CODEC.decode(buf);
        int mayorEntityId = buf.readVarInt() - 1;
        DevelopmentActionPayload.View view = DevelopmentActionPayload.View.fromWireId(
            buf.readVarInt());
        int revision = buf.readVarInt();
        int doctrine = buf.readVarInt();
        long chosenAt = buf.readLong();
        int availableCoins = buf.readVarInt();
        int nodeCount = Math.min(128, Math.max(0, buf.readVarInt()));
        List<NodeView> nodes = new ArrayList<>(nodeCount);
        for (int i = 0; i < nodeCount; i++) {
            nodes.add(NodeView.CODEC.decode(buf));
        }
        int emblemCount = Math.min(64, Math.max(0, buf.readVarInt()));
        List<EmblemView> emblems = new ArrayList<>(emblemCount);
        for (int i = 0; i < emblemCount; i++) {
            emblems.add(EmblemView.CODEC.decode(buf));
        }
        Optional<Component> feedback = ComponentSerialization.OPTIONAL_STREAM_CODEC.decode(buf);
        int upgradeCount = Math.min(16, Math.max(0, buf.readVarInt()));
        List<UpgradeView> upgrades = new ArrayList<>(upgradeCount);
        for (int i = 0; i < upgradeCount; i++) {
            upgrades.add(UpgradeView.CODEC.decode(buf));
        }
        return new DevelopmentSnapshotPayload(pos, settlementId, mayorId, mayorEntityId,
            view, revision,
            doctrine, chosenAt, availableCoins, nodes, emblems, feedback, upgrades);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
