package com.hearthstead.client.model;

import com.hearthstead.client.model.CarryPackRules.Look;
import com.hearthstead.client.model.CarryPackRules.Shape;
import com.hearthstead.entity.Profession;
import com.hearthstead.settlement.development.HaulGear;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CarryPackRulesTest {
    private static final Set<Profession> GOODS_MOVERS = EnumSet.of(Profession.MINER, Profession.HUNTER,
        Profession.HERDER, Profession.FISHER, Profession.BUILDER, Profession.BAKER, Profession.SMITH,
        Profession.SMELTER, Profession.TANNER, Profession.WEAVER, Profession.BREWER, Profession.COOK,
        Profession.BUTCHER, Profession.MILLER, Profession.CARPENTER, Profession.MASON, Profession.TRADER,
        Profession.INNKEEPER, Profession.FARMER, Profession.ARMOURER, Profession.SCHOLAR, Profession.HEALER);

    @Test
    void everyGoodsMoverShowsAJobContainerThatGrowsWithTheBag() {
        for (Profession p : GOODS_MOVERS) {
            assertTrue(CarryPackRules.governs(p), p + " governed");
            Look half = CarryPackRules.look(p, 0.5F, 1.0F, false, false, false);
            Look full = CarryPackRules.look(p, 1.0F, 1.0F, false, false, false);
            assertTrue(half.visible() && full.visible(), p + " container visible when carrying");
            assertNotEquals(Shape.SATCHEL, full.shape(), p + " carries its job container, not the satchel");
            assertTrue(full.scale() > half.scale(), p + " grows with fill");
            assertTrue(full.lean() > half.lean() && half.lean() > 0.0F, p + " lean scales with fill");
            assertEquals(3, full.tier(), p + " full shows all contents");
            assertTrue(full.style().contents().length >= 1 || full.shape() == Shape.BUNDLE, p + " has contents");
        }
    }

    @Test
    void jobsLookDifferent() {
        assertEquals(Shape.BASKET, CarryPackRules.styleFor(Profession.FARMER).shape());
        assertEquals(Shape.BASKET, CarryPackRules.styleFor(Profession.FISHER).shape());
        assertEquals(Shape.FRAME, CarryPackRules.styleFor(Profession.BUILDER).shape());
        assertEquals(Shape.CRATE, CarryPackRules.styleFor(Profession.SMITH).shape());
        assertEquals(Shape.BUNDLE, CarryPackRules.styleFor(Profession.TANNER).shape());
        assertEquals("minecraft:cod", CarryPackRules.styleFor(Profession.FISHER).contents()[0]);
        assertEquals("minecraft:cobblestone", CarryPackRules.styleFor(Profession.MINER).contents()[0]);
        Set<String> looks = new java.util.HashSet<>();
        for (Profession p : GOODS_MOVERS) {
            CarryPackRules.Style s = CarryPackRules.styleFor(p);
            looks.add(s.shape() + "/" + s.material() + "/" + Integer.toHexString(s.tint()) + "/"
                + String.join(",", s.contents()));
        }
        assertTrue(looks.size() >= 14, "at least 14 distinct job looks, got " + looks.size());
    }

    @Test
    void scaleUsesTheCourierRangeTimesTheSackTier() {
        assertEquals(CarryPackRules.MAX_SCALE, CarryPackRules.scale(1.0F, 1.0F), 1.0E-5F);
        assertEquals(CarryPackRules.MIN_SCALE + (CarryPackRules.MAX_SCALE - CarryPackRules.MIN_SCALE) * 0.5F,
            CarryPackRules.scale(0.5F, 1.0F), 1.0E-5F);
        assertEquals(CarryPackRules.MAX_SCALE * HaulGear.visualScale(2),
            CarryPackRules.scale(1.0F, HaulGear.visualScale(2)), 1.0E-5F);
        // Deflate: monotonic all the way down, and small just before the satchel swap.
        float previous = Float.MAX_VALUE;
        for (int i = 100; i >= 1; i--) {
            float s = CarryPackRules.scale(i / 100.0F, 1.0F);
            assertTrue(s <= previous + 1.0E-6F, "monotonic at fill " + i);
            previous = s;
        }
        assertTrue(CarryPackRules.scale(0.002F, 1.0F) < 0.4F, "near nothing just before empty");
    }

    @Test
    void emptyIdleShowsASatchelOrNothingForToolRigs() {
        assertEquals(Shape.SATCHEL, CarryPackRules.look(Profession.BAKER, 0.0F, 1.0F, false, false, false).shape());
        assertEquals(Shape.SATCHEL, CarryPackRules.look(Profession.MINER, 0.0F, 1.0F, false, false, false).shape());
        for (Profession p : new Profession[] {Profession.GUARD, Profession.ARCHER, Profession.FISHER,
                Profession.SPEARMAN, Profession.LONGSWORDSMAN}) {
            assertFalse(CarryPackRules.look(p, 0.0F, 1.0F, false, false, false).visible(), p + " rig, no satchel");
        }
    }

    @Test
    void cartPlacedContainerAndSwingsFreeTheBack() {
        assertFalse(CarryPackRules.look(Profession.MINER, 1.0F, 1.0F, true, false, false).visible(), "cart");
        assertFalse(CarryPackRules.look(Profession.MINER, 1.0F, 1.0F, false, true, false).visible(), "placed");
        assertFalse(CarryPackRules.look(Profession.MINER, 1.0F, 1.0F, false, false, true).visible(), "swing stow");
        assertEquals(0.0F, CarryPackRules.look(Profession.MINER, 1.0F, 1.0F, false, false, true).lean());
        // A soft sack sits flush and stays through a swing.
        assertTrue(CarryPackRules.look(Profession.HUNTER, 1.0F, 1.0F, false, false, true).visible());
    }

    @Test
    void courierAndLumbererKeepTheirAuthoredParts() {
        assertFalse(CarryPackRules.governs(Profession.COURIER));
        assertFalse(CarryPackRules.governs(Profession.LUMBERER));
        assertFalse(CarryPackRules.look(Profession.COURIER, 1.0F, 1.0F, false, false, false).visible());
    }

    /** Clearance: no container reaches past the torso sides, where a back-swinging arm passes. */
    @Test
    void noContainerReachesTheArmsAtAnyFillOrTier() {
        for (Profession p : Profession.values()) {
            for (int tier = 0; tier <= HaulGear.MAX_TIER; tier++) {
                for (int f = 0; f <= 20; f++) {
                    Look look = CarryPackRules.look(p, f / 20.0F, HaulGear.visualScale(tier), false, false, false);
                    if (!look.visible()) continue;
                    float half = CarryPackRules.halfWidth(look.shape())
                        * CarryPackRules.lateralScale(look.shape(), look.scale());
                    assertTrue(half <= CarryPackRules.TORSO_HALF_WIDTH - CarryPackRules.ARM_CLEARANCE + 1.0E-4F,
                        p + " tier " + tier + " fill " + f / 20.0F + " half-width " + half);
                }
            }
        }
    }

    /** Carry pack lane guard: every profession has an explicit rule; nobody falls back to a default by accident. */
    @Test
    void everyProfessionHasAnExplicitPackRule() {
        for (Profession p : Profession.values()) {
            CarryPackRules.Kind kind = CarryPackRules.kind(p);
            assertTrue(kind != null, p + " has a kind");
            CarryPackRules.Style style = CarryPackRules.styleFor(p);
            switch (kind) {
                case JOB -> {
                    assertNotEquals(Shape.NONE, style.shape(), p + " job pack has a shape");
                    assertNotEquals(Shape.SATCHEL, style.shape(), p + " job pack is more than the satchel");
                    assertTrue(style.contents().length == 3, p + " shows three goods as it fills");
                    Look full = CarryPackRules.look(p, 1.0F, 1.0F, false, false, false);
                    assertEquals(style.shape(), full.shape(), p + " loaded shows its own container");
                }
                case AUTHORED, KIT -> {
                    for (int f = 0; f <= 10; f++) {
                        assertFalse(CarryPackRules.look(p, f / 10.0F, 1.45F, false, false, false).visible(),
                            p + " never draws a carry pack (fill " + f / 10.0F + ")");
                    }
                }
                case SATCHEL_ONLY -> {
                    Look full = CarryPackRules.look(p, 1.0F, 1.45F, false, false, false);
                    assertEquals(Shape.SATCHEL, full.shape(), p + " keeps the satchel");
                    assertEquals(0.0F, full.lean(), p + " never leans");
                    assertEquals(1.0F, full.scale(), p + " never grows");
                }
            }
        }
        // Only the jobless settler wears the plain sack; every trade has its own look.
        Set<CarryPackRules.Style> seen = new java.util.HashSet<>();
        for (Profession p : Profession.values()) {
            if (CarryPackRules.kind(p) == CarryPackRules.Kind.JOB && p != Profession.NONE) {
                assertNotEquals(CarryPackRules.styleFor(Profession.NONE), CarryPackRules.styleFor(p),
                    p + " must not fall back to the plain sack");
            }
            seen.add(CarryPackRules.styleFor(p));
        }
    }

    @Test
    void thePackMatchesTheJob() {
        assertEquals(CarryPackRules.Kind.KIT, CarryPackRules.kind(Profession.ARCHER), "archer keeps the quiver");
        assertEquals(CarryPackRules.Kind.KIT, CarryPackRules.kind(Profession.GUARD));
        assertEquals(CarryPackRules.Kind.AUTHORED, CarryPackRules.kind(Profession.COURIER));
        assertEquals(CarryPackRules.Kind.AUTHORED, CarryPackRules.kind(Profession.LUMBERER));
        assertEquals(CarryPackRules.Kind.SATCHEL_ONLY, CarryPackRules.kind(Profession.MAYOR));
        assertEquals(Shape.BASKET, CarryPackRules.styleFor(Profession.FISHER).shape(), "fisher: a creel");
        assertEquals("minecraft:cod", CarryPackRules.styleFor(Profession.FISHER).contents()[0]);
        assertEquals(Shape.FRAME, CarryPackRules.styleFor(Profession.MASON).shape(), "mason: stone on a frame");
        assertEquals("minecraft:stone_bricks", CarryPackRules.styleFor(Profession.MASON).contents()[0]);
        assertEquals(Shape.SACK, CarryPackRules.styleFor(Profession.HUNTER).shape(), "hunter: a game bag");
        for (String id : CarryPackRules.styleFor(Profession.TRADER).contents()) {
            assertFalse(id.contains("gold_nugget"), "trader: no coin at the pack (the purse is at the hip)");
        }
    }

    @Test
    void thePackComesOffInBedOnASeatAndUnderACarcass() {
        for (Profession p : Profession.values()) {
            for (float fill : new float[] {0.0F, 0.5F, 1.0F}) {
                assertFalse(CarryPackRules.look(p, fill, 1.0F, false, false, false, true).visible(),
                    p + " off the back at fill " + fill);
            }
        }
        assertTrue(CarryPackRules.look(Profession.BAKER, 0.5F, 1.0F, false, false, false, false).visible());
    }

    @Test
    void heightIsCappedSoATierThreePackStaysAboveTheBelt() {
        float s = CarryPackRules.scale(1.0F, HaulGear.visualScale(HaulGear.MAX_TIER));
        assertTrue(s > CarryPackRules.VERTICAL_CAP, "tier 3 would grow past the cap");
        assertEquals(CarryPackRules.VERTICAL_CAP, CarryPackRules.verticalScale(s), 1.0E-6F);
        // Pack frame: pivot 10.5 px above the belt line; the tallest body (FRAME, 9 px) must end above it.
        assertTrue(9.0F * CarryPackRules.VERTICAL_CAP <= 10.5F, "bottom stays above the belt");
        assertEquals(0.9F, CarryPackRules.verticalScale(0.9F), 1.0E-6F);
    }

    @Test
    void stylesAreBuiltOnceAndNotPerFrame() {
        for (Profession p : Profession.values()) {
            assertTrue(CarryPackRules.styleFor(p) == CarryPackRules.styleFor(p), p + " style cached");
        }
        assertTrue(CarryPackRules.look(Profession.BAKER, 0.0F, 1.0F, false, false, false)
            == CarryPackRules.look(Profession.BAKER, 0.0F, 1.0F, false, false, false), "empty satchel look cached");
    }
}
