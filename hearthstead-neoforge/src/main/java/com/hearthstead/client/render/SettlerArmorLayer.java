package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.settlement.gear.GearTiers;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterial;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * Bannerhold's own armour look on settlers. Vanilla armour ITEMS are worn
 * (and protect) exactly as usual; only their look on a settler is ours:
 * quilted gambeson for leather, mail hauberk and coif for chain, plate over
 * mail with a nasal kettle helm for iron, gilded plate for gold, blued plate
 * for diamond and blackened knightly plate for netherite. Players keep the
 * vanilla look; this layer only exists on {@link SettlerRenderer}.
 *
 * <p>The settler rig is bespoke, so vanilla's {@code HumanoidArmorLayer}
 * cannot pose armour over it. Instead each (material, slot) pair has a
 * 128x64 overlay on the settler's own UV table ({@code tools/gen_gear_armor.py})
 * and this layer re-renders the already-posed parent model with it:
 * transparent texels are discarded by the cutout render type, opaque ones
 * land at depth identical to the skin's own fragments and overwrite them.
 * Rendering goes through {@code renderToBuffer}, so the motion engine's
 * bending limb meshes carry the armour for free.
 *
 * <p><b>Heraldry.</b> Guards and archers also wear a surcoat in the realm's
 * colours: a greyscale tabard tinted with the Banner's field colour and a
 * trim sheet tinted with its first charge colour, both read from the
 * settler's synced heraldry projection ({@code GearGate#heraldryPacked}).
 *
 * <p>The worn items are synced to every watcher, so what is drawn can never
 * disagree with what the settler actually wears; a slot with nothing in it
 * costs nothing.
 */
