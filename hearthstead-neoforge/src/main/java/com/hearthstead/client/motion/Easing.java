package com.hearthstead.client.motion;

import java.util.Locale;

/**
 * Keyframe easing curves, named exactly as GeckoLib / the Blockbench
 * "GeckoLib Animation Utils" plugin writes them into bedrock animation JSON
 * ({@code "easing": "easeInOutSine"}, optional {@code "easingArgs": [n]}).
 *
 * <p>An easing on a keyframe shapes the segment that ARRIVES at that
 * keyframe (GeckoLib semantics, and the same "end keyframe owns the
 * segment" rule vanilla {@code KeyframeAnimations} uses for LINEAR vs
 * CATMULLROM). Every function maps 0..1 to 0..1 at the end points; the
 * {@code back} family may overshoot in between, which is why the authored
 * settler clips only ever use it with small arguments for follow-through.
 */
public enum Easing {
    LINEAR("linear"),
    STEP("step"),
    EASE_IN_SINE("easeInSine"),
    EASE_OUT_SINE("easeOutSine"),
    EASE_IN_OUT_SINE("easeInOutSine"),
    EASE_IN_QUAD("easeInQuad"),
    EASE_OUT_QUAD("easeOutQuad"),
    EASE_IN_OUT_QUAD("easeInOutQuad"),
    EASE_IN_CUBIC("easeInCubic"),
    EASE_OUT_CUBIC("easeOutCubic"),
    EASE_IN_OUT_CUBIC("easeInOutCubic"),
    EASE_IN_QUART("easeInQuart"),
    EASE_OUT_QUART("easeOutQuart"),
    EASE_IN_OUT_QUART("easeInOutQuart"),
    EASE_IN_QUINT("easeInQuint"),
    EASE_OUT_QUINT("easeOutQuint"),
    EASE_IN_OUT_QUINT("easeInOutQuint"),
    EASE_IN_EXPO("easeInExpo"),
    EASE_OUT_EXPO("easeOutExpo"),
    EASE_IN_OUT_EXPO("easeInOutExpo"),
    EASE_IN_CIRC("easeInCirc"),
    EASE_OUT_CIRC("easeOutCirc"),
    EASE_IN_OUT_CIRC("easeInOutCirc"),
    EASE_IN_BACK("easeInBack"),
    EASE_OUT_BACK("easeOutBack"),
    EASE_IN_OUT_BACK("easeInOutBack"),
    EASE_IN_ELASTIC("easeInElastic"),
    EASE_OUT_ELASTIC("easeOutElastic"),
    EASE_IN_OUT_ELASTIC("easeInOutElastic"),
    EASE_IN_BOUNCE("easeInBounce"),
    EASE_OUT_BOUNCE("easeOutBounce"),
    EASE_IN_OUT_BOUNCE("easeInOutBounce");

    private final String jsonName;

    Easing(String jsonName) {
        this.jsonName = jsonName;
    }

    public String jsonName() {
        return jsonName;
    }

    /** Case-insensitive lookup by GeckoLib name; unknown names return null. */
    public static Easing byName(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.trim().toLowerCase(Locale.ROOT);
        for (Easing easing : values()) {
            if (easing.jsonName.toLowerCase(Locale.ROOT).equals(wanted)) {
                return easing;
            }
        }
        return null;
    }

