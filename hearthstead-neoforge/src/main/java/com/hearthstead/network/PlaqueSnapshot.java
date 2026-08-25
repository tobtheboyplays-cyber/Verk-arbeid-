package com.hearthstead.network;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
 * Everything the plaque screen draws, resolved on the server and sent as one
 * message.
 *
 * <p>The client is never trusted to work any of this out. It cannot know
 * whether a settler is eligible, what a building's capacity is, or whether a
 * requirement is met — so it is told, and it renders only what it was told.
 * The {@code revision} it receives comes back with every action, which is how
 * a click made against a stale screen gets refused.
 */
public record PlaqueSnapshot(BlockPos pos, String buildingType, String state,
                             int revision, int level,
                             List<RequirementLine> requirements,
                             List<Occupant> occupants,
                             List<Candidate> candidates,
                             int capacity, boolean mayManage)
    implements CustomPacketPayload {

    public static final Type<PlaqueSnapshot> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "plaque_snapshot"));

    /** One requirement row: "lanterns 1 / 2". */
    public record RequirementLine(String id, int have, int needed) {
        public static final StreamCodec<RegistryFriendlyByteBuf, RequirementLine> CODEC =
            StreamCodec.composite(
                ByteBufCodecs.STRING_UTF8, RequirementLine::id,
                ByteBufCodecs.VAR_INT, RequirementLine::have,
                ByteBufCodecs.VAR_INT, RequirementLine::needed,
                RequirementLine::new);
    }

    /** Someone who lives or works here. */
    public record Occupant(UUID id, String name, String profession,
                           float health, float maxHealth, int morale, boolean worker) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Occupant> CODEC =
            StreamCodec.of((buf, o) -> {
                net.minecraft.core.UUIDUtil.STREAM_CODEC.encode(buf, o.id());
                buf.writeUtf(o.name());
                buf.writeUtf(o.profession());
                buf.writeFloat(o.health());
                buf.writeFloat(o.maxHealth());
                buf.writeVarInt(o.morale());
                buf.writeBoolean(o.worker());
            }, buf -> new Occupant(
                net.minecraft.core.UUIDUtil.STREAM_CODEC.decode(buf),
                buf.readUtf(), buf.readUtf(), buf.readFloat(), buf.readFloat(),
                buf.readVarInt(), buf.readBoolean()));
    }

    /**
     * Someone who could move in or take the post.
     *
     * <p>{@code blockedReason} is empty when they are eligible; when it is not,
     * the row is drawn disabled and the reason is shown, because a greyed-out
     * row with no explanation is a bug report waiting to happen (D-014).
     *
     * <p>{@code fitness} is 0–5, drawn as pips rather than printed, because you
     * read "four of five" at a glance and never read "62" at a glance.
     *
     * <p>{@code costKey} and {@code costArg} are the sentence that says what
     * taking this settler costs — "the Farmhouse would have no worker" — sent
     * as a key and its argument so the client renders it in its own language.
     * The server works it out; the client is never asked to.
     */
    public record Candidate(UUID id, String name, String profession,
                            boolean housed, int distance, String blockedReason,
                            int fitness, String costKey, String costArg) {
        public static final StreamCodec<RegistryFriendlyByteBuf, Candidate> CODEC =
            StreamCodec.of((buf, c) -> {
                net.minecraft.core.UUIDUtil.STREAM_CODEC.encode(buf, c.id());
                buf.writeUtf(c.name());
                buf.writeUtf(c.profession());
                buf.writeBoolean(c.housed());
                buf.writeVarInt(c.distance());
                buf.writeUtf(c.blockedReason());
                buf.writeVarInt(c.fitness());
                buf.writeUtf(c.costKey());
                buf.writeUtf(c.costArg());
            }, buf -> new Candidate(
                net.minecraft.core.UUIDUtil.STREAM_CODEC.decode(buf),
                buf.readUtf(), buf.readUtf(), buf.readBoolean(), buf.readVarInt(),
                buf.readUtf(), buf.readVarInt(), buf.readUtf(), buf.readUtf()));
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, PlaqueSnapshot> CODEC =
        StreamCodec.of(PlaqueSnapshot::write, PlaqueSnapshot::read);

    private static void write(RegistryFriendlyByteBuf buf, PlaqueSnapshot snapshot) {
        buf.writeBlockPos(snapshot.pos);
        buf.writeUtf(snapshot.buildingType);
        buf.writeUtf(snapshot.state);
        buf.writeVarInt(snapshot.revision);
        buf.writeVarInt(snapshot.level);
        buf.writeVarInt(snapshot.requirements.size());
        for (RequirementLine line : snapshot.requirements) {
            RequirementLine.CODEC.encode(buf, line);
        }
        buf.writeVarInt(snapshot.occupants.size());
        for (Occupant occupant : snapshot.occupants) {
            Occupant.CODEC.encode(buf, occupant);
        }
        buf.writeVarInt(snapshot.candidates.size());
        for (Candidate candidate : snapshot.candidates) {
            Candidate.CODEC.encode(buf, candidate);
        }
        buf.writeVarInt(snapshot.capacity);
        buf.writeBoolean(snapshot.mayManage);
    }

    private static PlaqueSnapshot read(RegistryFriendlyByteBuf buf) {
        BlockPos pos = buf.readBlockPos();
        String type = buf.readUtf();
        String state = buf.readUtf();
        int revision = buf.readVarInt();
        int level = buf.readVarInt();
        int requirementCount = buf.readVarInt();
        List<RequirementLine> requirements = new java.util.ArrayList<>(requirementCount);
        for (int i = 0; i < requirementCount; i++) {
            requirements.add(RequirementLine.CODEC.decode(buf));
        }
        int occupantCount = buf.readVarInt();
        List<Occupant> occupants = new java.util.ArrayList<>(occupantCount);
        for (int i = 0; i < occupantCount; i++) {
            occupants.add(Occupant.CODEC.decode(buf));
        }
        int candidateCount = buf.readVarInt();
        List<Candidate> candidates = new java.util.ArrayList<>(candidateCount);
        for (int i = 0; i < candidateCount; i++) {
            candidates.add(Candidate.CODEC.decode(buf));
        }
        return new PlaqueSnapshot(pos, type, state, revision, level,
            List.copyOf(requirements), List.copyOf(occupants), List.copyOf(candidates),
            buf.readVarInt(), buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
