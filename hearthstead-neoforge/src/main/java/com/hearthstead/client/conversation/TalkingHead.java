package com.hearthstead.client.conversation;

import com.hearthstead.Hearthstead;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * Sims-like talking head: an additive head layer on the speaker, render
 * only. While a line types out, each babble syllable kicks a small nod (a
 * bigger one on stressed syllables), with a slow side-to-side sway and a
 * tilt after a question; while the player picks a reply the speaker
 * listens with slow, irregular nods. Never perfectly periodic: phases come
 * from the entity id and the voice's random syllable timing.
 *
 * <p>Implemented as a temporary offset of the entity's head pitch and yaw
 * for the one render call (restored right after), so it works on every
 * humanoid model (settlers, raiders, villagers, the peddler) and stacks
 * additively with the upper-body talk/listen clips.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class TalkingHead {
    private static float savedXRot;
    private static float savedXRotO;
    private static float savedHeadYaw;
    private static float savedHeadYawO;
    private static int appliedTo = -1;

    private TalkingHead() {
    }

    @SubscribeEvent(priority = EventPriority.LOW)
    public static void pre(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (!ConversationClient.active() || entity.getId() != ConversationClient.npcId()) return;
        float t = (System.nanoTime() % 1_000_000_000_000L) / 1.0e9F;
        float phase = (entity.getId() * 0.37F) % 6.28F;
        float since = ConversationVoice.sinceSyllable(entity.getId());
        // Talking for the whole typed line (babble only opens it), easing out over 0.6 s after typing ends.
        float typing = ConversationVoice.sinceReveal(entity.getId());
        float talk = typing < 0.25F ? 1.0F : Mth.clamp(1.0F - (typing - 0.25F) / 0.6F, 0.0F, 1.0F);
        float kick = ConversationVoice.emphatic(entity.getId()) ? 8.0F : 4.0F;
        float syllableNod = since < 0.6F ? kick * (float) Math.exp(-since * 12.0F) * Mth.sin(since * 28.0F + 0.6F) : 0.0F;
        // Irregular speech wobble between syllables: two incommensurate waves, never periodic.
        float wobble = 2.6F * Mth.sin(t * 7.3F + phase) * (0.5F + 0.5F * Mth.sin(t * 2.07F + phase * 1.3F));
        float talkPitch = syllableNod + wobble;
        float talkYaw = 3.5F * Mth.sin(t * 1.3F + phase) + 1.4F * Mth.sin(t * 3.1F + phase * 2.0F)
            + (ConversationVoice.question(entity.getId()) ? 6.0F : 0.0F);
        float slow = Mth.sin(t * 0.9F + phase) * Mth.sin(t * 0.43F + phase * 1.7F);
        float listenPitch = slow > 0.55F ? (slow - 0.55F) * 18.0F : 0.0F;
        float listenYaw = 2.0F * Mth.sin(t * 0.6F + phase);
        float pitch = Mth.lerp(talk, listenPitch, talkPitch);
        float yaw = Mth.lerp(talk, listenYaw, talkYaw);
        savedXRot = entity.getXRot();
        savedXRotO = entity.xRotO;
        savedHeadYaw = entity.yHeadRot;
        savedHeadYawO = entity.yHeadRotO;
        appliedTo = entity.getId();
        entity.setXRot(savedXRot + pitch);
        entity.xRotO = savedXRotO + pitch;
        entity.yHeadRot = savedHeadYaw + yaw;
        entity.yHeadRotO = savedHeadYawO + yaw;
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void post(RenderLivingEvent.Post<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (appliedTo != entity.getId()) return;
        entity.setXRot(savedXRot);
        entity.xRotO = savedXRotO;
        entity.yHeadRot = savedHeadYaw;
        entity.yHeadRotO = savedHeadYawO;
        appliedTo = -1;
    }
}
