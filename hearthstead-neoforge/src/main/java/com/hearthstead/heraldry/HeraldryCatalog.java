package com.hearthstead.heraldry;

import com.hearthstead.heraldry.VillageDesign.Layer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;

import java.util.List;

/**
 * What the Banner designer offers: a curated set of vanilla banner patterns
 * (divisions, ordinaries and charges that read well on a village banner; the
 * logo-like ones are left out) and twelve ready-made heraldic presets. The
 * server accepts any registered vanilla pattern, so a design copied from a
 * hung banner stays legal; the picker only offers these.
 */
public final class HeraldryCatalog {
    private HeraldryCatalog() {
    }

    /** Curated pattern ids (vanilla namespace), in picker order. */
    public static final List<String> PATTERNS = List.of(
        // Divisions
        "half_vertical", "half_vertical_right", "half_horizontal", "half_horizontal_bottom",
        "diagonal_left", "diagonal_right", "diagonal_up_left", "diagonal_up_right",
        "square_top_left", "square_top_right", "square_bottom_left", "square_bottom_right",
        // Ordinaries
        "stripe_top", "stripe_bottom", "stripe_center", "stripe_middle",
        "stripe_left", "stripe_right", "stripe_downright", "stripe_downleft",
        "straight_cross", "cross", "triangle_bottom", "triangle_top",
        "triangles_bottom", "triangles_top", "small_stripes", "border", "curly_border",
        // Charges and fields
        "circle", "rhombus", "flower", "skull", "globe", "bricks", "gradient", "gradient_up");

    public static ResourceLocation patternId(int index) {
        return VillageDesign.vanilla(PATTERNS.get(index));
    }

    /** Language key of a pattern's player-facing name; unknown ids get a generic name. */
    public static String patternKey(ResourceLocation id) {
        if ("minecraft".equals(id.getNamespace()) && PATTERNS.contains(id.getPath())) {
            return "hearthstead.heraldry.pattern." + id.getPath();
        }
        return "hearthstead.heraldry.pattern.other";
    }

    public static int indexOf(ResourceLocation id) {
        return "minecraft".equals(id.getNamespace()) ? PATTERNS.indexOf(id.getPath()) : -1;
    }

    /** A named ready-made design. */
    public record Preset(String id, VillageDesign design) {
        public String translationKey() {
            return "hearthstead.heraldry.preset." + id;
        }
    }

    private static Layer l(String pattern, DyeColor color) {
        return new Layer(VillageDesign.vanilla(pattern), color);
    }

    private static Preset p(String id, DyeColor base, BannerShape shape, Layer... layers) {
        return new Preset(id, new VillageDesign(base, List.of(layers), shape));
    }

    public static final List<Preset> PRESETS = List.of(
        new Preset("bannerhold", VillageDesign.FOUNDING),
        p("crusader", DyeColor.WHITE, BannerShape.STRAIGHT,
            l("straight_cross", DyeColor.RED)),
        p("saltire", DyeColor.BLUE, BannerShape.SWALLOWTAIL,
            l("cross", DyeColor.WHITE)),
        p("greenwood", DyeColor.GREEN, BannerShape.POINTED,
            l("triangle_bottom", DyeColor.YELLOW), l("circle", DyeColor.YELLOW)),
        p("ironhold", DyeColor.GRAY, BannerShape.STRAIGHT,
            l("stripe_top", DyeColor.BLACK), l("rhombus", DyeColor.WHITE), l("border", DyeColor.BLACK)),
        p("sunward", DyeColor.YELLOW, BannerShape.TONGUED,
            l("circle", DyeColor.ORANGE), l("curly_border", DyeColor.ORANGE)),
        p("nightwatch", DyeColor.BLACK, BannerShape.SWALLOWTAIL,
            l("stripe_center", DyeColor.WHITE), l("skull", DyeColor.WHITE)),
        p("harvest", DyeColor.BROWN, BannerShape.STRAIGHT,
            l("half_horizontal_bottom", DyeColor.YELLOW), l("flower", DyeColor.ORANGE)),
        p("seamark", DyeColor.CYAN, BannerShape.PENNANT,
            l("stripe_middle", DyeColor.WHITE), l("triangles_top", DyeColor.BLUE)),
        p("quartered", DyeColor.RED, BannerShape.STRAIGHT,
            l("square_top_left", DyeColor.WHITE), l("square_bottom_right", DyeColor.WHITE),
            l("straight_cross", DyeColor.BLACK)),
        p("royal", DyeColor.PURPLE, BannerShape.POINTED,
            l("border", DyeColor.YELLOW), l("rhombus", DyeColor.YELLOW), l("circle", DyeColor.PURPLE)),
        p("stonewall", DyeColor.LIGHT_GRAY, BannerShape.TONGUED,
            l("bricks", DyeColor.GRAY), l("stripe_top", DyeColor.RED)));
}
