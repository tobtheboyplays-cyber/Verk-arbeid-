package com.hearthstead.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Server -> client: the settlement's v3 tech tree state. Node layout, names,
 * costs and text come from the jar's data files on the client; this carries
 * only what the server decides (status, reason, gate progress, how much of
 * each cost line the treasury holds, study progress).
 */
public record TechTreeSnapshotPayload(BlockPos hearthPos, UUID settlementId, int revision,
                                      int coins, List<NodeState> nodes, String feedbackKey,
                                      String feedbackArg, boolean feedbackGood,
                                      boolean openScreen, String journeyNode) implements CustomPacketPayload {
    private static final int MAX_NODES = 256;
    private static final int MAX_LINES = 16;

    public record Gate(String kind, String detail, int progress, int target) {
    }

    /**
     * @param status  TechTree.Status ordinal
     * @param have    treasury count per cost line (Coins first when priced)
     */
    public record NodeState(String id, int status, String reason, String reasonArg, int[] have,
                            List<Gate> gates, long studyTotal, long studyDone) {
    }

    public static final Type<TechTreeSnapshotPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "techtree_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TechTreeSnapshotPayload> CODEC =
        StreamCodec.of(TechTreeSnapshotPayload::write, TechTreeSnapshotPayload::read);

    public TechTreeSnapshotPayload {
        nodes = List.copyOf(nodes);
        feedbackKey = feedbackKey == null ? "" : feedbackKey;
        feedbackArg = feedbackArg == null ? "" : feedbackArg;
        journeyNode = journeyNode == null ? "" : journeyNode;
    }

    public NodeState node(String id) {
        for (NodeState node : nodes) {
            if (node.id().equals(id)) {
                return node;
            }
        }
        return null;
    }

    private static void write(RegistryFriendlyByteBuf buf, TechTreeSnapshotPayload p) {
        buf.writeBlockPos(p.hearthPos);
        UUIDUtil.STREAM_CODEC.encode(buf, p.settlementId);
        buf.writeVarInt(p.revision);
        buf.writeVarInt(p.coins);
        buf.writeVarInt(Math.min(MAX_NODES, p.nodes.size()));
        for (int i = 0; i < Math.min(MAX_NODES, p.nodes.size()); i++) {
            NodeState n = p.nodes.get(i);
            buf.writeUtf(n.id, 64);
            buf.writeVarInt(n.status);
            buf.writeUtf(n.reason, 128);
            buf.writeUtf(n.reasonArg, 128);
            int lines = Math.min(MAX_LINES, n.have.length);
            buf.writeVarInt(lines);
            for (int j = 0; j < lines; j++) {
                buf.writeVarInt(Math.max(0, n.have[j]));
            }
            int gates = Math.min(MAX_LINES, n.gates.size());
            buf.writeVarInt(gates);
            for (int j = 0; j < gates; j++) {
                Gate g = n.gates.get(j);
                buf.writeUtf(g.kind, 64);
                buf.writeUtf(g.detail, 256);
                buf.writeVarInt(Math.max(0, g.progress));
                buf.writeVarInt(Math.max(0, g.target));
            }
            buf.writeVarLong(Math.max(0L, n.studyTotal));
            buf.writeVarLong(Math.max(0L, n.studyDone));
        }
        buf.writeUtf(p.feedbackKey, 128);
        buf.writeUtf(p.feedbackArg, 128);
        buf.writeBoolean(p.feedbackGood);
        buf.writeBoolean(p.openScreen);
        buf.writeUtf(p.journeyNode, 64);
    }

    private static TechTreeSnapshotPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        UUID settlement = UUIDUtil.STREAM_CODEC.decode(buf);
        int revision = buf.readVarInt();
        int coins = buf.readVarInt();
        int count = Math.min(MAX_NODES, buf.readVarInt());
        List<NodeState> nodes = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = buf.readUtf(64);
            int status = buf.readVarInt();
            String reason = buf.readUtf(128);
            String reasonArg = buf.readUtf(128);
            int lines = Math.min(MAX_LINES, buf.readVarInt());
            int[] have = new int[lines];
            for (int j = 0; j < lines; j++) {
                have[j] = buf.readVarInt();
            }
            int gateCount = Math.min(MAX_LINES, buf.readVarInt());
            List<Gate> gates = new ArrayList<>(gateCount);
            for (int j = 0; j < gateCount; j++) {
                gates.add(new Gate(buf.readUtf(64), buf.readUtf(256), buf.readVarInt(),
                    buf.readVarInt()));
            }
            nodes.add(new NodeState(id, status, reason, reasonArg, have, List.copyOf(gates),
                buf.readVarLong(), buf.readVarLong()));
        }
        return new TechTreeSnapshotPayload(pos, settlement, revision, coins, nodes,
            buf.readUtf(128), buf.readUtf(128), buf.readBoolean(), buf.readBoolean(), buf.readUtf(64));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
