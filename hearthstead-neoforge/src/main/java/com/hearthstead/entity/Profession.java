package com.hearthstead.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Supplier;

public enum Profession {
    NONE(0, "none", () -> ItemStack.EMPTY, 0xC9B28A),
    FARMER(1, "farmer", () -> new ItemStack(Items.IRON_HOE), 0x5B7A50),
    LUMBERER(2, "lumberer", () -> new ItemStack(Items.IRON_AXE), 0x93494E),
    GUARD(3, "guard", () -> new ItemStack(Items.IRON_SWORD), 0x57575E),
    // A2a: hands stay free for crates -- the carry animations own them.
    COURIER(4, "courier", () -> ItemStack.EMPTY, 0x8A6D3B),
    // CHAINS-1. Crafters keep their hands free: the work animation is what
    // identifies them, together with the room they stand in, and a vanilla
    // item held at the hip reads as clutter rather than as a trade.
    BAKER(5, "baker", () -> ItemStack.EMPTY, 0xC9A227),
    COOK(6, "cook", () -> ItemStack.EMPTY, 0xB5651D),
    BUTCHER(7, "butcher", () -> ItemStack.EMPTY, 0x8A3A35),
    SMELTER(8, "smelter", () -> ItemStack.EMPTY, 0x6B4A2F),
    SMITH(9, "smith", () -> ItemStack.EMPTY, 0x57575E),
    SAWYER(10, "sawyer", () -> ItemStack.EMPTY, 0x7A5C33),
    CARPENTER(11, "carpenter", () -> ItemStack.EMPTY, 0x8F6B3D),
    MASON(12, "mason", () -> ItemStack.EMPTY, 0x75715F),
    FLETCHER(13, "fletcher", () -> ItemStack.EMPTY, 0x5B7A50),
    WEAVER(14, "weaver", () -> ItemStack.EMPTY, 0xA8A294),
    TANNER(15, "tanner", () -> ItemStack.EMPTY, 0x7A5230);

    public static final Profession[] BY_ID = values();

    private final byte id;
    private final String key;
    private final Supplier<ItemStack> tool;
    private final int color;

    Profession(int id, String key, Supplier<ItemStack> tool, int color) {
        this.id = (byte) id;
        this.key = key;
        this.tool = tool;
        this.color = color;
    }

    public byte id() {
        return id;
    }

    public String key() {
        return key;
    }

    public ItemStack tool() {
        return tool.get();
    }

    public int color() {
        return color;
    }

    public boolean employed() {
        return this != NONE;
    }

    public Component displayName() {
        return Component.translatable("hearthstead.profession." + key);
    }

    public static Profession byId(int id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : NONE;
    }
}
