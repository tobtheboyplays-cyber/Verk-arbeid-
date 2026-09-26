package com.hearthstead.client.render;

import com.hearthstead.client.model.CarryPackRules;
import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Draws each job's back container (CarryPackRules) in the carried sack's
 * frame -- a few boxes on vanilla block textures, tinted per job -- plus up
 * to three real item models poking out of it as the bag fills (wheat in the
 * farmer's basket, a cod tail from the creel, ore lumps in the miner's hod).
 * Presentation only: the contents are the job's typical goods, not a read of
 * the server-side bag, whose fill alone is synced.
 *
 * <p>Geometry frame (model px): pivot at the top-back of the torso, +y down,
 * +z away from the back, x sideways; the same frame as the courier sack mesh
 * and the placed ground sack, so the job look follows the fill scale, the
 * footfall bounce and the ground put-down with no second transform.
 */
public final class CarryPackLayer extends RenderLayer<SettlerEntity, SettlerModel> {
    private static final Map<CarryPackRules.Material, ResourceLocation> TEXTURES =
        new EnumMap<>(CarryPackRules.Material.class);
    static {
        TEXTURES.put(CarryPackRules.Material.WOOL, tex("white_wool"));
        TEXTURES.put(CarryPackRules.Material.LEATHER, tex("white_wool"));
        TEXTURES.put(CarryPackRules.Material.WICKER, tex("hay_block_side"));
        TEXTURES.put(CarryPackRules.Material.STRAW, tex("hay_block_side"));
        TEXTURES.put(CarryPackRules.Material.PLANKS, tex("spruce_planks"));
        TEXTURES.put(CarryPackRules.Material.BARREL, tex("barrel_side"));
        TEXTURES.put(CarryPackRules.Material.LOG, tex("stripped_oak_log"));
    }
    private static final ResourceLocation STRAP = tex("brown_wool");
    private static final int STRAP_TINT = 0xFF6A4A32;
    /** Beyond this camera distance the content items are skipped (the container still draws). */
    private static final double CONTENTS_LOD_SQ = 24.0 * 24.0;
    /** Content slots (px, pack frame) for the soft sack (above the neck) and open containers (in the rim). */
    private static final float[][] SACK_SLOTS = {{-1.2F, -0.4F, 2.8F, 20F}, {1.3F, -0.7F, 3.4F, -35F},
        {0.0F, -1.0F, 2.2F, 80F}};
    private static final float[][] OPEN_SLOTS = {{-1.3F, 1.0F, 2.4F, 20F}, {1.3F, 0.7F, 3.6F, -35F},
        {0.0F, 0.2F, 3.0F, 80F}};
    private static final float[][] FRAME_SLOTS = {{0.0F, 6.2F, 2.6F, 0F}, {0.0F, 3.4F, 2.6F, 8F},
        {0.0F, 0.6F, 2.6F, -6F}};

    private static final Map<String, ItemStack> STACKS = new HashMap<>();

    private final ItemInHandRenderer items;

    public CarryPackLayer(RenderLayerParent<SettlerEntity, SettlerModel> parent, ItemInHandRenderer items) {
        super(parent);
        this.items = items;
    }

