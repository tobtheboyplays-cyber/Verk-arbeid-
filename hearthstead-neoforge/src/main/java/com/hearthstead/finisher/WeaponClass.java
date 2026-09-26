package com.hearthstead.finisher;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.DiggerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MaceItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TridentItem;

/**
 * The four weapon families an execution is choreographed for. The executor's
 * main hand decides; the choreography never changes the item.
 */
public enum WeaponClass {
    SWORD,
    AXE,
    /** Maces, clubs, hammers and other blunt tools (pickaxes and shovels swing like clubs). */
    MACE,
    BARE;

    public static WeaponClass of(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return BARE;
        }
        if (stack.getItem() instanceof SwordItem || stack.getItem() instanceof TridentItem) {
            return SWORD;
        }
        if (stack.getItem() instanceof AxeItem) {
            return AXE;
        }
        if (stack.getItem() instanceof MaceItem || stack.getItem() instanceof DiggerItem) {
            return MACE;
        }
        return ofItemId(BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath());
    }

    /** Name-based fallback for modded weapons without a vanilla weapon class. */
    public static WeaponClass ofItemId(String path) {
        if (path == null) {
            return BARE;
        }
        String p = path.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("pickaxe")) {
            return MACE;
        }
        if (p.contains("sword") || p.contains("sabre") || p.contains("saber") || p.contains("dagger")
            || p.contains("spear") || p.contains("rapier") || p.contains("blade")
            || p.contains("knife") || p.contains("glaive")) {
            return SWORD;
        }
        if (p.contains("axe") || p.contains("hatchet") || p.contains("halberd")) {
            return AXE;
        }
        if (p.contains("mace") || p.contains("club") || p.contains("hammer") || p.contains("maul")
            || p.contains("flail") || p.contains("cudgel")) {
            return MACE;
        }
        return BARE;
    }
}
