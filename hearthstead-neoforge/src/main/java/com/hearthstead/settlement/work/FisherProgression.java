package com.hearthstead.settlement.work;

import com.hearthstead.registry.ModComponents;
import com.hearthstead.registry.ModItems;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

/** Learned bait techniques improve cadence without creating tools or spending imaginary bait. */
public final class FisherProgression {
    private FisherProgression() {}
    public static int tier(int dexterity) {
        return dexterity >= 90 ? 4 : dexterity >= 75 ? 3 : dexterity >= 50 ? 2 : dexterity >= 25 ? 1 : 0;
    }
    public static int cycleTicks(int dexterity) {
        return switch (tier(dexterity)) { case 1 -> 260; case 2 -> 220; case 3 -> 180; case 4 -> 160; default -> 300; };
    }
    public static String technique(int dexterity) {
        return switch (tier(dexterity)) { case 1 -> "bait_selection"; case 2 -> "depth_reading";
            case 3 -> "precise_casting"; case 4 -> "master_angling"; default -> "simple_casting"; };
    }
    public static ItemStack rollCatch(RandomSource random, int dexterity) {
        boolean perch = random.nextFloat() < .65F;
        int roll = random.nextInt(10000);
        int quality = quality(dexterity,roll);
        ItemStack fish = new ItemStack(switch (quality) {
            case GoodsQuality.BASIC -> perch ? ModItems.RIVER_PERCH.get() : ModItems.BROWN_TROUT.get();
            case GoodsQuality.FINE -> ModItems.BROWN_TROUT.get();
            case GoodsQuality.SUPERIOR -> ModItems.SILVER_PIKE.get();
            default -> ModItems.GOLDEN_CHAR.get();
        });
        if (quality > GoodsQuality.BASIC) fish.set(ModComponents.GOODS_QUALITY.get(),quality);
        return fish;
    }
    public static int quality(int dexterity, int roll) {
        int tier = tier(dexterity);
        if (tier >= 4 && roll < 5) return GoodsQuality.LEGENDARY;
        if (tier >= 3 && roll < 25) return GoodsQuality.MASTERWORK;
        if (tier >= 2 && roll < 100) return GoodsQuality.EXCEPTIONAL;
        if (tier >= 1 && roll < 400) return GoodsQuality.SUPERIOR;
        return roll < 1500 ? GoodsQuality.FINE : GoodsQuality.BASIC;
    }
}
