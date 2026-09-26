package com.hearthstead.block;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BannerPattern;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.entity.BannerPatterns;

import javax.annotation.Nullable;

/**
 * The colours a settlement Banner flies. The design is always a real vanilla
 * banner item held by the block entity, so hanging new colours is an exact
 * item exchange: the adopted banner goes in and the previous one comes out.
 * An empty holder means the settlement flies Bannerhold's own colours, which
 * were never an item and so are never handed out.
 */
public final class SettlementHeraldry {
    /** Bannerhold's own colours: a red field with a gold bordure and a gold flower charge. */
    public static final DyeColor DEFAULT_BASE = DyeColor.RED;

    private SettlementHeraldry() {
    }

    public static boolean isBanner(ItemStack stack) {
        return !stack.isEmpty() && stack.getItem() instanceof BannerItem;
    }

    public static DyeColor baseColor(ItemStack heraldry) {
        return heraldry.getItem() instanceof BannerItem banner ? banner.getColor() : DEFAULT_BASE;
    }

    public static BannerPatternLayers patterns(ItemStack heraldry,
                                               @Nullable HolderLookup.Provider registries) {
        if (isBanner(heraldry)) {
            return heraldry.getOrDefault(DataComponents.BANNER_PATTERNS, BannerPatternLayers.EMPTY);
        }
        return defaultPatterns(registries);
    }

    /**
     * The founding design. Every layer is a vanilla pattern so the same look
     * can be reproduced on a loom; patterns missing from a datapack are skipped.
     */
    public static BannerPatternLayers defaultPatterns(@Nullable HolderLookup.Provider registries) {
        if (registries == null) {
            return BannerPatternLayers.EMPTY;
        }
        HolderGetter<BannerPattern> lookup = registries.lookupOrThrow(Registries.BANNER_PATTERN);
        return new BannerPatternLayers.Builder()
            .addIfRegistered(lookup, BannerPatterns.BORDER, DyeColor.YELLOW)
            .addIfRegistered(lookup, BannerPatterns.FLOWER, DyeColor.YELLOW)
            .build();
    }

    /** True when {@code candidate} would fly exactly what {@code current} already flies. */
    public static boolean sameDesign(ItemStack current, ItemStack candidate) {
        if (!isBanner(current)) {
            return false;
        }
        return ItemStack.isSameItemSameComponents(current, candidate);
    }
}
