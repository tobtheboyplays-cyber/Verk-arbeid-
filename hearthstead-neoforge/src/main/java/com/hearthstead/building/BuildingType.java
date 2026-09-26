package com.hearthstead.building;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * The kinds of building a plaque can declare, and what each one demands of
 * the room around it.
 *
 * <p>A plaque is the surveyor: it is bought from the architect already
 * dedicated to one of these types, and when placed it scans the room it hangs
 * in and measures it against that type's requirements. Nothing else declares
 * what a building is.
 *
 * <p>Requirement costs are sized against vanilla effort. A house asks for a
 * bed, a door and a lantern — an evening's work. A warehouse asks for real
 * storage and floor space. A lumber camp asks for the tools of the trade. The
 * intent is that each rung feels earned without becoming a chore.
 */
public enum BuildingType {

    HOUSE("house", 4, 1, Items.RED_BED,
        Requirement.beds(1),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(9)),

    LODGING("lodging", 8, 0, Items.WHITE_BED,
        Requirement.beds(4),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(24)),

    WAREHOUSE("warehouse", 0, 2, Items.CHEST,
        Requirement.blocks("storage", 4, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(25)),

    ARCHITECTS_STUDY("architects_study", 0, 1, Items.LECTERN,
        Requirement.blocks("lectern", 1, Blocks.LECTERN),
        Requirement.blocks("bookshelf", 2, Blocks.BOOKSHELF, Blocks.CHISELED_BOOKSHELF),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(16)),

    // workerCapacity 0, deliberately, for SCHOOL / INFIRMARY / MARKET below.
    // All three used to declare worker slots (1 / 1 / 2), which made
    // employsWorkers() true and put a Hire affordance on their plaque -- but
    // none of the three is in Employment.TRADES, so tradeOf() is NONE and
    // every hire was refused with `no_trade`. The refusal was honest; the
    // offer was not. A building that advertises a post nobody can ever fill
    // teaches the player that the plaque's promises are decorative, and the
    // plaque is the one surveyor this whole design rests on. Restore the
    // number the day the matching trade exists -- that is the only thing
    // that has to change back.
    SCHOOL("school", 0, 0, Items.WRITABLE_BOOK,
        Requirement.blocks("bookshelf", 4, Blocks.BOOKSHELF, Blocks.CHISELED_BOOKSHELF),
        Requirement.blocks("lectern", 2, Blocks.LECTERN),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(25)),

    FARMHOUSE("farmhouse", 0, 2, Items.WHEAT,
        Requirement.blocks("composter", 1, Blocks.COMPOSTER),
        Requirement.blocks("storage", 1, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(16)),

    MILL("mill", 0, 1, Items.HAY_BLOCK,
        Requirement.blocks("grindstone", 1, Blocks.GRINDSTONE),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(16)),

    BAKERY("bakery", 0, 2, Items.BREAD,
        Requirement.blocks("oven", 2, Blocks.FURNACE, Blocks.SMOKER),
        Requirement.blocks("storage", 1, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(16)),

    KITCHEN("kitchen", 0, 2, Items.COOKED_BEEF,
        Requirement.blocks("oven", 1, Blocks.FURNACE, Blocks.SMOKER),
        Requirement.blocks("cauldron", 1, Blocks.CAULDRON, Blocks.WATER_CAULDRON),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(20)),

    DINING_HALL("dining_hall", 0, 1, Items.CAKE,
        Requirement.blocks("hearth_fire", 1, Blocks.CAMPFIRE, Blocks.SOUL_CAMPFIRE),
        Requirement.blocks("storage", 1, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(3),
        Requirement.floorSpace(36)),

    PASTURE("pasture", 0, 2, Items.WHITE_WOOL,
        Requirement.blocks("hay", 2, Blocks.HAY_BLOCK),
        Requirement.blocks("storage", 1, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(25)),

    BUTCHER("butcher", 0, 1, Items.PORKCHOP,
        Requirement.blocks("smoker", 1, Blocks.SMOKER),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(16)),

    FISHERY("fishery", 0, 1, Items.FISHING_ROD,
        Requirement.blocks("fishing_water", 20, Blocks.WATER),
        new Requirement("fishing_chair", 1, r -> r.blockCounts().getOrDefault(com.hearthstead.registry.ModBlocks.FISHERS_CHAIR.get(), 0)),
        new Requirement("fish_rack", 1, r -> r.blockCounts().getOrDefault(com.hearthstead.registry.ModBlocks.FISH_RACK.get(), 0)),
        Requirement.blocks("rod_barrel", 1, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(16)),

    HUNTERS_LODGE("hunters_lodge", 0, 2, Items.BOW,
        Requirement.blocks("fletching", 1, Blocks.FLETCHING_TABLE),
        Requirement.blocks("storage", 1, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(16)),

    BREWERY("brewery", 0, 1, Items.BARREL,
        Requirement.blocks("brewing_stand", 1, Blocks.BREWING_STAND),
        Requirement.blocks("cauldron", 1, Blocks.CAULDRON, Blocks.WATER_CAULDRON),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(16)),

    TAVERN("tavern", 0, 2, Items.BELL,
        Requirement.blocks("bell", 1, Blocks.BELL),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.aleTap(1),
        Requirement.doors(1),
        Requirement.lights(3),
        Requirement.floorSpace(36)),

    WELL("well", 0, 0, Items.BUCKET,
        Requirement.blocks("water", 4, Blocks.WATER),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(9)),

    LUMBER_CAMP("lumber_camp", 0, 2, Items.IRON_AXE,
        Requirement.blocks("workbench", 1, Blocks.CRAFTING_TABLE),
        Requirement.blocks("storage", 1, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(16)),

    SAWMILL("sawmill", 0, 2, Items.OAK_PLANKS,
        Requirement.blocks("sawbench", 1, Blocks.STONECUTTER),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(20)),

    CARPENTER("carpenter", 0, 2, Items.CRAFTING_TABLE,
        Requirement.blocks("workbench", 2, Blocks.CRAFTING_TABLE),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(20)),

    MINE("mine", 0, 3, Items.IRON_PICKAXE,
        Requirement.blocks("ladder", 3, Blocks.LADDER),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(3),
        Requirement.floorSpace(20)),

    SMELTER("smelter", 0, 2, Items.FURNACE,
        Requirement.blocks("forge", 2, Blocks.BLAST_FURNACE, Blocks.FURNACE),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(20)),

    SMITHY("smithy", 0, 2, Items.ANVIL,
        Requirement.blocks("anvil", 1, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL),
        Requirement.blocks("smithing_table", 1, Blocks.SMITHING_TABLE),
        Requirement.blocks("forge", 1, Blocks.BLAST_FURNACE, Blocks.FURNACE),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(20)),

    WEAVER("weaver", 0, 2, Items.LOOM,
        Requirement.blocks("loom", 1, Blocks.LOOM),
        Requirement.blocks("cauldron", 1, Blocks.CAULDRON, Blocks.WATER_CAULDRON),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(16)),

    // SURVIVAL_AUDIT.md F3: a brewing_stand here gated a core-loop building
    // behind an undocumented Nether trip (vanilla's own recipe needs a
    // blaze rod). Coordinator's decision: the infirmary loses that gate. A
    // cauldron reads as the healer's fixture instead -- vanilla's own witch
    // hut already pairs a cauldron with herbal/potion work, so the room
    // still looks and feels like a hearthside infirmary, and the "cauldron"
    // requirement id is the same vocabulary KITCHEN/BREWERY/WEAVER already
    // use, so no new lang key is needed.
    // BATTLE-ROLES: the Healer is the Infirmary's trade now, so the post
    // it advertises is real (the note above says to restore the number
    // "the day the matching trade exists").
    INFIRMARY("infirmary", 0, 2, Items.GOLDEN_APPLE,
        Requirement.blocks("cauldron", 1, Blocks.CAULDRON, Blocks.WATER_CAULDRON),
        Requirement.beds(2),
        Requirement.blocks("storage", 1, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(20)),

    BARRACKS("barracks", 0, 4, Items.IRON_SWORD,
        Requirement.beds(2),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(36)),

    WATCHTOWER("watchtower", 0, 2, Items.SPYGLASS,
        Requirement.blocks("ladder", 4, Blocks.LADDER),
        Requirement.blocks("storage", 1, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(4),
        Requirement.floorSpace(9)),

    TANNERY("tannery", 0, 1, Items.LEATHER,
        Requirement.blocks("cauldron", 2, Blocks.CAULDRON, Blocks.WATER_CAULDRON),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(1),
        Requirement.floorSpace(16)),

    FLETCHER("fletcher", 0, 2, Items.ARROW,
        Requirement.blocks("fletching", 1, Blocks.FLETCHING_TABLE),
        Requirement.blocks("workbench", 1, Blocks.CRAFTING_TABLE),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(16)),

    // JOB 2 (SURVIVAL_AUDIT.md F6): SMITHY and ARMOURY each demanded their
    // own anvil -- 31 iron ingots apiece, vanilla's own recipe (3 iron
    // blocks + 4 ingots), BOTH rooms hand-mined before a single settler
    // industry exists to help. Softened here, ARMOURY only: it trades its
    // own anvil for a smithing_table -- 2 iron ingots + 4 planks, roughly a
    // fifteenth of the cost, reachable the same afternoon as the plaque
    // itself. This is not "the armoury goes free": SMITHY keeps its own
    // full anvil+forge+smithing_table below, unchanged, so the FIRST anvil
    // a village ever needs is still real and still expensive -- only the
    // SECOND one, which bought nothing but a duplicate hammering surface,
    // is gone. The room still reads as a forge annex rather than a bare
    // workbench: a smithing table is where vanilla 1.21 itself does armor
    // and tool UPGRADES (netherite, trims) -- it is the armourer's own
    // finishing bench, working leather and ingots the smithy already
    // forged or the tannery already tanned, not raw ore -- and the
    // armourer's own hammering motion (WORK_HAMMER, reused from the
    // smithy's hammer-at-anvil clip per ArmouryGameTests) still plays over
    // it, so the room keeps the sound and the sight of a forge even though
    // the block requirement no longer demands a second one.
    ARMOURY("armoury", 0, 2, Items.IRON_CHESTPLATE,
        Requirement.blocks("smithing_table", 1, Blocks.SMITHING_TABLE),
        Requirement.blocks("storage", 4, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(25)),

    MASON("mason", 0, 2, Items.BRICKS,
        Requirement.blocks("dressed_stone", 8, Blocks.STONE_BRICKS, Blocks.CHISELED_STONE_BRICKS, Blocks.SMOOTH_STONE),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(20)),

    LIBRARY("library", 0, 1, Items.BOOKSHELF,
        Requirement.blocks("bookshelf", 8, Blocks.BOOKSHELF, Blocks.CHISELED_BOOKSHELF),
        Requirement.blocks("lectern", 1, Blocks.LECTERN),
        Requirement.doors(1),
        Requirement.lights(3),
        Requirement.floorSpace(25)),

    MARKET("market", 0, 0, Items.EMERALD,
        Requirement.blocks("stall", 4, Blocks.BARREL, Blocks.SCAFFOLDING),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(36)),

    TRADING_POST("trading_post", 0, 1, Items.GOLD_NUGGET,
        // A counter is scaffolding (bamboo) OR a cartography table (paper +
        // planks): bamboo only grows in jungles, so a scaffolding-only counter
        // was a seed-dependent wall in front of the Trader (survival audit 2026-09-25).
        Requirement.blocks("counter", 1, Blocks.SCAFFOLDING, Blocks.CARTOGRAPHY_TABLE),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1), Requirement.lights(1), Requirement.floorSpace(16)),

    // BUILDER lane (plan/BUILDER.md): the bootstrap. Buildable by hand the
    // first evening -- a workbench, two chests the couriers deliver building
    // materials into, a door and a light -- and then it can build the rest.
    // No hut levels (owner, 26 Sep): the tech tree decides what the Builder
    // may raise, the checklist decides what any building's level is.
    BUILDERS_HUT("builders_hut", 0, 1, Items.SCAFFOLDING,
        Requirement.blocks("workbench", 1, Blocks.CRAFTING_TABLE),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1), Requirement.lights(1), Requirement.floorSpace(16)),

    // BATTLE-ROLES (plan/BATTLE-ROLES.md): the building decides the trade
    // (D-011), so each battlefield role gets its own hall. Persisted by id.
    // Pike Yard: hay bales are the spearmen's drill targets.
    PIKE_YARD("pike_yard", 0, 3, Items.TRIDENT,
        Requirement.beds(2),
        Requirement.blocks("drill_target", 2, Blocks.HAY_BLOCK),
        Requirement.blocks("storage", 1, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1), Requirement.lights(2), Requirement.floorSpace(30)),
    // Sword Hall: an anvil and a grindstone keep two-handed blades true --
    // the anvil is what makes this a Town-priced hall.
    SWORD_HALL("sword_hall", 0, 2, Items.GRINDSTONE,
        Requirement.beds(2),
        Requirement.blocks("anvil", 1, Blocks.ANVIL, Blocks.CHIPPED_ANVIL, Blocks.DAMAGED_ANVIL),
        Requirement.blocks("whetstone", 1, Blocks.GRINDSTONE),
        Requirement.blocks("storage", 1, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1), Requirement.lights(2), Requirement.floorSpace(30)),
    // Rune Hall: late by its materials (enchanting table, amethyst); the
    // mage cap (RoleHiring) applies on top of its two slots.
    RUNE_HALL("rune_hall", 0, 2, Items.ENCHANTING_TABLE,
        Requirement.blocks("rune_table", 1, Blocks.ENCHANTING_TABLE),
        Requirement.blocks("amethyst", 4, Blocks.AMETHYST_BLOCK),
        Requirement.blocks("bookshelf", 4, Blocks.BOOKSHELF, Blocks.CHISELED_BOOKSHELF),
        Requirement.doors(1), Requirement.lights(3), Requirement.floorSpace(25));

    private final String id;
    private final int residentCapacity;
    private final int workerCapacity;
    private final Item emblem;
    private final List<Requirement> requirements;

    BuildingType(String id, int residentCapacity, int workerCapacity, Item emblem,
                 Requirement... requirements) {
        this.id = id;
        this.residentCapacity = residentCapacity;
        this.workerCapacity = workerCapacity;
        this.emblem = emblem;
        this.requirements = List.of(requirements);
    }

    /**
     * The item that stands for this building on its plan sheet.
     *
     * <p>Not a drawing of the building, and not a drawing at all: the plaque
     * renders this ITEM, the way an item frame does. That is TekTopia's own
     * convention -- you declare a building there by hanging an item in a frame
     * beside its door -- and it is right for a reason that took seven attempts
     * at hand-drawn art to learn. A bed, a chest, a sheaf of wheat are shapes
     * every Minecraft player already knows without being taught, drawn by the
     * people who drew everything else the player is looking at. No sprite this
     * mod could author will ever be as recognisable, as consistent with the
     * rest of the screen, or as free to maintain when a seventh building type
     * is designed.
     */
    public Item emblem() {
        return emblem;
    }

    public String id() {
        return id;
    }

    /**
     * Beds still decide how many people a dwelling sleeps; this is the ceiling
     * the type imposes on top of that. Work buildings house nobody.
     */
    public int residentCapacity() {
        return residentCapacity;
    }

    public int workerCapacity() {
        return workerCapacity;
    }

    /**
     * The requirement with this id, or null. Needed because the plaque sends
     * its survey to the client as (id, have, needed) triples -- the counter
     * function itself cannot cross the wire, and the client only needs to
     * name and count, not to re-measure.
     */
    public Requirement requirementById(String id) {
        for (Requirement r : requirements()) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        return null;
    }

    public List<Requirement> requirements() {
        return requirements;
    }

    /**
     * How the plaque may survey this type. ROOM: an enclosed, roofed room
     * (RoomScanner). YARD_OR_ROOM: the open-air trades (owner, 26 Sep: "they
     * don't even need a roof, like a lumberjack camp") may ALSO register as a
     * bounded work yard with a covered tool shelter (settlement.YardScanner).
     * Rooms are always tried first, so a building of these types that already
     * registers as a room keeps registering exactly as before.
     */
    public enum ValidationMode { ROOM, YARD_OR_ROOM }

    public ValidationMode validationMode() {
        return switch (this) {
            case LUMBER_CAMP, SAWMILL, MINE, MASON, SMITHY, SMELTER, TANNERY, BUILDERS_HUT, WELL, MARKET ->
                ValidationMode.YARD_OR_ROOM;
            default -> ValidationMode.ROOM;
        };
    }

    public boolean housesResidents() {
        return residentCapacity > 0;
    }

    public boolean employsWorkers() {
        return workerCapacity > 0;
    }

    public Component displayName() {
        return Component.translatable("hearthstead.building." + id);
    }

    public static BuildingType byId(String id) {
        for (BuildingType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return HOUSE;
    }
}
