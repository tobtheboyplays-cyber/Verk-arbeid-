package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import javax.annotation.Nullable;

/**
 * Makes a guard's earned armor visible. {@link GuardRank#applyEquipment}
 * dresses guards in real gear server-side, but the settler rig is bespoke —
 * its bones do not map onto {@code HumanoidModel}, so vanilla's
 * {@code HumanoidArmorLayer} cannot pose armor over it. Instead each visible
 * rank has a 128x64 overlay sheet on the settler's own UV table
 * ({@code tools/gen_armor.py}), and this layer re-renders the already-posed
 * parent model with that sheet: transparent texels are discarded by the
 * cutout render type, opaque ones land at depth identical to the skin's own
 * fragments and overwrite them. No second geometry, no z-fighting — the
 * whole "SEEING the veteran on the wall" payoff for one extra draw.
 *
 * <p><b>The tier is derived from the synced equipment, not recomputed.</b>
 * Rank lives in {@code Attribute.STRENGTH}, which the client cannot see —
 * but the armor items themselves are synced to every watcher, and
 * {@link GuardRank#applyEquipment} gives each rank a unique (chest, helmet)
 * pair: no chestplate = RECRUIT (bare), leather chest alone = SPEARMAN,
 * leather chest + leather helmet = VETERAN, iron chest + leather helmet =
 * SERGEANT, iron chest + iron helmet = CAPTAIN. Reading the worn items means
 * the overlay can never disagree with what the settler is actually wearing
 * (chest truth, rendered), and settlers who are not guards — four empty
 * slots — get no overlay for free.
 */
public class SettlerArmorLayer extends RenderLayer<SettlerEntity, SettlerModel> {
    private static final ResourceLocation SPEARMAN =
        Hearthstead.id("textures/entity/settler/armor_leather.png");
    private static final ResourceLocation VETERAN =
        Hearthstead.id("textures/entity/settler/armor_iron_trim.png");
    private static final ResourceLocation SERGEANT =
        Hearthstead.id("textures/entity/settler/armor_iron.png");
    private static final ResourceLocation CAPTAIN =
        Hearthstead.id("textures/entity/settler/armor_captain.png");

    public SettlerArmorLayer(RenderLayerParent<SettlerEntity, SettlerModel> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack poseStack, MultiBufferSource buffers, int packedLight,
                       SettlerEntity entity, float limbSwing, float limbSwingAmount,
                       float partialTick, float ageInTicks, float netHeadYaw,
                       float headPitch) {
        if (entity.isInvisible()) {
            return;
        }
        ResourceLocation overlay = overlayFor(entity);
        if (overlay == null) {
            return;
        }
        // The parent model was posed by MobRenderer.render just before the
        // layers run, so rendering it again reuses the exact frame pose.
        renderColoredCutoutModel(getParentModel(), overlay, poseStack, buffers,
            packedLight, entity, -1);
    }

    /** The overlay this settler's worn armor has earned, or null for bare
     *  (RECRUIT, or not a guard at all). Mirrors the kit table in
     *  {@link GuardRank#applyEquipment} — chest decides the material tier,
     *  the helmet splits it. */
    @Nullable
    private static ResourceLocation overlayFor(SettlerEntity entity) {
        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        if (chest.is(Items.IRON_CHESTPLATE)) {
            ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
            return head.is(Items.IRON_HELMET) ? CAPTAIN : SERGEANT;
        }
        if (chest.is(Items.LEATHER_CHESTPLATE)) {
            ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
            return head.is(Items.LEATHER_HELMET) ? VETERAN : SPEARMAN;
        }
        return null;
    }
}
