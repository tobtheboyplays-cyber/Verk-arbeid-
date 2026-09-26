package com.hearthstead.entity;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** plan/ATTRIBUTES.md: every attribute useful, every effect bounded. */
class AttributeEffectsTest {

    @Test
    void everyAttributeHasAtLeastTwoLiveEffects() {
        for (Attribute attribute : Attribute.ALL) {
            assertTrue(AttributeEffects.liveEffectCount(attribute) >= 2,
                attribute + " has only " + AttributeEffects.liveEffectCount(attribute)
                    + " live effects");
        }
    }

    @Test
    void everyAttributeHasAtLeastTwoScaledEffectsOrStructuralOnes() {
        // The four attributes that were dead before the rework must now carry
        // at least two AttributeEffects of their own, not borrowed structure.
        for (Attribute attribute : EnumSet.of(Attribute.SPIRIT, Attribute.PERCEPTION,
                Attribute.FOCUS, Attribute.PRESENCE)) {
            assertTrue(AttributeEffects.effectsOf(attribute).size() >= 2, attribute.name());
        }
    }

    @Test
    void curveIsMonotonicAndBounded() {
        assertEquals(0.0D, AttributeEffects.curve(0), 1e-9);
        assertEquals(1.0D, AttributeEffects.curve(99), 1e-9);
        assertEquals(1.0D, AttributeEffects.curve(500), 1e-9);
        assertEquals(0.0D, AttributeEffects.curve(-7), 1e-9);
        double last = -1;
        for (int v = 0; v <= 99; v++) {
            double c = AttributeEffects.curve(v);
            assertTrue(c >= last);
            last = c;
        }
        // Newcomers differ noticeably: 4 is about a fifth, 15 about two fifths.
        assertEquals(0.20D, AttributeEffects.curve(4), 0.01D);
        assertEquals(0.39D, AttributeEffects.curve(15), 0.01D);
    }

    @Test
    void everyEffectStaysInsideItsCapAtAnyStrength() {
        double[] strengths = {-5, 0, 0.5, 1, 1.5, 2, 3, 100, Double.NaN,
            Double.POSITIVE_INFINITY};
        for (AttributeEffects.Effect effect : AttributeEffects.Effect.values()) {
            assertTrue(effect.max() > 0 && effect.cap() >= effect.max(), effect.name());
            // "modest": no fraction effect above +-25% at design strength.
            if (effect != AttributeEffects.Effect.HAUL_CAPACITY
                && effect != AttributeEffects.Effect.MAX_HEALTH) {
                assertTrue(effect.max() <= 0.30D, effect + " too large");
            }
            for (double s : strengths) {
                for (int v = -3; v <= 120; v++) {
                    double a = AttributeEffects.amount(effect, v, s);
                    assertTrue(a >= 0 && a <= effect.cap(), effect + " v=" + v + " s=" + s);
                }
            }
            assertEquals(effect.max(), AttributeEffects.amount(effect, 99, 1.0D), 1e-9);
            assertEquals(0.0D, AttributeEffects.amount(effect, 99, 0.0D), 1e-9);
        }
    }

    @Test
    void jobFitAndLevelSpeedNeverStackPastTheWorkCap() {
        assertEquals(0.15D, AttributeEffects.jobFit(99, 99, 1.0D), 1e-9);
        assertEquals(0.0D, AttributeEffects.jobFit(99, 99, 0.0D), 1e-9);
        assertEquals(AttributeEffects.MAX_WORK_TIME_CUT,
            AttributeEffects.combinedWorkCut(SkillLevels.MAX_SPEED_BONUS,
                AttributeEffects.jobFit(99, 99, 2.0D)), 1e-9);
        assertTrue(AttributeEffects.jobFit(15, 4, 1.0D) > AttributeEffects.jobFit(4, 4, 1.0D));
        // shorten never produces zero from a positive wait
        assertEquals(1, AttributeEffects.shorten(1, 0.3D));
        assertEquals(70, AttributeEffects.shorten(100, 0.9D));
        assertEquals(0, AttributeEffects.shorten(0, 0.3D));
    }

