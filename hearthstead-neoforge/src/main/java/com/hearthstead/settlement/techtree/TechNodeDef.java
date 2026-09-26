package com.hearthstead.settlement.techtree;

import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.util.List;

/**
 * One node of the v3 tech tree, loaded from
 * {@code data/hearthstead/techtree/<branch>.json} (see {@link TechTreeData}).
 *
 * <p>Pure data: no registry lookups happen here, so JUnit can load and
 * validate the whole tree without a game. Costs resolve to items lazily in
 * {@code TechCosts}; effects live in {@link EffectRegistry}; server rules in
 * {@code settlement.development.TechTree}.
 *
 * <p>{@link #legacy} names the old catalogue entry that stores this node's
 * learned state ({@code node:<DevelopmentNode id>} or
 * {@code upgrade:<PostRaidUpgrade id>}); a node without one is stored by id
 * in the Development state's tech set. Ids are save identities: never rename.
 */
public record TechNodeDef(
    String id,
    String branch,
    int tier,
    String type,
    int x,
    int y,
    int coins,
    List<GoodsLine> goods,
    int studyDays,
    List<String> requires,
    List<String> excludes,
    List<Gate> gates,
    String gateText,
    boolean auto,
    @Nullable String legacy,
    String designStatus,
    String impl,
    String name,
    String offers,
    List<String> details,
    String flavor,
    List<String> dependsOn,
    List<String> unlocksRecipes
) {
    public static final String LEGACY_NODE = "node:";
    public static final String LEGACY_UPGRADE = "upgrade:";

    /** One goods line: an item id, or an item tag id ("minecraft:logs"). */
    public record GoodsLine(@Nullable String item, @Nullable String tag, int count) {
        public boolean isTag() {
            return tag != null;
        }

        public String key() {
            return tag != null ? "#" + tag : item;
        }
    }

    /**
     * One machine-checked milestone. Kinds: {@code objective} (a
     * DevelopmentObjective id + target), {@code settlers}, {@code raids_won},
     * {@code first_raid}, {@code branch_nodes} (tier/count/branches) and
     * {@code owns_any} (nodes). Lanes may register more in {@code TechGates}.
     */
    public record Gate(String kind, @Nullable String objective, int target,
                       List<String> nodes, int tier, int count, int branches) {
    }

    public boolean legacyNode() {
        return legacy != null && legacy.startsWith(LEGACY_NODE);
    }

    public boolean legacyUpgrade() {
        return legacy != null && legacy.startsWith(LEGACY_UPGRADE);
    }

    /** The legacy catalogue id (after the "node:"/"upgrade:" prefix), or null. */
    @Nullable
    public String legacyId() {
        if (legacy == null) {
            return null;
        }
        int colon = legacy.indexOf(':');
        return colon < 0 ? legacy : legacy.substring(colon + 1);
    }

    public boolean choice() {
        return !excludes.isEmpty();
    }

    public boolean free() {
        return coins <= 0 && goods.isEmpty();
    }

    /** Lang key base; every text falls back to the English in the data file. */
    public String key(String part) {
        return "hearthstead.techtree.node." + id + "." + part;
    }

    public Component displayName() {
        return Component.translatableWithFallback(key("name"), name);
    }

    /** Short label for the map (icons.json "short"), falling back to the full name. */
    public Component shortName() {
        String fallback = TechTreeData.get().shortName(id);
        return Component.translatableWithFallback(key("short"), fallback == null ? name : fallback);
    }

    public Component offersText() {
        return Component.translatableWithFallback(key("offers"), offers);
    }

    public Component flavorText() {
        return Component.translatableWithFallback(key("flavor"), flavor);
    }

    public Component gateLine() {
        return Component.translatableWithFallback(key("gate"), gateText);
    }

    public Component detail(int index) {
        return Component.translatableWithFallback(key("detail." + index), details.get(index));
    }
}
