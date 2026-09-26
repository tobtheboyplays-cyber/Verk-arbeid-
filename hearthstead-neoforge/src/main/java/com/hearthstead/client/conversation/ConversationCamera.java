package com.hearthstead.client.conversation;

import com.hearthstead.Hearthstead;
import com.hearthstead.HearthsteadClientConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import javax.annotation.Nullable;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;

/**
 * Bannerlord-style camera focus, plus the Shadow-of-War intro swoop.
 *
 * <p>Vanilla places the camera (first or third person) first; then, in the
 * FOV hook that runs after placement and before the world renders, this
 * class blends from that vanilla pose to a framed shot: over the player's
 * left shoulder with the speaker in the left/centre third. Encounters first
 * swoop to a dramatic framing in front of the NPC (low angle for brutes and
 * captains, eye level for visitors), then settle into the talk framing.
 * Closing eases back to the live vanilla pose.
 *
 * <p>No clipping: every frame the framed position is ray-cast from the
 * nearest person's eyes and pulled in in front of any block. Presentation
 * only; nothing moves the player. Off via {@code [conversations] cameraFocus}.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class ConversationCamera {
    private enum Kind { VANILLA, INTRO, TALK, FROZEN }

    private record Pose(Vec3 pos, float yaw, float pitch) {
    }

    private static int npcId = -1;
    private static boolean low;
    private static Kind from = Kind.VANILLA;
    private static Kind to = Kind.VANILLA;
    private static long startNanos;
    private static float durationSeconds = 0.6F;
    private static long introNanos;
    @Nullable private static Pose frozen;
    @Nullable private static Pose lastApplied;
    private static float lastWeight;

    private static Method setPosition;
    private static Method setRotation;
    private static boolean rotation3;
    private static Field detached;
    private static boolean reflectionFailed;
    private static boolean loggedApply;
    private static long debugNanos;
    private static boolean frozenFromTalk;
    private static boolean talkFromEyes;

    private ConversationCamera() {
    }

    /**
     * A conversation opened with {@code entityId}. {@code cinematic}: a short swoop to the intro
     * framing (0.5 s; a raid captain's first meeting 0.8 s); otherwise a quick 0.4 s ease.
     */
    public static void begin(int entityId, boolean encounter, boolean lowAngle) {
        npcId = entityId;
        low = lowAngle;
        if (!HearthsteadClientConfig.conversationCamera()) {
            from = to = Kind.VANILLA;
            return;
        }
        boolean cinematic = encounter && HearthsteadClientConfig.encounterCinematics();
        startFrom(Kind.VANILLA, cinematic ? Kind.INTRO : Kind.TALK, cinematic ? (lowAngle ? 0.8F : 0.5F) : 0.4F);
        com.hearthstead.client.sound.HsSound.ui("convo.pull_in", null, cinematic ? 0.6F : 0.4F, 1.0F);
        introNanos = System.nanoTime();
    }

    /** The intro card finished or was skipped: settle into the talk framing. */
    public static void introDone() {
        if (to != Kind.INTRO) return;
        frozenFromTalk = false;
        freezeCurrent();
        startFrom(Kind.FROZEN, Kind.TALK, 0.45F);
    }

    public static void end() {
        if (to == Kind.VANILLA && lastWeight <= 0.0F) return;
        frozenFromTalk = to == Kind.TALK || (to == Kind.VANILLA && frozenFromTalk);
        freezeCurrent();
        startFrom(Kind.FROZEN, Kind.VANILLA, 0.5F);
    }

    public static boolean framing() {
        return lastWeight > 0.02F;
    }

    private static void freezeCurrent() {
        frozen = lastApplied;
    }

    private static void startFrom(Kind a, Kind b, float seconds) {
        from = a;
        to = b;
        startNanos = System.nanoTime();
        durationSeconds = seconds;
    }

    @SubscribeEvent
    public static void onHand(RenderHandEvent event) {
        if (framing()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onFov(ViewportEvent.ComputeFov event) {
        if (!event.usedConfiguredFov()) return;
        Minecraft mc = Minecraft.getInstance();
        Camera camera = event.getCamera();
        if (mc.level == null || mc.player == null || reflectionFailed) return;
        if (from == Kind.VANILLA && to == Kind.VANILLA) {
            lastWeight = 0.0F;
            lastApplied = null;
            return;
        }
        Entity npc = npcId < 0 ? null : mc.level.getEntity(npcId);
        float pt = (float) event.getPartialTick();
        Pose vanilla = new Pose(camera.getPosition(), camera.getYRot(), camera.getXRot());
        if (npc == null && (from != Kind.FROZEN || to != Kind.VANILLA)) {
            // Speaker gone: ease home from wherever we are.
            if (to != Kind.VANILLA) end();
        }
        Pose a = pose(from, vanilla, npc, pt);
        Pose b = pose(to, vanilla, npc, pt);
        float t = Mth.clamp((System.nanoTime() - startNanos) / 1.0e9F / durationSeconds, 0.0F, 1.0F);
        float s = t * t * (3.0F - 2.0F * t);
        Pose result = lerp(a, b, s);
        float weight = to == Kind.VANILLA ? 1.0F - s : 1.0F;
        if (to == Kind.VANILLA && t >= 1.0F) {
            from = Kind.VANILLA;
            lastWeight = 0.0F;
            lastApplied = null;
            return;
        }
        // The intro frames the speaker from in front, between the two: the local player stays hidden there
        // (else their body fills the frame). Over-the-shoulder talk shows them.
        boolean showPlayer = (to == Kind.TALK && !talkFromEyes) || (to == Kind.VANILLA && frozenFromTalk && !talkFromEyes);
        if (!apply(camera, result, weight > 0.02F && showPlayer)) return;
        if (!loggedApply) {
            loggedApply = true;
            Hearthstead.LOGGER.info("HSTALK_CAMERA applied pos={} yaw={} pitch={} weight={}", result.pos(), result.yaw(), result.pitch(), weight);
        }
        lastApplied = result;
        lastWeight = weight;
        event.setFOV(event.getFOV() * (1.0D - 0.12D * weight));
    }

    private static Pose pose(Kind kind, Pose vanilla, @Nullable Entity npc, float pt) {
        Minecraft mc = Minecraft.getInstance();
        if (kind == Kind.VANILLA || npc == null || mc.player == null) return kind == Kind.FROZEN && frozen != null ? frozen : vanilla;
        if (kind == Kind.FROZEN) return frozen != null ? frozen : vanilla;
        Vec3 eye = mc.player.getEyePosition(pt);
        Vec3 speaker = npc.getEyePosition(pt);
        Vec3 flat = new Vec3(speaker.x - eye.x, 0.0D, speaker.z - eye.z);
        if (flat.lengthSqr() < 1.0E-4D) flat = Vec3.directionFromRotation(0.0F, mc.player.getYRot());
        Vec3 d = flat.normalize();
        Vec3 right = new Vec3(-d.z, 0.0D, d.x);
        if (kind == Kind.TALK) {
            // Over the left shoulder: the player's head sits screen-right, the speaker left/centre.
            // Far enough back and wide enough that the player's head sits at the right edge, not over the speaker;
            // aimed low so the speaker's face lands in the upper half, above the talk bar.
            // Blocked behind (trees, walls, a hillside): try the right shoulder, then fall back to the
            // player's own eyes looking at the speaker (the body is hidden then, never a face-full of hair).
            double wanted = Math.sqrt(2.1D * 2.1D + 1.25D * 1.25D + 0.3D * 0.3D);
            Vec3 left = eye.subtract(d.scale(2.1D)).subtract(right.scale(1.25D)).add(0.0D, 0.3D, 0.0D);
            Vec3 cam = unclip(eye, left);
            Vec3 target = speaker.add(right.scale(0.25D)).add(0.0D, -1.05D, 0.0D);
            if (cam.distanceTo(eye) < wanted * 0.75D) {
                Vec3 rightSide = unclip(eye, eye.subtract(d.scale(2.1D)).add(right.scale(1.25D)).add(0.0D, 0.3D, 0.0D));
                if (rightSide.distanceTo(eye) >= wanted * 0.75D) {
                    cam = rightSide;
                    target = speaker.subtract(right.scale(0.25D)).add(0.0D, -1.05D, 0.0D);
                } else {
                    talkFromEyes = true;
                    return look(eye, speaker.add(0.0D, -0.55D, 0.0D));
                }
            }
            talkFromEyes = false;
            return look(cam, target);
        }
        // INTRO: in front of the speaker, pushing slowly in; low angle for brutes and captains.
        float hold = Mth.clamp((System.nanoTime() - introNanos) / 1.0e9F / 1.5F, 0.0F, 1.0F);
        double distance = Mth.lerp(hold, 2.7D, 2.1D);
        double lift = low ? -1.05D : -0.05D;
        Vec3 cam = speaker.subtract(d.scale(distance)).add(right.scale(0.55D)).add(0.0D, lift, 0.0D);
        cam = unclip(speaker, cam);
        Vec3 target = speaker.add(0.0D, low ? 0.05D : -0.1D, 0.0D);
        return look(cam, target);
    }

    /** Pull the camera in front of any block between {@code origin} and {@code wanted}. */
    private static Vec3 unclip(Vec3 origin, Vec3 wanted) {
        Minecraft mc = Minecraft.getInstance();
        HitResult hit = mc.level.clip(new ClipContext(origin, wanted, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE,
            mc.player));
        if (hit.getType() == HitResult.Type.MISS) return wanted;
        Vec3 dir = wanted.subtract(origin);
        double length = dir.length();
        if (length < 1.0E-4D) return origin;
        double keep = Math.max(0.0D, origin.distanceTo(hit.getLocation()) - 0.2D);
        return origin.add(dir.scale(keep / length));
    }

    private static Pose look(Vec3 cam, Vec3 target) {
        Vec3 v = target.subtract(cam);
        double horizontal = Math.sqrt(v.x * v.x + v.z * v.z);
        float yaw = (float) (Mth.atan2(v.z, v.x) * Mth.RAD_TO_DEG) - 90.0F;
        float pitch = (float) -(Mth.atan2(v.y, horizontal) * Mth.RAD_TO_DEG);
        return new Pose(cam, yaw, pitch);
    }

    private static Pose lerp(Pose a, Pose b, float s) {
        return new Pose(a.pos().lerp(b.pos(), s), a.yaw() + Mth.wrapDegrees(b.yaw() - a.yaw()) * s,
            Mth.lerp(s, a.pitch(), b.pitch()));
    }

    private static boolean apply(Camera camera, Pose pose, boolean detach) {
        try {
            if (setPosition == null) {
                setPosition = Camera.class.getDeclaredMethod("setPosition", Vec3.class);
                setPosition.setAccessible(true);
                try {
                    setRotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class, float.class);
                    rotation3 = true;
                } catch (NoSuchMethodException old) {
                    setRotation = Camera.class.getDeclaredMethod("setRotation", float.class, float.class);
                }
                setRotation.setAccessible(true);
                detached = Camera.class.getDeclaredField("detached");
                detached.setAccessible(true);
            }
            if (rotation3) setRotation.invoke(camera, pose.yaw(), pose.pitch(), 0.0F);
            else setRotation.invoke(camera, pose.yaw(), pose.pitch());
            setPosition.invoke(camera, pose.pos());
            if (detach) detached.setBoolean(camera, true);
            return true;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            reflectionFailed = true;
            Hearthstead.LOGGER.warn("Conversation camera disabled: camera hooks unavailable", failure);
            return false;
        }
    }
}
