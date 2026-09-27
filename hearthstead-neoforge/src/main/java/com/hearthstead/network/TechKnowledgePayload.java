package com.hearthstead.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

/**
 * Server -> client: the tech nodes learned by the settlement the player
 * crafts for (crafting padlock, handbook, recipe hints), and whether crafting
 * is tech-gated on this server.
 */
public record TechKnowledgePayload(boolean gating, List<String> learned) implements CustomPacketPayload {
    public static final Type<TechKnowledgePayload> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "tech_knowledge"));

    public static final StreamCodec<RegistryFriendlyByteBuf, TechKnowledgePayload> CODEC =
        StreamCodec.of(TechKnowledgePayload::write, TechKnowledgePayload::read);

    public TechKnowledgePayload {
        learned = List.copyOf(learned);
    }

    private static void write(RegistryFriendlyByteBuf buf, TechKnowledgePayload p) {
        buf.writeBoolean(p.gating);
        int n = Math.min(512, p.learned.size());
        buf.writeVarInt(n);
        for (int i = 0; i < n; i++) {
            buf.writeUtf(p.learned.get(i), 64);
        }
    }

    private static TechKnowledgePayload read(RegistryFriendlyByteBuf buf) {
        boolean gating = buf.readBoolean();
        int n = Math.min(512, buf.readVarInt());
        List<String> learned = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            learned.add(buf.readUtf(64));
        }
        return new TechKnowledgePayload(gating, learned);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
