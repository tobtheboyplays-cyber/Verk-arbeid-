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
 * <p>Every profession has an explicit {@link Kind} ({@link #kind} is an
 * exhaustive switch with no default, so a new trade does not compile until
 * it is given one). The courier (canvas sack with shoulder handoffs) and the
 * lumberer (log frame) keep their authored model parts ({@link Kind#AUTHORED});
 * the fighting trades keep their kit on the back ({@link Kind#KIT}).
 *
 * <p>Where the pack goes (carry pack lane, 26 Sep): it is a torso child, so it
 * follows the spine through every bend and twist. It comes OFF in bed and on
 * a seat (tavern chair, fisher's chair, drunk floor sit), under a shouldered
 * carcass, on a hitched cart and when the bag is set down; it stays on for
 * everything else (walk, run, work, combat, swim, ladder, conversation).
 */
public final class CarryPackRules {
    private CarryPackRules() {
    }

    /** How a profession's back is dressed. */
    public enum Kind {
        /** Job container that grows with the bag (satchel when empty unless {@link #rigWhenEmpty}). */
        JOB,
        /** Authored model part (courier sack, lumber frame); this table stays out of the way. */
        AUTHORED,
        /** Martial kit (quiver, shield, weapon): never a carry pack, whatever the bag holds. */
        KIT,
        /** A small document satchel that never grows (the mayor carries no goods). */
        SATCHEL_ONLY
    }

    /** Container silhouette. Every shape shares the carried sack's frame (pivot top-back, +y down, +z back). */
    public enum Shape {
        /** Nothing on the back (tool rig, martial kit, cart, placed on the ground, or taken off). */
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
    /**
     * Height never grows past this: a tier-3 pack at full would otherwise hang
     * below the belt into the swinging thighs. The extra tier volume goes into
     * depth (away from the back) instead.
     */
    public static final float VERTICAL_CAP = 1.10F;
    /** Below this fill the container shrinks toward nothing, so the swap to the satchel never pops. */
    static final float DEFLATE_FILL = 0.12F;
    static final float SOFT_LEAN = 0.08F;
    static final float HARD_LEAN = 0.14F;
    /** Torso half-width in model px; a container must stay inside it or a back-swinging arm clips it. */
    public static final float TORSO_HALF_WIDTH = 5.0F;
    /**
     * Side clearance kept inside the torso silhouette for arms swinging past
     * the back. The 4-wide arm cube starts 1 px inside the torso side (x=4),
     * so the pack must stay under that with a margin for the arm's slight
     * inward roll in the walk.
     */
    public static final float ARM_CLEARANCE = 0.8F;

    private static final Style PLAIN = new Style(Shape.SACK, Material.WOOL, 0xFFB8A488,
        "minecraft:bread", "minecraft:apple", "minecraft:stick");
    private static final Style NO_STYLE = new Style(Shape.NONE, Material.LEATHER, 0xFFFFFFFF);
    private static final Style DOCUMENTS = new Style(Shape.SATCHEL, Material.LEATHER, 0xFF6A4A32);
    private static final Look NONE = new Look(Shape.NONE, NO_STYLE, 1.0F, 0.0F, 0);

    /** Built once: styleFor is read every frame by the model and the layer (no per-frame allocation). */
    private static final Style[] STYLES = new Style[Profession.values().length];
    private static final Look[] SATCHELS = new Look[Profession.values().length];
    static {
        for (Profession p : Profession.values()) {
            STYLES[p.ordinal()] = buildStyle(p);
            SATCHELS[p.ordinal()] = new Look(Shape.SATCHEL, STYLES[p.ordinal()], 1.0F, 0.0F, 0);
        }
    }

    /** Nothing on the back this frame. */
    public static Look none() {
        return NONE;
    }

    /** The explicit rule of every profession. Exhaustive: a new trade must be given one to compile. */
    public static Kind kind(Profession p) {
        return switch (p) {
            case COURIER, LUMBERER -> Kind.AUTHORED;
            case GUARD, SPEARMAN, LONGSWORDSMAN, ARCHER -> Kind.KIT;
            case MAYOR -> Kind.SATCHEL_ONLY;
            case NONE, FARMER, BAKER, COOK, BUTCHER, SMELTER, SMITH, SAWYER, CARPENTER, MASON,
                 FLETCHER, WEAVER, TANNER, MINER, INNKEEPER, SCHOLAR, MILLER, BREWER, ARMOURER,
                 HERDER, FISHER, HUNTER, TRADER, HEALER, RUNE_MAGE, BUILDER -> Kind.JOB;
        };
    }

    /** False for jobs whose back container is an authored model part (courier sack, lumber frame). */
    public static boolean governs(Profession p) {
        return kind(p) != Kind.AUTHORED;
    }

    /** Jobs whose back is empty (a tool rig or martial kit shows instead) when the bag is. */
    public static boolean rigWhenEmpty(Profession p) {
        return switch (kind(p)) {
            case KIT, AUTHORED -> true;
            case SATCHEL_ONLY -> false;
            // The fisher's net and tackle ride on the belt; the creel only goes on with a catch.
            case JOB -> p == Profession.FISHER;
        };
    }

    public static Style styleFor(Profession p) {
        return STYLES[p.ordinal()];
    }

    private static Style buildStyle(Profession p) {
        return switch (p) {
            case NONE -> PLAIN;
            case COURIER, LUMBERER, GUARD, SPEARMAN, LONGSWORDSMAN, ARCHER -> NO_STYLE;
            case MAYOR -> DOCUMENTS;
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
            // Trader lane: ledger-ish goods only; the coin purse lives at the right hip.
            case TRADER -> new Style(Shape.SACK, Material.LEATHER, 0xFFB08850,
                "minecraft:paper", "minecraft:book", "minecraft:emerald");
            case HEALER -> new Style(Shape.SACK, Material.WOOL, 0xFFA8B890,
                "minecraft:fern", "minecraft:sweet_berries", "minecraft:paper");
            case FLETCHER -> new Style(Shape.SACK, Material.LEATHER, 0xFF8A7050,
                "minecraft:stick", "minecraft:feather", "minecraft:flint");
            case RUNE_MAGE -> new Style(Shape.SACK, Material.WOOL, 0xFF5A6AA8,
                "minecraft:amethyst_shard", "minecraft:paper", "minecraft:amethyst_shard");
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
     * The per-frame decision (pack worn on the back).
     *
     * @param fill      client-smoothed bag fill 0..1
     * @param tierScale HaulGear.visualScale(sackTier)
     * @param pulling   hitched to a hand cart (the cart carries; the back is free)
     * @param detached  the work container is placed on the ground (drawn there instead)
     * @param swinging  a work swing clip is playing (overhead/backswing tools)
     */
    public static Look look(Profession p, float fill, float tierScale, boolean pulling,
                            boolean detached, boolean swinging) {
        return look(p, fill, tierScale, pulling, detached, swinging, false);
    }

    /**
     * As above, plus {@code offBack}: the settler has taken the pack off (in bed,
     * seated, a carcass across the shoulders, a guest's own travel pack).
     */
    public static Look look(Profession p, float fill, float tierScale, boolean pulling,
                            boolean detached, boolean swinging, boolean offBack) {
        Kind kind = kind(p);
        if (kind == Kind.AUTHORED || kind == Kind.KIT || pulling || detached || offBack) {
            return NONE;
        }
        if (kind == Kind.SATCHEL_ONLY || fill <= 0.001F) {
            return rigWhenEmpty(p) ? NONE : SATCHELS[p.ordinal()];
        }
        Style style = STYLES[p.ordinal()];
        boolean rigid = style.shape() != Shape.SACK;
        if (swinging && rigid) {
            // Rule 6: stowed while an overhead/backswing tool swings (like the empty lumber frame).
            return NONE;
        }
        float lean = (rigid ? HARD_LEAN : SOFT_LEAN) * Math.min(1.0F, fill);
        return new Look(style.shape(), style, scale(fill, tierScale), lean, contentTier(fill));
    }

    /** Height scale: the fill/tier scale, capped at {@link #VERTICAL_CAP} so the pack stays above the belt. */
    public static float verticalScale(float scale) {
        return Math.min(scale, VERTICAL_CAP);
    }

    /**
     * X (sideways) scale: the fill/tier scale, capped so the container never
     * reaches past the torso's sides where a back-swinging arm passes. Depth
     * still grows with the full scale.
     */
    public static float lateralScale(Shape shape, float scale) {
        float half = halfWidth(shape);
        if (half <= 0.0F) {
            return scale;
        }
        return Math.min(scale, (TORSO_HALF_WIDTH - ARM_CLEARANCE) / half);
    }

    /** Half-width in model px of each shape at scale 1, contents included (the layer's geometry, kept in one place). */
    public static float halfWidth(Shape shape) {
        return switch (shape) {
            case NONE -> 0.0F;
            case SATCHEL -> 2.6F;
            case SACK -> 3.5F;
            case BASKET, CRATE -> 3.0F;
            case BUNDLE -> 3.5F;
            case FRAME -> 3.5F;
        };
    }

}
