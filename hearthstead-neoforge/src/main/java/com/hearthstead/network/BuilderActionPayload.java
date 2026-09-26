package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.UUID;

/**
 * Every Builder's Plan request from the client (BUILDER lane). One payload,
 * an action and its arguments; the server re-derives everything that matters
 * (settlement, unlocks, stock, the world) and never trusts a count from here.
 */
public record BuilderActionPayload(Action action, String text, BlockPos a, BlockPos b,
                                   int rotation, boolean mirror, int number, boolean flag,
                                   UUID id) implements CustomPacketPayload {

    public enum Action {
        /** Ask for the catalog, upgrade list and sites. */
        CATALOG,
        /** Ask for a blueprint's cells (text = blueprint id) for the ghost. */
        PREVIEW,
        /** Validate a placement (text, a = origin, rotation, mirror). */
        VALIDATE,
        /** Commit a placement (flag = allow overwrite). */
        PLACE,
        /** Validate a line (text = kind, a, b, number = gate offset, flag = gate). */
        VALIDATE_LINE,
        /** Commit a line. */
        PLACE_LINE,
        /** A Sites-tab action (id = job, number = SiteAction ordinal). */
        SITE,
        /** Validate an Upgrade Order (id = building). */
        VALIDATE_UPGRADE,
        /** Commit an Upgrade Order (id = building). */
        ORDER_UPGRADE,
        /** Survey Rod: save the box a..b as a player design named {@code text}. */
        SAVE_DESIGN,
        /** Remove a player design (text = design id). */
        DELETE_DESIGN,
        /** Builder settings (number = pickup mode ordinal, flag unused; text = fill mode). */
        SETTINGS,
        /** Validate / order deconstruction of a registered building (id = building; flag = commit). */
        DECONSTRUCT;

        static Action byOrdinal(int ordinal) {
            Action[] values = values();
            return ordinal >= 0 && ordinal < values.length ? values[ordinal] : CATALOG;
        }
    }

    public static final UUID NONE = new UUID(0L, 0L);

    public static final Type<BuilderActionPayload> TYPE = new Type<>(Hearthstead.id("builder_action"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BuilderActionPayload> CODEC =
        StreamCodec.of(BuilderActionPayload::write, BuilderActionPayload::read);

    public BuilderActionPayload {
        action = action == null ? Action.CATALOG : action;
        text = text == null ? "" : text.length() > 128 ? text.substring(0, 128) : text;
        a = a == null ? BlockPos.ZERO : a.immutable();
        b = b == null ? BlockPos.ZERO : b.immutable();
        id = id == null ? NONE : id;
    }

    public static BuilderActionPayload simple(Action action) {
        return new BuilderActionPayload(action, "", BlockPos.ZERO, BlockPos.ZERO, 0, false, 0, false, NONE);
    }

    private static void write(RegistryFriendlyByteBuf buf, BuilderActionPayload p) {
        buf.writeVarInt(p.action.ordinal());
        buf.writeUtf(p.text, 128);
        BlockPos.STREAM_CODEC.encode(buf, p.a);
        BlockPos.STREAM_CODEC.encode(buf, p.b);
        buf.writeVarInt(p.rotation);
        buf.writeBoolean(p.mirror);
        buf.writeVarInt(p.number);
        buf.writeBoolean(p.flag);
        UUIDUtil.STREAM_CODEC.encode(buf, p.id);
    }

    private static BuilderActionPayload read(RegistryFriendlyByteBuf buf) {
        return new BuilderActionPayload(Action.byOrdinal(buf.readVarInt()), buf.readUtf(128),
            BlockPos.STREAM_CODEC.decode(buf), BlockPos.STREAM_CODEC.decode(buf),
            buf.readVarInt(), buf.readBoolean(), buf.readVarInt(), buf.readBoolean(),
            UUIDUtil.STREAM_CODEC.decode(buf));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
