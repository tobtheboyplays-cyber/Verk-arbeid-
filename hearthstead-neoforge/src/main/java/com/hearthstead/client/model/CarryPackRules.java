package com.hearthstead.client.model;

import com.hearthstead.entity.Profession;

/**
 * What a settler carries on its back, per job -- pure presentation rules,
 * shared by {@link SettlerModel} (visibility, scale, carry lean) and
 * {@code CarryPackLayer} (the job-flavoured container and its visible
 * contents). No client classes, so the whole table is unit-testable.
 *
 * <p>One mechanic for every job that moves goods: the container grows with
 * the (client-smoothed, so it deflates over the existing 8-tick blend) bag
 * fill between the courier's min/max scale, times the HaulGear sack tier
 * scale, and the spine leans with the fill. Contents show in three tiers.
 *
 * <p>The courier (canvas sack with shoulder handoffs) and the lumberer (log
 * frame) keep their authored model parts; {@link #governs} is false for them.
 */
public final class CarryPackRules {
    private CarryPackRules() {
    }

    /** Container silhouette. Every shape shares the carried sack's frame (pivot top-back, +y down, +z back). */
    public enum Shape {
        /** Nothing on the back (tool rig, martial kit, cart, placed on the ground, or stowed for a swing). */
        NONE,
        /** Small flat satchel: the empty, idle default. */
        SATCHEL,
        /** Soft cinched sack (flour, wool, game, merchant pack, herbs, books). */
        SACK,
        /** Open woven basket / creel / hod: contents show above the rim. */
        BASKET,
        /** Wooden crate or bottle case. */
        CRATE,
        /** Strapped roll: hides, cloth bolts. */
        BUNDLE,
        /** Carrying frame with lashed planks or stone. */
        FRAME
    }

    /** Texture key the layer maps to a vanilla block texture. */
    public enum Material { WOOL, LEATHER, WICKER, STRAW, PLANKS, BARREL, LOG }

    /**
     * Per-job look.
     *
     * @param tint     ARGB multiplied over the material texture
     * @param contents item ids revealed one per content tier (up to 3)
     */
    public record Style(Shape shape, Material material, int tint, String... contents) {
    }

    /** One frame's decision. */
    public record Look(Shape shape, Style style, float scale, float lean, int tier) {
        public boolean visible() {
            return shape != Shape.NONE;
        }
    }

    public static final float MIN_SCALE = 0.80F;
    public static final float MAX_SCALE = 1.05F;
    /** Below this fill the container shrinks toward nothing, so the swap to the satchel never pops. */
    static final float DEFLATE_FILL = 0.12F;
    static final float SOFT_LEAN = 0.08F;
    static final float HARD_LEAN = 0.14F;
    /** Torso half-width in model px; a container must stay inside it or a back-swinging arm clips it. */
    public static final float TORSO_HALF_WIDTH = 5.0F;

    private static final Style PLAIN = new Style(Shape.SACK, Material.WOOL, 0xFFB8A488);
    private static final Look NONE = new Look(Shape.NONE, PLAIN, 1.0F, 0.0F, 0);

    /** False for jobs whose back container is an authored model part (courier sack, lumber frame). */
    public static boolean governs(Profession p) {
        return p != Profession.COURIER && p != Profession.LUMBERER;
    }

    /** Jobs that carry a tool rig or martial kit on the back instead of an empty satchel. */
    public static boolean rigWhenEmpty(Profession p) {
        return switch (p) {
            case GUARD, SPEARMAN, LONGSWORDSMAN, ARCHER, FISHER, LUMBERER -> true;
            default -> false;
        };
    }

