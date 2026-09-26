package com.hearthstead.settlement.work;

import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModComponents;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Employment;
import com.mojang.serialization.Codec;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import java.util.UUID;

/** Product quality only; no village identity or transfer-driven upgrades. */
public final class GoodsQuality {
    public static final int BASIC = 0, FINE = 1, SUPERIOR = 2, EXCEPTIONAL = 3,
        MASTERWORK = 4, LEGENDARY = 5, HIGHEST = LEGENDARY;
    public static final Codec<Integer> CODEC = Codec.intRange(FINE, HIGHEST);
    public static final StreamCodec<ByteBuf, Integer> STREAM_CODEC =
        ByteBufCodecs.VAR_INT.map(GoodsQuality::validated, GoodsQuality::validated);
    private GoodsQuality() {}

    private static int validated(int value) {
        if (value < FINE || value > HIGHEST) throw new IllegalArgumentException("Invalid goods quality");
        return value;
    }

    /** Missing or manually malformed components never satisfy a quality order. */
    public static int of(ItemStack stack) {
        Integer value = stack.get(ModComponents.GOODS_QUALITY.get());
        return value == null || value < FINE || value > HIGHEST ? BASIC : value;
    }

    public static boolean meets(ItemStack stack, int minimum) {
        return !stack.isEmpty() && minimum >= BASIC && minimum <= HIGHEST && of(stack) >= minimum;
    }

    static int forWork(SettlerEntity worker, Building workplace, UUID action, BlockPos source,
                       net.minecraft.resources.ResourceLocation paidTool) {
        // The operation's persisted durability receipt names the tool that did
        // the work, including its final breaking use; a later hand swap cannot upgrade it.
        ItemStack tool = new ItemStack(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(paidTool));
        boolean highTool = tool.is(Items.DIAMOND_AXE) || tool.is(Items.NETHERITE_AXE)
            || tool.is(Items.DIAMOND_HOE) || tool.is(Items.NETHERITE_HOE);
        boolean netheriteTool = tool.is(Items.NETHERITE_AXE) || tool.is(Items.NETHERITE_HOE);
        boolean ironOrBetter = highTool || tool.is(Items.IRON_AXE) || tool.is(Items.IRON_HOE);
        int skill = worker.attribute(Employment.trainedBy(workplace.type));
        long stable = action.getMostSignificantBits() ^ Long.rotateLeft(action.getLeastSignificantBits(), 23)
            ^ source.asLong();
        stable ^= stable >>> 33;
        stable *= 0xff51afd7ed558ccdL;
        stable ^= stable >>> 33;
        return determine(skill, ironOrBetter, highTool, netheriteTool, workplace.level, stable);
    }

    static int determine(int skill, boolean ironOrBetter, boolean highTool, int workplaceLevel, long roll) {
        return determine(skill, ironOrBetter, highTool, false, workplaceLevel, roll);
    }

    static int determine(int skill, boolean ironOrBetter, boolean highTool, boolean netheriteTool,
                         int workplaceLevel, long roll) {
        if (skill < 20 || !ironOrBetter) return FINE;
        // Building levels are persisted but do not yet have a player-facing
        // upgrade route. Require the real, valid workplace context (level 1+)
        // rather than making the best grades unreachable behind a dead gate.
        if (skill >= 40 && netheriteTool && workplaceLevel >= 1
                && Long.remainderUnsigned(roll, 100) == 0) return LEGENDARY;
        if (skill >= 35 && highTool && workplaceLevel >= 1
                && Long.remainderUnsigned(roll, 20) == 0) return MASTERWORK;
        if (skill >= 30 && highTool && workplaceLevel >= 1
                && Long.remainderUnsigned(roll, 20) == 0) return EXCEPTIONAL;
        return SUPERIOR;
    }

    /** Number of physical units required for one Coin at each quality tier. */
    public static int priceDivisor(int quality) {
        return switch (quality) {
            case BASIC -> 1;
            case FINE -> 2;
            case SUPERIOR -> 3;
            case EXCEPTIONAL -> 4;
            case MASTERWORK -> 5;
            case LEGENDARY -> 6;
            default -> throw new IllegalArgumentException("Unknown sale quality");
        };
    }
}
