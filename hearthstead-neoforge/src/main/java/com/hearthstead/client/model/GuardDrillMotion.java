package com.hearthstead.client.model;

import com.hearthstead.entity.GuardDrillScript;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.guard.drill.GuardDrillYard;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.nbt.CompoundTag;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Client side of the Guard Drill: turns the synced cue into "which clip, at what time, with
 * what weight over the stance" for SettlerModel. The session plan is rebuilt only when the cue
 * changes (one per guard, cached weakly); per frame it is a short scan of a few dozen actions.
 */
public final class GuardDrillMotion {
    private GuardDrillMotion() {
    }

    /**
     * One frame of the drill: the newest clip at {@code millis} with {@code weight}, the clip it
     * is replacing at {@code prevMillis} with {@code prevWeight}, the stance takes the rest.
     */
    public record Pick(AnimationDefinition clip, long millis, float weight,
                       AnimationDefinition prevClip, long prevMillis, float prevWeight, long stanceMillis) {
        public float stanceWeight() {
            return Math.max(0.0F, 1.0F - weight - prevWeight);
        }
    }

    private record Cached(CompoundTag cue, GuardDrillScript.Plan plan) {
    }

    private static final Map<SettlerEntity, Cached> PLANS = new WeakHashMap<>();

    public static AnimationDefinition stance() {
        return GuardDrillAnimations.GUARD_DRILL_STANCE;
    }

    static AnimationDefinition clip(int id) {
        return switch (id) {
            case GuardDrillScript.CUT_HIGH -> GuardDrillAnimations.GUARD_DRILL_CUT_HIGH;
            case GuardDrillScript.CUT_LOW -> GuardDrillAnimations.GUARD_DRILL_CUT_LOW;
            case GuardDrillScript.PARRY_HIGH -> GuardDrillAnimations.GUARD_DRILL_PARRY_HIGH;
            case GuardDrillScript.PARRY_LOW -> GuardDrillAnimations.GUARD_DRILL_PARRY_LOW;
            case GuardDrillScript.EVADE -> GuardDrillAnimations.GUARD_DRILL_EVADE;
            case GuardDrillScript.BREATHER -> GuardDrillAnimations.GUARD_DRILL_BREATHER;
            case GuardDrillScript.STEP_LEFT -> GuardDrillAnimations.GUARD_DRILL_STEP_LEFT;
            case GuardDrillScript.STEP_RIGHT -> GuardDrillAnimations.GUARD_DRILL_STEP_RIGHT;
            case GuardDrillScript.ADVANCE -> GuardDrillAnimations.GUARD_DRILL_ADVANCE;
            case GuardDrillScript.RETREAT -> GuardDrillAnimations.GUARD_DRILL_RETREAT;
            case GuardDrillScript.FEINT -> GuardDrillAnimations.GUARD_DRILL_FEINT;
            case GuardDrillScript.STUMBLE -> GuardDrillAnimations.GUARD_DRILL_STUMBLE;
            case GuardDrillScript.NOD -> GuardDrillAnimations.GUARD_DRILL_NOD;
            default -> GuardDrillAnimations.GUARD_DRILL_STANCE;
        };
    }

    /** This frame's drill pose, or null when the guard is not drilling. */
    @Nullable
    public static Pick pick(SettlerEntity entity, float ageInTicks) {
        CompoundTag cue = entity.guardDrillCue();
        if (cue.isEmpty() || !entity.isAlive()) return null;
        int mode = cue.getInt("Mode");
        if (mode == GuardDrillYard.MODE_NONE) return null;
        int seed = cue.getInt("Seed");
        int slot = cue.getInt("Slot");
        float elapsed = entity.level().getGameTime() - cue.getLong("Start") + ageInTicks - entity.tickCount;
        if (mode == GuardDrillYard.MODE_READY) {
            float s = GuardDrillScript.stanceLocal(seed, slot == 1 ? 1 : 0, elapsed);
            long ms = (long) (s * 1000.0F);
            return new Pick(stance(), ms, 0.0F, stance(), ms, 0.0F, ms);
        }
        Cached cached = PLANS.get(entity);
        if (cached == null || !cached.cue().equals(cue)) {
            cached = new Cached(cue.copy(), GuardDrillScript.plan(seed, mode == GuardDrillYard.MODE_PAIR,
                cue.getInt("Len")));
            PLANS.put(entity, cached);
        }
        GuardDrillScript.Sample s = GuardDrillScript.sample(cached.plan(), slot, elapsed);
        return new Pick(clip(s.clip()), (long) (s.local() * 1000.0F), s.weight(),
            clip(s.prevClip()), (long) (s.prevLocal() * 1000.0F), s.prevWeight(),
            (long) (s.stanceLocal() * 1000.0F));
    }
}
