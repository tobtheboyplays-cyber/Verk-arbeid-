package com.hearthstead.client.render;

import com.hearthstead.entity.SettlerEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Fisher v3 client clock (presentation only). The server syncs the rod cycle
 * as a phase 0..299 (FisherWorkGoal.phaseOf): cast 0-30, wait 30-240, bite at
 * 240, strike + reel 240-282, land 282-300. This turns that phase into "which
 * clip, how many seconds in" on the model clock, so the SettlerModel pose and
 * FisherLineRenderer's line, bobber and fish read the same moment. Entering a
 * window back-dates its start by how far into it the server already is (late
 * join, chunk reload), so nothing restarts from the top mid-cast.
 */
public final class FisherCastClock {
    public enum Window { CAST, WAIT, REEL, LAND }

    public static final int CAST_END = 30;
    public static final int BITE = 240;
    public static final int LAND = 282;
    public static final float CAST_SECONDS = 1.70F;
    public static final float REEL_SECONDS = 2.20F;
    public static final float LAND_SECONDS = 2.50F;
    /** Clip beats (fisher_v3_spec.py). */
    public static final float RELEASE = 0.80F;
    public static final float SPLASHDOWN = 1.25F;
    /** Land beats: out of the water, dangling + flapping on the line, swung in, grabbed. */
    public static final float LAND_UP = 0.45F;
    public static final float LAND_SWING = 1.75F;
    public static final float GRAB = 2.05F;

    /** Per-settler client state; weak so unloaded settlers vanish with their entity. */
    public static final class State {
        Window window;
        int lastPhase = -1;
        float startAge;
        float lastSeconds = -1.0F;
        Window lastCueWindow;
        /** Bobber's water cell for this cycle (null = no water found in front). */
        BlockPos water;
        long waterCycle = -1;
        long cycle;
        Vec3 releaseTip;
        Vec3 lastFish;
    }

    public record Sample(Window window, float seconds, State state) {
    }

    private static final Map<SettlerEntity, State> STATES = new WeakHashMap<>();

    private FisherCastClock() {
    }

    public static Window windowOf(int phase) {
        if (phase < CAST_END) {
            return Window.CAST;
        }
        if (phase < BITE) {
            return Window.WAIT;
        }
        return phase < LAND ? Window.REEL : Window.LAND;
    }

    /** Real ticks already spent in the window at this phase (FisherWorkGoal CAST/REEL/LAND_TICKS). */
    static float ticksInto(Window window, int phase) {
        return switch (window) {
            case CAST -> phase * (34.0F / 30.0F);
            case REEL -> (phase - BITE) * (44.0F / 42.0F);
            case LAND -> (phase - LAND) * (50.0F / 18.0F);
            case WAIT -> 0.0F;
        };
    }

    /** The current window and seconds into its clip; null when the settler is not rod fishing. */
    public static Sample sample(SettlerEntity entity, float ageInTicks) {
        int phase = entity.getFisherCycleTick();
        if (phase < 0) {
            STATES.remove(entity);
            return null;
        }
        State s = STATES.computeIfAbsent(entity, e -> new State());
        Window window = windowOf(phase);
        boolean newCycle = s.lastPhase >= 0 && phase + 20 < s.lastPhase;
        if (window != s.window || newCycle) {
            if (window == Window.CAST && (s.window != Window.CAST || newCycle)) {
                s.cycle++;
                s.releaseTip = null;
            }
            s.window = window;
            s.startAge = ageInTicks - ticksInto(window, phase);
        }
        s.lastPhase = phase;
        float seconds = Math.max(0.0F, (ageInTicks - s.startAge) / 20.0F);
        seconds = switch (window) {
            case CAST -> Math.min(seconds, CAST_SECONDS);
            case REEL -> Math.min(seconds, REEL_SECONDS);
            case LAND -> Math.min(seconds, LAND_SECONDS);
            case WAIT -> seconds;
        };
        return new Sample(window, seconds, s);
    }
}
