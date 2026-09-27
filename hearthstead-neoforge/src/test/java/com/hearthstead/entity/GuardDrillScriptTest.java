package com.hearthstead.entity;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The drill choreography (v2) keeps the owner's paired-animation rule and the "more fighting,
 * more movement, smoother" brief: per-person phase and speed, combos, feints, ripostes, rare
 * stumbles with a nod, circling and pressing footwork, short staggered breathers, cross-fades,
 * roughly half the time actively fighting, deterministic from the seed.
 */
final class GuardDrillScriptTest {
    private static final int LEN = 1600;

    @Test
    void phaseAndSpeedStayInsideTheOwnersRangeAndDifferBetweenPartners() {
        int differentPhase = 0;
        for (int seed = 0; seed < 500; seed++) {
            for (int slot = 0; slot < 2; slot++) {
                float p = GuardDrillScript.phaseTicks(seed * 7919, slot);
                assertTrue(p >= 2.0F && p <= 16.0F, "phase 0.1-0.8 s: " + p);
                float s = GuardDrillScript.speed(seed * 7919, slot);
                assertTrue(s >= 0.9F && s <= 1.1F, "speed +-10%: " + s);
            }
            if (Math.abs(GuardDrillScript.phaseTicks(seed * 7919, 0) - GuardDrillScript.phaseTicks(seed * 7919, 1)) > 0.5F) {
                differentPhase++;
            }
        }
        assertTrue(differentPhase > 400, "partners are almost never in phase: " + differentPhase);
    }

    @Test
    void thePlanIsDeterministicFromTheSeed() {
        GuardDrillScript.Plan a = GuardDrillScript.plan(123456, true, LEN);
        GuardDrillScript.Plan b = GuardDrillScript.plan(123456, true, LEN);
        assertEquals(a, b);
        assertNotEquals(a.contacts(), GuardDrillScript.plan(654321, true, LEN).contacts());
    }

    @Test
    void aPairFightsWithCombosFeintsRipostesFootworkAndRareStumbles() {
        int parries = 0, evades = 0, none = 0, high = 0, low = 0, blows = 0, feints = 0, combos = 0;
        int ripostes = 0, stumbles = 0, nods = 0, circles = 0, presses = 0, breathers = 0;
        float active = 0.0F;
        int seeds = 120;
        for (int seed = 1; seed <= seeds; seed++) {
            GuardDrillScript.Plan plan = GuardDrillScript.plan(seed * 104729, true, LEN);
            List<GuardDrillScript.Contact> cs = plan.contacts();
            float lastStumble = -1.0E9F;
            for (int i = 0; i < cs.size(); i++) {
                GuardDrillScript.Contact c = cs.get(i);
                assertTrue(c.tick() > 0 && c.tick() < LEN + 60);
                if (c.feint()) {
                    feints++;
                    continue;
                }
                blows++;
                switch (c.answer()) {
                    case PARRY -> parries++;
                    case EVADE -> evades++;
                    case STUMBLE -> {
                        stumbles++;
                        assertTrue(c.tick() - lastStumble > GuardDrillScript.STUMBLE_GAP_TICKS * 0.9F, "stumbles are rare");
                        lastStumble = c.tick();
                    }
                    default -> none++;
                }
                if (c.high()) high++; else low++;
                if (i > 0 && !cs.get(i - 1).feint()) {
                    float gap = c.tick() - cs.get(i - 1).tick();
                    if (c.attacker() == cs.get(i - 1).attacker() && gap < 20.0F) combos++;
                    if (c.attacker() != cs.get(i - 1).attacker() && gap < 30.0F
                        && cs.get(i - 1).answer() == GuardDrillScript.Answer.PARRY) {
                        ripostes++;
                    }
                }
            }
            for (int slot = 0; slot < 2; slot++) {
                for (GuardDrillScript.Action act : plan.actions(slot)) {
                    switch (act.clip()) {
                        case GuardDrillScript.BREATHER -> breathers++;
                        case GuardDrillScript.NOD -> nods++;
                        case GuardDrillScript.STEP_LEFT, GuardDrillScript.STEP_RIGHT -> circles++;
                        case GuardDrillScript.ADVANCE, GuardDrillScript.RETREAT -> presses++;
                        default -> { }
                    }
                }
            }
            active += GuardDrillScript.activeFraction(plan);
        }
        float mean = active / seeds;
        assertTrue(mean >= 0.38F && mean <= 0.62F, "about half the time is active fighting: " + mean);
        assertTrue(blows / (float) seeds >= 22, "many exchanges in 80 s: " + blows / (float) seeds);
        assertTrue(parries > blows / 2, "most blows are parried");
        assertTrue(evades > blows / 12, "some are stepped away from");
        assertTrue(none > blows / 20, "some are not answered at all");
        assertTrue(high > blows / 3 && low > blows / 3, "both cut variants");
        assertTrue(combos > seeds * 3, "combos of 2-3 blows: " + combos);
        assertTrue(ripostes > seeds, "parry then riposte: " + ripostes);
        assertTrue(feints > seeds / 2, "feints: " + feints);
        assertTrue(stumbles > 5 && stumbles < seeds * 2, "rare stumbles: " + stumbles);
        assertTrue(nods >= stumbles * 2 - 1, "every stumble ends with both nodding");
        assertTrue(circles > seeds * 4 && presses > seeds * 4, "they circle and press: " + circles + "/" + presses);
        assertTrue(breathers > seeds && breathers < seeds * 8, "short, rare breathers: " + breathers);
    }