    private static ResourceLocation tex(String block) {
        return ResourceLocation.withDefaultNamespace("textures/block/" + block + ".png");
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, SettlerEntity entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (entity.isInvisible()) {
            return;
        }
        SettlerModel model = getParentModel();
        boolean ground = model.packOnGround();
        CarryPackRules.Look look = model.packLook();
        CarryPackRules.Style style;
        CarryPackRules.Shape shape;
        int tier;
        if (ground) {
            style = CarryPackRules.styleFor(entity.getProfession());
            shape = style.shape();
            tier = CarryPackRules.contentTier(entity.visualCarryFraction());
        } else {
            if (!look.visible()) {
                return;
            }
            style = look.style();
            shape = look.shape();
            tier = look.tier();
        }
        pose.pushPose();
        model.translateToPack(pose, ground);
        pose.pushPose();
        pose.scale(1.0F / 16.0F, 1.0F / 16.0F, 1.0F / 16.0F);
        VertexConsumer body = buffers.getBuffer(RenderType.entityCutoutNoCull(TEXTURES.get(style.material())));
        int tint = style.tint();
        switch (shape) {
            case SATCHEL -> {
                // Empty and idle: a flat leather satchel, whatever the job.
                VertexConsumer leather = buffers.getBuffer(RenderType.entityCutoutNoCull(STRAP));
                box(pose, leather, -2.5F, 2.5F, 0.0F, 2.5F, 7.0F, 1.5F, STRAP_TINT, light);
                box(pose, leather, -2.6F, 2.3F, 1.3F, 2.6F, 4.6F, 1.9F, 0xFF7A5A3E, light);
            }
            case SACK -> {
                box(pose, body, -2.5F, 0.0F, 1.0F, 2.5F, 3.0F, 5.0F, tint, light);
                box(pose, body, -3.5F, 2.0F, 0.0F, 3.5F, 8.0F, 6.0F, tint, light);
                VertexConsumer cord = buffers.getBuffer(RenderType.entityCutoutNoCull(STRAP));
                box(pose, cord, -2.6F, 1.0F, 0.9F, 2.6F, 1.6F, 5.1F, STRAP_TINT, light);
            }
            case BASKET, CRATE -> {
                box(pose, body, -3.0F, 7.4F, 0.0F, 3.0F, 8.0F, 6.0F, tint, light);   // floor
                box(pose, body, -3.0F, 1.0F, 0.0F, 3.0F, 8.0F, 0.6F, tint, light);   // back-side wall
                box(pose, body, -3.0F, 1.0F, 5.4F, 3.0F, 8.0F, 6.0F, tint, light);   // outer wall
                box(pose, body, -3.0F, 1.0F, 0.6F, -2.4F, 8.0F, 5.4F, tint, light);  // left
                box(pose, body, 2.4F, 1.0F, 0.6F, 3.0F, 8.0F, 5.4F, tint, light);    // right
                VertexConsumer strap = buffers.getBuffer(RenderType.entityCutoutNoCull(STRAP));
                box(pose, strap, -2.2F, -1.0F, -0.3F, -1.4F, 6.0F, 0.1F, STRAP_TINT, light);
                box(pose, strap, 1.4F, -1.0F, -0.3F, 2.2F, 6.0F, 0.1F, STRAP_TINT, light);
            }
            case BUNDLE -> {
                // One strapped roll per content tier (hides, cloth bolts).
                int rolls = Math.max(1, tier);
                for (int r = 0; r < rolls; r++) {
                    float y0 = 5.0F - r * 3.0F;
                    int shade = r % 2 == 0 ? tint : darken(tint);
                    box(pose, body, -3.5F, y0, 0.0F, 3.5F, y0 + 2.8F, 3.2F, shade, light);
                }
                VertexConsumer strap = buffers.getBuffer(RenderType.entityCutoutNoCull(STRAP));
                float top = 5.0F - (rolls - 1) * 3.0F - 0.2F;
                box(pose, strap, -2.4F, top, -0.1F, -1.8F, 8.0F, 3.4F, STRAP_TINT, light);
                box(pose, strap, 1.8F, top, -0.1F, 2.4F, 8.0F, 3.4F, STRAP_TINT, light);
            }
            case FRAME -> {
                box(pose, body, -3.5F, -1.0F, 0.0F, -2.5F, 9.0F, 1.0F, tint, light);
                box(pose, body, 2.5F, -1.0F, 0.0F, 3.5F, 9.0F, 1.0F, tint, light);
                box(pose, body, -3.0F, 1.0F, 0.0F, 3.0F, 2.0F, 1.0F, tint, light);
                box(pose, body, -3.0F, 8.2F, 0.0F, 3.0F, 9.0F, 4.6F, tint, light);
            }
            default -> {
            }
        }
        pose.popPose();

        // Contents: the job's goods show one more item per fill tier.
        if (tier > 0 && shape != CarryPackRules.Shape.BUNDLE && shape != CarryPackRules.Shape.SATCHEL
            && closeToCamera(entity)) {
            float[][] slots = shape == CarryPackRules.Shape.SACK ? SACK_SLOTS
                : shape == CarryPackRules.Shape.FRAME ? FRAME_SLOTS : OPEN_SLOTS;
            String[] ids = style.contents();
            for (int i = 0; i < Math.min(tier, Math.min(ids.length, slots.length)); i++) {
                ItemStack stack = stackOf(ids[i]);
                if (stack.isEmpty()) {
                    continue;
                }
                float[] slot = slots[i];
                pose.pushPose();
                pose.translate(slot[0] / 16.0F, slot[1] / 16.0F, slot[2] / 16.0F);
                pose.mulPose(Axis.YP.rotationDegrees(slot[3]));
                boolean block = stack.getItem() instanceof BlockItem;
                if (shape == CarryPackRules.Shape.FRAME) {
                    // Lashed flat in the cradle: a wide, shallow stack.
                    pose.scale(1.1F, 0.55F, 0.7F);
                } else if (block) {
                    pose.scale(0.55F, 0.55F, 0.55F);
                } else {
                    // Sprites stand up out of the mouth, tilted like a fish tail or a sheaf.
                    pose.mulPose(Axis.ZP.rotationDegrees(i == 0 ? 25.0F : -15.0F));
                    pose.scale(0.34F, 0.34F, 0.34F);
                }
                items.renderItem(entity, stack, ItemDisplayContext.FIXED, false, pose, buffers, light);
                pose.popPose();
            }
        }
        pose.popPose();
    }

