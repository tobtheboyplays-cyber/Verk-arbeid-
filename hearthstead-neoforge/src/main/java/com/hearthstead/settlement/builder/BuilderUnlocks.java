package com.hearthstead.settlement.builder;

import com.hearthstead.building.BuildingType;
import com.hearthstead.settlement.Settlement;
import com.hearthstead.settlement.development.Development;
import com.hearthstead.settlement.development.PostRaidUpgrade;
import net.minecraft.server.level.ServerLevel;

import javax.annotation.Nullable;

/**
 * What the tech tree lets this settlement's Builder raise. No hut levels
 * (owner, 26 Sep): {@code builders_hut} (for now: Timber Rights) gives the
 * Builder, starter blueprints, barricades and Upgrade Orders;
 * {@code defense_plans} palisade lines, gates and the timber tower;
 * {@code masonry} stone lines, the gatehouse, stone towers and stone floors.
 */
public final class BuilderUnlocks {

    public static final String BUILDERS_HUT = "builders_hut";
    public static final String DEFENSE_PLANS = "defense_plans";
    public static final String MASONRY = "masonry";

    private BuilderUnlocks() {
    }

    /** Whether the named unlock is owned. Unknown ids are treated as locked. */
    public static boolean owns(ServerLevel level, Settlement settlement, String unlock) {
        return switch (unlock) {
            case BUILDERS_HUT -> Development.isBuildingUnlocked(level, settlement, BuildingType.BUILDERS_HUT);
            case DEFENSE_PLANS -> owns(level, settlement, BUILDERS_HUT)
                && Development.hasUpgrade(level, settlement, PostRaidUpgrade.DEFENSE_PLANS);
            case MASONRY -> owns(level, settlement, BUILDERS_HUT)
                && Development.hasUpgrade(level, settlement, PostRaidUpgrade.MASONRY);
            default -> false;
        };
    }

    /** The unlock a line kind needs. */
    public static String forLine(String kind) {
        return switch (kind) {
            case BuildPlanner.STONE -> MASONRY;
            case BuildPlanner.PALISADE -> DEFENSE_PLANS;
            default -> BUILDERS_HUT;
        };
    }

    /**
     * Why a blueprint cannot be ordered, as a lang key, or null when it can.
     * Both the blueprint's own unlock and its building type's node count:
     * a Tavern blueprint still needs Hospitality.
     */
    @Nullable
    public static String blueprintLock(ServerLevel level, Settlement settlement, Blueprint blueprint) {
        BlueprintMeta meta = blueprint.meta();
        if (!owns(level, settlement, meta.requires())) {
            return "hearthstead.builder.lock." + meta.requires();
        }
        BuildingType type = buildingType(meta.buildingType());
        if (type != null && !Development.isBuildingUnlocked(level, settlement, type)) {
            return "hearthstead.builder.lock.building";
        }
        return null;
    }

    // ------------------------------------------------- global size limits ---

    /**
     * The best checklist level among the settlement's valid Builder's Huts.
     * Shown in the plan only: no hut levels gate what he builds (owner,
     * 26 Sep; P1 from the native film -- an L1 hut refused every House).
     */
    public static int hutLevel(Settlement settlement) {
        int best = 0;
        for (com.hearthstead.settlement.Building building : settlement.buildings) {
            if (building.valid && building.type == BuildingType.BUILDERS_HUT) {
                best = Math.max(best, building.level);
            }
        }
        return best;
    }

    /** Largest footprint side one Builder job may cover (global safety limit, any hut). */
    public static final int MAX_FOOTPRINT = 32;

    /** Why one job is too big for any Builder (global safety limits), or null. */
    @Nullable
    public static String sizeLock(Settlement settlement, int sizeX, int sizeZ, int steps) {
        if (Math.max(sizeX, sizeZ) > MAX_FOOTPRINT || steps > BuildJob.MAX_STEPS) {
            return "hearthstead.builder.lock.too_big";
        }
        return null;
    }

    /** Exact id lookup (BuildingType.byId falls back to HOUSE, which would lie). */
    @Nullable
    public static BuildingType buildingType(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (BuildingType type : BuildingType.values()) {
            if (type.id().equals(id)) {
                return type;
            }
        }
        return null;
    }
}
