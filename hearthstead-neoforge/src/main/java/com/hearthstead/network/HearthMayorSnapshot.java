package com.hearthstead.network;

import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

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
                                  List<Candidate> candidates, boolean mayManage)
    implements CustomPacketPayload {

    public static final Type<HearthMayorSnapshot> TYPE = new Type<>(
        ResourceLocation.fromNamespaceAndPath("hearthstead", "hearth_mayor_snapshot"));

    /**
     * Someone who could take the seat, and the boon they would eventually
     * bring -- {@code Mayor.boonOf} is one boon per key attribute, so this is
     * never ambiguous. {@code knack} is that attribute's own 0-100 score,
     * carried so the screen can draw it as pips: a candidate's fitness for
     * their own boon reads at a glance rather than as a raw number.
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
        buf.writeBoolean(snapshot.mayManage);
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
        List<Candidate> candidates = new ArrayList<>(candidateCount);
        for (int i = 0; i < candidateCount; i++) {
            candidates.add(Candidate.CODEC.decode(buf));
        }
        boolean mayManage = buf.readBoolean();
        return new HearthMayorSnapshot(revision, hasMayor, mayorId, mayorName, boonKey,
            mayorSince, mourning, mourningUntil, List.copyOf(candidates), mayManage);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
