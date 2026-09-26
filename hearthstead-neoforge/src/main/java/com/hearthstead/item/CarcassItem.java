package com.hearthstead.item;

import com.hearthstead.registry.ModComponents;
import com.hearthstead.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A hunted animal's body. One per stack: it is carried on a Hunter's
 * shoulders, laid on a Butchering Table and worked into its recorded yield.
 * A dropped carcass never despawns -- the loot it replaced must never vanish.
 */
public final class CarcassItem extends Item {
    public CarcassItem(Properties properties) {
        super(properties);
    }

    public static ItemStack create(CarcassData data) {
        ItemStack stack = new ItemStack(ModItems.CARCASS.get());
        stack.set(ModComponents.CARCASS.get(), data);
        return stack;
    }

    @Nullable
    public static CarcassData data(ItemStack stack) {
        return stack == null || !stack.is(ModItems.CARCASS.get()) ? null
            : stack.get(ModComponents.CARCASS.get());
    }

    public static boolean isCarcass(ItemStack stack) {
        return data(stack) != null;
    }

    @Override
    public Component getName(ItemStack stack) {
        CarcassData data = data(stack);
        EntityType<?> type = data == null ? null : data.type().orElse(null);
        return type == null ? super.getName(stack)
            : Component.translatable("item.hearthstead.carcass.of", type.getDescription());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        CarcassData data = data(stack);
        if (data == null) {
            return;
        }
        tooltip.add(Component.translatable("item.hearthstead.carcass.tooltip")
            .withStyle(ChatFormatting.GRAY));
        for (ItemStack yield : data.yield()) {
            tooltip.add(Component.translatable("item.hearthstead.carcass.yield",
                yield.getCount(), yield.getHoverName()).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    @Override
    public int getEntityLifespan(ItemStack stack, Level level) {
        return Integer.MAX_VALUE;
    }
}
