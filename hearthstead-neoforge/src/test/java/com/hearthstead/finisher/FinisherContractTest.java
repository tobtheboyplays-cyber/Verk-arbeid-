package com.hearthstead.finisher;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Timeline, variant table, double latch and classification contracts. */
class FinisherContractTest {

    @Test
    void timelineHoldsTheContactFrameForTheSharedHitStop() {
        int impact = 14;
        assertEquals(0.0F, FinisherTimeline.clipTicks(0.0F, impact));
        assertEquals(13.5F, FinisherTimeline.clipTicks(13.5F, impact));
        for (int t = impact; t < impact + FinisherTimeline.HIT_STOP_TICKS; t++) {
            assertEquals(impact, FinisherTimeline.clipTicks(t + 0.7F, impact), "frozen at " + t);
            assertTrue(FinisherTimeline.inHitStop(t, impact));
        }
        assertEquals(impact + 1.0F,
            FinisherTimeline.clipTicks(impact + FinisherTimeline.HIT_STOP_TICKS + 1.0F, impact));
        assertTrue(FinisherTimeline.HIT_STOP_TICKS >= 2 && FinisherTimeline.HIT_STOP_TICKS <= 3,
            "owner: a 2-3 tick hit-stop");
    }

    @Test
    void variantTimingsAreSane() {
        for (FinisherVariant v : FinisherVariant.values()) {
            assertTrue(v.impactTick() > 6 && v.impactTick() < v.lengthTicks(), v.name());
            int lock = v.lockTicks();
            assertEquals(v.lengthTicks() + FinisherTimeline.HIT_STOP_TICKS, lock);
            // spec: 0.8-1.4 s of choreography (the double may run a little longer)
            assertTrue(v.lengthTicks() >= 16 && v.lengthTicks() <= (v.isDouble() ? 34 : 30),
                v.name() + " " + v.lengthTicks());
            assertTrue(v.actorDistance() > 0.5D && v.actorDistance() < 2.5D, v.name());
            assertFalse(v.weapons().isEmpty());
            assertFalse(v.enemies().isEmpty());
            assertTrue(v.leadClipKey().startsWith("player/finisher_"));
            assertTrue(v.victimClipKey("raider").startsWith("raider/finisher_victim_"));
            assertSame(v, FinisherVariant.byOrdinal(v.ordinal()));
        }
        assertNull(FinisherVariant.byOrdinal(-1));
        assertEquals("player/finisher_double_pin_lead", FinisherVariant.DOUBLE_PIN_EXECUTION.leadClipKey());
        assertEquals("player/finisher_double_pin_partner", FinisherVariant.DOUBLE_PIN_EXECUTION.partnerClipKey());
        assertNull(FinisherVariant.SWORD_PARRY_THRUST.partnerClipKey());
        assertEquals("goblin/finisher_victim_goblin_scruff_slam",
            FinisherVariant.GOBLIN_SCRUFF_SLAM.victimClipKey("goblin"));
    }

    @Test
    void doubleOnlyOnBrutesAndCaptains() {
        assertTrue(EnemyClass.BRUTE.allowsDouble());
        assertTrue(EnemyClass.CAPTAIN.allowsDouble());
        assertFalse(EnemyClass.SKIRMISHER.allowsDouble());
        assertFalse(EnemyClass.GOBLIN.allowsDouble());
        assertFalse(EnemyClass.OTHER.allowsDouble());
        assertTrue(FinisherVariant.DOUBLE_PIN_EXECUTION.isDouble());
        assertEquals(java.util.Set.of(EnemyClass.BRUTE, EnemyClass.CAPTAIN),
            FinisherVariant.DOUBLE_PIN_EXECUTION.enemies());
    }

    @Test
    void enemyClassificationPrecedence() {
        assertEquals(EnemyClass.OTHER, EnemyClass.of(false, true, true, true));
        assertEquals(EnemyClass.GOBLIN, EnemyClass.of(true, false, false, true));
        assertEquals(EnemyClass.BRUTE, EnemyClass.of(true, true, true, false),
            "a brute captain is choreographed by its size");
        assertEquals(EnemyClass.CAPTAIN, EnemyClass.of(true, false, true, false));
        assertEquals(EnemyClass.SKIRMISHER, EnemyClass.of(true, false, false, false));
    }

    @Test
    void weaponNamesFallBackSensibly() {
        assertEquals(WeaponClass.SWORD, WeaponClass.ofItemId("steel_longsword"));
        assertEquals(WeaponClass.SWORD, WeaponClass.ofItemId("wooden_spear"));
        assertEquals(WeaponClass.AXE, WeaponClass.ofItemId("bearded_axe"));
        assertEquals(WeaponClass.MACE, WeaponClass.ofItemId("iron_pickaxe"), "pickaxe is not an axe");
        assertEquals(WeaponClass.MACE, WeaponClass.ofItemId("war_hammer"));
        assertEquals(WeaponClass.MACE, WeaponClass.ofItemId("spiked_club"));
        assertEquals(WeaponClass.BARE, WeaponClass.ofItemId("bread"));
        assertEquals(WeaponClass.BARE, WeaponClass.ofItemId(null));
    }

    @Test
    void secondPlayerWithinHalfASecondJoinsTheDouble() {
        DoubleFinisherLatch latch = new DoubleFinisherLatch();
        UUID victim = UUID.randomUUID();
        UUID lead = UUID.randomUUID();
        UUID partner = UUID.randomUUID();
        assertTrue(latch.open(victim, lead, 100L));
        assertFalse(latch.open(victim, partner, 101L), "one latch per victim");
        assertNull(latch.join(victim, lead, 102L), "the lead cannot partner itself");
        DoubleFinisherLatch.Pending joined = latch.join(victim, partner, 100L + DoubleFinisherLatch.PAIR_TICKS);
        assertNotNull(joined);
        assertEquals(lead, joined.lead());
        assertFalse(latch.isPending(victim), "consumed");
        assertTrue(latch.drainExpired(1000L).isEmpty(), "a joined latch never also starts solo");
    }

    @Test
    void lateSecondPressLapsesIntoASolo() {
        DoubleFinisherLatch latch = new DoubleFinisherLatch();
        UUID victim = UUID.randomUUID();
        latch.open(victim, UUID.randomUUID(), 100L);
        assertNull(latch.join(victim, UUID.randomUUID(), 101L + DoubleFinisherLatch.PAIR_TICKS));
        assertTrue(latch.drainExpired(100L + DoubleFinisherLatch.PAIR_TICKS).isEmpty(),
            "still pending on its last tick");
        assertEquals(1, latch.drainExpired(101L + DoubleFinisherLatch.PAIR_TICKS).size());
        assertEquals(0, latch.size());
        assertEquals(10, DoubleFinisherLatch.PAIR_TICKS, "spec: within about 0.5 s");
    }
}
