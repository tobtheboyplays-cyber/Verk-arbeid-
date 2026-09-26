package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import com.hearthstead.ambient.AmbientCue;
import com.hearthstead.ambient.BarkPicker;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client (living village): settler {@code entityId} plays a short
 * body cue and/or says one bark line. Display only; the client never trusts
 * it for anything but a few seconds of presentation. {@code barkKey} is empty
 * for a cue without a line and otherwise a {@code hearthstead.bark.*} lang
 * key; {@code arg} is its one optional argument (a player or settler name).
 */
public record AmbientCuePayload(int entityId, int cue, String barkKey, String arg)
        implements CustomPacketPayload {
    public static final int MAX_KEY = 96;
    public static final int MAX_ARG = 64;

    public AmbientCuePayload {
        barkKey = barkKey == null ? "" : barkKey;
        arg = arg == null ? "" : arg;
        if (barkKey.length() > MAX_KEY || (!barkKey.isEmpty() && !barkKey.startsWith(BarkPicker.PREFIX))) {
            barkKey = "";
        }
        if (arg.length() > MAX_ARG) {
            arg = arg.substring(0, MAX_ARG);
        }
        if (AmbientCue.byId(cue) == AmbientCue.NONE) {
            cue = AmbientCue.NONE.ordinal();
        }
    }

    public AmbientCue cueType() {
        return AmbientCue.byId(cue);
    }

    public static final Type<AmbientCuePayload> TYPE = new Type<>(Hearthstead.id("ambient_cue"));
    public static final StreamCodec<RegistryFriendlyByteBuf, AmbientCuePayload> CODEC = new StreamCodec<>() {
        public AmbientCuePayload decode(RegistryFriendlyByteBuf buffer) {
            int entityId = buffer.readVarInt();
            int cue = buffer.readByte();
            String key = buffer.readUtf(MAX_KEY);
            String arg = buffer.readUtf(MAX_ARG);
            return new AmbientCuePayload(entityId, cue, key, arg);
        }

        public void encode(RegistryFriendlyByteBuf buffer, AmbientCuePayload value) {
            buffer.writeVarInt(value.entityId());
            buffer.writeByte(value.cue());
            buffer.writeUtf(value.barkKey(), MAX_KEY);
            buffer.writeUtf(value.arg(), MAX_ARG);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
