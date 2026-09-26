package com.hearthstead.entity.combat.role;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Heal-over-time and triage for the Healer (plan/BATTLE-ROLES.md §3). */
class HealLedgerTest {

    @Test
    void bandageHealsEightOverEightSecondsInOneSecondPulses() {
        HealLedger l = new HealLedger();
        UUID p = UUID.randomUUID();
        assertTrue(l.start(p, HealLedger.BANDAGE_TOTAL, 0));
        float total = 0;
        int pulses = 0;
        for (long t = 1; t <= 400; t++) {
            for (HealLedger.Pulse pulse : l.due(t)) {
                total += pulse.amount();
                pulses++;
                assertEquals(0, t % HealLedger.PULSE_TICKS, "pulses land on whole seconds");
            }
        }
        assertEquals(8.0F, total, 1.0E-4F);
        assertEquals(HealLedger.PULSES, pulses);
        assertFalse(l.healing(p), "finished heals are dropped");
    }

    @Test
    void neverStacksARefreshKeepsTheLarger() {
        HealLedger l = new HealLedger();
        UUID p = UUID.randomUUID();
        assertTrue(l.start(p, HealLedger.BANDAGE_TOTAL, 0));
        assertFalse(l.start(p, HealLedger.HERB_TOTAL, 5), "a smaller heal must not replace (supply kept)");
        l.due(100);                               // 5 pulses paid, 3 left
        assertEquals(3.0F, l.remaining(p), 1.0E-4F);
        assertTrue(l.start(p, HealLedger.HERB_TOTAL, 100), "4 > 3 left: refresh");
        float total = 0;
        for (long t = 101; t <= 500; t++) {
            for (HealLedger.Pulse pulse : l.due(t)) {
                total += pulse.amount();
            }
        }
        assertEquals(4.0F, total, 1.0E-4F);
    }

    @Test
    void lateTicksCatchUpWithoutOverpaying() {
        HealLedger l = new HealLedger();
        UUID p = UUID.randomUUID();
        l.start(p, 8.0F, 0);
        List<HealLedger.Pulse> burst = l.due(10_000);
        float sum = 0;
        for (HealLedger.Pulse pulse : burst) {
            sum += pulse.amount();
        }
        assertEquals(8.0F, sum, 1.0E-4F);
        assertTrue(l.due(20_000).isEmpty());
    }

    @Test
    void cancelStopsTheHeal() {
        HealLedger l = new HealLedger();
        UUID p = UUID.randomUUID();
        l.start(p, 8.0F, 0);
        l.cancel(p);
        assertTrue(l.due(1000).isEmpty());
    }

    @Test
    void triagePrefersSafeThenMostHurtThenNearest() {
        List<HealLedger.Patient<String>> c = List.of(
            new HealLedger.Patient<>("fine", 0.9F, 1.0D, false, false),
            new HealLedger.Patient<>("hurt-but-in-fight", 0.2F, 1.0D, true, false),
            new HealLedger.Patient<>("hurt-safe-far", 0.4F, 100.0D, false, false),
            new HealLedger.Patient<>("less-hurt-safe", 0.6F, 1.0D, false, false),
            new HealLedger.Patient<>("already", 0.1F, 1.0D, false, true),
            new HealLedger.Patient<>("dead", 0.0F, 1.0D, false, false));
        assertEquals("hurt-safe-far", HealLedger.triage(c));
        assertNull(HealLedger.triage(List.of(new HealLedger.Patient<>("fine", 0.95F, 1, false, false))));
    }

    @Test
    void evacuateOnlyWhenBadlyHurtAndInDanger() {
        assertTrue(HealLedger.shouldEvacuate(0.25F, true));
        assertFalse(HealLedger.shouldEvacuate(0.25F, false));
        assertFalse(HealLedger.shouldEvacuate(0.5F, true));
        assertFalse(HealLedger.shouldEvacuate(0.0F, true));
    }
}
