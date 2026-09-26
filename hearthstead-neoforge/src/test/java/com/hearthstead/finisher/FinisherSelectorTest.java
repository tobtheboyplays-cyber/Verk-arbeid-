package com.hearthstead.finisher;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FinisherSelectorTest {

    @Test
    void atLeastEightSoloPlayerFinishers() {
        long solo = EnumSet.allOf(FinisherVariant.class).stream().filter(v -> !v.isDouble()).count();
        assertTrue(solo >= 8, "spec: at least 8 player finisher variants, have " + solo);
    }

    @Test
    void everyWeaponAndEnemyPairHasAFittingMove() {
        for (WeaponClass weapon : WeaponClass.values()) {
            for (EnemyClass enemy : EnemyClass.values()) {
                List<FinisherVariant> pool = FinisherSelector.candidates(weapon, enemy);
                assertFalse(pool.isEmpty(), weapon + " x " + enemy);
                for (FinisherVariant v : pool) {
                    assertFalse(v.isDouble(), "the double is never a solo pick");
                    assertTrue(v.enemies().contains(enemy),
                        v + " is not authored for " + enemy + " (" + weapon + ")");
                }
            }
        }
    }

    @Test
    void weaponsGetTheirOwnChoreography() {
        assertTrue(FinisherSelector.candidates(WeaponClass.SWORD, EnemyClass.SKIRMISHER)
            .contains(FinisherVariant.SWORD_PARRY_THRUST), "sword vs skirmisher: parry-shove then thrust");
        assertTrue(FinisherSelector.candidates(WeaponClass.AXE, EnemyClass.SKIRMISHER)
            .contains(FinisherVariant.AXE_HOOK_CHOP), "axe vs skirmisher: hook the leg, overhead chop");
        Set<FinisherVariant> brute = Set.copyOf(FinisherSelector.candidates(WeaponClass.SWORD, EnemyClass.BRUTE));
        assertTrue(brute.contains(FinisherVariant.BRUTE_CLIMB_STRIKE));
        assertTrue(brute.contains(FinisherVariant.BRUTE_KNEE_BUCKLE));
        assertEquals(List.of(FinisherVariant.BRUTE_KNEE_BUCKLE),
            FinisherSelector.candidates(WeaponClass.MACE, EnemyClass.BRUTE));
        assertEquals(List.of(FinisherVariant.GOBLIN_SCRUFF_SLAM),
            FinisherSelector.candidates(WeaponClass.AXE, EnemyClass.GOBLIN));
        assertEquals(List.of(FinisherVariant.BARE_COLLAR_THROW),
            FinisherSelector.candidates(WeaponClass.BARE, EnemyClass.SKIRMISHER));
        assertTrue(FinisherSelector.candidates(WeaponClass.SWORD, EnemyClass.CAPTAIN)
            .contains(FinisherVariant.CAPTAIN_DISARM_DRIVE));
        assertFalse(FinisherSelector.candidates(WeaponClass.SWORD, EnemyClass.SKIRMISHER)
            .contains(FinisherVariant.BRUTE_CLIMB_STRIKE), "nobody climbs a skirmisher");
    }

    @Test
    void neverRepeatsThePreviousMoveWhenThereIsAChoice() {
        Random random = new Random(7);
        FinisherVariant previous = null;
        for (int i = 0; i < 200; i++) {
            FinisherVariant pick = FinisherSelector.pick(WeaponClass.SWORD, EnemyClass.SKIRMISHER,
                previous, random::nextInt);
            assertNotNull(pick);
            assertNotEquals(previous, pick);
            previous = pick;
        }
    }

    @Test
    void singleChoiceStillReturnsThatMove() {
        FinisherVariant pick = FinisherSelector.pick(WeaponClass.MACE, EnemyClass.BRUTE,
            FinisherVariant.BRUTE_KNEE_BUCKLE, bound -> 0);
        assertEquals(FinisherVariant.BRUTE_KNEE_BUCKLE, pick);
    }

    @Test
    void weightedPickCoversEveryCandidate() {
        Set<FinisherVariant> seen = EnumSet.noneOf(FinisherVariant.class);
        Random random = new Random(11);
        for (int i = 0; i < 400; i++) {
            seen.add(FinisherSelector.pick(WeaponClass.SWORD, EnemyClass.OTHER, null, random::nextInt));
        }
        assertEquals(Set.copyOf(FinisherSelector.candidates(WeaponClass.SWORD, EnemyClass.OTHER)), seen);
    }

    @Test
    void deterministicWithInjectedRandomness() {
        FinisherVariant a = FinisherSelector.pick(WeaponClass.AXE, EnemyClass.SKIRMISHER, null, bound -> 0);
        FinisherVariant b = FinisherSelector.pick(WeaponClass.AXE, EnemyClass.SKIRMISHER, null, bound -> 0);
        assertEquals(a, b);
    }
}
