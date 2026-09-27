package com.hearthstead.entity.combat.role;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Firebolt falloff, ward bookkeeping and the mage's spell choice. */
class RuneSpellMathTest {

    @Test
    void fireboltFallsOffLinearlyAndStopsAtTheRadius() {
        assertEquals(6.0F, RuneSpell.fireboltDamage(0.0D), 1.0E-4F);
        assertEquals(3.6F, RuneSpell.fireboltDamage(RuneSpell.FIREBOLT_RADIUS), 1.0E-4F);
        assertEquals(4.8F, RuneSpell.fireboltDamage(RuneSpell.FIREBOLT_RADIUS / 2.0D), 1.0E-4F);
        assertEquals(0.0F, RuneSpell.fireboltDamage(RuneSpell.FIREBOLT_RADIUS + 0.01D));
        assertEquals(0.0F, RuneSpell.fireboltDamage(-1.0D));
        assertEquals(0.0F, RuneSpell.fireboltDamage(Double.NaN));
    }

    @Test
    void wardRemovesOnlyWhatIsLeftOfItself() {
        // Untouched ward on top of a golden apple's 4: remove exactly 6.
        assertEquals(6.0F, RuneSpell.wardLeftAtExpiry(6.0F, 10.0F, 10.0F), 1.0E-4F);
        // 4 absorbed by hits: the ward (drained first) has 2 left.
        assertEquals(2.0F, RuneSpell.wardLeftAtExpiry(6.0F, 10.0F, 6.0F), 1.0E-4F);
        // Drained past the ward into the apple: nothing of the ward is left.
        assertEquals(0.0F, RuneSpell.wardLeftAtExpiry(6.0F, 10.0F, 3.0F), 1.0E-4F);
        // Never more than the current pool.
        assertEquals(1.0F, RuneSpell.wardLeftAtExpiry(6.0F, 6.0F, 1.0F), 1.0E-4F);
    }

    @Test
    void wardWinsWhenAlliesAreHurt() {
        RuneCharges c = new RuneCharges();
        RuneMageBrain.View v = new RuneMageBrain.View(3, 0.5F, false, 3, false, 3, true);
        assertEquals(RuneSpell.WARD, RuneMageBrain.choose(v, c, 0));
    }

    @Test
    void frostForClustersOrChargersFireboltForClumps() {
        RuneCharges c = new RuneCharges();
        assertEquals(RuneSpell.FROST_RUNE, RuneMageBrain.choose(
            new RuneMageBrain.View(0, 1.0F, false, 2, false, 2, true), c, 0));
        assertEquals(RuneSpell.FROST_RUNE, RuneMageBrain.choose(
            new RuneMageBrain.View(0, 1.0F, false, 1, true, 1, true), c, 0));
        assertEquals(RuneSpell.FIREBOLT, RuneMageBrain.choose(
            new RuneMageBrain.View(0, 1.0F, false, 1, false, 2, true), c, 0));
    }

    @Test
    void aLoneEnemyIsOnlyWorthABoltAtFullCharges() {
        RuneCharges c = new RuneCharges();
        RuneMageBrain.View lone = new RuneMageBrain.View(0, 1.0F, false, 1, false, 1, true);
        assertEquals(RuneSpell.FIREBOLT, RuneMageBrain.choose(lone, c, 0));
        c.release(RuneSpell.FIREBOLT, 0);
        assertNull(RuneMageBrain.choose(lone, c, 1000), "saves charges when not full");
    }

    @Test
    void bestClusterFindsTheCrowd() {
        List<double[]> pts = List.of(new double[] {0, 64, 0}, new double[] {1, 64, 0},
            new double[] {0, 64, 1}, new double[] {20, 64, 20});
        RuneMageBrain.Aim aim = RuneMageBrain.bestCluster(pts, 2.0D);
        assertEquals(3, aim.count());
        assertTrue(Math.abs(aim.x()) <= 1.0D && Math.abs(aim.z()) <= 1.0D);
    }

    @Test
    void mageCapIsOneEarlyTwoLater() {
        assertEquals(1, RuneMageBrain.mageCap(8, false));
        // Tech tree v3: size alone no longer grants the second mage; High Runes does.
        assertEquals(1, RuneMageBrain.mageCap(20, false));
        assertEquals(2, RuneMageBrain.mageCap(5, true));
    }
}
