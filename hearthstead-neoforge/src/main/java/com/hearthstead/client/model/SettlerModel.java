package com.hearthstead.client.model;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.WorkContainerKind;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ArmedModel;
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
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import com.hearthstead.client.motion.BendableLimb;
import com.hearthstead.client.motion.BoneMask;
import com.hearthstead.client.motion.LimbMotion;
import com.hearthstead.client.motion.MotionLibrary;
import com.hearthstead.client.motion.MotionTuning;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.world.entity.AnimationState;

/**
 * Sturdy medieval settler: broad 10-wide torso, hood, shoulder cape, belt and
 * backpack as separately animated parts. Texture atlas is 128x64; the UV
 * table here is mirrored exactly by tools/gen_settler.py.
 */
public class SettlerModel extends HierarchicalModel<SettlerEntity> implements ArmedModel {
    public static final ModelLayerLocation LAYER =
        new ModelLayerLocation(Hearthstead.id("settler"), "main");

    private final ModelPart root;
    private final ModelPart torso;
    private final ModelPart head;
    private final ModelPart hood;
    private final ModelPart guardRim;
    private final ModelPart hatBrim;
    private final ModelPart fisherBrim;
    private final ModelPart fisherNet;
    private final ModelPart fisherTackle;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
    private int serviceHands;
    /** Bag-unload-only split arms; neutral pose reproduces the legacy arm silhouette. */
    private final ModelPart bagRightUpperArm;
    private final ModelPart bagRightForearm;
    private final ModelPart bagLeftUpperArm;
    private final ModelPart bagLeftForearm;
    /** Farmer-only physical vessel, parented to the free hand during WORK_WATER. */
    private final ModelPart wateringCan;
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;
    /** Soft canvas parcel carried by couriers and produce-laden farmers. */
    private final ModelPart sack;
    /** Root-owned courier parcel used only while the portable sack is detached. */
    private final ModelPart groundSack;
    /** Rigid timber carrying frame carried only by lumberers. */
    private final ModelPart lumberFrame;
    private final ModelPart lumberLogLeft;
    private final ModelPart lumberLogCenter;
    private final ModelPart lumberLogRight;
    /** Root-owned duplicate that remains fixed at the server-authored ground anchor. */
    private final ModelPart groundLumberFrame;
    private final ModelPart groundLumberLogLeft;
    private final ModelPart groundLumberLogCenter;
    private final ModelPart groundLumberLogRight;
    private final ModelPart backpack;
    private final ModelPart archerQuiver;
    private final ModelPart travelerStaff;
    private final ModelPart travelerPack;
    private final ModelPart travelerRightUpper;
    private final ModelPart travelerRightFore;
    /** Motion engine: bend joints, layered clip evaluation, secondary motion. */
    private final LimbMotion motion;
    private final ModelPart rightForearm;
    private final ModelPart leftForearm;
    private final ModelPart rightShin;
    private final ModelPart leftShin;
    private final LimbMotion.Overlay overlay = new LimbMotion.Overlay();
    private SettlerEntity animatingEntity;
    /** IDLE under a meal keeps its breath on the chest but leaves the head and hands to EAT. */
    private static final BoneMask IDLE_UNDER_ACTION = BoneMask.of(0.5F,
        "head", 0.0F, "right_arm", 0.0F, "left_arm", 0.0F,
        "right_forearm", 0.0F, "left_forearm", 0.0F, "torso", 0.6F,
        // The meal plants its own feet (knee IK); the idle's hips must not slide them.
        "root", 0.0F, "right_leg", 0.0F, "left_leg", 0.0F, "right_shin", 0.0F, "left_shin", 0.0F);

    /** Courier parcel scale when barely loaded, and when full. */
    private static final float COURIER_PACK_MIN_SCALE = 0.80F;
    private static final float COURIER_PACK_MAX_SCALE = 1.05F;
    /** Forward lean, in radians, a full work container puts into the spine. */
    // A full work container adds 12.6 degrees to WALK_LADEN's authored
    // four-degree chest wedge. The previous 0.16 rad contribution passed the
    // maths gate but still read nearly upright from a clean side camera; this
    // stronger fill-scaled value makes the load unmistakable without folding
    // lightly loaded workers in half.
    private static final float LOAD_MAX_LEAN = 0.22F;
    // WALK_LADEN already supplies the carrier's forward wedge. Courier's soft
    // parcel needs only a small extra hinge; preserve the approved timber gait.
    private static final float COURIER_LOAD_MAX_LEAN = 0.08F;

    /** Inspectable neutral geometry contract for regression tests/tooling. */
    static SplitArmNeutralGeometry splitArmNeutralGeometry() {
        return new SplitArmNeutralGeometry(-2.0F, 10.0F, -2.0F, 4.0F,
            4.0F, 10.0F);
    }

    record SplitArmNeutralGeometry(float legacyMinY, float legacyMaxY,
                                   float upperMinY, float upperMaxY,
                                   float forearmMinY, float forearmMaxY) {
        boolean exactlyCoversLegacyArm() {
            return upperMinY == legacyMinY && forearmMaxY == legacyMaxY
                && upperMaxY == forearmMinY;
        }
    }

    public SettlerModel(ModelPart root) {
        this.root = root.getChild("root");
        this.torso = this.root.getChild("torso");
        this.head = torso.getChild("head");
        this.hood = head.getChild("hood");
        this.guardRim = head.getChild("guard_rim");
        this.hatBrim = head.getChild("hat_brim");
        this.fisherBrim = head.getChild("fisher_brim");
        this.fisherNet = torso.getChild("left_arm").getChild("fisher_net");
        this.fisherTackle = torso.getChild("fisher_tackle");
        this.rightArm = torso.getChild("right_arm");
        this.leftArm = torso.getChild("left_arm");
        this.bagRightUpperArm = torso.getChild("bag_right_upper_arm");
        this.bagRightForearm = bagRightUpperArm.getChild("bag_right_forearm");
        this.bagLeftUpperArm = torso.getChild("bag_left_upper_arm");
        this.bagLeftForearm = bagLeftUpperArm.getChild("bag_left_forearm");
        this.wateringCan = this.leftArm.getChild("watering_can");
        this.rightLeg = this.root.getChild("right_leg");
        this.leftLeg = this.root.getChild("left_leg");
        this.sack = torso.getChild("sack");
        this.groundSack = this.root.getChild("ground_sack");
        this.lumberFrame = torso.getChild("lumber_frame");
        this.lumberLogLeft = lumberFrame.getChild("log_left");
        this.lumberLogCenter = lumberFrame.getChild("log_center");
        this.lumberLogRight = lumberFrame.getChild("log_right");
        this.groundLumberFrame = this.root.getChild("ground_lumber_frame");
        this.groundLumberLogLeft = groundLumberFrame.getChild("ground_log_left");
        this.groundLumberLogCenter = groundLumberFrame.getChild("ground_log_center");
        this.groundLumberLogRight = groundLumberFrame.getChild("ground_log_right");
        this.backpack = torso.getChild("backpack");
        this.archerQuiver = torso.getChild("archer_quiver");
        this.travelerStaff = this.root.getChild("traveler_staff");
        this.travelerPack = torso.getChild("traveler_pack");
        this.travelerRightUpper = torso.getChild("traveler_right_upper_arm");
        this.travelerRightFore = travelerRightUpper.getChild("traveler_right_forearm");
        this.rightForearm = rightArm.getChild("right_forearm");
        this.leftForearm = leftArm.getChild("left_forearm");
        this.rightShin = rightLeg.getChild("right_shin");
        this.leftShin = leftLeg.getChild("left_shin");
        this.motion = new LimbMotion(this, this.root, torso, head,
            new ModelPart[] {rightArm, leftArm, rightLeg, leftLeg},
            new ModelPart[] {rightForearm, leftForearm, rightShin, leftShin},
            new BendableLimb[] {
                BendableLimb.arm(0, 32, false, 128.0F, 64.0F),
                BendableLimb.arm(16, 32, true, 128.0F, 64.0F),
                BendableLimb.leg(32, 32, false, 128.0F, 64.0F),
                BendableLimb.leg(48, 32, true, 128.0F, 64.0F)},
            new ModelPart[][] {{}, {fisherNet, wateringCan}, {}, {}});
        this.motion.setItemParts(rightForearm.getChild("right_item"), leftForearm.getChild("left_item"));
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition base = mesh.getRoot();
        PartDefinition root = base.addOrReplaceChild("root", CubeListBuilder.create(),
            PartPose.offset(0.0F, 24.0F, 0.0F));

        PartDefinition torso = root.addOrReplaceChild("torso", CubeListBuilder.create()
                .texOffs(64, 0).addBox(-5.0F, -12.0F, -2.5F, 10.0F, 12.0F, 5.0F),
            PartPose.offset(0.0F, -12.0F, 0.0F));

        PartDefinition head = torso.addOrReplaceChild("head", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F),
            PartPose.offset(0.0F, -12.0F, 0.0F));
        // Shared villager silhouette; the dedicated skin island cannot pick up
        // eyes, hair or headgear from the composited appearance layers.
        head.addOrReplaceChild("nose", CubeListBuilder.create()
                .texOffs(120, 32).addBox(-0.75F, -2.5F, -5.5F, 1.5F, 3.0F, 1.5F),
            PartPose.ZERO);
        head.addOrReplaceChild("hood", CubeListBuilder.create()
                .texOffs(32, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F,
                    new CubeDeformation(0.6F)),
            PartPose.ZERO);
        // Open iron brow: 0.9 units beyond the existing inflated hood, with
        // short side returns. No faceplate or added height; follows the head.
        head.addOrReplaceChild("guard_rim", CubeListBuilder.create()
                .texOffs(64, 44).addBox(-5.5F, -8.0F, -5.5F, 11.0F, 1.0F, 2.0F)
                .texOffs(90, 44).addBox(-5.5F, -8.0F, -3.5F, 1.0F, 1.0F, 8.0F)
                .texOffs(90, 44).addBox(4.5F, -8.0F, -3.5F, 1.0F, 1.0F, 8.0F),
            PartPose.ZERO);
        head.addOrReplaceChild("hat_brim", CubeListBuilder.create()
                .texOffs(64, 44).addBox(-6.0F, -5.0F, -6.0F, 12.0F, 1.0F, 12.0F),
            PartPose.ZERO);

        // Rolled cap edge follows the owner's dockside Fisher reference.
        head.addOrReplaceChild("fisher_brim", CubeListBuilder.create()
                .texOffs(40, 4).addBox(-4.8F, -6.3F, -4.8F, 9.6F, 1.2F, 1F)
                .texOffs(40, 4).addBox(-4.8F, -6.3F, 3.8F, 9.6F, 1.2F, 1F)
                .texOffs(40, 4).addBox(-4.8F, -6.3F, -3.8F, 1F, 1.2F, 7.6F)
                .texOffs(40, 4).addBox(3.8F, -6.3F, -3.8F, 1F, 1.2F, 7.6F), PartPose.ZERO);
        // Leather bait/tackle pouches stay attached to the belt, never simulated cargo.
        torso.addOrReplaceChild("fisher_tackle", CubeListBuilder.create()
                .texOffs(96, 0).addBox(4.5F, -3F, -2F, 2F, 3F, 3F)
                .texOffs(96, 0).addBox(-6.5F, -3F, -2F, 2F, 3F, 3F), PartPose.ZERO);