    private static boolean closeToCamera(SettlerEntity entity) {
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        return camera == null || !camera.isInitialized()
            || camera.getPosition().distanceToSqr(entity.getX(), entity.getY(), entity.getZ()) < CONTENTS_LOD_SQ;
    }

    private static ItemStack stackOf(String id) {
        ItemStack cached = STACKS.get(id);
        if (cached == null) {
            var item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            cached = new ItemStack(item);
            STACKS.put(id, cached);
        }
        return cached;
    }

    private static int darken(int argb) {
        int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
        return (argb & 0xFF000000) | ((r * 4 / 5) << 16) | ((g * 4 / 5) << 8) | (b * 4 / 5);
    }

    /** Axis-aligned box in pack px; each face samples the texture 1:1 (16 px texture = 16 px face). */
    private static void box(PoseStack pose, VertexConsumer vc, float x0, float y0, float z0,
                            float x1, float y1, float z1, int argb, int light) {
        PoseStack.Pose last = pose.last();
        // -X / +X
        quad(vc, last, argb, light, -1, 0, 0, x0, y0, z1, x0, y0, z0, x0, y1, z0, x0, y1, z1, z1 - z0, y1 - y0);
        quad(vc, last, argb, light, 1, 0, 0, x1, y0, z0, x1, y0, z1, x1, y1, z1, x1, y1, z0, z1 - z0, y1 - y0);
        // -Y (top in model space) / +Y (bottom)
        quad(vc, last, argb, light, 0, -1, 0, x0, y0, z1, x1, y0, z1, x1, y0, z0, x0, y0, z0, x1 - x0, z1 - z0);
        quad(vc, last, argb, light, 0, 1, 0, x0, y1, z0, x1, y1, z0, x1, y1, z1, x0, y1, z1, x1 - x0, z1 - z0);
        // -Z / +Z
        quad(vc, last, argb, light, 0, 0, -1, x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, x1 - x0, y1 - y0);
        quad(vc, last, argb, light, 0, 0, 1, x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, x1 - x0, y1 - y0);
    }

    private static void quad(VertexConsumer vc, PoseStack.Pose last, int argb, int light,
                             float nx, float ny, float nz,
                             float ax, float ay, float az, float bx, float by, float bz,
                             float cx, float cy, float cz, float dx, float dy, float dz,
                             float w, float h) {
        float u = Math.min(1.0F, w / 16.0F);
        float v = Math.min(1.0F, h / 16.0F);
        vertex(vc, last, ax, ay, az, 0.0F, 0.0F, argb, light, nx, ny, nz);
        vertex(vc, last, bx, by, bz, u, 0.0F, argb, light, nx, ny, nz);
        vertex(vc, last, cx, cy, cz, u, v, argb, light, nx, ny, nz);
        vertex(vc, last, dx, dy, dz, 0.0F, v, argb, light, nx, ny, nz);
    }

    private static void vertex(VertexConsumer vc, PoseStack.Pose last, float x, float y, float z,
                               float u, float v, int argb, int light, float nx, float ny, float nz) {
        vc.addVertex(last, x, y, z).setColor(argb).setUv(u, v)
            .setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(last, nx, ny, nz);
    }
}
