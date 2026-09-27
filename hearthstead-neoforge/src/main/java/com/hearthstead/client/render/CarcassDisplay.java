package com.hearthstead.client.render;

import com.hearthstead.item.CarcassData;
import com.hearthstead.item.CarcassItem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a carcass as its real species' vanilla model lying on its side --
 * across a Hunter's shoulders, on a Butchering Table, or on the Lodge floor.
 * Presentation only: one cached, never-ticked client dummy per species.
 */
public final class CarcassDisplay {
    private static final Map<EntityType<?>, Entity> DUMMIES = new HashMap<>();
    private static Level dummyLevel;

    private CarcassDisplay() {
    }

    /** Model scale so every species reads as a carried body of similar size. */
    public static float scaleFor(EntityType<?> type) {
        if (type == EntityType.COW) return 0.5F;
        if (type == EntityType.PIG) return 0.6F;
        if (type == EntityType.SHEEP) return 0.55F;
        if (type == EntityType.CHICKEN) return 0.9F;
        if (type == EntityType.RABBIT) return 1.0F;
        return 0.5F;
    }

    /**
     * Renders the carcass centred on the current pose origin, its body along
     * the pose's X axis and lying on its side. The pose must already be in
     * world orientation (Y up).
     */
    public static void render(ItemStack stack, PoseStack pose, MultiBufferSource buffers, int light) {
        CarcassData data = CarcassItem.data(stack);
        EntityType<?> type = data == null ? null : data.type().orElse(null);
        Level level = Minecraft.getInstance().level;
        if (type == null || level == null) {
            return;
        }
        Entity dummy = dummy(level, type, data);
        if (dummy == null) {
            return;
        }
        EntityRenderer<? super Entity> renderer =
            Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(dummy);
        float scale = scaleFor(type);
        pose.pushPose();
        pose.mulPose(Axis.YP.rotationDegrees(90.0F));
        pose.mulPose(Axis.ZP.rotationDegrees(90.0F));
        pose.scale(scale, scale, scale);
        pose.translate(0.0D, -dummy.getBbHeight() * 0.5D, 0.0D);
        renderer.render(dummy, 0.0F, 0.0F, pose, buffers, light);
        pose.popPose();
    }

    private static Entity dummy(Level level, EntityType<?> type, CarcassData data) {
        if (level != dummyLevel) {
            DUMMIES.clear();
            dummyLevel = level;
        }
        Entity entity = DUMMIES.computeIfAbsent(type, t -> {
            Entity created = t.create(level);
            if (created instanceof LivingEntity living) {
                living.yBodyRot = living.yBodyRotO = 0.0F;
                living.yHeadRot = living.yHeadRotO = 0.0F;
                living.setXRot(0.0F);
            }
            return created;
        });
        if (entity instanceof Sheep sheep) {
            // A sheep taken in the wild still wears its fleece colour.
            DyeColor color = DyeColor.WHITE;
            for (ItemStack yield : data.yield()) {
                if (yield.is(net.minecraft.tags.ItemTags.WOOL)) {
                    String path = net.minecraft.core.registries.BuiltInRegistries.ITEM
                        .getKey(yield.getItem()).getPath();
                    color = DyeColor.byName(path.replace("_wool", ""), DyeColor.WHITE);
                }
            }
            sheep.setColor(color);
            sheep.setSheared(false);
        }
        return entity;
    }
}
