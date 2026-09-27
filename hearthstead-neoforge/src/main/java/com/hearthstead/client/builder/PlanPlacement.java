package com.hearthstead.client.builder;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.BuilderPayloads;
import com.hearthstead.settlement.builder.BlueprintTransform;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import javax.annotation.Nullable;

/**
 * The anchored ghost of a crafted Building Plan (building-plan lane, 26 Sep;
 * MineColonies-style placement). Unlike the Builder's Plan ghost it does not
 * follow the crosshair: it appears where the player looked when the style was
 * picked and stays there while the player walks around. {@link PlanPanelScreen}
 * nudges, turns and mirrors it; Confirm sends the Builder's normal VALIDATE,
 * whose answer opens the usual {@link BuilderConfirmScreen} and PLACE order.
 * Right-clicking the plan again re-opens the panel for the pending placement.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class PlanPlacement {

    @Nullable
    private static String typeId;
    @Nullable
    private static String blueprintId;
    private static String styleLabel = "";
    @Nullable
    private static BuilderPayloads.Preview preview;
    /** Origin of the transformed footprint (null until the preview arrived). */
    @Nullable
    private static BlockPos origin;
    /** Where the player looked when picking: the footprint's centre goes here. */
    @Nullable
    private static BlockPos aim;
    private static int rotation;
    private static boolean mirror;

    private PlanPlacement() {
    }

    // ------------------------------------------------------------ control ---

    /** Right-click with a plan: the panel for its pending placement, else the style picker. */
    public static void openFor(String type) {
        Minecraft mc = Minecraft.getInstance();
        if (active() && type.equals(typeId)) {
            mc.setScreen(new PlanPanelScreen());
            return;
        }
        mc.setScreen(new PlanStyleScreen(type));
        BuilderClientState.requestCatalog();
    }

    /** A style was picked: anchor the ghost where the player looks (or just ahead of them). */
    public static void begin(String type, String blueprint, String label) {
        BuilderPlacement.cancel();
        clear();
        typeId = type;
        blueprintId = blueprint;
        styleLabel = label == null ? "" : label;
        rotation = 0;
        mirror = false;
        aim = aimPoint(Minecraft.getInstance());
        BuilderPayloads.Preview ready = PlanPreviews.get(blueprint);
        if (ready != null) {
            previewArrived(ready);
        }
        Minecraft.getInstance().setScreen(new PlanPanelScreen());
    }

    static void previewArrived(BuilderPayloads.Preview incoming) {
        if (blueprintId == null || !blueprintId.equals(incoming.id()) || preview != null) {
            return;
        }
        preview = incoming;
        if (aim != null) {
            BlueprintTransform t = transform();
            origin = aim.offset(-t.rotatedSizeX() / 2, -incoming.groundLevel(), -t.rotatedSizeZ() / 2);
            // Its near edge (not its middle) goes where the player looked, so the
            // ghost stands in front of them instead of around them.
            var player = Minecraft.getInstance().player;
            if (player != null) {
                Direction facing = player.getDirection();
                int depth = facing.getAxis() == Direction.Axis.X ? t.rotatedSizeX() : t.rotatedSizeZ();
                origin = origin.relative(facing, depth / 2 + 1);
            }
        }
    }

    /** Drops the pending placement (also called when the Builder's Plan cancels its own ghost). */
    public static void clear() {
        typeId = null;
        blueprintId = null;
        styleLabel = "";
        preview = null;
        origin = null;
        aim = null;
    }

    public static boolean active() {
        return blueprintId != null;
    }

    public static boolean ready() {
        return origin != null && preview != null;
    }

    @Nullable
    public static String typeId() {
        return typeId;
    }

    public static String styleLabel() {
        return styleLabel;
    }

    public static boolean mirrored() {
        return mirror;
    }

    public static int rotation() {
        return rotation;
    }

    /** Moves the ghost relative to where the player faces: forward/right/up in blocks. */
    public static void nudge(int forward, int right, int up) {
        Minecraft mc = Minecraft.getInstance();
        if (origin == null || mc.player == null) {
            return;
        }
        Direction facing = mc.player.getDirection();
        Direction side = facing.getClockWise();
        origin = origin.relative(facing, forward).relative(side, right).above(up);
    }

    /** Turns a quarter (1 = clockwise), keeping the footprint's centre where it was. */
    public static void rotate(int step) {
        if (origin == null) {
            rotation = Math.floorMod(rotation + step, 4);
            return;
        }
        BlueprintTransform before = transform();
        int cx = origin.getX() + before.rotatedSizeX() / 2;
        int cz = origin.getZ() + before.rotatedSizeZ() / 2;
        rotation = Math.floorMod(rotation + step, 4);
        BlueprintTransform after = transform();
        origin = new BlockPos(cx - after.rotatedSizeX() / 2, origin.getY(), cz - after.rotatedSizeZ() / 2);
    }

    public static void toggleMirror() {
        mirror = !mirror;
    }

    /** Confirm: the Builder's normal server validation; its answer opens the confirm sheet. */
    public static boolean confirm() {
        if (!ready() || blueprintId == null) {
            return false;
        }
        BuilderClientState.validateBlueprint(blueprintId, origin, rotation, mirror);
        return true;
    }

    static BlueprintTransform transform() {
        return new BlueprintTransform(rotation, mirror, preview == null ? 1 : Math.max(1, preview.sizeX()),
            preview == null ? 1 : Math.max(1, preview.sizeZ()));
    }

    @Nullable
    private static BlockPos aimPoint(Minecraft mc) {
        if (mc.player == null) {
            return null;
        }
        if (mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK) {
            return hit.getDirection() == Direction.UP ? hit.getBlockPos().above()
                : hit.getBlockPos().relative(hit.getDirection());
        }
        // Looking at the sky: a few blocks ahead, on the player's own level.
        return mc.player.blockPosition().relative(mc.player.getDirection(), 6);
    }

    static void hint(String key) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key), true);
        }
    }

    // ------------------------------------------------------------- events ---

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        clear();
        PlanPreviews.clear();
        PlanThumbs.clear();
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || !ready()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        BuilderPayloads.Preview p = preview;
        BlockPos o = origin;
        if (mc.level == null || p == null || o == null) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        BlueprintTransform t = transform();
        Rotation rot = Rotation.values()[t.rotation()];
        Mirror mir = mirror ? Mirror.FRONT_BACK : Mirror.NONE;
        var dispatcher = mc.getBlockRenderer();
        MultiBufferSource ghost = BuilderPlacement.ghostBuffers(buffers, 0.45F);
        int[] cells = p.cells();
        int drawn = 0;
        for (int i = 0; i + 1 < cells.length && drawn < 4096; i += 2) {
            int packed = cells[i];
            int s = cells[i + 1];
            if (s < 0 || s >= p.palette().size()) {
                continue;
            }
            int x = packed & 0xFF;
            int y = (packed >> 8) & 0xFF;
            int z = (packed >> 16) & 0xFF;
            BlockState state = p.palette().get(s).mirror(mir).rotate(rot);
            BlockPos world = o.offset(t.x(x, z), y, t.z(x, z));
            if (BuilderPlacement.alreadyThere(mc, world, state)) {
                continue; // grass already in the ground layer: not drawn over itself
            }
            pose.pushPose();
            pose.translate(world.getX() - cam.x, world.getY() - cam.y, world.getZ() - cam.z);
            dispatcher.renderSingleBlock(state, pose, ghost, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
            drawn++;
        }
        buffers.endBatch(RenderType.translucent());
        AABB box = new AABB(o.getX(), o.getY(), o.getZ(),
            o.getX() + t.rotatedSizeX(), o.getY() + p.sizeY(), o.getZ() + t.rotatedSizeZ())
            .move(-cam.x, -cam.y, -cam.z).inflate(0.01);
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        // Brass outline, like the Builder's Plan ghost once its spot is locked.
        LevelRenderer.renderLineBox(pose, lines, box, 0.95F, 0.80F, 0.30F, 1.0F);
        BuilderPayloads.Validation v = BuilderClientState.validation();
        if (v != null && v.kind() == BuilderPayloads.Validation.BLUEPRINT && o.equals(v.a())
            && p.id().equals(v.subject())) {
            for (BlockPos pos : v.playerBlocks()) {
                LevelRenderer.renderLineBox(pose, lines, new AABB(pos).move(-cam.x, -cam.y, -cam.z).inflate(0.02),
                    0.85F, 0.2F, 0.15F, 1.0F);
            }
        }
        buffers.endBatch(RenderType.lines());
    }
}
