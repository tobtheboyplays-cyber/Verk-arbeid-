package com.hearthstead.item;

import com.hearthstead.block.PlaqueItemData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import java.util.List;

/**
 * A slip of paper stamped for one building type (D-006). One item, six
 * dedications, carried the same way the plaque used to carry its own type:
 * the {@code BUILDING_TYPE} data component, read through
 * {@link PlaqueItemData}.
 *
 * <p>Fitting one into a blank plaque is what gives that plaque a type and
 * starts it surveying (W4); the plaque itself never stores a type of its own
 * until a plan says what it is.
 */
public class BuildPlanItem extends Item {

    public BuildPlanItem(Properties properties) {
        super(properties);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("hearthstead.build_plan.name",
            PlaqueItemData.buildingType(stack).displayName());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hearthstead.build_plan.tooltip")
            .withStyle(ChatFormatting.GRAY));
    }
}
