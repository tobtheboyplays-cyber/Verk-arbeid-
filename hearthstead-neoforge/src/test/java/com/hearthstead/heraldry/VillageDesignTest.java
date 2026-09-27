package com.hearthstead.heraldry;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Banner designer data: validation, the shape codec, NBT round trips and presets. */
class VillageDesignTest {
    /** The vanilla 1.21.1 banner patterns (what the server registry holds). */
    private static final Set<String> VANILLA = Set.of("base", "square_bottom_left", "square_bottom_right",
        "square_top_left", "square_top_right", "stripe_bottom", "stripe_top", "stripe_left", "stripe_right",
        "stripe_center", "stripe_middle", "stripe_downright", "stripe_downleft", "small_stripes", "cross",
        "straight_cross", "triangle_bottom", "triangle_top", "triangles_bottom", "triangles_top", "diagonal_left",
        "diagonal_up_right", "diagonal_up_left", "diagonal_right", "circle", "rhombus", "half_vertical",
        "half_horizontal", "half_vertical_right", "half_horizontal_bottom", "border", "curly_border", "gradient",
        "gradient_up", "bricks", "globe", "creeper", "skull", "flower", "mojang", "piglin", "flow", "guster");
    private static final Predicate<ResourceLocation> KNOWN =
        id -> "minecraft".equals(id.getNamespace()) && VANILLA.contains(id.getPath());

    private static VillageDesign.Layer layer(String pattern, DyeColor color) {
        return new VillageDesign.Layer(VillageDesign.vanilla(pattern), color);
    }

    @Test
    void foundingDesignIsLegalAndKeepsTheOldLook() {
        assertEquals(VillageDesign.Problem.NONE, VillageDesign.validate(VillageDesign.FOUNDING, KNOWN));
        assertEquals(DyeColor.RED, VillageDesign.FOUNDING.base());
        assertEquals(BannerShape.STRAIGHT, VillageDesign.FOUNDING.shape());
        assertEquals(DyeColor.YELLOW, VillageDesign.FOUNDING.trim());
    }

    @Test
    void validationRefusesTooManyLayersUnknownPatternsAndTheFieldPattern() {
        List<VillageDesign.Layer> six = new ArrayList<>();
        for (int i = 0; i < VillageDesign.MAX_LAYERS; i++) six.add(layer("border", DyeColor.WHITE));
        assertEquals(VillageDesign.Problem.NONE,
            VillageDesign.validate(new VillageDesign(DyeColor.BLUE, six, BannerShape.PENNANT), KNOWN));
        List<VillageDesign.Layer> seven = new ArrayList<>(six);
        seven.add(layer("circle", DyeColor.RED));
        assertEquals(VillageDesign.Problem.TOO_MANY_LAYERS,
            VillageDesign.validate(new VillageDesign(DyeColor.BLUE, seven, BannerShape.STRAIGHT), KNOWN));
        assertEquals(VillageDesign.Problem.UNKNOWN_PATTERN, VillageDesign.validate(new VillageDesign(DyeColor.BLUE,
            List.of(new VillageDesign.Layer(ResourceLocation.fromNamespaceAndPath("evil", "x"), DyeColor.RED)),
            BannerShape.STRAIGHT), KNOWN));
        assertEquals(VillageDesign.Problem.FIELD_PATTERN, VillageDesign.validate(new VillageDesign(DyeColor.BLUE,
            List.of(layer("base", DyeColor.RED)), BannerShape.STRAIGHT), KNOWN));
        assertEquals(VillageDesign.Problem.UNKNOWN_PATTERN, VillageDesign.validate(VillageDesign.INVALID, KNOWN),
            "the out-of-range wire marker is never legal");
    }

    @Test
    void everyCuratedPatternAndPresetIsLegalAndNamed() {
        Set<String> seen = new HashSet<>();
        for (String id : HeraldryCatalog.PATTERNS) {
            assertTrue(VANILLA.contains(id), "curated pattern must be vanilla: " + id);
            assertTrue(seen.add(id), "duplicate curated pattern " + id);
            assertNotEquals("hearthstead.heraldry.pattern.other", HeraldryCatalog.patternKey(VillageDesign.vanilla(id)));
        }
        assertTrue(HeraldryCatalog.PATTERNS.size() >= 30, "a broad selection");
        assertEquals(12, HeraldryCatalog.PRESETS.size());
        Set<String> presetIds = new HashSet<>();
        Set<VillageDesign> designs = new HashSet<>();
        for (HeraldryCatalog.Preset preset : HeraldryCatalog.PRESETS) {
            assertTrue(presetIds.add(preset.id()));
            assertTrue(designs.add(preset.design()), "presets must differ: " + preset.id());
            assertEquals(VillageDesign.Problem.NONE, VillageDesign.validate(preset.design(), KNOWN), preset.id());
            assertNotEquals(preset.design().base(), preset.design().trim(), "a visible trim: " + preset.id());
        }
    }

