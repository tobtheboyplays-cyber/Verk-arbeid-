package com.hearthstead.client.motion;

import net.minecraft.client.model.geom.ModelPart;

/**
 * One immutable, pre-baked animation clip in Java model space.
 *
 * <p>Values are stored exactly as vanilla {@code AnimationChannel} targets
 * are applied: rotation in radians added to {@code xRot/yRot/zRot};
 * position already flipped by {@code posVec} (y-down) and added to
 * {@code x/y/z}; scale as the {@code (s - 1)} offset added to the part's
 * scale. That makes a clip converted from a Java {@code AnimationDefinition}
 * evaluate bit-for-bit like {@code KeyframeAnimations.animate}, and a clip
 * loaded from bedrock JSON evaluate in the same additive grammar every
 * {@code SettlerModel} reset/override rule was written against.
 *
 * <p>Segment rule (vanilla / GeckoLib): the keyframe a segment ARRIVES at
 * owns its interpolation. CATMULLROM uses the clamped neighbours, exactly
 * like vanilla's {@code Mth.catmullrom} call; EASED uses an {@link Easing}.
 */
public final class MotionClip {
    public static final int ROTATION = 0;
    public static final int POSITION = 1;
    public static final int SCALE = 2;

    public static final byte LINEAR = 0;
    public static final byte CATMULLROM = 1;
    public static final byte STEP = 2;
    public static final byte EASED = 3;

    /** One bone channel: sorted keyframes with separate pre/post values. */
    public static final class Track {
        final String bone;
        final int target;
        final float[] times;
        final float[] pre;
        final float[] post;
        final byte[] mode;
        final Easing[] easing;
        final float[] easingArg;

        public Track(String bone, int target, float[] times, float[] pre, float[] post,
                     byte[] mode, Easing[] easing, float[] easingArg) {
            this.bone = bone;
            this.target = target;
            this.times = times;
            this.pre = pre;
            this.post = post;
            this.mode = mode;
            this.easing = easing;
            this.easingArg = easingArg;
        }

        public String bone() { return bone; }
        public int target() { return target; }
        public int size() { return times.length; }
        public float time(int i) { return times[i]; }
        public float pre(int i, int axis) { return pre[i * 3 + axis]; }
        public float post(int i, int axis) { return post[i * 3 + axis]; }
        public byte mode(int i) { return mode[i]; }
        public Easing easing(int i) { return easing[i]; }
        public float easingArg(int i) { return easingArg[i]; }

        /** Writes the channel value at {@code seconds} (already wrapped) into out[0..2]. */
        void sample(float seconds, float[] out) {
            float[] t = times;
            int n = t.length;
            // Identical search to vanilla: first index with seconds <= time, minus one.
            int lo = 0;
            int span = n;
            while (span > 0) {
                int half = span / 2;
                int mid = lo + half;
                if (seconds <= t[mid]) {
                    span = half;
                } else {
                    lo = mid + 1;
                    span -= half + 1;
                }
            }
            int i = Math.max(0, lo - 1);
            int j = Math.min(n - 1, i + 1);
            float delta = 0.0F;
            if (j != i) {
                delta = (seconds - t[i]) / (t[j] - t[i]);
                delta = delta < 0.0F ? 0.0F : (delta > 1.0F ? 1.0F : delta);
            }
            int a = i * 3;
            int b = j * 3;
            switch (mode[j]) {
                case CATMULLROM: {
                    int p = Math.max(0, i - 1) * 3;
                    int q = Math.min(n - 1, j + 1) * 3;
                    for (int axis = 0; axis < 3; axis++) {
                        out[axis] = catmullrom(delta, post[p + axis], post[a + axis],
                            pre[b + axis], pre[q + axis]);
                    }
                    break;
                }
                case STEP: {
                    float[] src = j == i || delta < 1.0F ? post : pre;
                    int base = j == i || delta < 1.0F ? a : b;
                    out[0] = src[base];
                    out[1] = src[base + 1];
                    out[2] = src[base + 2];
                    break;
                }
                case EASED: {
                    float e = easing[j] == null ? delta : easing[j].apply(delta, easingArg[j]);
                    for (int axis = 0; axis < 3; axis++) {
                        float from = post[a + axis];
                        out[axis] = from + (pre[b + axis] - from) * e;
                    }
                    break;
                }
                default: {
                    for (int axis = 0; axis < 3; axis++) {
                        float from = post[a + axis];
                        out[axis] = from + (pre[b + axis] - from) * delta;
                    }
                }
            }
        }
    }

