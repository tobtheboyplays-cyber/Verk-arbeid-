package com.hearthstead.network;

import net.minecraft.core.BlockPos;
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
 * Everything the research screen draws, resolved on the server and sent as
 * one message — {@link PlaqueSnapshot}'s own discipline: the client renders
 * only what it was told, never works out affordability or eligibility itself.
 *
 * <p>What is <b>not</b> sent is deliberate: every project's name, effect
 * sentence, emblem and required costs are {@code ResearchProject} constants —
 * common code the client already has, the same way the client already knows
 * {@code BuildingType.byId(...).emblem()} without the plaque snapshot
 * repeating it. This payload carries only what actually varies: what has been
 * gathered toward each cost, which six are done, and what (if anything) is
 * under way right now.
 */
public record ResearchSnapshotPayload(BlockPos pos, int revision, boolean mayManage,
                                      String scholarName, int activeOrdinal, int activeSessions,
                                      List<Integer> completedOrdinals,
                                      List<List<Integer>> costHaves,
                                      Optional<Component> refusal)
    implements CustomPacketPayload {

    public static final Type<ResearchSnapshotPayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "research_snapshot"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ResearchSnapshotPayload> CODEC =
        StreamCodec.of(ResearchSnapshotPayload::write, ResearchSnapshotPayload::read);

    private static void write(RegistryFriendlyByteBuf buf, ResearchSnapshotPayload snapshot) {
        buf.writeBlockPos(snapshot.pos);
        buf.writeVarInt(snapshot.revision);
        buf.writeBoolean(snapshot.mayManage);
        buf.writeUtf(snapshot.scholarName);
        buf.writeVarInt(snapshot.activeOrdinal);
        buf.writeVarInt(snapshot.activeSessions);
        buf.writeVarInt(snapshot.completedOrdinals.size());
        for (int ordinal : snapshot.completedOrdinals) {
            buf.writeVarInt(ordinal);
        }
        buf.writeVarInt(snapshot.costHaves.size());
        for (List<Integer> haves : snapshot.costHaves) {
            buf.writeVarInt(haves.size());
            for (int have : haves) {
                buf.writeVarInt(have);
            }
        }
        ComponentSerialization.OPTIONAL_STREAM_CODEC.encode(buf, snapshot.refusal);
    }

    private static ResearchSnapshotPayload read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        int revision = buf.readVarInt();
        boolean mayManage = buf.readBoolean();
        String scholarName = buf.readUtf();
        int activeOrdinal = buf.readVarInt();
        int activeSessions = buf.readVarInt();
        int completedCount = buf.readVarInt();
        List<Integer> completedOrdinals = new ArrayList<>(completedCount);
        for (int i = 0; i < completedCount; i++) {
            completedOrdinals.add(buf.readVarInt());
        }
        int projectCount = buf.readVarInt();
        List<List<Integer>> costHaves = new ArrayList<>(projectCount);
        for (int i = 0; i < projectCount; i++) {
            int lineCount = buf.readVarInt();
            List<Integer> haves = new ArrayList<>(lineCount);
            for (int j = 0; j < lineCount; j++) {
                haves.add(buf.readVarInt());
            }
            costHaves.add(List.copyOf(haves));
        }
        Optional<Component> refusal = ComponentSerialization.OPTIONAL_STREAM_CODEC.decode(buf);
        return new ResearchSnapshotPayload(pos, revision, mayManage, scholarName,
            activeOrdinal, activeSessions, List.copyOf(completedOrdinals),
            List.copyOf(costHaves), refusal);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