    @Test
    void highAttributeBeatsLowAttributeOnEveryEffect() {
        for (AttributeEffects.Effect effect : AttributeEffects.Effect.values()) {
            assertTrue(AttributeEffects.amount(effect, 15, 1.0D)
                > AttributeEffects.amount(effect, 3, 1.0D), effect.name());
        }
    }

    @Test
    void reworkNeedsNoSaveMigration() {
        // Ids, ordinals and the persisted shape are unchanged by the rework:
        // a current save loads back to exactly the same values and knack.
        assertEquals(2, SettlerAttributes.DATA_VERSION);
        assertEquals(8, Attribute.COUNT);
        assertEquals("presence", Attribute.PRESENCE.key());
        SettlerAttributes rolled = SettlerAttributes.roll(net.minecraft.util.RandomSource.create(42L));
        rolled.train(Attribute.FOCUS, 3.3F, 1.0F);
        net.minecraft.nbt.CompoundTag saved = rolled.save();
        SettlerAttributes loaded = SettlerAttributes.load(saved,
            net.minecraft.util.RandomSource.create(7L), 99L);
        org.junit.jupiter.api.Assertions.assertArrayEquals(saved.getIntArray("Values"),
            loaded.save().getIntArray("Values"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(saved.getIntArray("ProgressBits"),
            loaded.save().getIntArray("ProgressBits"));
        assertEquals(rolled.knack(), loaded.knack());
    }

    @Test
    void formatIsSignedAndUnitAware() {
        assertEquals("+20%", AttributeEffects.format(AttributeEffects.Effect.MELEE_DAMAGE, 99, 1.0D));
        assertEquals("-20%", AttributeEffects.format(AttributeEffects.Effect.ENERGY_DRAIN, 99, 1.0D));
        assertEquals("+4", AttributeEffects.format(AttributeEffects.Effect.HAUL_CAPACITY, 99, 1.0D));
        assertEquals("+10", AttributeEffects.format(AttributeEffects.Effect.PERSUASION, 99, 1.0D));
        assertEquals("+4.0", AttributeEffects.format(AttributeEffects.Effect.MAX_HEALTH, 99, 1.0D));
    }

    @Test
    void mayorBoonsAreHalfToFullAndCapped() {
        for (AttributeEffects.Boon boon : AttributeEffects.Boon.values()) {
            assertEquals(boon.max() / 2.0D, AttributeEffects.boonAmount(boon, 0, 1.0D), 1e-9, boon.name());
            assertEquals(boon.max(), AttributeEffects.boonAmount(boon, 99, 1.0D), 1e-9, boon.name());
            assertEquals(0.0D, AttributeEffects.boonAmount(boon, 99, 0.0D), 1e-9, boon.name());
            for (double s : new double[] {-1, 0.5, 2, 50, Double.NaN}) {
                double a = AttributeEffects.boonAmount(boon, 99, s);
                assertTrue(a >= 0 && a <= boon.cap(), boon + " s=" + s);
            }
            // Same names as settlement.Mayor.Boon, so the runtime can match them.
            assertTrue(java.util.Arrays.stream(com.hearthstead.settlement.Mayor.Boon.values())
                .anyMatch(m -> m.name().equals(boon.name()) && m.from() == boon.attribute()), boon.name());
        }
        assertTrue(AttributeEffects.MAX_MORALE_CUT <= 0.35D && AttributeEffects.MAX_RANGE_GAIN <= 0.40D
            && AttributeEffects.MAX_EXTRA_FIND <= 0.20D && AttributeEffects.MAX_PERSUASION <= 0.15D);
    }

    @Test
    void traitSpeedSightAndPanicAreBoundedAndOffAtZero() {
        assertEquals(0.92D, AttributeEffects.traitSpeed(0.92D, 1.0D), 1e-9);
        assertEquals(1.15D, AttributeEffects.traitSpeed(1.15D, 1.0D), 1e-9);
        assertEquals(1.15D, AttributeEffects.traitSpeed(3.0D, 2.0D), 1e-9);
        assertEquals(0.85D, AttributeEffects.traitSpeed(0.1D, 1.0D), 1e-9);
        assertEquals(1.0D, AttributeEffects.traitSpeed(0.92D, 0.0D), 1e-9);
        assertEquals(0.30D, AttributeEffects.traitSightGain(1.30D, 1.0D), 1e-9);
        assertEquals(0.30D, AttributeEffects.traitSightGain(9.0D, 2.0D), 1e-9);
        assertEquals(0.0D, AttributeEffects.traitSightGain(1.30D, 0.0D), 1e-9);
        assertEquals(12.0D, AttributeEffects.panicScanRadius(false, 1.0D, 1.0D), 1e-9);
        assertEquals(16.0D, AttributeEffects.panicScanRadius(true, 1.0D, 1.0D), 1e-9);
        assertEquals(15.6D, AttributeEffects.panicScanRadius(false, 1.30D, 1.0D), 1e-9);
        assertEquals(16.0D, AttributeEffects.panicScanRadius(true, 1.30D, 2.0D), 1e-9);
        assertEquals(12.0D, AttributeEffects.panicScanRadius(true, 1.30D, 0.0D), 1e-9);
        // The trait table itself stays inside the wired bounds.
        for (Trait t : Trait.ALL) {
            assertTrue(t.speed() >= AttributeEffects.MIN_TRAIT_SPEED && t.speed() <= AttributeEffects.MAX_TRAIT_SPEED);
            assertTrue(t.sight() >= 1.0D && t.sight() <= AttributeEffects.MAX_TRAIT_SIGHT);
        }
    }

    @Test
    void traitWorkAndClockAreBoundedAndOffAtZero() {
        assertEquals(0.15D, AttributeEffects.traitWorkCut(1.15D, 1.0D), 1e-12);
        assertEquals(-0.08D, AttributeEffects.traitWorkCut(0.92D, 1.0D), 1e-12);
        assertEquals(AttributeEffects.MAX_TRAIT_WORK_CUT, AttributeEffects.traitWorkCut(9.0D, 2.0D), 1e-12);
        assertEquals(AttributeEffects.MIN_TRAIT_WORK_CUT, AttributeEffects.traitWorkCut(0.1D, 2.0D), 1e-12);
        assertEquals(0.0D, AttributeEffects.traitWorkCut(1.15D, 0.0D), 1e-12);
        // Work never cut past 30% nor slowed past 10%, whatever stacks.
        assertEquals(0.30D, AttributeEffects.combinedWorkCut(0.18D, 0.15D, 0.2D), 1e-12);
        assertEquals(-0.10D, AttributeEffects.combinedWorkCut(0.0D, 0.0D, -0.5D), 1e-12);
        assertEquals(110, AttributeEffects.shortenSigned(100, -0.5D));
        assertEquals(70, AttributeEffects.shortenSigned(100, 0.9D));
        assertEquals(108, SkillLevels.shortenWaitBy(100, -0.08D));
        assertEquals(440, SkillLevels.shortenLoopedBy(400, 20, -0.10D));
        assertEquals(1000L, AttributeEffects.clockShift(AttributeEffects.EARLY_RISER_SHIFT, 1.0D));
        assertEquals(0L, AttributeEffects.clockShift(AttributeEffects.EARLY_RISER_SHIFT, 0.0D));
        assertTrue(AttributeEffects.morning(500L) && !AttributeEffects.morning(7000L));
        assertTrue(AttributeEffects.night(18000L) && !AttributeEffects.night(3000L));
        assertTrue(AttributeEffects.lateHours(10000L) && !AttributeEffects.lateHours(2000L));
        // Every trait's work value is inside the wired bounds.
        for (Trait t : Trait.ALL) {
            assertTrue(t.work() - 1.0D >= AttributeEffects.MIN_TRAIT_WORK_CUT
                && t.work() - 1.0D <= AttributeEffects.MAX_TRAIT_WORK_CUT, t.name());
        }
    }

    @Test
    void catalogueIsIndexedByAttribute() {
        Map<Attribute, Integer> count = new EnumMap<>(Attribute.class);
        for (AttributeEffects.Effect effect : AttributeEffects.Effect.values()) {
            count.merge(effect.attribute(), 1, Integer::sum);
            assertTrue(AttributeEffects.effectsOf(effect.attribute()).contains(effect));
            assertTrue(effect.translationKey().startsWith("hearthstead.attribute.effect."));
        }
        for (Attribute attribute : Attribute.ALL) {
            assertEquals(count.getOrDefault(attribute, 0),
                AttributeEffects.effectsOf(attribute).size());
        }
    }
}