        torso.addOrReplaceChild("right_arm", CubeListBuilder.create()
                .texOffs(0, 32).addBox(-2.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(-6.0F, -10.0F, 0.0F));
        PartDefinition leftArm = torso.addOrReplaceChild("left_arm", CubeListBuilder.create()
                .texOffs(16, 32).mirror().addBox(-2.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(6.0F, -10.0F, 0.0F));
        // Open landing-net hoop and tied mesh, original cuboid geometry. The net
        // hangs by the free hand and reaches forward only during the landing beat.
        CubeListBuilder net = CubeListBuilder.create()
            .texOffs(98, 2).addBox(-.5F, 0F, -.5F, 1F, 6F, 1F)
            .texOffs(98, 2).addBox(-3.5F, 6F, -.5F, 7F, .75F, 1F)
            .texOffs(98, 2).addBox(-3.5F, 12F, -.5F, 7F, .75F, 1F)
            .texOffs(98, 2).addBox(-3.5F, 6.75F, -.5F, .75F, 5.25F, 1F)
            .texOffs(98, 2).addBox(2.75F, 6.75F, -.5F, .75F, 5.25F, 1F);
        for (int strand = -2; strand <= 2; strand += 2)
            net.texOffs(98, 3).addBox(strand, 6.75F, -.1F, .25F, 5.25F, .25F);
        for (int row = 8; row <= 10; row += 2)
            net.texOffs(98, 3).addBox(-2.75F, row, -.1F, 5.5F, .25F, .25F);
        leftArm.addOrReplaceChild("fisher_net", net, PartPose.offset(0F, 9F, 0F));
        // Alternate split geometry is visible only during BAG_TO_CHEST.  The
        // ordinary twelve-pixel arms above stay byte-for-byte unchanged for
        // idle, locomotion, combat, armour and held-item attachment.  At zero
        // elbow rotation these two six-pixel sections occupy the same bounds
        // (-2..10 Y) as the legacy straight arm.
        PartDefinition bagRightUpperArm = torso.addOrReplaceChild("bag_right_upper_arm",
            CubeListBuilder.create().texOffs(0, 32)
                .addBox(-2.0F, -2.0F, -2.0F, 4.0F, 6.0F, 4.0F),
            PartPose.offset(-6.0F, -10.0F, 0.0F));
        bagRightUpperArm.addOrReplaceChild("bag_right_forearm",
            CubeListBuilder.create().texOffs(0, 38)
                .addBox(-2.0F, 0.0F, -2.0F, 4.0F, 6.0F, 4.0F),
            PartPose.offset(0.0F, 4.0F, 0.0F));
        PartDefinition bagLeftUpperArm = torso.addOrReplaceChild("bag_left_upper_arm",
            CubeListBuilder.create().texOffs(16, 32).mirror()
                .addBox(-2.0F, -2.0F, -2.0F, 4.0F, 6.0F, 4.0F),
            PartPose.offset(6.0F, -10.0F, 0.0F));
        bagLeftUpperArm.addOrReplaceChild("bag_left_forearm",
            CubeListBuilder.create().texOffs(16, 38).mirror()
                .addBox(-2.0F, 0.0F, -2.0F, 4.0F, 6.0F, 4.0F),
            PartPose.offset(0.0F, 4.0F, 0.0F));
        // A real, hand-parented watering vessel. WORK_WATER is the synced
        // server fact; this prop never creates or consumes inventory. It sits
        // on the free LEFT hand while ItemInHandLayer keeps the farmer's real
        // MAINHAND hoe visible and clear of the pour.
        PartDefinition wateringCan = leftArm.addOrReplaceChild("watering_can",
            CubeListBuilder.create()
                // Intentionally one pixel broader than the forearm on every
                // side: at the earlier 5x4x4 size the vessel projected as a
                // thick sleeve and failed the semantic side-view gate.
                // Use the guaranteed-opaque head/skin palette.  The former
                // lower-atlas UVs are transparent in profession composites,
                // so the correctly posed vessel vanished at runtime/preview.
                // This reads as a deliberately simple wooden field can and
                // stays visible for every farmer skin/outfit combination.
                .texOffs(0, 0).addBox(-3.5F, -1.0F, -3.0F, 7.0F, 5.0F, 6.0F)
                .texOffs(0, 0).addBox(-4.0F, -2.0F, -3.5F, 8.0F, 1.0F, 7.0F)
                // Oversized open handle is intentional: the earlier short
                // handle disappeared into the sleeve in the side cameras and
                // left only a white box.  The lower/forward pivot keeps the
                // complete vessel beyond the hand instead of wrapping it
                // around the forearm like armour.
                .texOffs(0, 0).addBox(-3.5F, -6.0F, -0.75F, 1.5F, 5.0F, 1.5F)
                .texOffs(0, 0).addBox(2.0F, -6.0F, -0.75F, 1.5F, 5.0F, 1.5F)
                .texOffs(0, 0).addBox(-2.0F, -6.0F, -0.75F, 4.0F, 1.5F, 1.5F),
            PartPose.offset(0.0F, 12.0F, -3.0F));
        wateringCan.addOrReplaceChild("spout", CubeListBuilder.create()
                .texOffs(0, 0).addBox(-0.75F, -0.75F, -9.0F, 1.5F, 1.5F, 9.0F)
                .texOffs(0, 0).addBox(-1.5F, -1.5F, -11.0F, 3.0F, 3.0F, 2.0F),
            // Positive X pitches local -Z toward Minecraft +Y (down), so the
            // long spout points at soil on the real moisture-contact frame.
            PartPose.offsetAndRotation(-3.0F, 0.0F, 0.0F, 0.75F, 0.42F, 0.0F));

        torso.addOrReplaceChild("cloak", CubeListBuilder.create()
                .texOffs(64, 32).addBox(-5.5F, 0.0F, -3.0F, 11.0F, 4.0F, 6.0F,
                    new CubeDeformation(0.2F)),
            PartPose.offset(0.0F, -12.0F, 0.0F));
        torso.addOrReplaceChild("backpack", CubeListBuilder.create()
                .texOffs(96, 0).addBox(-3.0F, -9.0F, 2.5F, 6.0F, 7.0F, 3.0F),
            PartPose.ZERO);
        // Archer-only leather quiver: a 3 x 9 x 3 case offset toward the
        // right shoulder (see below). Its upper three units are genuinely hollow; four
        // half-unit walls leave a 2 x 2 opening above the recessed base.
        // Reuse only the owned backpack UV island. Separate upper walls
        // avoid stretching the old 7-unit face into a taller solid backpack.
        // There are deliberately no shafts: archerQuiverCount is persisted
        // server state, not a synced client presentation value.
        // Anim lane 2026-09-26: mirrored to the RIGHT shoulder -- the archer is right-handed (bow in
        // the left hand, SettlerBowHold) and the right hand takes the arrows (ARCHER_RELOAD).
        torso.addOrReplaceChild("archer_quiver", CubeListBuilder.create()
                .texOffs(96, 0).addBox(-3.5F, -8.0F, 3.5F, 3.0F, 6.0F, 3.0F)
                .texOffs(99, 3).addBox(-3.5F, -11.0F, 3.5F, 3.0F, 3.0F, 0.5F)
                .texOffs(99, 3).addBox(-3.5F, -11.0F, 6.0F, 3.0F, 3.0F, 0.5F)
                .texOffs(99, 3).addBox(-3.5F, -11.0F, 4.0F, 0.5F, 3.0F, 2.0F)
                .texOffs(99, 3).addBox(-1.0F, -11.0F, 4.0F, 0.5F, 3.0F, 2.0F)
                // Two leather mounting tabs bridge the real torso rear at
                // z=2.5 to the case at z=3.5. Both sit below the shoulder
                // opening, preserving the existing hood and arm clearance.
                .texOffs(99, 5).addBox(-3.0F, -7.0F, 2.5F, 2.0F, 1.0F, 1.0F)
                .texOffs(99, 5).addBox(-3.0F, -4.0F, 2.5F, 2.0F, 1.0F, 1.0F),
            PartPose.ZERO);

        // Original guest kit: plain shaft and compact flap pouch. These use
        // owned opaque wood/leather atlas islands and never represent inventory.
        root.addOrReplaceChild("traveler_staff", CubeListBuilder.create()
                .texOffs(0, 49).addBox(-0.5F, -26.0F, -0.5F, 1, 12, 1)
                .texOffs(0, 49).addBox(-0.5F, -14.0F, -0.5F, 1, 12, 1)
                .texOffs(7, 49).addBox(-0.5F, -2.0F, -0.5F, 1, 2, 1)
                .texOffs(99, 5).addBox(-0.65F, -17.0F, -0.65F, 1.3F, 2, 1.3F),
            PartPose.ZERO);
        torso.addOrReplaceChild("traveler_pack", CubeListBuilder.create()
                .texOffs(96, 0).addBox(-3, -8, 2.6F, 6, 6, 3)
                .texOffs(99, 3).addBox(-3.2F, -8.5F, 2.5F, 6.4F, 2, 3.3F)
                .texOffs(99, 5).addBox(-0.5F, -7, 5.6F, 1, 4, 0.5F)
                .texOffs(99, 5).addBox(-2.5F, -10, 2.4F, 1, 3, 0.5F)
                .texOffs(99, 5).addBox(1.5F, -10, 2.4F, 1, 3, 0.5F),
            PartPose.ZERO);
        PartDefinition travelerUpper = torso.addOrReplaceChild("traveler_right_upper_arm",
            CubeListBuilder.create().texOffs(0, 32)
                .addBox(-2, -2, -2, 4, 6, 4), PartPose.offset(-6, -10, 0));
        travelerUpper.addOrReplaceChild("traveler_right_forearm",
            CubeListBuilder.create().texOffs(0, 38)
                .addBox(-2, 0, -2, 4, 6, 4), PartPose.offset(0, 4, 0));

        // The carried sack. Distinct from the decorative `backpack` above:
        // a narrow cinched neck overlaps a wider stuffed body, so it still
        // reads as soft cloth when the same mesh is set on the ground. Pivot
        // sits at the top-back of the torso so fill scale grows downward.
        torso.addOrReplaceChild("sack", CubeListBuilder.create()
                .texOffs(28, 17).addBox(-2.5F, 0.0F, 1.0F, 5.0F, 3.0F, 4.0F)
                .texOffs(0, 17).addBox(-3.5F, 2.0F, 0.0F, 7.0F, 6.0F, 6.0F),
            PartPose.offset(0.0F, -10.5F, 2.5F));

        // Original Hearthstead lumber frame: two oak rails, two cross-bars
        // and a bottom cradle. It is a torso child, so it follows the spine
        // exactly and can never trail a stride as a separately simulated prop.
        PartDefinition lumberFrame = torso.addOrReplaceChild("lumber_frame",
            lumberFrameBuilder(), PartPose.offset(0.0F, -10.5F, 4.0F));
        addLumberLogs(lumberFrame, false);
        torso.addOrReplaceChild("belt", CubeListBuilder.create()
                .texOffs(96, 20).addBox(-5.0F, -5.0F, -2.5F, 10.0F, 2.0F, 5.0F,
                    new CubeDeformation(0.3F)),
            PartPose.ZERO);

        root.addOrReplaceChild("right_leg", CubeListBuilder.create()
                .texOffs(32, 32).addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(-2.6F, -12.0F, 0.0F));
        root.addOrReplaceChild("left_leg", CubeListBuilder.create()
                .texOffs(48, 32).mirror().addBox(-2.0F, 0.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(2.6F, -12.0F, 0.0F));

        // Motion-engine joints: empty bend parts at the elbow (arm-local y 4)
        // and knee (leg-local y 6). Their rotation bends the limb mesh at
        // render time (client.motion.BendableLimb); zero = the straight limb.
        // Held-item "wrist" bones at the palm (forearm-local (0,6,0) = arm-local
        // y 10): a clip may roll/pitch/yaw the tool in the fist and slide the
        // grip; identity (never keyed) leaves vanilla's held-item transform.
        torso.getChild("right_arm").addOrReplaceChild("right_forearm", CubeListBuilder.create(),
            PartPose.offset(0.0F, 4.0F, 0.0F))
            .addOrReplaceChild("right_item", CubeListBuilder.create(), PartPose.offset(0.0F, 6.0F, 0.0F));
        leftArm.addOrReplaceChild("left_forearm", CubeListBuilder.create(),
            PartPose.offset(0.0F, 4.0F, 0.0F))
            .addOrReplaceChild("left_item", CubeListBuilder.create(), PartPose.offset(0.0F, 6.0F, 0.0F));
        root.getChild("right_leg").addOrReplaceChild("right_shin", CubeListBuilder.create(),
            PartPose.offset(0.0F, 6.0F, 0.0F));
        root.getChild("left_leg").addOrReplaceChild("left_shin", CubeListBuilder.create(),
            PartPose.offset(0.0F, 6.0F, 0.0F));

        // Seated-only articulation. Existing straight legs and every walking clip stay intact.
        addSeatedLeg(root, "seated_right_leg", -2.6F, 32, false);
        addSeatedLeg(root, "seated_left_leg", 2.6F, 48, true);

        // Same authored sack mesh, but root-owned so it can remain at a
        // fixed world position while the settler walks away. It is hidden
        // unless a server-authoritative WorkContainerKind.SACK is placed.
        root.addOrReplaceChild("ground_sack", CubeListBuilder.create()
                .texOffs(28, 17).addBox(-2.5F, 0.0F, 1.0F, 5.0F, 3.0F, 4.0F)
                .texOffs(0, 17).addBox(-3.5F, 2.0F, 0.0F, 7.0F, 6.0F, 6.0F),
            PartPose.ZERO);

        // Root-owned duplicate of the lumber frame. Runtime projects only
        // this duplicate into yaw-relative model space from the persisted
        // BlockPos, so a placed frame cannot follow, bob with, or teleport to
        // its owner while the lumberer walks around it.
        PartDefinition groundLumberFrame = root.addOrReplaceChild(
            "ground_lumber_frame", lumberFrameBuilder(), PartPose.ZERO);
        addLumberLogs(groundLumberFrame, true);

        return LayerDefinition.create(mesh, 128, 64);
    }

    private static CubeListBuilder lumberFrameBuilder() {
        return CubeListBuilder.create()
            .texOffs(0, 49).addBox(-4.5F, 0.0F, 0.0F, 1.0F, 10.0F, 1.0F)
            .texOffs(0, 49).addBox(3.5F, 0.0F, 0.0F, 1.0F, 10.0F, 1.0F)
            .texOffs(7, 49).addBox(-4.0F, 1.0F, 0.0F, 8.0F, 1.0F, 1.0F)
            .texOffs(7, 49).addBox(-4.0F, 8.0F, 0.0F, 8.0F, 1.0F, 1.0F)
            // Palm-depth lower grips make the two-hand contact readable
            // without protruding past either hand as thin timber spikes.
            // They are part of both attached and ground copies, so
            // detach/lift never swaps to a different silhouette.
            .texOffs(7, 49).addBox(-4.5F, 9.0F, 3.0F, 0.5F, 1.0F, 1.0F)
            .texOffs(7, 49).addBox(4.0F, 9.0F, 3.0F, 0.5F, 1.0F, 1.0F)
            .texOffs(26, 49).addBox(-4.0F, 9.0F, 0.0F, 8.0F, 1.0F, 4.0F)
            // One narrow wooden retainer secures the crossed load; it is
            // structural even when empty, not invented cargo or leather.
            .texOffs(0, 49).addBox(-0.5F, 2.0F, 6.0F, 1.0F, 7.0F, 1.0F);
    }

    private static void addLumberLogs(PartDefinition frame, boolean ground) {
        String prefix = ground ? "ground_log_" : "log_";
        // The retainer connects to the existing top cross-bar and cradle.
        // Their end-to-end joints leave the authored side palm grips clear.
        frame.addOrReplaceChild(prefix + "retainer_top", CubeListBuilder.create()
                .texOffs(7, 49).addBox(-3.0F, 0.0F, -0.5F, 6.0F, 1.0F, 1.0F),
            PartPose.offsetAndRotation(0.0F, 1.0F, 4.0F,
                0.0F, Mth.HALF_PI, 0.0F));
        frame.addOrReplaceChild(prefix + "retainer_base", CubeListBuilder.create()
                .texOffs(7, 49).addBox(-2.0F, 0.0F, -0.5F, 4.0F, 1.0F, 1.0F),
            PartPose.offsetAndRotation(0.0F, 9.0F, 6.0F,
                0.0F, Mth.HALF_PI, 0.0F));
        // Keep the exact 2x8x2 atlas island: its top is pale end grain and
        // its long faces are bark. Pitch that TOP toward the rear, rather
        // than hiding both cut ends above/below three overlapping uprights.
        // The existing fill order is left -> center -> right, so the stack
        // grows from the cradle upward without a floating first load.
        frame.addOrReplaceChild(prefix + "left", CubeListBuilder.create()
                .texOffs(50, 49).addBox(-1.0F, -4.0F, -1.0F,
                    2.0F, 8.0F, 2.0F, new CubeDeformation(0.1F)),
            PartPose.offsetAndRotation(0.0F, 7.9F, 3.9F,
                -Mth.HALF_PI, Mth.PI / 4.0F, 0.0F));
        frame.addOrReplaceChild(prefix + "center", CubeListBuilder.create()
                .texOffs(50, 49).addBox(-1.0F, -4.0F, -1.0F,
                    2.0F, 8.0F, 2.0F, new CubeDeformation(0.1F)),
            PartPose.offsetAndRotation(0.0F, 5.7F, 3.9F,
                -Mth.HALF_PI, -Mth.PI / 4.0F, 0.0F));
        frame.addOrReplaceChild(prefix + "right", CubeListBuilder.create()
                .texOffs(50, 49).addBox(-1.0F, -4.0F, -1.0F,
                    2.0F, 8.0F, 2.0F, new CubeDeformation(0.1F)),
            PartPose.offsetAndRotation(0.0F, 3.5F, 3.9F,
                -Mth.HALF_PI, Mth.PI / 4.0F, 0.0F));
        // Alternate the diagonal to expose cut ends on both rear quarters.
        // Cradle and palm grips retain their authored contact coordinates;
        // the same helper builds the secured attached and detached shapes.
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void setupAnim(SettlerEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
        serviceHands = 0;
        travelerStaff.visible = false;
        travelerPack.visible = false;
        travelerRightUpper.visible = false;
        root().getAllParts().forEach(ModelPart::resetPose);
        animatingEntity = entity;
        motion.begin(entity, ageInTicks);
        // Carry pack lane audit: variants that reach behind the back sit out while a job pack is on it.
        motion.setBackBusy(CarryPackRules.governs(entity.getProfession()));

        // Profession silhouette: hood up, straw brim, or bare head.
        // The visible set mirrors gen_settler.PROFESSION_OUTFITS exactly:
        // every trade whose outfit paints a hood shell (or the guard's
        // helm, which lives on the same cube) shows the cube; bare-headed
        // trades and the two cap-crown trades (farmer, miller -- painted
        // on the head itself) hide it. Before 2026-08-25 only NONE and
        // GUARD were listed, so seven hooded crafts painted hoods that
        // never rendered -- the outfit layer's whole trade-telling job.
        Profession profession = entity.getProfession();
        hood.visible = switch (profession) {
            case NONE, GUARD, BAKER, COOK, SMELTER, MASON, INNKEEPER,
                 WEAVER, MINER, SCHOLAR, MAYOR, BREWER, ARCHER,
                 // TRADES-1: rustic outdoor trades, the same silhouette
                 // family as MINER/ARCHER above -- a shepherd's, a fisher's
                 // and a hunter's hood all read as "works outside, weather-
                 // facing", painted by gen_settler.py's own outfit table.
                 HERDER, HUNTER,
                 // Battle roles: recoloured guard helm (spear, longsword) and
                 // the hooded scholar robe (healer, rune mage).
                 SPEARMAN, LONGSWORDSMAN, HEALER, RUNE_MAGE -> true;
            default -> false;
        };
        hatBrim.visible = profession == Profession.FARMER || profession == Profession.NONE
            && com.hearthstead.entity.look.CharacterLooks.costumeBrim(entity.getLookCostume());
        fisherBrim.visible = false
            && entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty();
        fisherTackle.visible = profession == Profession.FISHER;
        fisherNet.visible = profession == Profession.FISHER && entity.getOffhandItem().isEmpty();
        // SettlerArmorLayer reuses this posed model. Yield the decorative
        // profession rim to actual head equipment; never imply a second kit.
        guardRim.visible = profession == Profession.GUARD
            && entity.getItemBySlot(EquipmentSlot.HEAD).isEmpty();

        SettlerActivity activity = entity.getActivity();
        bagRightUpperArm.visible = false;
        bagLeftUpperArm.visible = false;
        rightArm.visible = true;
        leftArm.visible = true;
        wateringCan.visible = profession == Profession.FARMER
            && activity == SettlerActivity.WORK_WATER;
        boolean climbing = entity.onClimbable();
        boolean lowHealth = entity.getHealth() < entity.getMaxHealth() * 0.4F;
        // Only an actual moving, server-projected combat response gets urgency.
        // Idle/blocked guards and the contact one-shot keep their stable base.
        boolean tacticalRetreat = profession == Profession.GUARD
            && activity == SettlerActivity.RETREATING;
        boolean urgentGuard = profession == Profession.GUARD
            && ((activity == SettlerActivity.COMBAT && !lowHealth) || tacticalRetreat)
            && !entity.meleeState.isStarted() && limbSwingAmount > 0.01F
            && entity.getDeltaMovement().horizontalDistanceSqr() > 1.0E-5D;
        boolean night = entity.dayPhase().rest();
        boolean dark = entity.level().getRawBrightness(entity.blockPosition(), 0) <= 4;

        if (climbing) {
            animate(entity.climbState, SettlerAnimations.CLIMB_LADDER, ageInTicks);
        } else {
            // Locomotion: mutually exclusive alternatives to WALK, picked by
            // priority since animateWalk always writes legs+arms+torso+cloak
            // and vanilla's animate() is additive -- only one may run.
            var locomotion = SettlerAnimations.WALK;
            if (activity == SettlerActivity.FLEEING) {
                locomotion = SettlerAnimations.RUN_PANIC;
            } else if (tacticalRetreat) {
                // Real recovery navigation retains the martial ready gait,
                // even below the injury threshold. Never use fear gestures.
                locomotion = SettlerAnimations.WALK_HURRIED;
            } else if (hunterV2(entity, profession) && activity == SettlerActivity.TRACKING_GAME) {
                // Hunter v2 (anim lane): the crouched stalk, bow low, arrow nocked (WALK's stride).
                locomotion = HunterMotionAnimations.HUNTER_STALK;
            } else if (hunterV2(entity, profession) && activity == SettlerActivity.HAULING_CARCASS) {
                // Hunter v2: the kill across the shoulders, hands on its legs (WALK_LADEN's stride).
                locomotion = HunterMotionAnimations.HUNTER_HAUL;
            } else if (activity == SettlerActivity.CARRYING
                || activity == SettlerActivity.HAULING_LOG
                || activity == SettlerActivity.HAULING_CARCASS
                // BUILDER lane: a load of planks walks with the laden gait.
                || activity == SettlerActivity.CARRY_MATERIALS) {
                // A visible sack is carried by the back and spine. Both the
                // Courier and Lumberer therefore use the same short, loaded
                // gait, sampled from actual distance travelled. The old
                // Lumberer HAUL_LOG clip drove its own legs from a fixed
                // 2.4-second wall clock, which made slow navigation look like
                // crawling and allowed the feet to slide against the ground.
                locomotion = SettlerAnimations.WALK_LADEN;
            } else if (activity == SettlerActivity.COLLECTING_ITEMS
                && !entity.getOffhandItem().isEmpty()) {
                // The placed container stays behind. Only the one physical
                // item in OFFHAND travels, held visibly against the body.
                locomotion = SettlerAnimations.WALK_CARRY_ITEM;
            } else if (lowHealth) {
                locomotion = SettlerAnimations.WALK_LIMP;
            } else if (urgentGuard) {
                locomotion = SettlerAnimations.WALK_HURRIED;
            } else if (entity.isTipsy() && profession != Profession.GUARD) {
                // Tavern lane: real ales drunk (server-synced level) - 1 a slight sway, 2 an
                // unsteady weave, 3+ a lurching stagger. Same distance-clocked WALK slot.
                int drunk = entity.drunkLevel();
                locomotion = drunk >= com.hearthstead.entity.Drunkenness.VERY ? TavernMotionAnimations.VERY_DRUNK_WALK
                    : drunk == com.hearthstead.entity.Drunkenness.DRUNK ? TavernMotionAnimations.DRUNK_WALK
                    : TavernMotionAnimations.TIPSY_WALK;
            } else if (night && dark && profession != Profession.GUARD
                && activity != SettlerActivity.RESTING && activity != SettlerActivity.SLEEPING) {
                locomotion = SettlerAnimations.CREEP_NIGHT;
            } else if (activity == SettlerActivity.TRAVELING
                && !entity.hasTravelerAppearance()) {
                locomotion = SettlerAnimations.WALK_HURRIED;
            } else if (profession != Profession.GUARD
                && com.hearthstead.client.ambient.AmbientClient.hurryInRain(entity)) {
                // Living village: a brisker step through open rain (gait only).
                locomotion = SettlerAnimations.WALK_HURRIED;
            }
            // Replace only the ordinary civilian WALK. All urgent, injured,
            // laden and travel choices above remain their existing gaits.
            if (profession == Profession.GUARD && locomotion == SettlerAnimations.WALK) {
                locomotion = SettlerAnimations.GUARD_WALK;
            }
            boolean loadedCourier = (profession == Profession.COURIER || profession == Profession.TRADER)
                && entity.carryState.isStarted()
                && locomotion == SettlerAnimations.WALK_LADEN;
            float gaitAmount = limbSwingAmount;
            if (loadedCourier) {
                // A blocked path must not retain a walking pose from the
                // smoothed limb animation. Read actual horizontal travel.
                double dx = entity.getX() - entity.xo;
                double dz = entity.getZ() - entity.zo;
                gaitAmount = Math.min(gaitAmount,
                    (float) Math.sqrt(dx * dx + dz * dz) * 4.0F);
            }
            // Engine: a load shortens the step through the gait amplitude (arc and
            // stride together, so the stance foot stays planted) -- couriers by
            // their bag, lumberers hauling by their frame.
            float haulLoad = loadedCourier ? Mth.clamp(entity.visualCarryFraction(), 0.0F, 1.0F)
                : profession == Profession.LUMBERER && activity == SettlerActivity.HAULING_LOG
                    ? Mth.clamp(Math.max(entity.visualCarryFraction(), entity.heavyHaulPoseBlend()), 0.0F, 1.0F)
                    : 0.0F;
            float pullStride = com.hearthstead.client.render.HandCartRenderer.isPulling(entity) ? 0.85F : 1.0F;
            motion.setGaitAmplitude(Mth.lerp(haulLoad, 0.95F, 0.75F) / 0.95F * pullStride);
            animateWalk(locomotion, limbSwing, gaitAmount, 2.0F, 2.5F);
            motion.setGaitAmplitude(1.0F);
            if (loadedCourier && !motion.engine()) {
                float load = Mth.clamp(entity.visualCarryFraction(), 0.0F, 1.0F);
                // Keep one distance clock: changing its frequency as a bag
                // fills would rephase the feet. Weight shortens the stride
                // and widens the support stance without inward foot rotation.
                float stride = Mth.lerp(load, 0.95F, 0.75F);
                rightLeg.xRot *= stride;
                leftLeg.xRot *= stride;
                rightLeg.x -= 0.3F * load;
                leftLeg.x += 0.3F * load;
                torso.y += 0.25F * load;
            } else if (haulLoad > 0.0F && motion.engine()) {
                // Engine: the stride already shortened above; keep the wider stance.
                rightLeg.x -= 0.3F * haulLoad;
                leftLeg.x += 0.3F * haulLoad;
                torso.y += 0.25F * haulLoad;
            }
            if (locomotion == SettlerAnimations.WALK_LADEN
                || locomotion == SettlerAnimations.WALK_CARRY_ITEM) {
                // Load weight: the legs are rigid cuboids, so the "knee
                // bend" is a hip sink into the belt (torso only -- the root,
                // and so both soles, never drops below the ground plane).
                // Scaled by real gait so a stalled carrier stands tall.
                float carryDrive = Mth.clamp(gaitAmount * 2.5F, 0.0F, 1.0F);
                torso.y += 0.3F * carryDrive;
                if (locomotion == SettlerAnimations.WALK_CARRY_ITEM) {
                    // Free arm swings shorter under a load; the offhand
                    // cradle (left arm) keeps its authored hold untouched.
                    rightArm.xRot *= 0.75F;
                }
            }

            if (entity.haulPoseBlend() > 0.0F && entity.haulState.isStarted()) {
                // The sack already shows the load. HAUL_LOG is now only a
                // low arm/tool hold layered over WALK_LADEN; reset the arms
                // so no previous locomotion pose can lift the axe overhead.
                // Preserve that base pose first, then ease toward the authored
                // hold over five client ticks; this removes the activity-edge
                // snap without putting fixed-time legs back into the clip.
                float baseRightX = rightArm.xRot;
                float baseRightY = rightArm.yRot;
                float baseRightZ = rightArm.zRot;
                float baseLeftX = leftArm.xRot;
                float baseLeftY = leftArm.yRot;
                float baseLeftZ = leftArm.zRot;
                resetLimb(rightArm);
                resetLimb(leftArm);
                float heavyBlend = Mth.clamp(entity.heavyHaulPoseBlend(), 0.0F, 1.0F);
                if (heavyBlend <= 0.0F) {
                    animate(entity.haulState, SettlerAnimations.HAUL_LOG, ageInTicks);
                } else if (heavyBlend >= 1.0F) {
                    animate(entity.haulState, SettlerAnimations.HAUL_LOG_HEAVY, ageInTicks);
                } else {
                    animate(entity.haulState, SettlerAnimations.HAUL_LOG, ageInTicks);
                    float lightRightX = rightArm.xRot;
                    float lightRightY = rightArm.yRot;
                    float lightRightZ = rightArm.zRot;
                    float lightLeftX = leftArm.xRot;
                    float lightLeftY = leftArm.yRot;
                    float lightLeftZ = leftArm.zRot;
                    resetLimb(rightArm);
                    resetLimb(leftArm);
                    animate(entity.haulState, SettlerAnimations.HAUL_LOG_HEAVY, ageInTicks);
                    float easedHeavy = heavyBlend * heavyBlend * (3.0F - 2.0F * heavyBlend);
                    rightArm.xRot = Mth.lerp(easedHeavy, lightRightX, rightArm.xRot);
                    rightArm.yRot = Mth.lerp(easedHeavy, lightRightY, rightArm.yRot);
                    rightArm.zRot = Mth.lerp(easedHeavy, lightRightZ, rightArm.zRot);
                    leftArm.xRot = Mth.lerp(easedHeavy, lightLeftX, leftArm.xRot);
                    leftArm.yRot = Mth.lerp(easedHeavy, lightLeftY, leftArm.yRot);
                    leftArm.zRot = Mth.lerp(easedHeavy, lightLeftZ, leftArm.zRot);
                }
                float haulBlend = Mth.clamp(entity.haulPoseBlend(), 0.0F, 1.0F);
                float easedHaul = haulBlend * haulBlend * (3.0F - 2.0F * haulBlend);
                rightArm.xRot = Mth.lerp(easedHaul, baseRightX, rightArm.xRot);
                rightArm.yRot = Mth.lerp(easedHaul, baseRightY, rightArm.yRot);
                rightArm.zRot = Mth.lerp(easedHaul, baseRightZ, rightArm.zRot);
                leftArm.xRot = Mth.lerp(easedHaul, baseLeftX, leftArm.xRot);
                leftArm.yRot = Mth.lerp(easedHaul, baseLeftY, leftArm.yRot);
                leftArm.zRot = Mth.lerp(easedHaul, baseLeftZ, leftArm.zRot);
            } else if (entity.carcassCarryState.isStarted() && !hunterV2(entity, profession)) {
                // Hunter carcass on the shoulders (CarcassCarryLayer draws it). Hunter v2: HUNTER_HAUL owns the arms.
                resetLimb(rightArm);
                resetLimb(leftArm);
                animate(entity.carcassCarryState, SettlerAnimations.CARCASS_SHOULDER_CARRY, ageInTicks);
            } else if (entity.patrolState.isStarted()
                && (profession == Profession.ARCHER
                    ? entity.hasPhysicalMainhandBow()
                    : entity.hasPhysicalMainhandSword() || entity.hasGuardMeleeWeapon())) {
                // The moving martial overlays override WALK's arm swing with
                // an equipment-specific hold. This includes a GUARD in
                // COMBAT: MeleeAttackGoal is still navigating between blows,
                // but the sword arm must never inherit whichever half of the
                // civilian WALK cycle happened to be sampled when EV_MELEE
                // arrived. Root and both legs remain owned by distance-
                // sampled locomotion; the cloak keeps its gait-phase drag.
                // Torso belongs to the stable martial upper-body base, so a
                // WALK twist cannot move the real sword contact sideways.
                // Reset those exact upper-body bones first, since
                // vanilla's animate() adds onto the current pose rather than
                // replacing it. Gated on the AnimationState itself (not a
                // re-derived limbSwingAmount threshold, which used a
                // different cutoff than the state's own animateWhen
                // condition and could reset the arms with nothing applied
                // to replace them -- RELEASE_GATE LOW-4).
                float urgentFreeArm = leftArm.xRot;
                resetLimb(rightArm);
                resetLimb(leftArm);
                head.resetPose();
                torso.resetPose();
                animate(entity.patrolState,
                    profession == Profession.ARCHER
                        ? SettlerAnimations.ARCHER_PATROL
                        : SettlerAnimations.GUARD_PATROL,
                    ageInTicks);
                if (urgentGuard) {
                    float drive = Mth.clamp(limbSwingAmount * 2.5F, 0.0F, 1.0F);
                    torso.xRot = 0.24F * drive;
                    head.xRot -= 0.12F * drive;
                    // Keep the real sword's controlled martial hold. Only the
                    // genuinely free hand inherits distance-phased arm drive.
                    if (entity.getOffhandItem().isEmpty()) {
                        leftArm.xRot += urgentFreeArm * 0.55F;
                    }
                }
            } else if (entity.carryState.isStarted()
                && (profession == Profession.COURIER || profession == Profession.TRADER)) {
                // Legs, root, spine and cloak keep their actual-distance
                // WALK_LADEN pose. Only the strap grip uses a quiet hold;
                // applySack supplies the existing single load-dependent lean.
                // The old full-body wall-clock loop erased gait compression
                // and rocked even when navigation could not move the actor.
                resetLimb(rightArm);
                resetLimb(leftArm);
                animate(entity.carryState, SettlerAnimations.COURIER_CARRY_GRIP,
                    ageInTicks);
            } else if (entity.carryState.isStarted()
                && profession == Profession.FARMER) {
                // Farm produce is a real bag load, but the farmer still owns
                // a physical MAINHAND hoe. Reusing COURIER_CARRY would pull
                // both hands to the straps and drive that hoe through the
                // face. WALK_LADEN keeps the feet distance-sampled; the
                // dedicated FARMER_CARRY arms-only family keeps the tool low
                // and the free hand on the front strap without borrowing the
                // Lumberer's axe/log pose language.
                resetLimb(rightArm);
                resetLimb(leftArm);
                animate(entity.carryState, SettlerAnimations.FARMER_CARRY, ageInTicks);
            }
        }

        // Per-entity phase offsets so a crowd never moves in unison
        // (§17.4 check 25). Offsetting the sampled ageInTicks is only valid
        // for LOOPING clips (IDLE, SLEEP_IN_BED) -- it can jump a ONE-SHOT
        // past its own length on the very first evaluated frame, truncating
        // or skipping it entirely (RELEASE_GATE MEDIUM-2). CELEBRATE and
        // WAKE_STRETCH are one-shots, so their per-entity variation is
        // staggered server-side instead, on the TRIGGER tick
        // (SettlerEntity.celebrate()/triggerWakeStretch()) -- not here.
        int id = entity.getId();
        animate(entity.idleState, SettlerAnimations.IDLE, ageInTicks + (id % 80));
        // Trade idles (owner: "vil ogsa ha idle animations som matcher
        // jobben"). Each is gated exclusively with idleState and with each
        // other in SettlerEntity.setupAnimationStates() -- exactly one of
        // these fifteen (IDLE plus the fourteen below) is ever started for
        // a given settler at a time, so no resetPose() is needed here
        // beyond the one already done at the top of this method: the same
        // reasoning IDLE itself relies on. Per-entity phase offsets follow
        // the same id%N scheme as IDLE and the CHAINS-1 craft loops above
        // (valid here because every trade idle is a LOOPING clip) -- the
        // moduli are chosen distinct from the ones already in use on this
        // page so two different clips' offsets never accidentally beat
        // together.
        animate(entity.idleFarmerState, SettlerAnimations.IDLE_FARMER, ageInTicks + (id % 37));
        animate(entity.idleLumbererState, SettlerAnimations.IDLE_LUMBERER, ageInTicks + (id % 43));
        AnimationDefinition roleIdle = roleIdle(entity, profession);
        animate(entity.idleSentryState,
            roleIdle != null ? roleIdle
            : profession == Profession.SPEARMAN || profession == Profession.LONGSWORDSMAN
                ? SettlerAnimations.IDLE
            : profession == Profession.ARCHER && entity.hasPhysicalMainhandBow()
                ? SettlerAnimations.IDLE_ARCHER
                : profession == Profession.GUARD && !entity.hasPhysicalMainhandSword()
                    ? SettlerAnimations.IDLE
                    : SettlerAnimations.IDLE_SENTRY,
            ageInTicks + (id % 47));
        animate(entity.idleCourierState, SettlerAnimations.IDLE_COURIER, ageInTicks + (id % 41));
        animate(entity.idleTraderState, SettlerAnimations.IDLE_TRADER, ageInTicks + (id % 71));
        animate(entity.idleForgeState, SettlerAnimations.IDLE_FORGE, ageInTicks + (id % 53));
        animate(entity.idleBakerState, SettlerAnimations.IDLE_BAKER, ageInTicks + (id % 29));
        animate(entity.idleCookState, SettlerAnimations.IDLE_COOK, ageInTicks + (id % 31));
        animate(entity.idleSightEdgeState, SettlerAnimations.IDLE_SIGHT_EDGE, ageInTicks + (id % 59));
        animate(entity.idleFletcherState, SettlerAnimations.IDLE_FLETCHER, ageInTicks + (id % 23));
        animate(entity.idleMinerState, SettlerAnimations.IDLE_MINER, ageInTicks + (id % 61));
        animate(entity.idleScholarState, SettlerAnimations.IDLE_SCHOLAR, ageInTicks + (id % 33));
        animate(entity.idleInnkeeperState, SettlerAnimations.IDLE_INNKEEPER, ageInTicks + (id % 27));
        animate(entity.idleWeaverState, SettlerAnimations.IDLE_WEAVER, ageInTicks + (id % 39));
        animate(entity.idleBladeBenchState, SettlerAnimations.IDLE_BLADE_BENCH, ageInTicks + (id % 49));
        // TRADES-1: FISHER's own idle -- HERDER shares idleFarmerState and
        // HUNTER shares idleSentryState above, both already wired to
        // IDLE_FARMER/IDLE_SENTRY. Modulus 67 is not used by any other
        // clip's phase offset on this page.
        animate(entity.idleFisherState, SettlerAnimations.IDLE_FISHER, ageInTicks + (id % 67));
        if (entity.farmState.isStarted() || entity.chopState.isStarted()
            || entity.plantState.isStarted() || entity.harvestState.isStarted()
            || entity.waterState.isStarted()
            || (profession == Profession.FARMER && entity.sowState.isStarted())) {
            // These stationary field actions own their complete pose. Residual
            // distance-smoothed WALK and idle offsets must not steer a tool or
            // keep a foot swinging beneath a planted work/contact animation.
            root.resetPose();
            torso.resetPose();
            head.resetPose();
            resetLimb(rightArm);
            resetLimb(leftArm);
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            torso.getChild("cloak").resetPose();
        }
        animate(entity.farmState, SettlerAnimations.FARM_TILL, ageInTicks);
        animate(entity.chopState, SettlerAnimations.CHOP, ageInTicks);
        animate(entity.eatState, SettlerAnimations.EAT, ageInTicks);
        animate(entity.restState, SettlerAnimations.REST, ageInTicks);
        if (entity.celebrateState.isStarted()) {
            // CELEBRATING never actually played (docs/project/PLAN_ETTER_DEMO.md
            // round-1 known debt #2): this animate() call ran unconditionally,
            // every tick, laid on TOP of whichever trade idle (or work loop)
            // was already summed into the pose above -- vanilla's animate() is
            // additive, so CELEBRATE's own arm-to-176-degrees keys added onto
            // an idle's own arm rotation instead of replacing it, and the
            // settler's arm visibly speared through their own chest instead of
            // throwing up in celebration. Same bug shape as PICKUP_STOW/
            // COURIER_LIFT/COURIER_SET_DOWN above and the SHIELD_BLOCK branch
            // below, and the same fix: CELEBRATE is a full self-contained
            // one-shot (its own right_arm/left_arm/torso/head/root/cloak/
            // right_leg/left_leg channels -- see SettlerAnimations.CELEBRATE),
            // so every part it authors must be reset to a clean pose before
            // it is applied, not summed onto whatever loop was mid-frame.
            resetLimb(rightArm);
            resetLimb(leftArm);
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            animate(entity.celebrateState, SettlerAnimations.CELEBRATE, ageInTicks);
        }
        animate(entity.plantState, SettlerAnimations.FARM_PLANT, ageInTicks);
        animate(entity.harvestState, SettlerAnimations.FARM_HARVEST, ageInTicks);
        animate(entity.waterState, SettlerAnimations.FARM_WATER, ageInTicks);
        animate(entity.limbState, SettlerAnimations.LIMB_BRANCHES, ageInTicks);
        // CHAINS-1 craft motions. Staggered by entity id so a row of bakers
        // does not knead in lockstep -- the same trick idleState uses.
        // WORK_KNEAD: trade clock (the miller has his own MILL_GRIND).
        animate(entity.cleaveState, SettlerAnimations.CLEAVE, ageInTicks + (id % 17));
        animate(entity.butcherState, SettlerAnimations.CLEAVE, ageInTicks + (id % 17));
        // WORK_STOKE: played by the trade clock below (the brewer has his own BREW_MASH).
        // WORK_HAMMER: trade clock (the armourer has his own ARMOUR_PLANISH).
        animate(entity.sawState, SettlerAnimations.SAW, ageInTicks + (id % 22));
        // WORK_WEAVE: trade clock (the weaver has his own LOOM_WEAVE; scholars keep FINE_WORK).
        animate(entity.ovenState, SettlerAnimations.OVEN_TEND, ageInTicks + (id % 32));
        if (profession == Profession.FARMER && entity.sowState.isStarted()) {
            // A replant presses one seed at tick14, exactly like first planting.
            // Keep that approach unchanged and shorten only the recovery to28.
            entity.sowState.updateTime(ageInTicks, 1.0F);
            long elapsed = entity.sowState.getAccumulatedTime();
            long cycle = elapsed % 1400L;
            long plantTime = cycle <= 700L ? cycle : 700L + (cycle - 700L) * 13L / 7L;
            sampleClip(SettlerAnimations.FARM_PLANT, plantTime);
        } else {
            animate(entity.sowState, SettlerAnimations.SOW_BROADCAST, ageInTicks + (id % 28));
        }
        animate(entity.mineState, SettlerAnimations.MINE_PICK, ageInTicks + (id % 19));
        animate(entity.stirState, SettlerAnimations.COOK_STIR, ageInTicks + (id % 30));
        animate(entity.planeState, SettlerAnimations.CARPENTER_PLANE, ageInTicks + (id % 26));
        animate(entity.chiselState, SettlerAnimations.MASON_CHISEL, ageInTicks + (id % 21));
        // WORK_NAIL (wooden repair) has no entity AnimationState: its clock is
        // the model-side time since this settler entered the activity, which
        // starts on the same tick as RepairWorkGoal's own counter.
        float nailSeconds = activityClock(nailClocks, entity,
            activity == SettlerActivity.WORK_NAIL && entity.walkAnimation.speed() <= 0.05F, ageInTicks);
        if (nailSeconds >= 0.0F) {
            sampleClip(CraftMotionAnimations.NAIL_HAMMER, (long) (nailSeconds * 1000.0F));
        }
        // BUILDER lane: same model-clock pattern as WORK_NAIL. The clocks
        // start on the tick BuilderWorkGoal enters the activity, so its
        // contact beats (BUILD_PLACE taps at 0.90/1.20 s, BUILD_HAMMER at
        // 0.45 s) land on the WorkSoundSync taps.
        float buildSeconds = activityClock(buildClocks, entity,
            activity == SettlerActivity.WORK_BUILD && entity.walkAnimation.speed() <= 0.05F, ageInTicks);
        if (buildSeconds >= 0.0F) {
            sampleClip(CraftMotionAnimations.BUILD_PLACE, (long) (buildSeconds * 1000.0F));
        }
        float buildHammerSeconds = activityClock(buildHammerClocks, entity,
            activity == SettlerActivity.WORK_BUILD_HAMMER && entity.walkAnimation.speed() <= 0.05F, ageInTicks);
        if (buildHammerSeconds >= 0.0F) {
            sampleClip(CraftMotionAnimations.BUILD_HAMMER, (long) (buildHammerSeconds * 1000.0F));
        }
        // RING-1 lane: Sharpened Axes whet and Fisher's Nets set/haul, same model-clock
        // pattern; the clocks start on the tick LumbererWhetGoal / FisherNetGoal enter
        // the activity, so the sparks and the float land on the authored strokes.
        float whetSeconds = activityClock(whetClocks, entity,
            activity == SettlerActivity.WORK_WHET && entity.walkAnimation.speed() <= 0.05F, ageInTicks);
        if (whetSeconds >= 0.0F) {
            sampleClip(CraftMotionAnimations.WHET_AXE, (long) (whetSeconds * 1000.0F));
        }
        float netSeconds = activityClock(netClocks, entity,
            activity == SettlerActivity.WORK_NET && entity.walkAnimation.speed() <= 0.05F, ageInTicks);
        if (netSeconds >= 0.0F) {
            sampleClip(CraftMotionAnimations.FISHER_NET, (long) (netSeconds * 1000.0F));
        }
        // ANIM lane: trade work clips on a model clock from the activity's first tick -- the same
        // origin as CrafterWorkGoal's clipTick, so every settler's contact sound lands on the
        // strike (no per-entity phase offset). Variants (__vN) still pick at loop boundaries.
        AnimationDefinition tradeClip = tradeClip(activity, profession);
        float tradeSeconds = tradeClock(entity, activity,
            tradeClip != null && entity.walkAnimation.speed() <= 0.05F, ageInTicks);
        if (tradeSeconds >= 0.0F && tradeClip != null) {
            sampleClip(tradeClip, (long) (tradeSeconds * 1000.0F));
        }
        // TRADER lane: the 10 s deal at the counter, one-shot on a model clock from the tick
        // TraderWorkGoal enters TRADING, so the handshake frame is the sale tick (TraderDealScene).
        float dealSeconds = activityClock(traderDealClocks, entity,
            activity == SettlerActivity.TRADING && entity.walkAnimation.speed() <= 0.05F, ageInTicks);
        if (dealSeconds >= 0.0F) {
            sampleClip(TraderMotionAnimations.TRADER_DEAL, (long) (Math.min(dealSeconds, 10.0F) * 1000.0F));
        }
        float carrySeconds = activityClock(carryPlankClocks, entity,
            activity == SettlerActivity.CARRY_MATERIALS, ageInTicks);
        if (carrySeconds >= 0.0F) {
            sampleClip(CraftMotionAnimations.CARRY_PLANKS, (long) (carrySeconds * 1000.0F));
        }
        // ANIM lane (battle roles): rune channels and healer care, same model-clock pattern,
        // restarted whenever the role activity changes (firebolt -> frost starts a new clip).
        // Absolute full-body clips; no other activity clip is live under them.
        AnimationDefinition roleActivityClip = switch (activity) {
            case CAST_FIREBOLT -> RoleMotionAnimations.RUNE_CAST;
            case CAST_FROST -> RoleMotionAnimations.RUNE_FROST;
            case CAST_WARD -> RoleMotionAnimations.RUNE_WARD;
            case WORK_BANDAGE -> RoleMotionAnimations.HEALER_BANDAGE;
            case WORK_REVIVE -> RoleMotionAnimations.HEALER_REVIVE;
            default -> null;
        };
        float roleActivitySeconds = roleActivityClock(entity, activity,
            roleActivityClip != null && entity.walkAnimation.speed() <= 0.05F, ageInTicks);
        if (roleActivitySeconds >= 0.0F && roleActivityClip != null) {
            sampleClip(roleActivityClip, (long) (roleActivitySeconds * 1000.0F));
        }
        animate(entity.fletchState, SettlerAnimations.FLETCHER_FLETCH, ageInTicks + (id % 32));
        animate(entity.scrapeState, SettlerAnimations.TANNER_SCRAPE, ageInTicks + (id % 24));
        animate(entity.skinState, SettlerAnimations.TANNER_SCRAPE, ageInTicks + (id % 24));
        // TRADES-1: same stationary-work-loop pattern as the row above --
        // staggered by entity id so a row of herders/fishers/hunters never
        // moves in lockstep.
        animate(entity.shearState, SettlerAnimations.HERDER_SHEAR, ageInTicks + (id % 25));
        // Fisher pose is sampled from the authoritative catch clock below.
        // No id offset or 2-second loop may announce a catch that never happened.
        // Hunting is a single physical shot and recovery, not a staggered work loop.
        if (entity.huntState.isStarted()) {
            // Navigation has stopped; the client gait envelope can still be
            // decaying. Plant the body without delaying the real draw clock.
            torso.resetPose();
            head.resetPose();
            torso.getChild("cloak").resetPose();
            root.resetPose();
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            animate(entity.huntState, SettlerAnimations.HUNTER_LOOSE, ageInTicks);
        }
        // ANIM-TRUTH-0A craft contacts own the complete planted body. They
        // must replace any loop sampled earlier in this method, and their
        // clocks must remain exact: table commit is 1.50 s / tick 30 and
        // storage deposit is 0.70 s / tick 14.
        if (entity.craftState.isStarted() || entity.craftStoreState.isStarted()) {
            resetLimb(rightArm);
            resetLimb(leftArm);
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            animate(entity.craftState, SettlerAnimations.LUMBER_CRAFT,
                ageInTicks);
            animate(entity.craftStoreState,
                SettlerAnimations.CRAFT_OUTPUT_STORE, ageInTicks);
        }
        if (entity.leapState.isStarted()) {
            // LEAP_STRIKE is keyed ABSOLUTE (frame 0 = the GUARD_STANCE hold),
            // so summing it over the stance doubled every offset: reset the
            // bones it owns first, like the other full-body holds.
            resetLimb(rightArm);
            resetLimb(leftArm);
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
        }
        animate(entity.leapState, SettlerAnimations.LEAP_STRIKE, ageInTicks);
        // GATHER_LOG is a full-body contact one-shot. Vanilla animation
        // channels are additive, so layering this stoop over CHOP/LIMB/WALK
        // folded the torso and arms into impossible 60-180 degree sums. It
        // owns these bones absolutely for its short contact window, exactly
        // like PICKUP_STOW and the courier lift below.
        if (entity.gatherState.isStarted()) {
            resetLimb(rightArm);
            resetLimb(leftArm);
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            animate(entity.gatherState, SettlerAnimations.GATHER_LOG, ageInTicks);
        }
        // Portable work-container loop. These are four independent,
        // inspectable full-body beats; only the server event for the active
        // transaction phase starts one. Never layer them over a work loop.
        if (entity.workContainerDownState.isStarted()
            || entity.groundItemPickupState.isStarted()
            || entity.workContainerStowState.isStarted()
            || entity.workContainerUpState.isStarted()) {
            resetLimb(rightArm);
            resetLimb(leftArm);
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            animate(entity.workContainerDownState,
                SettlerAnimations.WORK_CONTAINER_DOWN, ageInTicks);
            animate(entity.groundItemPickupState,
                SettlerAnimations.GROUND_ITEM_PICKUP, ageInTicks);
            animate(entity.workContainerStowState,
                SettlerAnimations.WORK_CONTAINER_STOW, ageInTicks);
            animate(entity.workContainerUpState,
                SettlerAnimations.WORK_CONTAINER_UP, ageInTicks);
        }
        // Front collection and front item carry use negative arm pitch on this
        // rig (+Y runs down the arm, -Z is the actor's front). Keep the same
        // pickup/stow clocks and physical item attachment, including their seam.
        if (entity.groundItemPickupState.isStarted()
            || entity.workContainerStowState.isStarted()
            || (activity == SettlerActivity.COLLECTING_ITEMS
                && !entity.getOffhandItem().isEmpty()
                && !entity.workContainerDownState.isStarted()
                && !entity.workContainerUpState.isStarted())) {
            leftArm.xRot = -Math.abs(leftArm.xRot);
        }
        // PICKUP_STOW is a full-body one-shot like the courier lift: reset
        // every bone it owns first so the stoop layers over a clean pose
        // rather than whatever loop was mid-frame (same guard the lift uses).
        if (entity.pickupState.isStarted()) {
            resetLimb(rightArm);
            resetLimb(leftArm);
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            animate(entity.pickupState, SettlerAnimations.PICKUP_STOW, ageInTicks);
        }
        // COURIER_SORT: a stationary work clip, the same pattern as
        // chopState/farmState above -- sortState is already gated on
        // activity==SORTING && !moving (SettlerEntity), so WALK's own
        // near-zero contribution while stopped doesn't fight it. Drives
        // both of CourierWorkGoal's SORTING-activity phases (loading at
        // the hearth and filing at the warehouse chest) since neither has
        // a distinct AnimationState of its own -- see the piece 3 report.
        animate(entity.sortState, SettlerAnimations.COURIER_SORT, ageInTicks);

        // COURIER_LIFT / COURIER_SET_DOWN are event-driven one-shots that
        // OVERRIDE the sort loop they interrupt: the lift arrives at the
        // carry pose and the set-down departs from it, so letting either
        // sum with COURIER_SORT's own arm/torso holds would smear both.
        // Reset only the parts these clips author, then apply. No
        // per-entity phase offset -- a one-shot offset can jump past the
        // clip's own length on the first evaluated frame.
        if (entity.liftState.isStarted() || entity.setDownState.isStarted()) {
            resetLimb(rightArm);
            resetLimb(leftArm);
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            animate(entity.liftState, SettlerAnimations.COURIER_LIFT, ageInTicks);
            animate(entity.setDownState, SettlerAnimations.COURIER_SET_DOWN, ageInTicks);
        }
        // The reviewed bag-to-chest candidate owns the planted body for its
        // full four seconds. It is intentionally separate from the Lumberer
        // work-container states above, preserving their approved handoffs.
        var bagTransfer = entity.bagTransferPresentation();
        if (bagTransfer.ownsBodyPose(entity)) {
            resetLimb(rightArm);
            resetLimb(leftArm);
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            rightArm.visible = false;
            leftArm.visible = false;
            bagRightUpperArm.visible = true;
            bagLeftUpperArm.visible = true;
            // Follow the server's planted-session clock, never an event restart.
            sampleClip(SettlerAnimations.BAG_TO_CHEST_UNLOAD,
                (long) (Math.min(80.0F, bagTransfer.clock()
                    + Mth.clamp(ageInTicks - entity.tickCount, 0.0F, 0.999F)) * 50.0F));
            if (bagTransfer.sourcePickup() && bagTransfer.clock() >= 24
                    && bagTransfer.clock() < 64) {
                // Preserve the normal forward lower/lift segments. The middle
                // is an original source-face to sack reach, not reversed time.
                float partial = Mth.clamp(ageInTicks - entity.tickCount, 0, .999F);
                float clock = bagTransfer.clock() + partial;
                float blend = smoothStep((clock - 24) / 6);
                if (clock > 48) blend *= 1 - smoothStep((clock - 48) / 16);
                // Torso approach settles in six ticks; feet retain the original planted base.
                // An authored bag_to_chest_unload clip carries its own reach and dip.
                if (!(motion.engine() && MotionLibrary.override("settler/bag_to_chest_unload") != null)) {
                    torso.xRot = Mth.lerp(blend, torso.xRot, .5F);
                    torso.y = Mth.lerp(blend, torso.y, -8.0F);
                }
                Vec3 target = bagTransfer.sourceUnitPosition(Math.min(clock, 48));
                Vec3 local = sourceBagLocal(entity, target, partial);
                Vector3f rp = rootSpacePoint(0, 6, 0, bagRightForearm, bagRightUpperArm);
                Vector3f lp = rootSpacePoint(0, 6, 0, bagLeftForearm, bagLeftUpperArm);
                Vec3 rightRest = new Vec3(rp.x, rp.y, rp.z);
                Vec3 leftRest = new Vec3(lp.x, lp.y, lp.z);
                applyContainerGrip(rightArm, bagRightUpperArm, bagRightForearm,
                    rightRest.lerp(local.add(-1.0, 0, 0), blend), -1);
                applyContainerGrip(leftArm, bagLeftUpperArm, bagLeftForearm,
                    leftRest.lerp(local.add(1.0, 0, 0), blend), 1);
            }
        }
        animate(entity.sleepState, SettlerAnimations.SLEEP_IN_BED, ageInTicks + (id % 160));
        animate(entity.wakeState, SettlerAnimations.WAKE_STRETCH, ageInTicks);

        boolean physicalSword = entity.hasPhysicalMainhandSword();
        // Frontline roles (guard, spearman, longswordsman) with a real blade or
        // polearm share the guard moveset until their own clips land.
        boolean frontlineBlade = profession.frontline() && (physicalSword
            || entity.getMainHandItem().getItem() instanceof com.hearthstead.item.role.RoleWeaponItem
            || entity.hasGuardMeleeWeapon());
        boolean physicalShieldLoadout = physicalSword
            && entity.hasPhysicalOffhandShield();
        boolean saluting = isSaluting(entity, profession);
        boolean shieldWall = !saluting && profession == Profession.GUARD && physicalShieldLoadout
            && entity.isUsingItem() && entity.getUsedItemHand() == net.minecraft.world.InteractionHand.OFF_HAND
            && !entity.shieldState.isStarted() && !entity.guardStaggerState.isStarted()
            && entity.walkAnimation.speed() < 0.05F;
        if (saluting) {
            // Owner-requested salute (EV_GUARD_SALUTE): attention snap 0-0.40 s,
            // the salute 0.40-1.60 s, then hold attention until the state ends;
            // the cross-fade (salute bit in its signature) eases back to the
            // stance. Absolute full-body holds, reset first.
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            head.resetPose();
            torso.resetPose();
            resetLimb(rightArm);
            resetLimb(leftArm);
            root.resetPose();
            torso.getChild("cloak").resetPose();
            // Greeting (owner spec 2026-09-26, GuardSaluteGoal): attention snap 0-0.40 s,
            // sheathe 0.40-1.40 s, hand salute raised 1.40-1.70 s, held (loop) until the
            // goal's EV_GUARD_SALUTE_END: cut-away 0-0.35 s, draw 0.35-1.25 s.
            if (entity.guardSaluteEndState.isStarted()) {
                entity.guardSaluteEndState.updateTime(ageInTicks, 1.0F);
                long t = entity.guardSaluteEndState.getAccumulatedTime();
                if (t < 350L) {
                    sampleClip(GuardGreetingAnimations.GUARD_SALUTE_RELEASE, t);
                } else {
                    sampleClip(GuardGreetingAnimations.GUARD_DRAW_SWORD, Math.min(t - 350L, 900L));
                }
            } else {
                entity.guardSaluteState.updateTime(ageInTicks, 1.0F);
                long t = entity.guardSaluteState.getAccumulatedTime();
                if (t < 400L) {
                    sampleClip(GuardMovesetAnimations.GUARD_ATTENTION_SNAP, t);
                } else if (t < 1400L) {
                    sampleClip(GuardGreetingAnimations.GUARD_SHEATHE_SWORD, t - 400L);
                } else if (t < 1700L) {
                    sampleClip(GuardGreetingAnimations.GUARD_SALUTE_RAISE, t - 1400L);
                } else {
                    sampleClip(GuardGreetingAnimations.GUARD_SALUTE_HOLD_BROW, t - 1700L);
                }
            }
        } else if (shieldWall) {
            // Shield line (field order): a knight holding its OFFHAND shield up
            // keeps the looping SHIELD_BLOCK brace for as long as it is raised.
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            head.resetPose();
            torso.resetPose();
            resetLimb(rightArm);
            resetLimb(leftArm);
            root.resetPose();
            torso.getChild("cloak").resetPose();
            sampleClip(SettlerAnimations.SHIELD_BLOCK, (long) ((ageInTicks + id % 32) * 50.0F));
        } else if (entity.guardStaggerState.isStarted() && profession.frontline()) {
            // Knocked off balance by a heavy blow: a full self-contained hold,
            // reset first like GUARD_HIT_REACT (never summed over the stance).
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            head.resetPose();
            torso.resetPose();
            resetLimb(rightArm);
            resetLimb(leftArm);
            root.resetPose();
            torso.getChild("cloak").resetPose();
            animate(entity.guardStaggerState, GuardMovesetAnimations.GUARD_STAGGER, ageInTicks);
        } else if (entity.shieldState.isStarted()
            && (physicalShieldLoadout || physicalSword)) {
            // An incoming-hit event is not proof that a physical shield
            // exists. ItemInHandLayer renders only the entity's real synced
            // equipment, so select the shield brace only for a real OFFHAND
            // shield; ordinary first-demo guards use the authored free-hand
            // hit reaction instead. Both clips are full self-contained holds
            // (their own legs/torso/arms/head/root/cloak) -- reset every part
            // they touch first, or the
            // stance's hold values (and any small residual from WALK, whose
            // amplitude near-zeroes but doesn't fully zero while stationary)
            // add underneath and corrupt the block pose (RELEASE_GATE
            // MEDIUM-1). GUARD_STANCE resumes cleanly next frame once
            // shieldState (a short reflexive one-shot) ends.
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            head.resetPose();
            torso.resetPose();
            resetLimb(rightArm);
            resetLimb(leftArm);
            root.resetPose();
            torso.getChild("cloak").resetPose();
            animate(entity.shieldState,
                physicalShieldLoadout
                    ? SettlerAnimations.SHIELD_BLOCK
                    : SettlerAnimations.GUARD_HIT_REACT,
                ageInTicks);
        } else {
            // The absolute LEAP_STRIKE (reset + played above) replaces the stance.
            AnimationDefinition roleStance = roleIdle(entity, profession);
            if (roleStance == RoleMotionAnimations.IDLE_LONGSWORDSMAN) {
                roleStance = RoleMotionAnimations.LONGSWORD_STANCE;   // combat guard, strikes start/end here
            }
            if (!entity.leapState.isStarted()) animate(entity.stanceState,
                profession == Profession.ARCHER && entity.hasPhysicalMainhandBow()
                    ? SettlerAnimations.ARCHER_STANCE
                    : roleStance != null
                        ? roleStance
                    : frontlineBlade
                        ? SettlerAnimations.GUARD_STANCE
                        : SettlerAnimations.IDLE,
                ageInTicks);
            // EV_MELEE owns a zero-offset upper-body strike over exactly one
            // martial base. A stationary guard has GUARD_STANCE here; a
            // moving guard already received the absolute GUARD_PATROL
            // arm/head base above, after WALK's gait arms were cleared. This
            // ordering keeps the sword arm independent of gait phase while
            // leaving WALK's distance-sampled root and feet untouched.
            // Battle-role strikes (EV_ROLE_MOVE_BASE + RoleMove): ABSOLUTE full-body clips that
            // start and end on the role idle -- reset what they own first. On the move the
            // gait keeps the root and legs (STRIKE_OVER_GAIT via isGuardStrike).
            AnimationDefinition roleStrike = entity.roleMoveState.isStarted()
                ? roleMoveClip(entity.roleMoveId) : null;
            if (roleStrike != null && frontlineBlade) {
                if (entity.walkAnimation.speed() <= 0.05F) {
                    resetLimb(rightLeg);
                    resetLimb(leftLeg);
                    root.resetPose();
                }
                head.resetPose();
                torso.resetPose();
                resetLimb(rightArm);
                resetLimb(leftArm);
                torso.getChild("cloak").resetPose();
                animate(entity.roleMoveState, roleStrike, ageInTicks);
            } else if (entity.guardFinisherState.isStarted() && frontlineBlade) {
                animate(entity.guardFinisherState,
                    SettlerAnimations.GUARD_FINISHER_DRIVE, ageInTicks);
            } else if (entity.guardHeavyState.isStarted() && frontlineBlade
                    && weaponClipKey(entity, "_heavy") != null) {
                // Authored per-weapon heavy (animations/settler/<clipSet>_heavy.json).
                entity.guardHeavyState.updateTime(ageInTicks, 1.0F);
                motion.playKey(weaponClipKey(entity, "_heavy"),
                    entity.guardHeavyState.getAccumulatedTime() / 1000.0F, 1.0F);
            } else if (entity.guardHeavyState.isStarted() && frontlineBlade) {
                // Heavy hit tick = max(11, weapon contact): only the warhammer (12) re-times.
                animate(entity.guardHeavyState, GuardMovesetAnimations.GUARD_HEAVY_OVERHEAD, ageInTicks,
                    11.0F / Math.max(11, entity.guardWeaponClass().contactTick()));
            } else if (entity.guardShieldBashState.isStarted() && profession.frontline()) {
                animate(entity.guardShieldBashState, GuardMovesetAnimations.GUARD_SHIELD_BASH, ageInTicks);
            } else if (entity.guardLightBState.isStarted() && frontlineBlade) {
                animate(entity.guardLightBState, GuardMovesetAnimations.GUARD_LIGHT_SLASH_B, ageInTicks);
            } else if (entity.meleeState.isStarted() && frontlineBlade && weaponStrikeKey(entity) == null) {
                animate(entity.meleeState, SettlerAnimations.MELEE, ageInTicks, meleeRate(entity));
            } else if (entity.meleeState.isStarted() && frontlineBlade) {
                // Authored per-weapon swing (animations/settler/<clipSet>_strike.json).
                entity.meleeState.updateTime(ageInTicks, 1.0F);
                motion.playKey(weaponStrikeKey(entity), entity.meleeState.getAccumulatedTime() / 1000.0F, 1.0F);
            }
        }

        if (hunterV2(entity, profession)) {
            applyHunterV2(entity, activity, limbSwingAmount, ageInTicks);
        } else if ((profession == Profession.ARCHER
                || profession == Profession.HUNTER && entity.huntState.isStarted())
            && entity.hasPhysicalMainhandBow()) {
            if (profession == Profession.ARCHER && motion.engine()) {
                applyArcherClips(entity, ageInTicks);   // anim lane: the authored v5 draw / reload
            } else {
                applyBowMotion(entity, netHeadYaw, headPitch,
                    profession == Profession.HUNTER
                        ? com.hearthstead.entity.ai.HunterWorkGoal.HUNT_RELEASE_TICK : 20.0F, ageInTicks);
            }
        }

        // Head tracking layers additively over the keyframes, damped per the
        // catalogue's damping table (§17.4 check 24) -- most-specific first.
        float damp;
        if (activity == SettlerActivity.SLEEPING) {
            damp = 0.0F;
        } else if (entity.workContainerDownState.isStarted()
            || entity.groundItemPickupState.isStarted()
            || entity.workContainerStowState.isStarted()
            || entity.workContainerUpState.isStarted()) {
            // The eyes stay on the physical hand/container transaction.
            // Full free-look here can turn the head sixty degrees away at
            // contact and makes a correct bend read like a broken spine.
            damp = 0.12F;
        } else if (entity.farmState.isStarted() || entity.chopState.isStarted()
            || entity.plantState.isStarted() || entity.harvestState.isStarted()
            || entity.waterState.isStarted()
            || (profession == Profession.FARMER && entity.sowState.isStarted())) {
            damp = 0.2F;
        } else if (entity.shieldState.isStarted()) {
            damp = 0.15F;
        } else if (entity.guardSaluteState.isStarted() && isSaluting(entity, profession)) {
            // Held hand salute: the goal turns the body after the passing player; the head
            // adds only a little so the hand stays at the brow.
            damp = 0.4F;
        } else if (climbing) {
            damp = 0.3F;
        } else if (activity == SettlerActivity.FLEEING
            || activity == SettlerActivity.RETREATING) {
            damp = 0.4F;
        } else if (activity == SettlerActivity.RESTING || activity == SettlerActivity.EATING) {
            damp = 0.25F;
        } else {
            damp = 1.0F;
        }
        head.yRot += Mth.clamp(netHeadYaw, -60.0F, 60.0F) * ((float) Math.PI / 180F) * damp;
        head.xRot += headPitch * ((float) Math.PI / 180F) * damp;

        // Procedural hurt flinch (the motion engine's eased hit reaction replaces it).
        if (entity.hurtTime > 0 && !motion.engine()) {
            float progress = (float) entity.hurtTime / 10.0F;
            torso.xRot += Mth.sin(progress * (float) Math.PI) * 0.15F;
        }

        applyWorkContainer(entity, ageInTicks);

        // Absolute one-shot priority: the binding can arrive while a farmer
        // is tilling, a guard is braced, or a courier is moving, and animate()
        // is additive. Applying BLESSING_RECEIVE on top of any of those would
        // sum two incompatible full-body poses. Reset every part the clip
        // owns only after locomotion, work, one-shots, head tracking, hurt
        // flinch and carry lean have all run, then apply the self-contained
        // acceptance pose once. This changes presentation only; the entity's
        // synced activity and AI continue untouched underneath and become
        // visible again on the first frame after the 1.60 s state expires.
        if (entity.blessingReceiveState.isStarted()) {
            rightArm.visible = true;
            leftArm.visible = true;
            bagRightUpperArm.visible = false;
            bagLeftUpperArm.visible = false;
            resetLimb(rightArm);
            resetLimb(leftArm);
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            resetLimb(rightLeg);
            resetLimb(leftLeg);
            animate(entity.blessingReceiveState,
                SettlerAnimations.BLESSING_RECEIVE, ageInTicks);
        }
        if (entity.innkeeperSocialMode() == com.hearthstead.entity.InnkeeperAtmosphere.WELCOME
            && !entity.blessingReceiveState.isStarted()) {
            // The server reserves both empty hands and stops movement first.
            // Reset the mug-polishing idle rather than adding two arm poses.
            ModelPart[] greetingParts = {rightArm, leftArm, torso, root, rightLeg, leftLeg, head};
            PartPose[] previous = new PartPose[greetingParts.length];
            for (int i = 0; i < greetingParts.length; i++) previous[i] = greetingParts[i].storePose();
            resetLimb(rightArm); resetLimb(leftArm); torso.resetPose();
            root.resetPose(); resetLimb(rightLeg); resetLimb(leftLeg);
            head.resetPose();
            head.yRot = Mth.clamp(netHeadYaw, -60F, 60F) * Mth.DEG_TO_RAD;
            head.xRot = Mth.clamp(headPitch, -35F, 35F) * Mth.DEG_TO_RAD;
            float elapsed = entity.level().getGameTime() - entity.innkeeperSocialStart()
                + ageInTicks - entity.tickCount;
            sampleClip(SettlerAnimations.INN_WELCOME, (long) (Math.max(0F, elapsed) * 50F));
            float envelope = Math.min(smoothStep(elapsed / 4F), smoothStep((36F - elapsed) / 6F));
            for (int i = 0; i < greetingParts.length; i++) {
                ModelPart part = greetingParts[i];
                PartPose from = previous[i];
                part.x = Mth.lerp(envelope, from.x, part.x);
                part.y = Mth.lerp(envelope, from.y, part.y);
                part.z = Mth.lerp(envelope, from.z, part.z);
                part.xRot = Mth.lerp(envelope, from.xRot, part.xRot);
                part.yRot = Mth.lerp(envelope, from.yRot, part.yRot);
                part.zRot = Mth.lerp(envelope, from.zRot, part.zRot);
            }
        }
        int villageSocial = entity.villageSocialMode();
        if (villageSocial != com.hearthstead.entity.VillageSocial.NONE
            && !entity.blessingReceiveState.isStarted()) {
            // A village moment replaces ordinary idle/work presentation for a
            // few seconds. It is applied last and resets every authored bone
            // so a trade idle cannot add its arms onto a social gesture.
            ModelPart[] socialParts = {rightArm, leftArm, torso, root, rightLeg, leftLeg, head};
            for (ModelPart part : socialParts) resetLimb(part);
            float elapsed = entity.level().getGameTime() - entity.villageSocialStart()
                + ageInTicks - entity.tickCount;
            if (villageSocial == com.hearthstead.entity.VillageSocial.WELCOME) {
                sampleClip(SettlerAnimations.INN_WELCOME, (long) (Math.max(0F, elapsed) * 50F));
            } else if (villageSocial == com.hearthstead.entity.VillageSocial.PAIR) {
                // Tavern lane SocialPair: the shared clock only says WHO holds the floor
                // (turns of 1.5-6 s, occasional cut-ins). Each person runs the clips on
                // their own phase and speed, cross-faded over the hand-over.
                float el = Math.max(0F, elapsed);
                float speak = com.hearthstead.entity.SocialPair.speakWeight(entity.socialPairSlot(), el,
                    entity.socialPairSeed());
                long own = TavernPatronMotion.pairClock(entity, el);
                if (speak > 0.01F) sampleClipWeighted(TavernPatronMotion.PAIR_SPEAKER, own, speak);
                if (speak < 0.99F) sampleClipWeighted(TavernPatronMotion.PAIR_LISTENER, own, 1F - speak);
            } else if (villageSocial == com.hearthstead.entity.VillageSocial.CHAT) {
                sampleClip(SettlerAnimations.VILLAGE_CHAT, (long) (Math.max(0F, elapsed) * 50F));
            } else {
                sampleClip(SettlerAnimations.VILLAGE_LISTEN, (long) (Math.max(0F, elapsed) * 50F));
            }
            if (villageSocial != com.hearthstead.entity.VillageSocial.WELCOME) {
                // Eye contact: the server look control aims at the partner; give most of
                // that yaw back on top of the clip's own small head keys.
                head.yRot += Mth.clamp(netHeadYaw, -60F, 60F) * Mth.DEG_TO_RAD * 0.85F;
                head.xRot += Mth.clamp(headPitch, -30F, 30F) * Mth.DEG_TO_RAD * 0.5F;
                if (villageSocial == com.hearthstead.entity.VillageSocial.PAIR) {
                    // glances away and back, back-channel nods / head tilts (own timers)
                    float[] g = TavernPatronMotion.pairHead(entity, Math.max(0F, elapsed));
                    head.yRot += g[0];
                    head.xRot += g[1];
                    head.zRot += g[2];
                    torso.zRot += g[3];
                }
            }
        }
        // Guard Drill lane: the morning drill (GuardDrillMotion) owns every bone while its cue
        // lives. Reset first like the village socials; the relaxed stance runs under a
        // cross-faded one-shot (cut, parry, evade, breather) on each guard's own clock.
        GuardDrillMotion.Pick drill = villageSocial == com.hearthstead.entity.VillageSocial.NONE
            && !entity.blessingReceiveState.isStarted() ? GuardDrillMotion.pick(entity, ageInTicks) : null;
        if (drill != null) {
            for (ModelPart part : new ModelPart[] {rightArm, leftArm, torso, root, rightLeg, leftLeg, head}) {
                resetLimb(part);
            }
            torso.getChild("cloak").resetPose();
            if (drill.stanceWeight() > 0.001F) {
                sampleClipWeighted(GuardDrillMotion.stance(), drill.stanceMillis(), drill.stanceWeight());
            }
            if (drill.prevWeight() > 0.001F) {
                sampleClipWeighted(drill.prevClip(), drill.prevMillis(), drill.prevWeight());
            }
            if (drill.weight() > 0.001F) {
                sampleClipWeighted(drill.clip(), drill.millis(), drill.weight());
            }
            head.xRot += Mth.clamp(headPitch, -20F, 20F) * Mth.DEG_TO_RAD * 0.4F;
        }
        if (activity == SettlerActivity.PLAYING_MUSIC && !entity.blessingReceiveState.isStarted()
            && villageSocial == com.hearthstead.entity.VillageSocial.NONE) {
            // Tavern evening bard: replaces only arms and head (seated legs and
            // root stay with the chair motion). Mimed instrument, no prop.
            rightArm.visible = true;
            leftArm.visible = true;
            bagRightUpperArm.visible = false;
            bagLeftUpperArm.visible = false;
            resetLimb(rightArm);
            resetLimb(leftArm);
            head.resetPose();
            sampleClip(SettlerAnimations.BARD_PLAY, (long) (ageInTicks * 50F));
        }
        // Tavern lane (TavernPatronMotion, client-only choice): seated patrons on their
        // table's shared clock (cheer with converging mugs, teller + listeners, toast,
        // sleepy), the innkeeper at the tap / counter, the host's careful carry, a jig
        // near the bard. Applied after the social/bard branches, before the seat legs.
        TavernPatronMotion.Pick tavernPick = villageSocial == com.hearthstead.entity.VillageSocial.NONE
            && activity != SettlerActivity.PLAYING_MUSIC
            ? TavernPatronMotion.select(entity, activity, ageInTicks) : null;
        if (tavernPick != null) {
            if (tavernPick.owns() != TavernPatronMotion.Owns.OVERLAY) {
                rightArm.visible = true;
                leftArm.visible = true;
                bagRightUpperArm.visible = false;
                bagLeftUpperArm.visible = false;
                resetLimb(rightArm);
                resetLimb(leftArm);
                torso.resetPose();
                head.resetPose();
                torso.getChild("cloak").resetPose();
                if (tavernPick.owns() == TavernPatronMotion.Owns.FULL) {
                    root.resetPose();
                    resetLimb(rightLeg);
                    resetLimb(leftLeg);
                }
            }
            if (tavernPick.base() != null) {
                // natural gaps: a calm base (seated idle / leaning at the bar) with the act eased
                // in and out over it
                float w = Mth.clamp(tavernPick.weight(), 0F, 1F);
                if (w < 0.999F) sampleClipWeighted(tavernPick.base(), tavernPick.baseMillis(), 1F - w);
                if (w > 0.001F) sampleClipWeighted(tavernPick.def(), tavernPick.millis(), w);
            } else {
                sampleClip(tavernPick.def(), tavernPick.millis());
            }
            torso.yRot += tavernPick.torsoYaw();
            torso.xRot += tavernPick.torsoPitch();
            torso.y -= tavernPick.torsoLift();
            head.yRot += tavernPick.headYaw() * tavernPick.headWeight();
        }
        // Tavern lane: a drunk's server-timed stumble (layered over the walk), or a fall /
        // lean / sit (the whole body; the server holds the settler still meanwhile).
        boolean drunkPose = false;
        int drunkCue = entity.drunkCueMode();
        if (drunkCue != com.hearthstead.entity.Drunkenness.CUE_NONE && !entity.blessingReceiveState.isStarted()
            && !entity.isPassenger()) {
            float el = entity.level().getGameTime() - entity.drunkCueStart() + ageInTicks - entity.tickCount;
            AnimationDefinition cueDef = switch (drunkCue) {
                case com.hearthstead.entity.Drunkenness.CUE_STUMBLE -> TavernMotionAnimations.STUMBLE;
                case com.hearthstead.entity.Drunkenness.CUE_FALL_FORWARD -> TavernMotionAnimations.FALL_FORWARD;
                case com.hearthstead.entity.Drunkenness.CUE_FALL_SIDE -> TavernMotionAnimations.FALL_SIDE;
                case com.hearthstead.entity.Drunkenness.CUE_LEAN -> TavernMotionAnimations.DRUNK_LEAN;
                case com.hearthstead.entity.Drunkenness.CUE_SIT -> TavernMotionAnimations.DRUNK_SIT;
                default -> null;
            };
            float span = drunkCue == com.hearthstead.entity.Drunkenness.CUE_LEAN
                ? com.hearthstead.entity.Drunkenness.LEAN_TICKS : cueDef == null ? 0F : cueDef.lengthInSeconds() * 20F;
            if (cueDef != null && el >= 0F && el < span) {
                if (drunkCue != com.hearthstead.entity.Drunkenness.CUE_STUMBLE) {
                    resetLimb(rightArm);
                    resetLimb(leftArm);
                    resetLimb(rightLeg);
                    resetLimb(leftLeg);
                    torso.resetPose();
                    head.resetPose();
                    root.resetPose();
                    torso.getChild("cloak").resetPose();
                    drunkPose = true;
                }
                sampleClip(cueDef, (long) (el * 50F));
            }
        }
        boolean fishing = profession == Profession.FISHER
            && activity == SettlerActivity.WORK_FISH && entity.getFisherCycleTick() >= 0;
        if (fishing && !entity.blessingReceiveState.isStarted()) {
            applyFishingPose(entity, ageInTicks);
        }
        boolean fisherSeated = fishing && entity.getVehicle() instanceof com.hearthstead.entity.FisherSeatEntity;
        boolean seated = entity.getVehicle() instanceof com.hearthstead.entity.TavernSeatEntity;
        ModelPart seatedRight = root.getChild("seated_right_leg");
        ModelPart seatedLeft = root.getChild("seated_left_leg");
        seatedRight.visible = seated || fisherSeated;
        seatedLeft.visible = seated || fisherSeated;
        if (seated) {
            root.resetPose();
            rightLeg.visible = false;
            leftLeg.visible = false;
            seatedRight.xRot = seatedLeft.xRot = -(float) Math.PI / 2;
            seatedRight.getChild("shin").xRot = seatedLeft.getChild("shin").xRot = (float) Math.PI / 2;
            var vehicle=(com.hearthstead.entity.TavernSeatEntity)entity.getVehicle();
            float partial=Mth.clamp(ageInTicks-entity.tickCount,0,1);
            var frame=vehicle.motionFrame(partial);
            if(frame!=null) {
                applySeatLeg(seatedRight,frame.right(),frame.bend());
                applySeatLeg(seatedLeft,frame.left(),frame.bend());
                Vec3 rendered=new Vec3(Mth.lerp(partial,entity.xo,entity.getX()),Mth.lerp(partial,entity.yo,entity.getY()),Mth.lerp(partial,entity.zo,entity.getZ()));
                Vec3 delta=frame.origin().subtract(rendered);
                double yaw=Math.toRadians(frame.yaw());
                root.x+=(float)((Math.cos(yaw)*delta.x+Math.sin(yaw)*delta.z)*16);
                root.y-=(float)(delta.y*16);
                root.z+=(float)((Math.sin(yaw)*delta.x-Math.cos(yaw)*delta.z)*16);
            }
        } else if (fisherSeated) {
            // Rider origin is chair-base Y. Lower the 12px hip to the 8px seat.
            root.y += 4F;
            rightLeg.visible = leftLeg.visible = false;
            seatedRight.xRot = seatedLeft.xRot = -Mth.HALF_PI;
            seatedRight.getChild("shin").xRot = seatedLeft.getChild("shin").xRot = Mth.HALF_PI;
        } else {
            rightLeg.visible = true;
            leftLeg.visible = true;
        }
        // Subtle heel loading follows the authored chop weight shift. Neither
        // foot turns inward; the bottom edge stays on the same ground plane.
        if (entity.chopState.isStarted() && !entity.gatherState.isStarted()
            && !entity.blessingReceiveState.isStarted()
            && !entity.workContainerDownState.isStarted()
            && !entity.groundItemPickupState.isStarted()
            && !entity.workContainerStowState.isStarted()
            && !entity.workContainerUpState.isStarted()
            && !entity.isPassenger()
            // An authored chop clip plants its own legs (and knees).
            && !(motion.engine() && MotionLibrary.override("settler/chop") != null)) {
            float load = Mth.clamp(torso.zRot * 0.45F, -0.07F, 0.07F);
            applyPlantedHeelRoll(rightLeg, load);
            applyPlantedHeelRoll(leftLeg, -load);
        }
        // A carried frame/bag uses the existing articulated hands. Actual
        // offhand cargo keeps its separate front-carry pose and item attachment.
        boolean carriedFrame = profession == Profession.LUMBERER
            && activity == SettlerActivity.HAULING_LOG && lumberFrame.visible;
        boolean carriedBag = (profession == Profession.COURIER || profession == Profession.TRADER)
            && activity == SettlerActivity.CARRYING && sack.visible
            && entity.getMainHandItem().isEmpty();
        if ((carriedFrame || carriedBag) && entity.getOffhandItem().isEmpty()
            && !climbing && !entity.isPassenger()
            && !entity.blessingReceiveState.isStarted()
            && !entity.workContainerDownState.isStarted()
            && !entity.groundItemPickupState.isStarted()
            && !entity.workContainerStowState.isStarted()
            && !entity.workContainerUpState.isStarted()
            && !entity.bagToChestUnloadState.isStarted()
            && !entity.liftState.isStarted() && !entity.setDownState.isStarted()
            && !entity.pickupState.isStarted() && !entity.gatherState.isStarted()) {
            // Sack volume scales with real fill; palms follow that same mesh.
            double gripX = carriedFrame ? 4.5 : 3.5 * sack.xScale;
            double gripY = carriedFrame ? -5.0 : sack.y + 3.5 * sack.yScale;
            double gripZ = carriedFrame ? 4.5 : sack.z + sack.zScale;
            applyContainerGrip(rightArm, bagRightUpperArm, bagRightForearm,
                new Vec3(-gripX, gripY, gripZ), -1);
            applyContainerGrip(leftArm, bagLeftUpperArm, bagLeftForearm,
                new Vec3(gripX, gripY, gripZ), 1);
        }
        if (com.hearthstead.client.render.HandCartRenderer.isPulling(entity) && !climbing
            && !entity.isPassenger() && !bagTransfer.ownsBodyPose(entity)) {
            // Hand cart: both hands reach back and down to the shaft ends
            // (about 0.45 block behind, hip height, +/-0.31 block out), arms
            // nearly straight; the chest leans into the pull.
            resetLimb(rightArm);
            resetLimb(leftArm);
            rightArm.visible = true;
            leftArm.visible = true;
            bagRightUpperArm.visible = false;
            bagLeftUpperArm.visible = false;
            float sway = Mth.sin(limbSwing * 0.6662F) * 0.04F * Mth.clamp(limbSwingAmount * 2.5F, 0.0F, 1.0F);
            rightArm.xRot = 0.62F + sway;
            leftArm.xRot = 0.62F - sway;
            rightArm.zRot = -0.08F;
            leftArm.zRot = 0.08F;
            rightForearm.xRot = -0.08F;
            leftForearm.xRot = -0.08F;
            torso.xRot += 0.12F;
            head.xRot -= 0.10F;
        }
        boolean travelerStaffPose = TravelerStaffPose.apply(entity, ageInTicks, root, torso, rightArm,
                travelerRightUpper, travelerRightFore, travelerStaff);
        if (travelerStaffPose) {
            travelerPack.visible = true;
            backpack.visible = false;
            // The guest's own travel pack owns the back (the satchel overlapped it).
            packLook = CarryPackRules.none();
        }
        serviceHands = TavernServingHandPose.apply(entity, ageInTicks, root, torso,
            rightArm, leftArm, bagRightUpperArm, bagRightForearm,
            bagLeftUpperArm, bagLeftForearm);
        // Activity cross-fade (pose only, never timing). Every clip above is
        // still sampled from its own untouched clock; this pass only eases
        // the first few ticks after an activity/moving edge from the pose
        // that was actually on screen. Anything anchored to a world object,
        // a seat, a ticketed combat contact or a bed is excluded outright.
        boolean anchoredPose = climbing || seated || fisherSeated
            || tavernPick != null && tavernPick.owns() != TavernPatronMotion.Owns.OVERLAY || drunkPose
            || entity.isPassenger() || travelerStaffPose || serviceHands != 0
            || activity == SettlerActivity.SLEEPING || entity.isSleeping()
            || activity == SettlerActivity.COMBAT || activity == SettlerActivity.FLEEING
            || bagTransfer.ownsBodyPose(entity)
            || entity.workContainerDownState.isStarted()
            || entity.groundItemPickupState.isStarted()
            || entity.workContainerStowState.isStarted()
            || entity.workContainerUpState.isStarted()
            || entity.bagToChestUnloadState.isStarted()
            || entity.liftState.isStarted() || entity.setDownState.isStarted()
            || entity.pickupState.isStarted() || entity.gatherState.isStarted()
            || entity.meleeState.isStarted() || entity.guardFinisherState.isStarted()
            || entity.guardHeavyState.isStarted() || entity.guardShieldBashState.isStarted()
            || entity.guardLightBState.isStarted() || entity.guardStaggerState.isStarted()
            || entity.guardSaluteState.isStarted() || entity.guardSaluteEndState.isStarted()
            || entity.shieldState.isStarted() || entity.huntState.isStarted()
            || entity.archerLooseState.isStarted()
            || ((carriedFrame || carriedBag) && entity.getOffhandItem().isEmpty());
        // Fisher v3: the long rod's grip is set for fishing (60 deg above the
        // forearm); away from the chair it turns in the fist to ride upright
        // like a staff, so a hanging or swinging arm never rakes it through
        // the ground. Only when no clip has keyed the wrist this frame.
        if (profession == Profession.FISHER && !fishing && motion.engine()
            && entity.getMainHandItem().is(com.hearthstead.registry.ModItems.FISHERS_ROD.get())) {
            ModelPart wrist = rightForearm.getChild("right_item");
            if (wrist.xRot == 0.0F && wrist.yRot == 0.0F && wrist.zRot == 0.0F) {
                wrist.xRot = -Mth.HALF_PI;
            }
        }
        // Captain's nod layers over whatever he is doing (legs untouched).
        head.xRot += nodPitch(entity, ageInTicks);
        // Living village: procedural wave/nod/mourn/shiver until the authored
        // overlay clips exist, and the hunch when walking in rain.
        com.hearthstead.client.ambient.AmbientPose.apply(entity, ageInTicks, anchoredPose,
            head, torso, rightArm, leftArm);
        // Conversation gesture / listening overlay (client.motion.MotionOverrides).
        motion.playOverlay(entity, ageInTicks);
        if (motion.engine()) {
            applySecondaryMotion(entity, ageInTicks, anchoredPose || entity.isSleeping()
                || activity == SettlerActivity.SLEEPING, carriedBag || carriedFrame);
        }
        applyMomentum(entity, ageInTicks, limbSwingAmount,
            anchoredPose || activity.name().startsWith("WORK_"));
        applyActivityCrossFade(entity, activity, ageInTicks, anchoredPose);
        applyLegProportions();
        if (bagTransfer.ownsBodyPose(entity) && bagLeftUpperArm.visible) {
            // The hand that carries each unit from sack to chest (left palm in
            // BAG_TO_CHEST_UNLOAD), in model space, for the renderer's item beat.
            Vector3f palm = rootSpacePoint(0, 6, 0, bagLeftForearm, bagLeftUpperArm, torso, root);
            transferHandModel.set(palm);
            transferHandEntity = entity.getId();
        } else if (transferHandEntity == entity.getId()) {
            transferHandEntity = -1;
        }
        QaTravelerPoseCapture.capture(entity, ageInTicks, root);
    }

    /**
     * Leg girth against the 10 x 12 x 5 torso. The 4 x 12 x 4 leg cubes (and
     * their 128x64 UV islands) stay untouched; only the rendered width/depth
     * grows, pivoting at the hip so feet keep the ground plane (yScale = 1).
     * At the +/-2.6 hip pivots a 4.8-wide leg spans 0.2..5.0: flush with the
     * torso sides with a 0.4 crotch gap, so the legs neither overlap each
     * other nor poke past the belt or cloak. Runs last, after every
     * resetPose()/crossfade (1.21.1 loadPose() resets scale to 1), and
     * multiplies so a keyed SCALE channel would still compose.
     */
    private static final float LEG_GIRTH_X = 1.2F;
    private static final float LEG_GIRTH_Z = 1.15F;

    private void applyLegProportions() {
        for (ModelPart leg : new ModelPart[] {rightLeg, leftLeg}) {
            leg.xScale *= LEG_GIRTH_X;
            leg.zScale *= LEG_GIRTH_Z;
        }
        // Seated thighs are pitched -90 degrees, so their local Z is the shin's
        // length axis: widen the thigh on X only and give the shin (and the
        // boot it carries) its own depth, keeping the shin length exact.
        for (String name : new String[] {"seated_right_leg", "seated_left_leg"}) {
            ModelPart thigh = root.getChild(name);
            thigh.xScale *= LEG_GIRTH_X;
            thigh.getChild("shin").zScale *= LEG_GIRTH_Z;
        }
    }

    private Vec3 sourceBagLocal(SettlerEntity actor, Vec3 world, float partial) {
        Vec3 p = TavernServingHandPose.worldToModel(actor, world, partial);
        return inverseSourcePart(inverseSourcePart(p, root), torso);
    }

    private static Vec3 inverseSourcePart(Vec3 point, ModelPart part) {
        Vector3f p = new Vector3f((float) point.x - part.x,
            (float) point.y - part.y, (float) point.z - part.z);
        p.rotateZ(-part.zRot).rotateY(-part.yRot).rotateX(-part.xRot);
        return new Vec3(p.x / part.xScale, p.y / part.yScale, p.z / part.zScale);
    }

    /**
     * Client-only body momentum (pose only, never timing or world state).
     * When the body yaw turns, the torso (and, less, the head) stays facing
     * the old heading for a moment and eases back with an exponential decay;
     * a fast-minus-slow smoothing of gait speed gives a small forward lean
     * while speeding up and a slight back-settle on stopping. The state is
     * bounded (clamped, decaying toward zero) and is reset whenever the pose
     * is anchored, a frame gap/teleport occurs, or the entity is new, so it
     * can never accumulate. Repeated setupAnim calls in one frame (armour
     * layer) see dt == 0 and add nothing.
     */
    private static final float MOMENTUM_YAW_TAU = 3.5F;
    private static final float MOMENTUM_YAW_MAX_DEG = 7.0F;
    private static final float MOMENTUM_FAST_TAU = 1.5F;
    private static final float MOMENTUM_SLOW_TAU = 6.0F;
    private static final float MOMENTUM_LEAN_GAIN = 0.2F;
    private static final float MOMENTUM_LEAN_FWD = 0.07F;
    private static final float MOMENTUM_LEAN_BACK = -0.045F;
    private final java.util.Map<SettlerEntity, Momentum> momenta = new java.util.WeakHashMap<>();

    private static final class Momentum {
        float lastAge = Float.NaN;
        float lastYaw;
        float lagDeg;
        float fastSpeed;
        float slowSpeed;
    }

    private void applyMomentum(SettlerEntity entity, float ageInTicks, float speed, boolean excluded) {
        Momentum m = momenta.computeIfAbsent(entity, e -> new Momentum());
        float partial = Mth.clamp(ageInTicks - entity.tickCount, 0.0F, 1.0F);
        float yaw = Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot);
        float dt = ageInTicks - m.lastAge;
        float delta = Mth.wrapDegrees(yaw - m.lastYaw);
        if (excluded || Float.isNaN(dt) || dt < 0.0F || dt > 3.0F
            || Math.abs(delta) > 45.0F || !Float.isFinite(speed)) {
            m.lagDeg = 0.0F;
            m.fastSpeed = m.slowSpeed = Float.isFinite(speed) ? speed : 0.0F;
            m.lastYaw = yaw;
            m.lastAge = ageInTicks;
            return;
        }
        if (dt > 0.0F) {
            m.lagDeg = Mth.clamp((m.lagDeg - delta) * (float) Math.exp(-dt / MOMENTUM_YAW_TAU),
                -MOMENTUM_YAW_MAX_DEG, MOMENTUM_YAW_MAX_DEG);
            m.fastSpeed += (speed - m.fastSpeed) * (1.0F - (float) Math.exp(-dt / MOMENTUM_FAST_TAU));
            m.slowSpeed += (speed - m.slowSpeed) * (1.0F - (float) Math.exp(-dt / MOMENTUM_SLOW_TAU));
            m.lastYaw = yaw;
            m.lastAge = ageInTicks;
        }
        float lean = Mth.clamp((m.fastSpeed - m.slowSpeed) * MOMENTUM_LEAN_GAIN,
            MOMENTUM_LEAN_BACK, MOMENTUM_LEAN_FWD);
        float lag = m.lagDeg * Mth.DEG_TO_RAD;
        // Torso trails the hips; the head (a torso child) partly re-aims so
        // the gaze leads the turn slightly ahead of the chest.
        torso.yRot += lag * 0.7F;
        head.yRot -= lag * 0.25F;
        torso.xRot += lean;
        head.xRot -= lean * 0.5F;
    }

    /** Ticks for an activity-edge cross-fade into idle/locomotion/social. */
    private static final float CROSS_FADE_TICKS = 5.0F;
    /** Work loops ease in faster so the first synced contact (earliest is
     *  LIMB_BRANCHES at tick 6) is always sampled at full clip weight. */
    private static final float CROSS_FADE_WORK_TICKS = 4.0F;
    private static final int CROSS_FADE_BONES = 12;
    private static final int CROSS_FADE_STRIDE = 6;
    private final java.util.Map<SettlerEntity, CrossFade> crossFades = new java.util.WeakHashMap<>();

    private static final class CrossFade {
        final float[] shown = new float[CROSS_FADE_BONES * CROSS_FADE_STRIDE];
        final float[] from = new float[CROSS_FADE_BONES * CROSS_FADE_STRIDE];
        int signature = Integer.MIN_VALUE;
        float shownAge = Float.NaN;
        float startAge = Float.NEGATIVE_INFINITY;
        float duration = CROSS_FADE_TICKS;
        boolean shownAnchored = true;
        boolean moving;
    }

    /** Cached bone list for the cross-fade (no per-frame array). */
    private ModelPart[] crossFadeParts;

    private void applyActivityCrossFade(SettlerEntity entity, SettlerActivity activity,
                                        float ageInTicks, boolean anchored) {
        ModelPart[] parts = crossFadeParts;
        if (parts == null) {
            parts = crossFadeParts = new ModelPart[] {root, torso, head, rightArm, leftArm, rightLeg,
                leftLeg, torso.getChild("cloak"), rightForearm, leftForearm, rightShin, leftShin};
        }
        CrossFade fade = crossFades.computeIfAbsent(entity, e -> new CrossFade());
        // Hysteresis so a settler easing to a stop does not flicker between
        // the moving and standing signatures (each flip would start a fade).
        boolean moving = entity.walkAnimation.speed() > (fade.moving ? 0.03F : 0.07F);
        fade.moving = moving;
        // a battle-role strike (absolute, reset first) eases in/out like a ceremony
        boolean ceremony = entity.guardSaluteState.isStarted() || entity.guardSaluteEndState.isStarted()
            || entity.roleMoveState.isStarted();
        int signature = activity.ordinal() * 4 + (moving ? 1 : 0) + (ceremony ? 2 : 0);
        float sinceShown = ageInTicks - fade.shownAge;
        boolean continuous = sinceShown >= 0.0F && sinceShown <= 3.0F;
        if (signature != fade.signature) {
            boolean firstSeen = fade.signature == Integer.MIN_VALUE;
            fade.signature = signature;
            if (!firstSeen && continuous && !anchored && !fade.shownAnchored) {
                System.arraycopy(fade.shown, 0, fade.from, 0, fade.from.length);
                fade.startAge = ageInTicks;
                // Sized to the change: a small shift settles in ~3 ticks, a
                // whole-body change (stand -> kneel) takes up to 10. Work loops
                // stay within 4 so the first synced contact is at full weight.
                float change = 0.0F;
                for (int i = 0; i < CROSS_FADE_BONES; i++) {
                    ModelPart part = parts[i];
                    int o = i * CROSS_FADE_STRIDE;
                    change = Math.max(change, Math.abs(part.xRot - fade.from[o + 3]));
                    change = Math.max(change, Math.abs(part.yRot - fade.from[o + 4]));
                    change = Math.max(change, Math.abs(part.zRot - fade.from[o + 5]));
                    change = Math.max(change, (Math.abs(part.x - fade.from[o]) + Math.abs(part.y - fade.from[o + 1])
                        + Math.abs(part.z - fade.from[o + 2])) / 8.0F);
                }
                fade.duration = activity.name().startsWith("WORK_")
                    ? Mth.clamp(CROSS_FADE_WORK_TICKS * (0.6F + change), 2.5F, CROSS_FADE_WORK_TICKS)
                    : Mth.clamp(CROSS_FADE_TICKS * (0.6F + change), 3.0F, 10.0F);
            } else {
                fade.startAge = Float.NEGATIVE_INFINITY;
            }
        }
        if (anchored || !continuous && sinceShown > 0.0F) {
            fade.startAge = Float.NEGATIVE_INFINITY;
        }
        float progress = (ageInTicks - fade.startAge) / fade.duration;
        if (progress >= 0.0F && progress < 1.0F) {
            // Quintic smootherstep: zero velocity AND acceleration at both ends.
            float w = progress * progress * progress * (progress * (progress * 6.0F - 15.0F) + 10.0F);
            for (int i = 0; i < CROSS_FADE_BONES; i++) {
                ModelPart part = parts[i];
                int o = i * CROSS_FADE_STRIDE;
                part.x = Mth.lerp(w, fade.from[o], part.x);
                part.y = Mth.lerp(w, fade.from[o + 1], part.y);
                part.z = Mth.lerp(w, fade.from[o + 2], part.z);
                part.xRot = Mth.lerp(w, fade.from[o + 3], part.xRot);
                part.yRot = Mth.lerp(w, fade.from[o + 4], part.yRot);
                part.zRot = Mth.lerp(w, fade.from[o + 5], part.zRot);
            }
        }
        for (int i = 0; i < CROSS_FADE_BONES; i++) {
            ModelPart part = parts[i];
            int o = i * CROSS_FADE_STRIDE;
            fade.shown[o] = part.x;
            fade.shown[o + 1] = part.y;
            fade.shown[o + 2] = part.z;
            fade.shown[o + 3] = part.xRot;
            fade.shown[o + 4] = part.yRot;
            fade.shown[o + 5] = part.zRot;
        }
        fade.shownAge = ageInTicks;
        fade.shownAnchored = anchored;
    }

    private static void applyPlantedHeelRoll(ModelPart leg, float pitch) {
        leg.xRot = pitch;
        leg.yRot = 0;
        leg.zRot = 0;
        leg.y += 12.0F * (1.0F - Mth.cos(pitch)) - 2.0F * Math.abs(Mth.sin(pitch));
        leg.z -= 12.0F * Mth.sin(pitch);
    }

    private static void applyContainerGrip(ModelPart rigid, ModelPart upper,
                                           ModelPart fore, Vec3 palm, int side) {
        Vec3 shoulder = new Vec3(rigid.x, rigid.y, rigid.z);
        var solution = TavernServingHandPose.solve(shoulder, palm, side);
        if (solution == null) return;
        upper.resetPose();
        fore.resetPose();
        Vec3 direction = solution.elbow().subtract(shoulder);
        upper.xRot = (float) Math.atan2(direction.z, Math.hypot(direction.x, direction.y));
        upper.zRot = (float) Math.atan2(-direction.x, direction.y);
        Vec3 lower = palm.subtract(solution.elbow());
        double c = Math.cos(-upper.zRot), sn = Math.sin(-upper.zRot);
        double x = lower.x * c - lower.y * sn;
        double y = lower.x * sn + lower.y * c;
        c = Math.cos(-upper.xRot); sn = Math.sin(-upper.xRot);
        double z = y * sn + lower.z * c;
        y = y * c - lower.z * sn;
        fore.xRot = (float) Math.atan2(z, Math.hypot(x, y));
        fore.zRot = (float) Math.atan2(-x, y);
        rigid.visible = false;
        upper.visible = true;
    }

    /**
     * Uses the vanilla, server-synced use-item clock to pull the real bow into
     * the canonical bow-and-arrow pose. EV_ARCHER_LOOSE then lowers both arms
     * from that exact pose over 0.40 s, so arrow spawn and visible release can
     * never be authored by two unrelated timers.
     */
    /** ARCHER_DRAW reaches the right-jaw anchor at 0.90 s: 13.5 ticks on this clock, so a draw shortened by
     *  drill / tech / Focus still looses from the anchor; longer draws (20, Power Shot 45) hold the aim. */
    private static final float ARCHER_DRAW_CLOCK_TICKS = 15.0F;
    /** ARCHER_RELOAD length (= ArcherAttackGoal's 15 recovery ticks). */
    private static final long ARCHER_RELOAD_MS = 750L;

    /**
     * Anim lane (owner-approved v5 cycle, 26 Sep): the authored ARCHER_DRAW sampled on the server-synced
     * use-item clock and ARCHER_RELOAD (quiver fetch, nock at 0.66 s) on EV_ARCHER_LOOSE -- the same two
     * physical clocks applyBowMotion used, so arrow spawn and visible release still share one authority.
     * The bow is drawn by SettlerBowHold's v5 longbow holds, solved on these clips' arm poses. Absolute
     * full-body clips; an archer on the move keeps its gait legs and root.
     */
    private void applyArcherClips(SettlerEntity entity, float ageInTicks) {
        entity.archerLooseState.updateTime(ageInTicks, 1.0F);
        boolean drawing = entity.isUsingItem() && entity.getUsedItemHand() == InteractionHand.MAIN_HAND;
        AnimationDefinition clip = null;
        float seconds = 0.0F;
        if (drawing) {
            float partialTick = Mth.clamp(ageInTicks - entity.tickCount, 0.0F, 1.0F);
            seconds = Math.min(1.0F, (entity.getTicksUsingItem() + partialTick) / ARCHER_DRAW_CLOCK_TICKS);
            clip = ArcherMotionAnimations.ARCHER_DRAW;
        } else if (entity.archerLooseState.isStarted()
                && entity.archerLooseState.getAccumulatedTime() < ARCHER_RELOAD_MS) {
            seconds = entity.archerLooseState.getAccumulatedTime() / 1000.0F;
            clip = ArcherMotionAnimations.ARCHER_RELOAD;
        }
        if (clip == null) {
            return;
        }
        boolean moving = entity.walkAnimation.speed() > 0.05F;
        PartPose rootPose = root.storePose();
        PartPose rightLegPose = rightLeg.storePose();
        PartPose leftLegPose = leftLeg.storePose();
        PartPose rightShinPose = rightShin.storePose();
        PartPose leftShinPose = leftShin.storePose();
        head.resetPose();
        torso.resetPose();
        torso.getChild("cloak").resetPose();
        resetLimb(rightArm);
        resetLimb(leftArm);
        if (!moving) {
            root.resetPose();
            resetLimb(rightLeg);
            resetLimb(leftLeg);
        }
        sampleClip(clip, (long) (seconds * 1000.0F));
        if (moving) {
            root.loadPose(rootPose);
            rightLeg.loadPose(rightLegPose);
            leftLeg.loadPose(leftLegPose);
            rightShin.loadPose(rightShinPose);
            leftShin.loadPose(leftShinPose);
        }
    }

    private void applyBowMotion(SettlerEntity entity,
                                      float netHeadYaw, float headPitch, float drawDurationTicks, float ageInTicks) {
        float blend = 0.0F;
        boolean drawing = entity.isUsingItem()
            && entity.getUsedItemHand() == InteractionHand.MAIN_HAND;
        // setupAnimationStates advances whole client ticks, while this partial-
        // tick update keeps the 400 ms release blend smooth when rendered.
        entity.archerLooseState.updateTime(ageInTicks, 1.0F);
        if (entity.archerLooseState.isStarted()) {
            // The successful-shot event can precede the synced item-use clear.
            float release = Mth.clamp(
                entity.archerLooseState.getAccumulatedTime() / 400.0F,
                0.0F, 1.0F);
            float eased = release * release * (3.0F - 2.0F * release);
            blend = 1.0F - eased;
        } else if (drawing) {
            float partialTick = Mth.clamp(ageInTicks - entity.tickCount, 0.0F, 1.0F);
            float draw = Mth.clamp((entity.getTicksUsingItem() + partialTick) / drawDurationTicks,
                0.0F, 1.0F);
            blend = draw * draw * (3.0F - 2.0F * draw);
        }
        if (blend <= 0.0F) {
            return;
        }

        float aimPitch = headPitch * ((float) Math.PI / 180.0F);
        float aimYaw = netHeadYaw * ((float) Math.PI / 180.0F);
        // Both professions use torso-child arms. ARCHER_STANCE also turns
        // the torso, so applying body-relative head aim unchanged adds its
        // yaw/pitch a second time. Preserve the existing Hunter compensation
        // for Archer too; physical release and lower-body motion stay unchanged.
        aimYaw -= torso.yRot;
        aimPitch -= torso.xRot;
        float bowArmX = -Mth.HALF_PI + aimPitch;
        float bowArmY = -0.10F + aimYaw;
        float stringArmX = -Mth.HALF_PI + aimPitch;
        float stringArmY = 0.50F + aimYaw;

        rightArm.xRot = Mth.lerp(blend, rightArm.xRot, bowArmX);
        rightArm.yRot = Mth.lerp(blend, rightArm.yRot, bowArmY);
        rightArm.zRot = Mth.lerp(blend, rightArm.zRot, 0.0F);
        leftArm.xRot = Mth.lerp(blend, leftArm.xRot, stringArmX);
        leftArm.yRot = Mth.lerp(blend, leftArm.yRot, stringArmY);
        leftArm.zRot = Mth.lerp(blend, leftArm.zRot, 0.0F);
        if (motion.engine()) {
            // A real draw: the bow arm locks out straight while the string
            // hand folds back past the cheek -- the elbow closes sideways
            // (forearm Z) instead of both arms pointing forward like a
            // vanilla skeleton. Weighted by the same synced draw blend.
            resetLimb(rightForearm);
            leftForearm.resetPose();
            leftArm.xRot -= 0.12F * blend;
            leftArm.yRot -= 0.12F * blend;
            leftForearm.zRot = MotionTuning.BOW_DRAW_FOLD * blend;
            leftForearm.xRot = -0.18F * blend;
            rightForearm.xRot = -0.04F * blend;
        }
    }

    /**
     * Makes the physical load readable without opening a screen. Couriers and
     * produce-laden farmers use a compact canvas parcel; lumberers use a rigid
     * timber frame whose visible log count follows {@code visualCarryFraction()}.
     * The attached and placed forms are separate model branches so ownership
     * never becomes ambiguous.
     */
    private static void applySeatLeg(ModelPart thigh, com.hearthstead.entity.TavernSeatMotion.Leg pose, float bend) {
        thigh.z=2*(1-bend); thigh.xRot=pose.x(); thigh.yRot=pose.y(); thigh.zRot=pose.z();
        ModelPart shin=thigh.getChild("shin"), boot=shin.getChild("boot");
        shin.xRot=pose.shinX(); shin.yRot=pose.shinY(); shin.zRot=pose.shinZ();
        boot.xRot=pose.bootX(); boot.yRot=pose.bootY(); boot.zRot=pose.bootZ();
    }
    private static void addSeatedLeg(PartDefinition root, String name, float x, int u, boolean mirrored) {
        CubeListBuilder thigh = CubeListBuilder.create().texOffs(u, 32);
        CubeListBuilder shin = CubeListBuilder.create().texOffs(u, 36);
        if (mirrored) { thigh.mirror(); shin.mirror(); }
        PartDefinition leg = root.addOrReplaceChild(name,
            thigh.addBox(-2, 0, -4, 4, 6, 4), PartPose.offset(x, -12, 0));
        PartDefinition calf=leg.addOrReplaceChild("shin", shin.addBox(-2, 0, -4, 4, 6, 4), PartPose.offset(0, 4, 0));
        CubeListBuilder boot=CubeListBuilder.create().texOffs(u,42);
        if(mirrored) boot.mirror();
        calf.addOrReplaceChild("boot",boot.addBox(-2,0,-4,4,2,4),PartPose.offset(0,6,0));
    }

    private final Vector3f bagTransferAnimationScratch = new Vector3f();
    private boolean stowedFrame;

    /**
     * Carry pack lane (26 Sep): the settler has taken the back container off --
     * lying in bed, seated on a tavern chair or a fisher's chair (both have a
     * backrest the pack went through), or with a carcass across the shoulders
     * (CarcassCarryLayer owns the back). Kept on for everything else.
     */
    static boolean packOffBack(SettlerEntity entity) {
        SettlerActivity activity = entity.getActivity();
        return entity.isSleeping() || activity == SettlerActivity.SLEEPING
            || entity.getVehicle() instanceof com.hearthstead.entity.TavernSeatEntity
            || entity.getVehicle() instanceof com.hearthstead.entity.FisherSeatEntity
            || activity == SettlerActivity.HAULING_CARCASS || entity.carcassCarryState.isStarted();
    }
    /** This frame's job pack (CarryPackRules), read by CarryPackLayer right after setupAnim. */
    private CarryPackRules.Look packLook = CarryPackRules.look(Profession.COURIER, 0, 1, false, false, false);
    /** True when the job pack is drawn at the placed ground container instead of the back. */
    private boolean packOnGround;

    private void applyWorkContainer(SettlerEntity entity, float ageInTicks) {
        float fill = entity.visualCarryFraction();
        WorkContainerKind kind = entity.placedWorkContainerKind();
        var placed = entity.placedWorkContainerPos();
        Profession profession = entity.getProfession();
        var transfer = entity.bagTransferPresentation();
        // Every job that unloads through the shared BAG_TO_CHEST_UNLOAD clip
        // (Courier/Trader, Farmer GroundedBagUnload, Lumberer camp unload)
        // owns the same synchronized transfer clock. Each therefore draws the
        // one moving duplicate during its shoulder handoffs (ticks 0-12 and
        // 64-80). Before this, only Courier/Trader did: the farmer's sack
        // vanished at the strap release and the lumber frame popped from the
        // back straight to the floor at tick 12 and back again at tick 80.
        boolean courierTransfer = transfer.ownsBodyPose(entity);
        if (courierTransfer && placed == null) placed = transfer.bagAnchor();
        boolean detached = kind == WorkContainerKind.SACK && placed != null;
        detached |= courierTransfer && placed != null;
        boolean lumberer = profession == Profession.LUMBERER;
        boolean courier = (profession == Profession.COURIER || profession == Profession.TRADER);
        SettlerActivity carryActivity = entity.getActivity();
        // Farm produce and the fisher's catch are real bag loads. Keep the
        // canvas sack on the back both while walking (CARRYING) and while
        // standing at the storage/rack (SORTING) so the laden walk never
        // shows an empty back and the sack never blinks out at arrival.
        boolean farmerCarrying = (profession == Profession.FARMER
                || profession == Profession.FISHER)
            && (carryActivity == SettlerActivity.CARRYING
                || carryActivity == SettlerActivity.SORTING)
            && fill > 0.001F;
        // Carry pack lane: the pack comes off in bed, on a seat and under a
        // shouldered carcass -- for every job, authored sack/frame included.
        boolean offBack = packOffBack(entity);

        // Physical contract: an attached container is a direct torso child;
        // a detached one is a root child projected from the persisted world
        // anchor. There is no stride-phase rotation or spring offset anywhere
        // in this path, so neither silhouette can follow a frame behind.
        lumberFrame.visible = lumberer && !detached && !offBack;
        // An EMPTY carrying frame left on the back while swinging an axe reads
        // as a loose brown arch beside the shoulder once the chest twists into
        // the cut. On the engine, an unladen frame is stowed while felling or
        // limbing; any load keeps it visible (the logs are real carried cargo).
        if (lumberFrame.visible && motion.engine() && fill <= 0.001F
            && (entity.chopState.isStarted() || entity.limbState.isStarted())) {
            lumberFrame.visible = false;
            stowedFrame = true;
        } else {
            stowedFrame = false;
        }
        sack.visible = (courier || farmerCarrying) && !detached && !offBack
            // A hitched hand cart carries the load; the back is free.
            && !com.hearthstead.client.render.HandCartRenderer.isPulling(entity);
        groundLumberFrame.visible = lumberer && detached;
        groundSack.visible = detached && !groundLumberFrame.visible;
        backpack.visible = !lumberFrame.visible && !sack.visible
            && !groundLumberFrame.visible && !groundSack.visible && !stowedFrame && !offBack;
        archerQuiver.visible = backpack.visible && profession == Profession.ARCHER;
        backpack.visible = backpack.visible && !archerQuiver.visible;

        // Every other job that moves goods: a job-flavoured container that
        // grows with the bag (CarryPackRules; drawn by CarryPackLayer in the
        // carried sack's frame). The generic backpack is retired for them.
        boolean governed = CarryPackRules.governs(profession);
        packOnGround = false;
        if (governed) {
            boolean pulling = com.hearthstead.client.render.HandCartRenderer.isPulling(entity);
            // Carry pack lane (26 Sep): tools/blender/pipeline/pack_audit.py swept every
            // work clip (arms, forearms and the held tool head) against every job pack. Only
            // the mason's chisel-and-mallet backswing reaches the frame; every other work
            // keeps its pack on, so it no longer blinks off and on at each work start/stop.
            boolean swinging = entity.chiselState.isStarted();
            packLook = CarryPackRules.look(profession, fill, entity.sackVisualScale(),
                pulling, detached, swinging, offBack);
            sack.visible = false;
            backpack.visible = false;
            if (packLook.visible() || (fill > 0.001F && !detached && !pulling)) {
                // A job container or a stowed one replaces the quiver slot too.
                archerQuiver.visible = archerQuiver.visible && !packLook.visible();
            }
            if (packLook.visible()) {
                float s = packLook.scale();
                sack.xScale = CarryPackRules.lateralScale(packLook.shape(), s);
                // Height stops at the cap (a tier-3 pack hung into the thighs); depth grows.
                sack.yScale = CarryPackRules.verticalScale(s);
                sack.zScale = s;
                torso.xRot += packLook.lean();
                head.xRot -= packLook.lean() * 0.6F;
            }
            // The placed work container takes the job's look as well.
            if (groundSack.visible && CarryPackRules.styleFor(profession).shape()
                    != CarryPackRules.Shape.SACK) {
                packOnGround = true;
            }
        } else {
            packLook = CarryPackRules.look(profession, 0.0F, 1.0F, false, false, false);
        }

        setLumberLoadVisibility(lumberFrame.visible, lumberLogLeft,
            lumberLogCenter, lumberLogRight, fill);
        setLumberLoadVisibility(groundLumberFrame.visible, groundLumberLogLeft,
            groundLumberLogCenter, groundLumberLogRight, fill);

        float courierSize = (COURIER_PACK_MIN_SCALE
            + (COURIER_PACK_MAX_SCALE - COURIER_PACK_MIN_SCALE) * fill)
            * entity.sackVisualScale();   // Satchel / Leather Pack / Frame Pack tiers
        if (!governed) {
            // Same clearance caps as every job pack: a tier-3 courier sack was 10.6 px
            // wide (arms through its sides) and 12 px tall (thighs through its base).
            sack.xScale = CarryPackRules.lateralScale(CarryPackRules.Shape.SACK, courierSize);
            sack.yScale = CarryPackRules.verticalScale(courierSize);
            sack.zScale = courierSize;
        }

        if (!governed && (lumberFrame.visible || sack.visible)) {
            // The weight belongs in the carrier's spine, never in a delayed
            // prop transform. HumanoidModel uses positive X for the vanilla
            // crouch/forward hinge. The old negative sign arched the worker
            // backward into the load in the 2026-08-28 live capture.
            float loadLean = (courier ? COURIER_LOAD_MAX_LEAN : LOAD_MAX_LEAN) * fill;
            torso.xRot += loadLean;
            head.xRot -= loadLean * 0.6F;
        }

        ModelPart groundContainer = groundLumberFrame.visible
            ? groundLumberFrame : groundSack;
        if (!groundContainer.visible) {
            return;
        }
        float groundScale = groundLumberFrame.visible ? 1.0F : courierSize;
        boolean groundIsSack = !groundLumberFrame.visible;
        // The placed sack keeps the carried silhouette (same caps), so the
        // shoulder handoff never swaps sizes mid-air. Lumber scale is 1.
        groundContainer.xScale = groundIsSack
            ? CarryPackRules.lateralScale(CarryPackRules.Shape.SACK, groundScale) : groundScale;
        groundContainer.yScale = groundIsSack ? CarryPackRules.verticalScale(groundScale) : groundScale;
        groundContainer.zScale = groundScale;

        // Convert the server-authored world anchor into this entity model's
        // current yaw-relative root space. LivingEntityRenderer applies both
        // (180-bodyYaw) and the vanilla (-X,-Y,+Z) model mirror. The mirror is
        // part of the inverse: omitting it reflects the anchor around the
        // worker and makes a placed sack orbit when body yaw changes.
        float partial = Mth.clamp(ageInTicks - entity.tickCount, 0.0F, 1.0F);
        double entityX = Mth.lerp(partial, entity.xo, entity.getX());
        double entityY = Mth.lerp(partial, entity.yo, entity.getY());
        double entityZ = Mth.lerp(partial, entity.zo, entity.getZ());
        float bodyYaw = Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot);
        double dx = placed.getX() + 0.5 - entityX;
        double dz = placed.getZ() + 0.5 - entityZ;
        if (transfer.active() && placed.equals(transfer.bagAnchor())) {
            // The unload anchor is the block the worker stands on. Draw the
            // grounded sack/frame at the reviewed forward-left spot instead of
            // through both legs; the renderer's item flight and the source
            // pickup reach use the same world point.
            Vec3 sackPoint = transfer.visualSackPoint();
            dx = sackPoint.x - entityX;
            dz = sackPoint.z - entityZ;
        }
        WorldAnchoredContainerTransform fixed = worldAnchoredContainerTransform(
            dx, dz, bodyYaw);
        float fixedX = fixed.xPixels();
        float fixedZ = fixed.zPixels();
        // The canvas mesh ends eight pixels below its pivot. Keep its sole on
        // the anchor while cargo changes its scale, instead of letting a light
        // sack hover and a full sack sink through the floor. Lumber is unchanged.
        float bottomOffset = groundIsSack ? 8.0F * groundContainer.yScale : 8.0F;
        float fixedY = (float) (entityY - placed.getY()) * 16.0F - bottomOffset;

        // Root crouches are body motion, not sack motion. Cancel the root's
        // transient offsets so the detached prop does not bob when knees bend.
        fixedX -= root.x;
        fixedY -= root.y - 24.0F;
        fixedZ -= root.z;

        // During put-down / lift, the root-owned duplicate follows one
        // monotonic shoulder <-> persisted-world path. The arms were authored
        // against this path so both palms remain visibly on the frame; there
        // is no intermediate hand anchor that can send the frame back toward
        // the torso or teleport it between ownership points. The fixed anchor
        // is authoritative before/after contact, reload and interruption.
        float targetX = fixedX;
        float targetY = fixedY;
        float targetZ = fixedZ;
        float courierGroundBlend = 1.0F;
        boolean lowering = entity.workContainerDownState.isStarted();
        boolean lifting = entity.workContainerUpState.isStarted();
        if (courierTransfer) {
            float clock = Mth.clamp(transfer.clock() + partial, 0.0F, 80.0F);
            courierGroundBlend = clock < 12.0F ? smoothStep(clock / 12.0F)
                : clock >= 64.0F ? 1.0F - smoothStep((clock - 64.0F) / 16.0F)
                : 1.0F;
            Vector3f shoulder = rootSpacePoint(0.0F, -10.5F,
                groundLumberFrame.visible ? 4.0F : 2.5F, torso);
            boolean handCarried = motion.engine() && bagLeftUpperArm.visible
                && (clock < 12.0F || clock >= 64.0F)
                && MotionLibrary.override("settler/bag_to_chest_unload") != null;
            if (handCarried) {
                // Authored one-handed carry (bag animator, 2026-09-26): the left
                // fist holds the sack neck over the left shoulder, out past the
                // arm, down to the forward-left spot, and the reverse for the
                // heft -- the straight shoulder line ran through the shoulder.
                Vector3f palm = rootSpacePoint(0.0F, 6.0F, 0.0F, bagLeftForearm, bagLeftUpperArm, torso);
                float gs = groundScale;
                float handX = palm.x;
                float handY = palm.y + 0.3F * gs;
                float handZ = palm.z - 3.0F * gs;
                float pathX, pathY, pathZ;
                if (clock < 12.0F) {
                    float land = smoothStep((clock - 10.0F) / 2.0F);
                    pathX = Mth.lerp(land, handX, fixedX);
                    pathY = Mth.lerp(land, handY, fixedY);
                    pathZ = Mth.lerp(land, handZ, fixedZ);
                    courierGroundBlend = smoothStep((clock - 2.5F) / 2.5F);
                } else {
                    float grab = smoothStep((clock - 64.0F) / 2.0F);
                    pathX = Mth.lerp(grab, fixedX, handX);
                    pathY = Mth.lerp(grab, fixedY, handY);
                    pathZ = Mth.lerp(grab, fixedZ, handZ);
                    float wide = smoothStep((clock - 73.5F) / 2.0F);
                    pathX = Mth.lerp(wide, pathX, 11.5F);
                    pathY = Mth.lerp(wide, pathY, -0.5F);
                    pathZ = Mth.lerp(wide, pathZ, 1.0F);
                    courierGroundBlend = 1.0F - smoothStep((clock - 75.0F) / 4.5F);
                }
                targetX = Mth.lerp(courierGroundBlend, shoulder.x, pathX);
                targetY = Mth.lerp(courierGroundBlend, shoulder.y, pathY);
                targetZ = Mth.lerp(courierGroundBlend, shoulder.z, pathZ);
            } else {
                targetX = Mth.lerp(courierGroundBlend, shoulder.x, fixedX);
                targetY = Mth.lerp(courierGroundBlend, shoulder.y, fixedY);
                targetZ = Mth.lerp(courierGroundBlend, shoulder.z, fixedZ);
            }
        } else if (lowering || lifting) {
            // Only transition frames need the shoulder point. The long fixed
            // collection phase stays allocation-free in the render loop.
            Vector3f shoulder = rootSpacePoint(0.0F, -10.5F,
                groundLumberFrame.visible ? 4.0F : 2.5F, torso);
            if (lowering) {
                float time = entity.workContainerDownState.getAccumulatedTime() / 1000.0F;
                if (time <= 0.25F) {
                    targetX = shoulder.x;
                    targetY = shoulder.y;
                    targetZ = shoulder.z;
                } else if (time < 1.00F && motion.engine()
                        && MotionLibrary.override("settler/work_container_down") != null) {
                    // The authored clip swings the container round the left
                    // hip in the LEFT hand (it owns the load from 0.32 s): the
                    // prop rides that palm and settles onto its fixed anchor
                    // over the last 0.15 s. Sack: palm on its left side;
                    // frame: palm on its left rail grip.
                    Vector3f lp = rootSpacePoint(0, 6, 0, leftForearm, leftArm, torso);
                    float s = groundScale;
                    float gx = groundIsSack ? 4.1F : 4.25F;
                    float gy = groundIsSack
                        ? 6.0F + (2.6F - 6.0F) * smoothStep((time - 0.40F) / 0.12F) : 9.5F;
                    float gz = groundIsSack ? 3.0F : 3.5F;
                    float handX = lp.x - gx * s;
                    float handY = lp.y - gy * s;
                    float handZ = lp.z - gz * s;
                    float settle = smoothStep((time - 0.85F) / 0.15F);
                    float enter = smoothStep((time - 0.25F) / 0.07F);
                    targetX = Mth.lerp(settle, Mth.lerp(enter, shoulder.x, handX), fixedX);
                    targetY = Mth.lerp(settle, Mth.lerp(enter, shoulder.y, handY), fixedY);
                    targetZ = Mth.lerp(settle, Mth.lerp(enter, shoulder.z, handZ), fixedZ);
                } else if (time < 1.00F) {
                    float descent = smoothStep((time - 0.25F) / 0.75F);
                    targetX = Mth.lerp(descent, shoulder.x, fixedX);
                    targetY = Mth.lerp(descent, shoulder.y, fixedY);
                    targetZ = Mth.lerp(descent, shoulder.z, fixedZ);
                }
            } else {
                float time = entity.workContainerUpState.getAccumulatedTime() / 1000.0F;
                if (time < 0.60F) {
                    // Stay at the fixed world anchor until grip contact.
                } else if (time < 1.35F) {
                    float ascent = smoothStep((time - 0.60F) / 0.75F);
                    targetX = Mth.lerp(ascent, fixedX, shoulder.x);
                    targetY = Mth.lerp(ascent, fixedY, shoulder.y);
                    targetZ = Mth.lerp(ascent, fixedZ, shoulder.z);
                } else {
                    targetX = shoulder.x;
                    targetY = shoulder.y;
                    targetZ = shoulder.z;
                }
            }
        }
        groundContainer.x = targetX;
        groundContainer.y = targetY;
        groundContainer.z = targetZ;
        // A grounded sack stands upright on its sole for every job; the old
        // 0.08 rad farmer tilt pushed one edge of the sole into the floor.
        groundContainer.xRot = 0.0F;
        // Keep the detached prop on one fixed world heading as well as one
        // fixed world position. Without this inverse child yaw the centre can
        // be correct while the sack/frame still spins with its former owner.
        float authoredWorldYaw = 0.0F;
        if (transfer.active() && transfer.bagAnchor() != null
            && transfer.bagAnchor().equals(placed)) {
            authoredWorldYaw = (float) Math.toRadians(transfer.bagYaw());
        }
        groundContainer.yRot = fixed.yRotRadians() + authoredWorldYaw;
        if (courierTransfer) {
            // Match the attached mesh at both shoulder endpoints; the planted
            // middle keeps its persisted world heading while the actor turns.
            groundContainer.xRot = Mth.lerp(courierGroundBlend, torso.xRot, 0.0F);
            groundContainer.yRot = torso.yRot + Mth.wrapDegrees(
                (groundContainer.yRot - torso.yRot) * Mth.RAD_TO_DEG)
                * Mth.DEG_TO_RAD * courierGroundBlend;
            groundContainer.zRot = torso.zRot * (1.0F - courierGroundBlend);
        }
        if (packOnGround) {
            // Positioned above; CarryPackLayer draws the job's container here instead.
            groundSack.visible = false;
        }
    }

    /**
     * Inverse of LivingEntityRenderer's yaw followed by its vanilla X mirror.
     * Package-private so a pure client unit test can prove the world-space
     * round trip at every cardinal/intercardinal yaw without a running game.
     *
     * <p>The ground meshes are authored from z=0 forward. A constant +3 model
     * pixel world pivot offset keeps their 6-pixel depth centred on the block
     * anchor. It is applied before the inverse so that centring cannot rotate
     * with the worker.</p>
     */
    static WorldAnchoredContainerTransform worldAnchoredContainerTransform(
            double worldDx, double worldDz, float bodyYawDegrees) {
        double rendererYaw = Math.toRadians(180.0D - bodyYawDegrees);
        double cos = Math.cos(rendererYaw);
        double sin = Math.sin(rendererYaw);
        double centredWorldDz = worldDz + 3.0D / 16.0D;

        // q = R(-rendererYaw) * worldDelta; model = mirror(q).
        float xPixels = (float) (-cos * worldDx
            + sin * centredWorldDz) * 16.0F;
        float zPixels = (float) (sin * worldDx
            + cos * centredWorldDz) * 16.0F;

        // R(rendererYaw) * mirror * R(-bodyYaw) is the constant
        // R(180deg) * mirror world heading used by the authored ground mesh.
        float yRotRadians = (float) Math.toRadians(-bodyYawDegrees);
        return new WorldAnchoredContainerTransform(xPixels, zPixels,
            yRotRadians);
    }

    record WorldAnchoredContainerTransform(float xPixels, float zPixels,
                                           float yRotRadians) {
    }

    /**
     * Fisher v3 (27 Sep): seated rod fishing, one authored clip per server
     * phase window (FisherWorkGoal.phaseOf / client.render.FisherCastClock):
     * FISHER_CAST_V3 (overhead cast, release 0.80 s) -> FISHER_WAIT loop (+__v2
     * look round) -> FISHER_STRIKE_REEL on the real bite (left hand on the
     * crank) -> FISHER_LAND_FISH (fish into the left hand at 0.78 s). The line,
     * float and fish are drawn by FisherLineRenderer from this same pose.
     */
    private void applyFishingPose(SettlerEntity entity, float ageInTicks) {
        root.resetPose(); torso.resetPose(); resetLimb(rightArm); resetLimb(leftArm);
        resetLimb(rightLeg); resetLimb(leftLeg); head.resetPose();
        torso.getChild("cloak").resetPose();
        // The catalogued activity clip stays the lower-body base (the chair's
        // seated legs replace the legs); the phase clip below owns the rest.
        animate(entity.fishState, SettlerAnimations.FISHER_CAST, ageInTicks);
        root.resetPose(); torso.resetPose(); resetLimb(rightArm); resetLimb(leftArm); head.resetPose();
        torso.getChild("cloak").resetPose();
        com.hearthstead.client.render.FisherCastClock.Sample phase =
            com.hearthstead.client.render.FisherCastClock.sample(entity, ageInTicks);
        AnimationDefinition clip = phase == null ? CraftMotionAnimations.FISHER_WAIT : switch (phase.window()) {
            case CAST -> CraftMotionAnimations.FISHER_CAST_V3;
            case WAIT -> CraftMotionAnimations.FISHER_WAIT;
            case REEL -> CraftMotionAnimations.FISHER_STRIKE_REEL;
            case LAND -> CraftMotionAnimations.FISHER_LAND_FISH;
        };
        float seconds = phase == null ? ageInTicks / 20.0F : phase.seconds();
        sampleClip(clip, (long) (seconds * 1000.0F));
        // The landing net rides on the belt while both hands work the rod and the catch.
        fisherNet.visible = false;
    }

    private static float smoothStep(float value) {
        float clamped = Mth.clamp(value, 0.0F, 1.0F);
        return clamped * clamped * (3.0F - 2.0F * clamped);
    }

    private static Vector3f rootSpacePoint(float x, float y, float z,
                                           ModelPart... chain) {
        Vector3f point = new Vector3f(x, y, z);
        for (ModelPart part : chain) {
            transformPoint(point, part);
        }
        return point;
    }

    private static void transformPoint(Vector3f point, ModelPart part) {
        float x = point.x * part.xScale;
        float y = point.y * part.yScale;
        float z = point.z * part.zScale;

        float cosX = Mth.cos(part.xRot);
        float sinX = Mth.sin(part.xRot);
        float yAfterX = y * cosX - z * sinX;
        float zAfterX = y * sinX + z * cosX;

        float cosY = Mth.cos(part.yRot);
        float sinY = Mth.sin(part.yRot);
        float xAfterY = x * cosY + zAfterX * sinY;
        float zAfterY = -x * sinY + zAfterX * cosY;

        float cosZ = Mth.cos(part.zRot);
        float sinZ = Mth.sin(part.zRot);
        point.set(
            xAfterY * cosZ - yAfterX * sinZ + part.x,
            xAfterY * sinZ + yAfterX * cosZ + part.y,
            zAfterY + part.z
        );
    }

    private static void setLumberLoadVisibility(boolean frameVisible,
                                                ModelPart left, ModelPart center,
                                                ModelPart right, float fill) {
        // Tiers change only log count, never the rigid frame's pose. The first
        // real item becomes visible immediately; further logs communicate
        // approximate fullness without scaling or deforming the carrier.
        left.visible = frameVisible && fill > 0.001F;
        center.visible = frameVisible && fill >= 0.34F;
        right.visible = frameVisible && fill >= 0.67F;
    }

    /** Exact contact endpoint in model space; no vanilla held-item offset. */
    public void translateToServiceHand(HumanoidArm side, PoseStack pose) {
        root.translateAndRotate(pose);
        torso.translateAndRotate(pose);
        if (hasServiceHandPose(side)) {
            (side == HumanoidArm.RIGHT ? bagRightUpperArm : bagLeftUpperArm).translateAndRotate(pose);
            (side == HumanoidArm.RIGHT ? bagRightForearm : bagLeftForearm).translateAndRotate(pose);
            pose.translate(0, 6.0 / 16.0, 0);
        } else {
            (side == HumanoidArm.RIGHT ? rightArm : leftArm).translateAndRotate(pose);
            motion.applyHand(side == HumanoidArm.RIGHT ? LimbMotion.RIGHT_ARM : LimbMotion.LEFT_ARM, pose);
            pose.translate(0, 10.0 / 16.0, 0);
        }
    }

    public boolean hasServiceHandPose(HumanoidArm side) {
        return (serviceHands & (side == HumanoidArm.RIGHT ? 1 : 2)) != 0;
    }

    /** Root + torso transform (GuardScabbardLayer: the sheathed sword at the hip). */
    public void translateToTorso(PoseStack pose) {
        root.translateAndRotate(pose);
        torso.translateAndRotate(pose);
    }

    /** This frame's job pack decision (valid right after setupAnim, like every layer read). */
    public CarryPackRules.Look packLook() {
        return packLook;
    }

    /** Whether the job pack is drawn at the placed ground container (then read {@link #groundPackStyle}). */
    public boolean packOnGround() {
        return packOnGround;
    }

    /**
     * Into the carried sack's frame (pivot top-back of the torso, +y down,
     * +z back), including its fill/tier scale -- or, when the pack is placed,
     * the ground container's frame. CarryPackLayer draws every job shape here.
     */
    public void translateToPack(PoseStack pose, boolean ground) {
        root.translateAndRotate(pose);
        if (ground) {
            groundSack.translateAndRotate(pose);
        } else {
            torso.translateAndRotate(pose);
            sack.translateAndRotate(pose);
        }
    }

    /**
     * The greeting's sheathed window (client clock): from the seat in GUARD_SHEATHE_SWORD
     * (0.40 + 0.70 s) until the grip in GUARD_DRAW_SWORD (0.35 + 0.05 s). Presentation only.
     */
    public static boolean swordSheathed(SettlerEntity entity) {
        if (entity.getProfession() != Profession.GUARD || !entity.hasPhysicalMainhandSword()) {
            return false;
        }
        if (entity.guardSaluteEndState.isStarted()) {
            return entity.guardSaluteEndState.getAccumulatedTime() < 400L;
        }
        return entity.guardSaluteState.isStarted() && entity.guardSaluteState.getAccumulatedTime() >= 1100L;
    }

    /** The scabbard hangs at the hip for the whole greeting (snap to draw). */
    public static boolean greetingScabbard(SettlerEntity entity) {
        return entity.getProfession() == Profession.GUARD && entity.hasPhysicalMainhandSword()
            && (entity.guardSaluteState.isStarted() || entity.guardSaluteEndState.isStarted());
    }

    @Override
    public void translateToHand(HumanoidArm side, PoseStack pose) {
        root.translateAndRotate(pose);
        torso.translateAndRotate(pose);
        (side == HumanoidArm.RIGHT ? rightArm : leftArm).translateAndRotate(pose);
        motion.applyHand(side == HumanoidArm.RIGHT ? LimbMotion.RIGHT_ARM : LimbMotion.LEFT_ARM, pose);
    }

    // ------------------------------------------------------ motion engine ---

    /** Bent limbs render as continuous two-segment meshes; unbent is vanilla. */
    @Override
    public void renderToBuffer(PoseStack pose, VertexConsumer buffer, int light, int overlayCoords, int color) {
        motion.render(pose, buffer, light, overlayCoords, color);
    }

    /**
     * Every clip played through {@code animate(state, def, age)} lands here.
     * Engine off: the untouched vanilla path. Engine on: the same state clock
     * drives the authored JSON clip (or the converted legacy clip).
     */
    @Override
    protected void animate(AnimationState state, AnimationDefinition def, float ageInTicks, float speed) {
        if (!motion.engine()) {
            super.animate(state, def, ageInTicks, speed);
            return;
        }
        state.updateTime(ageInTicks, speed);
        if (!state.isStarted()) {
            return;
        }
        BoneMask mask = null;
        if (def == SettlerAnimations.IDLE && animatingEntity != null
            && animatingEntity.eatState.isStarted()) {
            mask = IDLE_UNDER_ACTION;
        } else if (animatingEntity != null && animatingEntity.walkAnimation.speed() > 0.05F
            && isGuardStrike(def)) {
            // A strike thrown on the move: distance-clocked locomotion keeps
            // the hips, legs and knees; the strike owns the upper body only.
            mask = STRIKE_OVER_GAIT;
        }
        motion.play(def, state.getAccumulatedTime() / 1000.0F, 1.0F, mask);
    }

    /** Additive guard strikes (MELEE family): stationary they may shift weight and bend knees. */
    private static boolean isGuardStrike(AnimationDefinition def) {
        String key = com.hearthstead.client.motion.LegacyClipBridge.keyOf(def);
        if (key == null) {
            return false;
        }
        if (key.equals("settler/melee") || key.startsWith("settler/spear_")
            || key.startsWith("settler/longsword_")) {
            return true;
        }
        return key.startsWith("settler/guard_") && !key.equals("settler/guard_stance")
            && !key.equals("settler/guard_patrol") && !key.equals("settler/guard_walk")
            && !key.equals("settler/guard_hit_react") && !key.equals("settler/guard_stagger");
    }

    private static final BoneMask STRIKE_OVER_GAIT = BoneMask.of(1.0F,
        "root", 0.0F, "right_leg", 0.0F, "left_leg", 0.0F, "right_shin", 0.0F, "left_shin", 0.0F);

    /** Locomotion: distance-clocked, stride-matched gait on the engine; vanilla otherwise. */
    @Override
    protected void animateWalk(AnimationDefinition def, float limbSwing, float limbSwingAmount,
                               float maxAnimationSpeed, float animationScaleFactor) {
        if (!motion.engine()) {
            super.animateWalk(def, limbSwing, limbSwingAmount, maxAnimationSpeed, animationScaleFactor);
            return;
        }
        motion.walk(def, limbSwingAmount, animationScaleFactor);
    }

    /** Server-clocked samples (bag transfer, socials, bard, replant) at an exact millisecond. */
    /**
     * Library key of the authored plain swing for the held weapon type
     * ("settler/" + WeaponClass.clipSet() + "_strike"), or null when the
     * engine is off, the weapon is a sword/axe (MELEE is their clip) or no
     * such clip is shipped yet.
     */
    private String weaponStrikeKey(SettlerEntity entity) {
        return weaponClipKey(entity, "_strike");
    }

    /** "settler/" + clipSet + suffix when shipped (engine on, non-sword class), else null. */
    private String weaponClipKey(SettlerEntity entity, String suffix) {
        if (!motion.engine()) {
            return null;
        }
        String set = entity.guardWeaponClass().clipSet();
        if ("guard".equals(set) || "none".equals(set) || "archer".equals(set)) {
            return null;
        }
        String key = "settler/" + set + suffix;
        return MotionLibrary.override(key) != null ? key : null;
    }

    /**
     * MELEE's blade contact is at 0.20 s (tick 4). Until a weapon has its own
     * clip, re-time MELEE so its contact lands on the weapon's contact tick
     * (spear 5, longsword 8, great axe 9, warhammer 12): heavier weapons read
     * as slower, heavier swings instead of hitting with no visible blow.
     */
    private static float meleeRate(SettlerEntity entity) {
        int contact = entity.guardWeaponClass().contactTick();
        return contact > 0 ? Mth.clamp(4.0F / contact, 0.25F, 1.0F) : 1.0F;
    }

    /** Salute wins over idle/patrol/stance, never over combat or a hit. */
    private static boolean isSaluting(SettlerEntity entity, Profession profession) {
        return (entity.guardSaluteState.isStarted() || entity.guardSaluteEndState.isStarted())
            && profession == Profession.GUARD
            && entity.getActivity() != SettlerActivity.COMBAT
            && !entity.guardStaggerState.isStarted() && !entity.shieldState.isStarted()
            && !entity.meleeState.isStarted() && !entity.guardHeavyState.isStarted()
            && !entity.guardLightBState.isStarted() && !entity.guardFinisherState.isStarted()
            && !entity.guardShieldBashState.isStarted();
    }

    /** Captain's acknowledging nod (EV_GUARD_NOD): head pitch 0 -> 12 -> 0 deg over 0.7 s, additive. */
    private static float nodPitch(SettlerEntity entity, float ageInTicks) {
        if (!entity.guardNodState.isStarted()) {
            return 0.0F;
        }
        entity.guardNodState.updateTime(ageInTicks, 1.0F);
        float t = Mth.clamp(entity.guardNodState.getAccumulatedTime() / 700.0F, 0.0F, 1.0F);
        float s = Mth.sin(t * (float) Math.PI);
        return 12.0F * Mth.DEG_TO_RAD * s * s;
    }

    /** Tavern lane: a weighted additive sample (cross-fades); vanilla path picks the dominant clip. */
    private void sampleClipWeighted(AnimationDefinition def, long millis, float weight) {
        if (!motion.engine()) {
            if (weight >= 0.5F) net.minecraft.client.animation.KeyframeAnimations.animate(this, def, millis, 1.0F,
                bagTransferAnimationScratch);
            return;
        }
        motion.play(def, millis / 1000.0F, weight, null);
    }

    private void sampleClip(AnimationDefinition def, long millis) {
        if (!motion.engine()) {
            net.minecraft.client.animation.KeyframeAnimations.animate(this, def, millis, 1.0F,
                bagTransferAnimationScratch);
            return;
        }
        motion.play(def, millis / 1000.0F, 1.0F, null);
    }

    private final java.util.Map<SettlerEntity, float[]> nailClocks = new java.util.WeakHashMap<>();
    // BUILDER lane model clocks.
    private final java.util.Map<SettlerEntity, float[]> buildClocks = new java.util.WeakHashMap<>();
    private final java.util.Map<SettlerEntity, float[]> buildHammerClocks = new java.util.WeakHashMap<>();
    // RING-1 lane model clocks.
    private final java.util.Map<SettlerEntity, float[]> whetClocks = new java.util.WeakHashMap<>();
    private final java.util.Map<SettlerEntity, float[]> netClocks = new java.util.WeakHashMap<>();
    // TRADER lane model clock.
    private final java.util.Map<SettlerEntity, float[]> traderDealClocks = new java.util.WeakHashMap<>();
    private final java.util.Map<SettlerEntity, float[]> carryPlankClocks = new java.util.WeakHashMap<>();
    // ANIM lane (battle roles) model clock.
    private final java.util.Map<SettlerEntity, float[]> roleActivityClocks = new java.util.WeakHashMap<>();
    private final java.util.Map<SettlerEntity, float[]> tradeClocks = new java.util.WeakHashMap<>();

    /** Trade work clip on the model clock (null: that activity still plays through its AnimationState). */
    private static AnimationDefinition tradeClip(SettlerActivity activity, Profession profession) {
        return switch (activity) {
            case WORK_STOKE -> profession == Profession.BREWER ? TradeMotionAnimations.BREW_MASH
                : SettlerAnimations.STOKE;
            case WORK_HAMMER -> profession == Profession.ARMOURER ? TradeMotionAnimations.ARMOUR_PLANISH
                : SettlerAnimations.HAMMER_ANVIL;
            case WORK_WEAVE -> profession == Profession.WEAVER ? TradeMotionAnimations.LOOM_WEAVE
                : SettlerAnimations.FINE_WORK;
            case WORK_KNEAD -> profession == Profession.MILLER ? TradeMotionAnimations.MILL_GRIND
                : SettlerAnimations.KNEAD;
            default -> null;
        };
    }

    /** Hunter v2 (anim lane, owner 26 Sep "full rework"): the authored hunter clips; engine only. */
    private boolean hunterV2(SettlerEntity entity, Profession profession) {
        return profession == Profession.HUNTER && motion.engine();
    }

    private final java.util.Map<SettlerEntity, float[]> hunterClocks = new java.util.WeakHashMap<>();

    /**
     * HUNTER v2: every authored hunter clip, on the same physical clocks the old clips used -- the draw on
     * EV-free WORK_HUNT (huntState, release tick 14 = 0.70 s), the take-kill on EV_PICKUP (contact tick 11),
     * the kneeling skin / butcher on a clock restarted per phase (HunterButchery contact ticks 4 / 9), the
     * stalk-ready and the lodge idle. Absolute full-body clips: reset first. The stalk and the haul are the
     * locomotion itself (see the locomotion chain); standing still with the kill, the haul's arms hold on.
     */
    private void applyHunterV2(SettlerEntity entity, SettlerActivity activity, float limbSwingAmount, float ageInTicks) {
        boolean moving = entity.walkAnimation.speed() > 0.05F;
        boolean floorKill = com.hearthstead.item.CarcassItem.isCarcass(entity.getOffhandItem());
        AnimationDefinition clip = null;
        float seconds = 0.0F;
        boolean keepGait = false;
        if (entity.huntState.isStarted()) {
            entity.huntState.updateTime(ageInTicks, 1.0F);
            clip = HunterMotionAnimations.HUNTER_DRAW;
            seconds = Math.min(1.2F, entity.huntState.getAccumulatedTime() / 1000.0F);
        } else if (entity.pickupState.isStarted()) {
            entity.pickupState.updateTime(ageInTicks, 1.0F);
            clip = HunterMotionAnimations.HUNTER_TAKE_KILL;
            seconds = Math.min(1.4F, entity.pickupState.getAccumulatedTime() / 1000.0F);
        } else if ((activity == SettlerActivity.WORK_SKIN || activity == SettlerActivity.WORK_BUTCHER) && floorKill) {
            clip = activity == SettlerActivity.WORK_SKIN ? HunterMotionAnimations.HUNTER_SKIN_KNEEL
                : HunterMotionAnimations.HUNTER_BUTCHER_KNEEL;
            seconds = hunterClock(entity, activity, ageInTicks);
        } else if (activity == SettlerActivity.TRACKING_GAME && !moving) {
            clip = HunterMotionAnimations.HUNTER_READY;
            seconds = hunterClock(entity, activity, ageInTicks);
        } else if (entity.idleSentryState.isStarted()) {
            clip = HunterMotionAnimations.IDLE_HUNTER;
            seconds = (ageInTicks + entity.getId() % 47) / 20.0F;
        } else if (activity == SettlerActivity.HAULING_CARCASS) {
            // Standing with the kill: the haul's arms, faded in as the gait weight fades out.
            float still = 1.0F - Mth.clamp(limbSwingAmount * 2.5F, 0.0F, 1.0F);
            if (still > 0.01F) {
                resetLimb(rightArm);
                resetLimb(leftArm);
                motion.play(HunterMotionAnimations.HUNTER_HAUL, 0.0F, 1.0F,
                    com.hearthstead.client.motion.BoneMask.UPPER_BODY);
            }
            return;
        } else {
            hunterClocks.remove(entity);
            return;
        }
        if (clip != HunterMotionAnimations.HUNTER_READY && clip != HunterMotionAnimations.HUNTER_SKIN_KNEEL
            && clip != HunterMotionAnimations.HUNTER_BUTCHER_KNEEL) {
            hunterClocks.remove(entity);
        }
        keepGait = moving && clip == HunterMotionAnimations.HUNTER_DRAW;
        PartPose rootPose = root.storePose();
        PartPose rightLegPose = rightLeg.storePose();
        PartPose leftLegPose = leftLeg.storePose();
        PartPose rightShinPose = rightShin.storePose();
        PartPose leftShinPose = leftShin.storePose();
        head.resetPose();
        torso.resetPose();
        torso.getChild("cloak").resetPose();
        resetLimb(rightArm);
        resetLimb(leftArm);
        root.resetPose();
        resetLimb(rightLeg);
        resetLimb(leftLeg);
        sampleClip(clip, (long) (seconds * 1000.0F));
        if (keepGait) {
            root.loadPose(rootPose);
            rightLeg.loadPose(rightLegPose);
            leftLeg.loadPose(leftLegPose);
            rightShin.loadPose(rightShinPose);
            leftShin.loadPose(leftShinPose);
        }
    }

    /** Seconds on a clock restarted whenever the hunter's activity changes (skin -> butcher restarts the chop). */
    private float hunterClock(SettlerEntity entity, SettlerActivity activity, float ageInTicks) {
        float[] clock = hunterClocks.get(entity);
        if (clock == null || clock[1] != activity.ordinal()) {
            clock = new float[] {ageInTicks, activity.ordinal()};
            hunterClocks.put(entity, clock);
        }
        return Math.max(0.0F, (ageInTicks - clock[0]) / 20.0F);
    }

    private float tradeClock(SettlerEntity entity, SettlerActivity activity, boolean active, float ageInTicks) {
        float[] clock = tradeClocks.get(entity);
        if (!active) {
            if (clock != null) {
                tradeClocks.remove(entity);
            }
            return -1.0F;
        }
        if (clock == null || clock[1] != activity.ordinal()) {
            clock = new float[] {ageInTicks, activity.ordinal()};
            tradeClocks.put(entity, clock);
        }
        return Math.max(0.0F, (ageInTicks - clock[0]) / 20.0F);
    }

    /** activityClock that also restarts when the (role) activity itself changes. */
    private float roleActivityClock(SettlerEntity entity, SettlerActivity activity, boolean active,
                                    float ageInTicks) {
        float[] clock = roleActivityClocks.get(entity);
        if (!active) {
            if (clock != null) {
                roleActivityClocks.remove(entity);
            }
            return -1.0F;
        }
        if (clock == null || clock[1] != activity.ordinal()) {
            clock = new float[] {ageInTicks, activity.ordinal()};
            roleActivityClocks.put(entity, clock);
        }
        return Math.max(0.0F, (ageInTicks - clock[0]) / 20.0F);
    }

    /** IDLE_SPEARMAN / IDLE_LONGSWORDSMAN for a battle role holding its own weapon, else null. */
    private static AnimationDefinition roleIdle(SettlerEntity entity, Profession profession) {
        if (!(entity.getMainHandItem().getItem() instanceof com.hearthstead.item.role.RoleWeaponItem weapon)) {
            return null;
        }
        if (profession == Profession.SPEARMAN && weapon.isSpear()) {
            return RoleMotionAnimations.IDLE_SPEARMAN;
        }
        if (profession == Profession.LONGSWORDSMAN && weapon.isLongsword()) {
            return RoleMotionAnimations.IDLE_LONGSWORDSMAN;
        }
        return null;
    }

    /** The authored clip of a battle-role strike (RoleMove ordinal), or null. */
    private static AnimationDefinition roleMoveClip(int moveId) {
        return switch (moveId) {
            case 0 -> RoleMotionAnimations.SPEAR_THRUST;
            case 1 -> RoleMotionAnimations.SPEAR_DOUBLE_THRUST;
            case 2 -> RoleMotionAnimations.SPEAR_BRACE_STRIKE;
            case 3 -> RoleMotionAnimations.LONGSWORD_CLEAVE;
            case 4 -> RoleMotionAnimations.LONGSWORD_HEAVY;
            case 5 -> RoleMotionAnimations.LONGSWORD_HALF_SWORD;
            default -> null;
        };
    }

    /** Seconds since {@code active} became true for this entity, or -1 while inactive. */
    private static float activityClock(java.util.Map<SettlerEntity, float[]> clocks, SettlerEntity entity,
                                       boolean active, float ageInTicks) {
        float[] start = clocks.get(entity);
        if (!active) {
            if (start != null) {
                clocks.remove(entity);
            }
            return -1.0F;
        }
        if (start == null) {
            start = new float[] {ageInTicks};
            clocks.put(entity, start);
        }
        return Math.max(0.0F, (ageInTicks - start[0]) / 20.0F);
    }

    private final Vector3f transferHandModel = new Vector3f();
    private int transferHandEntity = -1;

    /**
     * World position of the carrying palm during a bag-to-chest beat, from the
     * pose this model just rendered for {@code entity}; null when unavailable.
     * Inverse of LivingEntityRenderer's scale, (180 - bodyYaw) yaw, (-1,-1,1)
     * flip and -1.501 lift.
     */
    public Vec3 transferHandWorld(SettlerEntity entity, float partialTick) {
        if (transferHandEntity != entity.getId()) {
            return null;
        }
        double lx = -transferHandModel.x / 16.0;
        double ly = 1.501 - transferHandModel.y / 16.0;
        double lz = transferHandModel.z / 16.0;
        double yaw = Math.toRadians(180.0F - Mth.rotLerp(partialTick, entity.yBodyRotO, entity.yBodyRot));
        double c = Math.cos(yaw), sn = Math.sin(yaw);
        double wx = lx * c + lz * sn;
        double wz = -lx * sn + lz * c;
        float scale = entity.getScale();
        return entity.getPosition(partialTick).add(wx * scale, ly * scale, wz * scale);
    }

    /** The motion runtime driving this model (props, bend state). */
    public LimbMotion motion() {
        return motion;
    }

    /** Resets a limb together with its elbow/knee joint. */
    private void resetLimb(ModelPart part) {
        motion.resetLimb(part);
    }

    private void applySecondaryMotion(SettlerEntity entity, float ageInTicks, boolean anchored,
                                      boolean bagGripped) {
        LimbMotion.Overlay in = overlay;
        in.anchored = anchored || entity.onClimbable() || entity.isPassenger();
        in.bagGripped = bagGripped;
        in.packPinned = entity.isPassenger() || entity.isSleeping();
        in.breathingClip = entity.idleState.isStarted() || entity.idleFarmerState.isStarted()
            || entity.idleLumbererState.isStarted() || entity.idleSentryState.isStarted()
            || entity.idleCourierState.isStarted() || entity.idleTraderState.isStarted()
            || entity.idleForgeState.isStarted() || entity.idleBakerState.isStarted()
            || entity.idleCookState.isStarted() || entity.idleSightEdgeState.isStarted()
            || entity.idleFletcherState.isStarted() || entity.idleMinerState.isStarted()
            || entity.idleScholarState.isStarted() || entity.idleInnkeeperState.isStarted()
            || entity.idleWeaverState.isStarted() || entity.idleBladeBenchState.isStarted()
            || entity.idleFisherState.isStarted() || entity.sleepState.isStarted();
        in.stationaryWork = entity.getActivity().name().startsWith("WORK_");
        in.load = (sack.visible || lumberFrame.visible)
            ? Mth.clamp(entity.visualCarryFraction(), 0.0F, 1.0F) : 0.0F;
        in.hurtTime = entity.hurtTime;
        in.hurtDuration = entity.hurtDuration;
        in.sack = sack;
        in.packVisible = packLook.visible();
        if (in.packs.length != 3) {
            in.packs = new ModelPart[] {backpack, archerQuiver, travelerPack};
        }
        in.brim = hatBrim;
        in.cloak = torso.getChild("cloak");
        SettlerActivity act = entity.getActivity();
        in.exertionFloor = act == SettlerActivity.COMBAT || act == SettlerActivity.FLEEING ? 0.6F : 0.0F;
        // Personal carriage from synced facts only (traits are server-side):
        // a seed-stable build and stride, a slump when spent, a straighter back
        // when content and a hung head when low.
        int seed = entity.getAppearanceSeed();
        float tired = Mth.clamp((35.0F - entity.getEnergy()) / 35.0F, 0.0F, 1.0F);
        float proud = Mth.clamp((entity.getMorale() - 75.0F) / 25.0F, 0.0F, 1.0F);
        float low = Mth.clamp((35.0F - entity.getMorale()) / 35.0F, 0.0F, 1.0F);
        in.posture = (LimbMotion.hash01(seed, 1) - 0.4F) * 0.07F + 0.07F * tired
            - 0.04F * proud + 0.03F * low;
        in.headDrop = 0.06F * tired - 0.03F * proud + 0.09F * low;
        motion.setStrideScale((0.94F + 0.12F * LimbMotion.hash01(seed, 2)) * (1.0F - 0.08F * tired));
        motion.secondary(entity, ageInTicks, in);
    }
}
