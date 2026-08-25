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
    TANNER(15, "tanner", () -> ItemStack.EMPTY, 0x7A5230),
    // A starter trade in both references, and the one this roster was
    // missing. The pick stays in hand: it is what reads at distance.
    MINER(16, "miner", () -> new ItemStack(Items.IRON_PICKAXE), 0x62604F),
    // SLICE RECRUIT-1: the settlement's other half of A2's recruiting chain
    // (DESIGN.md system 8) -- somebody has to stand behind the bar for the
    // tavern to be more than an empty room. Hands stay free, same as
    // COURIER: hospitality is a manner, not a tool, and there is nothing to
    // hold that reads at a glance the way a hoe or an axe does.
    INNKEEPER(17, "innkeeper", () -> ItemStack.EMPTY, 0xC08A3E),
    // SLICE RESEARCH-1 (docs/project/PLAN_RESEARCH.md): the scholar's hands
    // stay free, the same as every crafting trade above -- the work
    // animation and the room they stand in are what identify them, and a
    // vanilla item at the hip would read as clutter rather than a trade.
    SCHOLAR(18, "scholar", () -> ItemStack.EMPTY, 0x3E5C8A),
    // Coordinator addendum, 2026-08-25: the mill and the brewery needed a
    // trade the moment Production (CHAINS) grew recipe tables for them --
    // CrafterWorkGoal already knows how to run any building with a recipe
    // table, it only needed somebody hireable to send there (D-014: a
    // recipe nobody can be hired to run is worse than no recipe at all).
    MILLER(19, "miller", () -> ItemStack.EMPTY, 0xD8CBA8),
    BREWER(20, "brewer", () -> ItemStack.EMPTY, 0x9C6B2F);

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
