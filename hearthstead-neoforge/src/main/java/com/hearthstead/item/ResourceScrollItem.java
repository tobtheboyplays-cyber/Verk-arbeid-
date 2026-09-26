package com.hearthstead.item;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * The Resource Scroll (BUILDER lane; MineColonies' resource scroll): right-
 * click a Builder's Hut to link it, then right-click anywhere to see that
 * Builder's current site -- what it needs, what the hut holds, what is
 * missing and what is on the way -- without walking to the hut.
 */
public class ResourceScrollItem extends Item {

    public static final String HUT = "HearthsteadHut";
    public static final String BUILDER = "HearthsteadBuilder";

    public ResourceScrollItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!(context.getLevel() instanceof ServerLevel level) || context.getPlayer() == null) {
            return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
        }
        Settlement settlement = SettlementManager.at(level, context.getClickedPos());
        Building hut = null;
        if (settlement != null) {
            for (Building building : settlement.buildings) {
                if (building.valid && building.type == BuildingType.BUILDERS_HUT && building.bounds != null
                    && (building.bounds.isInside(context.getClickedPos())
                        || building.plaquePos.equals(context.getClickedPos()))) {
                    hut = building;
                }
            }
        }
        if (hut == null) {
            return InteractionResult.PASS; // not a hut: fall through to use()
        }
        CompoundTag tag = new CompoundTag();
        tag.putUUID(HUT, hut.id);
        if (!hut.workers.isEmpty()) {
            tag.putUUID(BUILDER, hut.workers.get(0));
        }
        context.getItemInHand().set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        context.getPlayer().displayClientMessage(Component.translatable("hearthstead.builder.scroll.linked")
            .withStyle(ChatFormatting.GOLD), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        UUID builder = linkedBuilder(stack);
        if (level.isClientSide) {
            com.hearthstead.client.ClientHooks.openBuilderSites(builder);
        } else if (builder == null) {
            player.displayClientMessage(Component.translatable("hearthstead.builder.scroll.unlinked"), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Nullable
    public static UUID linkedBuilder(ItemStack stack) {
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) {
            return null;
        }
        CompoundTag tag = data.copyTag();
        return tag.hasUUID(BUILDER) ? tag.getUUID(BUILDER) : null;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable(linkedBuilder(stack) == null ? "hearthstead.builder.scroll.tooltip.unlinked"
            : "hearthstead.builder.scroll.tooltip.linked").withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("hearthstead.builder.scroll.tooltip.use").withStyle(ChatFormatting.GRAY));
    }
}
