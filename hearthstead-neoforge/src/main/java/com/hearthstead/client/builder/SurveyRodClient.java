package com.hearthstead.client.builder;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.BuilderActionPayload;
import com.hearthstead.registry.ModItems;
import com.hearthstead.settlement.builder.PlayerDesignSavedData;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;

/**
 * Survey Rod in the world: right-click the first corner, right-click the
 * opposite corner, name the design. The box is outlined while choosing and
 * turns red when it is larger than a design may be. Left-click cancels.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class SurveyRodClient {

    @Nullable
    private static BlockPos first;
    private static boolean armed = true;

    private SurveyRodClient() {
    }

    private static boolean holding(Minecraft mc) {
        return mc.player != null && mc.player.getMainHandItem().is(ModItems.SURVEY_ROD.get());
    }

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (!holding(mc) || mc.screen != null) {
            return;
        }
        if (event.isAttack()) {
            if (first != null) {
                first = null;
                event.setCanceled(true);
                hint("hearthstead.builder.design.cancelled");
            }
            return;
        }
        if (!event.isUseItem()) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        if (!armed || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        armed = false;
        BlockPos pos = hit.getBlockPos().immutable();
        if (first == null) {
            first = pos;
            hint("hearthstead.builder.design.second");
            return;
        }
        BlockPos a = first;
        first = null;
        String refusal = PlayerDesignSavedData.refusal(a, pos);
        if (refusal != null) {
            hint(refusal);
            return;
        }
        mc.setScreen(new DesignNameScreen(a, pos));
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.keyUse.isDown()) {
            armed = true;
        }
        if (first != null && !holding(mc)) {
            first = null;
        }
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || first == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        BlockPos b = mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            ? hit.getBlockPos() : first;
        Vec3 cam = event.getCamera().getPosition();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        boolean ok = PlayerDesignSavedData.refusal(first, b) == null;
        AABB box = AABB.encapsulatingFullBlocks(first, b).move(-cam.x, -cam.y, -cam.z).inflate(0.01);
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), box,
            ok ? 0.45F : 0.9F, ok ? 0.75F : 0.25F, ok ? 0.95F : 0.2F, 1.0F);
        buffers.endBatch(RenderType.lines());
    }

    static void save(BlockPos a, BlockPos b, String name) {
        if (Minecraft.getInstance().getConnection() != null) {
            PacketDistributor.sendToServer(new BuilderActionPayload(BuilderActionPayload.Action.SAVE_DESIGN, name,
                a, b, 0, false, 0, false, BuilderActionPayload.NONE));
        }
    }

    private static void hint(String key) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key), true);
        }
    }
}
