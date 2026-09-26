package com.hearthstead.network;

import com.hearthstead.Hearthstead;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: the nearby settlement's soldier roster and live field
 * orders, for the command HUD chips, preview dot counts, active-slot dots and
 * the order icon over each soldier. Bounded; sent only to players within
 * command reach of that settlement.
 */
public record FieldOrderStatePayload(int revision, List<RosterEntry> roster,
                                     List<GroupLine> groups, List<SlotEntry> slots)
    implements CustomPacketPayload {
    public static final int MAX_ROSTER = 256;
    public static final int MAX_NAME = 48;
    public static final int MAX_GROUPS = 8;

    /** One commandable, ready soldier of the nearby settlement and its role wire id. */
    public record RosterEntry(int entityId, int group) {
    }

    /** Latest order summary for one role. */
    public record GroupLine(int group, int kind, String issuer, String target, int heard, int total,
                            int unreachable, boolean holdFire, BlockPos center, int octant,
                            int enemyEntityId, int orderId) {
    }

    /** One soldier's assigned slot (or enemy anchor) under a live order. */
    public record SlotEntry(int entityId, int group, int kind, BlockPos slot, boolean reachable,
                            boolean holdFire) {
    }

    public static final Type<FieldOrderStatePayload> TYPE = new Type<>(Hearthstead.id("field_order_state"));
    public static final StreamCodec<RegistryFriendlyByteBuf, FieldOrderStatePayload> CODEC = StreamCodec.of(
        FieldOrderStatePayload::write, FieldOrderStatePayload::read);

    private static void write(RegistryFriendlyByteBuf buf, FieldOrderStatePayload p) {
        buf.writeVarInt(p.revision);
        int rosterSize = Math.min(p.roster.size(), MAX_ROSTER);
        buf.writeVarInt(rosterSize);
        for (int i = 0; i < rosterSize; i++) {
            buf.writeVarInt(p.roster.get(i).entityId());
            buf.writeByte(p.roster.get(i).group());
        }
        buf.writeVarInt(Math.min(p.groups.size(), MAX_GROUPS));
        for (int i = 0; i < Math.min(p.groups.size(), MAX_GROUPS); i++) {
            GroupLine g = p.groups.get(i);
            buf.writeVarInt(g.group);
            buf.writeVarInt(g.kind);
            buf.writeUtf(clip(g.issuer), MAX_NAME);
            buf.writeUtf(clip(g.target), MAX_NAME);
            buf.writeVarInt(g.heard);
            buf.writeVarInt(g.total);
            buf.writeVarInt(g.unreachable);
            buf.writeBoolean(g.holdFire);
            buf.writeBlockPos(g.center);
            buf.writeByte(g.octant);
            buf.writeVarInt(g.enemyEntityId + 1);
            buf.writeVarInt(g.orderId);
        }
        int slots = Math.min(p.slots.size(), MAX_ROSTER);
        buf.writeVarInt(slots);
        for (int i = 0; i < slots; i++) {
            SlotEntry s = p.slots.get(i);
            buf.writeVarInt(s.entityId);
            buf.writeByte(s.group);
            buf.writeByte(s.kind);
            buf.writeBlockPos(s.slot);
            buf.writeBoolean(s.reachable);
            buf.writeBoolean(s.holdFire);
        }
    }

    private static FieldOrderStatePayload read(RegistryFriendlyByteBuf buf) {
        int revision = buf.readVarInt();
        int rosterSize = buf.readVarInt();
        if (rosterSize < 0 || rosterSize > MAX_ROSTER) throw new IllegalArgumentException("Too many field roster ids");
        List<RosterEntry> roster = new ArrayList<>(rosterSize);
        for (int i = 0; i < rosterSize; i++) roster.add(new RosterEntry(buf.readVarInt(), buf.readByte()));
        int groupCount = buf.readVarInt();
        if (groupCount < 0 || groupCount > MAX_GROUPS) throw new IllegalArgumentException("Too many field groups");
        List<GroupLine> groups = new ArrayList<>(groupCount);
        for (int i = 0; i < groupCount; i++) {
            groups.add(new GroupLine(buf.readVarInt(), buf.readVarInt(), buf.readUtf(MAX_NAME),
                buf.readUtf(MAX_NAME), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean(), buf.readBlockPos(), buf.readByte(), buf.readVarInt() - 1,
                buf.readVarInt()));
        }
        int slotCount = buf.readVarInt();
        if (slotCount < 0 || slotCount > MAX_ROSTER) throw new IllegalArgumentException("Too many field slots");
        List<SlotEntry> slots = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            slots.add(new SlotEntry(buf.readVarInt(), buf.readByte(), buf.readByte(), buf.readBlockPos(),
                buf.readBoolean(), buf.readBoolean()));
        }
        return new FieldOrderStatePayload(revision, roster, groups, slots);
    }

    private static String clip(String value) {
        if (value == null) return "";
        return value.length() > MAX_NAME ? value.substring(0, MAX_NAME) : value;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