    private final String key;
    private final String source;
    private final float length;
    private final boolean looping;
    private final Track[] tracks;
    private final boolean[] bendBones;
    /** Authored ground distance per gait cycle (hearthstead_meta.blocks_per_cycle), or NaN. */
    private float strideBlocks = Float.NaN;
    /** Display-only hand props while this clip plays (see MotionProp), or null. */
    private MotionProp[] props;
    /** Client-local timeline sounds (see ClipSound), or null. */
    private ClipSound[] sounds;
    /** Client-local contact cues: particles plus a secondary-motion impact (see ClipParticle), or null. */
    private ClipParticle[] particles;
    // Two-slot rig cache: the settler and raider rigs are the only callers.
    private MotionRig cacheRigA;
    private int[] cacheIdxA;
    private MotionRig cacheRigB;
    private int[] cacheIdxB;

    public MotionClip(String key, String source, float length, boolean looping, Track[] tracks) {
        this.key = key;
        this.source = source;
        this.length = length;
        this.looping = looping;
        this.tracks = tracks;
        this.bendBones = new boolean[4];
        for (Track track : tracks) {
            int bend = MotionBones.bendIndex(track.bone);
            if (bend >= 0 && track.target == ROTATION) {
                bendBones[bend] = true;
            }
        }
    }

    public String key() { return key; }
    public float strideBlocks() { return strideBlocks; }

    public MotionProp[] props() { return props; }
    public ClipSound[] sounds() { return sounds; }
    public ClipParticle[] particles() { return particles; }

    MotionClip withSounds(ClipSound[] sounds) {
        this.sounds = sounds;
        return this;
    }

    MotionClip withParticles(ClipParticle[] particles) {
        this.particles = particles;
        return this;
    }

    MotionClip withProps(MotionProp[] props) {
        this.props = props;
        return this;
    }

    MotionClip withStrideBlocks(float blocks) {
        this.strideBlocks = blocks;
        return this;
    }
    public String source() { return source; }
    public float length() { return length; }
    public boolean looping() { return looping; }
    public Track[] tracks() { return tracks; }

    /** True when this clip explicitly authors that bend bone (MotionBones.RIGHT_FOREARM...). */
    public boolean authorsBend(int bendIndex) {
        return bendBones[bendIndex];
    }

    public boolean authorsAnyBend() {
        return bendBones[0] || bendBones[1] || bendBones[2] || bendBones[3];
    }

    /** Vanilla's elapsed-seconds rule: looping clips wrap, one-shots run past the end (clamped by the search). */
    public float wrap(float seconds) {
        return looping && length > 0.0F ? seconds % length : seconds;
    }

    private int[] indices(MotionRig rig) {
        if (rig == cacheRigA) return cacheIdxA;
        if (rig == cacheRigB) return cacheIdxB;
        int[] idx = new int[tracks.length];
        for (int i = 0; i < tracks.length; i++) {
            idx[i] = rig.indexOf(tracks[i].bone);
        }
        cacheRigB = cacheRigA;
        cacheIdxB = cacheIdxA;
        cacheRigA = rig;
        cacheIdxA = idx;
        return idx;
    }

    /**
     * Additive application, the exact semantic of vanilla
     * {@code KeyframeAnimations.animate(model, def, millis, weight, scratch)}.
     */
    public void apply(MotionRig rig, float seconds, float weight, BoneMask mask, float[] scratch) {
        float t = wrap(seconds);
        int[] idx = indices(rig);
        for (int i = 0; i < tracks.length; i++) {
            int bone = idx[i];
            if (bone < 0) {
                continue;
            }
            Track track = tracks[i];
            float w = weight;
            if (mask != null && !mask.isAll()) {
                w *= mask.weight(track.bone);
                if (w == 0.0F) {
                    continue;
                }
            }
            track.sample(t, scratch);
            ModelPart part = rig.part(bone);
            float x = scratch[0] * w;
            float y = scratch[1] * w;
            float z = scratch[2] * w;
            switch (track.target) {
                case ROTATION -> {
                    part.xRot += x;
                    part.yRot += y;
                    part.zRot += z;
                }
                case POSITION -> {
                    part.x += x;
                    part.y += y;
                    part.z += z;
                }
                default -> {
                    part.xScale += x;
                    part.yScale += y;
                    part.zScale += z;
                }
            }
        }
    }

    static float catmullrom(float delta, float p0, float p1, float p2, float p3) {
        // Mth.catmullrom, reproduced so the evaluator has no game dependency.
        return 0.5F * (2.0F * p1 + (p2 - p0) * delta
            + (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * delta * delta
            + (3.0F * p1 - p0 - 3.0F * p2 + p3) * delta * delta * delta);
    }
}
