package com.hearthstead.entity.ai;

/** Bounded recovery economics; no timer grants health without a consumed meal. */
public final class GuardRecoveryPolicy {
    public static final float ENTER_FRACTION = 0.30F;
    public static final float RETURN_FRACTION = 0.65F;
    public static final float MAX_HEAL_PER_MEAL = 4.0F;

    private GuardRecoveryPolicy() {}

    public static boolean shouldRecover(float health, float maximum, boolean active) {
        return Float.isFinite(health) && Float.isFinite(maximum) && maximum > 0
            && health > 0 && (active ? health < maximum * RETURN_FRACTION
                : health <= maximum * ENTER_FRACTION);
    }

    public static float mealHeal(float health, float maximum, int nutrition) {
        return mealHeal(health, maximum, nutrition, false);
    }

    public static float mealHeal(float health, float maximum, int nutrition, boolean peacetime) {
        if (!Float.isFinite(health) || !Float.isFinite(maximum)
            || maximum <= 0 || health <= 0 || nutrition <= 0) return 0;
        return Math.max(0, Math.min(maximum * (peacetime ? 1.0F : RETURN_FRACTION) - health,
            Math.min(MAX_HEAL_PER_MEAL, nutrition)));
    }
}