public class SettlerArmorLayer extends RenderLayer<SettlerEntity, SettlerModel> {
    private static final String[] MATERIAL_BY_TIER = {
        "leather", "chain", "iron", "diamond", "netherite",
    };
    private static final Map<String, ResourceLocation> SHEETS = new HashMap<>();
    private static final ResourceLocation TABARD_FIELD =
        Hearthstead.id("textures/entity/settler/gear/tabard_field.png");
    private static final ResourceLocation TABARD_TRIM =
        Hearthstead.id("textures/entity/settler/gear/tabard_trim.png");
    /** Bannerhold's own colours when the Banner is not known yet. */
    private static final DyeColor DEFAULT_FIELD = DyeColor.RED;
    private static final DyeColor DEFAULT_TRIM = DyeColor.YELLOW;

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
        if (!com.hearthstead.settlement.gear.GearGate.enabled()) {
            // Kill switch ([features] gearTiers=false): the pre-tier look.
            ResourceLocation legacy = legacyOverlay(entity);
            if (legacy != null) {
                renderColoredCutoutModel(getParentModel(), legacy, poseStack, buffers,
                    packedLight, entity, -1);
            }
            // Village colours are not a gear tier: guards wear them either way.
            drawTabard(entity, poseStack, buffers, packedLight);
            return;
        }
        // The parent model was posed by MobRenderer.render just before the
        // layers run, so rendering it again reuses the exact frame pose.
        // Order matters at equal depth: later passes overwrite earlier ones.
        draw(entity, EquipmentSlot.FEET, "feet", poseStack, buffers, packedLight);
        draw(entity, EquipmentSlot.LEGS, "legs", poseStack, buffers, packedLight);
        draw(entity, EquipmentSlot.CHEST, "chest", poseStack, buffers, packedLight);
        drawTabard(entity, poseStack, buffers, packedLight);
        draw(entity, EquipmentSlot.HEAD, "head", poseStack, buffers, packedLight);
    }

    /** Guards' and archers' surcoat in the village colours (field + trim). */
    private void drawTabard(SettlerEntity entity, PoseStack poseStack, MultiBufferSource buffers,
                            int packedLight) {
        if (com.hearthstead.settlement.gear.GearGate.roleOf(entity.getProfession())
                == com.hearthstead.settlement.gear.GearTier.Role.WORKER) {
            return;
        }
        int heraldry = entity.heraldryPacked();
        DyeColor field = heraldry == 0 ? DEFAULT_FIELD : DyeColor.byId(heraldry & 0xF);
        DyeColor trim = heraldry == 0 ? DEFAULT_TRIM : DyeColor.byId((heraldry >> 4) & 0xF);
        renderColoredCutoutModel(getParentModel(), TABARD_FIELD, poseStack, buffers,
            packedLight, entity, 0xFF000000 | field.getTextureDiffuseColor());
        renderColoredCutoutModel(getParentModel(), TABARD_TRIM, poseStack, buffers,
            packedLight, entity, 0xFF000000 | trim.getTextureDiffuseColor());
    }

    private void draw(SettlerEntity entity, EquipmentSlot slot, String slotKey,
                      PoseStack poseStack, MultiBufferSource buffers, int packedLight) {
        ItemStack worn = entity.getItemBySlot(slot);
        String material = materialOf(worn);
        if (material == null) {
            return;
        }
        renderColoredCutoutModel(getParentModel(), sheet(material, slotKey), poseStack,
            buffers, packedLight, entity, -1);
    }

    private static ResourceLocation sheet(String material, String slot) {
        return SHEETS.computeIfAbsent(material + "_" + slot, key ->
            Hearthstead.id("textures/entity/settler/gear/" + key + ".png"));
    }

    /** Look family of one worn piece, or null for nothing drawable. */
    @Nullable
    static String materialOf(ItemStack worn) {
        if (worn.isEmpty() || !(worn.getItem() instanceof ArmorItem armor)) {
            return null;
        }
        Holder<ArmorMaterial> m = armor.getMaterial();
        if (same(m, ArmorMaterials.LEATHER)) {
            return "leather";
        }
        if (same(m, ArmorMaterials.CHAIN) || same(m, ArmorMaterials.TURTLE)) {
            return "chain";
        }
        if (same(m, ArmorMaterials.IRON)) {
            return "iron";
        }
        if (same(m, ArmorMaterials.GOLD)) {
            return "gold";
        }
        if (same(m, ArmorMaterials.DIAMOND)) {
            return "diamond";
        }
        if (same(m, ArmorMaterials.NETHERITE)) {
            return "netherite";
        }
        // Modded armour: dressed by its Gear Tier.
        return MATERIAL_BY_TIER[Math.max(0, Math.min(4, GearTiers.tierOf(worn)))];
    }

    private static final ResourceLocation LEGACY_SPEARMAN =
        Hearthstead.id("textures/entity/settler/armor_leather.png");
    private static final ResourceLocation LEGACY_VETERAN =
        Hearthstead.id("textures/entity/settler/armor_iron_trim.png");
    private static final ResourceLocation LEGACY_SERGEANT =
        Hearthstead.id("textures/entity/settler/armor_iron.png");
    private static final ResourceLocation LEGACY_CAPTAIN =
        Hearthstead.id("textures/entity/settler/armor_captain.png");

    /** The pre-tier rank overlay (tools/gen_armor.py), read off chest + helmet. */
    @Nullable
    private static ResourceLocation legacyOverlay(SettlerEntity entity) {
        ItemStack chest = entity.getItemBySlot(EquipmentSlot.CHEST);
        ItemStack head = entity.getItemBySlot(EquipmentSlot.HEAD);
        if (chest.is(net.minecraft.world.item.Items.IRON_CHESTPLATE)) {
            return head.is(net.minecraft.world.item.Items.IRON_HELMET) ? LEGACY_CAPTAIN : LEGACY_SERGEANT;
        }
        if (chest.is(net.minecraft.world.item.Items.LEATHER_CHESTPLATE)) {
            return head.is(net.minecraft.world.item.Items.LEATHER_HELMET) ? LEGACY_VETERAN : LEGACY_SPEARMAN;
        }
        return null;
    }

    private static boolean same(Holder<ArmorMaterial> a, Holder<ArmorMaterial> b) {
        return a == b || (a.unwrapKey().isPresent() && a.unwrapKey().equals(b.unwrapKey()));
    }
}
