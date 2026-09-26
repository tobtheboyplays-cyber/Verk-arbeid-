package com.hearthstead.client.look;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.look.CharacterGenome;
import com.hearthstead.entity.look.CharacterLooks;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.DyeColor;

/**
 * The "3D pass": small cuboid accessories on the settler rig -- buns, tails,
 * braids, hair volume, protruding beards, nose shapes, job headwear, aprons,
 * pouches and the hero Captain's cape and plume. Parts ride the parent
 * model's own torso/head bones after setupAnim, so they follow every clip.
 * One static greyscale atlas, tinted per part (hair colour, skin tone, job
 * accent, cape dye). Skipped beyond {@link #LOD_RANGE} blocks and whenever
 * [features] characterSkins is off. Geometry mirrors tools/skins/accessories.py.
 */
public final class SettlerAccessoryLayer extends RenderLayer<SettlerEntity, SettlerModel> {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(Hearthstead.id("settler_accessories"), "main");
    public static final ResourceLocation ATLAS = Hearthstead.id("textures/entity/look/accessories.png");
    static final double LOD_RANGE = 32.0D;

    /** Stop-3 colour of each hair ramp scaled for the atlas greys (tools/skins sheets3d.hair_tint). */
    static final int[] HAIR_TINT = {0x666B7B, 0x7B5D44, 0x9F7B54, 0x9B634D, 0xC37F52, 0xCDB27A, 0xFDEBA9,
        0xE4DDCF, 0xFFFFF7};
    static final int[] SKIN_TINT = {0xFFF0CC, 0xFFD4A7, 0xF9B784, 0xEAC18A, 0xD69668, 0xC3895E, 0xA57250,
        0x8E6750};
    static final int[][] COSTUME_TINT = {{}, {0x6E7A4A, 0x4E6B45, 0x4A5E86}, {0x8A3444, 0x6A7A42, 0xC09848},
        {}, {}, {}};
    private static final int WHITE = 0xFFFFFF;
    private static final int DEFAULT_CAPE = 0xB2282C;
    private static final int PLUME = 0xD63434;
    private static final int FEATHER = 0xF5ECD6;

    private static final Set<Profession> APRON = EnumSet.of(Profession.SMITH, Profession.BUTCHER,
        Profession.TANNER, Profession.CARPENTER, Profession.ARMOURER, Profession.FLETCHER, Profession.BREWER);

    private final Map<String, ModelPart> parts = new HashMap<>();

    public SettlerAccessoryLayer(RenderLayerParent<SettlerEntity, SettlerModel> parent, EntityModelSet models) {
        super(parent);
        ModelPart root = models.bakeLayer(LAYER);
        for (String name : PART_NAMES) {
            parts.put(name, root.getChild(name));
        }
    }

    static final String[] PART_NAMES = {"bun", "tail", "braid", "braid_r", "braid_l", "volume", "knit_cap",
        "knit_rim", "beard_full", "beard_long", "plume", "nose_broad", "nose_hook", "nose_button", "hood_peak",
        "cap_brim", "knot", "knot_tails", "feather", "pouch", "pouch_flap", "apron", "cape"};
    static final Set<String> TORSO_PARTS = Set.of("pouch", "pouch_flap", "apron", "cape");

