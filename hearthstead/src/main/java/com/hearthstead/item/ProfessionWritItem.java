package com.hearthstead.item;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A reusable writ of trade. Use on a settler to appoint them; sneak-use to
 * relieve them of duty. The writ is never consumed.
 */
public class ProfessionWritItem extends Item {
    private final Profession profession;

    public ProfessionWritItem(Profession profession, Properties properties) {
        super(properties);
        this.profession = profession;
    }

    public Profession getProfession() {
        return profession;
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                  LivingEntity target, InteractionHand hand) {
        if (!(target instanceof SettlerEntity settler)) {
            return InteractionResult.PASS;
        }
        if (player.level().isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!settler.isBound()) {
            player.displayClientMessage(
                Component.translatable("hearthstead.message.writ_unbound"), true);
            return InteractionResult.CONSUME;
        }
        if (player.isSecondaryUseActive()) {
            if (settler.getProfession() != Profession.NONE) {
                settler.assignProfession(Profession.NONE);
                player.displayClientMessage(Component.translatable(
                    "hearthstead.message.writ_cleared", settler.getSettlerName()), true);
            }
            return InteractionResult.CONSUME;
        }
        if (settler.getProfession() == profession) {
            player.displayClientMessage(Component.translatable(
                "hearthstead.message.writ_already", settler.getSettlerName(),
                profession.displayName()), true);
            return InteractionResult.CONSUME;
        }
        settler.assignProfession(profession);
        player.displayClientMessage(Component.translatable(
            "hearthstead.message.writ_assigned", settler.getSettlerName(),
            profession.displayName()), true);
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hearthstead.writ.tooltip1",
            profession.displayName()).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.hearthstead.writ.tooltip2")
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.hearthstead.writ.tooltip3")
            .withStyle(ChatFormatting.DARK_GRAY));
    }
}
