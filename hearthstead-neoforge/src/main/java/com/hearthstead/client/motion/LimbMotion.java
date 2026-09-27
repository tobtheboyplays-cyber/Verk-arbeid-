package com.hearthstead.client.motion;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The layered runtime for one humanoid rig with bending limbs.
 *
 * <p>Layer order inside a frame (the model's own setupAnim decides WHICH
 * clips run; this class decides HOW they are evaluated and composed):
 * <ol>
 *   <li><b>Locomotion</b> ({@link #walk}): legs, hips and cloak sampled on a
 *       per-entity distance clock whose cadence is matched to the clip's
 *       real stride, so the planted foot stays planted at any speed.</li>
 *   <li><b>Actions</b> ({@link #play}): upper-body or full-body clips over
 *       that gait, with an optional {@link BoneMask} weight per bone.</li>
 *   <li><b>Additive overlays</b> ({@link #secondary}): breathing, turn
 *       banking, hit reaction, and lagging bag/brim/tool springs.</li>
 * </ol>
 * The model's existing cross-fade then eases activity edges over the
 * composed result (bend bones included).
 */
public final class LimbMotion {
    public static final int RIGHT_ARM = 0;
    public static final int LEFT_ARM = 1;
    public static final int RIGHT_LEG = 2;
    public static final int LEFT_LEG = 3;
    /** Arm-local Y of the palm (settler/raider arms end at y 10). */
    private static final float HAND_Y = 10.0F;

    private final HierarchicalModel<?> model;
    private final MotionRig rig;
    private final ModelPart root;
    private final ModelPart torso;
    private final ModelPart head;
    private final ModelPart[] limbs;
    private final ModelPart[] bends;
    private final BendableLimb[] meshes;
    private final ModelPart[][] carried;
    private final boolean[] carriedVisible = new boolean[16];
    private final float[] scratch = new float[3];
    private final Map<LivingEntity, MotionState> states = new WeakHashMap<>();
    private final Map<MotionClip, Float> strideCache = new IdentityHashMap<>();

    private MotionState state;
    private LivingEntity entity;
    /** Optional held-item wrist bones (right, left): children of the bend parts at the palm. */
    private ModelPart[] items;
    private final org.joml.Quaternionf itemRotation = new org.joml.Quaternionf();
    private boolean engine;
    private boolean secondaryOn;
    private float entityScale = 1.0F;

    /**
     * @param limbs   right arm, left arm, right leg, left leg
     * @param bends   the matching empty bend children (forearm/shin)
     * @param meshes  the matching bendable meshes
     * @param carried per limb, the children that must ride the lower segment
     */
    public LimbMotion(HierarchicalModel<?> model, ModelPart root, ModelPart torso, ModelPart head,
                      ModelPart[] limbs, ModelPart[] bends, BendableLimb[] meshes, ModelPart[][] carried) {
        this.model = model;
        this.rig = new MotionRig(model);
        this.root = root;
        this.torso = torso;
        this.head = head;
        this.limbs = limbs;
        this.bends = bends;
        this.meshes = meshes;
        this.carried = carried;
    }

    /** Registers the held-item bones (see applyHand); null entries are allowed. */
    public void setItemParts(ModelPart right, ModelPart left) {
        this.items = new ModelPart[] {right, left};
    }

    public MotionRig rig() {
        return rig;
    }

    public boolean engine() {
        return engine;
    }

    public boolean secondaryOn() {
        return secondaryOn;
    }

    public ModelPart bend(int limb) {
        return bends[limb];
    }

    // ------------------------------------------------------------ frame ---

    /** First call of every setupAnim: engine flags, frame clock and real ground distance. */
    public void begin(LivingEntity e, float ageInTicks) {
        engine = MotionSettings.engineEnabled();
        secondaryOn = MotionSettings.secondaryEnabled();
        entity = e;
        entityScale = e.getScale();
        MotionState s = states.get(e);
        if (s == null) {
            s = new MotionState();
            // Crowd desync: every settler starts its gait at its own phase.
            s.gaitPhase = (e.getId() * 0.618034F) % 1.0F;
            states.put(e, s);
        }
        state = s;
        float partial = Mth.clamp(ageInTicks - e.tickCount, 0.0F, 1.0F);
        double x = Mth.lerp(partial, e.xo, e.getX());
        double z = Mth.lerp(partial, e.zo, e.getZ());
        float dtTicks = ageInTicks - s.lastAge;
        s.newFrame = !(dtTicks == 0.0F);
        s.frameDistance = 0.0F;
        if (Float.isNaN(s.lastAge) || dtTicks < 0.0F || dtTicks > 10.0F
            || s.libraryGeneration != MotionLibrary.generation()) {
            s.dt = 0.0F;
            s.resetDynamics();
            s.libraryGeneration = MotionLibrary.generation();
        } else {
            s.dt = dtTicks / 20.0F;
            if (!Double.isNaN(s.lastX)) {
                double dx = x - s.lastX;
                double dz = z - s.lastZ;
                double d = Math.sqrt(dx * dx + dz * dz);
                // Teleports and seat snaps are not walking.
                s.frameDistance = d > 1.5 ? 0.0F : (float) d;
            }
            if (s.dt > 0.0F) {
                float speed = s.frameDistance / s.dt;
                float k = 1.0F - (float) Math.exp(-s.dt / 0.15F);
                float previous = s.speedBlocksPerSecond;
                s.speedBlocksPerSecond += (speed - s.speedBlocksPerSecond) * k;
                float rawAccel = (s.speedBlocksPerSecond - previous) / s.dt;
                s.accel += (rawAccel - s.accel) * k;
            }
        }
        s.propMain = null;
        s.propOff = null;
        s.lastAge = ageInTicks;
        s.lastX = x;
        s.lastZ = z;
        s.gaitApplied = false;
    }

    /** Resets a limb and its bend child together (a clip that re-owns a limb re-owns its joint). */
    public void resetLimb(ModelPart part) {
        part.resetPose();
        for (int i = 0; i < limbs.length; i++) {
            if (limbs[i] == part) {
                bends[i].resetPose();
                if (items != null && i < 2 && items[i] != null) {
                    items[i].resetPose();
                }
                return;
            }
        }
    }

    // ------------------------------------------------------------ clips ---

    /** Share of loop cycles that play the base clip when variants exist. */
    public static final float BASE_VARIANT_WEIGHT = 0.65F;

    /**
     * Action/overlay layer: additive, exactly the vanilla semantic, optionally
     * masked. When the clip has authored variants ({@code <key>__v2}, ...)
     * the next variant is chosen at each loop boundary of the one playing
     * (seeded per settler, so a crowd never switches in sync); a one-shot
     * picks once per start. Idle loops also get a per-settler phase over the
     * whole clip and a 0.92..1.08 playback rate.
     */
    public void play(AnimationDefinition def, float seconds, float weight, BoneMask mask) {
        MotionClip base = MotionLibrary.resolve(def);
        String key = MotionLibrary.keyOf(def);
        int id = entity == null ? 0 : entity.getId();
        if (key != null && key.startsWith("settler/idle") && base.looping()) {
            float rate = 0.92F + 0.16F * hash01(id, 0x51ED);
            seconds = seconds * rate + hash01(id, 0x7919) * base.length();
        }
        MotionClip clip = base;
        float local = seconds;
        // A masked layer (idle breathing under a meal) always plays its base:
        // variants are authored as whole-body idles, not as under-layers.
        MotionClip[] variants = mask == null ? MotionLibrary.variants(key) : null;
        if (variants != null && state != null) {
            MotionState.Cursor c = state.cursor(def);
            boolean restarted = Float.isNaN(c.lastSeconds) || seconds < c.lastSeconds - 0.05F
                || c.clip == null;
            if (restarted) {
                c.counter++;
                c.start = base.looping() && base.length() > 0.0F
                    ? (float) Math.floor(seconds / base.length()) * base.length() : 0.0F;
                c.clip = backSafe(pickVariant(base, variants, id, key, c.counter), base);
            }
            if (base.looping()) {
                int guard = 0;
                while (seconds - c.start >= c.clip.length() && guard++ < 64) {
                    c.start += c.clip.length();
                    c.counter++;
                    c.clip = backSafe(pickVariant(base, variants, id, key, c.counter), base);
                }
                if (guard >= 64) {
                    c.start = seconds;
                }
            }
            c.lastSeconds = seconds;
            clip = c.clip;
            local = seconds - c.start;
        }
        clip.apply(rig, local, weight, mask, scratch);
        if ((clip.sounds() != null || clip.particles() != null) && state != null && entity != null
            && state.newFrame && weight > 0.5F) {
            fireCues(def, clip, clip.wrap(local));
        }
        MotionProp[] props = clip.props();
        if (props != null && state != null && weight > 0.5F) {
            float t = clip.wrap(local);
            for (MotionProp prop : props) {
                if (prop.activeAt(t)) {
                    if (prop.hand() == MotionProp.MAINHAND) state.propMain = prop;
                    else state.propOff = prop;
                }
            }
        }
    }

    /** Fires timeline cues crossed since the last frame (loop wrap and variant change aware). */
    private void fireCues(Object def, MotionClip clip, float now) {
        MotionState.SoundCursor c = state.soundCursor(def);
        float before = c.local;
        boolean sameClip = c.clip == clip;
        c.clip = clip;
        c.local = now;
        if (Float.isNaN(before) || state.dt <= 0.0F || state.dt > 0.5F) {
            return;
        }
        ClipParticle[] particles = clip.particles();
        if (particles != null) {
            for (ClipParticle cue : particles) {
                if (crossed(cue.t(), before, now, sameClip, clip.looping())) {
                    state.pendingImpact = Math.max(state.pendingImpact, cue.impact());
                    spawnContact(cue);
                }
            }
        }
        if (clip.sounds() == null) {
            return;
        }
        for (ClipSound cue : clip.sounds()) {
            if (crossed(cue.t(), before, now, sameClip, clip.looping())) {
                var level = entity.level();
                float pitch = 1.0F + (level.random.nextFloat() * 2.0F - 1.0F) * cue.pitchJitter();
                level.playLocalSound(entity.getX(), entity.getY() + 0.9, entity.getZ(), cue.sound(),
                    net.minecraft.sounds.SoundSource.NEUTRAL, cue.volume(), pitch, false);
            }
        }
    }

    /**
     * Scripted-clip hook (see {@link MotionOverrides}): when this entity has an
     * active entry whose clip exists, plays it absolute (the caller has just
     * reset every part) and returns true -- the model should then skip its own
     * locomotion and one-shots. Works with the engine on or off, since a
     * scripted clip has no legacy fallback.
     */
    public boolean playScripted(LivingEntity e, float ageInTicks) {
        MotionOverrides.Active active = MotionOverrides.get(e.getId());
        if (active == null) {
            return false;
        }
        MotionClip clip = MotionLibrary.override(active.key());
        if (clip == null) {
            return false;
        }
        float seconds = MotionOverrides.secondsFor(e.getId(), active, clip, ageInTicks);
        if (Float.isNaN(seconds)) {
            return false;
        }
        clip.apply(rig, seconds, 1.0F, null, scratch);
        if ((clip.sounds() != null || clip.particles() != null) && state != null && state.newFrame) {
            fireCues(active, clip, clip.wrap(seconds));
        }
        return true;
    }

    /** Plays a library clip by key (additive, exact vanilla semantics); no-op when the key is missing. */
    public void playKey(String key, float seconds, float weight) {
        MotionClip clip = key == null ? null : MotionLibrary.override(key);
        if (clip == null) {
            return;
        }
        clip.apply(rig, clip.wrap(Math.max(0.0F, seconds)), weight, null, scratch);
        if ((clip.sounds() != null || clip.particles() != null) && state != null && entity != null
            && state.newFrame && weight > 0.5F) {
            fireCues(clip, clip, clip.wrap(Math.max(0.0F, seconds)));
        }
    }

    /**
     * Additive upper-body overlay hook (see {@link MotionOverrides#overlay}):
     * call late in setupAnim, after locomotion and stance. No-op without an
     * active entry or when the clip is missing.
     */
    public void playOverlay(LivingEntity e, float ageInTicks) {
        MotionOverrides.Overlay overlay = MotionOverrides.overlayOf(e.getId());
        if (overlay == null) {
            return;
        }
        float w = MotionOverrides.overlayWeight(e.getId(), overlay, ageInTicks);
        MotionClip clip = MotionLibrary.override(overlay.key());
        if (w <= 0.0F || clip == null) {
            return;
        }
        float seconds = (ageInTicks - overlay.startAgeTicks) / 20.0F;
        clip.apply(rig, clip.wrap(Math.max(0.0F, seconds)), w, BoneMask.UPPER_BODY, scratch);
    }

    private static boolean crossed(float t, float before, float now, boolean sameClip, boolean looping) {
        if (!sameClip) {
            return t <= now;
        }
        if (now >= before) {
            return t > before && t <= now;
        }
        return (looping && t > before) || t <= now;
    }

    private final net.minecraft.core.BlockPos.MutableBlockPos contactPos =
        new net.minecraft.core.BlockPos.MutableBlockPos();

    /**
     * Contact debris at a point in front of the entity (see ClipParticle).
     * Allocation happens only on the contact frame, never per frame.
     */
    private void spawnContact(ClipParticle cue) {
        if (cue.count() <= 0 || (cue.options() == null && cue.blockType() == null)) {
            return;
        }
        float yaw = entity.yBodyRot * Mth.DEG_TO_RAD;
        float fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
        float rx = -fz, rz = fx;
        double x = entity.getX() + (fx * cue.forward() + rx * cue.right()) * entityScale;
        double y = entity.getY() + cue.up() * entityScale;
        double z = entity.getZ() + (fz * cue.forward() + rz * cue.right()) * entityScale;
        spawnDebris(cue.options(), cue.blockType(), cue.blockMode(), cue.count(), x, y, z, fx, fz,
            cue.spread(), cue.speed());
    }

    private void spawnDebris(net.minecraft.core.particles.ParticleOptions options,
                             net.minecraft.core.particles.ParticleType<net.minecraft.core.particles.BlockParticleOption> blockType,
                             int blockMode, int count, double x, double y, double z, float fx, float fz,
                             float spread, float speed) {
        var level = entity.level();
        if (options == null) {
            net.minecraft.world.level.block.state.BlockState block;
            if (blockMode == ClipParticle.GROUND) {
                block = level.getBlockState(contactPos.set(x, y - 0.2, z));
            } else {
                block = level.getBlockState(contactPos.set(x, y, z));
                if (block.isAir()) {
                    x += fx * 0.35;
                    z += fz * 0.35;
                    block = level.getBlockState(contactPos.set(x, y, z));
                }
                if (!block.isAir()) {
                    // Debris flies off the struck FACE, not from inside the
                    // block: walk back toward the worker until the point is
                    // in the open (at most 0.75 block).
                    for (int step = 0; step < 15; step++) {
                        if (level.getBlockState(contactPos.set(x - fx * 0.05, y, z - fz * 0.05)).isAir()) {
                            x -= fx * 0.07;
                            z -= fz * 0.07;
                            break;
                        }
                        x -= fx * 0.05;
                        z -= fz * 0.05;
                    }
                }
            }
            if (block.isAir() || block.getRenderShape() == net.minecraft.world.level.block.RenderShape.INVISIBLE) {
                return;
            }
            options = new net.minecraft.core.particles.BlockParticleOption(blockType, block);
        }
        var random = level.random;
        for (int i = 0; i < count; i++) {
            double ox = (random.nextDouble() * 2.0 - 1.0) * spread;
            double oy = (random.nextDouble() * 2.0 - 1.0) * spread * 0.6;
            double oz = (random.nextDouble() * 2.0 - 1.0) * spread;
            // Debris kicks back toward the worker and up, fanned sideways.
            double vx = (-fx * 0.7 + (random.nextDouble() * 2.0 - 1.0) * 0.8) * speed;
            double vy = (0.6 + random.nextDouble() * 0.8) * speed;
            double vz = (-fz * 0.7 + (random.nextDouble() * 2.0 - 1.0) * 0.8) * speed;
            level.addParticle(options, x + ox, y + oy, z + oz, vx, vy, vz);
        }
    }

    /** Filming aid (/hsmotion force KEY N): pin a clip key to one variant (1 = base). */
    public static final java.util.Map<String, Integer> FORCED_VARIANT = new java.util.HashMap<>();

    /**
     * Variants whose hands reach behind the back (carry pack lane audit, 26 Sep: the forearm goes through
     * the job pack). While the back is busy the base clip plays instead; without a pack they stay in the mix.
     */
    public static final java.util.Set<String> BACK_REACH_VARIANTS = java.util.Set.of(
        "settler/idle__v2", "settler/idle_weaver__v3", "settler/idle_baker__v3", "settler/village_chat__v2",
        "settler/idle_innkeeper__v4", "settler/farm_plant__v2");
    private boolean backBusy;

    /** This frame's settler wears a job pack / rig on the back (set right after {@link #begin}). */
    public void setBackBusy(boolean busy) {
        backBusy = busy;
    }

    private MotionClip backSafe(MotionClip picked, MotionClip base) {
        return backBusy && picked != base && BACK_REACH_VARIANTS.contains(picked.key()) ? base : picked;
    }

    private static MotionClip pickVariant(MotionClip base, MotionClip[] variants, int id, String key, int counter) {
        Integer forced = FORCED_VARIANT.get(key);
        if (forced != null) {
            return forced <= 1 || variants.length == 0 ? base : variants[Math.min(variants.length, forced - 1) - 1];
        }
        float r = hash01(id * 31 + key.hashCode(), counter * 0x2545F491 + 0x9E37);
        if (r < BASE_VARIANT_WEIGHT || variants.length == 0) {
            return base;
        }
        int index = (int) ((r - BASE_VARIANT_WEIGHT) / (1.0F - BASE_VARIANT_WEIGHT) * variants.length);
        return variants[Math.min(variants.length - 1, index)];
    }

    /** Deterministic 0..1 from two ints (no allocation, stable across sessions). */
    public static float hash01(int a, int b) {
        long h = (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL);
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return (h >>> 40) / (float) (1L << 24);
    }

    /** Current frame's display props (read by the settler prop layer). */
    public MotionProp prop(int hand) {
        if (!engine || state == null) return null;
        return hand == MotionProp.MAINHAND ? state.propMain : state.propOff;
    }

    private float gaitAmplitude = 1.0F;

    /**
     * Gait amplitude for the NEXT walk() (1 = authored): scales the leg arc and
     * the stride together, so a heavy load takes shorter steps at a quicker
     * cadence without the planted foot sliding.
     */
    public void setGaitAmplitude(float amplitude) {
        gaitAmplitude = Math.max(0.3F, Math.min(1.0F, amplitude));
    }

    /** Per-settler gait personality: stride multiplier (1 = authored). */
    public void setStrideScale(float scale) {
        if (state != null) state.strideScale = scale;
    }

    public void playClip(MotionClip clip, float seconds, float weight, BoneMask mask) {
        clip.apply(rig, seconds, weight, mask, scratch);
    }

    /**
     * Locomotion layer. Replaces vanilla's {@code limbSwing * 100 ms} clock
     * with a per-entity phase advanced by real ground distance over the clip's
     * own stride, so cadence follows speed and the stance foot stays put.
     * Weight keeps vanilla's {@code min(amount * scaleFactor, 1)} envelope.
     */
    public void walk(AnimationDefinition def, float limbSwingAmount, float scaleFactor) {
        MotionClip clip = MotionLibrary.resolve(def);
        MotionState s = state;
        float weight = Math.min(limbSwingAmount * scaleFactor, 1.0F) * gaitAmplitude;
        if (s.newFrame && s.frameDistance > 0.0F) {
            float stride = strideOf(clip) * Math.max(0.35F, weight) * entityScale * s.strideScale;
            float before = s.gaitPhase;
            float advanced = before + s.frameDistance / Math.max(0.2F, stride);
            s.gaitPhase = advanced % 1.0F;
            // Footfalls at the half-cycle marks (each foot's plant), for the
            // bag bounce and heavy-step dust in secondary().
            if (weight > 0.3F) {
                int steps = (int) Math.floor(advanced * 2.0F) - (int) Math.floor(before * 2.0F);
                if (steps > 0) {
                    s.pendingFootfalls = Math.min(2, s.pendingFootfalls + steps);
                    s.lastFoot = ((int) Math.floor(advanced * 2.0F)) & 1;
                }
            }
        }
        float seconds = s.gaitPhase * clip.length();
        clip.apply(rig, seconds, weight, null, scratch);
        s.gaitWeight = weight;

        // Procedural joints for gaits that do not author their own.
        s.gaitElbow[0] = s.gaitElbow[1] = 0.0F;
        s.gaitKnee[0] = s.gaitKnee[1] = 0.0F;
        if (secondaryOn && weight > 0.01F) {
            for (int leg = 0; leg < 2; leg++) {
                if (clip.authorsBend(MotionBones.RIGHT_SHIN + leg)) continue;
                float knee = gaitKnee(clip, leg == 0 ? "right_leg" : "left_leg", seconds) * weight;
                s.gaitKnee[leg] = knee;
                bends[RIGHT_LEG + leg].xRot += knee;
            }
            for (int arm = 0; arm < 2; arm++) {
                if (clip.authorsBend(MotionBones.RIGHT_FOREARM + arm)) continue;
                ModelPart part = limbs[arm];
                // Relaxed carry: a slight constant bend, more as the arm swings forward.
                float forward = Mth.clamp(-part.xRot / 0.6F, 0.0F, 1.0F);
                float elbow = -(0.16F + 0.36F * forward * forward) * weight;
                s.gaitElbow[arm] = elbow;
                bends[arm].xRot += elbow;
            }
        }
        snapshotGait();
    }

    private void snapshotGait() {
        MotionState s = state;
        for (int i = 0; i < 4; i++) {
            s.gaitSnapshot[i * 3] = limbs[i].xRot;
            s.gaitSnapshot[i * 3 + 1] = limbs[i].yRot;
            s.gaitSnapshot[i * 3 + 2] = limbs[i].zRot;
        }
        s.gaitApplied = true;
    }

    /** True when the gait layer still owns this limb (nothing reset or overwrote it since). */
    public boolean gaitOwns(int limb) {
        MotionState s = state;
        if (s == null || !s.gaitApplied) return false;
        ModelPart p = limbs[limb];
        return p.xRot == s.gaitSnapshot[limb * 3] && p.yRot == s.gaitSnapshot[limb * 3 + 1]
            && p.zRot == s.gaitSnapshot[limb * 3 + 2];
    }

    /** Knee flexion from the clip's own leg swing: big through swing, a small load after heel strike. */
    private float gaitKnee(MotionClip clip, String leg, float seconds) {
        MotionClip.Track track = findRotation(clip, leg);
        if (track == null) {
            return 0.0F;
        }
        float eps = Math.max(0.01F, clip.length() * 0.02F);
        track.sample(clip.wrap(seconds), scratch);
        float pitch = scratch[0];
        track.sample(clip.wrap(seconds + eps), scratch);
        float ahead = scratch[0];
        float t0 = seconds - eps;
        if (t0 < 0.0F) t0 += clip.length();
        track.sample(clip.wrap(t0), scratch);
        float behind = scratch[0];
        // Radians per gait cycle; forward swing is negative pitch velocity.
        float velocity = (ahead - behind) / (2.0F * eps) * clip.length();
        float swing = smooth(Mth.clamp(-velocity / 3.2F, 0.0F, 1.0F));
        float loading = smooth(Mth.clamp(velocity / 3.2F, 0.0F, 1.0F))
            * Mth.clamp(-pitch / 0.45F, 0.0F, 1.0F);
        return 0.95F * swing + 0.14F * loading;
    }

    private static MotionClip.Track findRotation(MotionClip clip, String bone) {
        for (MotionClip.Track track : clip.tracks()) {
            if (track.target == MotionClip.ROTATION && track.bone.equals(bone)) {
                return track;
            }
        }
        return null;
    }

    /** Blocks travelled per full gait cycle at full weight, from the clip's own leg arc. */
    private float strideOf(MotionClip clip) {
        Float cached = strideCache.get(clip);
        if (cached != null) {
            return cached;
        }
        float stride = 1.8F;
        MotionClip.Track track = findRotation(clip, "right_leg");
        if (!Float.isNaN(clip.strideBlocks())) {
            stride = clip.strideBlocks();
        } else if (track != null) {
            float min = 0.0F, max = 0.0F;
            for (int i = 0; i <= 64; i++) {
                track.sample(clip.length() * i / 64.0F, scratch);
                min = Math.min(min, scratch[0]);
                max = Math.max(max, scratch[0]);
            }
            float legBlocks = 12.0F / 16.0F;
            float computed = 2.0F * legBlocks * ((float) Math.sin(-min) + (float) Math.sin(max));
            if (computed > 0.3F) {
                stride = computed;
            }
        }
        strideCache.put(clip, stride);
        return stride;
    }

    // -------------------------------------------------------- secondary ---

    /** Inputs for the overlay layer, reused every frame (no allocation). */
    public static final class Overlay {
        public boolean anchored;
        public boolean breathingClip;
        public boolean bagGripped;
        public boolean stationaryWork;
        public float load;
        public int hurtTime;
        public int hurtDuration;
        public ModelPart sack;
        public ModelPart[] packs = new ModelPart[0];
        public ModelPart brim;
        /** Personal carriage: torso pitch (+ = stooped) and extra head pitch, radians. */
        public float posture;
        public float headDrop;
        /** Shoulder mantle / cape that sways with speed and turn (or null). */
        public ModelPart cloak;
        /** Heavy bodies (brutes) raise a little dust on every footfall. */
        public boolean heavySteps;
        /** A job pack (CarryPackLayer) is drawn in the sack's frame although the sack mesh is hidden. */
        public boolean packVisible;
        /** 0..1 floor for exertion from the caller (combat, fleeing). */
        public float exertionFloor;
        /**
         * Carry pack lane: the back container is held still (seated, in bed). Unlike
         * {@link #anchored} this does NOT freeze it while fleeing, working or in a
         * one-shot, so a loaded pack keeps its damped sway on the panic run.
         */
        public boolean packPinned;
    }

    /**
     * Breathing, turn bank, hit reaction and lagging props. Subtle by
     * design -- every term is a few degrees at most and decays to rest.
     */
    /** Beyond this camera distance (blocks) the secondary layer is skipped (LOD). */
    public static final double SECONDARY_LOD_BLOCKS = 24.0;
    /** Beyond this camera distance the ankle leveling is skipped too. */
    public static final double FEET_LOD_BLOCKS = 40.0;

    public void secondary(LivingEntity e, float ageInTicks, Overlay in) {
        MotionState s = state;
        double camDistSq = cameraDistanceSq(e);
        levelFeet = engine && !in.anchored && camDistSq < FEET_LOD_BLOCKS * FEET_LOD_BLOCKS;
        if (!secondaryOn || s == null || camDistSq > SECONDARY_LOD_BLOCKS * SECONDARY_LOD_BLOCKS) {
            if (s != null) {
                s.pendingImpact = 0.0F;
                s.pendingFootfalls = 0;
            }
            return;
        }
        float dt = s.newFrame ? s.dt : 0.0F;
        float partial = Mth.clamp(ageInTicks - e.tickCount, 0.0F, 1.0F);
        float impact = 0.0F;
        int footfalls = 0;
        if (dt > 0.0F) {
            impact = s.pendingImpact;
            footfalls = s.pendingFootfalls;
            s.pendingImpact = 0.0F;
            s.pendingFootfalls = 0;
        }
        if (in.anchored) {
            footfalls = 0;
        }

        // Exertion: running, hauling and blows raise it within a few seconds;
        // it takes ~15 s of calm to fade. Drives breath rate and depth.
        if (dt > 0.0F) {
            float run = Mth.clamp((s.speedBlocksPerSecond - 2.4F) / 2.2F, 0.0F, 1.0F);
            float haul = in.load * Mth.clamp(s.speedBlocksPerSecond / 1.5F, 0.0F, 1.0F) * 0.6F;
            float target = Math.max(Math.max(run, haul), Mth.clamp(in.exertionFloor, 0.0F, 1.0F));
            float tau = target > s.exertion ? 4.0F : 15.0F;
            s.exertion += (target - s.exertion) * (1.0F - (float) Math.exp(-dt / tau));
            s.exertion = Mth.clamp(s.exertion + 0.05F * impact, 0.0F, 1.0F);
        }

        // Landings: knees give and (from a real drop) dust puffs at the feet.
        double y = Mth.lerp(partial, e.yo, e.getY());
        boolean onGround = e.onGround();
        if (dt > 0.0F && !Double.isNaN(s.lastY)) {
            float vy = (float) ((y - s.lastY) / dt);
            if (!onGround) {
                s.fallSpeed = Math.max(s.fallSpeed, -vy);
            } else if (!s.wasOnGround) {
                float drop = s.fallSpeed;
                s.fallSpeed = 0.0F;
                if (drop > 4.5F && !in.anchored) {
                    float strength = Mth.clamp((drop - 4.5F) / 7.0F, 0.15F, 1.0F);
                    s.landing.velocity += 3.2F * strength;
                    s.bagBounce.velocity += 14.0F * strength;
                    s.headJolt.velocity += 0.6F * strength;
                    if (drop > 9.0F || (in.heavySteps && drop > 5.0F)) {
                        dustAtFeet(e, 6 + (int) (6 * strength), 0.28F, 0.07F);
                    }
                }
            }
            if (onGround && s.wasOnGround) {
                s.fallSpeed = 0.0F;
            }
        }
        s.lastY = y;
        s.wasOnGround = onGround;

        // Turn banking: lean into the curve, gaze stays level.
        float yaw = Mth.rotLerp(partial, e.yBodyRotO, e.yBodyRot);
        if (dt > 0.0F && !Float.isNaN(s.lastBodyYaw)) {
            float rate = Mth.wrapDegrees(yaw - s.lastBodyYaw) / dt;
            if (Math.abs(rate) > 900.0F) rate = 0.0F;
            s.yawRate += (rate - s.yawRate) * (1.0F - (float) Math.exp(-dt / 0.12F));
        }
        s.lastBodyYaw = yaw;
        if (!in.anchored) {
            float speedFactor = Mth.clamp(s.speedBlocksPerSecond / 3.0F, 0.0F, 1.0F);
            float bank = Mth.clamp(-s.yawRate * MotionTuning.BANK_GAIN * speedFactor, -0.075F, 0.075F);
            torso.zRot += bank;
            head.zRot -= bank * 0.6F;
            // A heavy load settles the stance: slightly more knee under the hips.
            if (in.load > 0.0F && s.gaitApplied) {
                for (int leg = 0; leg < 2; leg++) {
                    if (gaitOwns(RIGHT_LEG + leg)) {
                        bends[RIGHT_LEG + leg].xRot += 0.10F * in.load;
                    }
                }
            }
        }

        // Personal carriage: who this settler is and how their day is going.
        if (!in.anchored && !in.stationaryWork) {
            torso.xRot += in.posture * MotionTuning.SECONDARY_SCALE;
            head.xRot += in.headDrop * MotionTuning.SECONDARY_SCALE;
        }

        // Breathing overlay for poses whose clip has no breath of its own.
        // The rate is integrated (3.6 s calm -> 1.5 s spent) so a change of pace
        // never jumps the chest; clips with their own breath get only the
        // exertion part on top.
        if (Float.isNaN(s.breathPhase)) {
            s.breathPhase = (e.getId() * 13.0F) / 20.0F * (float) (2.0 * Math.PI / 3.6);
        }
        float ex = s.exertion;
        if (dt > 0.0F) {
            float period = Mth.lerp(ex, 3.6F, 1.5F);
            s.breathPhase = (s.breathPhase + dt * (float) (2.0 * Math.PI) / period) % (float) (2.0 * Math.PI * 64.0);
        }
        if (!in.anchored) {
            float breath = (Mth.sin(s.breathPhase) + 1.0F) * 0.5F;
            float amount = in.breathingClip ? 0.9F * ex
                : (in.stationaryWork ? 0.7F : 0.45F) * (1.0F + 1.4F * ex);
            torso.xScale += 0.010F * breath * amount;
            torso.zScale += 0.014F * breath * amount;
            torso.yScale += 0.006F * breath * amount;
            limbs[RIGHT_ARM].zRot += 0.012F * breath * amount;
            limbs[LEFT_ARM].zRot -= 0.012F * breath * amount;
            // Winded: shoulders heave and the head bobs with each breath.
            torso.xRot += 0.030F * ex * breath;
            head.xRot -= 0.020F * ex * breath;
        }

        // Hit reaction: fast flinch, slow eased recovery, knees give a little.
        if (in.hurtTime > 0 && !in.anchored) {
            float duration = Math.max(1.0F, in.hurtDuration);
            float p = Mth.clamp((duration - in.hurtTime + partial) / duration, 0.0F, 1.0F);
            float env = p < 0.18F
                ? Easing.EASE_OUT_CUBIC.apply(p / 0.18F, Float.NaN)
                : 1.0F - Easing.EASE_IN_OUT_SINE.apply((p - 0.18F) / 0.82F, Float.NaN);
            torso.xRot += 0.17F * env;
            torso.y += 0.5F * env;
            head.xRot += 0.10F * env;
            limbs[RIGHT_ARM].xRot -= 0.20F * env;
            limbs[LEFT_ARM].xRot -= 0.20F * env;
            bends[RIGHT_ARM].xRot -= 0.35F * env;
            bends[LEFT_ARM].xRot -= 0.35F * env;
            bends[RIGHT_LEG].xRot += 0.16F * env;
            bends[LEFT_LEG].xRot += 0.16F * env;
        }

        // Lagging props. Angular velocities in rad/s of the parts they hang from.
        float torsoPitchVel = 0.0F, torsoRollVel = 0.0F, headPitchVel = 0.0F, headYawVel = 0.0F;
        if (dt > 0.0F) {
            if (!Float.isNaN(s.lastTorsoPitch)) {
                torsoPitchVel = clampVel((torso.xRot - s.lastTorsoPitch) / dt);
                torsoRollVel = clampVel((torso.zRot - s.lastTorsoYaw) / dt);
            }
            if (!Float.isNaN(s.lastHeadPitch)) {
                headPitchVel = clampVel((head.xRot - s.lastHeadPitch) / dt);
                headYawVel = clampVel((head.yRot - s.lastHeadYaw) / dt);
            }
            s.lastTorsoPitch = torso.xRot;
            s.lastTorsoYaw = torso.zRot;
            s.lastHeadPitch = head.xRot;
            s.lastHeadYaw = head.yRot;
        }
        float speedKick = -s.accel * 0.35F;
        // Contact impacts (clip cues) and footfalls kick every hanging thing once.
        if (impact > 0.0F) {
            s.toolShake.velocity += 7.0F * impact;
            s.headJolt.velocity += 0.55F * impact;
            s.brimPitch.velocity += 1.4F * impact;
            s.brimRoll.velocity += (s.lastFoot == 0 ? 0.7F : -0.7F) * impact;
            s.cloakPitch.velocity += 0.6F * impact;
            s.bagPitch.velocity += 0.5F * impact;
            s.bagBounce.velocity += 6.0F * impact;
        }
        if (footfalls > 0) {
            float heft = 0.55F + 0.6F * in.load;
            s.bagBounce.velocity += 7.5F * heft;
            s.bagPitch.velocity += 0.35F * heft;
            s.bagRoll.velocity += (s.lastFoot == 0 ? 0.3F : -0.3F) * heft;
            s.cloakPitch.velocity += 0.18F;
            s.brimPitch.velocity += 0.25F;
            if (in.heavySteps && e.onGround()) {
                dustAtFeet(e, 3, 0.12F, 0.035F);
            }
        }
        s.bagPitch.update(dt, -0.035F * torsoPitchVel, speedKick, 1.4F, 0.55F, 0.075F);
        s.bagRoll.update(dt, -0.035F * torsoRollVel + 0.0006F * s.yawRate, 0.0F, 1.2F, 0.55F, 0.07F);
        if (in.packPinned || in.bagGripped) {
            s.bagPitch.reset();
            s.bagRoll.reset();
        } else {
            if (in.sack != null && (in.sack.visible || in.packVisible)) {
                // The pack hangs from its top-back edge: it may swing out, but the
                // back stops it (a negative pitch drove its lower half into the torso).
                in.sack.xRot += Math.max(0.0F, s.bagPitch.value);
                in.sack.zRot += s.bagRoll.value;
            }
            for (ModelPart pack : in.packs) {
                if (pack != null && pack.visible) {
                    pack.xRot += s.bagPitch.value * 0.35F;
                    pack.zRot += s.bagRoll.value * 0.35F;
                }
            }
        }
        s.brimPitch.update(dt, -0.02F * headPitchVel, 0.0F, 2.2F, 0.6F, 0.045F);
        s.brimRoll.update(dt, 0.015F * headYawVel, 0.0F, 2.0F, 0.6F, 0.04F);
        if (in.brim != null && in.brim.visible && !in.anchored) {
            in.brim.xRot += s.brimPitch.value;
            in.brim.zRot += s.brimRoll.value;
        }

        // Tool lag: only while the free gait swings the tool arm.
        float armVel = 0.0F;
        ModelPart arm = limbs[RIGHT_ARM];
        if (dt > 0.0F && !Float.isNaN(s.lastArmPitch)) {
            armVel = clampVel((arm.xRot - s.lastArmPitch) / dt);
        }
        if (dt > 0.0F) s.lastArmPitch = arm.xRot;
        boolean freeSwing = !in.anchored && gaitOwns(RIGHT_ARM);
        s.toolPitch.update(dt, freeSwing ? 0.05F * armVel : 0.0F, 0.0F, 1.8F, 0.6F, 0.14F);
        if (!freeSwing && dt > 0.0F) {
            s.toolPitch.value *= (float) Math.exp(-dt / 0.08F);
        }
        // The haft rings briefly after a blow: fast, lightly damped, tiny.
        s.toolShake.update(dt, 0.0F, 0.0F, 7.5F, 0.22F, 0.10F);

        // Hat, hair and head: a short nod after a blow or a landing.
        s.headJolt.update(dt, 0.0F, 0.0F, 4.0F, 0.45F, 0.05F);
        if (!in.anchored) {
            head.xRot += s.headJolt.value;
        }

        // Sack drops a fraction of a pixel on each footfall and springs back.
        s.bagBounce.update(dt, 0.0F, 0.0F, 3.2F, 0.45F, 0.9F);
        if (in.packPinned || in.bagGripped) {
            s.bagBounce.reset();
        } else {
            if (in.sack != null && (in.sack.visible || in.packVisible)) {
                in.sack.y += s.bagBounce.value;
            }
            for (ModelPart pack : in.packs) {
                if (pack != null && pack.visible) {
                    pack.y += s.bagBounce.value * 0.4F;
                }
            }
        }

        // Cloak: trails with speed, lags the torso, swings out of turns.
        float speedFactor = Mth.clamp(s.speedBlocksPerSecond / 4.0F, 0.0F, 1.0F);
        s.cloakPitch.update(dt, 0.07F * speedFactor - 0.03F * torsoPitchVel, -s.accel * 0.25F,
            1.6F, 0.5F, 0.10F);
        s.cloakRoll.update(dt, -0.03F * torsoRollVel + 0.0005F * s.yawRate * speedFactor, 0.0F,
            1.5F, 0.5F, 0.08F);
        if (in.cloak != null && in.cloak.visible && !in.anchored) {
            in.cloak.xRot += s.cloakPitch.value;
            in.cloak.zRot += s.cloakRoll.value;
        }

        // Landing: knees give, hips follow, the body sinks so the soles stay down.
        s.landing.update(dt, 0.0F, 0.0F, 2.4F, 0.7F, 0.35F);
        float give = s.landing.value;
        if (give > 0.001F && !in.anchored) {
            for (int leg = 0; leg < 2; leg++) {
                limbs[RIGHT_LEG + leg].xRot -= give * 0.5F;
                bends[RIGHT_LEG + leg].xRot += give;
            }
            torso.xRot += give * 0.4F;
            root.y += 12.0F * (1.0F - Mth.cos(give * 0.5F));
        } else if (give < 0.0F) {
            s.landing.reset();
        }

        // Hip sway on walks: the pelvis rolls over the planted foot, the chest
        // counters it, so the head stays level. Scaled by the gait weight.
        if (!in.anchored && s.gaitApplied && s.gaitWeight > 0.05F) {
            float sway = Mth.sin(s.gaitPhase * (float) (2.0 * Math.PI)) * 0.035F * s.gaitWeight
                * MotionTuning.SECONDARY_SCALE;
            root.zRot += sway;
            torso.zRot -= sway * 0.7F;
            head.zRot -= sway * 0.3F;
        }

        // Arm lag on turns: the arms swing out of the curve and settle back.
        s.armTurn.update(dt, Mth.clamp(-s.yawRate * 0.0007F, -0.10F, 0.10F), 0.0F, 1.8F, 0.55F, 0.12F);
        if (!in.anchored && !in.stationaryWork) {
            for (int side = 0; side < 2; side++) {
                if (gaitOwns(side) || !s.gaitApplied) {
                    limbs[side].zRot += s.armTurn.value;
                }
            }
        }

        // Glance: a settler at rest turns its head toward a player standing
        // close by, and looks away again when they leave. Clamped to a
        // comfortable neck range, blended over ~0.4 s.
        glanceAtPlayer(e, s, in, dt, partial);

        // Turn in place: a standing body that rotates shuffles its feet (one
        // small step per ~40 degrees) instead of spinning on planted soles.
        boolean standing = !in.anchored && !in.stationaryWork && s.speedBlocksPerSecond < 0.35F
            && e.onGround();
        float turnRate = Math.abs(s.yawRate);
        if (dt > 0.0F) {
            float want = standing ? Mth.clamp((turnRate - 25.0F) / 60.0F, 0.0F, 1.0F) : 0.0F;
            float tau = want > s.turnStepWeight ? 0.10F : 0.22F;
            s.turnStepWeight += (want - s.turnStepWeight) * (1.0F - (float) Math.exp(-dt / tau));
            if (s.turnStepWeight > 0.01F) {
                s.turnStepPhase = (s.turnStepPhase + turnRate * dt / 80.0F) % 1.0F;
                if (turnRate > 25.0F) {
                    s.turnStepSign = Math.signum(s.yawRate);
                }
            } else {
                s.turnStepPhase = 0.0F;
            }
        }
        float tw = s.turnStepWeight;
        if (tw > 0.01F && standing) {
            float angle = s.turnStepPhase * (float) (2.0 * Math.PI);
            for (int leg = 0; leg < 2; leg++) {
                // Each leg lifts on its own half of the cycle; lead leg first.
                float lift = Math.max(0.0F, Mth.sin(angle + (leg == 0 ? 0.0F : (float) Math.PI)));
                float eased = lift * lift * (3.0F - 2.0F * lift);
                limbs[RIGHT_LEG + leg].xRot -= 0.35F * eased * tw;
                bends[RIGHT_LEG + leg].xRot += 0.80F * eased * tw;
                // The lifted foot swings toward the turn; the planted one stays.
                limbs[RIGHT_LEG + leg].yRot += 0.18F * s.turnStepSign * eased * tw;
            }
            // A touch of weight shift over the planted foot.
            torso.zRot += 0.025F * Mth.sin(angle) * tw;
        }
    }

    private static double cameraDistanceSq(LivingEntity e) {
        var camera = net.minecraft.client.Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera == null || !camera.isInitialized()) {
            return 0.0;
        }
        return camera.getPosition().distanceToSqr(e.getX(), e.getY(), e.getZ());
    }

    private void glanceAtPlayer(LivingEntity e, MotionState s, Overlay in, float dt, float partial) {
        var player = net.minecraft.client.Minecraft.getInstance().player;
        boolean want = false;
        float yawOff = 0.0F, pitchOff = 0.0F;
        if (player != null && player != e && !in.anchored && !in.stationaryWork && !player.isSpectator()
            && s.speedBlocksPerSecond < 1.2F) {
            double dx = player.getX() - e.getX();
            double dz = player.getZ() - e.getZ();
            double dy = player.getEyeY() - e.getEyeY();
            double flat = Math.sqrt(dx * dx + dz * dz);
            if (flat < 5.0 && flat > 0.3) {
                float bodyYaw = Mth.rotLerp(partial, e.yBodyRotO, e.yBodyRot);
                float toPlayer = (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0F;
                yawOff = Mth.wrapDegrees(toPlayer - bodyYaw);
                if (Math.abs(yawOff) < 100.0F) {
                    want = true;
                    yawOff = Mth.clamp(yawOff, -55.0F, 55.0F) * Mth.DEG_TO_RAD;
                    pitchOff = Mth.clamp((float) (-Mth.atan2(dy, flat)), -0.45F, 0.45F);
                }
            }
        }
        if (dt > 0.0F) {
            float k = 1.0F - (float) Math.exp(-dt / 0.15F);
            s.glance += ((want ? 0.75F : 0.0F) - s.glance) * (1.0F - (float) Math.exp(-dt / 0.4F));
            if (want) {
                s.glanceYaw += (yawOff - s.glanceYaw) * k;
                s.glancePitch += (pitchOff - s.glancePitch) * k;
            }
        }
        if (s.glance > 0.01F) {
            head.yRot = Mth.lerp(s.glance, head.yRot, s.glanceYaw);
            head.xRot = Mth.lerp(s.glance, head.xRot, s.glancePitch);
        }
    }

    private void dustAtFeet(LivingEntity e, int count, float spread, float speed) {
        float yaw = e.yBodyRot * Mth.DEG_TO_RAD;
        float fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
        spawnDebris(null, net.minecraft.core.particles.ParticleTypes.BLOCK, ClipParticle.GROUND, count,
            e.getX(), e.getY() + 0.05, e.getZ(), fx, fz, spread * entityScale, speed);
    }

    private static float clampVel(float v) {
        return Mth.clamp(v, -20.0F, 20.0F);
    }

    private static float smooth(float t) {
        return t * t * (3.0F - 2.0F * t);
    }

    // ----------------------------------------------------------- render ---

    private final org.joml.Quaternionf[] ankles = {new org.joml.Quaternionf(), new org.joml.Quaternionf()};
    private final org.joml.Quaternionf chain = new org.joml.Quaternionf();
    private final org.joml.Quaternionf twist = new org.joml.Quaternionf();
    private final org.joml.Quaternionf partRot = new org.joml.Quaternionf();
    private final org.joml.Quaternionf identityQ = new org.joml.Quaternionf();
    private final org.joml.Quaternionf swingQ = new org.joml.Quaternionf();
    private boolean levelFeet;

    /**
     * Ankle correction per leg: the foot keeps the shin's heading but its sole
     * stays level with the ground (root -> leg -> shin swing removed), fading
     * out past ~35-60 degrees of swing so lying, sitting and climbing poses
     * keep the foot on the shin.
     */
    private void computeAnkles() {
        for (int leg = 0; leg < 2; leg++) {
            org.joml.Quaternionf a = ankles[leg];
            a.identity();
            if (!levelFeet || !meshes[RIGHT_LEG + leg].hasAnkle()) continue;
            ModelPart thigh = limbs[RIGHT_LEG + leg];
            ModelPart shin = bends[RIGHT_LEG + leg];
            chain.rotationZYX(root.zRot, root.yRot, root.xRot);
            chain.mul(partRot.rotationZYX(thigh.zRot, thigh.yRot, thigh.xRot));
            chain.mul(partRot.rotationZYX(shin.zRot, shin.yRot, shin.xRot));
            float len = (float) Math.sqrt(chain.y * chain.y + chain.w * chain.w);
            if (len < 1.0E-5F) continue;
            twist.set(0.0F, chain.y / len, 0.0F, chain.w / len);
            // swing = chain * conj(twist); its angle decides the fade
            partRot.set(twist).conjugate();
            org.joml.Quaternionf swing = swingQ.set(chain).mul(partRot);
            float angle = 2.0F * (float) Math.acos(Math.min(1.0F, Math.abs(swing.w)));
            float fade = Mth.clamp((1.05F - angle) / 0.45F, 0.0F, 1.0F);
            if (fade <= 0.0F || angle < 1.0E-3F) continue;
            // A = chain^-1 * twist  (foot world = chain * A = twist: level, same heading)
            a.set(chain).conjugate().mul(twist);
            if (fade < 1.0F) {
                identityQ.identity();
                identityQ.slerp(a, fade, a);
            }
        }
    }

    private boolean anyBent() {
        if (!engine) return false;
        computeAnkles();
        for (int i = 0; i < 4; i++) {
            if (limbs[i].visible && (BendableLimb.isBent(bends[i])
                || (i >= 2 && !BendableLimb.isIdentity(ankles[i - 2])))) return true;
        }
        return false;
    }

    /**
     * Draws the rig. Unbent (or engine off): exactly vanilla. Bent: vanilla
     * for everything except the bent limbs, whose own cube is skipped and
     * redrawn as a continuous bent mesh; their hand/foot children ride the
     * lower segment.
     */
    public void render(PoseStack pose, VertexConsumer buffer, int light, int overlay, int color) {
        if (!anyBent()) {
            root.render(pose, buffer, light, overlay, color);
            return;
        }
        boolean[] bent = this.bent;
        int carriedIndex = 0;
        for (int i = 0; i < 4; i++) {
            bent[i] = limbs[i].visible && !limbs[i].skipDraw && (BendableLimb.isBent(bends[i])
                || (i >= 2 && !BendableLimb.isIdentity(ankles[i - 2])));
            if (bent[i]) {
                limbs[i].skipDraw = true;
                for (ModelPart child : carried[i]) {
                    carriedVisible[carriedIndex++] = child.visible;
                    child.visible = false;
                }
            } else {
                carriedIndex += carried[i].length;
            }
        }
        try {
            root.render(pose, buffer, light, overlay, color);
        } finally {
            carriedIndex = 0;
            for (int i = 0; i < 4; i++) {
                if (bent[i]) {
                    limbs[i].skipDraw = false;
                    for (ModelPart child : carried[i]) {
                        child.visible = carriedVisible[carriedIndex++];
                    }
                } else {
                    carriedIndex += carried[i].length;
                }
            }
        }
        if (!root.visible) {
            return;
        }
        for (int i = 0; i < 4; i++) {
            if (!bent[i]) continue;
            boolean arm = i < 2;
            if (arm && !torso.visible) continue;
            pose.pushPose();
            root.translateAndRotate(pose);
            if (arm) torso.translateAndRotate(pose);
            limbs[i].translateAndRotate(pose);
            org.joml.Quaternionf ankle = i >= 2 ? ankles[i - 2] : null;
            meshes[i].render(pose.last(), buffer, light, overlay, color, bends[i], ankle);
            meshes[i].applyLowerTransform(pose, bends[i], ankle);
            for (ModelPart child : carried[i]) {
                child.render(pose, buffer, light, overlay, color);
            }
            pose.popPose();
        }
    }

    private final boolean[] bent = new boolean[4];

    /** Hand space: root, torso, arm, then the elbow bend and a touch of tool lag. */
    public void applyHand(int arm, PoseStack pose) {
        if (!engine || !limbs[arm].visible) {
            return;
        }
        meshes[arm].applyLowerTransform(pose, bends[arm]);
        ModelPart item = items == null ? null : items[arm];
        if (item != null) {
            PartPose rest = item.getInitialPose();
            float dx = item.x - rest.x, dy = item.y - rest.y, dz = item.z - rest.z;
            boolean moved = dx != 0.0F || dy != 0.0F || dz != 0.0F;
            boolean turned = item.xRot != 0.0F || item.yRot != 0.0F || item.zRot != 0.0F;
            if (moved || turned) {
                // Wrist: rotate about the palm (arm-local (0,10,0)) and slide the grip.
                float palm = HAND_Y / 16.0F;
                pose.translate(dx / 16.0F, palm + dy / 16.0F, dz / 16.0F);
                if (turned) {
                    pose.mulPose(itemRotation.rotationZYX(item.zRot, item.yRot, item.xRot));
                }
                pose.translate(0.0F, -palm, 0.0F);
            }
        }
        if (arm == RIGHT_ARM && state != null && secondaryOn
            && (state.toolPitch.value != 0.0F || state.toolShake.value != 0.0F)) {
            // Wrist lag around the palm (arm-local y = 10), tool trails the swing;
            // after a contact the haft rings for a few frames (pitch + a little roll).
            float shake = state.toolShake.value;
            pose.translate(0.0F, 10.0F / 16.0F, 0.0F);
            pose.mulPose(itemRotation.rotationZYX(shake * 0.45F, 0.0F, state.toolPitch.value + shake));
            pose.translate(0.0F, -10.0F / 16.0F, 0.0F);
        }
    }

    /** For debugging / tests. */
    public MotionState state() {
        return state;
    }

    public LivingEntity entity() {
        return entity;
    }

    @SuppressWarnings("unused")
    private HierarchicalModel<?> model() {
        return model;
    }
}
