package com.hearthstead.client.motion;

import com.google.gson.JsonObject;

/**
 * Procedural-pose constants that are tuned by eye. Defaults live here; the
 * optional resource {@code assets/hearthstead/motion/tuning.json} overrides
 * them on every resource reload (F3+T / {@code /hsmotion reload}), so the
 * values can be iterated on without a rebuild.
 */
public final class MotionTuning {
    /** Sideways elbow fold of the string arm at full draw (radians). */
    public static float BOW_DRAW_FOLD = 1.75F;
    /** Turn-bank gain (radians per degree/second of body yaw rate at full speed). */
    public static float BANK_GAIN = 0.0011F;
    /** Overall scale of the procedural secondary layer (1 = authored). */
    public static float SECONDARY_SCALE = 1.0F;

    private MotionTuning() {
    }

    static void load(JsonObject json) {
        BOW_DRAW_FOLD = read(json, "bow_draw_fold", 1.75F);
        BANK_GAIN = read(json, "bank_gain", 0.0011F);
        SECONDARY_SCALE = read(json, "secondary_scale", 1.0F);
    }

    private static float read(JsonObject json, String key, float fallback) {
        try {
            return json != null && json.has(key) ? json.get(key).getAsFloat() : fallback;
        } catch (RuntimeException bad) {
            return fallback;
        }
    }
}
