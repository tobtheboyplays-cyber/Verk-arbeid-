package com.hearthstead.entity.combat;

import com.hearthstead.entity.ai.GuardMeleeGoal;
import com.hearthstead.entity.ai.RaiderMeleeGoal;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** World-free contract for the guard/raider moveset data and move choice. */
class GuardMovesetTest {

    private static GuardMoveSelector.Situation ordinary(int rank) {
        return new GuardMoveSelector.Situation(false, -1, false, false, rank,
            false, true, false);
    }

    // ------------------------------------------------------------- data

    @Test
    void everyMoveHasWindupBeforeContactAndRecoveryAfter() {
        for (GuardMove move : GuardMove.values()) {
            assertTrue(move.hitTick() >= 1, move + " must never hit at start");
            assertTrue(move.recoveryTicks() > 0, move + " needs recovery");
            assertEquals(move.lengthTicks(), move.hitTick() + move.recoveryTicks());
            assertEquals(move.authoredLengthTicks(), move.lengthTicks(),
                move + " default length must equal the authored clip");
            assertTrue(move.reachScale() > 0.0D && move.reachScale() <= 1.0D);
            assertTrue(move.arcHalfDegrees() > 0.0F && move.arcHalfDegrees() <= 90.0F);
            assertEquals(1.0F, GuardMove.windupPlaybackScale(move), 1.0E-6F);
        }
        for (RaiderMove move : RaiderMove.values()) {
            assertTrue(move.hitTick() >= 1 && move.recoveryTicks() > 0, move.name());
            assertEquals(move.authoredLengthTicks(), move.lengthTicks());
        }
    }

    @Test
    void lightSlashKeepsTheAuthoredMeleeContactAndHistoricalCadence() {
        assertEquals(GuardMeleeGoal.MELEE_CONTACT_TICK, GuardMove.LIGHT_A.hitTick());
        assertEquals(10, GuardMove.LIGHT_A.lengthTicks(), "MELEE is 0.50 s");
        assertEquals(20, GuardMove.LIGHT_A.cadenceTicks(),
            "a single light keeps the old 20-tick guard cadence");
        assertEquals(1.0D, GuardMove.LIGHT_A.damageMultiplier());
        assertFalse(GuardMove.LIGHT_A.staggers());
    }

    @Test
    void lightWindupIsShortAndHeavyIsTelegraphed() {
        assertTrue(GuardMove.LIGHT_A.windupTicks() <= 5);
        assertTrue(GuardMove.HEAVY.windupTicks() >= 10 && GuardMove.HEAVY.windupTicks() <= 12);
        assertEquals(1.8D, GuardMove.HEAVY.damageMultiplier(), 1.0E-9);
        assertTrue(GuardMove.HEAVY.knockback() > GuardMove.LIGHT_A.knockback());
        assertTrue(GuardMove.HEAVY.staggers());
        assertTrue(GuardMove.HEAVY.arcHalfDegrees() < GuardMove.LIGHT_A.arcHalfDegrees(),
            "the committed chop is narrower than a light slash");
    }

    @Test
    void comboIsLightLightFinisherAndFinisherReusesTheDriveContact() {
        assertEquals(GuardMove.LIGHT_A, GuardMove.comboLink(0));
        assertEquals(GuardMove.LIGHT_B, GuardMove.comboLink(1));
        assertEquals(GuardMove.COMBO_FINISHER, GuardMove.comboLink(2));
        assertThrows(IllegalArgumentException.class, () -> GuardMove.comboLink(3));
        assertEquals(4, GuardMove.COMBO_FINISHER.hitTick(),
            "GUARD_FINISHER_DRIVE contacts at t=0.20 s");
        assertEquals(14, GuardMove.COMBO_FINISHER.lengthTicks(), "the drive is 0.70 s");
        assertTrue(GuardMove.LIGHT_A.chains() && GuardMove.LIGHT_B.chains());
        assertFalse(GuardMove.COMBO_FINISHER.chains() || GuardMove.HEAVY.chains()
            || GuardMove.SHIELD_BASH.chains());
        // Each chain point sits after contact and before the clip ends.
        for (GuardMove link : new GuardMove[] {GuardMove.LIGHT_A, GuardMove.LIGHT_B}) {
            assertTrue(link.chainTick() > link.hitTick()
                && link.chainTick() <= link.lengthTicks());
        }
    }

