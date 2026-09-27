package com.hearthstead.settlement.techtree;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.JobEmblemCatalog;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tech tree audit guard (owner, 26 Sep: "everything in the tech tree works,
 * checked from the centre outward"). Every unlock a node promises must
 * resolve to something the player can actually get:
 * <ul>
 *   <li>a building plan the node opens has a craftable {@code build_plan_} recipe;</li>
 *   <li>an emblem the node opens is on sale in the emblem shop (JobEmblemCatalog);</li>
 *   <li>every plan recipe and every shop emblem is opened by some node (no
 *       orphan that is either free on day one or never obtainable);</li>
 *   <li>prerequisites never point outward (a ring-1 node never needs a ring-2 one).</li>
 * </ul>
 * Icons and handbook entries per node are guarded by TechTreeNodeIconsTest and
 * ReferenceGuardTest; recipe gates by TechRecipeGatesTest. The audit table is
 * plan/TECHTREE-AUDIT.md.
 */
class TechTreeCompletenessTest {

    /** Plans a node really opens: its v3 claims plus the unclaimed legacy/RoleUnlocks ones. */
    private static Set<BuildingType> plansOf(TechNodeDef def) {
        EffectRegistry registry = EffectRegistry.get();
        Set<BuildingType> out = new LinkedHashSet<>();
        for (TechEffect effect : registry.effects(def.id())) {
            if (effect instanceof TechEffect.UnlockBuilding b) {
                out.add(b.type());
            }
        }
        if (def.legacyNode()) {
            DevelopmentNode node = DevelopmentNode.byId(def.legacyId());
            if (node != null) {
                for (BuildingType type : node.buildings()) {
                    if (!registry.claimed(type)) {
                        out.add(type);
                    }
                }
            }
        }
        return out;
    }

    private static Set<Profession> emblemsOf(TechNodeDef def) {
        EffectRegistry registry = EffectRegistry.get();
        Set<Profession> out = new LinkedHashSet<>();
        for (TechEffect effect : registry.effects(def.id())) {
            if (effect instanceof TechEffect.UnlockProfession p) {
                out.add(p.profession());
            }
        }
        if (def.legacyNode()) {
            DevelopmentNode node = DevelopmentNode.byId(def.legacyId());
            if (node != null) {
                for (Profession profession : node.professions()) {
                    if (!registry.claimed(profession)) {
                        out.add(profession);
                    }
                }
            }
        }
        return out;
    }

    private static boolean hasPlanRecipe(BuildingType type) {
        return TechTreeCompletenessTest.class.getResource(
            "/data/hearthstead/recipe/build_plan_" + type.id() + ".json") != null;
    }

    @Test
    void everyPlanANodeOpensHasABuildPlanRecipe() {
        List<String> missing = new ArrayList<>();
        for (TechNodeDef def : TechTreeData.get().nodes()) {
            for (BuildingType type : plansOf(def)) {
                if (!hasPlanRecipe(type)) {
                    missing.add(def.id() + " -> build_plan_" + type.id());
                }
            }
        }
        assertTrue(missing.isEmpty(), "Nodes promise plans that cannot be crafted: " + missing);
    }

    @Test
    void everyEmblemANodeOpensIsInTheEmblemShop() {
        Set<Profession> shop = new LinkedHashSet<>();
        for (JobEmblemCatalog.Entry entry : JobEmblemCatalog.RELEASE_CATALOG) {
            shop.add(entry.profession());
        }
        List<String> missing = new ArrayList<>();
        for (TechNodeDef def : TechTreeData.get().nodes()) {
            for (Profession profession : emblemsOf(def)) {
                if (!shop.contains(profession)) {
                    missing.add(def.id() + " -> " + profession.name());
                }
            }
        }
        assertTrue(missing.isEmpty(), "Nodes promise emblems the shop never sells: " + missing);
    }

    @Test
    void everyPlanRecipeIsOpenedBySomeNode() {
        List<String> orphans = new ArrayList<>();
        for (BuildingType type : BuildingType.values()) {
            if (hasPlanRecipe(type) && TechRecipeGates.nodeForBuilding(type) == null) {
                orphans.add(type.id());
            }
        }
        assertTrue(orphans.isEmpty(), "Build plans no tech node opens (ungated or unreachable): " + orphans);
    }

    @Test
    void everyShopEmblemIsOpenedBySomeNode() {
        Set<String> legacyIds = new LinkedHashSet<>();
        for (TechNodeDef def : TechTreeData.get().nodes()) {
            if (def.legacyNode()) {
                legacyIds.add(def.legacyId());
            }
        }
        List<String> orphans = new ArrayList<>();
        for (JobEmblemCatalog.Entry entry : JobEmblemCatalog.RELEASE_CATALOG) {
            boolean claimed = !EffectRegistry.get().professionClaimants(entry.profession()).isEmpty();
            if (!claimed && !legacyIds.contains(entry.unlock().id())) {
                orphans.add(entry.profession().name() + " (legacy " + entry.unlock().id() + ")");
            }
        }
        assertTrue(orphans.isEmpty(), "Shop emblems no tech node opens: " + orphans);
    }

    @Test
    void prerequisitesNeverPointOutward() {
        List<String> bad = new ArrayList<>();
        TechTreeData data = TechTreeData.get();
        for (TechNodeDef def : data.nodes()) {
            for (String req : def.requires()) {
                TechNodeDef other = data.node(req);
                if (other != null && other.tier() > def.tier()) {
                    bad.add(def.id() + " (ring " + def.tier() + ") needs " + req + " (ring " + other.tier() + ")");
                }
            }
        }
        assertTrue(bad.isEmpty(), "Prerequisites in an outer ring: " + bad);
    }
}
