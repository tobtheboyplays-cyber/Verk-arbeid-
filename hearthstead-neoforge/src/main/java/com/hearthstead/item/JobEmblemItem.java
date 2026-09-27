package com.hearthstead.item;

import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Employment;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.SettlementManager;
import com.hearthstead.settlement.equipment.EquipmentRequirement;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

import javax.annotation.Nullable;
import java.util.List;

/**
 * A physical Mayor-issued job authorization.
 *
 * <p>This class deliberately exposes a tiny handoff API for Employment:
 * {@link #professionOf(ItemStack)} validates identity without mutation, and
 * {@link #consumeOneIfMatches(ItemStack, Profession)} performs the one final
 * shrink only after Employment and the plaque protocol have validated the
 * live settlement, settler, workplace, roster, capacity and snapshot revision.
 * Rejected actions therefore never consume an emblem.
 */
public final class JobEmblemItem extends Item {
    private final Profession profession;

    public JobEmblemItem(Profession profession, Properties properties) {
        super(properties);
        if (profession == null || profession == Profession.NONE) {
            throw new IllegalArgumentException("A job emblem needs a real profession");
        }
        this.profession = profession;
    }

    public Profession profession() {
        return profession;
    }

    @Nullable
    public static Profession professionOf(ItemStack stack) {
        return stack != null && stack.getItem() instanceof JobEmblemItem emblem
            ? emblem.profession : null;
    }

    public static boolean consumeOneIfMatches(ItemStack stack,
                                               Profession expectedProfession) {
        if (stack == null || stack.isEmpty()
            || professionOf(stack) != expectedProfession) {
            return false;
        }
        stack.shrink(1);
        return true;
    }

    public static ItemStack stackFor(Profession profession) {
        return switch (profession) {
            case LUMBERER -> new ItemStack(ModItems.LUMBERER_EMBLEM.get());
            case FARMER -> new ItemStack(ModItems.FARMER_EMBLEM.get());
            case FISHER -> new ItemStack(ModItems.FISHER_EMBLEM.get());
            case COURIER -> new ItemStack(ModItems.COURIER_EMBLEM.get());
            case INNKEEPER -> new ItemStack(ModItems.INNKEEPER_EMBLEM.get());
            case TRADER -> new ItemStack(ModItems.TRADER_EMBLEM.get());
            case GUARD -> new ItemStack(ModItems.GUARD_EMBLEM.get());
            case ARCHER -> new ItemStack(ModItems.ARCHER_EMBLEM.get());
            case HUNTER -> new ItemStack(ModItems.HUNTER_EMBLEM.get());
            case SAWYER -> new ItemStack(ModItems.SAWYER_EMBLEM.get());
            case SCHOLAR -> new ItemStack(ModItems.SCHOLAR_EMBLEM.get());
            case SPEARMAN -> new ItemStack(com.hearthstead.registry.RoleItems.SPEARMAN_EMBLEM.get());
            case LONGSWORDSMAN -> new ItemStack(com.hearthstead.registry.RoleItems.LONGSWORDSMAN_EMBLEM.get());
            case HEALER -> new ItemStack(com.hearthstead.registry.RoleItems.HEALER_EMBLEM.get());
            case RUNE_MAGE -> new ItemStack(com.hearthstead.registry.RoleItems.RUNE_MAGE_EMBLEM.get());
            case BUILDER -> new ItemStack(ModItems.BUILDER_EMBLEM.get());
            // Trades-unlock lane: the 15 extended trades (empty for any other).
            default -> com.hearthstead.registry.TradeEmblemItems.stackFor(profession);
        };
    }

    /**
     * Give the emblem to the person, not to a plaque. Employment resolves the
     * nearest active compatible workplace and consumes this exact visible stack
     * only after the whole assignment commits.
     */
    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                   LivingEntity target,
                                                   InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || player.isShiftKeyDown()
            || !(target instanceof SettlerEntity settler)) {
            return InteractionResult.PASS;
        }
        if (target.level().isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }
        if (!(player instanceof ServerPlayer serverPlayer)
            || !(target.level() instanceof ServerLevel level)) {
            return InteractionResult.FAIL;
        }

        Settlement settlement = SettlementManager.byId(level, settler.getSettlementId());
        if (settlement == null) {
            player.displayClientMessage(Component.translatable(
                "hearthstead.employ.refused.not_member"), true);
            return InteractionResult.sidedSuccess(false);
        }

        Employment.AutoHired result = Employment.autoHireWithHeldEmblem(
            level, settlement, settler, serverPlayer);
        if (!result.ok() || result.workplace() == null) {
            if (result.refusal() != null) {
                // Playtest 27 Sep #4: a refusal must be readable, not a
                // one-second action-bar flash. Chat, plus the settler sheet's
                // "Right now" line for the next minute.
                player.displayClientMessage(result.refusal(), false);
                settler.noteWorkRefusal(result.refusal());
            }
            return InteractionResult.sidedSuccess(false);
        }

        EquipmentRequirement requirement =
            com.hearthstead.settlement.equipment.EquipmentRequests
                .requirementFor(profession);
        Component message;
        if (requirement == null) {
            message = Component.translatable("hearthstead.employ.auto.success",
                settler.getDisplayName(), profession.displayName(),
                result.workplace().type.displayName());
        } else {
            message = Component.translatable("hearthstead.employ.auto.success_needs",
                settler.getDisplayName(), profession.displayName(),
                result.workplace().type.displayName(),
                new ItemStack(requirement.preferredItem()).getHoverName());
        }
        player.displayClientMessage(message, false);
        if (result.cost().loses() != null) {
            player.displayClientMessage(result.cost().sentence(), false);
        }
        return InteractionResult.sidedSuccess(false);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable("item.hearthstead.job_emblem." + profession.key());
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.hearthstead.job_emblem.role",
            profession.displayName()).withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("item.hearthstead.job_emblem.use")
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.hearthstead.job_emblem.safe")
            .withStyle(ChatFormatting.DARK_GRAY));
    }
}
