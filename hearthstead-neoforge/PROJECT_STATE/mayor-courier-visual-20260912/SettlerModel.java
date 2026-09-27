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

        torso.addOrReplaceChild("right_arm", CubeListBuilder.create()
                .texOffs(0, 32).addBox(-2.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(-6.0F, -10.0F, 0.0F));
        PartDefinition leftArm = torso.addOrReplaceChild("left_arm", CubeListBuilder.create()
                .texOffs(16, 32).mirror().addBox(-2.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(6.0F, -10.0F, 0.0F));
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
        // left shoulder. Its upper three units are genuinely hollow; four
        // half-unit walls leave a 2 x 2 opening above the recessed base.
        // Reuse only the owned backpack UV island. Separate upper walls
        // avoid stretching the old 7-unit face into a taller solid backpack.
        // There are deliberately no shafts: archerQuiverCount is persisted
        // server state, not a synced client presentation value.
        torso.addOrReplaceChild("archer_quiver", CubeListBuilder.create()
                .texOffs(96, 0).addBox(0.5F, -8.0F, 3.5F, 3.0F, 6.0F, 3.0F)
                .texOffs(99, 3).addBox(0.5F, -11.0F, 3.5F, 3.0F, 3.0F, 0.5F)
                .texOffs(99, 3).addBox(0.5F, -11.0F, 6.0F, 3.0F, 3.0F, 0.5F)
                .texOffs(99, 3).addBox(0.5F, -11.0F, 4.0F, 0.5F, 3.0F, 2.0F)
                .texOffs(99, 3).addBox(3.0F, -11.0F, 4.0F, 0.5F, 3.0F, 2.0F)
                // Two leather mounting tabs bridge the real torso rear at
                // z=2.5 to the case at z=3.5. Both sit below the shoulder
                // opening, preserving the existing hood and arm clearance.
                .texOffs(99, 5).addBox(1.0F, -7.0F, 2.5F, 2.0F, 1.0F, 1.0F)
                .texOffs(99, 5).addBox(1.0F, -4.0F, 2.5F, 2.0F, 1.0F, 1.0F),
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
                 HERDER, FISHER, HUNTER -> true;
            default -> false;
        };
        hatBrim.visible = profession == Profession.FARMER;
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
            } else if (activity == SettlerActivity.CARRYING
                || activity == SettlerActivity.HAULING_LOG) {
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
            } else if (night && dark && profession != Profession.GUARD
                && activity != SettlerActivity.RESTING && activity != SettlerActivity.SLEEPING) {
                locomotion = SettlerAnimations.CREEP_NIGHT;
            } else if (activity == SettlerActivity.TRAVELING
                && !entity.hasTravelerAppearance()) {
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
            animateWalk(locomotion, limbSwing, gaitAmount, 2.0F, 2.5F);
            if (loadedCourier) {
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
                rightArm.resetPose();
                leftArm.resetPose();
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
                    rightArm.resetPose();
                    leftArm.resetPose();
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
            } else if (entity.patrolState.isStarted()
                && (profession == Profession.ARCHER
                    ? entity.hasPhysicalMainhandBow()
                    : entity.hasPhysicalMainhandSword())) {
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
                rightArm.resetPose();
                leftArm.resetPose();
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
                rightArm.resetPose();
                leftArm.resetPose();
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
                rightArm.resetPose();
                leftArm.resetPose();
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
        animate(entity.idleSentryState,
            profession == Profession.ARCHER && entity.hasPhysicalMainhandBow()
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
            rightArm.resetPose();
            leftArm.resetPose();
            rightLeg.resetPose();
            leftLeg.resetPose();
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
            rightArm.resetPose();
            leftArm.resetPose();
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            rightLeg.resetPose();
            leftLeg.resetPose();
            animate(entity.celebrateState, SettlerAnimations.CELEBRATE, ageInTicks);
        }
        animate(entity.plantState, SettlerAnimations.FARM_PLANT, ageInTicks);
        animate(entity.harvestState, SettlerAnimations.FARM_HARVEST, ageInTicks);
        animate(entity.waterState, SettlerAnimations.FARM_WATER, ageInTicks);
        animate(entity.limbState, SettlerAnimations.LIMB_BRANCHES, ageInTicks);
        // CHAINS-1 craft motions. Staggered by entity id so a row of bakers
        // does not knead in lockstep -- the same trick idleState uses.
        animate(entity.kneadState, SettlerAnimations.KNEAD, ageInTicks + (id % 24));
        animate(entity.cleaveState, SettlerAnimations.CLEAVE, ageInTicks + (id % 17));
        animate(entity.stokeState, SettlerAnimations.STOKE, ageInTicks + (id % 28));
        animate(entity.hammerState, SettlerAnimations.HAMMER_ANVIL, ageInTicks + (id % 20));
        animate(entity.sawState, SettlerAnimations.SAW, ageInTicks + (id % 22));
        animate(entity.fineWorkState, SettlerAnimations.FINE_WORK, ageInTicks + (id % 18));
        animate(entity.ovenState, SettlerAnimations.OVEN_TEND, ageInTicks + (id % 32));
        if (profession == Profession.FARMER && entity.sowState.isStarted()) {
            // A replant presses one seed at tick14, exactly like first planting.
            // Keep that approach unchanged and shorten only the recovery to28.
            entity.sowState.updateTime(ageInTicks, 1.0F);
            long elapsed = entity.sowState.getAccumulatedTime();
            long cycle = elapsed % 1400L;
            long plantTime = cycle <= 700L ? cycle : 700L + (cycle - 700L) * 13L / 7L;
            net.minecraft.client.animation.KeyframeAnimations.animate(this,
                SettlerAnimations.FARM_PLANT, plantTime, 1.0F, bagTransferAnimationScratch);
        } else {
            animate(entity.sowState, SettlerAnimations.SOW_BROADCAST, ageInTicks + (id % 28));
        }
        animate(entity.mineState, SettlerAnimations.MINE_PICK, ageInTicks + (id % 19));
        animate(entity.stirState, SettlerAnimations.COOK_STIR, ageInTicks + (id % 30));
        animate(entity.planeState, SettlerAnimations.CARPENTER_PLANE, ageInTicks + (id % 26));
        animate(entity.chiselState, SettlerAnimations.MASON_CHISEL, ageInTicks + (id % 21));
        animate(entity.fletchState, SettlerAnimations.FLETCHER_FLETCH, ageInTicks + (id % 32));
        animate(entity.scrapeState, SettlerAnimations.TANNER_SCRAPE, ageInTicks + (id % 24));
        // TRADES-1: same stationary-work-loop pattern as the row above --
        // staggered by entity id so a row of herders/fishers/hunters never
        // moves in lockstep.
        animate(entity.shearState, SettlerAnimations.HERDER_SHEAR, ageInTicks + (id % 25));
        animate(entity.fishState, SettlerAnimations.FISHER_CAST, ageInTicks + (id % 34));
        // Hunting is a single physical shot and recovery, not a staggered work loop.
        if (entity.huntState.isStarted()) {
            // Navigation has stopped; the client gait envelope can still be
            // decaying. Plant the body without delaying the real draw clock.
            torso.resetPose();
            head.resetPose();
            torso.getChild("cloak").resetPose();
            root.resetPose();
            rightLeg.resetPose();
            leftLeg.resetPose();
            animate(entity.huntState, SettlerAnimations.HUNTER_LOOSE, ageInTicks);
        }
        // ANIM-TRUTH-0A craft contacts own the complete planted body. They
        // must replace any loop sampled earlier in this method, and their
        // clocks must remain exact: table commit is 1.50 s / tick 30 and
        // storage deposit is 0.70 s / tick 14.
        if (entity.craftState.isStarted() || entity.craftStoreState.isStarted()) {
            rightArm.resetPose();
            leftArm.resetPose();
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            rightLeg.resetPose();
            leftLeg.resetPose();
            animate(entity.craftState, SettlerAnimations.LUMBER_CRAFT,
                ageInTicks);
            animate(entity.craftStoreState,
                SettlerAnimations.CRAFT_OUTPUT_STORE, ageInTicks);
        }
        animate(entity.leapState, SettlerAnimations.LEAP_STRIKE, ageInTicks);
        // GATHER_LOG is a full-body contact one-shot. Vanilla animation
        // channels are additive, so layering this stoop over CHOP/LIMB/WALK
        // folded the torso and arms into impossible 60-180 degree sums. It
        // owns these bones absolutely for its short contact window, exactly
        // like PICKUP_STOW and the courier lift below.
        if (entity.gatherState.isStarted()) {
            rightArm.resetPose();
            leftArm.resetPose();
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            rightLeg.resetPose();
            leftLeg.resetPose();
            animate(entity.gatherState, SettlerAnimations.GATHER_LOG, ageInTicks);
        }
        // Portable work-container loop. These are four independent,
        // inspectable full-body beats; only the server event for the active
        // transaction phase starts one. Never layer them over a work loop.
        if (entity.workContainerDownState.isStarted()
            || entity.groundItemPickupState.isStarted()
            || entity.workContainerStowState.isStarted()
            || entity.workContainerUpState.isStarted()) {
            rightArm.resetPose();
            leftArm.resetPose();
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            rightLeg.resetPose();
            leftLeg.resetPose();
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
            rightArm.resetPose();
            leftArm.resetPose();
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            rightLeg.resetPose();
            leftLeg.resetPose();
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
            rightArm.resetPose();
            leftArm.resetPose();
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            rightLeg.resetPose();
            leftLeg.resetPose();
            animate(entity.liftState, SettlerAnimations.COURIER_LIFT, ageInTicks);
            animate(entity.setDownState, SettlerAnimations.COURIER_SET_DOWN, ageInTicks);
        }
        // The reviewed bag-to-chest candidate owns the planted body for its
        // full four seconds. It is intentionally separate from the Lumberer
        // work-container states above, preserving their approved handoffs.
        var bagTransfer = entity.bagTransferPresentation();
        if (bagTransfer.ownsBodyPose(entity)) {
            rightArm.resetPose();
            leftArm.resetPose();
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            rightLeg.resetPose();
            leftLeg.resetPose();
            rightArm.visible = false;
            leftArm.visible = false;
            bagRightUpperArm.visible = true;
            bagLeftUpperArm.visible = true;
            // Follow the server's planted-session clock, never an event restart.
            net.minecraft.client.animation.KeyframeAnimations.animate(this,
                SettlerAnimations.BAG_TO_CHEST_UNLOAD,
                (long) (Math.min(80.0F, bagTransfer.clock()
                    + Mth.clamp(ageInTicks - entity.tickCount, 0.0F, 0.999F)) * 50.0F),
                1.0F, bagTransferAnimationScratch);
            if (bagTransfer.sourcePickup() && bagTransfer.clock() >= 24
                    && bagTransfer.clock() < 64) {
                // Preserve the normal forward lower/lift segments. The middle
                // is an original source-face to sack reach, not reversed time.
                float partial = Mth.clamp(ageInTicks - entity.tickCount, 0, .999F);
                float clock = bagTransfer.clock() + partial;
                float blend = smoothStep((clock - 24) / 6);
                if (clock > 48) blend *= 1 - smoothStep((clock - 48) / 16);
                // Torso approach settles in six ticks; feet retain the original planted base.
                torso.xRot = Mth.lerp(blend, torso.xRot, .5F);
                torso.y = Mth.lerp(blend, torso.y, -8.0F);
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
        boolean physicalShieldLoadout = physicalSword
            && entity.hasPhysicalOffhandShield();
        if (entity.shieldState.isStarted()
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
            rightLeg.resetPose();
            leftLeg.resetPose();
            head.resetPose();
            torso.resetPose();
            rightArm.resetPose();
            leftArm.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            animate(entity.shieldState,
                physicalShieldLoadout
                    ? SettlerAnimations.SHIELD_BLOCK
                    : SettlerAnimations.GUARD_HIT_REACT,
                ageInTicks);
        } else {
            animate(entity.stanceState,
                profession == Profession.ARCHER && entity.hasPhysicalMainhandBow()
                    ? SettlerAnimations.ARCHER_STANCE
                    : profession == Profession.GUARD && physicalSword
                        ? SettlerAnimations.GUARD_STANCE
                        : SettlerAnimations.IDLE,
                ageInTicks);
            // EV_MELEE owns a zero-offset upper-body strike over exactly one
            // martial base. A stationary guard has GUARD_STANCE here; a
            // moving guard already received the absolute GUARD_PATROL
            // arm/head base above, after WALK's gait arms were cleared. This
            // ordering keeps the sword arm independent of gait phase while
            // leaving WALK's distance-sampled root and feet untouched.
            if (entity.meleeState.isStarted()
                && profession == Profession.GUARD && physicalSword) {
                animate(entity.meleeState, SettlerAnimations.MELEE, ageInTicks);
            }
        }

        if ((profession == Profession.ARCHER
                || profession == Profession.HUNTER && entity.huntState.isStarted())
            && entity.hasPhysicalMainhandBow()) {
            applyBowMotion(entity, netHeadYaw, headPitch,
                profession == Profession.HUNTER
                    ? com.hearthstead.entity.ai.HunterWorkGoal.HUNT_RELEASE_TICK : 20.0F, ageInTicks);
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

        // Procedural hurt flinch.
        if (entity.hurtTime > 0) {
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
            rightArm.resetPose();
            leftArm.resetPose();
            torso.resetPose();
            head.resetPose();
            root.resetPose();
            torso.getChild("cloak").resetPose();
            rightLeg.resetPose();
            leftLeg.resetPose();
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
            rightArm.resetPose(); leftArm.resetPose(); torso.resetPose();
            root.resetPose(); rightLeg.resetPose(); leftLeg.resetPose();
            head.resetPose();
            head.yRot = Mth.clamp(netHeadYaw, -60F, 60F) * Mth.DEG_TO_RAD;
            head.xRot = Mth.clamp(headPitch, -35F, 35F) * Mth.DEG_TO_RAD;
            float elapsed = entity.level().getGameTime() - entity.innkeeperSocialStart()
                + ageInTicks - entity.tickCount;
            net.minecraft.client.animation.KeyframeAnimations.animate(this,
                SettlerAnimations.INN_WELCOME, (long) (Math.max(0F, elapsed) * 50F),
                1F, new Vector3f());
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
        boolean seated = entity.getVehicle() instanceof com.hearthstead.entity.TavernSeatEntity;
        ModelPart seatedRight = root.getChild("seated_right_leg");
        ModelPart seatedLeft = root.getChild("seated_left_leg");
        seatedRight.visible = seated;
        seatedLeft.visible = seated;
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
            && !entity.isPassenger()) {
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
        if (TravelerStaffPose.apply(entity, ageInTicks, root, torso, rightArm,
                travelerRightUpper, travelerRightFore, travelerStaff)) {
            travelerPack.visible = true;
            backpack.visible = false;
        }
        serviceHands = TavernServingHandPose.apply(entity, ageInTicks, root, torso,
            rightArm, leftArm, bagRightUpperArm, bagRightForearm,
            bagLeftUpperArm, bagLeftForearm);
        QaTravelerPoseCapture.capture(entity, ageInTicks, root);
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

    /**
     * A Mayor keeps the MAYOR profession and its own idle identity, but an
     * assigned Mayor Courier uses the real Courier bag presentation while
     * carrying or committing that server-authored bag transfer.
     */
    private static boolean usesCourierCargoPresentation(SettlerEntity entity,
                                                         Profession profession) {
        return profession == Profession.COURIER || profession == Profession.TRADER
            || profession == Profession.MAYOR
                && (entity.carryState.isStarted()
                    || entity.bagTransferPresentation().ownsBodyPose(entity));
    }

    private void applyWorkContainer(SettlerEntity entity, float ageInTicks) {
        float fill = entity.visualCarryFraction();
        WorkContainerKind kind = entity.placedWorkContainerKind();
        var placed = entity.placedWorkContainerPos();
        Profession profession = entity.getProfession();
        var transfer = entity.bagTransferPresentation();
        boolean courierTransfer = (profession == Profession.COURIER || profession == Profession.TRADER)
            && transfer.ownsBodyPose(entity);
        // Courier owns a synchronized transfer clock, not Lumberer's down/up
        // events. Draw one moving duplicate during its shoulder handoffs too.
        if (courierTransfer && placed == null) placed = transfer.bagAnchor();
        boolean detached = kind == WorkContainerKind.SACK && placed != null;
        detached |= courierTransfer && placed != null;
        boolean lumberer = profession == Profession.LUMBERER;
        boolean courier = usesCourierCargoPresentation(entity, profession);
        boolean farmerCarrying = profession == Profession.FARMER
            && entity.getActivity() == SettlerActivity.CARRYING
            && fill > 0.001F;

        // Physical contract: an attached container is a direct torso child;
        // a detached one is a root child projected from the persisted world
        // anchor. There is no stride-phase rotation or spring offset anywhere
        // in this path, so neither silhouette can follow a frame behind.
        lumberFrame.visible = lumberer && !detached;
        sack.visible = (courier || farmerCarrying) && !detached;
        groundLumberFrame.visible = lumberer && detached;
        groundSack.visible = detached && !groundLumberFrame.visible;
        backpack.visible = !lumberFrame.visible && !sack.visible
            && !groundLumberFrame.visible && !groundSack.visible;
        archerQuiver.visible = backpack.visible && profession == Profession.ARCHER;
        backpack.visible = backpack.visible && !archerQuiver.visible;

        setLumberLoadVisibility(lumberFrame.visible, lumberLogLeft,
            lumberLogCenter, lumberLogRight, fill);
        setLumberLoadVisibility(groundLumberFrame.visible, groundLumberLogLeft,
            groundLumberLogCenter, groundLumberLogRight, fill);

        float courierSize = COURIER_PACK_MIN_SCALE
            + (COURIER_PACK_MAX_SCALE - COURIER_PACK_MIN_SCALE) * fill;
        sack.xScale = courierSize;
        sack.yScale = courierSize;
        sack.zScale = courierSize;

        if (lumberFrame.visible || sack.visible) {
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
        groundContainer.xScale = groundScale;
        groundContainer.yScale = groundScale;
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
        WorldAnchoredContainerTransform fixed = worldAnchoredContainerTransform(
            dx, dz, bodyYaw);
        float fixedX = fixed.xPixels();
        float fixedZ = fixed.zPixels();
        // The canvas mesh ends eight pixels below its pivot. Keep its sole on
        // the anchor while cargo changes its scale, instead of letting a light
        // sack hover and a full sack sink through the floor. Lumber is unchanged.
        float bottomOffset = courier ? 8.0F * groundScale : 8.0F;
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
            Vector3f shoulder = rootSpacePoint(0.0F, -10.5F, 2.5F, torso);
            targetX = Mth.lerp(courierGroundBlend, shoulder.x, fixedX);
            targetY = Mth.lerp(courierGroundBlend, shoulder.y, fixedY);
            targetZ = Mth.lerp(courierGroundBlend, shoulder.z, fixedZ);
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
        groundContainer.xRot = groundLumberFrame.visible || courier
            ? 0.0F : 0.08F;
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
            pose.translate(0, 10.0 / 16.0, 0);
        }
    }

    public boolean hasServiceHandPose(HumanoidArm side) {
        return (serviceHands & (side == HumanoidArm.RIGHT ? 1 : 2)) != 0;
    }

    @Override
    public void translateToHand(HumanoidArm side, PoseStack pose) {
        root.translateAndRotate(pose);
        torso.translateAndRotate(pose);
        (side == HumanoidArm.RIGHT ? rightArm : leftArm).translateAndRotate(pose);
    }
}
