package com.hearthstead.entity.combat.captain;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Hero Captain: brain rules, cooldowns, once-per-fight, re-arm, persistence (plan/CAPTAIN.md). */
class CaptainRulesTest {

    private static CaptainBrain.Situation s(float hp, boolean swUsed, int fight, double[] enemies,
                                            double targetDist, boolean strong, boolean guarded,
                                            float targetHp, boolean charging, int heavyIn, boolean civ) {
        return new CaptainBrain.Situation(hp, swUsed, fight, enemies, targetDist, strong, guarded,
            targetHp, charging, heavyIn, civ);
    }

    private static final CaptainBrain.Ready ALL = sp -> true;

    @Test
    void ownerRulesPickTheRightSpecial() {
        // Civilians threatened: Hold the Line (sword & shield).
        assertEquals(CaptainSpecial.HOLD_THE_LINE, CaptainBrain.choose(CaptainLoadout.SWORD_SHIELD,
            s(1F, false, 500, new double[] {5}, 5, false, false, 1F, false, -1, true), ALL));
        // Fight start with a crowd: Rally.
        assertEquals(CaptainSpecial.RALLY_CRY, CaptainBrain.choose(CaptainLoadout.GREAT_AXE,
            s(1F, false, 10, new double[] {5, 6, 9}, 5, false, false, 1F, false, -1, false), ALL));
        // Three close enemies later in the fight: the crowd attack.
        assertEquals(CaptainSpecial.SPINNING_CHOP, CaptainBrain.choose(CaptainLoadout.GREAT_AXE,
            s(1F, false, 500, new double[] {1, 2, 3}, 1, false, false, 1F, false, -1, false), ALL));
        // A brute: the anti-brute move.
        assertEquals(CaptainSpecial.POMMEL_STUN, CaptainBrain.choose(CaptainLoadout.SWORD_SHIELD,
            s(1F, false, 500, new double[] {2}, 2, true, false, 1F, false, -1, false), ALL));
        assertEquals(CaptainSpecial.GROUND_SLAM, CaptainBrain.choose(CaptainLoadout.WARHAMMER,
            s(1F, false, 500, new double[] {2}, 2, true, false, 1F, false, -1, false), ALL));
        // Badly hurt: Second Wind first.
        assertEquals(CaptainSpecial.SECOND_WIND, CaptainBrain.choose(CaptainLoadout.BOW,
            s(0.2F, false, 500, new double[] {5}, 5, false, false, 1F, false, -1, true), ALL));
        // A finished enemy in reach: Execution.
        assertEquals(CaptainSpecial.EXECUTION, CaptainBrain.choose(CaptainLoadout.DUAL_SWORDS,
            s(1F, false, 500, new double[] {2}, 2, false, false, 0.2F, false, -1, false), ALL));
        // An incoming heavy: the dual-sword dodge.
        assertEquals(CaptainSpecial.DODGE_STEP, CaptainBrain.choose(CaptainLoadout.DUAL_SWORDS,
            s(1F, false, 500, new double[] {2}, 2, false, false, 1F, false, 4, false), ALL));
        // Target at range: the charge closes the gap.
        assertEquals(CaptainSpecial.SHIELD_CHARGE, CaptainBrain.choose(CaptainLoadout.SWORD_SHIELD,
            s(1F, false, 500, new double[] {6}, 6, false, false, 1F, false, -1, false), ALL));
        // Nothing fits: nothing.
        assertNull(CaptainBrain.choose(CaptainLoadout.BOW,
            s(1F, false, 500, new double[] {30}, 30, false, false, 1F, false, -1, false), ALL));
    }