    @Test
    void shapeCodecRoundTripsAndFallsBackToStraight() {
        for (BannerShape shape : BannerShape.values()) {
            assertEquals(shape, BannerShape.byId(shape.id()));
            assertEquals(shape, BannerShape.byId(" " + shape.id().toUpperCase() + " "));
            assertEquals(shape, BannerShape.byOrdinal(shape.ordinal()));
        }
        assertEquals(BannerShape.STRAIGHT, BannerShape.byId("zigzag"));
        assertEquals(BannerShape.STRAIGHT, BannerShape.byId(""));
        assertEquals(BannerShape.STRAIGHT, BannerShape.byId(null));
        assertEquals(null, BannerShape.byOrdinal(-1));
        assertEquals(null, BannerShape.byOrdinal(BannerShape.values().length));
    }

    @Test
    void shapesStayInsideTheClothAndFaceLikeVanilla() {
        for (BannerShape shape : BannerShape.values()) {
            float area = 0;
            for (float[] q : shape.quads()) {
                assertEquals(8, q.length);
                for (int i = 0; i < 4; i++) {
                    float x = q[i * 2];
                    float y = q[i * 2 + 1];
                    assertTrue(x >= BannerShape.LEFT && x <= BannerShape.RIGHT && y >= BannerShape.TOP
                        && y <= BannerShape.BOTTOM, shape + " corner outside the cloth: " + x + "," + y);
                }
                float a = BannerShape.signedArea(q);
                assertTrue(a < 0, shape + " quad must use the front-face winding, area " + a);
                area -= a;
            }
            float[] o = shape.outline();
            float outline = 0;
            for (int i = 0; i < o.length / 2; i++) {
                int j = (i + 1) % (o.length / 2);
                outline += o[i * 2] * o[j * 2 + 1] - o[j * 2] * o[i * 2 + 1];
            }
            outline = Math.abs(outline) / 2F;
            assertEquals(outline, area, 0.01F, shape + ": quads must tile exactly the outline");
            assertTrue(area <= 800F + 0.01F);
        }
        assertEquals(800F, -BannerShape.signedArea(BannerShape.STRAIGHT.quads().get(0)), 0.001F);
    }

    @Test
    void nbtRoundTripsAndOldOrBrokenDataLoadsSafely() {
        VillageDesign design = new VillageDesign(DyeColor.CYAN,
            List.of(layer("stripe_middle", DyeColor.WHITE), layer("triangles_top", DyeColor.BLUE)),
            BannerShape.SWALLOWTAIL);
        assertEquals(design, VillageDesign.load(design.save()));
        assertEquals(VillageDesign.FOUNDING, VillageDesign.load(VillageDesign.FOUNDING.save()));

        CompoundTag empty = new CompoundTag();
        VillageDesign fromEmpty = VillageDesign.load(empty);
        assertEquals(VillageDesign.FOUNDING.base(), fromEmpty.base());
        assertEquals(BannerShape.STRAIGHT, fromEmpty.shape());
        assertTrue(fromEmpty.layers().isEmpty());

        CompoundTag broken = design.save();
        broken.putString("Shape", "flying_carpet");
        broken.putString("Base", "ultraviolet");
        ListTag layers = broken.getList("Layers", 10);
        CompoundTag bad = new CompoundTag();
        bad.putString("Pattern", "minecraft:circle");
        bad.putString("Color", "not_a_dye");
        layers.add(bad);
        VillageDesign loaded = VillageDesign.load(broken);
        assertEquals(BannerShape.STRAIGHT, loaded.shape());
        assertEquals(DyeColor.RED, loaded.base());
        assertEquals(2, loaded.layers().size(), "a layer with an unknown colour is dropped");
    }

    @Test
    void trimIsTheFirstContrastingLayerColour() {
        VillageDesign d = new VillageDesign(DyeColor.WHITE,
            List.of(layer("border", DyeColor.WHITE), layer("cross", DyeColor.RED)), BannerShape.STRAIGHT);
        assertEquals(DyeColor.RED, d.trim());
        assertEquals(DyeColor.YELLOW, new VillageDesign(DyeColor.BLUE, List.of(), BannerShape.STRAIGHT).trim());
        assertEquals(DyeColor.WHITE, new VillageDesign(DyeColor.YELLOW, List.of(), BannerShape.STRAIGHT).trim());
    }

    /**
     * Item accounting: reading a banner's design never changes or consumes
     * the banner item, and a design is data only (no item inside it).
     */
    @Test
    void readingADesignFromABannerLeavesTheItemUntouched() {
        net.minecraft.world.item.ItemStack banner = new net.minecraft.world.item.ItemStack(
            net.minecraft.world.item.Items.BLUE_BANNER, 2);
        net.minecraft.world.item.ItemStack before = banner.copy();
        VillageDesign design = VillageDesign.fromBanner(banner, BannerShape.POINTED);
        assertTrue(net.minecraft.world.item.ItemStack.matches(before, banner), "the item must be untouched");
        assertEquals(2, banner.getCount());
        assertEquals(DyeColor.BLUE, design.base());
        assertEquals(BannerShape.POINTED, design.shape(), "a hung banner keeps the chosen cloth shape");
        assertTrue(design.layers().isEmpty());
        VillageDesign none = VillageDesign.fromBanner(net.minecraft.world.item.ItemStack.EMPTY, BannerShape.PENNANT);
        assertEquals(VillageDesign.FOUNDING.withShape(BannerShape.PENNANT), none,
            "no banner: the founding colours, never an empty banner");
    }
}
