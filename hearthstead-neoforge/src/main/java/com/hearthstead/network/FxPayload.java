package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import com.hearthstead.fx.FxEffect;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client, display only: play particle moment {@code effect} at
 * (x, y, z). ONE payload per moment; the client spawns every particle.
 *
 * @param entityId the entity the moment belongs to (a settler levelling up),
 *                 or -1; the client follows it when loaded, else uses the point
 * @param arg      effect-specific small number (a quality tier)
 * @param box      optional world box {minX, minY, minZ, maxX, maxY, maxZ}
 *                 (a room outline, a finished building), or empty
 */
public record FxPayload(int effect, double x, double y, double z, int entityId, int arg, int[] box)
        implements CustomPacketPayload {
    public static final int NO_ENTITY = -1;
    private static final int[] NO_BOX = new int[0];

    public FxPayload {
        box = box == null || box.length != 6 ? NO_BOX : box.clone();
    }

    public FxEffect effectType() {
        return FxEffect.byWireId(effect);
    }

    public boolean hasBox() {
        return box.length == 6;
    }

    public static final Type<FxPayload> TYPE = new Type<>(Hearthstead.id("fx"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FxPayload> CODEC = new StreamCodec<>() {
        public FxPayload decode(RegistryFriendlyByteBuf buffer) {
            int effect = buffer.readVarInt();
            double x = buffer.readDouble();
            double y = buffer.readDouble();
            double z = buffer.readDouble();
            int entity = buffer.readVarInt();
            int arg = buffer.readVarInt();
            int[] box = NO_BOX;
            if (buffer.readBoolean()) {
                box = new int[6];
                for (int i = 0; i < 6; i++) {
                    box[i] = buffer.readVarInt();
                }
            }
            return new FxPayload(effect, x, y, z, entity, arg, box);
        }

        public void encode(RegistryFriendlyByteBuf buffer, FxPayload value) {
            buffer.writeVarInt(value.effect());
            buffer.writeDouble(value.x());
            buffer.writeDouble(value.y());
            buffer.writeDouble(value.z());
            buffer.writeVarInt(value.entityId());
            buffer.writeVarInt(value.arg());
            buffer.writeBoolean(value.hasBox());
            if (value.hasBox()) {
                for (int v : value.box()) {
                    buffer.writeVarInt(v);
                }
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
