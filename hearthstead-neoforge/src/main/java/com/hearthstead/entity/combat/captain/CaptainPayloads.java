package com.hearthstead.entity.combat.captain;

import com.hearthstead.Hearthstead;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Captain packets. The client only ever ASKS (rename, switch loadout,
 * cosmetics); the server alone validates the hero, the player, the items and
 * the re-arm window, then answers with the new state.
 */
public final class CaptainPayloads {
    public static final int MAX_NAME = 24;

    private CaptainPayloads() {
    }

    /** Client to server: one Captain panel action. */
    public record Action(int entityId, int kind, int value, String text) implements CustomPacketPayload {
        public static final int REFRESH = 0;
        public static final int RENAME = 1;
        public static final int LOADOUT = 2;
        public static final int CAPE = 3;
        public static final int PLUME = 4;
        public static final int DISMISS_PROMPT = 5;
        public static final Type<Action> TYPE = new Type<>(Hearthstead.id("captain_action"));
        public static final StreamCodec<ByteBuf, Action> CODEC = new StreamCodec<>() {
            public Action decode(ByteBuf buf) {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                return new Action(b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf(64));
            }

            public void encode(ByteBuf buf, Action v) {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(v.entityId());
                b.writeVarInt(v.kind());
                b.writeVarInt(v.value());
                b.writeUtf(v.text() == null ? "" : v.text(), 64);
            }
        };

        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }

    /**
     * Server to tracking clients: one settler's hero state. {@code cooldowns}
     * holds the remaining ticks of every special in {@link CaptainSpecial}
     * order ({@code -1} = not available to this loadout); {@code feedback} is
     * a translation key for the last action's result, or empty.
     */
    public record State(int entityId, boolean hero, int loadout, int cape, boolean plume,
                        boolean prompt, int rearmTicks, int[] cooldowns, int heldLoadout,
                        String feedback) implements CustomPacketPayload {
        public static final Type<State> TYPE = new Type<>(Hearthstead.id("captain_state"));
        public static final StreamCodec<ByteBuf, State> CODEC = new StreamCodec<>() {
            public State decode(ByteBuf buf) {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                int id = b.readVarInt();
                boolean hero = b.readBoolean();
                int loadout = b.readVarInt();
                int cape = b.readVarInt() - 1;
                boolean plume = b.readBoolean();
                boolean prompt = b.readBoolean();
                int rearm = b.readVarInt();
                int n = Math.min(64, b.readVarInt());
                int[] cds = new int[n];
                for (int i = 0; i < n; i++) {
                    cds[i] = b.readVarInt() - 1;
                }
                int held = b.readVarInt() - 1;
                String fb = b.readUtf(128);
                return new State(id, hero, loadout, cape, plume, prompt, rearm, cds, held, fb);
            }

            public void encode(ByteBuf buf, State v) {
                FriendlyByteBuf b = new FriendlyByteBuf(buf);
                b.writeVarInt(v.entityId());
                b.writeBoolean(v.hero());
                b.writeVarInt(v.loadout());
                b.writeVarInt(v.cape() + 1);
                b.writeBoolean(v.plume());
                b.writeBoolean(v.prompt());
                b.writeVarInt(Math.max(0, v.rearmTicks()));
                b.writeVarInt(v.cooldowns().length);
                for (int cd : v.cooldowns()) {
                    b.writeVarInt(Math.max(-1, cd) + 1);
                }
                b.writeVarInt(v.heldLoadout() + 1);
                b.writeUtf(v.feedback() == null ? "" : v.feedback(), 128);
            }
        };

        public Type<? extends CustomPacketPayload> type() { return TYPE; }
    }
}
