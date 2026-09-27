package com.hearthstead.item;

import com.hearthstead.block.PlaqueBlockEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.Building;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.state.BlessingQuality;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.component.CustomData;
import java.util.Optional;
import com.hearthstead.settlement.state.TargetBlessingState;
import com.hearthstead.util.AuthorityTelemetry;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;

import java.util.List;
import java.util.Objects;

/**
 * A physical raid reward that binds one permanent Blessing to one settler or
 * one registered building.
 *
 * <p>The target owns all validation and persistence. This item treats
 * {@link TargetBlessingState.ApplyResult#APPLIED} as the sole authority to
 * consume a seal; a full or invalid target can never destroy the reward.
 */
public final class BlessingSealItem extends Item {
    private final BlessingId blessing;

    public BlessingSealItem(BlessingId blessing, Properties properties) {
        super(properties);
        this.blessing = Objects.requireNonNull(blessing, "blessing");
    }

    public BlessingId blessing() {
        return blessing;
    }

    /**
     * Canonical Blessing-to-item mapping for reward delivery. Keeping it here
     * prevents the Hearth screen and future loot sources from duplicating a
     * switch whose cases can drift from the registered item instances.
     */
    public static ItemStack stackFor(BlessingId blessing) {
        return new ItemStack(switch (Objects.requireNonNull(blessing, "blessing")) {
            case WARDEN_OATH -> ModItems.WARDEN_OATH_SEAL.get();
            case HEARTHWARD -> ModItems.HEARTHWARD_SEAL.get();
            case THORNED_ROADS -> ModItems.THORNED_ROADS_SEAL.get();
        });
    }

    private static final String QUALITY_TAG = "hearthstead:blessing_quality";

