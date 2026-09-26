package com.hearthstead.settlement.techtree;

import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.PostRaidUpgrade;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The v3 tech tree data contract: 84 nodes load from the jar, ids are unique,
 * every requires/excludes/gate id exists, pick-ones are symmetric, costs
 * resolve to real items, every legacy mapping points at a real catalogue
 * entry and never demands more than the tree requires (else a save would
 * quarantine), and old names migrate.
 */
class TechTreeDataTest {
    private static final TechTreeData DATA = TechTreeData.get();

    @Test
    void allDesignNodesLoadInFiveBranches() {
        // v3 design: 84 nodes (crown 5, watch 23, logistics 18, commons 21,
        // craft 17). Owner-approved additions may raise a branch; never lower.
        assertTrue(DATA.nodes().size() >= 84, "tree shrank: " + DATA.nodes().size());
        Map<String, Integer> counts = TechTreeData.countsByBranch(DATA);
        assertTrue(counts.get("crown") >= 5);
        assertTrue(counts.get("watch") >= 23);
        assertTrue(counts.get("logistics") >= 18);
        assertTrue(counts.get("commons") >= 21);
        assertTrue(counts.get("craft") >= 17);
        int sum = 0;
        for (int n : counts.values()) {
            sum += n;
        }
        assertEquals(DATA.nodes().size(), sum);
        assertEquals(5, DATA.branches().size());
        assertEquals(5, DATA.tiers().size());
    }

    @Test
    void idsUniqueAndEveryReferenceValid() {
        Set<String> ids = new HashSet<>();
        for (TechNodeDef node : DATA.nodes()) {
            assertTrue(ids.add(node.id()), "duplicate " + node.id());
        }
        List<String> errors = DATA.validate();
        assertTrue(errors.isEmpty(), String.join("\n", errors));
    }

    @Test
    void everyNodeReachesTheBannerAndLayoutIsDistinct() {
        Set<String> positions = new HashSet<>();
        for (TechNodeDef node : DATA.nodes()) {
            if (!node.id().equals("settlement_charter")) {
                assertTrue(DATA.ancestors(node.id()).contains("settlement_charter"),
                    node.id() + " must chain back to the Banner");
            }
            assertTrue(positions.add(node.x() + "," + node.y()), node.id() + " overlaps another node");
            assertTrue(!node.name().isBlank() && !node.offers().isBlank(), node.id() + " needs text");
        }
    }

    @Test
    void costsResolveToRealItemsCoinsFirst() {
        for (TechNodeDef node : DATA.nodes()) {
            List<DevelopmentNode.Cost> costs = TechCosts.costs(node);
            assertEquals(node.goods().size() + (node.coins() > 0 ? 1 : 0), costs.size(), node.id());
            if (node.coins() > 0) {
                assertEquals(node.coins(), costs.getFirst().count(), node.id());
            }
        }
    }

    @Test
    void everyBranchEffectClassRegistersWithoutErrors() {
        EffectRegistry.get();
        assertTrue(com.hearthstead.settlement.techtree.effects.TechEffects.registrationFailures().isEmpty(),
            String.join("; ", com.hearthstead.settlement.techtree.effects.TechEffects.registrationFailures()));
    }

    @Test
    void gateKindsAreKnown() {
        Set<String> known = Set.of("objective", "settlers", "raids_won", "first_raid",
            "branch_nodes", "owns_any");
        for (TechNodeDef node : DATA.nodes()) {
            for (TechNodeDef.Gate gate : node.gates()) {
                assertTrue(known.contains(gate.kind()), node.id() + " gate kind " + gate.kind());
            }
        }
    }

