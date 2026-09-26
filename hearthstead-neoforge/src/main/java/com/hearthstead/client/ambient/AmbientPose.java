package com.hearthstead.client.ambient;

import com.hearthstead.HearthsteadClientConfig;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.util.Mth;

/**
 * Procedural stand-ins for the living-village cues until the animation lane's
 * authored clips exist (an authored clip plays through MotionOverrides and
 * this does nothing for it). Small, additive, eased in and out; the legs are
 * never touched, so a settler keeps walking while it waves or nods.
 * Called once per frame from SettlerModel, after the captain's nod.
 */
public final class AmbientPose {
    private AmbientPose() {
    }

    public static void apply(SettlerEntity entity, float ageInTicks, boolean anchored,
                             ModelPart head, ModelPart torso, ModelPart rightArm, ModelPart leftArm) {
        if (!HearthsteadClientConfig.ambientMotion()) {
            return;
        }
        AmbientClient.CueState state = AmbientClient.cue(entity.getId());
        if (state != null && state.procedural()) {
            float t = ageInTicks - state.startAge();
            int duration = state.cue().durationTicks();
            if (t >= 0.0F && t <= duration) {
                float env = Math.min(smooth(t / 5.0F), smooth((duration - t) / 6.0F));
                cue(state, t, env, anchored, head, torso, rightArm, leftArm);
            }
        }
        if (!anchored && !AmbientClient.rainClipActive(entity.getId()) && AmbientClient.rainWalking(entity)) {
            // Shoulders up, head down against the rain.
            head.xRot += 0.16F;
            torso.xRot += 0.09F;
        }
    }

    private static void cue(AmbientClient.CueState state, float t, float env, boolean anchored,
                            ModelPart head, ModelPart torso, ModelPart rightArm, ModelPart leftArm) {
        switch (state.cue()) {
            case NOD -> {
                float s = Mth.sin(t / state.cue().durationTicks() * (float) Math.PI * 2.0F);
                head.xRot += 0.30F * s * s;
            }
            case MOURN -> {
                head.xRot += 0.45F * env;
                if (!anchored) {
                    torso.xRot += 0.07F * env;
                    rightArm.xRot = Mth.lerp(env, rightArm.xRot, -0.45F);
                    rightArm.zRot = Mth.lerp(env, rightArm.zRot, -0.35F);
                    leftArm.xRot = Mth.lerp(env, leftArm.xRot, -0.45F);
                    leftArm.zRot = Mth.lerp(env, leftArm.zRot, 0.35F);
                }
            }
            case WAVE -> {
                if (anchored) {
                    head.xRot += 0.12F * env;
                    return;
                }
                rightArm.xRot = Mth.lerp(env, rightArm.xRot, -2.6F);
                rightArm.yRot = Mth.lerp(env, rightArm.yRot, 0.0F);
                rightArm.zRot = Mth.lerp(env, rightArm.zRot, 0.1F + 0.28F * Mth.sin(t * 0.85F));
                head.zRot += 0.06F * env;
            }
            case CHEER -> {
                if (anchored) {
                    return;
                }
                float pump = 0.18F * Mth.sin(t * 0.55F);
                rightArm.xRot = Mth.lerp(env, rightArm.xRot, -2.85F + pump);
                rightArm.zRot = Mth.lerp(env, rightArm.zRot, 0.2F);
                leftArm.xRot = Mth.lerp(env, leftArm.xRot, -2.85F - pump);
                leftArm.zRot = Mth.lerp(env, leftArm.zRot, -0.2F);
                head.xRot -= 0.2F * env;
            }
            case STRETCH_YAWN -> {
                if (anchored) {
                    head.xRot -= 0.25F * env;
                    return;
                }
                rightArm.xRot = Mth.lerp(env, rightArm.xRot, -2.9F);
                rightArm.zRot = Mth.lerp(env, rightArm.zRot, 0.35F);
                leftArm.xRot = Mth.lerp(env, leftArm.xRot, -2.9F);
                leftArm.zRot = Mth.lerp(env, leftArm.zRot, -0.35F);
                head.xRot -= 0.3F * env;
                torso.xRot -= 0.05F * env;
            }
            case SHIVER -> {
                torso.zRot += 0.035F * env * Mth.sin(t * 3.1F);
                head.zRot += 0.03F * env * Mth.sin(t * 3.7F + 1.0F);
                if (!anchored) {
                    rightArm.xRot = Mth.lerp(env, rightArm.xRot, -0.7F);
                    rightArm.zRot = Mth.lerp(env, rightArm.zRot, -0.5F);
                    leftArm.xRot = Mth.lerp(env, leftArm.xRot, -0.7F);
                    leftArm.zRot = Mth.lerp(env, leftArm.zRot, 0.5F);
                }
            }
            default -> {
            }
        }
    }

    private static float smooth(float x) {
        float c = Mth.clamp(x, 0.0F, 1.0F);
        return c * c * (3.0F - 2.0F * c);
    }
}