    @Test
    void nobodyEverShowsMoreThanTwoClipsAtOnce() {
        for (int seed = 1; seed <= 60; seed++) {
            GuardDrillScript.Plan plan = GuardDrillScript.plan(seed * 9931, true, LEN);
            for (int slot = 0; slot < 2; slot++) {
                for (float t = 0; t < LEN + 60; t += 1.0F) {
                    int running = 0;
                    for (GuardDrillScript.Action act : plan.actions(slot)) {
                        if (act.start() <= t && t < act.end()) running++;
                    }
                    assertTrue(running <= 2, "seed " + seed + " slot " + slot + " t " + t + ": " + running);
                    GuardDrillScript.Sample s = GuardDrillScript.sample(plan, slot, t);
                    assertTrue(s.weight() + s.prevWeight() <= 1.0001F);
                }
            }
        }
    }

    @Test
    void partnersNeverTakeTheirBreatherInUnison() {
        int pairs = 0;
        for (int seed = 1; seed <= 300; seed++) {
            GuardDrillScript.Plan plan = GuardDrillScript.plan(seed * 7349, true, LEN);
            for (GuardDrillScript.Action a : plan.slot0()) {
                if (a.clip() != GuardDrillScript.BREATHER) continue;
                for (GuardDrillScript.Action b : plan.slot1()) {
                    if (b.clip() != GuardDrillScript.BREATHER || Math.abs(a.start() - b.start()) > 60.0F) continue;
                    pairs++;
                    assertTrue(Math.abs(a.start() - b.start()) >= 8.0F - 1.0E-3F,
                        "one lowers the guard first, the other 0.4 s or more later");
                }
            }
        }
        assertTrue(pairs > 60, "both breathe in most breaks: " + pairs);
    }

    @Test
    void theAnswerLandsOnTheBlowButNeverFrameExact() {
        Set<Float> offsets = new HashSet<>();
        for (int seed = 1; seed <= 50; seed++) {
            GuardDrillScript.Plan plan = GuardDrillScript.plan(seed * 31337, true, LEN);
            for (GuardDrillScript.Contact c : plan.contacts()) {
                if (c.answer() == GuardDrillScript.Answer.NONE) continue;
                int defender = 1 - c.attacker();
                float best = Float.MAX_VALUE;
                for (GuardDrillScript.Action act : plan.actions(defender)) {
                    boolean answerClip = act.clip() == GuardDrillScript.PARRY_HIGH || act.clip() == GuardDrillScript.PARRY_LOW
                        || act.clip() == GuardDrillScript.EVADE || act.clip() == GuardDrillScript.STUMBLE;
                    if (!answerClip || act.start() > c.tick() || c.tick() >= act.end()) continue;
                    float meet = act.at(GuardDrillScript.CONTACT_S[act.clip()]);
                    if (Math.abs(meet - c.tick()) < Math.abs(best)) best = meet - c.tick();
                }
                assertTrue(best != Float.MAX_VALUE, "an answered blow has an answer in progress (seed " + seed + ")");
                assertTrue(best >= -1.01F && best <= 2.01F, "reaction within a couple of ticks: " + best);
                offsets.add(Math.round(best * 10.0F) / 10.0F);
            }
        }
        assertTrue(offsets.size() > 5, "reactions vary");
    }

    @Test
    void samplingCrossFadesOverFiveTicksAndFallsBackToTheStance() {
        assertTrue(GuardDrillScript.BLEND_S * 20.0F >= 4.0F, "blends of at least 4-6 ticks");
        GuardDrillScript.Plan plan = GuardDrillScript.plan(42, true, LEN);
        assertEquals(GuardDrillScript.STANCE, GuardDrillScript.sample(plan, 0, -5.0F).clip());
        GuardDrillScript.Action first = plan.actions(0).get(0);
        GuardDrillScript.Sample atStart = GuardDrillScript.sample(plan, 0, first.start() + 0.01F);
        assertEquals(first.clip(), atStart.clip());
        assertTrue(atStart.weight() < 0.05F, "fades in from the stance");
        GuardDrillScript.Sample after = GuardDrillScript.sample(plan, 0, LEN + 100.0F);
        assertEquals(GuardDrillScript.STANCE, after.clip());
        // No pops: the total clip weight never jumps by more than a fade step per tick.
        for (int slot = 0; slot < 2; slot++) {
            float prev = 0.0F;
            for (float t = 0; t < LEN; t += 0.5F) {
                GuardDrillScript.Sample s = GuardDrillScript.sample(plan, slot, t);
                float total = s.weight() + s.prevWeight();
                assertTrue(Math.abs(total - prev) < 0.35F, "smooth weights at t=" + t + ": " + prev + " -> " + total);
                prev = total;
            }
        }
        for (float t = -40; t < 2000; t += 13.7F) {
            float s = GuardDrillScript.stanceLocal(42, 1, t);
            assertTrue(s >= 0.0F && s < GuardDrillScript.LENGTH_S[GuardDrillScript.STANCE]);
        }
    }

