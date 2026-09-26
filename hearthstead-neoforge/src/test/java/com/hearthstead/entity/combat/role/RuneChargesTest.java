package com.hearthstead.entity.combat.role;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Spell economy of the Rune Mage (plan/BATTLE-ROLES.md §4). */
class RuneChargesTest {

    @Test
    void startsFullAndSpendsAtRelease() {
        RuneCharges c = new RuneCharges();
        assertEquals(RuneCharges.MAX_CHARGES, c.charges());
        assertTrue(c.release(RuneSpell.FIREBOLT, 0));
        assertEquals(2, c.charges());
        assertTrue(c.release(RuneSpell.WARD, 20));
        assertEquals(0, c.charges());
        assertFalse(c.canCast(RuneSpell.FROST_RUNE, 40), "no charges left");
    }

    @Test
    void perSpellCooldownAndGlobalCooldown() {
        RuneCharges c = new RuneCharges();
        assertTrue(c.release(RuneSpell.FIREBOLT, 100));
        assertFalse(c.canCast(RuneSpell.FROST_RUNE, 100 + RuneCharges.GLOBAL_COOLDOWN_TICKS - 1),
            "global cooldown blocks a different spell");
        assertTrue(c.canCast(RuneSpell.FROST_RUNE, 100 + RuneCharges.GLOBAL_COOLDOWN_TICKS));
        assertFalse(c.canCast(RuneSpell.FIREBOLT, 100 + RuneSpell.FIREBOLT.cooldownTicks() - 1));
        assertTrue(c.canCast(RuneSpell.FIREBOLT, 100 + RuneSpell.FIREBOLT.cooldownTicks()));
    }

    @Test
    void rechargesOneChargePerMinuteAndStopsAtMax() {
        RuneCharges c = new RuneCharges();
        c.release(RuneSpell.WARD, 0);           // 3 -> 1, recharge clock starts at 0
        c.update(RuneCharges.RECHARGE_TICKS - 1);
        assertEquals(1, c.charges());
        c.update(RuneCharges.RECHARGE_TICKS);
        assertEquals(2, c.charges());
        c.update(10 * RuneCharges.RECHARGE_TICKS);
        assertEquals(RuneCharges.MAX_CHARGES, c.charges(), "never above max");
        assertEquals(0, c.ticksToNextCharge(10 * RuneCharges.RECHARGE_TICKS));
    }

    @Test
    void spendingWhileRechargingKeepsTheRunningClock() {
        RuneCharges c = new RuneCharges();
        c.release(RuneSpell.FIREBOLT, 0);        // 2, clock from 0
        c.release(RuneSpell.FROST_RUNE, 600);    // 1, clock still from 0
        c.update(RuneCharges.RECHARGE_TICKS);
        assertEquals(2, c.charges(), "the first recharge is not reset by the second cast");
    }

    @Test
    void interruptKeepsTheChargeButSpendsTheCooldown() {
        RuneCharges c = new RuneCharges();
        c.interrupt(RuneSpell.FROST_RUNE, 50);
        assertEquals(RuneCharges.MAX_CHARGES, c.charges());
        assertFalse(c.canCast(RuneSpell.FROST_RUNE, 50 + RuneSpell.FROST_RUNE.cooldownTicks() - 1));
        assertTrue(c.canCast(RuneSpell.FROST_RUNE, 50 + RuneSpell.FROST_RUNE.cooldownTicks()));
    }

    @Test
    void runeStonesRefillButNeverOverfill() {
        RuneCharges c = new RuneCharges();
        c.release(RuneSpell.WARD, 0);            // 1 left
        assertEquals(2, c.inscribe(5, 10), "only the two missing charges use stones");
        assertEquals(RuneCharges.MAX_CHARGES, c.charges());
        assertEquals(0, c.inscribe(5, 20));
        assertEquals(2, c.stonesUsed());
    }

    @Test
    void failedReleaseChangesNothing() {
        RuneCharges c = new RuneCharges();
        c.release(RuneSpell.WARD, 0);
        c.release(RuneSpell.FIREBOLT, 200);      // 0 left
        assertFalse(c.release(RuneSpell.FIREBOLT, 400));
        assertEquals(2, c.spellsReleased());
    }

    @Test
    void saveLoadRoundTrip() {
        RuneCharges c = new RuneCharges();
        c.release(RuneSpell.WARD, 0);
        RuneCharges back = RuneCharges.load(c.save());
        assertEquals(1, back.charges());
        assertFalse(back.canCast(RuneSpell.WARD, 10));
        back.update(RuneCharges.RECHARGE_TICKS);
        assertEquals(2, back.charges(), "the recharge clock survives a reload");
    }

    @Test
    void openingBurstIsSmallerThanOneGuardCombo() {
        // "Strong but never a replacement for guards": three firebolts at the
        // centre of the blast, the whole opening burst on full charges.
        float burst = 3 * RuneSpell.fireboltDamage(0.0D);
        assertEquals(18.0F, burst, 1.0E-4F);
        assertTrue(burst < 3 * 11.0F, "a recruit guard's three-link combo lands ~33");
    }
}