    @Test
    void shieldBashIsShortRangeFastAndStaggers() {
        GuardMove bash = GuardMove.SHIELD_BASH;
        assertTrue(bash.reachScale() < 1.0D);
        assertTrue(bash.hitTick() < GuardMove.LIGHT_A.hitTick(),
            "the bash must beat even a light to the punch");
        assertTrue(bash.staggers());
        assertTrue(bash.damageMultiplier() < 1.0D);
    }

    @Test
    void raiderPairTimingMatchesTheAuthoredClips() {
        assertEquals(RaiderMeleeGoal.BRUTE_CLUB_CONTACT_TICK, RaiderMove.CLUB.hitTick());
        assertEquals(18, RaiderMove.CLUB.hitTick(), "BRUTE_CLUB_STRIKE contacts at 0.90 s");
        assertEquals(34, RaiderMove.CLUB.lengthTicks(), "BRUTE_CLUB_STRIKE is 1.70 s");
        assertEquals(3, RaiderMove.LIGHT.hitTick(), "RAIDER_LIGHT jab contacts at 0.15 s");
        assertEquals(8, RaiderMove.LIGHT.lengthTicks(), "RAIDER_LIGHT is 0.40 s");
        assertEquals(24, RaiderMove.HEAVY.hitTick(), "RAIDER_HEAVY contacts at 1.20 s");
        assertEquals(44, RaiderMove.HEAVY.lengthTicks(), "RAIDER_HEAVY is 2.20 s");
        assertEquals(20, RaiderMove.LIGHT.cadenceTicks());
        for (RaiderMove crushing : new RaiderMove[] {RaiderMove.CLUB, RaiderMove.HEAVY}) {
            assertTrue(crushing.crushing() && crushing.slamsGround(true));
            assertFalse(crushing.slamsGround(false), "only a Brute slams the ground");
            assertTrue(crushing.hitTick() >= 18,
                "a crushing blow must be readable: at least 0.9 s of wind-up");
            assertTrue(crushing.recoveryTicks() > GuardMove.HEAVY.hitTick(),
                "a whiffed slam leaves time for a guard heavy to punish it");
            assertTrue(crushing.hitTick() > GuardMove.SHIELD_BASH.hitTick() + 1,
                "a guard must have time to read and bash it");
            assertTrue(crushing.staggerTicks() > crushing.blockedStaggerTicks()
                && crushing.blockedStaggerTicks() > 0,
                "a raised shield halves the stagger but does not remove it");
            assertTrue(crushing.slamRadius() >= 2.5D && crushing.slamRadius() <= 3.0D);
        }
        assertFalse(RaiderMove.LIGHT.crushing());
        double heavyOverLight = RaiderMove.HEAVY.damageMultiplier()
            / RaiderMove.LIGHT.damageMultiplier();
        assertTrue(heavyOverLight >= 2.2D && heavyOverLight <= 2.5D,
            "the heavy hits 2.2-2.5x the light, was " + heavyOverLight);
        assertTrue(RaiderMove.HEAVY.knockback() > RaiderMove.CLUB.knockback());
    }

    @Test
    void slamFalloffIsFullAtTheImpactAndFadesToTheRim() {
        assertEquals(1.0D, RaiderMove.slamFalloff(0.0D, 3.0D), 1.0E-9);
        assertEquals(0.3D, RaiderMove.slamFalloff(3.0D, 3.0D), 1.0E-9);
        assertEquals(0.0D, RaiderMove.slamFalloff(3.01D, 3.0D));
        assertTrue(RaiderMove.slamFalloff(1.0D, 3.0D) > RaiderMove.slamFalloff(2.0D, 3.0D));
    }

