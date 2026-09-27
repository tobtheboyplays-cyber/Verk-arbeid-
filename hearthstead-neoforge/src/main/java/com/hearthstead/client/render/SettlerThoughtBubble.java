package com.hearthstead.client.render;

import com.hearthstead.entity.LifeNeed;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.logistics.StopReason;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.joml.Vector3f;

/**
 * A small, calm "thought bubble" above a settler's head naming, with one
 * icon, why their work is stopped. It is pure presentation of state the
 * server already syncs to every client:
 * <ul>
 *   <li>the active equipment need ({@link SettlerEntity#requestedEquipmentIcon()},
 *       backed by the synced requested-equipment projection), and</li>
 *   <li>the logistics / Work Zone stop reason
 *       ({@link SettlerEntity#logisticsStopReason()}, backed by the packed
 *       synced logistics-stop integer), and</li>
 *   <li>when no work blocker applies, the one pressing life need
 *       ({@link SettlerEntity#lifeNeed()}, a synced {@link LifeNeed} byte):
 *       hungry with an empty Hearth, shaken after a raid/alarm, no bed at
 *       nightfall, exhausted, or no Tavern in the evening.</li>
 * </ul>
 * No new network state, no world scan and no per-frame allocation beyond
 * the one-count stack the equipment accessor already returns.
 *
 * <p>Visibility mirrors the Tavern cue: within 6 blocks, or within 12 while
 * under the crosshair, depth-tested so it never shows through walls. It
 * yields to a visible Tavern cue, to sleep, to combat/fleeing, to portrait
 * draws and to F1.
 */
public final class SettlerThoughtBubble {
    /** One bubble pixel in blocks; a 16px icon is 0.32 blocks tall. */
    private static final float PX = 0.02F;
    /** Bubble centre above the bounding box, clear of the name plate. */
    private static final double BASE_HEIGHT = 0.92D;
    private static final long FADE_IN_MS = 150L;
    private static final double NEAR_SQ = 6.0D * 6.0D;
    private static final double TARGETED_SQ = 12.0D * 12.0D;

    private static final int LINEN = 0xF1E6CF;
    private static final int WALNUT = 0x5A3E2B;

    private static final ItemStack CHEST = new ItemStack(Items.CHEST);
    private static final ItemStack CAMPFIRE = new ItemStack(Items.CAMPFIRE);
    private static final ItemStack COMPASS = new ItemStack(Items.COMPASS);
    private static final ItemStack MAP = new ItemStack(Items.MAP);
    private static final ItemStack HOPPER = new ItemStack(Items.HOPPER);
    private static final ItemStack CLOCK = new ItemStack(Items.CLOCK);
    private static final ItemStack BOWL = new ItemStack(Items.BOWL);
    private static final ItemStack BED = new ItemStack(Items.RED_BED);
    private static final ItemStack HONEY = new ItemStack(Items.HONEY_BOTTLE);
    private static final Component QUESTION =
        Component.literal("?").withStyle(ChatFormatting.BOLD);
    private static final Component ALARM =
        Component.literal("!").withStyle(ChatFormatting.BOLD);
    private static final Component DROWSY =
        Component.literal("z").withStyle(ChatFormatting.BOLD);

    /**
     * Client render-thread only fade bookkeeping: {shownSinceMillis, thoughtKey}.
     * Weak keys, so an unloaded settler takes its entry with it.
     */
    private static final Map<SettlerEntity, long[]> SHOWN = new WeakHashMap<>();

    private SettlerThoughtBubble() {
    }

    /** icon == null means the drawn glyph (a "?" unless given). */
    private record Thought(long key, ItemStack icon, Component glyph) {
        Thought(long key, ItemStack icon) {
            this(key, icon, QUESTION);
        }
    }

