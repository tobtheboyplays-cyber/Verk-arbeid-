package com.hearthstead.client.finisher;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadClientConfig;
import com.hearthstead.client.motion.MotionClip;
import com.hearthstead.client.motion.MotionLibrary;
import com.hearthstead.client.motion.MotionRig;
import com.hearthstead.finisher.EnemyClass;
import com.hearthstead.finisher.FinisherService;
import com.hearthstead.finisher.FinisherTimeline;
import com.hearthstead.finisher.FinisherVariant;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.List;
import java.util.Map;

/**
 * Execution presentation that is not a body pose: the distance-scaled impact
 * shake (the one per-player extra the owner allows, identical rules for
 * everyone near), the brief weapon trail through the strike, and the
 * executor's first-person weapon following the move.
 *
 * <p>The trail is sampled analytically from the executor's clip at a few
 * past instants every frame, so it is frame-rate independent and every
 * viewer draws the same arc.</p>
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class FinisherFx {
    public static final int SHAKE_SETTLE_TICKS = 9;
    public static final double SHAKE_RADIUS = 16.0D;
    private static final float MAX_YAW = 1.4F;
    private static final float MAX_PITCH = 2.6F;
    private static final float MAX_ROLL = 2.2F;
    /** Trail from this many ticks before the impact to the end of the hit-stop. */
    public static final int TRAIL_LEAD_TICKS = 6;
    private static final int TRAIL_SAMPLES = 9;
    private static final float TRAIL_STEP_TICKS = 0.55F;

    private static float trauma;
    private static float previousTrauma;
    private static long seed;
    private static final FlatSampler SAMPLER = new FlatSampler();

    private FinisherFx() {
    }

    static void onStart(FinisherClient.Exec exec) {
        // Presentation starts are driven by the clips and the server's sounds.
    }

    /** Shared impact beat: every nearby player gets the same distance-scaled shake. */
    static void onImpact(FinisherClient.Exec exec, LivingEntity victim) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || victim.level() != player.level()
            || !HearthsteadClientConfig.combatCameraShake()) {
            return;
        }
        EnemyClass enemy = FinisherService.classify(victim);
        float magnitude = 0.35F + 0.45F * enemy.impactScale();
        if (exec.variant() != null && exec.variant().isDouble()) {
            magnitude *= 1.2F;
        }
        if (exec.isGuard()) {
            magnitude *= 0.7F;
        }
        double distance = Math.sqrt(player.distanceToSqr(victim));
        float added = magnitude * falloff(distance, SHAKE_RADIUS);
        if (added <= 0.001F) {
            return;
        }
        trauma = Math.min(1.0F, trauma + added);
        previousTrauma = Math.max(previousTrauma, trauma);
        seed = victim.getId() * 131L;
    }

    public static float falloff(double distance, double radius) {
        if (radius <= 0.0D || distance >= radius) {
            return 0.0F;
        }
        float t = (float) (1.0D - distance / radius);
        return t * t;
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        previousTrauma = trauma;
        if (trauma > 0.0F) {
            trauma = Math.max(0.0F, trauma - 1.0F / SHAKE_SETTLE_TICKS);
        }
    }

    @SubscribeEvent
    public static void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (trauma <= 0.0F && previousTrauma <= 0.0F) {
            return;
        }
        float partial = (float) event.getPartialTick();
        float t = Mth.lerp(partial, previousTrauma, trauma);
        Minecraft mc = Minecraft.getInstance();
        float amp = t * t * mc.options.screenEffectScale().get().floatValue();
        if (amp <= 0.0F) {
            return;
        }
        float time = FinisherClient.ticks() + partial + seed;
        event.setYaw(event.getYaw() + MAX_YAW * amp * Mth.sin(time * 3.1F));
        event.setPitch(event.getPitch() + MAX_PITCH * amp * Mth.sin(time * 3.9F + 1.1F));
        event.setRoll(event.getRoll() + MAX_ROLL * amp * Mth.sin(time * 2.5F + 2.3F));
    }

    // ------------------------------------------------------------------ weapon trail

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(true);
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        boolean drew = false;
        for (FinisherClient.Exec exec : FinisherClient.executions()) {
            FinisherVariant variant = exec.variant();
            if (variant == null || exec.isGuard()) {
                continue;
            }
            float real = exec.realTicks(partial);
            int impact = variant.impactTick();
            if (real < impact - TRAIL_LEAD_TICKS || real > impact + FinisherTimeline.HIT_STOP_TICKS + 2) {
                continue;
            }
            drew |= drawTrail(level, exec, exec.leadId(), variant.leadClipKey(), real, partial, cam,
                event.getPoseStack(), buffers);
            if (exec.partnerId() >= 0 && variant.partnerClipKey() != null) {
                drew |= drawTrail(level, exec, exec.partnerId(), variant.partnerClipKey(), real,
                    partial, cam, event.getPoseStack(), buffers);
            }
        }
        if (drew) {
            buffers.endBatch(RenderType.lightning());
        }
    }

    private static boolean drawTrail(ClientLevel level, FinisherClient.Exec exec, int actorId,
                                     String clipKey, float real, float partial, Vec3 cam,
                                     PoseStack poseStack, MultiBufferSource buffers) {
        Entity actor = level.getEntity(actorId);
        MotionClip clip = MotionLibrary.override(clipKey);
        if (actor == null || clip == null) {
            return false;
        }
        if (actor == Minecraft.getInstance().player
            && Minecraft.getInstance().options.getCameraType().isFirstPerson()) {
            return false;
        }
        Vec3 origin = actor.getPosition(partial);
        float yaw = exec.yawOf(actorId);
        Vec3[] base = new Vec3[TRAIL_SAMPLES];
        Vec3[] tip = new Vec3[TRAIL_SAMPLES];
        Vector3f b = new Vector3f();
        Vector3f t = new Vector3f();
        for (int i = 0; i < TRAIL_SAMPLES; i++) {
            float at = Math.max(0.0F, real - i * TRAIL_STEP_TICKS);
            SAMPLER.pose(clip, Math.min(at / FinisherTimeline.TICKS_PER_SECOND, clip.length()));
            SAMPLER.weaponPoints(b, t);
            base[i] = toWorld(origin, yaw, b);
            tip[i] = toWorld(origin, yaw, t);
        }
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        poseStack.pushPose();
        poseStack.translate(-cam.x, -cam.y, -cam.z);
        Matrix4f m = poseStack.last().pose();
        for (int i = 0; i < TRAIL_SAMPLES - 1; i++) {
            float a0 = 0.55F * (1.0F - i / (float) (TRAIL_SAMPLES - 1));
            float a1 = 0.55F * (1.0F - (i + 1) / (float) (TRAIL_SAMPLES - 1));
            quad(vc, m, base[i], tip[i], tip[i + 1], base[i + 1], a0, a1);
        }
        poseStack.popPose();
        return true;
    }

    private static void quad(VertexConsumer vc, Matrix4f m, Vec3 b0, Vec3 t0, Vec3 t1, Vec3 b1,
                             float a0, float a1) {
        vertex(vc, m, b0, a0 * 0.2F);
        vertex(vc, m, t0, a0);
        vertex(vc, m, t1, a1);
        vertex(vc, m, b1, a1 * 0.2F);
        // back face (the lightning type does not cull, but keep winding both ways for safety)
        vertex(vc, m, b1, a1 * 0.2F);
        vertex(vc, m, t1, a1);
        vertex(vc, m, t0, a0);
        vertex(vc, m, b0, a0 * 0.2F);
    }

    private static void vertex(VertexConsumer vc, Matrix4f m, Vec3 p, float alpha) {
        vc.addVertex(m, (float) p.x, (float) p.y, (float) p.z)
            .setColor(1.0F, 0.93F, 0.80F, Mth.clamp(alpha, 0.0F, 1.0F));
    }

    /**
     * Model px (player renderer space) to world, following
     * LivingEntityRenderer: rotY(180 - bodyYaw), scale(-1,-1,1), the player
     * scale 0.9375, translate(0, -1.501, 0).
     */
    static Vec3 toWorld(Vec3 origin, float bodyYaw, Vector3f modelPx) {
        Vector3f v = new Vector3f(modelPx.x / 16.0F, modelPx.y / 16.0F - 1.501F, modelPx.z / 16.0F)
            .mul(0.9375F);
        v.set(-v.x, -v.y, v.z);
        new Quaternionf().rotationY((180.0F - bodyYaw) * Mth.DEG_TO_RAD).transform(v);
        return origin.add(v.x, v.y, v.z);
    }

    // ------------------------------------------------------------------ first person

    @SubscribeEvent
    public static void onRenderHand(RenderHandEvent event) {
        if (event.getHand() != InteractionHand.MAIN_HAND || !HearthsteadClientConfig.finisherFirstPerson()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null) {
            return;
        }
        FinisherClient.Exec exec = FinisherClient.forActor(player);
        if (exec == null || exec.isGuard() || !exec.actorsLocked(event.getPartialTick())) {
            return;
        }
        String key = FinisherPoseHooks.playerClipKey(exec, player);
        MotionClip clip = key == null ? null : MotionLibrary.override(key);
        if (clip == null) {
            return;
        }
        float seconds = exec.realTicks(event.getPartialTick()) / FinisherTimeline.TICKS_PER_SECOND;
        SAMPLER.pose(clip, clip.looping() ? clip.wrap(seconds) : Math.min(seconds, clip.length()));
        float armX = SAMPLER.rightArm.xRot;
        float armZ = SAMPLER.rightArm.zRot;
        // First person looks along an arm held forward (xRot = -90 deg). A higher arm
        // lifts the weapon into view, a lower one drops it through the cut.
        float delta = armX + Mth.HALF_PI;
        float pitch = Mth.clamp(-delta * 0.55F, -0.95F, 0.95F);
        PoseStack ps = event.getPoseStack();
        ps.mulPose(Axis.XP.rotation(pitch));
        ps.mulPose(Axis.ZP.rotation(Mth.clamp(armZ * 0.45F, -0.5F, 0.5F)));
    }

    /** Flat vanilla-humanoid rig for sampling player clips outside the renderer. */
    static final class FlatSampler {
        final ModelPart base;
        final ModelPart head;
        final ModelPart body;
        final ModelPart rightArm;
        final ModelPart leftArm;
        final ModelPart rightLeg;
        final ModelPart leftLeg;
        final MotionRig rig;
        private final float[] scratch = new float[3];

        FlatSampler() {
            head = part(PartPose.ZERO);
            body = part(PartPose.ZERO);
            rightArm = part(PartPose.offset(-5.0F, 2.0F, 0.0F));
            leftArm = part(PartPose.offset(5.0F, 2.0F, 0.0F));
            rightLeg = part(PartPose.offset(-1.9F, 12.0F, 0.0F));
            leftLeg = part(PartPose.offset(1.9F, 12.0F, 0.0F));
            base = new ModelPart(List.of(), Map.of("head", head, "body", body, "right_arm", rightArm,
                "left_arm", leftArm, "right_leg", rightLeg, "left_leg", leftLeg));
            HierarchicalModel<Entity> wrapper = new HierarchicalModel<>() {
                @Override
                public ModelPart root() {
                    return base;
                }

                @Override
                public void setupAnim(Entity entity, float a, float b, float c, float d, float e) {
                }
            };
            rig = new MotionRig(wrapper);
        }

        private static ModelPart part(PartPose pose) {
            ModelPart p = new ModelPart(List.of(), Map.of());
            p.setInitialPose(pose);
            p.resetPose();
            return p;
        }

        void pose(MotionClip clip, float seconds) {
            base.getAllParts().forEach(ModelPart::resetPose);
            clip.apply(rig, seconds, 1.0F, null, scratch);
        }

        /** Right fist and blade tip in model px, from the posed right arm. */
        void weaponPoints(Vector3f handOut, Vector3f tipOut) {
            Matrix4f arm = new Matrix4f().translate(rightArm.x, rightArm.y, rightArm.z)
                .rotate(new Quaternionf().rotationZYX(rightArm.zRot, rightArm.yRot, rightArm.xRot));
            // Vanilla holds a blade out of the fist forward (-Z) and a little up the arm.
            arm.transformPosition(0.0F, 9.5F, -3.0F, handOut);
            arm.transformPosition(0.0F, 7.5F, -13.0F, tipOut);
        }
    }
}