    @Test
    void everyLoadoutsSpecialsAreInItsOwnOrderAndOnlyThere() {
        for (CaptainLoadout l : CaptainLoadout.values()) {
            Set<CaptainSpecial> order = EnumSet.noneOf(CaptainSpecial.class);
            order.addAll(java.util.List.of(CaptainBrain.order(l)));
            for (CaptainSpecial own : l.specials()) {
                assertTrue(order.contains(own), l + " never picks " + own);
            }
            for (CaptainSpecial sp : order) {
                assertTrue(sp.loadout() == null || sp.loadout() == l, l + " would pick foreign " + sp);
            }
        }
    }

    @Test
    void cooldownLoadoutAndRearmGateReadiness() {
        CaptainState st = new CaptainState();
        assertTrue(st.ready(CaptainSpecial.SHIELD_CHARGE, 0));
        assertFalse(st.ready(CaptainSpecial.BLADE_WHIRL, 0), "not the dual-sword loadout");
        st.fire(CaptainSpecial.SHIELD_CHARGE, 100);
        assertFalse(st.ready(CaptainSpecial.SHIELD_CHARGE, 100 + CaptainSpecial.SHIELD_CHARGE.cooldownTicks() - 1));
        assertTrue(st.ready(CaptainSpecial.SHIELD_CHARGE, 100 + CaptainSpecial.SHIELD_CHARGE.cooldownTicks()));
        st.switchTo(CaptainLoadout.DUAL_SWORDS, 1000);
        assertFalse(st.ready(CaptainSpecial.BLADE_WHIRL, 1000 + CaptainState.REARM_TICKS - 1), "re-arming");
        assertTrue(st.ready(CaptainSpecial.BLADE_WHIRL, 1000 + CaptainState.REARM_TICKS));
        assertFalse(st.ready(CaptainSpecial.SHIELD_CHARGE, 5000), "old loadout's special is gone");
    }

    @Test
    void secondWindOncePerFightResetsAfterAGap() {
        CaptainState st = new CaptainState();
        st.sawEnemy(0);
        assertTrue(st.ready(CaptainSpecial.SECOND_WIND, 10));
        st.fire(CaptainSpecial.SECOND_WIND, 10);
        assertFalse(st.ready(CaptainSpecial.SECOND_WIND, 20));
        st.sawEnemy(100);
        assertFalse(st.ready(CaptainSpecial.SECOND_WIND, 110), "same fight");
        st.sawEnemy(100 + CaptainSpecial.FIGHT_GAP_TICKS + 1);
        assertTrue(st.ready(CaptainSpecial.SECOND_WIND, 800), "a new fight restores it");
        assertEquals(0, st.fightTicks(100 + CaptainSpecial.FIGHT_GAP_TICKS + 1));
    }

    @Test
    void stateRoundTrips() {
        CaptainState st = new CaptainState();
        st.switchTo(CaptainLoadout.BOW, 0);
        st.fire(CaptainSpecial.ARROW_VOLLEY, 50);
        st.setCapeColour(14);
        st.setPlume(false);
        st.setPromptPending(true);
        CaptainState back = CaptainState.load(st.save());
        assertEquals(CaptainLoadout.BOW, back.loadout());
        assertFalse(back.ready(CaptainSpecial.ARROW_VOLLEY, 100));
        assertEquals(14, back.capeColour());
        assertFalse(back.plume());
        assertTrue(back.promptPending());
    }

    @Test
    void namesAreSanitised() {
        assertEquals("Ser Aldric", CaptainService.sanitizeName("  Ser §cAldric\n "));
        assertEquals(CaptainPayloads.MAX_NAME, CaptainService.sanitizeName("x".repeat(60)).length());
        assertEquals("", CaptainService.sanitizeName("§§  "));
    }

    @Test
    void everySpecialHasAHookAndSaneTiming() {
        for (CaptainSpecial sp : CaptainSpecial.values()) {
            assertTrue(sp.fxId().startsWith("captain."));
            assertTrue(sp.windupTicks() >= 0 && sp.windupTicks() <= 20, sp + " telegraph");
            assertTrue(sp.oncePerFight() || sp.cooldownTicks() >= 100, sp + " has a real cooldown");
        }
    }
}
