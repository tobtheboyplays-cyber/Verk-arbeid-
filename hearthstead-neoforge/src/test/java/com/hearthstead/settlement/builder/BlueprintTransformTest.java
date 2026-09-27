package com.hearthstead.settlement.builder;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintTransformTest {

    @Test
    void identityKeepsEveryCell() {
        BlueprintTransform t = BlueprintTransform.identity(5, 3);
        assertEquals(2, t.x(2, 1));
        assertEquals(1, t.z(2, 1));
        assertEquals(5, t.rotatedSizeX());
        assertEquals(3, t.rotatedSizeZ());
    }

    @Test
    void clockwiseQuarterTurnMatchesVanillaMinusZX() {
        // Vanilla CLOCKWISE_90 maps (x, z) to (-z, x); translated back into
        // the footprint that is (sizeZ - 1 - z, x).
        BlueprintTransform t = new BlueprintTransform(1, false, 5, 3);
        assertEquals(3, t.rotatedSizeX());
        assertEquals(5, t.rotatedSizeZ());
        // North-east corner (4, 0) turns to south-east.
        assertEquals(2, t.x(4, 0));
        assertEquals(4, t.z(4, 0));
        // North-west corner (0, 0) turns to north-east.
        assertEquals(2, t.x(0, 0));
        assertEquals(0, t.z(0, 0));
    }

    @Test
    void mirrorFlipsXBeforeRotating() {
        BlueprintTransform m = new BlueprintTransform(0, true, 5, 3);
        assertEquals(4, m.x(0, 2));
        assertEquals(2, m.z(0, 2));
        BlueprintTransform mr = new BlueprintTransform(1, true, 5, 3);
        // mirror (0,0)->(4,0), then rotate (4,0)->(2,4)
        assertEquals(2, mr.x(0, 0));
        assertEquals(4, mr.z(0, 0));
    }

    @Test
    void everyTransformIsABijectionOntoTheRotatedFootprint() {
        int sx = 7;
        int sz = 4;
        for (int r = 0; r < 4; r++) {
            for (boolean mirror : new boolean[]{false, true}) {
                BlueprintTransform t = new BlueprintTransform(r, mirror, sx, sz);
                Set<Long> seen = new HashSet<>();
                for (int x = 0; x < sx; x++) {
                    for (int z = 0; z < sz; z++) {
                        int tx = t.x(x, z);
                        int tz = t.z(x, z);
                        assertTrue(tx >= 0 && tx < t.rotatedSizeX(), "x in footprint r=" + r);
                        assertTrue(tz >= 0 && tz < t.rotatedSizeZ(), "z in footprint r=" + r);
                        assertTrue(seen.add(((long) tx << 32) | tz), "no two cells land together");
                        assertEquals(x, t.inverseX(tx, tz), "inverse x r=" + r + " m=" + mirror);
                        assertEquals(z, t.inverseZ(tx, tz), "inverse z r=" + r + " m=" + mirror);
                    }
                }
                assertEquals(sx * sz, seen.size());
            }
        }
    }

    @Test
    void fourQuarterTurnsComeBackHome() {
        BlueprintTransform t = new BlueprintTransform(0, false, 6, 2);
        for (int i = 0; i < 4; i++) {
            t = t.rotatedClockwise();
        }
        assertEquals(0, t.rotation());
        assertEquals(t.rotation(), new BlueprintTransform(5, false, 6, 2).rotatedCounterClockwise().rotation());
    }

    @Test
    void facingsRotateClockwiseAndMirrorSwapsEastWest() {
        BlueprintTransform r1 = new BlueprintTransform(1, false, 3, 3);
        assertEquals(1, r1.facing(0)); // north -> east
        assertEquals(2, r1.facing(1)); // east -> south
        BlueprintTransform m = new BlueprintTransform(0, true, 3, 3);
        assertEquals(3, m.facing(1)); // east -> west
        assertEquals(0, m.facing(0)); // north stays north
    }
}
