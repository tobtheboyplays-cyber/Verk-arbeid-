package com.hearthstead.client.model;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.util.Mth;

/**
 * The raider rig: lean, hooded and hunched, deliberately unlike the
 * settler's broad, cloaked build.
 *
 * <p>Silhouette is the whole point. Both reference mods field raiders that
 * players cannot tell apart from each other or from their own guards --
 * MineColonies' own design intent is that raiders be "similar to guards",
 * and the resulting complaint is that "chief raiders don't even stand out".
 * A captain here is a different shape at fifty blocks: pauldron, helm, and a
 * taller stance.
 *
 * <p><b>Build geometry lives here, motion lives in {@link RaiderAnimations}.</b>
 * {@link RaiderEntity.Variant#BRUTE} is reshaped every frame, entirely on
 * {@code ModelPart} {@code SCALE} -- mass forward (a broader, flatter
 * chest), arms too long, a wide low skull sunk toward the shoulders by the
 * torso's own compression, stockier legs. SCALE is deliberately the only
 * channel touched here: no clip in {@code RaiderAnimations} ever keys
 * SCALE, so this persistent shape can never be summed with (or erased by)
 * anything the authored clips do to ROTATION/POSITION, in either direction.
 * A captain of either build stands a few degrees straighter than the troops
 * around them -- confidence is the tell, same principle as the guard's
 * confident-vs-nervous {@code GUARD_STANCE} split (animation-quality
 * skill): same skeleton, a shallower number.
 *
 * <p>Texture atlas is 64x64; the UV table is mirrored by tools/gen_raider.py.
 */
public class RaiderModel extends HierarchicalModel<RaiderEntity> {
    public static final ModelLayerLocation LAYER =
        new ModelLayerLocation(Hearthstead.id("raider"), "main");

    private final ModelPart root;
    private final ModelPart torso;
    private final ModelPart head;
    private final ModelPart hood;
    private final ModelPart helm;
    private final ModelPart pauldron;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;

    // ---- BRUTE geometry (ModelPart SCALE only -- see class doc). ----
    private static final float BRUTE_TORSO_X = 1.30F; // broad chest
    private static final float BRUTE_TORSO_Y = 0.90F; // squashed low; pulls
    // the head pivot (torso's child) down toward the shoulders for free
    private static final float BRUTE_TORSO_Z = 1.22F;
    private static final float BRUTE_ARM_LENGTH = 1.38F; // the door-breaker's reach
    private static final float BRUTE_ARM_GIRTH = 1.16F;
    private static final float BRUTE_HEAD_WIDTH = 1.10F;
    private static final float BRUTE_HEAD_HEIGHT = 0.94F; // never taller
    private static final float BRUTE_LEG_GIRTH = 1.14F;
    private static final float BRUTE_LEG_HEIGHT = 0.94F;

    /** Confidence is the captain's tell -- a few degrees straighter than
     * the troops around them, applied after every clip below has run so it
     * corrects STALK, BRUTE_MARCH, SPRINT and MENACE_IDLE alike from one
     * line rather than needing a captain branch baked into each. */
    private static final float CAPTAIN_STRAIGHTEN = 0.09F; // ~5deg

    public RaiderModel(ModelPart root) {
        this.root = root.getChild("root");
        this.torso = this.root.getChild("torso");
        this.head = torso.getChild("head");
        this.hood = head.getChild("hood");
        this.helm = head.getChild("helm");
        this.pauldron = torso.getChild("pauldron");
        this.rightArm = torso.getChild("right_arm");
        this.leftArm = torso.getChild("left_arm");
        this.rightLeg = this.root.getChild("right_leg");
        this.leftLeg = this.root.getChild("left_leg");
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition base = mesh.getRoot();
        PartDefinition rootPart = base.addOrReplaceChild("root",
            CubeListBuilder.create(), PartPose.offset(0.0F, 24.0F, 0.0F));

        PartDefinition torso = rootPart.addOrReplaceChild("torso",
            CubeListBuilder.create()
                .texOffs(0, 16).addBox(-4.0F, -12.0F, -2.0F, 8.0F, 12.0F, 4.0F),
            PartPose.offset(0.0F, -12.0F, 0.0F));

        PartDefinition head = torso.addOrReplaceChild("head",
            CubeListBuilder.create()
                .texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F),
            PartPose.offset(0.0F, -12.0F, 0.0F));
        head.addOrReplaceChild("hood", CubeListBuilder.create()
                .texOffs(32, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F,
                    new CubeDeformation(0.45F)),
            PartPose.ZERO);
        head.addOrReplaceChild("helm", CubeListBuilder.create()
                .texOffs(0, 48).addBox(-4.5F, -10.5F, -4.5F, 9.0F, 3.0F, 9.0F),
            PartPose.ZERO);

