package com.hearthstead.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Supplier;

public enum Profession {
    NONE(0, "none", () -> ItemStack.EMPTY, 0xC9B28A),
    FARMER(1, "farmer", () -> new ItemStack(Items.IRON_HOE), 0x5B7A50),
    LUMBERER(2, "lumberer", () -> new ItemStack(Items.IRON_AXE), 0x93494E),
    GUARD(3, "guard", () -> new ItemStack(Items.IRON_SWORD), 0x57575E);

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