    /**
     * Evaluates the curve. {@code arg} is GeckoLib's first easingArg: the
     * overshoot for the back family (default 1.70158), bounciness for
     * elastic (default 1), ignored otherwise. NaN selects the default.
     */
    public float apply(float t, float arg) {
        if (t <= 0.0F) return 0.0F;
        if (t >= 1.0F) return 1.0F;
        switch (this) {
            case LINEAR: return t;
            case STEP: return 0.0F;
            case EASE_IN_SINE: return 1.0F - (float) Math.cos(t * Math.PI / 2.0);
            case EASE_OUT_SINE: return (float) Math.sin(t * Math.PI / 2.0);
            case EASE_IN_OUT_SINE: return (float) (-(Math.cos(Math.PI * t) - 1.0) / 2.0);
            case EASE_IN_QUAD: return t * t;
            case EASE_OUT_QUAD: return 1.0F - (1.0F - t) * (1.0F - t);
            case EASE_IN_OUT_QUAD: return t < 0.5F ? 2.0F * t * t : 1.0F - pow(-2.0F * t + 2.0F, 2) / 2.0F;
            case EASE_IN_CUBIC: return t * t * t;
            case EASE_OUT_CUBIC: return 1.0F - pow(1.0F - t, 3);
            case EASE_IN_OUT_CUBIC: return t < 0.5F ? 4.0F * t * t * t : 1.0F - pow(-2.0F * t + 2.0F, 3) / 2.0F;
            case EASE_IN_QUART: return pow(t, 4);
            case EASE_OUT_QUART: return 1.0F - pow(1.0F - t, 4);
            case EASE_IN_OUT_QUART: return t < 0.5F ? 8.0F * pow(t, 4) : 1.0F - pow(-2.0F * t + 2.0F, 4) / 2.0F;
            case EASE_IN_QUINT: return pow(t, 5);
            case EASE_OUT_QUINT: return 1.0F - pow(1.0F - t, 5);
            case EASE_IN_OUT_QUINT: return t < 0.5F ? 16.0F * pow(t, 5) : 1.0F - pow(-2.0F * t + 2.0F, 5) / 2.0F;
            case EASE_IN_EXPO: return (float) Math.pow(2.0, 10.0 * t - 10.0);
            case EASE_OUT_EXPO: return 1.0F - (float) Math.pow(2.0, -10.0 * t);
            case EASE_IN_OUT_EXPO: return t < 0.5F ? (float) Math.pow(2.0, 20.0 * t - 10.0) / 2.0F
                : (2.0F - (float) Math.pow(2.0, -20.0 * t + 10.0)) / 2.0F;
            case EASE_IN_CIRC: return 1.0F - (float) Math.sqrt(1.0 - t * t);
            case EASE_OUT_CIRC: return (float) Math.sqrt(1.0 - (t - 1.0) * (t - 1.0));
            case EASE_IN_OUT_CIRC: return t < 0.5F
                ? (float) (1.0 - Math.sqrt(1.0 - Math.pow(2.0 * t, 2))) / 2.0F
                : (float) (Math.sqrt(1.0 - Math.pow(-2.0 * t + 2.0, 2)) + 1.0) / 2.0F;
            case EASE_IN_BACK: {
                float c1 = Float.isNaN(arg) ? 1.70158F : arg;
                return (c1 + 1.0F) * t * t * t - c1 * t * t;
            }
            case EASE_OUT_BACK: {
                float c1 = Float.isNaN(arg) ? 1.70158F : arg;
                float u = t - 1.0F;
                return 1.0F + (c1 + 1.0F) * u * u * u + c1 * u * u;
            }
            case EASE_IN_OUT_BACK: {
                float c2 = (Float.isNaN(arg) ? 1.70158F : arg) * 1.525F;
                return t < 0.5F
                    ? (pow(2.0F * t, 2) * ((c2 + 1.0F) * 2.0F * t - c2)) / 2.0F
                    : (pow(2.0F * t - 2.0F, 2) * ((c2 + 1.0F) * (t * 2.0F - 2.0F) + c2) + 2.0F) / 2.0F;
            }
            case EASE_IN_ELASTIC: return 1.0F - EASE_OUT_ELASTIC.apply(1.0F - t, arg);
            case EASE_OUT_ELASTIC: {
                float bounciness = Float.isNaN(arg) ? 1.0F : Math.max(0.01F, arg);
                double c4 = (2.0 * Math.PI) / (3.0 / bounciness);
                return (float) (Math.pow(2.0, -10.0 * t) * Math.sin((t * 10.0 - 0.75) * c4) + 1.0);
            }
            case EASE_IN_OUT_ELASTIC: return t < 0.5F
                ? EASE_IN_ELASTIC.apply(t * 2.0F, arg) / 2.0F
                : 0.5F + EASE_OUT_ELASTIC.apply(t * 2.0F - 1.0F, arg) / 2.0F;
            case EASE_IN_BOUNCE: return 1.0F - bounceOut(1.0F - t);
            case EASE_OUT_BOUNCE: return bounceOut(t);
            case EASE_IN_OUT_BOUNCE: return t < 0.5F
                ? (1.0F - bounceOut(1.0F - 2.0F * t)) / 2.0F
                : (1.0F + bounceOut(2.0F * t - 1.0F)) / 2.0F;
            default: return t;
        }
    }

    private static float bounceOut(float t) {
        final float n1 = 7.5625F;
        final float d1 = 2.75F;
        if (t < 1.0F / d1) return n1 * t * t;
        if (t < 2.0F / d1) { t -= 1.5F / d1; return n1 * t * t + 0.75F; }
        if (t < 2.5F / d1) { t -= 2.25F / d1; return n1 * t * t + 0.9375F; }
        t -= 2.625F / d1;
        return n1 * t * t + 0.984375F;
    }

    private static float pow(float value, int exponent) {
        float result = 1.0F;
        for (int i = 0; i < exponent; i++) result *= value;
        return result;
    }
}
