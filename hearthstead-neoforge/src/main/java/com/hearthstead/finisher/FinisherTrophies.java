package com.hearthstead.finisher;

import com.hearthstead.entity.RaiderEntity;
import net.minecraft.ChatFormatting;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * A captain executed by a finisher leaves a trophy: its war banner (black
 * field, red saltire and border, a wall display piece), and on a co-op double
 * its weapon too. Named after the captain, so a nemesis captain's banner on a
 * hall wall tells its own story.
 */
public final class FinisherTrophies {
    private FinisherTrophies() {
    }

    public static void drop(ServerLevel level, RaiderEntity captain, @Nullable Player executor,
                            boolean doubleExecution) {
        Component name = captain.getDisplayName().copy();
        spawn(level, captain, banner(level, name, executor));
        if (doubleExecution) {
            ItemStack weapon = new ItemStack(captain.variant() == RaiderEntity.Variant.BRUTE
                ? Items.STONE_AXE : Items.IRON_AXE);
            weapon.set(DataComponents.CUSTOM_NAME, Component.translatable(
                "item.hearthstead.finisher_trophy_weapon", name).withStyle(ChatFormatting.GOLD));
            weapon.set(DataComponents.LORE, lore(executor));
            weapon.set(DataComponents.RARITY, Rarity.EPIC);
            spawn(level, captain, weapon);
        }
    }

    public static ItemStack banner(ServerLevel level, Component captainName, @Nullable Player executor) {
        ItemStack stack = new ItemStack(Items.BLACK_BANNER);
        BannerPatternLayers.Builder layers = new BannerPatternLayers.Builder();
        add(level, layers, BannerPatterns.CROSS, DyeColor.RED);
        add(level, layers, BannerPatterns.BORDER, DyeColor.RED);
        add(level, layers, BannerPatterns.CIRCLE_MIDDLE, DyeColor.BLACK);
        stack.set(DataComponents.BANNER_PATTERNS, layers.build());
        stack.set(DataComponents.CUSTOM_NAME, Component.translatable(
            "item.hearthstead.finisher_trophy_banner", captainName).withStyle(ChatFormatting.GOLD));
        stack.set(DataComponents.LORE, lore(executor));
        stack.set(DataComponents.RARITY, Rarity.EPIC);
        return stack;
    }

    private static void add(ServerLevel level, BannerPatternLayers.Builder layers,
                            ResourceKey<BannerPattern> key, DyeColor color) {
        level.registryAccess().registry(Registries.BANNER_PATTERN)
            .flatMap(registry -> registry.getHolder(key))
            .ifPresent(holder -> layers.add((Holder<BannerPattern>) holder, color));
    }

    private static ItemLore lore(@Nullable Player executor) {
        List<Component> lines = new ArrayList<>();
        lines.add(executor == null
            ? Component.translatable("item.hearthstead.finisher_trophy.lore_guard")
                .withStyle(ChatFormatting.GRAY)
            : Component.translatable("item.hearthstead.finisher_trophy.lore", executor.getName())
                .withStyle(ChatFormatting.GRAY));
        return new ItemLore(lines);
    }

    private static void spawn(ServerLevel level, RaiderEntity at, ItemStack stack) {
        ItemEntity item = new ItemEntity(level, at.getX(), at.getY() + 0.6D, at.getZ(), stack);
        item.setDefaultPickUpDelay();
        item.setDeltaMovement(level.random.nextGaussian() * 0.04D, 0.22D,
            level.random.nextGaussian() * 0.04D);
        item.setUnlimitedLifetime();
        level.addFreshEntity(item);
    }
}
