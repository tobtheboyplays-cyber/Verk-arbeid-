package com.hearthstead.client.motion;

import com.hearthstead.Hearthstead;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.world.entity.player.Player;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.util.ObfuscationReflectionHelper;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * The one shared player-pose hook: downed/revive, finisher executions, any
 * future scripted player motion. Features register a provider; each frame,
 * after vanilla {@code PlayerModel.setupAnim}, the first provider that
 * returns a request has its library clip ({@code animations/player/*.json},
 * key {@code player/<name>}) applied ADDITIVELY over the vanilla pose (so a
 * clip authored over Pose.SWIMMING / CROUCHING keeps that base).
 *
 * <p>Installed by swapping each PlayerRenderer's model for {@link Model}, a
 * PlayerModel subclass (reflection on LivingEntityRenderer.model; no mixin,
 * no access transformer). If the swap fails, providers are simply never
 * asked -- vanilla rendering is untouched.
 *
 * <p>Bones: head, body, right_arm, left_arm, right_leg, left_leg. The hat,
 * jacket, sleeves and pants are re-copied from them afterwards, so do not
 * key the layer bones. First-person hands are never posed.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class PlayerClips {

    /**
     * A clip request for one player this frame.
     *
     * @param key            library key ("player/downed_idle") or a bare constant
     *                       ("DOWNED_IDLE" -> "player/downed_idle")
     * @param startGameTime  level game time the clip started at
     * @param loop           wrap the clip; otherwise hold its last frame
     * @param absolute       reset head, body, arms and legs to rest first, so a
     *                       full-body choreography authored from rest is not
     *                       skewed by vanilla's item arm pose, swing, crouch or bob
     */
    public record Request(String key, long startGameTime, boolean loop, boolean absolute) {
        public Request(String key, long startGameTime, boolean loop) {
            this(key, startGameTime, loop, false);
        }
    }

    private static final List<Function<Player, Request>> PROVIDERS = new CopyOnWriteArrayList<>();

    private PlayerClips() {
    }

    /** Registers a provider (first non-null request wins, in registration order). */
    public static void register(Function<Player, Request> provider) {
        PROVIDERS.add(provider);
    }

    static String keyOf(String key) {
        return key.indexOf('/') >= 0 ? key : "player/" + key.toLowerCase(Locale.ROOT);
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onAddLayers(EntityRenderersEvent.AddLayers event) {
        for (PlayerSkin.Model skin : event.getSkins()) {
            EntityRenderer<? extends Player> renderer = event.getSkin(skin);
            if (!(renderer instanceof LivingEntityRenderer<?, ?> living)
                || !(living.getModel() instanceof PlayerModel<?>)) {
                continue;
            }
            boolean slim = skin == PlayerSkin.Model.SLIM;
            try {
                ModelPart root = event.getEntityModels().bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER);
                ObfuscationReflectionHelper.setPrivateValue(LivingEntityRenderer.class, living,
                    new Model(root, slim), "model");
            } catch (RuntimeException failure) {
                Hearthstead.LOGGER.warn("Hearthstead motion: player pose hook not installed for {} ({})",
                    skin, failure.toString());
            }
        }
    }

    /** PlayerModel that asks the providers after the vanilla pose. */
    public static final class Model extends PlayerModel<AbstractClientPlayer> {
        private final MotionRig rig;
        private final float[] scratch = new float[3];

        public Model(ModelPart root, boolean slim) {
            super(root, slim);
            this.rig = new MotionRig(root);
        }

        @Override
        public void setupAnim(AbstractClientPlayer player, float limbSwing, float limbSwingAmount,
                              float ageInTicks, float netHeadYaw, float headPitch) {
            // Rest pose first, every frame (QA-ANIM-01). Clips are additive (+=) and vanilla
            // re-sets only part of each bone (not head/body x and z, leg x or any scale), so
            // a revive clip's offsets grew frame by frame and stayed after it ended. This
            // renderer model is shared by every player of the same skin type, so leftovers
            // would also bleed onto others. Vanilla then poses what it manages.
            restPose();
            super.setupAnim(player, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            // PlayerRenderer.renderHand calls setupAnim with all zeros: never pose first-person arms.
            if (PROVIDERS.isEmpty() || ageInTicks == 0.0F || player.level() == null) {
                return;
            }
            Request request = null;
            for (Function<Player, Request> provider : PROVIDERS) {
                try {
                    request = provider.apply(player);
                } catch (RuntimeException failure) {
                    request = null;
                }
                if (request != null) {
                    break;
                }
            }
            if (request == null) {
                return;
            }
            MotionClip clip = MotionLibrary.override(keyOf(request.key()));
            if (clip == null) {
                return;
            }
            float partial = ageInTicks - player.tickCount;
            float seconds = Math.max(0.0F,
                (player.level().getGameTime() - request.startGameTime() + partial) / 20.0F);
            if (clip.length() > 0.0F) {
                seconds = request.loop() || clip.looping() ? seconds % clip.length() : Math.min(seconds, clip.length());
            }
            if (request.absolute()) {
                restPose(); // discard vanilla's pose too: the clip is authored from rest
            }
            clip.apply(rig, seconds, 1.0F, null, scratch);
            hat.copyFrom(head);
            jacket.copyFrom(body);
            leftSleeve.copyFrom(leftArm);
            rightSleeve.copyFrom(rightArm);
            leftPants.copyFrom(leftLeg);
            rightPants.copyFrom(rightLeg);
        }

        /** Head, body, arms and legs back to the baked rest pose (every field, scale included). */
        void restPose() {
            head.resetPose();
            body.resetPose();
            rightArm.resetPose();
            leftArm.resetPose();
            rightLeg.resetPose();
            leftLeg.resetPose();
        }
    }
}
