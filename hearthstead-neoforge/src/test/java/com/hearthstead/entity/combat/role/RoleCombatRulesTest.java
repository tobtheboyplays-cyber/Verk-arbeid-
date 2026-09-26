package com.hearthstead.entity.combat.role;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spear reach/brace/flank and longsword cleave rules (plan/BATTLE-ROLES.md §1-2). */
class RoleCombatRulesTest {

    @Test
    void spearReachIsOneAndAHalfTimesMelee() {
        double melee = RoleCombatRules.reach(0.6D, 0.6D, 1.0D);
        double spear = RoleCombatRules.reach(0.6D, 0.6D, RoleMove.SPEAR_THRUST.reachScale());
        assertEquals(1.5D, spear / melee, 1.0E-9D);
        assertTrue(RoleCombatRules.inReach(2.0D * 2.0D, 0.6D, 0.6D, 1.5D));
        assertFalse(RoleCombatRules.inReach(2.0D * 2.0D, 0.6D, 0.6D, 1.0D));
    }

    @Test
    void yawMathWrapsAndMatchesMinecraft() {
        assertEquals(10.0F, RoleCombatRules.yawDifference(355.0F, 5.0F), 1.0E-4F);
        assertEquals(180.0F, RoleCombatRules.yawDifference(0.0F, 180.0F), 1.0E-4F);
        // Minecraft: yaw 0 faces +Z.
        assertEquals(0.0F, RoleCombatRules.yawDifference(
            RoleCombatRules.yawToward(0, 0, 0, 5), 0.0F), 1.0E-3F);
    }

    @Test
    void chargeReadNeedsClosingSpeed() {
        double closing = RoleCombatRules.closingSpeed(0, 0, 5, 0, -0.2D, 0);
        assertEquals(0.2D, closing, 1.0E-9D);
        assertTrue(RoleCombatRules.isCharging(closing, false));
        double walking = RoleCombatRules.closingSpeed(0, 0, 5, 0, -0.09D, 0);
        assertFalse(RoleCombatRules.isCharging(walking, false));
        assertTrue(RoleCombatRules.isCharging(walking, true), "a brute's lumber still counts");
        double away = RoleCombatRules.closingSpeed(0, 0, 5, 0, 0.3D, 0);
        assertFalse(RoleCombatRules.isCharging(away, true));
    }

    @Test
    void flankIsMoreThanAHundredDegreesOffFacing() {
        assertFalse(RoleCombatRules.isFlankHit(0.0F, 30.0F));
        assertFalse(RoleCombatRules.isFlankHit(0.0F, 100.0F));
        assertTrue(RoleCombatRules.isFlankHit(0.0F, 101.0F));
        assertTrue(RoleCombatRules.isFlankHit(90.0F, 270.0F), "from behind");
    }

    @Test
    void braceStrikeOnlyOnAChargerWhileBraced() {
        assertEquals(RoleMove.SPEAR_BRACE_STRIKE, RoleCombatRules.chooseSpearMove(
            new RoleCombatRules.SpearSituation(true, true, true, 0), 0.9D));
        assertEquals(RoleMove.SPEAR_THRUST, RoleCombatRules.chooseSpearMove(
            new RoleCombatRules.SpearSituation(true, true, false, 0), 0.9D), "brace strike on cooldown");
        assertEquals(RoleMove.SPEAR_THRUST, RoleCombatRules.chooseSpearMove(
            new RoleCombatRules.SpearSituation(false, true, true, 0), 0.9D), "not braced");
        assertEquals(RoleMove.SPEAR_DOUBLE_THRUST, RoleCombatRules.chooseSpearMove(
            new RoleCombatRules.SpearSituation(false, false, true, 2), 0.1D));
    }

    @Test
    void longswordBreaksGuardsPunishesOpeningsCleavesCrowds() {
        assertEquals(RoleMove.LONGSWORD_HALF_SWORD, RoleCombatRules.chooseLongswordMove(
            new RoleCombatRules.SwordSituation(true, true, false, false, 1), 0.9D));
        assertEquals(RoleMove.LONGSWORD_HEAVY, RoleCombatRules.chooseLongswordMove(
            new RoleCombatRules.SwordSituation(false, true, true, false, 3), 0.9D));
        assertEquals(RoleMove.LONGSWORD_CLEAVE, RoleCombatRules.chooseLongswordMove(
            new RoleCombatRules.SwordSituation(false, true, false, true, 2), 0.1D));
        assertEquals(RoleMove.LONGSWORD_HEAVY, RoleCombatRules.chooseLongswordMove(
            new RoleCombatRules.SwordSituation(false, true, false, true, 1), 0.1D));
    }

    @Test
    void cleaveHitsEachEnemyAtMostOncePrimaryFirstCappedAtThree() {
        List<RoleCombatRules.Candidate<String>> c = List.of(
            new RoleCombatRules.Candidate<>("far", 3.0D, 10.0F, false),
            new RoleCombatRules.Candidate<>("primary", 2.0D, 0.0F, true),
            new RoleCombatRules.Candidate<>("near", 1.0D, -20.0F, false),
            new RoleCombatRules.Candidate<>("near", 1.0D, -20.0F, false),
            new RoleCombatRules.Candidate<>("behind", 0.5D, 180.0F, false),
            new RoleCombatRules.Candidate<>("fourth", 3.5D, 30.0F, false));
        List<String> hits = RoleCombatRules.cleaveTargets(c, 0.0F,
            RoleMove.LONGSWORD_CLEAVE.arcHalfDegrees(), 16.0D, RoleMove.LONGSWORD_CLEAVE.maxTargets());
        assertEquals(List.of("primary", "near", "far"), hits);
    }

    @Test
    void cleaveOutOfReachIsDropped() {
        List<RoleCombatRules.Candidate<String>> c = List.of(
            new RoleCombatRules.Candidate<>("primary", 2.0D, 0.0F, true),
            new RoleCombatRules.Candidate<>("toofar", 20.0D, 0.0F, false));
        assertEquals(List.of("primary"), RoleCombatRules.cleaveTargets(c, 0.0F, 70.0F, 9.0D, 3));
    }

    @Test
    void moveDataIsSane() {
        for (RoleMove m : RoleMove.values()) {
            assertTrue(m.hitTick() > 0 && m.hitTick() < m.lengthTicks(), m + " contact inside the clip");
            for (int t : m.contactTicks()) {
                assertTrue(t < m.lengthTicks(), m + " every contact inside the clip");
            }
        }
        assertEquals(2, RoleMove.SPEAR_DOUBLE_THRUST.contactTicks().length);
        assertTrue(RoleMove.LONGSWORD_HALF_SWORD.breaksGuard());
        assertTrue(RoleMove.LONGSWORD_CLEAVE.cleaves());
    }
}
