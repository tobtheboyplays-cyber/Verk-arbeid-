package com.hearthstead.heraldry;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Wire messages of the Banner designer. */
public final class BannerDesignPayloads {
    private BannerDesignPayloads() {
    }

    /**
     * Client to server. {@link #OPEN}: "open the designer for this Banner"
     * (the Banner screen's Heraldry button). {@link #CONFIRM}: "fly this
     * design" and, when {@code name} is not empty, "call the kingdom this".
     * {@link #CLOSE}: the designer was closed without saving.
     */
    public record Action(BlockPos pos, int action, VillageDesign design, String name) implements CustomPacketPayload {
        public static final int OPEN = 0;
        public static final int CONFIRM = 1;
        public static final int CLOSE = 2;
        public static final Type<Action> TYPE = new Type<>(Hearthstead.id("banner_design_action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Action> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeByte(p.action);
                VillageDesign.STREAM_CODEC.encode(buf, p.design);
                buf.writeUtf(p.name, 64);
            },
            buf -> new Action(buf.readBlockPos(), buf.readByte(), VillageDesign.STREAM_CODEC.decode(buf),
                buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * Server to client: open the designer on {@code design} ({@link #PLACED}
     * right after placing the Banner, {@link #EDIT} from the Heraldry
     * button), or {@link #REFRESH} an open designer because {@code changedBy}
     * just raised new colours on the same Banner. {@code name} is the
     * kingdom's current name, empty while the Banner has not founded one.
     */
    public record Open(BlockPos pos, int mode, VillageDesign design, String changedBy, String name)
        implements CustomPacketPayload {
        public static final int PLACED = 0;
        public static final int EDIT = 1;
        public static final int REFRESH = 2;
        public static final Type<Open> TYPE = new Type<>(Hearthstead.id("banner_designer_open"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBlockPos(p.pos);
                buf.writeByte(p.mode);
                VillageDesign.STREAM_CODEC.encode(buf, p.design);
                buf.writeUtf(p.changedBy, 64);
                buf.writeUtf(p.name, 64);
            },
            buf -> new Open(buf.readBlockPos(), buf.readByte(), VillageDesign.STREAM_CODEC.decode(buf),
                buf.readUtf(64), buf.readUtf(64)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