    @Test
    void legacyMappingsPointAtRealEntriesAndMatchTheirGates() {
        int legacy = 0;
        for (TechNodeDef node : DATA.nodes()) {
            if (node.legacyNode()) {
                legacy++;
                DevelopmentNode dn = DevelopmentNode.byId(node.legacyId());
                assertNotNull(dn, node.id());
                // Legacy prerequisites are validated when a save loads; the
                // tree must imply them or learning could quarantine a save.
                Set<String> implied = DATA.ancestors(node.id());
                for (String pre : dn.prerequisites()) {
                    assertTrue(pre.equals("shelter") || implied.contains(pre),
                        node.id() + ": legacy prerequisite " + pre + " not implied by requires");
                }
                List<String> legacyGates = new ArrayList<>();
                for (DevelopmentNode.QuestRequirement q : dn.quests()) {
                    legacyGates.add(q.objective().id() + "=" + q.target());
                }
                List<String> dataGates = new ArrayList<>();
                for (TechNodeDef.Gate g : node.gates()) {
                    if ("objective".equals(g.kind())) {
                        dataGates.add(g.objective() + "=" + g.target());
                    } else if ("first_raid".equals(g.kind())) {
                        dataGates.add("first_raid_complete=1");
                    }
                }
                assertEquals(legacyGates, dataGates, node.id() + " gates must match the legacy quests");
            } else if (node.legacyUpgrade()) {
                legacy++;
                PostRaidUpgrade up = PostRaidUpgrade.byId(node.legacyId());
                assertNotNull(up, node.id());
                Set<String> implied = DATA.ancestors(node.id());
                assertTrue(implied.contains(up.requires().id()),
                    node.id() + ": legacy parent " + up.requires().id() + " not implied");
                if (up.requiresUpgrade() != null) {
                    assertTrue(implied.contains(up.requiresUpgrade().id()),
                        node.id() + ": legacy chain " + up.requiresUpgrade().id() + " not implied");
                }
                String legacyGate = up.gateObjective() == null ? ""
                    : up.gateObjective().id() + "=" + up.gateTarget();
                String dataGate = "";
                for (TechNodeDef.Gate g : node.gates()) {
                    if ("objective".equals(g.kind())) {
                        dataGate = g.objective() + "=" + g.target();
                    }
                }
                assertEquals(legacyGate, dataGate, node.id() + " gate must match the upgrade gate");
            }
        }
        assertEquals(39, legacy, "18 DevelopmentNodes + 21 PostRaidUpgrades back v3 nodes");
    }

    @Test
    void displayNamesAreUnique() throws Exception {
        Map<String, String> names = new java.util.HashMap<>();
        Map<String, String> shorts = new java.util.HashMap<>();
        for (TechNodeDef node : DATA.nodes()) {
            String prior = names.put(node.name().toLowerCase(java.util.Locale.ROOT), node.id());
            assertTrue(prior == null, node.id() + " and " + prior + " are both called \"" + node.name() + "\"");
            String shortName = DATA.shortName(node.id());
            if (shortName != null) {
                String priorShort = shorts.put(shortName.toLowerCase(java.util.Locale.ROOT), node.id());
                assertTrue(priorShort == null, node.id() + " and " + priorShort + " share the short name " + shortName);
            }
        }
        // Legacy Development node names (old screen, handbook) must not reuse a different v3 node's name.
        try (var in = TechTreeDataTest.class.getResourceAsStream("/assets/hearthstead/lang/en_us.json")) {
            assertNotNull(in, "en_us.json on the classpath");
            var lang = com.google.gson.JsonParser.parseReader(
                new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            String prefix = "hearthstead.development.node.";
            for (String key : lang.keySet()) {
                if (!key.startsWith(prefix) || !key.endsWith(".name")) {
                    continue;
                }
                String legacyId = key.substring(prefix.length(), key.length() - ".name".length());
                String owner = names.get(lang.get(key).getAsString().toLowerCase(java.util.Locale.ROOT));
                assertTrue(owner == null || owner.equals(legacyId),
                    "legacy node " + legacyId + " reuses the name of v3 node " + owner);
            }
        }
    }

    @Test
    void oldNamesMigrateToV3Ids() {
        assertEquals("settlement_charter", TechIdMigration.canonical("shelter"));
        assertEquals("hall_and_learning", TechIdMigration.canonical("HALL_AND_LEARNING"));
        assertEquals("first_watch", TechIdMigration.canonical("node:first_watch"));
        assertEquals("guard_drill", TechIdMigration.canonical("upgrade:guard_drill"));
        assertEquals("kitchen_and_hall", TechIdMigration.canonical("varied_table"));
        assertEquals("veteran_techniques", TechIdMigration.canonical("shieldbearers"));
        assertEquals("master_armoury", TechIdMigration.canonical("hearthstead:master_armoury"));
        for (DevelopmentNode node : DevelopmentNode.values()) {
            String id = TechIdMigration.canonical(node.name());
            assertNotNull(DATA.node(id), node.name() + " must map to a v3 node");
        }
        for (PostRaidUpgrade upgrade : PostRaidUpgrade.values()) {
            assertNotNull(DATA.node(TechIdMigration.canonical(upgrade.name())),
                upgrade.name() + " must map to a v3 node");
        }
    }

    @Test
    void choicePairsAreTheDesignedSeven() {
        Set<String> pairs = new HashSet<>();
        for (TechNodeDef node : DATA.nodes()) {
            for (String ex : node.excludes()) {
                pairs.add(node.id().compareTo(ex) < 0 ? node.id() + "|" + ex : ex + "|" + node.id());
            }
        }
        assertEquals(Set.of("archer_longbow_drill|crossbows", "earthworks|palisade",
            "knights|pike_square", "porters_guild|runners_guild", "charcoal_kilns|deep_mine",
            "alehouse|wayside_shrine", "cathedral|hall_of_revels"), pairs);
    }
}
