package com.hearthstead.client.model;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.mojang.blaze3d.vertex.PoseStack;
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
import net.minecraft.world.entity.HumanoidArm;

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
    private final ModelPart furMantle;
    private final ModelPart rightShoulder;
    private final ModelPart leftShoulder;
    private final ModelPart hoodTail;
    private final ModelPart scarf;
    private final ModelPart club;
    private final ModelPart dagger;
    private final ModelPart captainFaceGuard;
    private final ModelPart captainAxe;
    private final ModelPart captainShield;
    private final ModelPart beard;
    private final ModelPart rightBracer;
    private final ModelPart leftBracer;
    /** Motion engine: elbow/knee bend joints, eased JSON clips, hit reaction. */
    private final com.hearthstead.client.motion.LimbMotion motion;
    private final com.hearthstead.client.motion.LimbMotion.Overlay overlay =
        new com.hearthstead.client.motion.LimbMotion.Overlay();

    // ---- BRUTE geometry (ModelPart SCALE only -- see class doc). ----
    private static final float BRUTE_TORSO_X = 1.18F; // powerful without a stretched silhouette
    private static final float BRUTE_TORSO_Y = 1.05F; // tall enough to tower over skirmishers
    private static final float BRUTE_TORSO_Z = 1.12F;
    private static final float BRUTE_ARM_LENGTH = 1.26F; // heavy reach without gorilla-like folding
    private static final float BRUTE_ARM_GIRTH = 1.10F;
    private static final float BRUTE_HEAD_WIDTH = 1.06F;
    private static final float BRUTE_HEAD_HEIGHT = 1.02F;
    private static final float BRUTE_LEG_GIRTH = 1.10F;
    // Keep the feet on the ground; overall height comes from the renderer and torso.
    private static final float BRUTE_LEG_HEIGHT = 1.00F;

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
        this.rightArm = torso.getChild("right_arm");
        this.leftArm = torso.getChild("left_arm");
        this.pauldron = rightArm.getChild("pauldron");
        this.rightLeg = this.root.getChild("right_leg");
        this.leftLeg = this.root.getChild("left_leg");
        this.furMantle = torso.getChild("fur_mantle");
        this.rightShoulder = rightArm.getChild("brute_shoulder");
        this.leftShoulder = leftArm.getChild("brute_shoulder");
        this.hoodTail = head.getChild("hood_tail");
        this.scarf = torso.getChild("scarf");
        this.club = rightArm.getChild("club");
        this.dagger = rightArm.getChild("dagger");
        this.captainFaceGuard = head.getChild("captain_face_guard");
        this.captainAxe = rightArm.getChild("captain_axe");
        this.captainShield = leftArm.getChild("captain_shield");
        this.beard = head.getChild("brute_beard");
        this.rightBracer = rightArm.getChild("bracer");
        this.leftBracer = leftArm.getChild("bracer");
        ModelPart rightForearm = rightArm.getChild("right_forearm");
        ModelPart leftForearm = leftArm.getChild("left_forearm");
        ModelPart rightShin = rightLeg.getChild("right_shin");
        ModelPart leftShin = leftLeg.getChild("left_shin");
        this.motion = new com.hearthstead.client.motion.LimbMotion(this, this.root, torso, head,
            new ModelPart[] {rightArm, leftArm, rightLeg, leftLeg},
            new ModelPart[] {rightForearm, leftForearm, rightShin, leftShin},
            new com.hearthstead.client.motion.BendableLimb[] {
                new com.hearthstead.client.motion.BendableLimb(32, 16, -1.5F, -1.5F, -1.5F,
                    3.0F, 12.0F, 3.0F, 0.0F, false, 64.0F, 64.0F, 4.5F),
                new com.hearthstead.client.motion.BendableLimb(48, 16, -1.5F, -1.5F, -1.5F,
                    3.0F, 12.0F, 3.0F, 0.0F, true, 64.0F, 64.0F, 4.5F),
                com.hearthstead.client.motion.BendableLimb.leg(0, 32, false, 64.0F, 64.0F),
                com.hearthstead.client.motion.BendableLimb.leg(16, 32, true, 64.0F, 64.0F)},
            new ModelPart[][] {
                {club, dagger, captainAxe, rightBracer},
                {captainShield, leftBracer},
                {rightLeg.getChild("boot_toe")},
                {leftLeg.getChild("boot_toe")}});
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
        PartDefinition hood = head.addOrReplaceChild("hood", CubeListBuilder.create()
                .texOffs(32, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F,
                    new CubeDeformation(0.45F)),
            PartPose.ZERO);
        hood.addOrReplaceChild("brow_rim", CubeListBuilder.create()
                .texOffs(46, 40).addBox(-3.0F, -6.2F, -5.4F, 3.0F, 1.0F, 2.0F)
                .texOffs(46, 40).addBox(0.0F, -6.2F, -5.4F, 3.0F, 1.0F, 2.0F)
                .texOffs(46, 40).addBox(-4.4F, -6.0F, -5.1F, 1.0F, 3.0F, 2.0F)
                .texOffs(46, 40).addBox(3.4F, -6.0F, -5.1F, 1.0F, 3.0F, 2.0F)
                .texOffs(46, 40).addBox(-2.0F, -3.5F, -5.0F, 4.0F, 3.0F, 2.0F),
            PartPose.ZERO);
        head.addOrReplaceChild("brute_beard", CubeListBuilder.create()
                .texOffs(0, 60).addBox(-2.0F, -2.0F, -5.0F, 4.0F, 2.0F, 2.0F)
                .texOffs(0, 60).addBox(-1.0F, 0.0F, -4.75F, 2.0F, 2.0F, 1.0F),
            PartPose.ZERO);
        head.addOrReplaceChild("helm", CubeListBuilder.create()
                .texOffs(0, 48).addBox(-4.5F, -9.0F, -4.5F, 9.0F, 3.0F, 9.0F),
            PartPose.ZERO);
        head.addOrReplaceChild("captain_face_guard", CubeListBuilder.create()
                .texOffs(40, 48).addBox(-0.5F, -6.0F, -4.75F, 1.0F, 4.0F, 1.0F)
                .texOffs(40, 48).addBox(-4.5F, -3.0F, -4.5F, 1.0F, 3.0F, 1.0F)
                .texOffs(40, 48).addBox(3.5F, -3.0F, -4.5F, 1.0F, 3.0F, 1.0F)
                .texOffs(40, 48).addBox(-2.0F, -1.0F, -4.5F, 4.0F, 2.0F, 1.0F),
            PartPose.ZERO);

        PartDefinition rightArm = torso.addOrReplaceChild("right_arm", CubeListBuilder.create()
                .texOffs(32, 16).addBox(-1.5F, -1.5F, -1.5F, 3.0F, 12.0F, 3.0F),
            PartPose.offset(-5.0F, -10.0F, 0.0F));
        PartDefinition leftArm = torso.addOrReplaceChild("left_arm", CubeListBuilder.create()
                .texOffs(48, 16).mirror().addBox(-1.5F, -1.5F, -1.5F, 3.0F, 12.0F, 3.0F),
            PartPose.offset(5.0F, -10.0F, 0.0F));
        rightArm.addOrReplaceChild("pauldron", CubeListBuilder.create()
                .texOffs(32, 32).addBox(-5.0F, -2.5F, -2.5F, 10.0F, 3.0F, 5.0F)
                .texOffs(40, 48).addBox(-4.0F, 0.3F, -2.1F, 4.0F, 2.0F, 4.0F),
            PartPose.ZERO);
        for (PartDefinition arm : new PartDefinition[] {rightArm, leftArm}) {
            arm.addOrReplaceChild("bracer", CubeListBuilder.create()
                    .texOffs(40, 48).addBox(-2.0F, 5.5F, -2.0F, 4.0F, 3.0F, 4.0F)
                    .texOffs(40, 48).addBox(-1.5F, 6.0F, -2.4F, 3.0F, 2.0F, 1.0F),
                PartPose.ZERO);
        }

        // Reference-inspired details use previously unallocated atlas space.
        // Their parent bones carry all existing motion; no clip names change.
        PartDefinition mantle = torso.addOrReplaceChild("fur_mantle", CubeListBuilder.create()
                .texOffs(32, 40).addBox(-5.0F, -13.0F, -3.0F, 4.0F, 3.0F, 3.0F)
                .texOffs(32, 40).addBox(1.0F, -13.0F, -3.0F, 4.0F, 3.0F, 3.0F)
                .texOffs(32, 40).addBox(-4.0F, -12.0F, 1.0F, 4.0F, 3.0F, 3.0F)
                .texOffs(32, 40).addBox(0.0F, -12.0F, 1.0F, 4.0F, 3.0F, 3.0F)
                .texOffs(32, 40).addBox(-2.0F, -9.0F, 1.25F, 4.0F, 3.0F, 3.0F),
            PartPose.ZERO);
        mantle.addOrReplaceChild("right_fur_lapel", CubeListBuilder.create()
                .texOffs(32, 40).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 3.0F, 3.0F)
                .texOffs(32, 40).addBox(-1.0F, 2.75F, -1.25F, 2.0F, 2.0F, 2.0F),
            PartPose.offsetAndRotation(-2.8F, -10.1F, -2.8F, -0.14F, 0.0F, -0.18F));
        mantle.addOrReplaceChild("left_fur_lapel", CubeListBuilder.create()
                .texOffs(32, 40).addBox(-1.5F, 0.0F, -1.5F, 3.0F, 3.0F, 3.0F)
                .texOffs(32, 40).addBox(-1.0F, 2.75F, -1.25F, 2.0F, 2.0F, 2.0F),
            PartPose.offsetAndRotation(2.8F, -10.1F, -2.8F, -0.14F, 0.0F, 0.18F));
        rightArm.addOrReplaceChild("brute_shoulder", CubeListBuilder.create()
                .texOffs(40, 48).addBox(-2.5F, -2.75F, -2.0F, 4.0F, 2.0F, 4.0F)
                .texOffs(40, 48).addBox(-3.0F, -0.9F, -1.6F, 3.0F, 2.0F, 3.0F),
            PartPose.rotation(0.0F, 0.0F, -0.12F));
        leftArm.addOrReplaceChild("brute_shoulder", CubeListBuilder.create()
                .texOffs(40, 48).mirror().addBox(-1.5F, -2.75F, -2.0F, 4.0F, 2.0F, 4.0F)
                .texOffs(40, 48).addBox(0.0F, -0.9F, -1.6F, 3.0F, 2.0F, 3.0F),
            PartPose.rotation(0.0F, 0.0F, 0.12F));
        head.addOrReplaceChild("hood_tail", CubeListBuilder.create()
                .texOffs(46, 40).addBox(-2.0F, -2.0F, 3.0F, 4.0F, 3.0F, 2.0F),
            PartPose.ZERO);
        torso.addOrReplaceChild("scarf", CubeListBuilder.create()
                .texOffs(46, 40).addBox(-4.0F, -12.0F, -2.5F, 4.0F, 3.0F, 2.0F)
                .texOffs(46, 40).addBox(0.0F, -12.0F, -2.5F, 4.0F, 3.0F, 2.0F),
            PartPose.ZERO);

        // Visual role props, not inventory items: existing attribute-driven
        // melee remains authoritative. Suppressed by an item in the same physical hand.
        rightArm.addOrReplaceChild("club", CubeListBuilder.create()
                .texOffs(36, 48).addBox(-0.5F, -7.0F, -0.5F, 1.0F, 8.0F, 1.0F)
                .texOffs(40, 48).addBox(-1.5F, -10.0F, -1.5F, 3.0F, 4.0F, 3.0F)
                .texOffs(40, 48).addBox(-2.5F, -9.5F, -1.25F, 1.0F, 3.0F, 3.0F)
                .texOffs(40, 48).addBox(1.5F, -9.5F, -1.25F, 1.0F, 3.0F, 3.0F)
                .texOffs(40, 48).addBox(-1.0F, -11.0F, -1.0F, 2.0F, 1.0F, 2.0F),
            PartPose.offsetAndRotation(0.0F, 8.0F, 0.0F, (float) Math.PI / 2.0F, 0.0F, 0.0F));
        rightArm.addOrReplaceChild("dagger", CubeListBuilder.create()
                .texOffs(36, 48).addBox(-0.5F, -1.0F, -0.5F, 1.0F, 3.0F, 1.0F)
                .texOffs(56, 55).addBox(-1.5F, -2.0F, -0.5F, 3.0F, 1.0F, 1.0F)
                .texOffs(56, 48).addBox(-0.5F, -8.0F, -0.5F, 1.0F, 6.0F, 1.0F),
            PartPose.offsetAndRotation(0.0F, 8.0F, 0.0F, (float) Math.PI / 2.0F, 0.0F, 0.0F));
        rightArm.addOrReplaceChild("captain_axe", CubeListBuilder.create()
                .texOffs(36, 48).addBox(-0.5F, -7.0F, -0.5F, 1.0F, 8.0F, 1.0F)
                .texOffs(40, 48).addBox(-1.0F, -8.0F, -1.0F, 3.0F, 2.0F, 2.0F)
                .texOffs(40, 48).addBox(2.0F, -8.5F, -0.75F, 1.0F, 4.0F, 2.0F)
                .texOffs(40, 48).addBox(3.0F, -8.0F, -0.5F, 1.0F, 3.0F, 1.0F)
                .texOffs(40, 48).addBox(1.0F, -5.0F, -0.75F, 1.0F, 1.0F, 2.0F),
            PartPose.offsetAndRotation(0.0F, 8.0F, 0.0F, (float) Math.PI / 2.0F, 0.0F, 0.0F));
        CubeListBuilder shield = CubeListBuilder.create();
        for (int plank = 0; plank < 7; plank++) {
            shield.texOffs(36, 48).addBox(-3.5F + plank, -5.0F, -0.5F,
                1.0F, 10.0F, 1.0F);
        }
        for (int rim = 0; rim < 3; rim++) {
            shield.texOffs(40, 48).addBox(-4.5F + rim * 3, -6.0F, -0.75F,
                    3.0F, 1.0F, 1.0F)
                .texOffs(40, 48).addBox(-4.5F + rim * 3, 5.0F, -0.75F,
                    3.0F, 1.0F, 1.0F);
        }
        for (float side : new float[] {-4.5F, 3.5F}) {
            shield.texOffs(56, 48).addBox(side, -5.0F, -0.75F, 1.0F, 6.0F, 1.0F)
                .texOffs(56, 48).addBox(side, 1.0F, -0.75F, 1.0F, 4.0F, 1.0F);
        }
        shield.texOffs(40, 48).addBox(-1.5F, -1.5F, -1.5F, 3.0F, 3.0F, 1.0F);
        leftArm.addOrReplaceChild("captain_shield", shield,
            PartPose.offset(0.0F, 7.0F, -2.0F));

        PartDefinition rightLeg = rootPart.addOrReplaceChild("right_leg", CubeListBuilder.create()
                .texOffs(0, 32).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(-2.2F, -12.0F, 0.0F));
        PartDefinition leftLeg = rootPart.addOrReplaceChild("left_leg", CubeListBuilder.create()
                .texOffs(16, 32).mirror().addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(2.2F, -12.0F, 0.0F));

        // Motion-engine joints: empty bend parts at the elbow (arm-local y 4.5
        // of the -1.5..10.5 arm) and knee (leg-local y 6); see client.motion.
        rightArm.addOrReplaceChild("right_forearm", CubeListBuilder.create(),
            PartPose.offset(0.0F, 4.5F, 0.0F));
        leftArm.addOrReplaceChild("left_forearm", CubeListBuilder.create(),
            PartPose.offset(0.0F, 4.5F, 0.0F));
        rightLeg.addOrReplaceChild("right_shin", CubeListBuilder.create(),
            PartPose.offset(0.0F, 6.0F, 0.0F));
        leftLeg.addOrReplaceChild("left_shin", CubeListBuilder.create(),
            PartPose.offset(0.0F, 6.0F, 0.0F));

        for (PartDefinition leg : new PartDefinition[] {rightLeg, leftLeg}) {
            leg.addOrReplaceChild("boot_toe", CubeListBuilder.create()
                    .texOffs(40, 57).addBox(-2.0F, 10.0F, -3.25F, 4.0F, 2.0F, 3.0F),
                PartPose.ZERO);
        }

        return LayerDefinition.create(mesh, 64, 64);
    }

    @Override
    public void setupAnim(RaiderEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        root().getAllParts().forEach(ModelPart::resetPose);
        motion.begin(entity, ageInTicks);

        RaiderEntity.Variant variant = entity.variant();
        boolean captain = entity.isCaptain();
        boolean brute = variant == RaiderEntity.Variant.BRUTE;

        helm.visible = captain;
        pauldron.visible = captain;
        hood.visible = !captain && !brute; // brute is bareheaded; captain keeps helm
        furMantle.visible = brute;
        rightShoulder.visible = brute && !captain;
        leftShoulder.visible = brute && !captain;
        hoodTail.visible = !brute && !captain;
        scarf.visible = !brute;
        beard.visible = brute && !captain;
        rightBracer.visible = brute || captain;
        leftBracer.visible = brute || captain;
        boolean rightHandEmpty = (entity.getMainArm() == HumanoidArm.RIGHT
            ? entity.getMainHandItem() : entity.getOffhandItem()).isEmpty();
        boolean leftHandEmpty = (entity.getMainArm() == HumanoidArm.LEFT
            ? entity.getMainHandItem() : entity.getOffhandItem()).isEmpty();
        boolean showRoleWeapon = rightHandEmpty;
        // The club remains an attached visual prop rather than an inventory
        // grant: every BRUTE, including a brute-built captain, visibly holds
        // it while existing saved/current equipment stays untouched and no
        // new raid loot can appear.
        club.visible = brute && showRoleWeapon;
        dagger.visible = !brute && !captain && showRoleWeapon;
        captainFaceGuard.visible = captain;
        captainAxe.visible = captain && !brute && showRoleWeapon;
        // Cosmetic only: this does not add blocking, mitigation or an item drop.
        captainShield.visible = captain && leftHandEmpty;
        if (captain) {
            // Retain the rank/marked UV island on one large shoulder plate.
            pauldron.xScale = 0.5F;
            pauldron.x = 0.0F;
        }

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

        // ---- Scripted clip (finisher victim, cinematic): owns the whole body
        // after the silhouette is set; see client.motion.MotionOverrides.
        if (motion.playScripted(entity, ageInTicks)) {
            return;
        }

        // ---- Locomotion: mutually exclusive, same reasoning SettlerModel
        // documents for WALK/WALK_HURRIED/RUN_PANIC -- animateWalk always
        // writes legs+arms+torso, so only one clip may drive it. SPRINT is
        // the SKIRMISHER's charge and only plays while actually closing on
        // a live target; BRUTE always gets BRUTE_MARCH -- "the walk itself
        // is the threat" is true whether it is idle travel or a charge.
        // isCharging(), NOT getTarget(): Mob.target is server-only AI state
        // and is always null on the client render copy, so the old
        // getTarget() test was false for every raider on every frame and
        // SPRINT could never play -- skirmishers crept at the player at
        // walking pace all the way through a kill. RaiderEntity publishes
        // the fact as synced data now; see DATA_CHARGING there.
        boolean sprinting = !brute && entity.isCharging();
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
            // Negative X is forward on this rig. The authored family now
            // uses that verified sign, so straightening must add toward zero
            // rather than deepen the former backwards bend.
            // Remade (JSON) raider clips key the torso with positive X = forward
            // (it extends ABOVE its hip pivot); the legacy clips leaned back.
            float straighten = motion.engine() && com.hearthstead.client.motion.MotionLibrary
                .override("raider/stalk") != null ? -CAPTAIN_STRAIGHTEN : CAPTAIN_STRAIGHTEN;
            torso.xRot += straighten;
            head.xRot += straighten * 0.5F;
        }

        // ---- One-shots: clear only the MOTION (rotation) of the bones
        // they own, never a full resetPose() -- that would zero out the
        // BRUTE scale set above right when the strike needs it most.
        // Mutually exclusive: each event starts its one-shot through
        // RaiderEntity.startOnlyCombatOneShot(), so a BRUTE's ticketed club
        // cannot layer with its block-only BREACH_SLAM or the generic strike.
        if (entity.cinematicStaggerState.isStarted()) {
            clearMotion(rightArm);
            clearMotion(leftArm);
            clearMotion(torso);
            clearMotion(head);
            animate(entity.cinematicStaggerState, RaiderAnimations.CINEMATIC_STAGGER,
                ageInTicks);
        } else if (entity.cinematicExposedState.isStarted()) {
            clearMotion(rightArm);
            clearMotion(leftArm);
            clearMotion(torso);
            clearMotion(head);
            animate(entity.cinematicExposedState, RaiderAnimations.CINEMATIC_EXPOSED,
                ageInTicks);
        } else if (entity.bruteClubStrikeState.isStarted()) {
            clearMotion(rightArm);
            clearMotion(leftArm);
            clearMotion(torso);
            clearMotion(head);
            clearMotion(rightLeg);
            clearMotion(leftLeg);
            // The club is parented to right_arm, including its held transform.
            animate(entity.bruteClubStrikeState, RaiderAnimations.BRUTE_CLUB_STRIKE,
                ageInTicks);
        } else if (entity.raiderHopBackState.isStarted() || entity.raiderDodgeLeftState.isStarted()
            || entity.raiderDodgeRightState.isStarted()) {
            clearMotion(torso);
            clearMotion(rightLeg);
            clearMotion(leftLeg);
            animate(entity.raiderHopBackState, RaiderMovesetAnimations.RAIDER_HOP_BACK, ageInTicks);
            animate(entity.raiderDodgeLeftState, RaiderMovesetAnimations.RAIDER_DODGE_LEFT, ageInTicks);
            animate(entity.raiderDodgeRightState, RaiderMovesetAnimations.RAIDER_DODGE_RIGHT, ageInTicks);
        } else if (entity.raiderTauntState.isStarted()) {
            // Layered over the idle/stalk base: the clip fades itself in and out.
            animate(entity.raiderTauntState, RaiderMovesetAnimations.RAIDER_TAUNT, ageInTicks);
        } else if (entity.raiderLightState.isStarted()) {
            clearMotion(rightArm);
            clearMotion(leftArm);
            clearMotion(torso);
            clearMotion(head);
            clearMotion(rightLeg);
            clearMotion(leftLeg);
            animate(entity.raiderLightState, RaiderMovesetAnimations.RAIDER_LIGHT, ageInTicks);
        } else if (entity.raiderHeavyState.isStarted()) {
            clearMotion(rightArm);
            clearMotion(leftArm);
            clearMotion(torso);
            clearMotion(head);
            clearMotion(rightLeg);
            clearMotion(leftLeg);
            animate(entity.raiderHeavyState, RaiderMovesetAnimations.RAIDER_HEAVY, ageInTicks);
        } else if (entity.strikeState.isStarted()) {
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

        if (entity.hurtTime > 0 && !motion.engine()) {
            float progress = (float) entity.hurtTime / 10.0F;
            torso.xRot += Mth.sin(progress * (float) Math.PI) * 0.18F;
        }
        // Conversation gesture / listening overlay (client.motion.MotionOverrides).
        motion.playOverlay(entity, ageInTicks);
        if (motion.engine()) {
            overlay.anchored = entity.isPassenger();
            overlay.breathingClip = true;
            overlay.stationaryWork = false;
            overlay.hurtTime = entity.hurtTime;
            overlay.hurtDuration = entity.hurtDuration;
            boolean heavy = entity.variant() == RaiderEntity.Variant.BRUTE;
            overlay.heavySteps = heavy;
            overlay.cloak = heavy ? furMantle : (hoodTail.visible ? hoodTail : null);
            overlay.exertionFloor = entity.isAggressive() ? 0.5F : 0.0F;
            motion.secondary(entity, ageInTicks, overlay);
        }
    }

    /** Bent limbs render as continuous two-segment meshes; unbent is vanilla. */
    @Override
    public void renderToBuffer(PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer buffer,
                               int light, int overlayCoords, int color) {
        motion.render(pose, buffer, light, overlayCoords, color);
    }

    /** Engine on: the state clock drives the authored JSON clip (or the converted legacy clip). */
    @Override
    protected void animate(net.minecraft.world.entity.AnimationState state,
                           net.minecraft.client.animation.AnimationDefinition def,
                           float ageInTicks, float speed) {
        if (!motion.engine()) {
            super.animate(state, def, ageInTicks, speed);
            return;
        }
        state.updateTime(ageInTicks, speed);
        if (state.isStarted()) {
            motion.play(def, state.getAccumulatedTime() / 1000.0F, 1.0F, null);
        }
    }

    /** Locomotion: distance-clocked, stride-matched gait on the engine; vanilla otherwise. */
    @Override
    protected void animateWalk(net.minecraft.client.animation.AnimationDefinition def, float limbSwing,
                               float limbSwingAmount, float maxAnimationSpeed, float animationScaleFactor) {
        if (!motion.engine()) {
            super.animateWalk(def, limbSwing, limbSwingAmount, maxAnimationSpeed, animationScaleFactor);
            return;
        }
        motion.walk(def, limbSwingAmount, animationScaleFactor);
    }

    /** Zeroes a part's ROTATION only -- never SCALE, so the BRUTE's
     * persistent silhouette shaping above survives a one-shot clearing
     * whatever clip's motion came before it. Position is left alone too:
     * no clip in {@link RaiderAnimations} keys POSITION on anything but
     * {@code root}, so there is nothing on these bones to clear. */
    private void clearMotion(ModelPart part) {
        part.xRot = 0.0F;
        part.yRot = 0.0F;
        part.zRot = 0.0F;
        // A one-shot that re-owns a limb re-owns its elbow/knee joint too.
        String bend = part == rightArm ? "right_forearm" : part == leftArm ? "left_forearm"
            : part == rightLeg ? "right_shin" : part == leftLeg ? "left_shin" : null;
        if (bend != null) {
            part.getChild(bend).resetPose();
        }
    }

    /** Kept only because {@code RaiderRenderer} (not owned by this file)
     * still writes to it every frame via {@code entity.getAttackAnim(partialTick)}
     * -- vanilla's own generic arm-swing progress. No longer read here:
     * {@link RaiderAnimations#RAIDER_STRIKE}, an authored one-shot
     * triggered from {@code RaiderEntity#doHurtTarget}, replaced it as the
     * actual attack motion. */
    public float attackTime;

    /** Apply the actual animated hierarchy, including the Brute's part scales. */
    public void translateToHand(HumanoidArm side, PoseStack pose) {
        root.translateAndRotate(pose);
        torso.translateAndRotate(pose);
        (side == HumanoidArm.RIGHT ? rightArm : leftArm).translateAndRotate(pose);
        motion.applyHand(side == HumanoidArm.RIGHT ? com.hearthstead.client.motion.LimbMotion.RIGHT_ARM
            : com.hearthstead.client.motion.LimbMotion.LEFT_ARM, pose);
    }

    @Override
    public ModelPart root() {
        return root;
    }
}
