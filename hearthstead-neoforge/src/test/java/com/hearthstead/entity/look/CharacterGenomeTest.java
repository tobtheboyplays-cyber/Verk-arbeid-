package com.hearthstead.entity.look;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

/**
 * The look genome is the one piece of state that must be identical on the
 * server, on every client and after every reload. Golden values come from
 * tools/skins/genome.py --golden (the contact sheets' decoder), so the Java
 * and Python decoders can never drift apart silently.
 */
class CharacterGenomeTest {
    // seed, hint, then sex age build skin style color beard eyes brows mark clothing nose
    private static final int[][] GOLDEN = {
        {0, -1, 1, 0, 0, 2, 6, 5, 0, 4, 1, 4, 0, 3},
        {0, 0, 0, 0, 0, 2, 3, 5, 0, 4, 1, 4, 0, 3},
        {0, 1, 1, 0, 0, 2, 6, 5, 0, 4, 1, 4, 0, 3},
        {1, -1, 0, 1, 1, 0, 11, 2, 3, 2, 2, 0, 5, 0},
        {1, 0, 0, 1, 1, 0, 11, 2, 3, 2, 2, 0, 5, 0},
        {1, 1, 1, 1, 1, 0, 8, 2, 0, 2, 2, 0, 5, 0},
        {-1, -1, 1, 0, 0, 4, 4, 0, 0, 2, 1, 2, 7, 0},
        {-1, 0, 0, 0, 0, 4, 4, 0, 0, 2, 0, 2, 7, 0},
        {-1, 1, 1, 0, 0, 4, 4, 0, 0, 2, 1, 2, 7, 0},
        {42, -1, 1, 1, 1, 0, 10, 0, 0, 3, 0, 4, 3, 2},
        {42, 0, 0, 1, 1, 0, 3, 0, 0, 3, 1, 4, 3, 2},
        {42, 1, 1, 1, 1, 0, 10, 0, 0, 3, 0, 4, 3, 2},
        {123456789, -1, 1, 2, 0, 2, 9, 7, 0, 0, 1, 3, 5, 1},
        {123456789, 0, 0, 2, 0, 2, 0, 7, 3, 0, 1, 3, 5, 1},
        {123456789, 1, 1, 2, 0, 2, 9, 7, 0, 0, 1, 3, 5, 1},
        {-987654321, -1, 0, 0, 1, 6, 4, 1, 0, 2, 2, 0, 1, 0},
        {-987654321, 0, 0, 0, 1, 6, 4, 1, 0, 2, 2, 0, 1, 0},
        {-987654321, 1, 1, 0, 1, 6, 5, 1, 0, 2, 1, 0, 1, 0},
        {2147483647, -1, 1, 1, 2, 1, 10, 5, 0, 1, 0, 4, 1, 2},
        {2147483647, 0, 0, 1, 2, 1, 2, 5, 5, 1, 0, 4, 1, 2},
        {2147483647, 1, 1, 1, 2, 1, 10, 5, 0, 1, 0, 4, 1, 2},
        {-2147483648, -1, 1, 1, 0, 2, 8, 7, 0, 4, 0, 1, 2, 0},
        {-2147483648, 0, 0, 1, 0, 2, 1, 7, 0, 4, 2, 1, 2, 0},
        {-2147483648, 1, 1, 1, 0, 2, 8, 7, 0, 4, 0, 1, 2, 0},
    };

    @Test
    void matchesThePythonDecoderBitForBit() {
        for (int[] row : GOLDEN) {
            int[] expected = java.util.Arrays.copyOfRange(row, 2, row.length);
            assertArrayEquals(expected, CharacterGenome.decode(row[0], row[1]).toArray(),
                "seed " + row[0] + " hint " + row[1]);
        }
    }

    @Test
    void deterministicAndInRange() {
        Random r = new Random(7);
        for (int i = 0; i < 20000; i++) {
            int seed = r.nextInt();
            CharacterGenome a = CharacterGenome.decode(seed, -1);
            assertEquals(a, CharacterGenome.decode(seed, -1));
            assertTrue(a.skinTone() >= 0 && a.skinTone() < CharacterGenome.SKIN_COUNT);
            assertTrue(a.hairStyle() >= 0 && a.hairStyle() < CharacterGenome.HAIR_STYLE_COUNT);
            assertTrue(a.hairColor() >= 0 && a.hairColor() < CharacterGenome.HAIR_COLOR_COUNT);
            assertTrue(a.beard() >= 0 && a.beard() < CharacterGenome.BEARD_COUNT);
            assertTrue(a.eyes() >= 0 && a.eyes() < CharacterGenome.EYES_COUNT);
            assertTrue(a.brows() >= 0 && a.brows() < CharacterGenome.BROWS_COUNT);
            assertTrue(a.mark() >= 0 && a.mark() < CharacterGenome.MARK_COUNT);
            assertTrue(a.clothing() >= 0 && a.clothing() < CharacterGenome.CLOTHING_COUNT);
            assertTrue(a.nose() >= 0 && a.nose() < CharacterGenome.NOSE_COUNT);
            assertTrue(a.age() >= 0 && a.age() < 3 && a.build() >= 0 && a.build() < 3);
            if (a.sex() == 1) {
                assertEquals(0, a.beard(), "feminine presentation never gets a beard");
            }
            CharacterGenome hinted = CharacterGenome.decode(seed, 1);
            assertEquals(1, hinted.sex());
            assertEquals(a.skinTone(), hinted.skinTone(), "a name hint changes only the sex draw");
            assertEquals(a.clothing(), hinted.clothing());
        }
    }

