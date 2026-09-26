package com.hearthstead.finisher;

import com.hearthstead.Hearthstead;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * The four finisher packets. Presentation packets carry entity ids and the
 * variant only; the server alone decides eligibility, reach, timing and the
 * kill. Clients never trust them for gameplay.
 */
public final class FinisherPayloads {
    private FinisherPayloads() {
    }

    /** Client to server: "I pressed the finish key on this entity". */
    public record Request(int targetId) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Hearthstead.id("finisher_request"));
        public static final StreamCodec<ByteBuf, Request> CODEC = new StreamCodec<>() {
            public Request decode(ByteBuf buf) {
                return new Request(new FriendlyByteBuf(buf).readVarInt());
            }

            public void encode(ByteBuf buf, Request value) {
                new FriendlyByteBuf(buf).writeVarInt(value.targetId());
            }
        };

        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server to tracking clients: the finish-window state of one enemy.
     * {@code state}: 0 closed, 1 open (glow + prompt), 2 a co-op double is
     * waiting for a partner (glow + "join" prompt).
     */
    public record Window(int entityId, byte state, int ticksLeft) implements CustomPacketPayload {
        public static final byte CLOSED = 0;
        public static final byte OPEN = 1;
        public static final byte JOIN = 2;
        public static final Type<Window> TYPE = new Type<>(Hearthstead.id("finisher_window"));
        public static final StreamCodec<ByteBuf, Window> CODEC = new StreamCodec<>() {
            public Window decode(ByteBuf buf) {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new Window(b.readVarInt(), b.readByte(), b.readVarInt());
            }

            public void encode(ByteBuf buf, Window value) {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(value.entityId());
                b.writeByte(value.state());
                b.writeVarInt(Math.max(0, value.ticksLeft()));
            }
        };

        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server to tracking clients: an execution starts (or, for a client that
     * starts tracking mid-move, is {@code elapsedTicks} in). Yaw values are
     * the fixed facings the server locked every participant to, so every
     * viewer renders the identical choreography.
     *
     * <p>{@code variant == -1} is the co-op READY hold (lead steadied, waiting
     * for a partner). {@code flags}: bit 0 = guard execution (the guard plays
     * its own GUARD_FINISHER_DRIVE, only the victim and impact are staged).</p>
     */
    public record Start(int victimId, int leadId, int partnerId, int variant, int elapsedTicks,
                        float victimYaw, float leadYaw, float partnerYaw, int flags)
        implements CustomPacketPayload {
        public static final int FLAG_GUARD = 1;
        public static final Type<Start> TYPE = new Type<>(Hearthstead.id("finisher_start"));
        public static final StreamCodec<ByteBuf, Start> CODEC = new StreamCodec<>() {
            public Start decode(ByteBuf buf) {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new Start(b.readVarInt(), b.readVarInt(), b.readVarInt() - 1,
                    b.readVarInt() - 1, b.readVarInt(), b.readFloat(), b.readFloat(),
                    b.readFloat(), b.readVarInt());
            }

            public void encode(ByteBuf buf, Start v) {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(v.victimId());
                b.writeVarInt(v.leadId());
                b.writeVarInt(v.partnerId() + 1);
                b.writeVarInt(v.variant() + 1);
                b.writeVarInt(Math.max(0, v.elapsedTicks()));
                b.writeFloat(v.victimYaw());
                b.writeFloat(v.leadYaw());
                b.writeFloat(v.partnerYaw());
                b.writeVarInt(v.flags());
            }
        };

        public boolean isReadyHold() {
            return variant < 0;
        }

        public boolean isGuard() {
            return (flags & FLAG_GUARD) != 0;
        }

        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server to tracking clients: this enemy lost its balance for {@code ticks}
     * (0 = regained). Drives the off-balance wobble; the finish window itself
     * is the separate {@link Window} (off-balance AND below 10% health).
     */
    public record Balance(int entityId, int ticks) implements CustomPacketPayload {
        public static final Type<Balance> TYPE = new Type<>(Hearthstead.id("finisher_balance"));
        public static final StreamCodec<ByteBuf, Balance> CODEC = new StreamCodec<>() {
            public Balance decode(ByteBuf buf) {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new Balance(b.readVarInt(), b.readVarInt());
            }

            public void encode(ByteBuf buf, Balance value) {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(value.entityId());
                b.writeVarInt(Math.max(0, value.ticks()));
            }
        };

        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /** Server to tracking clients: an execution ended early (aborted) or normally. */
    public record End(int victimId, boolean aborted) implements CustomPacketPayload {
        public static final Type<End> TYPE = new Type<>(Hearthstead.id("finisher_end"));
        public static final StreamCodec<ByteBuf, End> CODEC = new StreamCodec<>() {
            public End decode(ByteBuf buf) {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new End(b.readVarInt(), b.readBoolean());
            }

            public void encode(ByteBuf buf, End value) {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(value.victimId());
                b.writeBoolean(value.aborted());
            }
        };

        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
