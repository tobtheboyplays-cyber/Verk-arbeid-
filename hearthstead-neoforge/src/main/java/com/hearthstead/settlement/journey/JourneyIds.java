package com.hearthstead.settlement.journey;

import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Frozen stable IDs for the Journey.
 *
 * <p>Definition v3 is append-only. The first 45 entries are byte-for-byte the
 * shipped v2 roster and retain ordinals 0..44. The First Watch additions use
 * new ordinals at the end of the roster; their dependency edges, rather than
 * their numeric-looking names, place them before FJ-560 in gameplay.
 */
public final class JourneyIds {
    public static final ResourceLocation CHAPTER_FOUNDATION = chapter("foundation");
    public static final ResourceLocation CHAPTER_FIRST_LABOR = chapter("first_labor");
    public static final ResourceLocation CHAPTER_LOGISTICS = chapter("logistics");
    public static final ResourceLocation CHAPTER_FOOD_SECURITY = chapter("food_security");
    public static final ResourceLocation CHAPTER_GROWTH = chapter("growth");
    public static final ResourceLocation CHAPTER_FIRST_WATCH = chapter("first_watch");
    public static final ResourceLocation CHAPTER_FIRST_RAID = chapter("first_raid");

    public static final ResourceLocation FJ_010_FOUND_HEARTH = step("fj_010_found_hearth");
    public static final ResourceLocation FJ_020_OPEN_JOURNEY = step("fj_020_open_journey");
    public static final ResourceLocation FJ_030_APPOINT_MAYOR = step("fj_030_appoint_mayor");
    public static final ResourceLocation FJ_100_UNLOCK_LUMBER_CAMP = step("fj_100_unlock_lumber_camp");
    public static final ResourceLocation FJ_110_LINK_LUMBER_CAMP = step("fj_110_link_lumber_camp");
    public static final ResourceLocation FJ_120_STAFF_LUMBER_CAMP = step("fj_120_staff_lumber_camp");
    public static final ResourceLocation FJ_130_OPEN_LUMBERER_INVENTORY = step("fj_130_open_lumberer_inventory");
    public static final ResourceLocation FJ_140_SET_LUMBER_ZONE = step("fj_140_set_lumber_zone");
    public static final ResourceLocation FJ_150_LUMBERER_REQUESTS_AXE = step("fj_150_lumberer_requests_axe");
    public static final ResourceLocation FJ_160_GIVE_LUMBERER_AXE = step("fj_160_give_lumberer_axe");
    public static final ResourceLocation FJ_170_LUMBERER_FELLS_TREE = step("fj_170_lumberer_fells_tree");
    public static final ResourceLocation FJ_180_LUMBER_CAMP_STORES_LOG = step("fj_180_lumber_camp_stores_log");
    public static final ResourceLocation FJ_200_UNLOCK_WAREHOUSE = step("fj_200_unlock_warehouse");
    public static final ResourceLocation FJ_210_LINK_WAREHOUSE = step("fj_210_link_warehouse");
    public static final ResourceLocation FJ_220_STAFF_WAREHOUSE = step("fj_220_staff_warehouse");
    public static final ResourceLocation FJ_230_OPEN_REQUEST_LEDGER = step("fj_230_open_request_ledger");
    public static final ResourceLocation FJ_240_REQUEST_FIRST_PICKUP = step("fj_240_request_first_pickup");
    public static final ResourceLocation FJ_250_COURIER_CLAIMS_PICKUP = step("fj_250_courier_claims_pickup");
    public static final ResourceLocation FJ_260_WAREHOUSE_RECEIVES_LOG = step("fj_260_warehouse_receives_log");
    public static final ResourceLocation FJ_300_UNLOCK_FARMHOUSE = step("fj_300_unlock_farmhouse");
    public static final ResourceLocation FJ_310_LINK_FARMHOUSE = step("fj_310_link_farmhouse");
    public static final ResourceLocation FJ_320_STAFF_FARMHOUSE = step("fj_320_staff_farmhouse");
    public static final ResourceLocation FJ_330_SET_FARM_ZONE = step("fj_330_set_farm_zone");
    public static final ResourceLocation FJ_340_FARMER_REQUESTS_HOE = step("fj_340_farmer_requests_hoe");
    public static final ResourceLocation FJ_350_EQUIP_FARMER = step("fj_350_equip_farmer");
    public static final ResourceLocation FJ_360_SUPPLY_FIRST_SEED = step("fj_360_supply_first_seed");
    public static final ResourceLocation FJ_370_FARMHOUSE_STORES_CROP = step("fj_370_farmhouse_stores_crop");
    public static final ResourceLocation FJ_380_WAREHOUSE_RECEIVES_CROP = step("fj_380_warehouse_receives_crop");
    public static final ResourceLocation FJ_400_UNLOCK_HOME = step("fj_400_unlock_home");
    public static final ResourceLocation FJ_410_LINK_FIRST_HOME = step("fj_410_link_first_home");
    public static final ResourceLocation FJ_420_UNLOCK_TAVERN = step("fj_420_unlock_tavern");
    public static final ResourceLocation FJ_430_LINK_TAVERN = step("fj_430_link_tavern");
    public static final ResourceLocation FJ_440_RECRUITMENT_WINDOW_STARTS = step("fj_440_recruitment_window_starts");
    public static final ResourceLocation FJ_450_TRAVELER_ARRIVES = step("fj_450_traveler_arrives");
    public static final ResourceLocation FJ_460_ADMIT_TRAVELER = step("fj_460_admit_traveler");
    public static final ResourceLocation FJ_500_UNLOCK_FIRST_WATCH = step("fj_500_unlock_first_watch");
    public static final ResourceLocation FJ_510_LINK_BARRACKS = step("fj_510_link_barracks");
    public static final ResourceLocation FJ_520_STAFF_BARRACKS = step("fj_520_staff_barracks");
    public static final ResourceLocation FJ_530_GUARD_REQUESTS_WEAPON = step("fj_530_guard_requests_weapon");
    public static final ResourceLocation FJ_540_EQUIP_GUARD = step("fj_540_equip_guard");
    public static final ResourceLocation FJ_550_SET_GUARD_ORDER = step("fj_550_set_guard_order");
    public static final ResourceLocation FJ_560_DECLARE_RAID_READY = step("fj_560_declare_raid_ready");
    public static final ResourceLocation FJ_600_RECEIVE_FIRST_WARNING = step("fj_600_receive_first_warning");
    public static final ResourceLocation FJ_610_FIRST_RAID_RESOLVED = step("fj_610_first_raid_resolved");
    public static final ResourceLocation FJ_620_REVIEW_AFTERMATH = step("fj_620_review_aftermath");