    @Test
    void weaponTypeDrivesTimingChainingAndReach() {
        for (WeaponClass sword : new WeaponClass[] {WeaponClass.SWORD,
                WeaponClass.SHORT_SWORD, WeaponClass.AXE, null}) {
            for (GuardMove move : GuardMove.values()) {
                assertEquals(move.hitTick(), move.hitTick(sword),
                    "sword-type weapons keep the authored moveset");
                assertEquals(move.lengthTicks(), move.lengthTicks(sword));
            }
            assertEquals(1.0D, GuardMove.weaponReach(sword));
        }
        assertTrue(GuardMove.LIGHT_A.chains(WeaponClass.SHORT_SWORD));
        for (WeaponClass heavy : new WeaponClass[] {WeaponClass.LONGSWORD,
                WeaponClass.SPEAR, WeaponClass.GREAT_AXE, WeaponClass.HALBERD,
                WeaponClass.WARHAMMER}) {
            assertEquals(heavy.contactTick(), GuardMove.LIGHT_A.hitTick(heavy),
                heavy + " plain swing contacts on its own clip");
            assertEquals(heavy.swingLength(), GuardMove.LIGHT_A.lengthTicks(heavy));
            assertFalse(GuardMove.LIGHT_A.chains(heavy), heavy + " never chains a combo");
            assertFalse(GuardMove.allowsShieldBash(heavy), heavy + " has no hand for a bash");
            assertTrue(GuardMove.HEAVY.hitTick(heavy) >= heavy.contactTick()
                && GuardMove.HEAVY.hitTick(heavy) >= GuardMove.HEAVY.hitTick());
            assertEquals(GuardMove.HEAVY.recoveryTicks(),
                GuardMove.HEAVY.lengthTicks(heavy) - GuardMove.HEAVY.hitTick(heavy));
        }
        assertEquals(9, GuardMove.LIGHT_A.hitTick(WeaponClass.GREAT_AXE));
        assertEquals(12, GuardMove.HEAVY.hitTick(WeaponClass.WARHAMMER));
        assertEquals(1.5D, GuardMove.weaponReach(WeaponClass.HALBERD));
        assertEquals(1.5D, GuardMove.weaponReach(WeaponClass.SPEAR));
    }

    @Test
    void heavyWeaponsBiteHarderIntoBrutesOnly() {
        assertEquals(1.35D, GuardMeleeGoal.heavyWeaponMultiplier(WeaponClass.GREAT_AXE,
            com.hearthstead.entity.RaiderEntity.Variant.BRUTE));
        assertEquals(1.35D, GuardMeleeGoal.heavyWeaponMultiplier(WeaponClass.WARHAMMER,
            com.hearthstead.entity.RaiderEntity.Variant.BRUTE));
        assertEquals(1.0D, GuardMeleeGoal.heavyWeaponMultiplier(WeaponClass.SWORD,
            com.hearthstead.entity.RaiderEntity.Variant.BRUTE));
        assertEquals(1.0D, GuardMeleeGoal.heavyWeaponMultiplier(WeaponClass.GREAT_AXE,
            com.hearthstead.entity.RaiderEntity.Variant.SKIRMISHER));
    }

    // ------------------------------------------------------------ choice

    @Test
    void bruteMostlyHeavySkirmisherOnlyJabs() {
        assertEquals(RaiderMove.HEAVY, RaiderMove.choose(true, false, 0.0D));
        assertEquals(RaiderMove.HEAVY, RaiderMove.choose(true, false, 0.69D));
        assertEquals(RaiderMove.CLUB, RaiderMove.choose(true, false, 0.71D));
        assertEquals(RaiderMove.HEAVY, RaiderMove.choose(false, true, 0.3D),
            "a captain leans on the heavy");
        for (int i = 0; i < 100; i++) {
            assertEquals(RaiderMove.LIGHT, RaiderMove.choose(false, false, i / 100.0D),
                "an ordinary skirmisher is a hit-and-run jabber");
        }
    }

    @Test
    void recruitsNeverComboAndVeteransComboMore() {
        assertEquals(0.0D, GuardMoveSelector.comboChance(0));
        for (int rank = 1; rank < 5; rank++) {
            assertTrue(GuardMoveSelector.comboChance(rank)
                > GuardMoveSelector.comboChance(rank - 1), "rank " + rank);
        }
        assertEquals(GuardMoveSelector.comboChance(4), GuardMoveSelector.comboChance(99));
        GuardMoveSelector.Choice recruit = GuardMoveSelector.chooseOpener(ordinary(0), 0.0D, 0.0D);
        assertEquals(GuardMove.LIGHT_A, recruit.move());
        assertFalse(recruit.planCombo());
        GuardMoveSelector.Choice veteran = GuardMoveSelector.chooseOpener(ordinary(2), 0.0D, 0.5D);
        assertEquals(GuardMove.LIGHT_A, veteran.move());
        assertTrue(veteran.planCombo());
        assertFalse(GuardMoveSelector.chooseOpener(ordinary(2), 0.0D, 0.6D).planCombo());
    }

    @Test
    void ordinaryTargetsAlwaysOpenLightSoTimingStaysDeterministic() {
        for (int rank = 0; rank < 5; rank++) {
            for (double roll = 0.0D; roll < 1.0D; roll += 0.05D) {
                assertEquals(GuardMove.LIGHT_A,
                    GuardMoveSelector.chooseOpener(ordinary(rank), roll, roll).move());
            }
        }
    }

