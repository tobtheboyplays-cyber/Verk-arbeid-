package com.hearthstead.conversation.net;

import com.hearthstead.Hearthstead;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.item.ItemStack;

/**
 * Server to client: open or refresh the barter table. {@code theirs} is the
 * partner's stock, {@code mine} the player's 36 main inventory slots; each
 * with a copper value per item so the client can draw the satisfaction bar.
 * The server recomputes everything on accept.
 *
 * @param result 0 fresh table, 1 deal done, 2 refused (inventories changed / not enough)
 * @param revision what an accept must echo; each accept uses it up (no double deals)
 */
public record ConvBarterPayload(int session, int npcId, Component name, int relation, int askPercent,
                                List<ItemStack> theirs, List<Integer> theirValues,
                                List<ItemStack> mine, List<Integer> mineValues, int result, int revision)
    implements CustomPacketPayload {
    public static final int MAX_THEIRS = 27;
    public static final int MINE_SLOTS = 36;

    public static final Type<ConvBarterPayload> TYPE = new Type<>(Hearthstead.id("conv_barter"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ConvBarterPayload> CODEC = new StreamCodec<>() {
        @Override
        public ConvBarterPayload decode(RegistryFriendlyByteBuf buf) {
            int session = buf.readVarInt();
            int npc = buf.readVarInt();
            Component name = ComponentSerialization.TRUSTED_STREAM_CODEC.decode(buf);
            int relation = buf.readVarInt();
            int ask = buf.readVarInt();
            List<ItemStack> theirs = new ArrayList<>();
            List<Integer> theirValues = new ArrayList<>();
            readStacks(buf, MAX_THEIRS, theirs, theirValues);
            List<ItemStack> mine = new ArrayList<>();
            List<Integer> mineValues = new ArrayList<>();
            readStacks(buf, MINE_SLOTS, mine, mineValues);
            int result = buf.readVarInt();
            return new ConvBarterPayload(session, npc, name, relation, ask, theirs, theirValues, mine, mineValues,
                result, buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ConvBarterPayload value) {
            buf.writeVarInt(value.session());
            buf.writeVarInt(value.npcId());
            ComponentSerialization.TRUSTED_STREAM_CODEC.encode(buf, value.name());
            buf.writeVarInt(value.relation());
            buf.writeVarInt(value.askPercent());
            writeStacks(buf, MAX_THEIRS, value.theirs(), value.theirValues());
            writeStacks(buf, MINE_SLOTS, value.mine(), value.mineValues());
            buf.writeVarInt(value.result());
            buf.writeVarInt(value.revision());
        }
    };

    private static void readStacks(RegistryFriendlyByteBuf buf, int max, List<ItemStack> stacks, List<Integer> values) {
        int count = Math.min(max, buf.readVarInt());
        for (int i = 0; i < count; i++) {
            ItemStack stack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
            int size = buf.readVarInt();
            stacks.add(stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(Math.max(1, size)));
            values.add(buf.readVarInt());
        }
    }

    private static void writeStacks(RegistryFriendlyByteBuf buf, int max, List<ItemStack> stacks, List<Integer> values) {
        int count = Math.min(max, stacks.size());
        buf.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            ItemStack stack = stacks.get(i);
            // Counts can exceed a stack's max size in a peddler's bag: send separately.
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1));
            buf.writeVarInt(stack.isEmpty() ? 0 : stack.getCount());
            buf.writeVarInt(i < values.size() ? Math.max(0, values.get(i)) : 0);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
