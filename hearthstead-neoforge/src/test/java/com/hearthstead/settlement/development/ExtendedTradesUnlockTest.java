package com.hearthstead.settlement.development;

import com.hearthstead.entity.Profession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Trades-unlock lane (26 Sep): every one of the 15 formerly admin-only
 * professions has a reachable survival path -- an implemented node whose
 * whole prerequisite chain is implemented, which grants the profession, and a
 * Mayor emblem gated on that node. With {@code [features] extendedTrades}
 * off, the old behaviour (FUTURE nodes, no emblem) must hold exactly.
 */
class ExtendedTradesUnlockTest {

    static final List<Profession> EXTENDED = List.of(
        Profession.MILLER, Profession.HERDER, Profession.BAKER, Profession.BUTCHER,
        Profession.MINER, Profession.CARPENTER, Profession.MASON, Profession.SMELTER,
        Profession.SMITH, Profession.TANNER, Profession.WEAVER,
        Profession.COOK, Profession.BREWER,
        Profession.ARMOURER, Profession.FLETCHER);

    @AfterEach
    void restoreSwitch() {
        ExtendedTrades.overrideForTests(null);
    }

    @Test
    void switchDefaultsOn() {
        assertTrue(ExtendedTrades.enabled(),
            "an unloaded server config must fall back to extendedTrades = true");
    }

    @Test
    void everyExtendedTradeHasAReachableUnlockPath() {
        for (Profession profession : EXTENDED) {
            JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
            assertNotNull(entry, profession + " must be sold by the Mayor");
            DevelopmentNode node = entry.unlock();
            assertTrue(node.extendedTrade(), profession + " rides on a specialization");
            assertTrue(node.implemented(), node.id() + " must be learnable");
            assertTrue(node.knowledge().jobEmblems().contains(profession),
                node.id() + " must grant " + profession);
            assertFalse(node.knowledge().buildPlans().isEmpty(),
                node.id() + " must grant the workplace plans");
            assertFalse(node.materialCosts().isEmpty(),
                node.id() + " must have a real price");
            assertReachable(node);
        }
    }

    @Test
    void everySpecializationEmblemIsInTheCatalogAndPricedInTheTradeBand() {
        Set<Profession> seen = EnumSet.noneOf(Profession.class);
        for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
            if (!node.extendedTrade()) {
                continue;
            }
            for (Profession profession : node.professions()) {
                JobEmblemCatalog.Entry entry = JobEmblemCatalog.forProfession(profession);
                assertNotNull(entry, node.id() + " must not promise an unsold " + profession);
                assertEquals(node, entry.unlock(), profession + " unlock node");
                assertEquals(2, entry.coinPrice(),
                    profession + " is a plain trade emblem (2 Coins)");
                assertFalse(entry.goods().isEmpty(), profession + " goods");
                seen.add(profession);
            }
        }
        assertEquals(EnumSet.copyOf(EXTENDED), seen,
            "the four specializations grant exactly the 15 extended trades");
    }

    @Test
    void specializationPricesFollowTheDesignTree() {
        assertEquals(8, DevelopmentNode.FORTIFICATION.coinCost());
        assertEquals(8, DevelopmentNode.CRAFT_AND_INDUSTRY.coinCost());
        assertEquals(6, DevelopmentNode.LAND_AND_HARVEST.coinCost());
        assertEquals(6, DevelopmentNode.HALL_AND_LEARNING.coinCost());
    }

    @Test
    void switchOffRestoresTheOldBehaviour() {
        ExtendedTrades.overrideForTests(false);
        for (Profession profession : EXTENDED) {
            assertNull(JobEmblemCatalog.forProfession(profession),
                profession + " must not be sold with extendedTrades off");
            assertFalse(JobEmblemCatalog.releaseReady(profession));
        }
        int base = 0;
        for (JobEmblemCatalog.Entry entry : JobEmblemCatalog.RELEASE_CATALOG) {
            if (!entry.unlock().extendedTrade()) {
                base++;
                assertNotNull(JobEmblemCatalog.forProfession(entry.profession()),
                    entry.profession() + " stays on sale");
            }
        }
        assertEquals(16, base, "the old sixteen-emblem shop is untouched");
        for (DevelopmentNode node : DevelopmentNode.PRESENTATION_ORDER) {
            assertEquals(!node.extendedTrade(), node.implemented(),
                node.id() + " implemented with the switch off");
        }
    }

    /** The node and every prerequisite (transitively) are learnable. */
    private static void assertReachable(DevelopmentNode node) {
        ArrayDeque<DevelopmentNode> open = new ArrayDeque<>();
        Set<DevelopmentNode> visited = new HashSet<>();
        open.add(node);
        while (!open.isEmpty()) {
            DevelopmentNode current = open.pop();
            if (!visited.add(current)) {
                continue;
            }
            assertTrue(current.implemented(),
                current.id() + " (on the path to " + node.id() + ") must be implemented");
            for (String id : current.prerequisites()) {
                DevelopmentNode required = DevelopmentNode.byId(id);
                assertNotNull(required, current.id() + " requires unknown " + id);
                open.add(required);
            }
        }
        assertTrue(visited.contains(DevelopmentNode.SETTLEMENT_CHARTER),
            node.id() + " must chain back to the Settlement Charter");
    }
}
