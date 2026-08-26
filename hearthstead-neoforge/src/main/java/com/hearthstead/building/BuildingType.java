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

    SCHOOL("school", 0, 1, Items.WRITABLE_BOOK,
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
        Requirement.blocks("water", 2, Blocks.WATER),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
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
    INFIRMARY("infirmary", 0, 1, Items.GOLDEN_APPLE,
        Requirement.blocks("cauldron", 1, Blocks.CAULDRON, Blocks.WATER_CAULDRON),
        Requirement.beds(2),
        Requirement.blocks("storage", 1, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(20)),

    BARRACKS("barracks", 0, 4, Items.IRON_SWORD,
        Requirement.beds(4),
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

    MARKET("market", 0, 2, Items.EMERALD,
        Requirement.blocks("stall", 4, Blocks.BARREL, Blocks.SCAFFOLDING),
        Requirement.blocks("storage", 2, Blocks.CHEST, Blocks.BARREL),
        Requirement.doors(1),
        Requirement.lights(2),
        Requirement.floorSpace(36));

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
