package com.hearthstead.conversation.net;

import com.hearthstead.Hearthstead;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Client to server: one player intent in a conversation. The server trusts
 * none of it beyond "this player asked": session ownership, distance, reply
 * visibility, affordability and barter contents are all re-checked.
 *
 * <p>{@code revision} echoes the revision of the talk/barter state the
 * player acted on. A reply or an accept is honoured only for the current
 * revision, which the server consumes before it changes anything: a
 * double-click, a replayed packet or a click on a stale table does nothing.
 */
public record ConvActionPayload(int session, int kind, int revision, int index, String optionId,
                                List<Line> give, List<Line> take) implements CustomPacketPayload {
    public static final int CHOOSE = 0;
    public static final int LEAVE = 1;
    public static final int BARTER_ACCEPT = 2;
    public static final int BARTER_BACK = 3;
    public static final int MAX_LINES = 36;

    /** give: a main-inventory slot and count; take: an index into the partner's stock and count. */
    public record Line(int index, int count) {
    }

    public static ConvActionPayload choose(int session, int revision, int index, String optionId) {
        return new ConvActionPayload(session, CHOOSE, revision, index, optionId, List.of(), List.of());
    }

    public static ConvActionPayload accept(int session, int revision, List<Line> give, List<Line> take) {
        return new ConvActionPayload(session, BARTER_ACCEPT, revision, -1, "", give, take);
    }

    public static ConvActionPayload simple(int session, int kind) {
        return new ConvActionPayload(session, kind, 0, -1, "", List.of(), List.of());
    }

    public static final Type<ConvActionPayload> TYPE = new Type<>(Hearthstead.id("conv_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConvActionPayload> CODEC = new StreamCodec<>() {
        @Override
        public ConvActionPayload decode(RegistryFriendlyByteBuf buf) {
            int session = buf.readVarInt();
            int kind = buf.readVarInt();
            int revision = buf.readVarInt();
            int index = buf.readVarInt() - 1;
            String option = buf.readUtf(64);
            return new ConvActionPayload(session, kind, revision, index, option, lines(buf), lines(buf));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ConvActionPayload value) {
            buf.writeVarInt(value.session());
            buf.writeVarInt(value.kind());
            buf.writeVarInt(value.revision());
            buf.writeVarInt(value.index() + 1);
            buf.writeUtf(value.optionId() == null ? "" : value.optionId(), 64);
            write(buf, value.give());
            write(buf, value.take());
        }
    };

    private static List<Line> lines(RegistryFriendlyByteBuf buf) {
        int count = Math.min(MAX_LINES, buf.readVarInt());
        List<Line> out = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) out.add(new Line(buf.readVarInt(), buf.readVarInt()));
        return out;
    }

    private static void write(RegistryFriendlyByteBuf buf, List<Line> lines) {
        int count = Math.min(MAX_LINES, lines.size());
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            // Written as sent: the server validates (negative or oversized counts are refused there).
            buf.writeVarInt(lines.get(i).index());
            buf.writeVarInt(lines.get(i).count());
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
