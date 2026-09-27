package com.hearthstead.entity;

import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.entity.ai.GuardMeleeGoal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enemy health budget (owner request 26 Sep: "a bit more health"): hits-to-kill
 * for a new Guard with an iron sword and for a player's iron sword, from
 * compile-time constants only, so it runs without a Minecraft bootstrap. The
 * live proof is RaidHealthBalanceGameTests.
 */
class RaiderHealthBudgetTest {
    /** SettlerEntity's base ATTACK_DAMAGE attribute. */
    private static final double SETTLER_BASE_ATTACK = 4.0;
    /** The iron sword's main-hand ATTACK_DAMAGE modifier. */
    private static final double IRON_SWORD = 5.0;
    /** A player's iron sword: 1 base + 5. */
    private static final double PLAYER_IRON_SWORD = 6.0;

    private static double afterArmour(double damage) {
        double armour = RaiderEntity.VARIANT_ARMOR;
        double reduced = Math.min(20.0, Math.max(armour / 5.0, armour - damage / 2.0));
        return damage * (1.0 - reduced / 25.0);
    }

    private static double guardLight(boolean brute) {
        double raw = (SETTLER_BASE_ATTACK + IRON_SWORD + GuardMeleeGoal.GUARD_TRAINING_DAMAGE)
            * HearthsteadServerConfig.DEFAULT_GUARD_LIGHT_DAMAGE_MULTIPLIER
            * (brute ? GuardMeleeGoal.COUNTER_DAMAGE_MULTIPLIER : 1.0);
        return afterArmour(raw);
    }

    private static int hits(double health, double perHit) {
        return (int) Math.ceil(health / perHit - 1e-9);
    }

    @Test
    void chosenDefaultsArePinned() {
        assertEquals(28.0, RaiderEntity.SKIRMISHER_MAX_HEALTH);
        assertEquals(70.0, RaiderEntity.BRUTE_MAX_HEALTH);
        assertEquals(20.0, RaiderEntity.GOBLIN_THIEF_MAX_HEALTH);
    }

    @Test
    void skirmisherTakesAFewBlows() {
        assertEquals(3, hits(RaiderEntity.SKIRMISHER_MAX_HEALTH, guardLight(false)));
        int player = hits(RaiderEntity.SKIRMISHER_MAX_HEALTH, afterArmour(PLAYER_IRON_SWORD));
        assertTrue(player >= 4 && player <= 6, "player iron-sword hits: " + player);
    }

    @Test
    void bruteIsAFocusFireTarget() {
        int guard = hits(RaiderEntity.BRUTE_MAX_HEALTH, guardLight(true));
        assertTrue(guard >= 4 && guard <= 6, "Brute guard light hits: " + guard);
        int player = hits(RaiderEntity.BRUTE_MAX_HEALTH, afterArmour(PLAYER_IRON_SWORD));
        assertTrue(player >= 11 && player <= 14, "Brute player iron-sword hits: " + player);
    }

    @Test
    void goblinThiefSurvivesAFewPlayerBlows() {
        assertTrue(hits(RaiderEntity.GOBLIN_THIEF_MAX_HEALTH, PLAYER_IRON_SWORD) >= 3);
        assertEquals(2, hits(RaiderEntity.GOBLIN_THIEF_MAX_HEALTH, guardLight(false)));
    }

    @Test
    void menaceAtMostDoublesHealth() {
        assertEquals(2.0, 1.0 + (RaiderEntity.MAX_MENACE - 1.0) * RaiderEntity.MENACE_HEALTH_PER_POINT,
            1e-9);
    }
    /** Permadeath re-measure (W26f): the raid-1 outlaw captain hits like a war-band regular, not a war captain. */
    @Test
    void outlawCaptainHitsNoHarderThanAWarBandRegular() {
        assertEquals(3.0, RaiderEntity.BANDIT_ATTACK_DAMAGE + RaiderEntity.BANDIT_CAPTAIN_DAMAGE_BONUS);
        assertTrue(RaiderEntity.BANDIT_CAPTAIN_DAMAGE_BONUS < RaiderEntity.CAPTAIN_DAMAGE_BONUS);
        assertTrue(RaiderEntity.BANDIT_ATTACK_DAMAGE + RaiderEntity.BANDIT_CAPTAIN_DAMAGE_BONUS
            <= RaiderEntity.VARIANT_ATTACK_DAMAGE);
    }
}