    public static final ResourceLocation FJ_551_UNLOCK_ARM_THE_WATCH =
        step("fj_551_unlock_arm_the_watch");
    public static final ResourceLocation FJ_552_ADD_FIFTH_BED =
        step("fj_552_add_fifth_bed");
    public static final ResourceLocation FJ_553_SECOND_RECRUITMENT_WINDOW =
        step("fj_553_second_recruitment_window");
    public static final ResourceLocation FJ_554_SECOND_TRAVELER_ARRIVES =
        step("fj_554_second_traveler_arrives");
    public static final ResourceLocation FJ_555_ADMIT_FIFTH_SETTLER =
        step("fj_555_admit_fifth_settler");
    public static final ResourceLocation FJ_556_LINK_WATCHTOWER =
        step("fj_556_link_watchtower");
    public static final ResourceLocation FJ_557_STAFF_WATCHTOWER =
        step("fj_557_staff_watchtower");
    public static final ResourceLocation FJ_558_ARCHER_REQUESTS_BOW =
        step("fj_558_archer_requests_bow");
    public static final ResourceLocation FJ_559_EQUIP_ARCHER =
        step("fj_559_equip_archer");
    public static final ResourceLocation FJ_559A_SUPPLY_ARCHER_AMMUNITION =
        step("fj_559a_supply_archer_ammunition");
    public static final ResourceLocation FJ_559B_SET_TOWER_POST =
        step("fj_559b_set_tower_post");

