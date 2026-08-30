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
import net.minecraft.world.entity.HumanoidArm;
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
    private final ModelPart hatBrim;
    private final ModelPart rightArm;
    private final ModelPart leftArm;
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

    /** Courier parcel scale when barely loaded, and when full. */
    private static final float COURIER_PACK_MIN_SCALE = 0.80F;
    private static final float COURIER_PACK_MAX_SCALE = 1.05F;
    /** Forward lean, in radians, a full work container puts into the spine. */
    private static final float LOAD_MAX_LEAN = 0.16F;

    public SettlerModel(ModelPart root) {
        this.root = root.getChild("root");
        this.torso = this.root.getChild("torso");
        this.head = torso.getChild("head");
        this.hood = head.getChild("hood");
        this.hatBrim = head.getChild("hat_brim");
        this.rightArm = torso.getChild("right_arm");
        this.leftArm = torso.getChild("left_arm");
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
        head.addOrReplaceChild("hood", CubeListBuilder.create()
                .texOffs(32, 0).addBox(-4.0F, -8.0F, -4.0F, 8.0F, 8.0F, 8.0F,
                    new CubeDeformation(0.6F)),
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
            lumberFrameBuilder(), PartPose.offset(0.0F, -10.5F, 2.5F));
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
            .texOffs(26, 49).addBox(-4.0F, 9.0F, 0.0F, 8.0F, 1.0F, 4.0F);
    }

    private static void addLumberLogs(PartDefinition frame, boolean ground) {
        String prefix = ground ? "ground_log_" : "log_";
        frame.addOrReplaceChild(prefix + "left", CubeListBuilder.create()
                .texOffs(50, 49).addBox(-3.5F, 0.5F, 1.25F, 2.0F, 8.0F, 2.0F),
            PartPose.ZERO);
        frame.addOrReplaceChild(prefix + "center", CubeListBuilder.create()
                .texOffs(50, 49).addBox(-1.0F, 0.5F, 1.25F, 2.0F, 8.0F, 2.0F),
            PartPose.ZERO);
        frame.addOrReplaceChild(prefix + "right", CubeListBuilder.create()
                .texOffs(50, 49).addBox(1.5F, 0.5F, 1.25F, 2.0F, 8.0F, 2.0F),
            PartPose.ZERO);
    }

    @Override
    public ModelPart root() {
        return root;
    }

    @Override
    public void setupAnim(SettlerEntity entity, float limbSwing, float limbSwingAmount,
                          float ageInTicks, float netHeadYaw, float headPitch) {
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
                 WEAVER, MINER, SCHOLAR, BREWER, ARCHER,
                 // TRADES-1: rustic outdoor trades, the same silhouette
                 // family as MINER/ARCHER above -- a shepherd's, a fisher's
                 // and a hunter's hood all read as "works outside, weather-
                 // facing", painted by gen_settler.py's own outfit table.
                 HERDER, FISHER, HUNTER -> true;
            default -> false;
        };
        hatBrim.visible = profession == Profession.FARMER;

        SettlerActivity activity = entity.getActivity();
        wateringCan.visible = profession == Profession.FARMER
            && activity == SettlerActivity.WORK_WATER;
        boolean climbing = entity.onClimbable();
        boolean lowHealth = entity.getHealth() < entity.getMaxHealth() * 0.4F;
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
            } else if (night && dark && profession != Profession.GUARD
                && activity != SettlerActivity.RESTING && activity != SettlerActivity.SLEEPING) {
                locomotion = SettlerAnimations.CREEP_NIGHT;
            } else if (activity == SettlerActivity.TRAVELING) {
                locomotion = SettlerAnimations.WALK_HURRIED;
            }
            animateWalk(locomotion, limbSwing, limbSwingAmount, 2.0F, 2.5F);

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
                rightArm.resetPose();
                leftArm.resetPose();
                head.resetPose();
                torso.resetPose();
                animate(entity.patrolState,
                    profession == Profession.ARCHER
                        ? SettlerAnimations.ARCHER_PATROL
                        : SettlerAnimations.GUARD_PATROL,
                    ageInTicks);
            } else if (entity.carryState.isStarted()
                && profession == Profession.COURIER) {
                // COURIER_CARRY (catalogue §5.2) is the flagship carry
                // clip: it authors arms/torso/head/cloak/root itself, not
                // just an arm overlay like GUARD_PATROL -- it re-derives
                // the same lean/breath/root-compression WALK_LADEN just
                // wrote, deliberately (its own comment: "reasserted here so
                // the clip is correct if played standing still"). Left
                // as-is, WALK_LADEN's contribution and COURIER_CARRY's
                // would SUM on every bone they share (vanilla animate() is
                // additive) -- the settler would read as bent double, not
                // leaning back under a load. Reset every part this clip
                // owns before applying it; legs are deliberately NOT reset
                // (COURIER_CARRY authors no leg channel at all -- catalogue
                // §5.2: "inherited from WALK_LADEN; do not author").
                // carryState.animateWhen is activity==CARRYING alone, with
                // no moving component, so this branch and the clip both run
                // continuously whether the courier is walking or standing
                // at a chest -- satisfying the catalogue's "standing-still
                // variant... required, not optional" without a second clip.
                torso.resetPose();
                head.resetPose();
                torso.getChild("cloak").resetPose();
                root.resetPose();
                rightArm.resetPose();
                leftArm.resetPose();
                animate(entity.carryState, SettlerAnimations.COURIER_CARRY, ageInTicks);

                // Standing-still weight shift (catalogue §5.2, "the single
                // most robotic thing this mod could ship, so this variant
                // is required, not optional"): a courier stopped at a chest
                // still needs to look alive. Procedural, not a second
                // keyframe clip -- COURIER_CARRY's own single deterministic
                // curve can't represent two different situations (walking
                // vs. planted) at once, and this is the same pattern as the
                // hurt-flinch below: a small addition on top of the
                // authored pose, gated on real movement.
                if (limbSwingAmount < 0.01F) {
                    // ~40-tick (2s) period, matching the catalogue's cited
                    // root-dip/rise cadence for this variant.
                    float settleWave = Mth.sin(ageInTicks * 0.157F);
                    root.y += settleWave * 0.2F - 0.2F;
                    torso.zRot += settleWave * 0.0524F; // +-3 degrees
                }
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
        animate(entity.sowState, SettlerAnimations.SOW_BROADCAST, ageInTicks + (id % 28));
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
        animate(entity.huntState, SettlerAnimations.HUNTER_LOOSE, ageInTicks + (id % 29));
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

        if (profession == Profession.ARCHER
            && entity.hasPhysicalMainhandBow()) {
            applyArcherBowMotion(entity, netHeadYaw, headPitch);
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
        } else if (entity.shieldState.isStarted()) {
            damp = 0.15F;
        } else if (climbing) {
            damp = 0.3F;
        } else if (activity == SettlerActivity.FLEEING) {
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
    }

    /**
     * Uses the vanilla, server-synced use-item clock to pull the real bow into
     * the canonical bow-and-arrow pose. EV_ARCHER_LOOSE then lowers both arms
     * from that exact pose over 0.40 s, so arrow spawn and visible release can
     * never be authored by two unrelated timers.
     */
    private void applyArcherBowMotion(SettlerEntity entity,
                                      float netHeadYaw, float headPitch) {
        float blend = 0.0F;
        boolean drawing = entity.isUsingItem()
            && entity.getUsedItemHand() == InteractionHand.MAIN_HAND;
        if (drawing) {
            float draw = Mth.clamp((entity.getTicksUsingItem() + 1) / 20.0F,
                0.0F, 1.0F);
            blend = draw * draw * (3.0F - 2.0F * draw);
        } else if (entity.archerLooseState.isStarted()) {
            float release = Mth.clamp(
                entity.archerLooseState.getAccumulatedTime() / 400.0F,
                0.0F, 1.0F);
            float eased = release * release * (3.0F - 2.0F * release);
            blend = 1.0F - eased;
        }
        if (blend <= 0.0F) {
            return;
        }

        float aimPitch = headPitch * ((float) Math.PI / 180.0F);
        float aimYaw = netHeadYaw * ((float) Math.PI / 180.0F);
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
    private void applyWorkContainer(SettlerEntity entity, float ageInTicks) {
        float fill = entity.visualCarryFraction();
        WorkContainerKind kind = entity.placedWorkContainerKind();
        var placed = entity.placedWorkContainerPos();
        Profession profession = entity.getProfession();
        boolean detached = kind == WorkContainerKind.SACK && placed != null;
        boolean lumberer = profession == Profession.LUMBERER;
        boolean courier = profession == Profession.COURIER;
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
            torso.xRot += LOAD_MAX_LEAN * fill;
            head.xRot -= LOAD_MAX_LEAN * fill * 0.6F;
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
        // current yaw-relative root space. Inverting MobRenderer's
        // (180-bodyYaw) rotation is what keeps the sack in exactly one world
        // spot even while the worker turns and walks around it.
        float partial = Mth.clamp(ageInTicks - entity.tickCount, 0.0F, 1.0F);
        double entityX = Mth.lerp(partial, entity.xo, entity.getX());
        double entityY = Mth.lerp(partial, entity.yo, entity.getY());
        double entityZ = Mth.lerp(partial, entity.zo, entity.getZ());
        float bodyYaw = Mth.rotLerp(partial, entity.yBodyRotO, entity.yBodyRot);
        float inverse = (180.0F - bodyYaw) * ((float) Math.PI / 180.0F);
        double dx = placed.getX() + 0.5 - entityX;
        double dz = placed.getZ() + 0.5 - entityZ;
        float cos = Mth.cos(inverse);
        float sin = Mth.sin(inverse);
        float fixedX = (float) (cos * dx - sin * dz) * 16.0F;
        float fixedZ = (float) (sin * dx + cos * dz) * 16.0F - 3.0F;
        float fixedY = (float) (entityY - placed.getY()) * 16.0F - 8.0F;

        // Root crouches are body motion, not sack motion. Cancel the root's
        // transient offsets so the detached prop does not bob when knees bend.
        fixedX -= root.x;
        fixedY -= root.y - 24.0F;
        fixedZ -= root.z;

        // During put-down / lift, the root-owned duplicate uses three explicit
        // ownership anchors: shoulder -> guiding left hand -> persisted world
        // position (and the exact reverse on lift). A straight shoulder/world
        // lerp visibly floated the prop through empty air even though its end
        // point was correct. The fixed anchor is still authoritative before
        // and after contact, including reload and interruption recovery.
        float targetX = fixedX;
        float targetY = fixedY;
        float targetZ = fixedZ;
        boolean lowering = entity.workContainerDownState.isStarted();
        boolean lifting = entity.workContainerUpState.isStarted();
        if (lowering || lifting) {
            // Only transition frames need forward-kinematic anchors. The long
            // fixed collection phase stays allocation-free in the render loop.
            Vector3f shoulder = rootSpacePoint(0.0F, -10.5F, 2.5F, torso);
            Vector3f hand = rootSpacePoint(0.0F, 9.0F, 0.0F, leftArm, torso);
            if (lowering) {
                float time = entity.workContainerDownState.getAccumulatedTime() / 1000.0F;
                if (time <= 0.20F) {
                    targetX = shoulder.x;
                    targetY = shoulder.y;
                    targetZ = shoulder.z;
                } else if (time < 0.38F) {
                    float contact = smoothStep((time - 0.20F) / 0.18F);
                    targetX = Mth.lerp(contact, shoulder.x, hand.x);
                    targetY = Mth.lerp(contact, shoulder.y, hand.y);
                    targetZ = Mth.lerp(contact, shoulder.z, hand.z);
                } else if (time <= 0.88F) {
                    targetX = hand.x;
                    targetY = hand.y;
                    targetZ = hand.z;
                } else if (time < 1.05F) {
                    float release = smoothStep((time - 0.88F) / 0.17F);
                    targetX = Mth.lerp(release, hand.x, fixedX);
                    targetY = Mth.lerp(release, hand.y, fixedY);
                    targetZ = Mth.lerp(release, hand.z, fixedZ);
                }
            } else {
                float time = entity.workContainerUpState.getAccumulatedTime() / 1000.0F;
                if (time < 0.60F) {
                    // Stay at the fixed world anchor until grip contact.
                } else if (time < 0.78F) {
                    float contact = smoothStep((time - 0.60F) / 0.18F);
                    targetX = Mth.lerp(contact, fixedX, hand.x);
                    targetY = Mth.lerp(contact, fixedY, hand.y);
                    targetZ = Mth.lerp(contact, fixedZ, hand.z);
                } else if (time <= 1.25F) {
                    targetX = hand.x;
                    targetY = hand.y;
                    targetZ = hand.z;
                } else if (time < 1.45F) {
                    float shoulderContact = smoothStep((time - 1.25F) / 0.20F);
                    targetX = Mth.lerp(shoulderContact, hand.x, shoulder.x);
                    targetY = Mth.lerp(shoulderContact, hand.y, shoulder.y);
                    targetZ = Mth.lerp(shoulderContact, hand.z, shoulder.z);
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
        groundContainer.xRot = groundLumberFrame.visible
            ? 0.0F : 0.08F;
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

    @Override
    public void translateToHand(HumanoidArm side, PoseStack pose) {
        root.translateAndRotate(pose);
        torso.translateAndRotate(pose);
        (side == HumanoidArm.RIGHT ? rightArm : leftArm).translateAndRotate(pose);
    }
}
