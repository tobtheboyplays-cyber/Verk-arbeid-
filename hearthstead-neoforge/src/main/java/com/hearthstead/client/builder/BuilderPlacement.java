package com.hearthstead.client.builder;

import com.hearthstead.Hearthstead;
import com.hearthstead.network.BuilderPayloads;
import com.hearthstead.settlement.builder.BlueprintTransform;
import com.hearthstead.settlement.builder.DefenseLinePlanner;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * The Builder's Plan in the world (BUILDER lane, plan/BUILDER.md section 4):
 * a translucent ghost of the chosen blueprint that follows the crosshair,
 * and the two-click line tool for walls and barricades.
 *
 * <p>Controls while placing: <b>scroll</b> rotates (line: moves the gate),
 * <b>shift+scroll</b> raises/lowers, <b>M</b> or middle-click mirrors,
 * <b>G</b> toggles the gate, <b>right-click</b> asks the server to validate
 * (the confirm sheet then shows cost, stock and anything in the way),
 * <b>left-click</b> or <b>Esc</b> cancels. Only one ghost exists at a time.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class BuilderPlacement {

    public enum Mode { NONE, BLUEPRINT, LINE }

    private static Mode mode = Mode.NONE;
    // Blueprint ghost
    @Nullable
    private static BuilderPayloads.Preview preview;
    private static int rotation;
    private static boolean mirror;
    private static int lift;
    @Nullable
    private static BlockPos locked;
    // Line tool
    private static String lineKind = "palisade";
    @Nullable
    private static BlockPos lineA;
    private static boolean gate = true;
    private static int gateOffset;
    private static boolean useArmed = true;

    private BuilderPlacement() {
    }

    // ------------------------------------------------------------ control ---

    public static void startBlueprint(BuilderPayloads.Preview incoming) {
        preview = incoming;
        mode = Mode.BLUEPRINT;
        rotation = 0;
        mirror = false;
        lift = 0;
        locked = null;
        Minecraft mc = Minecraft.getInstance();
        mc.setScreen(null);
        hint("hearthstead.builder.ui.hint.blueprint");
    }

    public static void startLine(String kind, boolean withGate) {
        mode = Mode.LINE;
        lineKind = kind;
        lineA = null;
        gate = withGate;
        gateOffset = 0;
        Minecraft.getInstance().setScreen(null);
        hint("hearthstead.builder.ui.hint.line");
    }

    public static void cancel() {
        mode = Mode.NONE;
        preview = null;
        locked = null;
        lineA = null;
    }

    public static Mode mode() {
        return mode;
    }

    /** Called after a successful placement or a "back" from the confirm sheet. */
    public static void resume() {
        locked = null;
    }

    private static void hint(String key) {
        var player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(Component.translatable(key), true);
        }
    }

    // --------------------------------------------------------- geometry ---

    @Nullable
    private static BlockPos target(Minecraft mc) {
        if (!(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
            return null;
        }
        return hit.getDirection() == Direction.UP ? hit.getBlockPos().above()
            : hit.getBlockPos().relative(hit.getDirection());
    }

    /** Origin of the transformed footprint so the cursor sits at its centre-front. */
    @Nullable
    public static BlockPos origin(Minecraft mc) {
        if (locked != null) {
            return locked;
        }
        if (preview == null) {
            return null;
        }
        BlockPos at = target(mc);
        if (at == null) {
            return null;
        }
        BlueprintTransform t = transform();
        return at.offset(-t.rotatedSizeX() / 2, lift, -t.rotatedSizeZ() / 2);
    }

    static BlueprintTransform transform() {
        return new BlueprintTransform(rotation, mirror, preview == null ? 1 : Math.max(1, preview.sizeX()),
            preview == null ? 1 : Math.max(1, preview.sizeZ()));
    }

    // ------------------------------------------------------------- input ---

    @SubscribeEvent
    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (mode == Mode.NONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null) {
            return;
        }
        event.setCanceled(true);
        event.setSwingHand(false);
        if (event.isAttack()) {
            cancel();
            hint("hearthstead.builder.ui.hint.cancelled");
            return;
        }
        if (event.isPickBlock()) {
            mirror = !mirror;
            return;
        }
        if (!event.isUseItem() || !useArmed) {
            return;
        }
        useArmed = false;
        if (mode == Mode.BLUEPRINT && preview != null) {
            BlockPos origin = origin(mc);
            if (origin != null) {
                locked = origin;
                BuilderClientState.validateBlueprint(preview.id(), origin, rotation, mirror);
            }
        } else if (mode == Mode.LINE) {
            BlockPos at = target(mc);
            if (at == null) {
                return;
            }
            if (lineA == null) {
                lineA = at;
                hint("hearthstead.builder.ui.hint.line_second");
            } else {
                BuilderClientState.validateLine(lineKind, lineA, at, gate, gateOffset);
                lineB = at;
            }
        }
    }

    @Nullable
    private static BlockPos lineB;

    @SubscribeEvent
    public static void onClientTick(net.neoforged.neoforge.client.event.ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (!mc.options.keyUse.isDown()) {
            useArmed = true;
        }
        if (mode != Mode.NONE && (mc.player == null
            || !mc.player.getMainHandItem().is(com.hearthstead.registry.ModItems.BUILDERS_PLAN.get()))) {
            // Put the plan away and the ghost goes with it.
            if (mc.screen == null) {
                cancel();
            }
        }
    }

    @SubscribeEvent
    public static void onScroll(InputEvent.MouseScrollingEvent event) {
        if (mode == Mode.NONE || Minecraft.getInstance().screen != null) {
            return;
        }
        event.setCanceled(true);
        int step = event.getScrollDeltaY() > 0 ? 1 : event.getScrollDeltaY() < 0 ? -1 : 0;
        boolean shift = Minecraft.getInstance().options.keyShift.isDown();
        if (mode == Mode.BLUEPRINT) {
            if (shift) {
                lift = Math.max(-8, Math.min(8, lift + step));
            } else {
                rotation = Math.floorMod(rotation + step, 4);
            }
            locked = null;
        } else {
            gateOffset = Math.max(-16, Math.min(16, gateOffset + step));
        }
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (mode == Mode.NONE || Minecraft.getInstance().screen != null
            || event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        if (event.getKey() == GLFW.GLFW_KEY_M && mode == Mode.BLUEPRINT) {
            mirror = !mirror;
            locked = null;
        } else if (event.getKey() == GLFW.GLFW_KEY_G && mode == Mode.LINE) {
            gate = !gate;
        }
    }

    @SubscribeEvent
    public static void onScreenOpening(ScreenEvent.Opening event) {
        if (mode != Mode.NONE && event.getNewScreen() instanceof PauseScreen) {
            event.setCanceled(true);
            cancel();
            hint("hearthstead.builder.ui.hint.cancelled");
        }
    }

    @SubscribeEvent
    public static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        cancel();
        BuildSitesClient.clear();
    }

    // ------------------------------------------------------------- render ---

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mode == Mode.NONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        if (mode == Mode.BLUEPRINT) {
            renderBlueprint(mc, pose, cam, buffers);
        } else {
            renderLine(mc, pose, cam, buffers);
        }
    }

    private static void renderBlueprint(Minecraft mc, PoseStack pose, Vec3 cam,
                                        MultiBufferSource.BufferSource buffers) {
        BlockPos origin = origin(mc);
        if (origin == null || preview == null) {
            return;
        }
        BlueprintTransform t = transform();
        Rotation rot = Rotation.values()[t.rotation()];
        Mirror mir = mirror ? Mirror.FRONT_BACK : Mirror.NONE;
        var dispatcher = mc.getBlockRenderer();
        MultiBufferSource ghost = ghostBuffers(buffers, 0.45F);
        int[] cells = preview.cells();
        int drawn = 0;
        for (int i = 0; i + 1 < cells.length && drawn < 4096; i += 2) {
            int packed = cells[i];
            int x = packed & 0xFF;
            int y = (packed >> 8) & 0xFF;
            int z = (packed >> 16) & 0xFF;
            int s = cells[i + 1];
            if (s < 0 || s >= preview.palette().size()) {
                continue;
            }
            BlockState state = preview.palette().get(s).mirror(mir).rotate(rot);
            BlockPos world = origin.offset(t.x(x, z), y, t.z(x, z));
            pose.pushPose();
            pose.translate(world.getX() - cam.x, world.getY() - cam.y, world.getZ() - cam.z);
            dispatcher.renderSingleBlock(state, pose, ghost, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
            pose.popPose();
            drawn++;
        }
        buffers.endBatch(RenderType.translucent());
        AABB box = new AABB(origin.getX(), origin.getY(), origin.getZ(),
            origin.getX() + t.rotatedSizeX(), origin.getY() + preview.sizeY(), origin.getZ() + t.rotatedSizeZ())
            .move(-cam.x, -cam.y, -cam.z).inflate(0.01);
        boolean pending = locked != null;
        LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()), box,
            pending ? 0.95F : 0.96F, pending ? 0.80F : 0.88F, pending ? 0.30F : 0.50F, 1.0F);
        renderPlayerBlocks(pose, cam, buffers);
        buffers.endBatch(RenderType.lines());
    }

    private static void renderLine(Minecraft mc, PoseStack pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        BlockPos a = lineA;
        BlockPos b = target(mc);
        if (a == null) {
            if (b != null) {
                LevelRenderer.renderLineBox(pose, buffers.getBuffer(RenderType.lines()),
                    new AABB(b).move(-cam.x, -cam.y, -cam.z).inflate(0.01), 0.96F, 0.88F, 0.5F, 1.0F);
                buffers.endBatch(RenderType.lines());
            }
            return;
        }
        if (b == null) {
            b = lineB == null ? a : lineB;
        }
        boolean barricade = "barricade".equals(lineKind);
        DefenseLinePlanner.Line line = DefenseLinePlanner.plan(a.getX(), a.getZ(), b.getX(), b.getZ(),
            gate && !barricade, gateOffset, barricade ? 0 : 4);
        int height = "stone".equals(lineKind) ? 4 : barricade ? 2 : 4;
        var dispatcher = mc.getBlockRenderer();
        MultiBufferSource ghost = ghostBuffers(buffers, 0.45F);
        List<DefenseLinePlanner.Column> columns = new ArrayList<>(line.columns());
        if (barricade && columns.size() > 5) {
            columns = columns.subList(0, 5);
        }
        for (DefenseLinePlanner.Column col : columns) {
            int ground = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, col.x(), col.z());
            BlockState block = col.gate() ? Blocks.SPRUCE_FENCE_GATE.defaultBlockState()
                : "stone".equals(lineKind) ? Blocks.STONE_BRICKS.defaultBlockState()
                : barricade ? Blocks.OAK_FENCE.defaultBlockState() : Blocks.SPRUCE_LOG.defaultBlockState();
            int h = col.gate() ? 1 : height;
            for (int y = 0; y < h; y++) {
                pose.pushPose();
                pose.translate(col.x() - cam.x, ground + y - cam.y, col.z() - cam.z);
                dispatcher.renderSingleBlock(block, pose, ghost, LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                pose.popPose();
            }
        }
        buffers.endBatch(RenderType.translucent());
        renderPlayerBlocks(pose, cam, buffers);
        buffers.endBatch(RenderType.lines());
    }

    /** Red boxes on the player blocks the last validation found in the way. */
    private static void renderPlayerBlocks(PoseStack pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        BuilderPayloads.Validation v = BuilderClientState.validation();
        if (v == null || locked == null && lineB == null) {
            return;
        }
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        for (BlockPos pos : v.playerBlocks()) {
            LevelRenderer.renderLineBox(pose, lines, new AABB(pos).move(-cam.x, -cam.y, -cam.z).inflate(0.02),
                0.85F, 0.2F, 0.15F, 1.0F);
        }
    }

    /**
     * Forces every block quad into the translucent pass with its alpha
     * scaled, so the ghost reads as a plan, not as built blocks.
     */
    static MultiBufferSource ghostBuffers(MultiBufferSource.BufferSource buffers, float alpha) {
        return type -> new AlphaConsumer(buffers.getBuffer(RenderType.translucent()), alpha);
    }

    private record AlphaConsumer(VertexConsumer inner, float alpha) implements VertexConsumer {
        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            inner.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            inner.setColor(r, g, b, (int) (a * alpha));
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            inner.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            inner.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            inner.setUv2(u, v);
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            inner.setNormal(x, y, z);
            return this;
        }
    }
}
