package com.hearthstead.settlement.development;

import com.hearthstead.building.BuildingType;
import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.techtree.EffectRegistry;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;

import java.util.List;

/**
 * GameTest fixture helper (techtree-craft lane, 26 Sep): tech tree v3 lets a
 * node CLAIM a plan or emblem (builders_hut takes the Builder off Timber
 * Rights, tannery / carpenter_mason take trades off Mine, Smelter &amp; Smithy,
 * ...). Old fixtures that learned only the legacy node call this to also
 * grant the claiming node, the way a pre-v3 save is grandfathered.
 * Test-only: never called from gameplay.
 */
public final class TechTreeTestGrants {

    private TechTreeTestGrants() {
    }

    /** Grants one claimant node for the profession and for each plan, when claimed and not yet owned. */
    public static void grantClaimants(DevelopmentState state, Profession profession, BuildingType... plans) {
        if (profession != null) {
            grantAny(state, EffectRegistry.get().professionClaimants(profession));
        }
        for (BuildingType type : plans) {
            if (type != null) {
                grantAny(state, EffectRegistry.get().buildingClaimants(type));
            }
        }
    }

    private static void grantAny(DevelopmentState state, List<String> claimants) {
        if (claimants.isEmpty()) {
            return;
        }
        for (String id : claimants) {
            if (TechTree.learned(state, id)) {
                return;
            }
        }
        grant(state, claimants.get(0));
    }

    /** Records {@code id} as learned in its own storage (legacy catalogue or v3 id set). */
    public static void grant(DevelopmentState state, String id) {
        TechNodeDef def = TechTreeData.get().node(id);
        if (def == null) {
            return;
        }
        if (def.legacyNode()) {
            DevelopmentNode node = DevelopmentNode.byId(def.legacyId());
            if (node != null) {
                state.unlock(node);
            }
        } else if (def.legacyUpgrade()) {
            PostRaidUpgrade upgrade = PostRaidUpgrade.byId(def.legacyId());
            if (upgrade != null) {
                state.unlockUpgrade(upgrade);
            }
        } else {
            state.learnTech(def.id());
        }
    }
}