    /** Common remains byte-for-byte compatible with existing one-rank seals. */
    public static ItemStack stackFor(BlessingId blessing, BlessingQuality quality) {
        ItemStack stack = stackFor(blessing);
        if (Objects.requireNonNull(quality, "quality") == BlessingQuality.RARE) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, data -> {
                CompoundTag tag = new CompoundTag();
                tag.putInt("Version", 1);
                tag.putInt("RankUnits", quality.rankUnits());
                data.put(QUALITY_TAG, tag);
            });
        }
        return stack;
    }

    /** Missing means legacy Common; present malformed data is never downgraded. */
    public static Optional<BlessingQuality> qualityOf(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlessingSealItem)) return Optional.empty();
        CompoundTag data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!data.contains(QUALITY_TAG)) return Optional.of(BlessingQuality.COMMON);
        if (!(data.get(QUALITY_TAG) instanceof CompoundTag tag)
                || !tag.contains("Version", Tag.TAG_INT) || tag.getInt("Version") != 1
                || !tag.contains("RankUnits", Tag.TAG_INT)) return Optional.empty();
        return BlessingQuality.fromRankUnits(tag.getInt("RankUnits"));
    }

    @Override
    public InteractionResult interactLivingEntity(ItemStack stack, Player player,
                                                   LivingEntity target,
                                                   InteractionHand hand) {
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (!(target instanceof SettlerEntity settler)) {
            if (!target.level().isClientSide) {
                player.displayClientMessage(Component.translatable(
                    "item.hearthstead.blessing_seal.invalid_target"), true);
            }
            return InteractionResult.sidedSuccess(target.level().isClientSide);
        }
        return bindToSettler(stack, player, settler);
    }

    /**
     * Definitive world-target dispatch shared with SettlerEntity's main-hand
     * pre-pass. This keeps a delivered offhand seal usable even when the main
     * item would otherwise consume the interaction first.
     */
    public InteractionResult bindToSettler(ItemStack stack, Player player,
                                           SettlerEntity settler) {
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }
        if (settler.level().isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }
        int rankBefore = settler.blessingRank(blessing);
        int sealsBefore = stack.getCount();
        TargetBlessingState.ApplyResult result = qualityOf(stack)
            .filter(quality -> stack.getItem() == this)
            .map(quality -> settler.applyBlessing(blessing, quality.rankUnits()))
            .orElse(TargetBlessingState.ApplyResult.INVALID);
        finishUse(stack, player, result, settler.getDisplayName());
        if (settler.level() instanceof ServerLevel level) {
            int expectedDelta = result == TargetBlessingState.ApplyResult.APPLIED
                && !player.getAbilities().instabuild ? -1 : 0;
            AuthorityTelemetry.emit(level,
                result == TargetBlessingState.ApplyResult.APPLIED
                    ? AuthorityTelemetry.Event.BLESSING_BOUND_SETTLER
                    : AuthorityTelemetry.Event.AUTHORITY_REJECTED,
                result == TargetBlessingState.ApplyResult.APPLIED
                    ? AuthorityTelemetry.Result.COMMITTED
                    : AuthorityTelemetry.Result.REJECTED,
                AuthorityTelemetry.Fields.items(settler.getSettlementId(),
                    "settler:" + settler.getUUID(), rankBefore,
                    settler.blessingRank(blessing), rankBefore,
                    settler.blessingRank(blessing),
                    BuiltInRegistries.ITEM.getKey(stackFor(blessing).getItem())
                        .toString(),
                    sealsBefore, stack.getCount(), expectedDelta,
                    blessing.id() + "_" + result.name().toLowerCase(
                        java.util.Locale.ROOT)));
        }
        return InteractionResult.sidedSuccess(false);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Player player = context.getPlayer();
        if (player == null || !player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        BlockPos pos = context.getClickedPos();
        if (!(context.getLevel().getBlockEntity(pos) instanceof PlaqueBlockEntity plaque)) {
            return InteractionResult.PASS;
        }
        return bindToPlaque(context.getItemInHand(), player, plaque);
    }

    /** Same conservation boundary for a direct offhand plaque pre-pass. */
    public InteractionResult bindToPlaque(ItemStack stack, Player player,
                                         PlaqueBlockEntity plaque) {
        if (!player.isShiftKeyDown() || plaque.getLevel() == null) {
            return InteractionResult.PASS;
        }
        if (plaque.getLevel().isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }
        ServerLevel level = (ServerLevel) plaque.getLevel();
        Settlement settlement = plaque.settlementFor(level);
        Building building = plaque.building(level);
        int rankBefore = plaque.blessingRank(blessing);
        int sealsBefore = stack.getCount();
        TargetBlessingState.ApplyResult result = qualityOf(stack)
            .filter(quality -> stack.getItem() == this)
            .map(quality -> plaque.applyBlessing(blessing, quality.rankUnits()))
            .orElse(TargetBlessingState.ApplyResult.INVALID);
        finishUse(stack, player, result, plaque.type().displayName());
        int expectedDelta = result == TargetBlessingState.ApplyResult.APPLIED
            && !player.getAbilities().instabuild ? -1 : 0;
        AuthorityTelemetry.emit(level,
            result == TargetBlessingState.ApplyResult.APPLIED
                ? AuthorityTelemetry.Event.BLESSING_BOUND_BUILDING
                : AuthorityTelemetry.Event.AUTHORITY_REJECTED,
            result == TargetBlessingState.ApplyResult.APPLIED
                ? AuthorityTelemetry.Result.COMMITTED
                : AuthorityTelemetry.Result.REJECTED,
            AuthorityTelemetry.Fields.items(
                settlement == null ? null : settlement.id,
                building == null ? "plaque:" + plaque.getBlockPos().asLong()
                    : "building:" + building.id,
                rankBefore, plaque.blessingRank(blessing), rankBefore,
                plaque.blessingRank(blessing),
                BuiltInRegistries.ITEM.getKey(stackFor(blessing).getItem())
                    .toString(),
                sealsBefore, stack.getCount(), expectedDelta,
                blessing.id() + "_" + result.name().toLowerCase(
                    java.util.Locale.ROOT)));
        return InteractionResult.sidedSuccess(false);
    }

    private void finishUse(ItemStack stack, Player player,
                           TargetBlessingState.ApplyResult result,
                           Component targetName) {
        switch (result) {
            case APPLIED -> {
                if (!player.getAbilities().instabuild) {
                    stack.shrink(1);
                }
                player.displayClientMessage(Component.translatable(
                    "item.hearthstead.blessing_seal.applied", blessingName(), targetName), true);
            }
            case MAXED -> player.displayClientMessage(Component.translatable(
                "item.hearthstead.blessing_seal.maxed", targetName, blessingName()), true);
            case INSUFFICIENT_CAPACITY -> player.displayClientMessage(Component.translatable(
                "item.hearthstead.blessing_seal.insufficient_capacity", targetName,
                qualityOf(stack).map(BlessingQuality::rankUnits).orElse(0)), true);
            case INVALID -> player.displayClientMessage(Component.translatable(
                "item.hearthstead.blessing_seal.invalid"), true);
        }
    }

    private Component blessingName() {
        return Component.translatable("hearthstead.blessing." + blessing.id() + ".name");
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context,
                                List<Component> tooltip, TooltipFlag flag) {
        Optional<BlessingQuality> quality = qualityOf(stack);
        tooltip.add(Component.translatable(quality.isEmpty()
                ? "item.hearthstead.blessing_seal.quality.invalid"
                : quality.get() == BlessingQuality.RARE
                    ? "item.hearthstead.blessing_seal.quality.rare"
                    : "item.hearthstead.blessing_seal.quality.common")
            .withStyle(quality.isEmpty() ? ChatFormatting.RED
                : quality.get() == BlessingQuality.RARE ? ChatFormatting.LIGHT_PURPLE : ChatFormatting.GRAY));
        tooltip.add(Component.translatable(
                "item.hearthstead.blessing_seal.effect." + blessing.id())
            .withStyle(ChatFormatting.LIGHT_PURPLE));
        tooltip.add(Component.translatable("item.hearthstead.blessing_seal.tooltip")
            .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("item.hearthstead.blessing_seal.permanent")
            .withStyle(ChatFormatting.DARK_PURPLE));
    }
}