    public static Style styleFor(Profession p) {
        return switch (p) {
            case FARMER -> new Style(Shape.BASKET, Material.WICKER, 0xFFFFFFFF,
                "minecraft:wheat", "minecraft:carrot", "minecraft:potato");
            case FISHER -> new Style(Shape.BASKET, Material.WICKER, 0xFFD8C8A0,
                "minecraft:cod", "minecraft:salmon", "minecraft:cod");
            case MINER -> new Style(Shape.BASKET, Material.PLANKS, 0xFFB09070,
                "minecraft:cobblestone", "minecraft:raw_iron", "minecraft:coal");
            case HUNTER -> new Style(Shape.SACK, Material.LEATHER, 0xFF8A6A4A,
                "minecraft:rabbit_hide", "minecraft:feather", "minecraft:leather");
            case HERDER -> new Style(Shape.SACK, Material.WOOL, 0xFFF2EEE4,
                "minecraft:white_wool", "minecraft:milk_bucket", "minecraft:white_wool");
            case BUILDER -> new Style(Shape.FRAME, Material.LOG, 0xFFFFFFFF,
                "minecraft:oak_planks", "minecraft:oak_planks", "minecraft:stone_bricks");
            case BAKER, MILLER -> new Style(Shape.SACK, Material.WOOL, 0xFFF4F0E6,
                "minecraft:wheat", "minecraft:sugar", "minecraft:bread");
            case SMITH, SMELTER, ARMOURER -> new Style(Shape.CRATE, Material.PLANKS, 0xFF9A8A7A,
                "minecraft:iron_ingot", "minecraft:coal", "minecraft:raw_iron");
            case TANNER -> new Style(Shape.BUNDLE, Material.LEATHER, 0xFF9A6A42,
                "minecraft:leather", "minecraft:rabbit_hide", "minecraft:leather");
            case WEAVER -> new Style(Shape.BUNDLE, Material.WOOL, 0xFFC8B8D8,
                "minecraft:white_wool", "minecraft:string", "minecraft:red_wool");
            case BREWER, INNKEEPER -> new Style(Shape.CRATE, Material.BARREL, 0xFFFFFFFF,
                "minecraft:honey_bottle", "minecraft:glass_bottle", "minecraft:wheat");
            case COOK, BUTCHER -> new Style(Shape.BASKET, Material.WICKER, 0xFFE0C8A0,
                "minecraft:beef", "minecraft:carrot", "minecraft:porkchop");
            case CARPENTER, SAWYER -> new Style(Shape.FRAME, Material.LOG, 0xFFFFFFFF,
                "minecraft:oak_planks", "minecraft:stick", "minecraft:oak_planks");
            case MASON -> new Style(Shape.FRAME, Material.LOG, 0xFFFFFFFF,
                "minecraft:stone_bricks", "minecraft:cobblestone", "minecraft:stone");
            case SCHOLAR -> new Style(Shape.SACK, Material.LEATHER, 0xFF6A4A3A,
                "minecraft:book", "minecraft:paper", "minecraft:writable_book");
            case TRADER -> new Style(Shape.SACK, Material.LEATHER, 0xFFB08850,
                "minecraft:gold_nugget", "minecraft:emerald", "minecraft:paper");
            case HEALER -> new Style(Shape.SACK, Material.WOOL, 0xFFA8B890,
                "minecraft:fern", "minecraft:sweet_berries", "minecraft:paper");
            case FLETCHER -> new Style(Shape.SACK, Material.LEATHER, 0xFF8A7050,
                "minecraft:stick", "minecraft:feather", "minecraft:flint");
            case RUNE_MAGE -> new Style(Shape.SACK, Material.WOOL, 0xFF5A6AA8,
                "minecraft:amethyst_shard", "minecraft:paper", "minecraft:amethyst_shard");
            default -> PLAIN;
        };
    }

    /** 0 (empty) .. 3 (full): how many content items show. */
    public static int contentTier(float fill) {
        if (fill <= 0.001F) return 0;
        if (fill < 0.34F) return 1;
        if (fill < 0.67F) return 2;
        return 3;
    }

    /** Fill (smoothed) to container scale, same range as the courier sack, times the sack tier scale. */
    public static float scale(float fill, float tierScale) {
        float f = Math.max(0.0F, Math.min(1.0F, fill));
        float s = (MIN_SCALE + (MAX_SCALE - MIN_SCALE) * f) * tierScale;
        if (f < DEFLATE_FILL) {
            float t = f / DEFLATE_FILL;
            s *= 0.45F + 0.55F * t * t * (3.0F - 2.0F * t);
        }
        return s;
    }

    /**
     * The per-frame decision.
     *
     * @param fill      client-smoothed bag fill 0..1
     * @param tierScale HaulGear.visualScale(sackTier)
     * @param pulling   hitched to a hand cart (the cart carries; the back is free)
     * @param detached  the work container is placed on the ground (drawn there instead)
     * @param swinging  a work swing clip is playing (overhead/backswing tools)
     */
    public static Look look(Profession p, float fill, float tierScale, boolean pulling,
                            boolean detached, boolean swinging) {
        if (!governs(p) || pulling || detached) {
            return NONE;
        }
        Style style = styleFor(p);
        if (fill <= 0.001F) {
            return rigWhenEmpty(p) ? NONE : new Look(Shape.SATCHEL, style, 1.0F, 0.0F, 0);
        }
        boolean rigid = style.shape() != Shape.SACK;
        if (swinging && rigid) {
            // Rule 6: stowed while the tool swings (like the empty lumber frame).
            return NONE;
        }
        float lean = (rigid ? HARD_LEAN : SOFT_LEAN) * Math.min(1.0F, fill);
        return new Look(style.shape(), style, scale(fill, tierScale), lean, contentTier(fill));
    }

    /** Side clearance kept inside the torso silhouette for arms swinging past the back. */
    public static final float ARM_CLEARANCE = 0.4F;

    /**
     * X (sideways) scale: the fill/tier scale, capped so the container never
     * reaches past the torso's sides where a back-swinging arm passes. Depth
     * and height still grow with the full scale.
     */
    public static float lateralScale(Shape shape, float scale) {
        float half = halfWidth(shape);
        if (half <= 0.0F) {
            return scale;
        }
        return Math.min(scale, (TORSO_HALF_WIDTH - ARM_CLEARANCE) / half);
    }

    /** Half-width in model px of each shape at scale 1 (the layer's geometry, kept in one place). */
    public static float halfWidth(Shape shape) {
        return switch (shape) {
            case NONE -> 0.0F;
            case SATCHEL -> 2.5F;
            case SACK -> 3.5F;
            case BASKET, CRATE -> 3.0F;
            case BUNDLE -> 3.5F;
            case FRAME -> 3.5F;
        };
    }
}
