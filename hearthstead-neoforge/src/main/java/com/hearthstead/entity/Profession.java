package com.hearthstead.entity;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.function.Supplier;

public enum Profession {
    NONE(0, "none", () -> ItemStack.EMPTY, 0xC9B28A),
    FARMER(1, "farmer", () -> new ItemStack(Items.IRON_HOE), 0x5B7A50),
    LUMBERER(2, "lumberer", () -> new ItemStack(Items.IRON_AXE), 0x93494E),
    GUARD(3, "guard", () -> new ItemStack(Items.WOODEN_SWORD), 0x57575E),
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
    // A starter trade in both references. The pick is a request template,
    // never projected into the hand by assigning the profession.
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
    BREWER(20, "brewer", () -> ItemStack.EMPTY, 0x9C6B2F),
    // Owner's ask, 2026-08-25: an archer with abilities over time (ArcherRank
    // -- Power Shot, Triple Shot). The bow is required physical equipment,
    // not free profession projection. Forest green, distinct
    // from the guard's iron grey and the farmer/fletcher sage: the tower
    // archer is a woodland silhouette, not a wall one.
    ARCHER(21, "archer", () -> new ItemStack(Items.BOW), 0x2E5D34),
    // ARMOURY-3 (docs/project/PLAN_CIRCULATION.md, "still open,
    // MILITARY-OUT-adjacent"): Production.of(ARMOURY) gained eight real
    // recipes (ARMOURY-2) but nobody could be HIRED to run them --
    // Employment.tradeOf(ARMOURY) was Profession.NONE, so hire() refused
    // with no_trade and the recipes were reachable only from a GameTest
    // calling Production.run() directly. Same precedent MILLER/BREWER set
    // one slice earlier: a Production table without a hireable trade on it
    // is worse than no table (D-014). A crafting trade, so hands stay free
    // like every other one above -- the hammer at the anvil is what
    // identifies them, not an item at the hip.
    ARMOURER(22, "armourer", () -> ItemStack.EMPTY, 0x6E7A8A),
    // TRADES-1 (SURVIVAL_AUDIT F1 / PLAN_CIRCULATION "Input sources (Ring-1
    // completion)"): PASTURE, FISHERY and HUNTERS_LODGE have stood since
    // BuildingType was written with no trade that could ever staff them --
    // "the worker code was never written, not just unwired." These three
    // close that: the settlement's own wool, eggs, fish, hides and meat now
    // have a producer, instead of leaving the weaver, tanner and butcher to
    // live on whatever the player hands them directly.
    //
    // These values are request templates, not starting equipment. SHEARS,
    // FISHING_ROD and BOW must all enter the settlement as physical items.
    HERDER(23, "herder", () -> new ItemStack(Items.SHEARS), 0x8B9A6B),
    FISHER(24, "fisher", () -> new ItemStack(com.hearthstead.registry.ModItems.FISHERS_ROD.get()), 0x3E7C8A),
    HUNTER(25, "hunter", () -> new ItemStack(Items.BOW), 0x5C4A32),
    MAYOR(26, "mayor", () -> ItemStack.EMPTY, 0xB59A55),
    TRADER(27, "trader", () -> ItemStack.EMPTY, 0xB89643),
    // BATTLE-ROLES (plan/BATTLE-ROLES.md): four battlefield roles, each with
    // its own building (the building decides the trade, D-011) and its own
    // command key. Ids are APPENDED; never renumber (saves + sync + wire).
    // The spear/longsword are request templates exactly like the Guard's
    // sword: hiring never conjures one (EquipmentRequests).
    SPEARMAN(28, "spearman",
        () -> new ItemStack(com.hearthstead.registry.RoleItems.WOODEN_SPEAR.get()), 0x8C5A2B),
    LONGSWORDSMAN(29, "longswordsman",
        () -> new ItemStack(com.hearthstead.registry.RoleItems.IRON_LONGSWORD.get()), 0x4F6D8A),
    // Hands stay free for bandages; supplies ride in the offhand where they show.
    HEALER(30, "healer", () -> ItemStack.EMPTY, 0xC8BFA8),
    // Spells are rune charges, not a held item; rune stones ride in the offhand.
    RUNE_MAGE(31, "rune_mage", () -> ItemStack.EMPTY, 0x3A4FA0),
    // BUILDER lane (plan/BUILDER.md): raises what a player explicitly ordered
    // -- a placed blueprint, a drawn defense line, an Upgrade Order -- from
    // real delivered materials. Hands stay free: the plank carry and the
    // place-and-tap clips own them. Appended id; never renumber.
    BUILDER(32, "builder", () -> ItemStack.EMPTY, 0x9A6A3A);

    public static final Profession[] BY_ID = values();

    private final byte id;
    private final String key;
    /**
     * Template for the equipment this profession requests. Calling this never
     * equips a settler; the returned stack must come from real settlement or
     * player inventory through the request/delivery system.
     */
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

    public ItemStack requestedTool() {
        return tool.get();
    }

    /** @deprecated use {@link #requestedTool()}; this never equips anything. */
    @Deprecated(forRemoval = false)
    public ItemStack tool() {
        return requestedTool();
    }

    public int color() {
        return color;
    }

    public boolean employed() {
        return this != NONE && this != MAYOR;
    }

    /**
     * The trades that fight: they hold a night watch, they do not flee when
     * hurt, and they pick their own targets.
     *
     * <p>One predicate rather than a {@code == GUARD} test repeated across
     * five goals — which is exactly how the archer arrived panicking, sleeping
     * through its own watch and never acquiring a target (ARCHER-1's
     * out-of-ownership notes). A new martial trade joins here and every gate
     * follows.
     */
    public boolean martial() {
        return this == GUARD || this == ARCHER || this == SPEARMAN
            || this == LONGSWORDSMAN || this == RUNE_MAGE;
    }

    /**
     * Everyone who belongs on the battlefield during a raid: the martial
     * trades plus the Healer. A Healer stays awake, out of the panic shelter
     * and at work while a raid runs, but it is deliberately NOT martial: it
     * never picks targets and it flees when targeted (plan/BATTLE-ROLES.md).
     */
    public boolean battlefield() {
        return martial() || this == HEALER;
    }

    /** Melee trades that fight at the line (knights, spearmen, longswords). */
    public boolean frontline() {
        return this == GUARD || this == SPEARMAN || this == LONGSWORDSMAN;
    }

    public Component displayName() {
        return Component.translatable("hearthstead.profession." + key);
    }

    public static Profession byId(int id) {
        return id >= 0 && id < BY_ID.length ? BY_ID[id] : NONE;
    }
}