    @Test
    void strongStaggeredOrBlockingTargetsDrawTheHeavy() {
        GuardMoveSelector.Situation strong = new GuardMoveSelector.Situation(true, -1,
            false, false, 0, false, true, false);
        assertEquals(GuardMove.HEAVY, GuardMoveSelector.chooseOpener(strong, 0.1D, 0.9D).move());
        assertEquals(GuardMove.LIGHT_A, GuardMoveSelector.chooseOpener(strong, 0.9D, 0.9D).move(),
            "a little randomness: not every brute swing is a heavy");
        GuardMoveSelector.Situation staggered = new GuardMoveSelector.Situation(false, -1,
            true, false, 0, false, true, false);
        assertEquals(GuardMove.HEAVY, GuardMoveSelector.chooseOpener(staggered, 0.99D, 0.0D).move());
        GuardMoveSelector.Situation blocking = new GuardMoveSelector.Situation(false, -1,
            false, true, 0, false, true, false);
        assertEquals(GuardMove.HEAVY, GuardMoveSelector.chooseOpener(blocking, 0.99D, 0.0D).move());
    }

    @Test
    void anEnemyHeavyIsBashedWhenPossibleOtherwiseDodgedWhenTooClose() {
        // Shield, bash ready, in bash reach, heavy 6 ticks out: bash lands first.
        GuardMoveSelector.Situation bashable = new GuardMoveSelector.Situation(true, 6,
            false, false, 0, true, true, true);
        GuardMoveSelector.Choice bash = GuardMoveSelector.chooseOpener(bashable, 0.0D, 0.0D);
        assertEquals(GuardMoveSelector.Action.ATTACK, bash.action());
        assertEquals(GuardMove.SHIELD_BASH, bash.move());
        // Too late for the bash (contact in 3 == bash hit tick): step back.
        GuardMoveSelector.Situation late = new GuardMoveSelector.Situation(true, 3,
            false, false, 0, true, true, true);
        GuardMoveSelector.Choice dodge = GuardMoveSelector.react(late);
        assertNotNull(dodge);
        assertEquals(GuardMoveSelector.Action.EVADE, dodge.action());
        assertTrue(dodge.evadeTicks() > 3, "the back-step outlasts the enemy contact");
        // No shield: a light still lands first at 6 ticks, so trade.
        GuardMoveSelector.Situation noShield = new GuardMoveSelector.Situation(true, 6,
            false, false, 0, false, true, true);
        assertNull(GuardMoveSelector.react(noShield));
        // No shield, heavy 4 ticks out: our light would be too late, dodge.
        GuardMoveSelector.Situation noShieldLate = new GuardMoveSelector.Situation(true, 4,
            false, false, 0, false, true, true);
        assertEquals(GuardMoveSelector.Action.EVADE,
            GuardMoveSelector.react(noShieldLate).action());
        // Bash on cooldown: no bash.
        GuardMoveSelector.Situation cooling = new GuardMoveSelector.Situation(true, 6,
            false, false, 0, true, false, true);
        assertNull(GuardMoveSelector.react(cooling));
        // Nothing incoming: no reaction.
        assertNull(GuardMoveSelector.react(ordinary(3)));
    }

    @Test
    void aComboContinuesOnlyOnALandedLinkInReachInsideTheWindow() {
        assertTrue(GuardMoveSelector.continueCombo(1, true, true, 10, 10, 16));
        assertTrue(GuardMoveSelector.continueCombo(2, true, true, 16, 10, 16));
        assertFalse(GuardMoveSelector.continueCombo(1, false, true, 10, 10, 16),
            "a missed link breaks the chain");
        assertFalse(GuardMoveSelector.continueCombo(1, true, false, 10, 10, 16),
            "a target out of reach breaks the chain");
        assertFalse(GuardMoveSelector.continueCombo(1, true, true, 9, 10, 16),
            "no link before the chain point");
        assertFalse(GuardMoveSelector.continueCombo(1, true, true, 17, 10, 16),
            "the window closes");
        assertFalse(GuardMoveSelector.continueCombo(3, true, true, 10, 10, 16),
            "the combo has exactly three links");
        assertFalse(GuardMoveSelector.continueCombo(0, true, true, 10, 10, 16));
    }
}