    /** Exact shipped definition-2 roster. Never reorder or add to this list. */
    public static final List<ResourceLocation> V2_STEPS = List.of(
        FJ_010_FOUND_HEARTH,
        FJ_020_OPEN_JOURNEY,
        FJ_030_APPOINT_MAYOR,
        FJ_100_UNLOCK_LUMBER_CAMP,
        FJ_110_LINK_LUMBER_CAMP,
        FJ_120_STAFF_LUMBER_CAMP,
        FJ_130_OPEN_LUMBERER_INVENTORY,
        FJ_140_SET_LUMBER_ZONE,
        FJ_150_LUMBERER_REQUESTS_AXE,
        FJ_160_GIVE_LUMBERER_AXE,
        FJ_170_LUMBERER_FELLS_TREE,
        FJ_180_LUMBER_CAMP_STORES_LOG,
        FJ_200_UNLOCK_WAREHOUSE,
        FJ_210_LINK_WAREHOUSE,
        FJ_220_STAFF_WAREHOUSE,
        FJ_230_OPEN_REQUEST_LEDGER,
        FJ_240_REQUEST_FIRST_PICKUP,
        FJ_250_COURIER_CLAIMS_PICKUP,
        FJ_260_WAREHOUSE_RECEIVES_LOG,
        FJ_300_UNLOCK_FARMHOUSE,
        FJ_310_LINK_FARMHOUSE,
        FJ_320_STAFF_FARMHOUSE,
        FJ_330_SET_FARM_ZONE,
        FJ_340_FARMER_REQUESTS_HOE,
        FJ_350_EQUIP_FARMER,
        FJ_360_SUPPLY_FIRST_SEED,
        FJ_370_FARMHOUSE_STORES_CROP,
        FJ_380_WAREHOUSE_RECEIVES_CROP,
        FJ_400_UNLOCK_HOME,
        FJ_410_LINK_FIRST_HOME,
        FJ_420_UNLOCK_TAVERN,
        FJ_430_LINK_TAVERN,
        FJ_440_RECRUITMENT_WINDOW_STARTS,
        FJ_450_TRAVELER_ARRIVES,
        FJ_460_ADMIT_TRAVELER,
        FJ_500_UNLOCK_FIRST_WATCH,
        FJ_510_LINK_BARRACKS,
        FJ_520_STAFF_BARRACKS,
        FJ_530_GUARD_REQUESTS_WEAPON,
        FJ_540_EQUIP_GUARD,
        FJ_550_SET_GUARD_ORDER,
        FJ_560_DECLARE_RAID_READY,
        FJ_600_RECEIVE_FIRST_WARNING,
        FJ_610_FIRST_RAID_RESOLVED,
        FJ_620_REVIEW_AFTERMATH
    );

    /** New IDs appended at ordinals 45..55 in definition 3. */
    public static final List<ResourceLocation> V3_APPENDED_STEPS = List.of(
        FJ_551_UNLOCK_ARM_THE_WATCH,
        FJ_552_ADD_FIFTH_BED,
        FJ_553_SECOND_RECRUITMENT_WINDOW,
        FJ_554_SECOND_TRAVELER_ARRIVES,
        FJ_555_ADMIT_FIFTH_SETTLER,
        FJ_556_LINK_WATCHTOWER,
        FJ_557_STAFF_WATCHTOWER,
        FJ_558_ARCHER_REQUESTS_BOW,
        FJ_559_EQUIP_ARCHER,
        FJ_559A_SUPPLY_ARCHER_AMMUNITION,
        FJ_559B_SET_TOWER_POST
    );

    public static final List<ResourceLocation> ALL_STEPS;

    static {
        java.util.ArrayList<ResourceLocation> all = new java.util.ArrayList<>(
            V2_STEPS.size() + V3_APPENDED_STEPS.size());
        all.addAll(V2_STEPS);
        all.addAll(V3_APPENDED_STEPS);
        ALL_STEPS = List.copyOf(all);
    }

    public static ResourceLocation step(String path) {
        return ResourceLocation.fromNamespaceAndPath("hearthstead", "journey/" + path);
    }

    public static ResourceLocation chapter(String path) {
        return ResourceLocation.fromNamespaceAndPath(
            "hearthstead", "journey/chapter/" + path);
    }

    private JourneyIds() {
    }
}