    public static LayerDefinition createLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot();
        part(root, "bun", 0, 0, -2.0F, -9.2F, 3.0F, 4.0F, 4.0F, 3.0F, 0.0F, 0.0F, 0.0F);
        part(root, "tail", 14, 0, -1.0F, -6.4F, 4.1F, 2.0F, 10.0F, 2.0F, 0.38F, 0.0F, 0.0F);
        part(root, "braid", 24, 0, -1.0F, -6.0F, 4.0F, 2.0F, 12.0F, 2.0F, 0.16F, 0.0F, 0.0F);
        part(root, "braid_r", 32, 0, -5.3F, -4.2F, -3.2F, 2.0F, 9.0F, 2.0F, 0.0F, 0.0F, 0.06F);
        part(root, "braid_l", 32, 0, 3.3F, -4.2F, -3.2F, 2.0F, 9.0F, 2.0F, 0.0F, 0.0F, -0.06F);
        part(root, "volume", 40, 0, -4.6F, -8.4F, 3.4F, 9.0F, 9.0F, 2.0F, 0.0F, 0.0F, 0.0F);
        part(root, "knit_cap", 64, 0, -4.5F, -9.4F, -4.5F, 9.0F, 4.0F, 9.0F, 0.0F, 0.0F, 0.0F);
        part(root, "knit_rim", 64, 13, -4.7F, -6.4F, -4.7F, 9.0F, 1.0F, 9.0F, 0.0F, 0.0F, 0.0F);
        part(root, "beard_full", 0, 16, -4.2F, -3.0F, -5.7F, 8.0F, 4.0F, 2.0F, 0.0F, 0.0F, 0.0F);
        part(root, "beard_long", 20, 16, -3.0F, 0.2F, -5.4F, 6.0F, 5.0F, 2.0F, -0.18F, 0.0F, 0.0F);
        part(root, "plume", 36, 16, -1.0F, -13.4F, -3.0F, 2.0F, 5.0F, 6.0F, 0.0F, 0.0F, 0.0F);
        part(root, "nose_broad", 52, 16, -1.5F, -3.2F, -5.4F, 3.0F, 2.0F, 1.0F, 0.0F, 0.0F, 0.0F);
        part(root, "nose_hook", 52, 20, -1.0F, -4.6F, -6.1F, 2.0F, 2.0F, 1.0F, 0.0F, 0.0F, 0.0F);
        part(root, "nose_button", 52, 24, -1.0F, -2.6F, -6.2F, 2.0F, 2.0F, 1.0F, 0.0F, 0.0F, 0.0F);
        part(root, "hood_peak", 20, 28, -1.5F, -10.6F, 0.5F, 3.0F, 3.0F, 4.0F, -0.55F, 0.0F, 0.0F);
        part(root, "cap_brim", 34, 28, -4.5F, -7.0F, -7.4F, 9.0F, 1.0F, 3.0F, 0.12F, 0.0F, 0.0F);
        part(root, "knot", 58, 28, -1.5F, -6.2F, 4.4F, 3.0F, 2.0F, 2.0F, 0.0F, 0.0F, 0.0F);
        part(root, "knot_tails", 68, 28, -1.0F, -4.4F, 4.9F, 2.0F, 3.0F, 1.0F, 0.25F, 0.0F, 0.0F);
        part(root, "feather", 74, 28, 4.3F, -13.0F, -1.5F, 1.0F, 6.0F, 3.0F, 0.0F, 0.0F, -0.35F);
        part(root, "pouch", 0, 28, 3.4F, -5.6F, -4.0F, 3.0F, 3.0F, 2.0F, 0.0F, 0.0F, 0.0F);
        part(root, "pouch_flap", 10, 28, 3.3F, -5.9F, -4.1F, 3.0F, 1.0F, 2.0F, 0.0F, 0.0F, 0.0F);
        part(root, "apron", 0, 40, -3.5F, -5.2F, -3.1F, 7.0F, 8.0F, 1.0F, 0.0F, 0.0F, 0.0F);
        // The cape hangs from the shoulders behind the back (pivot 0,-12,2.9 on the torso).
        root.addOrReplaceChild("cape", CubeListBuilder.create().texOffs(16, 40)
                .addBox(-5.0F, 0.0F, 0.0F, 10.0F, 16.0F, 1.0F),
            PartPose.offsetAndRotation(0.0F, -12.0F, 2.9F, 0.1F, 0.0F, 0.0F));
        return LayerDefinition.create(mesh, 128, 64);
    }

    private static void part(PartDefinition root, String name, int u, int v, float x, float y, float z,
                             float w, float h, float d, float rx, float ry, float rz) {
        root.addOrReplaceChild(name, CubeListBuilder.create().texOffs(u, v).addBox(x, y, z, w, h, d),
            PartPose.rotation(rx, ry, rz));
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, SettlerEntity entity,
                       float limbSwing, float limbSwingAmount, float partialTick, float ageInTicks,
                       float netHeadYaw, float headPitch) {
        if (entity.isInvisible() || !CharacterLooks.enabled()) {
            return;
        }
        var camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        if (camera.getPosition().distanceToSqr(entity.position()) > LOD_RANGE * LOD_RANGE) {
            return;
        }
        SettlerModel model = getParentModel();
        ModelPart torso = model.root().getChild("torso");
        ModelPart head = torso.getChild("head");
        if (!head.visible) {
            return;
        }
        boolean hoodUp = head.getChild("hood").visible;
        boolean brim = head.getChild("hat_brim").visible;
        CharacterGenome g = CharacterLooks.genomeOf(entity);
        Profession p = entity.getProfession();
        int costume = p == Profession.NONE ? CharacterLooks.costumeOf(entity) : 0;
        boolean hero = CharacterLooks.heroHook.test(entity);
        VertexConsumer buffer = buffers.getBuffer(RenderType.entityCutoutNoCull(ATLAS));
        int overlay = LivingEntityRenderer.getOverlayCoords(entity, 0.0F);

        int hair = HAIR_TINT[Math.floorMod(g.hairColor(), HAIR_TINT.length)];
        int skin = SKIN_TINT[Math.floorMod(g.skinTone(), SKIN_TINT.length)];
        int job = scaled(p.color());
        boolean knitCap = costume == 0 && p == Profession.LUMBERER;
        boolean covered = hoodUp || brim || knitCap || hero;
        // Job packs, frames, sacks and quivers own the space behind the back
        // (motion lane's CarryPackLayer); hanging hair and the cape yield to them.
        boolean backBusy = backBusy(model, torso);

        // head bone
        pose.pushPose();
        model.translateToTorso(pose);
        head.translateAndRotate(pose);
        int st = g.hairStyle();
        if (st == 9 && !covered) draw("bun", pose, buffer, light, overlay, hair);
        if (st == 10 && !hero && !backBusy) draw("tail", pose, buffer, light, overlay, hair);
        if (st == 7 && !hero && !backBusy) draw("braid", pose, buffer, light, overlay, hair);
        if (st == 8) {
            draw("braid_r", pose, buffer, light, overlay, hair);
            draw("braid_l", pose, buffer, light, overlay, hair);
        }
        if ((st == 5 || st == 6) && !covered) draw("volume", pose, buffer, light, overlay, hair);
        if (g.beard() >= 4) draw("beard_full", pose, buffer, light, overlay, hair);
        if (g.beard() == 5) draw("beard_long", pose, buffer, light, overlay, hair);
        switch (g.nose()) {
            case 1 -> draw("nose_broad", pose, buffer, light, overlay, skin);
            case 2 -> draw("nose_hook", pose, buffer, light, overlay, skin);
            default -> {
            }
        }
        if (costume > 0) {
            int[] tints = COSTUME_TINT[costume];
            int variant = CharacterLooks.costumeVariant(entity.getUUID(), costume);
            int tint = tints.length == 0 ? job : scaled(tints[Math.floorMod(variant, tints.length)]);
            switch (costume) {
                case CharacterLooks.COSTUME_MINSTREL -> draw("feather", pose, buffer, light, overlay, FEATHER);
                case CharacterLooks.COSTUME_REFUGEE -> {
                    if (variant != 1) {
                        draw("knot", pose, buffer, light, overlay, tint);
                        draw("knot_tails", pose, buffer, light, overlay, tint);
                    }
                }
                case CharacterLooks.COSTUME_TRAVELLER -> draw("hood_peak", pose, buffer, light, overlay, tint);
                default -> {
                }
            }
        } else if (!hero) {
            switch (p) {
                case LUMBERER -> {
                    draw("knit_cap", pose, buffer, light, overlay, job);
                    draw("knit_rim", pose, buffer, light, overlay, job);
                }
                case HUNTER, ARCHER, HERDER -> {
                    if (hoodUp) draw("hood_peak", pose, buffer, light, overlay, job);
                }
                case MINER -> draw("cap_brim", pose, buffer, light, overlay, job);
                case BAKER, INNKEEPER, COOK, WEAVER -> {
                    draw("knot", pose, buffer, light, overlay, job);
                    draw("knot_tails", pose, buffer, light, overlay, job);
                }
                default -> {
                }
            }
        }
        if (hero && heroPlume(entity)) {
            draw("plume", pose, buffer, light, overlay, scaled(capeRgb(entity, PLUME)));
        }
        pose.popPose();

        // torso bone
        pose.pushPose();
        model.translateToTorso(pose);
        boolean apron = costume == 0 && !hero && APRON.contains(p);
        if (apron) draw("apron", pose, buffer, light, overlay, job);
        if (!apron && !hero && Math.floorMod(g.clothing(), 3) == 0) {
            draw("pouch", pose, buffer, light, overlay, WHITE);
            draw("pouch_flap", pose, buffer, light, overlay, WHITE);
        }
        if (hero && !backBusy) draw("cape", pose, buffer, light, overlay, scaled(capeRgb(entity, DEFAULT_CAPE)));
        pose.popPose();
    }

    private static boolean backBusy(SettlerModel model, ModelPart torso) {
        try {
            if (model.packLook().visible() && !model.packOnGround()) {
                return true;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // older rig without the pack API: fall through to the part checks
        }
        for (String name : BACK_PARTS) {
            if (torso.hasChild(name) && torso.getChild(name).visible) {
                return true;
            }
        }
        return false;
    }

    private static final String[] BACK_PARTS = {"lumber_frame", "sack", "backpack", "archer_quiver", "traveler_pack"};

    private void draw(String name, PoseStack pose, VertexConsumer buffer, int light, int overlay, int rgb) {
        ModelPart part = parts.get(name);
        if (part != null) {
            part.render(pose, buffer, light, overlay, 0xFF000000 | rgb);
        }
    }

    /** Atlas greys are authored so stop 3 (200/255) reproduces the tint colour. */
    static int scaled(int rgb) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) * 255 / 200);
        int gr = Math.min(255, ((rgb >> 8) & 0xFF) * 255 / 200);
        int b = Math.min(255, (rgb & 0xFF) * 255 / 200);
        return (r << 16) | (gr << 8) | b;
    }

    private static int capeRgb(SettlerEntity entity, int fallback) {
        int dye = HeroHooks.capeColour(entity);
        return dye < 0 ? fallback : DyeColor.byId(dye).getTextureDiffuseColor() & 0xFFFFFF;
    }

    private static boolean heroPlume(SettlerEntity entity) {
        return HeroHooks.plume(entity);
    }
}