    /**
     * Draws the bubble when eligible.
     *
     * @param suppressed true while a Tavern cue is visible or during a
     *                   portrait draw
     * @return true when this bubble owns the space above the head this frame,
     *         so the caller can skip its own overlapping equipment bubble
     */
    public static boolean render(SettlerEntity entity, float partialTick,
                                 PoseStack pose, MultiBufferSource buffers,
                                 int light, Font font,
                                 EntityRenderDispatcher dispatcher,
                                 boolean suppressed) {
        Thought thought = eligible(entity, dispatcher, suppressed)
            ? thoughtFor(entity) : null;
        if (thought == null) {
            SHOWN.remove(entity);
            return false;
        }
        long now = Util.getMillis();
        long[] state = SHOWN.get(entity);
        if (state == null || state[1] != thought.key()) {
            // A new or changed reason fades in again rather than popping.
            state = new long[] {now, thought.key()};
            SHOWN.put(entity, state);
        }
        float appear = Mth.clamp((now - state[0]) / (float) FADE_IN_MS, 0.0F, 1.0F);
        appear = appear * appear * (3.0F - 2.0F * appear);
        double distance = Math.sqrt(dispatcher.distanceToSqr(entity));
        float fade = appear * Mth.clamp((float) ((12.0D - distance) / 2.0D), 0.0F, 1.0F);
        if (fade <= 0.02F) {
            return true;
        }

        // Which local z points at the camera is derived, not assumed, so
        // layering stays right regardless of the billboard's sign convention.
        Vector3f normal = dispatcher.cameraOrientation()
            .transform(new Vector3f(0.0F, 0.0F, 1.0F));
        Vector3f look = Minecraft.getInstance().gameRenderer.getMainCamera()
            .getLookVector();
        float toward = normal.dot(look) < 0.0F ? 1.0F : -1.0F;

        // A slow 1px bob (0.5px amplitude), phase-shifted per settler so a
        // row of blocked workers does not breathe in unison.
        float t = entity.tickCount + partialTick + (entity.getId() & 63) * 3.0F;
        double bob = Mth.sin(t * 0.07F) * 0.5F * PX;
        float grow = 0.9F + 0.1F * appear;

        pose.pushPose();
        pose.translate(0.0D, entity.getBbHeight() + BASE_HEIGHT + bob, 0.0D);
        pose.mulPose(dispatcher.cameraOrientation());
        // Same near-distance cap as the name plate (QA gate: huge up close).
        float near = SettlerRenderer.nearLabelScale(entity.getPosition(partialTick)
            .add(0.0, entity.getBbHeight() + BASE_HEIGHT + bob, 0.0)
            .distanceTo(dispatcher.camera.getPosition()));
        pose.scale(PX * grow * near, -PX * grow * near, PX * grow * near);

        // Depth-tested plate (same render type as the Tavern cue plate).
        VertexConsumer plate = buffers.getBuffer(RenderType.textBackground());
        int rim = argb(WALNUT, fade * 255.0F);
        int fill = argb(LINEN, fade * 240.0F);
        float zRim = 0.0F;
        float zFill = toward * 0.05F;
        // Walnut outline with clipped corners, then the tapered tail.
        quad(plate, pose, -10, 10, -11, 11, zRim, rim, light);
        quad(plate, pose, -11, 11, -10, 10, zRim, rim, light);
        quad(plate, pose, -3, 3, 11, 12, zRim, rim, light);
        quad(plate, pose, -2, 2, 12, 13, zRim, rim, light);
        quad(plate, pose, -1, 1, 13, 14, zRim, rim, light);
        // Linen fill; the tail fill punches through the bottom outline.
        quad(plate, pose, -9, 9, -10, 10, zFill, fill, light);
        quad(plate, pose, -10, 10, -9, 9, zFill, fill, light);
        quad(plate, pose, -2, 2, 10, 12, zFill, fill, light);
        quad(plate, pose, -1, 1, 12, 13, zFill, fill, light);

        if (thought.icon() == null) {
            pose.pushPose();
            pose.translate(0.0F, 0.0F, toward * 0.1F);
            pose.scale(1.5F, 1.5F, 1.0F);
            float width = font.width(thought.glyph());
            font.drawInBatch(thought.glyph(), -width / 2.0F + 0.5F, -3.5F,
                argb(WALNUT, fade * 255.0F), false, pose.last().pose(), buffers,
                Font.DisplayMode.NORMAL, 0, light);
            pose.popPose();
        } else if (fade > 0.35F) {
            // Item quads cannot take alpha; the icon joins once the plate
            // is mostly in, which reads as part of the same soft fade.
            pose.pushPose();
            pose.translate(0.0F, 0.0F, toward * 9.0F);
            pose.scale(16.0F, -16.0F, 16.0F);
            if (toward < 0.0F) {
                pose.mulPose(Axis.YP.rotationDegrees(180.0F));
            }
            Minecraft.getInstance().getItemRenderer().renderStatic(thought.icon(),
                ItemDisplayContext.GUI, light, OverlayTexture.NO_OVERLAY, pose,
                buffers, entity.level(), entity.getId());
            pose.popPose();
        }
        pose.popPose();
        return true;
    }

