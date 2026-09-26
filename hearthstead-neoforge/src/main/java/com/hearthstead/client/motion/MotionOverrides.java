package com.hearthstead.client.motion;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;

/**
 * Client-side "this entity is being driven by a scripted clip" table: a
 * finisher victim, a cinematic, a staged reaction. While an entry exists the
 * entity's model (RaiderModel, GoblinThiefModel) skips its own locomotion and
 * one-shots and plays the named library clip ABSOLUTE over a clean reset,
 * timed from {@code startAgeTicks} (the entity's own tickCount clock, so the
 * clip stays in sync with the server's timeline packet).
 *
 * <p>Display only: nothing here moves the entity, blocks AI or changes
 * state. The caller (the payload handler) starts and stops entries; a stale
 * entry is harmless beyond its {@code holdTicks} after the clip ends, when
 * it is dropped automatically.
 */
public final class MotionOverrides {
    /** A scripted clip on one entity; {@code key} is a library key, e.g. "raider/finisher_victim_spin". */
    public record Active(String key, float startAgeTicks, int holdTicks) {
    }

    private static final Int2ObjectOpenHashMap<Active> ACTIVE = new Int2ObjectOpenHashMap<>();

    private MotionOverrides() {
    }

    /**
     * Starts (or restarts) a scripted clip on an entity.
     *
     * @param holdTicks how long the last frame is held after a one-shot ends
     *                  before the entry expires on its own (e.g. a body lying
     *                  still until the death animation takes over)
     */
    public static void start(int entityId, String key, float startAgeTicks, int holdTicks) {
        ACTIVE.put(entityId, new Active(key, startAgeTicks, Math.max(0, holdTicks)));
    }

    public static void stop(int entityId) {
        ACTIVE.remove(entityId);
    }

    public static Active get(int entityId) {
        return ACTIVE.isEmpty() ? null : ACTIVE.get(entityId);
    }

    /** Drops both the scripted clip and the overlay of one entity (it left the level). */
    public static void forget(int entityId) {
        ACTIVE.remove(entityId);
        OVERLAYS.remove(entityId);
    }

    public static void clear() {
        ACTIVE.clear();
        OVERLAYS.clear();
    }

    // ---- Additive upper-body overlays (conversation gestures, listening) ----

    /** An additive upper-body loop on one entity; fades in/out over 0.3 s. */
    public static final class Overlay {
        final String key;
        final float startAgeTicks;
        float stopAgeTicks = Float.NaN;

        Overlay(String key, float startAgeTicks) {
            this.key = key;
            this.startAgeTicks = startAgeTicks;
        }

        public String key() {
            return key;
        }
    }

    private static final Int2ObjectOpenHashMap<Overlay> OVERLAYS = new Int2ObjectOpenHashMap<>();

    /**
     * Plays a looping library clip ADDITIVELY on the entity's upper body
     * (torso, head, arms; legs keep walking) until {@link #stopOverlay}.
     * Keys: "settler/village_chat" (talking, open-hand gestures + nods) and
     * "settler/village_listen" (listening: weight shift, head tilt) work on
     * both the settler and the raider rig. Starting a different key while one
     * plays swaps immediately; the same key keeps its phase.
     */
    public static void overlay(int entityId, String key, float startAgeTicks) {
        Overlay current = OVERLAYS.get(entityId);
        if (current != null && current.key.equals(key) && Float.isNaN(current.stopAgeTicks)) {
            return;
        }
        OVERLAYS.put(entityId, new Overlay(key, startAgeTicks));
    }

    /** Fades the overlay out from {@code nowAgeTicks} (0.3 s). */
    public static void stopOverlay(int entityId, float nowAgeTicks) {
        Overlay current = OVERLAYS.get(entityId);
        if (current != null && Float.isNaN(current.stopAgeTicks)) {
            current.stopAgeTicks = nowAgeTicks;
        }
    }

    static Overlay overlayOf(int entityId) {
        return OVERLAYS.isEmpty() ? null : OVERLAYS.get(entityId);
    }

    /** Envelope weight for this frame, or -1 once faded out (the entry is then dropped). */
    static float overlayWeight(int entityId, Overlay overlay, float ageInTicks) {
        float in = Math.min(1.0F, Math.max(0.0F, (ageInTicks - overlay.startAgeTicks) / 6.0F));
        float w = in * in * (3.0F - 2.0F * in);
        if (!Float.isNaN(overlay.stopAgeTicks)) {
            float out = Math.min(1.0F, Math.max(0.0F, (ageInTicks - overlay.stopAgeTicks) / 6.0F));
            if (out >= 1.0F) {
                OVERLAYS.remove(entityId);
                return -1.0F;
            }
            w *= 1.0F - out * out * (3.0F - 2.0F * out);
        }
        return w;
    }

    /**
     * Seconds into the scripted clip for this frame, or NaN when the entry
     * has run out (the entry is then removed). Looping clips never expire.
     */
    static float secondsFor(int entityId, Active active, MotionClip clip, float ageInTicks) {
        float seconds = Math.max(0.0F, (ageInTicks - active.startAgeTicks()) / 20.0F);
        if (!clip.looping() && seconds > clip.length() + active.holdTicks() / 20.0F) {
            ACTIVE.remove(entityId);
            return Float.NaN;
        }
        return clip.looping() ? seconds : Math.min(seconds, clip.length());
    }
}