        torso.addOrReplaceChild("pauldron", CubeListBuilder.create()
                .texOffs(32, 32).addBox(-5.0F, -12.5F, -2.5F, 10.0F, 3.0F, 5.0F),
            PartPose.ZERO);

        torso.addOrReplaceChild("right_arm", CubeListBuilder.create()
                .texOffs(32, 16).addBox(-1.5F, -1.5F, -1.5F, 3.0F, 12.0F, 3.0F),
            PartPose.offset(-5.0F, -10.0F, 0.0F));
        torso.addOrReplaceChild("left_arm", CubeListBuilder.create()
                .texOffs(48, 16).mirror().addBox(-1.5F, -1.5F, -1.5F, 3.0F, 12.0F, 3.0F),
            PartPose.offset(5.0F, -10.0F, 0.0F));

        rootPart.addOrReplaceChild("right_leg", CubeListBuilder.create()
                .texOffs(0, 32).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(-2.2F, -12.0F, 0.0F));
        rootPart.addOrReplaceChild("left_leg", CubeListBuilder.create()
                .texOffs(16, 32).mirror().addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(2.2F, -12.0F, 0.0F));

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(RaiderEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        root().getAllParts().forEach(ModelPart::resetPose);

        RaiderEntity.Variant variant = entity.variant();
        boolean captain = entity.isCaptain();
        boolean brute = variant == RaiderEntity.Variant.BRUTE;

        helm.visible = captain;
        pauldron.visible = captain;
        hood.visible = !captain; // the helm replaces the hood, never stacks

        // ---- Geometry first: the silhouette must read before a single
        // frame of motion plays. SCALE only -- see class doc for why.
        if (brute) {
            torso.xScale = BRUTE_TORSO_X;
            torso.yScale = BRUTE_TORSO_Y;
            torso.zScale = BRUTE_TORSO_Z;
            rightArm.yScale = BRUTE_ARM_LENGTH;
            leftArm.yScale = BRUTE_ARM_LENGTH;
            rightArm.xScale = BRUTE_ARM_GIRTH;
            leftArm.xScale = BRUTE_ARM_GIRTH;
            rightArm.zScale = BRUTE_ARM_GIRTH;
            leftArm.zScale = BRUTE_ARM_GIRTH;
            head.xScale = BRUTE_HEAD_WIDTH;
            head.zScale = BRUTE_HEAD_WIDTH;
            head.yScale = BRUTE_HEAD_HEIGHT;
            rightLeg.xScale = BRUTE_LEG_GIRTH;
            leftLeg.xScale = BRUTE_LEG_GIRTH;
            rightLeg.zScale = BRUTE_LEG_GIRTH;
            leftLeg.zScale = BRUTE_LEG_GIRTH;
            rightLeg.yScale = BRUTE_LEG_HEIGHT;
            leftLeg.yScale = BRUTE_LEG_HEIGHT;
        }

        // ---- Locomotion: mutually exclusive, same reasoning SettlerModel
        // documents for WALK/WALK_HURRIED/RUN_PANIC -- animateWalk always
        // writes legs+arms+torso, so only one clip may drive it. SPRINT is
        // the SKIRMISHER's charge and only plays while actually closing on
        // a live target; BRUTE always gets BRUTE_MARCH -- "the walk itself
        // is the threat" is true whether it is idle travel or a charge.
        boolean sprinting = !brute && entity.getTarget() != null
            && entity.getTarget().isAlive();
        var locomotion = brute ? RaiderAnimations.BRUTE_MARCH
            : (sprinting ? RaiderAnimations.SPRINT : RaiderAnimations.STALK);
        animateWalk(locomotion, limbSwing, limbSwingAmount, 2.0F, 2.5F);

        // MENACE_IDLE: the stationary read, additive on top of a locomotion
        // clip that is already near-zero while stopped (animateWalk scales
        // its whole output by limbSwingAmount, the same mechanism the
        // settler's own IDLE relies on). Every raider gets it while
        // stopped -- pack, brute, captain, and the telegraph scout, all the
        // same gate, no variant/profession condition.
        int id = entity.getId();
        animate(entity.menaceIdleState, RaiderAnimations.MENACE_IDLE,
            ageInTicks + (id % 53));

        if (captain) {
            torso.xRot -= CAPTAIN_STRAIGHTEN;
            head.xRot -= CAPTAIN_STRAIGHTEN * 0.5F;
        }

        // ---- One-shots: clear only the MOTION (rotation) of the bones
        // they own, never a full resetPose() -- that would zero out the
        // BRUTE scale set above right when the strike needs it most.
        // Mutually exclusive: exactly one of these three ever plays at once
        // (RaiderEntity gates strikeState/breachSlamState/lootSnatchState
        // from three different, non-overlapping trigger sites).
        if (entity.strikeState.isStarted()) {
            clearMotion(rightArm);
            clearMotion(leftArm);
            clearMotion(torso);
            clearMotion(head);
            clearMotion(rightLeg);
            clearMotion(leftLeg);
            animate(entity.strikeState, RaiderAnimations.RAIDER_STRIKE, ageInTicks);
        } else if (entity.breachSlamState.isStarted()) {
            clearMotion(rightArm);
            clearMotion(leftArm);
            clearMotion(torso);
            clearMotion(head);
            clearMotion(rightLeg);
            clearMotion(leftLeg);
            animate(entity.breachSlamState, RaiderAnimations.BREACH_SLAM, ageInTicks);
        } else if (entity.lootSnatchState.isStarted()) {
            clearMotion(rightArm);
            clearMotion(leftArm);
            clearMotion(torso);
            clearMotion(head);
            clearMotion(rightLeg);
            clearMotion(leftLeg);
            animate(entity.lootSnatchState, RaiderAnimations.LOOT_SNATCH, ageInTicks);
        }

        head.yRot += Mth.clamp(netHeadYaw, -55.0F, 55.0F) * ((float) Math.PI / 180F);
        head.xRot += headPitch * ((float) Math.PI / 180F) * 0.8F;

        if (entity.hurtTime > 0) {
            float progress = (float) entity.hurtTime / 10.0F;
            torso.xRot += Mth.sin(progress * (float) Math.PI) * 0.18F;
        }
    }

    /** Zeroes a part's ROTATION only -- never SCALE, so the BRUTE's
     * persistent silhouette shaping above survives a one-shot clearing
     * whatever clip's motion came before it. Position is left alone too:
     * no clip in {@link RaiderAnimations} keys POSITION on anything but
     * {@code root}, so there is nothing on these bones to clear. */
    private static void clearMotion(ModelPart part) {
        part.xRot = 0.0F;
        part.yRot = 0.0F;
        part.zRot = 0.0F;
    }

    /** Kept only because {@code RaiderRenderer} (not owned by this file)
     * still writes to it every frame via {@code entity.getAttackAnim(partialTick)}
     * -- vanilla's own generic arm-swing progress. No longer read here:
     * {@link RaiderAnimations#RAIDER_STRIKE}, an authored one-shot
     * triggered from {@code RaiderEntity#doHurtTarget}, replaced it as the
     * actual attack motion. */
    public float attackTime;

    @Override
    public ModelPart root() {
        return root;
    }
}
