package com.hearthstead.settlement.development;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Stable, common catalogue for the settlement Development tree.
 *
 * <p>The declaration order is presentation order only. Save data and network
 * packets use {@link #id} and {@link #wireId}; neither ever persists an enum
 * ordinal. The first nine nodes are the shared tutorial trunk. The next
 * three are the first post-raid choice, and the last five deliberately remain
 * visible-but-planned until their complete gameplay loops pass release QA.
 */
public enum DevelopmentNode {
    SETTLEMENT_CHARTER(0, "settlement_charter", Stage.ROOT, Branch.COMMON, true,
        noRequirements(), noCosts(), noBuildings(), noProfessions()),

    SHELTER(1, "shelter", Stage.TRUNK, Branch.COMMON, true,
        requires("settlement_charter"), noQuests(), noCosts(),
        noBuildings(), noProfessions()),

    TIMBER_RIGHTS(2, "timber_rights", Stage.TRUNK, Branch.COMMON, true,
        requires("shelter"), quests(
            objective(DevelopmentObjective.FOUNDATION_READY, 1)),
        costs(logCost(8), cost(Items.COBBLESTONE, 8)),
        buildings(BuildingType.LUMBER_CAMP), professions(Profession.LUMBERER)),

    // Stable wire id 4 intentionally appears before id 3. Wire ids are save
    // identities, while declaration order is the corrected tutorial order.
    STORES_AND_ROADS(4, "stores_and_roads", Stage.TRUNK, Branch.COMMON, true,
        requires("timber_rights"), quests(
            objective(DevelopmentObjective.LUMBER_LOGS_STORED, 1)),
        costs(logCost(8), cost(Items.LEATHER, 2)),
        buildings(BuildingType.WAREHOUSE), professions(Profession.COURIER)),

    CULTIVATED_GROUND(3, "cultivated_ground", Stage.TRUNK, Branch.COMMON, true,
        requires("stores_and_roads"), quests(
            objective(DevelopmentObjective.COURIER_DELIVERIES, 1)),
        costs(cost(Items.WHEAT_SEEDS, 8), logCost(4)),
        buildings(BuildingType.FARMHOUSE), professions(Profession.FARMER)),

    HOME(9, "home", Stage.TRUNK, Branch.COMMON, true,
        requires("cultivated_ground"), quests(
            objective(DevelopmentObjective.FARM_CROPS_STORED, 1)),
        costs(logCost(12), cost(Items.COBBLESTONE, 8)),
        buildings(BuildingType.HOUSE, BuildingType.LODGING), noProfessions()),

    HOSPITALITY(5, "hospitality", Stage.TRUNK, Branch.COMMON, true,
        requires("home"), quests(
            objective(DevelopmentObjective.HOUSED_SETTLERS, 3)),
        costs(cost(Items.BREAD, 8), cost(Items.LEATHER, 2)),
        buildings(BuildingType.TAVERN),
        professions(Profession.INNKEEPER)),

    FIRST_WATCH(6, "first_watch", Stage.TRUNK, Branch.COMMON, true,
        requires("hospitality"), quests(
            objective(DevelopmentObjective.HOUSED_SETTLERS, 4)),
        costs(cost(Items.IRON_INGOT, 8), logCost(8)),
        buildings(BuildingType.BARRACKS), professions(Profession.GUARD)),

    // Wire id 8 is intentionally appended instead of renumbering the shipped
    // 0..7 trunk. First Watch grants the physical Barracks/Guard loop; this
    // second phase proves that loop through one real Courier weapon delivery.
    ARM_THE_WATCH(8, "arm_the_watch", Stage.TRUNK, Branch.COMMON, true,
        requires("first_watch"), quests(
            objective(DevelopmentObjective.GUARD_EQUIPMENT_DELIVERIES, 1)),
        noCosts(), buildings(BuildingType.WATCHTOWER),
        professions(Profession.ARCHER)),

    FIRST_RAID_AFTERMATH(7, "first_raid_aftermath", Stage.AFTERMATH,
        Branch.COMMON, true, requires("arm_the_watch"), quests(
            objective(DevelopmentObjective.FIRST_RAID_COMPLETE, 1)), noCosts(),
        noBuildings(), noProfessions()),

    SHIELD_DOCTRINE(20, "shield_doctrine", Stage.DOCTRINE, Branch.SHIELD, true,
        requires("first_raid_aftermath"), quests(
            objective(DevelopmentObjective.GUARD_XP_EARNED, 40)),
        costs(cost(Items.IRON_INGOT, 16), cost(Items.LEATHER, 4)),
        noBuildings(), noProfessions()),

    GUILD_DOCTRINE(21, "guild_doctrine", Stage.DOCTRINE, Branch.GUILD, true,
        requires("first_raid_aftermath"), quests(
            objective(DevelopmentObjective.PRODUCTIVE_GOODS_MOVED, 64),
            objective(DevelopmentObjective.COURIER_DELIVERIES, 3)),
        costs(logCost(24), cost(Items.IRON_INGOT, 8)),
        buildings(BuildingType.SAWMILL), professions(Profession.SAWYER)),

    HEARTH_DOCTRINE(22, "hearth_doctrine", Stage.DOCTRINE, Branch.HEARTH, true,
        requires("first_raid_aftermath"), quests(
            objective(DevelopmentObjective.ALL_HOUSED_TICKS, 24_000),
            objective(DevelopmentObjective.EQUIPMENT_REQUESTS_SERVED, 5)),
        costs(cost(Items.BOOK, 4), cost(Items.BREAD, 8)),
        buildings(BuildingType.ARCHITECTS_STUDY), professions(Profession.SCHOLAR)),

    FORTIFICATION(40, "fortification", Stage.SPECIALIZATION,
        Branch.FORTIFICATION, false, requires("shield_doctrine"),
        costs(Items.IRON_BLOCK, 4),
        buildings(BuildingType.ARMOURY), professions(Profession.ARMOURER)),

    BORDER_WARDENS(41, "border_wardens", Stage.SPECIALIZATION,
        Branch.BORDER_WARDENS, false, requires("shield_doctrine"),
        costs(Items.COMPASS, 2, Items.LEATHER, 12),
        buildings(BuildingType.FLETCHER, BuildingType.HUNTERS_LODGE,
            BuildingType.FISHERY),
        professions(Profession.FLETCHER, Profession.HUNTER, Profession.FISHER)),

    LAND_AND_HARVEST(42, "land_and_harvest", Stage.SPECIALIZATION,
        Branch.LAND_AND_HARVEST, false, requires("guild_doctrine"),
        costs(Items.HAY_BLOCK, 8),
        buildings(BuildingType.MILL, BuildingType.PASTURE, BuildingType.BAKERY,
            BuildingType.BUTCHER),
        professions(Profession.MILLER, Profession.HERDER, Profession.BAKER,
            Profession.BUTCHER)),

    CRAFT_AND_INDUSTRY(43, "craft_and_industry", Stage.SPECIALIZATION,
        Branch.CRAFT_AND_INDUSTRY, false, requires("guild_doctrine"),
        costs(Items.IRON_BLOCK, 3, Items.BRICKS, 16),
        buildings(BuildingType.MINE, BuildingType.CARPENTER, BuildingType.MASON,
            BuildingType.SMELTER, BuildingType.SMITHY, BuildingType.TANNERY,
            BuildingType.WEAVER),
        professions(Profession.MINER, Profession.CARPENTER, Profession.MASON,
            Profession.SMELTER, Profession.SMITH, Profession.TANNER,
            Profession.WEAVER)),

    HALL_AND_LEARNING(44, "hall_and_learning", Stage.SPECIALIZATION,
        Branch.HALL_AND_LEARNING, false, requires("hearth_doctrine"),
        costs(Items.BOOKSHELF, 6, Items.BREAD, 16),
        buildings(BuildingType.KITCHEN, BuildingType.LIBRARY,
            BuildingType.DINING_HALL, BuildingType.BREWERY, BuildingType.WELL,
            BuildingType.SCHOOL, BuildingType.INFIRMARY, BuildingType.MARKET),
        professions(Profession.COOK, Profession.BREWER));

    public enum Stage {
        ROOT,
        TRUNK,
        AFTERMATH,
        DOCTRINE,
        SPECIALIZATION
    }

    public enum Branch {
        COMMON("common"),
        SHIELD("shield"),
        GUILD("guild"),
        HEARTH("hearth"),
        FORTIFICATION("fortification"),
        BORDER_WARDENS("border_wardens"),
        LAND_AND_HARVEST("land_and_harvest"),
        CRAFT_AND_INDUSTRY("craft_and_industry"),
        HALL_AND_LEARNING("hall_and_learning");

        private final String id;

        Branch(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    /** One physical line paid atomically from the Hearth inventory. */
    public record Cost(Item item, int count, @Nullable TagKey<Item> acceptedTag,
                       @Nullable String displayKey) {
        public Cost(Item item, int count) {
            this(item, count, null, null);
        }

        public Cost {
            if (item == null || count <= 0) {
                throw new IllegalArgumentException("Development costs must be positive items");
            }
        }

        public boolean matches(ItemStack stack) {
            return stack != null && !stack.isEmpty()
                && (stack.is(item) || acceptedTag != null && stack.is(acceptedTag));
        }

        public Component displayName() {
            return displayKey == null
                ? new ItemStack(item).getHoverName()
                : Component.translatable(displayKey);
        }
    }

    /** One server-authored X/Y gate; composite quests contain several. */
    public record QuestRequirement(DevelopmentObjective objective, int target) {
        public QuestRequirement {
            if (objective == null || target <= 0) {
                throw new IllegalArgumentException("Development quests need a positive target");
            }
        }
    }

    /**
     * Knowledge granted permanently to one settlement by this node. Build
     * Plans and Mayor emblem issuance both consult this payload through
     * {@link Development}; no global recipe-book flag is treated as
     * settlement knowledge.
     */
    public record Knowledge(List<BuildingType> buildPlans,
                            List<Profession> jobEmblems) {
    }

    public static final DevelopmentNode[] PRESENTATION_ORDER = values();

    private final int wireId;
    private final String id;
    private final Stage stage;
    private final Branch branch;
    private final boolean implemented;
    private final List<String> prerequisites;
    private final List<QuestRequirement> quests;
    private final List<Cost> costs;
    private final List<BuildingType> buildings;
    private final List<Profession> professions;
    private final Knowledge knowledge;

    DevelopmentNode(int wireId, String id, Stage stage, Branch branch,
                    boolean implemented, List<String> prerequisites,
                    List<Cost> costs, List<BuildingType> buildings,
                    List<Profession> professions) {
        this(wireId, id, stage, branch, implemented, prerequisites,
            noQuests(), costs, buildings, professions);
    }

    DevelopmentNode(int wireId, String id, Stage stage, Branch branch,
                    boolean implemented, List<String> prerequisites,
                    List<QuestRequirement> quests, List<Cost> costs,
                    List<BuildingType> buildings,
                    List<Profession> professions) {
        this.wireId = wireId;
        this.id = id;
        this.stage = stage;
        this.branch = branch;
        this.implemented = implemented;
        this.prerequisites = prerequisites;
        this.quests = quests;
        this.costs = costs;
        this.buildings = buildings;
        this.professions = professions;
        this.knowledge = new Knowledge(buildings, professions);
    }

    public int wireId() {
        return wireId;
    }

    public String id() {
        return id;
    }

    public Stage stage() {
        return stage;
    }

    public Branch branch() {
        return branch;
    }

    public boolean implemented() {
        return implemented;
    }

    public List<String> prerequisites() {
        return prerequisites;
    }

    public List<QuestRequirement> quests() {
        return quests;
    }

    public List<Cost> costs() {
        return costs;
    }

    public List<BuildingType> buildings() {
        return buildings;
    }

    public List<Profession> professions() {
        return professions;
    }

    public Knowledge knowledge() {
        return knowledge;
    }

    public boolean doctrine() {
        return stage == Stage.DOCTRINE;
    }

    public Component displayName() {
        return Component.translatable("hearthstead.development.node." + id + ".name");
    }

    public Component description() {
        return Component.translatable("hearthstead.development.node." + id + ".desc");
    }

    public Component tradeoff() {
        return Component.translatable("hearthstead.development.node." + id + ".tradeoff");
    }

    @Nullable
    public static DevelopmentNode byId(String id) {
        if (id == null) {
            return null;
        }
        for (DevelopmentNode node : PRESENTATION_ORDER) {
            if (node.id.equals(id)) {
                return node;
            }
        }
        return null;
    }

    @Nullable
    public static DevelopmentNode byWireId(int wireId) {
        for (DevelopmentNode node : PRESENTATION_ORDER) {
            if (node.wireId == wireId) {
                return node;
            }
        }
        return null;
    }

    private static List<String> noRequirements() {
        return List.of();
    }

    private static List<String> requires(String... ids) {
        return List.of(ids);
    }

    private static List<Cost> noCosts() {
        return List.of();
    }

    private static List<QuestRequirement> noQuests() {
        return List.of();
    }

    private static QuestRequirement objective(DevelopmentObjective objective,
                                              int target) {
        return new QuestRequirement(objective, target);
    }

    private static List<QuestRequirement> quests(QuestRequirement... requirements) {
        return List.of(requirements);
    }

    private static Cost cost(Item item, int count) {
        return new Cost(item, count);
    }

    private static Cost logCost(int count) {
        return new Cost(Items.OAK_LOG, count, ItemTags.LOGS,
            "hearthstead.development.cost.any_log");
    }

    private static List<Cost> costs(Cost... costs) {
        return List.of(costs);
    }

    private static List<Cost> costs(Object... itemCountPairs) {
        if ((itemCountPairs.length & 1) != 0) {
            throw new IllegalArgumentException("costs requires item/count pairs");
        }
        java.util.ArrayList<Cost> costs = new java.util.ArrayList<>(itemCountPairs.length / 2);
        for (int i = 0; i < itemCountPairs.length; i += 2) {
            costs.add(new Cost((Item) itemCountPairs[i], (Integer) itemCountPairs[i + 1]));
        }
        return List.copyOf(costs);
    }

    private static List<BuildingType> noBuildings() {
        return List.of();
    }

    private static List<BuildingType> buildings(BuildingType... types) {
        return List.of(types);
    }

    private static List<Profession> noProfessions() {
        return List.of();
    }

    private static List<Profession> professions(Profession... professions) {
        return List.of(professions);
    }
}