    private static boolean eligible(SettlerEntity entity,
                                    EntityRenderDispatcher dispatcher,
                                    boolean suppressed) {
        Minecraft minecraft = Minecraft.getInstance();
        if (suppressed || minecraft.options.hideGui || !entity.isAlive()
            || entity.isInvisible() || entity.isSleeping()) {
            return false;
        }
        SettlerActivity activity = entity.getActivity();
        if (activity == SettlerActivity.SLEEPING
            || activity == SettlerActivity.COMBAT
            || activity == SettlerActivity.FLEEING) {
            return false;
        }
        double distanceSqr = dispatcher.distanceToSqr(entity);
        return distanceSqr <= TARGETED_SQ
            && (distanceSqr <= NEAR_SQ || minecraft.crosshairPickEntity == entity);
    }

    /**
     * A missing tool is the root cause the player can act on first, so it
     * outranks the logistics diagnosis. RESTING_AFTER_FAIL is a short,
     * self-healing backoff and deliberately stays quiet. Work blockers
     * outrank life needs; critical life needs (hungry with an empty Hearth,
     * shaken after a raid) outrank mild ones (no bed, exhausted, no Tavern).
     * The synced {@link LifeNeed} code already holds only the single most
     * pressing need, so at most one bubble is ever drawn.
     */
    private static Thought thoughtFor(SettlerEntity entity) {
        ItemStack tool = entity.requestedEquipmentIcon();
        if (!tool.isEmpty()) {
            return new Thought(1000L + BuiltInRegistries.ITEM.getId(tool.getItem()), tool);
        }
        Thought blocker = workBlocker(entity.logisticsStopReason());
        return blocker != null ? blocker : lifeNeed(entity.lifeNeed());
    }

    private static Thought lifeNeed(int code) {
        // Clear of 1000 + item id (the item registry is well over 1000).
        long key = 1_000_000L + code;
        return switch (code) {
            case LifeNeed.HUNGRY_NO_FOOD -> new Thought(key, BOWL);
            case LifeNeed.FRIGHTENED -> new Thought(key, null, ALARM);
            case LifeNeed.HOMELESS -> new Thought(key, BED);
            case LifeNeed.EXHAUSTED -> new Thought(key, null, DROWSY);
            case LifeNeed.WANTS_TAVERN -> new Thought(key, HONEY);
            case LifeNeed.TAVERN_NO_FOOD -> new Thought(key, BOWL);
            default -> null;
        };
    }

    private static Thought workBlocker(StopReason reason) {
        return switch (reason) {
            case CHEST_FULL, NO_WAREHOUSE_SPACE, FOOD_OVERFLOW -> new Thought(reason.wireId(), CHEST);
            case HEARTH_FULL -> new Thought(reason.wireId(), CAMPFIRE);
            case NO_PATH -> new Thought(reason.wireId(), COMPASS);
            case NO_WORK_ZONE -> new Thought(reason.wireId(), MAP);
            case NO_VALID_TARGET, NOTHING_TO_STUDY -> new Thought(reason.wireId(), null);
            // The specific missing input is not synced; the hopper says
            // "waiting for input" without guessing an item.
            case WAITING_INPUT -> new Thought(reason.wireId(), HOPPER);
            case RESERVED_BY_OTHER -> new Thought(reason.wireId(), CLOCK);
            case RESTING_AFTER_FAIL, NONE -> null;
        };
    }

    private static int argb(int rgb, float alpha) {
        return Mth.clamp(Math.round(alpha), 0, 255) << 24 | (rgb & 0xFFFFFF);
    }

    private static void quad(VertexConsumer buffer, PoseStack pose, float x0, float x1,
                             float y0, float y1, float z, int argb, int light) {
        int a = argb >>> 24;
        int r = argb >> 16 & 0xFF;
        int g = argb >> 8 & 0xFF;
        int b = argb & 0xFF;
        PoseStack.Pose last = pose.last();
        buffer.addVertex(last, x0, y0, z).setColor(r, g, b, a).setLight(light);
        buffer.addVertex(last, x1, y0, z).setColor(r, g, b, a).setLight(light);
        buffer.addVertex(last, x1, y1, z).setColor(r, g, b, a).setLight(light);
        buffer.addVertex(last, x0, y1, z).setColor(r, g, b, a).setLight(light);
    }
}
