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
 * Server to client: open or update the conversation bar. Display only; the
 * client echoes back the session id, the reply index and its id, and the
 * server re-validates everything.
 *
 * @param flourish 0 none, 1 persuasion succeeded, 2 persuasion failed (dice/seal flourish)
 * @param mode 0 plain talk, 1 encounter (intro cinematic + name card first)
 * @param style 0 eye-level framing, 1 low heroic angle
 * @param revision what a reply must echo (one answer per state; replays are ignored)
 */
public record ConvStatePayload(int session, int npcId, Component name, Component title, int relation,
                               Component memory, Component record, List<Component> lines,
                               List<OptionView> options, int flourish, int flourishChance,
                               int mode, int style, int revision) implements CustomPacketPayload {
    public static final int MAX_LINES = 8;

    public record CostView(ItemStack icon, int amount, int have) {
    }

    /** One numbered reply as the player sees it. {@code chance} is -1 without a check. */
    public record OptionView(String id, Component text, int chance, List<CostView> costs,
                             boolean enabled, Component reason, boolean barter) {
    }

    public static final Type<ConvStatePayload> TYPE = new Type<>(Hearthstead.id("conv_state"));

    private static final StreamCodec<RegistryFriendlyByteBuf, Component> TEXT = ComponentSerialization.TRUSTED_STREAM_CODEC;

    public static final StreamCodec<RegistryFriendlyByteBuf, ConvStatePayload> CODEC = new StreamCodec<>() {
        @Override
        public ConvStatePayload decode(RegistryFriendlyByteBuf buf) {
            int session = buf.readVarInt();
            int npc = buf.readVarInt();
            Component name = TEXT.decode(buf);
            Component title = TEXT.decode(buf);
            int relation = buf.readVarInt();
            Component memory = TEXT.decode(buf);
            Component record = TEXT.decode(buf);
            int lineCount = Math.min(MAX_LINES, buf.readVarInt());
            List<Component> lines = new ArrayList<>(lineCount);
            for (int i = 0; i < lineCount; i++) lines.add(TEXT.decode(buf));
            int optionCount = Math.min(9, buf.readVarInt());
            List<OptionView> options = new ArrayList<>(optionCount);
            for (int i = 0; i < optionCount; i++) {
                String id = buf.readUtf(64);
                Component text = TEXT.decode(buf);
                int chance = buf.readVarInt() - 1;
                int costCount = Math.min(4, buf.readVarInt());
                List<CostView> costs = new ArrayList<>(costCount);
                for (int c = 0; c < costCount; c++) {
                    costs.add(new CostView(ItemStack.OPTIONAL_STREAM_CODEC.decode(buf), buf.readVarInt(), buf.readVarInt()));
                }
                boolean enabled = buf.readBoolean();
                Component reason = TEXT.decode(buf);
                boolean barter = buf.readBoolean();
                options.add(new OptionView(id, text, chance, costs, enabled, reason, barter));
            }
            return new ConvStatePayload(session, npc, name, title, relation, memory, record, lines, options,
                buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buf, ConvStatePayload value) {
            buf.writeVarInt(value.session());
            buf.writeVarInt(value.npcId());
            TEXT.encode(buf, value.name());
            TEXT.encode(buf, value.title());
            buf.writeVarInt(value.relation());
            TEXT.encode(buf, value.memory());
            TEXT.encode(buf, value.record());
            int lineCount = Math.min(MAX_LINES, value.lines().size());
            buf.writeVarInt(lineCount);
            for (int i = 0; i < lineCount; i++) TEXT.encode(buf, value.lines().get(i));
            int optionCount = Math.min(9, value.options().size());
            buf.writeVarInt(optionCount);
            for (int i = 0; i < optionCount; i++) {
                OptionView option = value.options().get(i);
                buf.writeUtf(option.id(), 64);
                TEXT.encode(buf, option.text());
                buf.writeVarInt(option.chance() + 1);
                int costCount = Math.min(4, option.costs().size());
                buf.writeVarInt(costCount);
                for (int c = 0; c < costCount; c++) {
                    CostView cost = option.costs().get(c);
                    ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, cost.icon());
                    buf.writeVarInt(Math.max(0, cost.amount()));
                    buf.writeVarInt(Math.max(0, cost.have()));
                }
                buf.writeBoolean(option.enabled());
                TEXT.encode(buf, option.reason());
                buf.writeBoolean(option.barter());
            }
            buf.writeVarInt(value.flourish());
            buf.writeVarInt(value.flourishChance());
            buf.writeVarInt(value.mode());
            buf.writeVarInt(value.style());
            buf.writeVarInt(value.revision());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