    @Test
    void wideVarietyNoCrowdOfClones() {
        java.util.Set<CharacterGenome> seen = new java.util.HashSet<>();
        Random r = new Random(11);
        for (int i = 0; i < 1000; i++) {
            seen.add(CharacterGenome.decode(r.nextInt(), -1));
        }
        assertTrue(seen.size() > 990, "1000 random settlers should be (nearly) all distinct, got " + seen.size());
    }

    @Test
    void genomeRoundTripsThroughTheSavedSeed() {
        UUID id = UUID.fromString("5f0c7c1e-2a9b-4e61-9d51-0d2b4f1e8a77");
        int seed = 0x13572468;
        CompoundTag tag = new CompoundTag();
        tag.putInt("Appearance", seed);
        CompoundTag reloaded = copy(tag);
        int restored = reloaded.contains("Appearance") ? reloaded.getInt("Appearance") : id.hashCode();
        assertEquals(CharacterGenome.decode(seed, -1), CharacterGenome.decode(restored, -1));
    }

    @Test
    void oldSavesWithoutASeedDeriveAStableLookFromTheUuid() {
        UUID id = UUID.fromString("0b8e5a4c-1f22-4c3e-8a6f-3c5d7e9f1a2b");
        CompoundTag legacy = new CompoundTag();
        int first = legacy.contains("Appearance") ? legacy.getInt("Appearance") : CharacterGenome.seedFor(id);
        int second = copy(legacy).contains("Appearance") ? 0 : CharacterGenome.seedFor(id);
        assertEquals(first, second);
        assertEquals(CharacterGenome.decode(first, -1), CharacterGenome.decode(second, -1));
        assertEquals(id.hashCode(), CharacterGenome.seedFor(id), "same fallback SettlerEntity uses");
    }

    @Test
    void namesImplyPresentation() {
        assertEquals(0, CharacterGenome.presentationOf("Aldric"));
        assertEquals(1, CharacterGenome.presentationOf("Sigrun"));
        assertEquals(1, CharacterGenome.presentationOf("[!] Liv the Younger, waiting"));
        assertEquals(0, CharacterGenome.presentationOf("Grimr"));
        assertEquals(-1, CharacterGenome.presentationOf("Settler"));
        assertEquals(-1, CharacterGenome.presentationOf(null));
    }

    @Test
    void captainLookFollowsTheNameAndTheEpithetOnlyRecolours() {
        CaptainLook skarde = CharacterLooks.captainLook(CharacterLooks.crc32("Skarde"), "the Ashen");
        assertEquals(new CaptainLook(1, 0, 1, 1, 1, 1), skarde);
        CaptainLook grimr = CharacterLooks.captainLook(CharacterLooks.crc32("Grimr"), "Red-Handed");
        assertEquals(new CaptainLook(2, 4, 4, 4, 1, 6), grimr);
        CaptainLook grimrLater = CharacterLooks.captainLook(CharacterLooks.crc32("Grimr"), "the Reaper");
        assertEquals(grimr.helm(), grimrLater.helm());
        assertEquals(grimr.paint(), grimrLater.paint());
        assertEquals(grimr.scar(), grimrLater.scar());
        assertEquals(6, grimrLater.scheme());
        assertEquals("Grimr", CharacterLooks.firstName("Grimr the Torch, sworn to Kettil"));
        assertEquals("the Torch", CharacterLooks.epithetOf("Grimr the Torch, sworn to Kettil"));
        assertEquals(0, CharacterLooks.schemeFor(""));
        assertEquals(5, CharacterLooks.schemeFor("Chain-Bringer"));
        assertEquals(3, CharacterLooks.schemeFor("Ember-Bringer"));
        assertEquals(4, CharacterLooks.schemeFor("Larder's Bane"));
    }

    @Test
    void costumesAndVariantsAreStable() {
        assertEquals(CharacterLooks.COSTUME_REFUGEE, CharacterLooks.costumeForRole("refugee_leader"));
        assertEquals(CharacterLooks.COSTUME_MINSTREL, CharacterLooks.costumeForRole("minstrel"));
        assertEquals(CharacterLooks.COSTUME_ENVOY, CharacterLooks.costumeForRole("rival_envoy"));
        assertEquals(CharacterLooks.COSTUME_ATTENDANT, CharacterLooks.costumeForRole("envoy_attendant"));
        assertEquals(CharacterLooks.COSTUME_NONE, CharacterLooks.costumeForRole("peddler"));
        UUID id = UUID.randomUUID();
        for (int c = 1; c < CharacterLooks.COSTUME_KEYS.length; c++) {
            int v = CharacterLooks.costumeVariant(id, c);
            assertEquals(v, CharacterLooks.costumeVariant(id, c));
            assertTrue(v >= 0 && v < CharacterLooks.COSTUME_VARIANTS[c]);
        }
        int[] hits = new int[CharacterLooks.SKIRMISHER_VARIANTS];
        Random r = new Random(3);
        for (int i = 0; i < 600; i++) {
            hits[CharacterLooks.raiderVariant(new UUID(r.nextLong(), r.nextLong()), hits.length)]++;
        }
        for (int h : hits) {
            assertTrue(h > 50, "a raid band mixes every skirmisher variant");
        }
    }

    private static CompoundTag copy(CompoundTag tag) {
        return tag.copy();
    }
}