    @Test
    void footworkMovesThePairAndIsEasedAndBounded() {
        float total = 0.0F;
        int seeds = 20;
        for (int seed = 1; seed <= seeds; seed++) {
            GuardDrillScript.Plan plan = GuardDrillScript.plan(seed * 2024, true, LEN);
            for (int slot = 0; slot < 2; slot++) {
                float left = 0.0F;
                float fwd = 0.0F;
                for (float t = 0; t < LEN + 100; t += 1.0F) {
                    float[] d = GuardDrillScript.displacement(plan, slot, t, t + 1.0F);
                    assertTrue(Math.abs(d[0]) < 0.08F && Math.abs(d[1]) < 0.08F, "eased: no teleport steps");
                    left += d[0];
                    fwd += d[1];
                    total += Math.abs(d[0]) + Math.abs(d[1]);
                }
                float expectLeft = 0.0F;
                float expectFwd = 0.0F;
                for (GuardDrillScript.Action act : plan.actions(slot)) {
                    expectLeft += GuardDrillScript.MOVE_LEFT[act.clip()];
                    expectFwd += GuardDrillScript.MOVE_FWD[act.clip()];
                }
                assertEquals(expectLeft, left, 1.0E-3F);
                assertEquals(expectFwd, fwd, 1.0E-3F);
            }
        }
        float perGuard = total / (seeds * 2);
        assertTrue(perGuard > 4.0F, "they do not stand still: " + perGuard + " blocks of footwork per guard per session");
        assertEquals(0.0F, GuardDrillScript.moveEase(GuardDrillScript.STEP_LEFT, 0.0F), 1.0E-6F);
        assertEquals(1.0F, GuardDrillScript.moveEase(GuardDrillScript.STEP_LEFT, 0.8F), 1.0E-6F);
    }

    @Test
    void aSoloGuardShadowDrillsWithFootworkBreathersAndNoPartnerBeats() {
        GuardDrillScript.Plan plan = GuardDrillScript.plan(777, false, LEN);
        assertTrue(plan.slot1().isEmpty());
        assertTrue(plan.actions(0).size() >= 20);
        assertTrue(plan.actions(0).stream().anyMatch(a -> a.clip() == GuardDrillScript.BREATHER));
        assertTrue(plan.actions(0).stream().anyMatch(a -> GuardDrillScript.moves(a.clip())));
        assertTrue(plan.actions(0).stream().anyMatch(a -> a.clip() == GuardDrillScript.PARRY_HIGH
            || a.clip() == GuardDrillScript.PARRY_LOW), "guards against an imagined blow too");
        for (GuardDrillScript.Contact c : plan.contacts()) {
            assertEquals(GuardDrillScript.SOLO, c.attacker());
            assertEquals(GuardDrillScript.Answer.NONE, c.answer());
        }
        assertFalse(plan.contacts().isEmpty());
    }

    @Test
    void contactWindowsAreHalfOpenSoNoBeatIsCountedTwice() {
        GuardDrillScript.Plan plan = GuardDrillScript.plan(99, true, LEN);
        int counted = 0;
        for (float t = -1; t < LEN + 100; t += 1.0F) {
            counted += GuardDrillScript.contactsBetween(plan, t, t + 1.0F).size();
        }
        assertEquals(plan.contacts().size(), counted);
    }

    @Test
    void clipContractsAreConsistent() {
        for (float[] table : new float[][] {GuardDrillScript.LENGTH_S, GuardDrillScript.CONTACT_S,
            GuardDrillScript.CHAIN_S, GuardDrillScript.MOVE_LEFT, GuardDrillScript.MOVE_FWD}) {
            assertEquals(GuardDrillScript.CLIPS, table.length);
        }
        for (int i = 0; i < GuardDrillScript.CLIPS; i++) {
            assertTrue(GuardDrillScript.CONTACT_S[i] < GuardDrillScript.LENGTH_S[i]);
            assertTrue(GuardDrillScript.CHAIN_S[i] < GuardDrillScript.LENGTH_S[i]);
            assertTrue(GuardDrillScript.LENGTH_S[i] > 2 * GuardDrillScript.BLEND_S, "room to fade in and out");
        }
    }
}
