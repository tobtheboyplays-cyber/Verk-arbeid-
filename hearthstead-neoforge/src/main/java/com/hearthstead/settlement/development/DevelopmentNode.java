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
 * three are the first post-raid choice. Specializations remain visible while
 * each complete gameplay loop is released independently.
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
        // BUILDER lane: the Builder's Hut + Builder ride on Timber Rights
        // until the tech tree's own `builders_hut` node exists (agreed with
        // the tech-tree designer; no new node needs a DevelopmentScreen slot).
        buildings(BuildingType.LUMBER_CAMP, BuildingType.BUILDERS_HUT),
        professions(Profession.LUMBERER, Profession.BUILDER)),

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

    SHORE_PROVISIONS(10, "shore_provisions", Stage.TRUNK, Branch.COMMON, true,
        requires("stores_and_roads"), quests(
            objective(DevelopmentObjective.COURIER_DELIVERIES, 1)),
        costs(logCost(4), cost(Items.STRING, 2)),
        buildings(BuildingType.FISHERY), professions(Profession.FISHER)),

    HOME(9, "home", Stage.TRUNK, Branch.COMMON, true,
        requires("shelter"), quests(
            objective(DevelopmentObjective.FOUNDATION_READY, 1)),
        costs(logCost(12), cost(Items.COBBLESTONE, 8)),
        buildings(BuildingType.HOUSE, BuildingType.LODGING), noProfessions()),

    HOSPITALITY(5, "hospitality", Stage.TRUNK, Branch.COMMON, true,
        requires("home"), quests(
            objective(DevelopmentObjective.HOUSED_SETTLERS, 3)),
        // Survival audit 2026-09-25: 4 bread (12 wheat), not 8. Hospitality opens
        // the Tavern, which gates recruitment and first-raid readiness; every
        // bread spent here is also a ready meal the 8-per-settler reserve needs.
        costs(cost(Items.BREAD, 4), cost(Items.LEATHER, 2)),
        buildings(BuildingType.TAVERN, BuildingType.TRADING_POST),
        professions(Profession.INNKEEPER, Profession.TRADER)),

    FIRST_WATCH(6, "first_watch", Stage.TRUNK, Branch.COMMON, true,
        // An optional early-defense sibling of the economic/Hospitality path.
        // A real post-Warehouse Courier route, rather than merely choosing the
        // Courier profession, proves that the settlement can supply a Guard.
        requires("stores_and_roads"), quests(
            objective(DevelopmentObjective.COURIER_DELIVERIES, 1)),
        costs(cost(Items.IRON_INGOT, 4), logCost(8)),
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
        noBuildings(), 
        // BATTLE-ROLES: surviving the first raid teaches the village to bind
        // wounds (Healer emblem). The Infirmary plan itself is opened through
        // RoleUnlocks so this milestone card keeps its own icon.
        professions(Profession.HEALER)),

    SHIELD_DOCTRINE(20, "shield_doctrine", Stage.DOCTRINE, Branch.SHIELD, true,
        requires("first_raid_aftermath"), quests(
            objective(DevelopmentObjective.GUARD_XP_EARNED, 40)),
        costs(cost(Items.IRON_INGOT, 8), cost(Items.LEATHER, 4)),
        noBuildings(), 
        // BATTLE-ROLES: the shield drill grows into pike and two-hander
        // drills (Pike Yard / Sword Hall plans via RoleUnlocks).
        professions(Profession.SPEARMAN, Profession.LONGSWORDSMAN)),

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
        buildings(BuildingType.ARCHITECTS_STUDY), professions(Profession.SCHOLAR, // BATTLE-ROLES: scholars carve runes too
            Profession.RUNE_MAGE)),

    // TECH TREE v3 (26 Sep): the five specializations below require only the
    // First Raid Aftermath (Village Charter) here. The v3 tree's own requires
    // (data/hearthstead/techtree) are stricter and are what players see; the
    // legacy list must never demand more than the tree implies, because it is
    // re-validated when a save loads (TechTreeDataTest checks this). Loosening
    // it cannot quarantine an old save.
    // TRADES-UNLOCK (26 Sep): the four specializations below are learnable
    // behind [features] extendedTrades (ExtendedTrades). Goods follow the v3
    // design tree (plan/techtree/techtree.json) where the node corresponds;
    // prerequisites stay on the doctrine the DevelopmentScreen edges draw.
    // The Fletcher rides here: arrows are the watch's supply line, like the
    // Armourer's plate (no fletcher node exists in the tree).
    FORTIFICATION(40, "fortification", Stage.SPECIALIZATION,
        Branch.FORTIFICATION, true, requires("first_raid_aftermath"),
        costs(Items.IRON_INGOT, 12, Items.LEATHER, 8),
        buildings(BuildingType.ARMOURY, BuildingType.FLETCHER),
        professions(Profession.ARMOURER, Profession.FLETCHER)),

    BORDER_WARDENS(41, "border_wardens", Stage.SPECIALIZATION,
        Branch.BORDER_WARDENS, true, requires("first_raid_aftermath"),
        costs(Items.LEATHER, 8, Items.ARROW, 16),
        buildings(BuildingType.HUNTERS_LODGE),
        professions(Profession.HUNTER)),

    LAND_AND_HARVEST(42, "land_and_harvest", Stage.SPECIALIZATION,
        Branch.LAND_AND_HARVEST, true, requires("first_raid_aftermath"),
        costs(Items.HAY_BLOCK, 8, Items.OAK_FENCE, 8),
        buildings(BuildingType.MILL, BuildingType.PASTURE, BuildingType.BAKERY,
            BuildingType.BUTCHER),
        professions(Profession.MILLER, Profession.HERDER, Profession.BAKER,
            Profession.BUTCHER)),

    CRAFT_AND_INDUSTRY(43, "craft_and_industry", Stage.SPECIALIZATION,
        Branch.CRAFT_AND_INDUSTRY, true, requires("first_raid_aftermath"),
        costs(cost(Items.IRON_INGOT, 8), cost(Items.COBBLESTONE, 24), logCost(16)),
        buildings(BuildingType.MINE, BuildingType.CARPENTER, BuildingType.MASON,
            BuildingType.SMELTER, BuildingType.SMITHY, BuildingType.TANNERY,
            BuildingType.WEAVER),
        professions(Profession.MINER, Profession.CARPENTER, Profession.MASON,
            Profession.SMELTER, Profession.SMITH, Profession.TANNER,
            Profession.WEAVER)),

    HALL_AND_LEARNING(44, "hall_and_learning", Stage.SPECIALIZATION,
        Branch.HALL_AND_LEARNING, true, requires("first_raid_aftermath"),
        costs(Items.BOOKSHELF, 6, Items.BREAD, 16),
        // Only plans with a working loop: the Well, School and Market do not
        // operate yet, and the Infirmary already comes with the Healer.
        buildings(BuildingType.KITCHEN, BuildingType.LIBRARY,
            BuildingType.DINING_HALL, BuildingType.BREWERY),
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
    /** Lazily resolved (Coins are a deferred registry item). */
    private volatile List<Cost> pricedCosts;

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

    /**
     * Learnable in this world. The four extended-trade specializations also
     * need {@code [features] extendedTrades}; off, they read FUTURE exactly
     * as before the trades-unlock lane.
     */
    public boolean implemented() {
        return implemented && (!extendedTrade() || ExtendedTrades.enabled());
    }

    /** One of the four specializations gated by {@link ExtendedTrades}. */
    public boolean extendedTrade() {
        return switch (this) {
            case FORTIFICATION, LAND_AND_HARVEST, CRAFT_AND_INDUSTRY,
                 HALL_AND_LEARNING -> true;
            default -> false;
        };
    }

    public List<String> prerequisites() {
        return prerequisites;
    }

    public List<QuestRequirement> quests() {
        return quests;
    }

    /**
     * The full price charged exactly once from the Hearth treasury view
     * (Hearth, the buyer's inventory and the settlement Warehouse chests):
     * the Coin line first, then every declared physical goods line. Nodes
     * that declare no costs (the auto-granted Founding seals, Arm the Watch
     * and the First Raid milestone) stay free.
     */
    public List<Cost> costs() {
        if (costs.isEmpty()) return costs;
        List<Cost> cached = pricedCosts;
        if (cached == null) {
            java.util.ArrayList<Cost> priced = new java.util.ArrayList<>(costs.size() + 1);
            priced.add(new Cost(com.hearthstead.registry.ModItems.GOLD_COIN.get(), coinCost()));
            priced.addAll(costs);
            cached = List.copyOf(priced);
            pricedCosts = cached;
        }
        return cached;
    }

    /** Coins in this node's price; tier-scaled so each step outward costs more. */
    public int coinCost() {
        if (costs.isEmpty()) return 0;
        return switch (this) {
            // Early-Coin balance (26 Sep): the two nodes a new player buys
            // from the first merchant's money cost 1, so Lumber Camp, the
            // Lumberer and Home fit one Basic log shipment (32 logs).
            case TIMBER_RIGHTS, HOME -> 1;
            case STORES_AND_ROADS, CULTIVATED_GROUND, SHORE_PROVISIONS -> 2;
            case HOSPITALITY, FIRST_WATCH -> 4;
            // v3 design tree: the two widest specializations cost 8.
            case FORTIFICATION, CRAFT_AND_INDUSTRY -> 8;
            default -> 6;
        };
    }

    /** Only the physical goods lines (no Coins), in declaration order. */
    public List<Cost> materialCosts() {
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
