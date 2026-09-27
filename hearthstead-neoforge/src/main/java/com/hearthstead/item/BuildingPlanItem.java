package com.hearthstead.item;

import com.hearthstead.building.BuildingType;
import com.hearthstead.registry.ModComponents;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A crafted plan for one building (owner, 26 Sep: "craft the building from
 * the recipe you learned, pick one of the 5 styles, place it", MineColonies
 * hut-item style). The building type rides on the shared
 * {@code hearthstead:building_type} component. Right-click opens the style
 * picker; the placement then goes through the Builder's normal validate /
 * confirm / PLACE order, and the server consumes one plan when that order is
 * accepted ({@link com.hearthstead.network.BuildingPlanOrders}).
 *
 * <p>Different from the plaque's {@link BuildPlanItem}: that one dedicates a
 * hand-built plaque; this one orders the Builder to raise the building.
 */
public class BuildingPlanItem extends Item {

    public BuildingPlanItem(Properties properties) {
        super(properties);
    }

    /** The building this plan orders, or null when the stack carries no (or an unknown) type. */
    @Nullable
    public static BuildingType typeOf(ItemStack stack) {
        String id = stack.get(ModComponents.BUILDING_TYPE.get());
        return id == null ? null : BuildingPlans.byId(id);
    }

    public static ItemStack of(BuildingType type) {
        ItemStack stack = new ItemStack(com.hearthstead.registry.ModItems.BUILDING_PLAN.get());
        stack.set(ModComponents.BUILDING_TYPE.get(), type.id());
        return stack;
    }

    @Override
    public Component getName(ItemStack stack) {
        BuildingType type = typeOf(stack);
        if (type == null) {
            return super.getName(stack);
        }
        return Component.translatable("item.hearthstead.building_plan.named", type.displayName());
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        BuildingType type = typeOf(stack);
        if (type == null) {
            return InteractionResultHolder.pass(stack);
        }
        if (level.isClientSide) {
            if (hand != InteractionHand.MAIN_HAND) {
                player.displayClientMessage(Component.translatable("hearthstead.building_plan.main_hand"), true);
            } else {
                com.hearthstead.client.ClientHooks.openBuildingPlan(type.id());
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        BuildingType type = typeOf(stack);
        if (type != null) {
            tooltip.add(Component.translatable("item.hearthstead.building_plan.tooltip.1", type.displayName())
                .withStyle(ChatFormatting.GOLD));
        }
        tooltip.add(Component.translatable("item.hearthstead.building_plan.tooltip.2")
            .withStyle(ChatFormatting.GRAY));
    }
}
