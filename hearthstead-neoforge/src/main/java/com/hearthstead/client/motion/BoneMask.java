package com.hearthstead.client.motion;

/**
 * Per-bone layer weight. A mask is how one clip is laid over another without
 * the second clip's bones fighting the first: locomotion owns the legs and
 * hips, an upper-body action owns the arms and torso, and an overlay (idle
 * breath under a meal) can be heard on the chest while staying out of the
 * head and hands. Immutable; built once, shared by every settler.
 */
public final class BoneMask {
    public static final BoneMask ALL = new BoneMask(1.0F, new String[0], new float[0]);

    private final float defaultWeight;
    private final String[] bones;
    private final float[] weights;

    private BoneMask(float defaultWeight, String[] bones, float[] weights) {
        this.defaultWeight = defaultWeight;
        this.bones = bones;
        this.weights = weights;
    }

    /** {@code of(0.0F, "torso", 1.0F, "head", 0.5F)} -- pairs of bone, weight. */
    public static BoneMask of(float defaultWeight, Object... boneWeightPairs) {
        int n = boneWeightPairs.length / 2;
        String[] bones = new String[n];
        float[] weights = new float[n];
        for (int i = 0; i < n; i++) {
            bones[i] = (String) boneWeightPairs[i * 2];
            weights[i] = ((Number) boneWeightPairs[i * 2 + 1]).floatValue();
        }
        return new BoneMask(defaultWeight, bones, weights);
    }

    public float weight(String bone) {
        for (int i = 0; i < bones.length; i++) {
            if (bones[i].equals(bone)) {
                return weights[i];
            }
        }
        return defaultWeight;
    }

    public boolean isAll() {
        return this == ALL;
    }

    /** Legs, hips and cloak: the distance-sampled gait layer. */
    public static final BoneMask LOWER_BODY = of(0.0F,
        "root", 1.0F, "right_leg", 1.0F, "left_leg", 1.0F,
        "right_shin", 1.0F, "left_shin", 1.0F, "cloak", 1.0F);

    /** Arms, forearms, torso and head: the action layer over a gait. */
    public static final BoneMask UPPER_BODY = of(0.0F,
        "torso", 1.0F, "head", 1.0F, "right_arm", 1.0F, "left_arm", 1.0F,
        "right_forearm", 1.0F, "left_forearm", 1.0F);
}
