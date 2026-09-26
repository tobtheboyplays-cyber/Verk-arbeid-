package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws the Courier's Hand Cart (the {@code hand_cart} upgrade) from vanilla
 * block models: a plank bed with boards, two spoked log wheels, two shafts
 * and a load of crates that follows the real bag fill.
 *
 * <p>Presentation only. The cart is not an entity and holds nothing: the
 * goods are the Courier's own bag (see {@code HaulGear}), so the cart can
 * never duplicate, strand or lose an item, and it has no collision, so it
 * never blocks a route. While the Courier walks a delivery outdoors the cart
 * is hitched behind them ({@link #isPulling}); when they step under a roof or
 * work at a chest, the cart stays parked at the last outdoor spot and the
 * last metres are carried by hand. Drawn from {@link RenderLivingEvent.Post}
 * so {@code SettlerRenderer} is untouched.
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class HandCartRenderer {
    /** Cart centre distance behind the Courier's body centre, in blocks. */
    private static final float CART_BACK = 1.45F;
    private static final float WHEEL_RADIUS = 0.30F;
    /** A parked cart further than this from its Courier is "in the shed". */
    private static final double PARK_MAX_DISTANCE_SQR = 24.0D * 24.0D;
    private static final int MAX_PARKED = 256;

    private static final BlockState PLANKS = Blocks.OAK_PLANKS.defaultBlockState();
    /** Coster's Cart / Mule Cart bed: darker, heavier timber. */
    private static final BlockState HEAVY_PLANKS = Blocks.DARK_OAK_PLANKS.defaultBlockState();
    private static final BlockState BOARD = Blocks.SPRUCE_PLANKS.defaultBlockState();
    private static final BlockState WHEEL = Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState()
        .setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
    private static final BlockState SHAFT = Blocks.STRIPPED_OAK_LOG.defaultBlockState()
        .setValue(RotatedPillarBlock.AXIS, Direction.Axis.Z);
    private static final BlockState CRATE = Blocks.BARREL.defaultBlockState();
    private static final BlockState BALE = Blocks.HAY_BLOCK.defaultBlockState();

    /** Last hitched cart pose per Courier entity id (client memory only). */
    private static final Map<Integer, Parked> PARKED = new HashMap<>();
    private static Object world;

    private record Parked(double x, double y, double z, float yaw, float spin) {
    }

    private HandCartRenderer() {
    }

    /**
     * True while this Courier has the cart hitched: owns the Hand Cart, walks
     * a delivery or travels, is outdoors and is not mid bag-to-chest
     * transfer. The motion engine reads this for the pulling pose.
     */
    public static boolean isPulling(SettlerEntity settler) {
        if (settler == null || !settler.isAlive() || settler.isPassenger()
            || settler.getProfession() != Profession.COURIER || !settler.hasHandCart()) {
            return false;
        }
        SettlerActivity activity = settler.getActivity();
        if (activity != SettlerActivity.CARRYING && activity != SettlerActivity.TRAVELING) {
            return false;
        }
        if (settler.bagTransferPresentation().ownsBodyPose(settler)) {
            return false;
        }
        return settler.level().canSeeSky(settler.blockPosition().above());
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != world) {
            PARKED.clear();
            world = mc.level;
        }
        if (mc.level != null && mc.level.getGameTime() % 100L == 0L) {
            PARKED.keySet().removeIf(id -> {
                var entity = mc.level.getEntity(id);
                return !(entity instanceof SettlerEntity settler) || !settler.hasHandCart();
            });
        }
    }

    @SubscribeEvent
    public static void render(RenderLivingEvent.Post<?, ?> event) {
        if (!(event.getEntity() instanceof SettlerEntity settler)
            || settler.getProfession() != Profession.COURIER || !settler.hasHandCart()
            || settler.isInvisible()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || settler.level() != mc.level) {
            return;
        }
        float partial = event.getPartialTick();
        double ex = Mth.lerp(partial, settler.xo, settler.getX());
        double ey = Mth.lerp(partial, settler.yo, settler.getY());
        double ez = Mth.lerp(partial, settler.zo, settler.getZ());
        float fill = Mth.clamp(settler.visualCarryFraction(), 0.0F, 1.0F);
        BlockRenderDispatcher blocks = mc.getBlockRenderer();
        PoseStack pose = event.getPoseStack();
        MultiBufferSource buffers = event.getMultiBufferSource();

        if (isPulling(settler)) {
            float yaw = Mth.rotLerp(partial, settler.yBodyRotO, settler.yBodyRot);
            float spin = settler.walkAnimation.position(partial) * 0.55F;
            if (PARKED.size() < MAX_PARKED || PARKED.containsKey(settler.getId())) {
                Vec3 behind = cartCentre(ex, ey, ez, yaw);
                PARKED.put(settler.getId(), new Parked(behind.x, ey, behind.z, yaw, spin));
            }
            pose.pushPose();
            pose.mulPose(Axis.YP.rotationDegrees(-yaw));
            pose.translate(0.0D, 0.0D, -CART_BACK);
            drawCart(blocks, pose, buffers, event.getPackedLight(), fill, spin, true,
                settler.cartTier());
            pose.popPose();
            return;
        }
        Parked parked = PARKED.get(settler.getId());
        if (parked == null) {
            return;
        }
        double dx = parked.x - ex;
        double dy = parked.y - ey;
        double dz = parked.z - ez;
        if (dx * dx + dy * dy + dz * dz > PARK_MAX_DISTANCE_SQR) {
            return;
        }
        int light = LevelRenderer.getLightColor(mc.level,
            BlockPos.containing(parked.x, parked.y + 0.5D, parked.z));
        pose.pushPose();
        pose.translate(dx, dy, dz);
        pose.mulPose(Axis.YP.rotationDegrees(-parked.yaw));
        drawCart(blocks, pose, buffers, light, fill, parked.spin, false, settler.cartTier());
        pose.popPose();
    }

    private static Vec3 cartCentre(double x, double y, double z, float yaw) {
        double rad = Math.toRadians(yaw);
        // Local +Z (forward) in world space is (-sin yaw, cos yaw).
        return new Vec3(x + Math.sin(rad) * CART_BACK, y, z - Math.cos(rad) * CART_BACK);
    }

    /**
     * One cart around its own centre on the ground; local +Z points at the
     * Courier. {@code hitched} lifts the shafts to the hands, a parked cart
     * rests them on the ground. {@code cartTier} 2 (Coster's Cart) and 3
     * (Mule Cart) draw a dark-timber bed, higher sides and a taller load.
     */
    private static void drawCart(BlockRenderDispatcher blocks, PoseStack pose,
                                 MultiBufferSource buffers, int light, float fill,
                                 float spin, boolean hitched, int cartTier) {
        int tier = Mth.clamp(cartTier, 1, 3);
        float sides = 0.22F + 0.12F * (tier - 1);
        // Bed and boards.
        box(blocks, pose, buffers, light, tier >= 2 ? HEAVY_PLANKS : PLANKS,
            -0.45F, 0.40F, -0.50F, 0.90F, 0.10F, 1.00F);
        box(blocks, pose, buffers, light, BOARD, -0.47F, 0.50F, -0.50F, 0.06F, sides, 1.00F);
        box(blocks, pose, buffers, light, BOARD, 0.41F, 0.50F, -0.50F, 0.06F, sides, 1.00F);
        box(blocks, pose, buffers, light, BOARD, -0.41F, 0.50F, -0.52F, 0.82F, sides, 0.06F);
        box(blocks, pose, buffers, light, BOARD, -0.41F, 0.50F, 0.46F, 0.82F, 0.16F, 0.06F);
        // Axle and two wheels: two crossed squares read as an eight-spoke wheel.
        box(blocks, pose, buffers, light, SHAFT, -0.55F, WHEEL_RADIUS - 0.03F, -0.03F,
            1.10F, 0.06F, 0.06F);
        wheel(blocks, pose, buffers, light, -0.58F, spin);
        wheel(blocks, pose, buffers, light, 0.50F, spin);
        // Shafts from the front board to the Courier's hands (or the ground).
        float tipY = hitched ? 0.78F : 0.05F;
        float tipZ = CART_BACK - 0.45F;
        shaft(blocks, pose, buffers, light, -0.34F, tipY, tipZ);
        shaft(blocks, pose, buffers, light, 0.28F, tipY, tipZ);
        // Load: crates and bales following the real bag fill, one layer of
        // four per cart tier (a full Mule Cart is piled three high).
        int capacity = 4 * tier;
        int pieces = fill <= 0.001F ? 0 : Mth.clamp(Mth.ceil(fill * capacity), 1, capacity);
        float[][] slots = {{-0.38F, -0.44F}, {0.02F, 0.02F}, {-0.38F, 0.02F}, {0.02F, -0.44F}};
        for (int i = 0; i < pieces; i++) {
            int layer = i / 4;
            int k = i % 4;
            box(blocks, pose, buffers, light, ((i + layer) & 1) == 0 ? CRATE : BALE,
                slots[k][0], 0.50F + 0.36F * layer, slots[k][1], 0.36F, 0.36F, 0.40F);
        }
    }

    private static void wheel(BlockRenderDispatcher blocks, PoseStack pose,
                              MultiBufferSource buffers, int light, float x, float spin) {
        float d = WHEEL_RADIUS * 2.0F;
        float side = d / (float) Math.sqrt(2.0D) * 1.08F;
        for (int k = 0; k < 2; k++) {
            pose.pushPose();
            pose.translate(x, WHEEL_RADIUS, 0.0F);
            pose.mulPose(Axis.XP.rotation(spin + k * Mth.HALF_PI * 0.5F));
            pose.translate(0.0F, -side / 2.0F, -side / 2.0F);
            pose.scale(0.08F, side, side);
            blocks.renderSingleBlock(WHEEL, pose, buffers, light, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }
    }

    private static void shaft(BlockRenderDispatcher blocks, PoseStack pose,
                              MultiBufferSource buffers, int light, float x,
                              float tipY, float tipZ) {
        float startY = 0.50F;
        float startZ = 0.40F;
        float dy = tipY - startY;
        float dz = tipZ - startZ;
        float length = (float) Math.sqrt(dy * dy + dz * dz);
        pose.pushPose();
        pose.translate(x, startY, startZ);
        pose.mulPose(Axis.XP.rotation((float) -Math.atan2(dy, dz)));
        pose.scale(0.06F, 0.06F, length);
        blocks.renderSingleBlock(SHAFT, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    private static void box(BlockRenderDispatcher blocks, PoseStack pose,
                            MultiBufferSource buffers, int light, BlockState state,
                            float x, float y, float z, float sx, float sy, float sz) {
        pose.pushPose();
        pose.translate(x, y, z);
        pose.scale(sx, sy, sz);
        blocks.renderSingleBlock(state, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }
}
