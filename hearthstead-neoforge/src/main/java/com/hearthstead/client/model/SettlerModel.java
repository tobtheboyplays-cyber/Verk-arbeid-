package com.hearthstead.client.model;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
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
import net.minecraft.world.entity.HumanoidArm;

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
    private final ModelPart rightLeg;
    private final ModelPart leftLeg;
    private final ModelPart sack;
    private final ModelPart backpack;

    /** Sack scale when barely loaded, and when full. */
    private static final float SACK_MIN_SCALE = 0.55F;
    private static final float SACK_MAX_SCALE = 1.15F;
    /** Forward lean, in radians, a full sack puts into the spine. */
    private static final float SACK_MAX_LEAN = 0.16F;

    public SettlerModel(ModelPart root) {
        this.root = root.getChild("root");
        this.torso = this.root.getChild("torso");
        this.head = torso.getChild("head");
        this.hood = head.getChild("hood");
        this.hatBrim = head.getChild("hat_brim");
        this.rightArm = torso.getChild("right_arm");
        this.leftArm = torso.getChild("left_arm");
        this.rightLeg = this.root.getChild("right_leg");
        this.leftLeg = this.root.getChild("left_leg");
        this.sack = torso.getChild("sack");
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
        torso.addOrReplaceChild("left_arm", CubeListBuilder.create()
                .texOffs(16, 32).mirror().addBox(-2.0F, -2.0F, -2.0F, 4.0F, 12.0F, 4.0F),
            PartPose.offset(6.0F, -10.0F, 0.0F));

        torso.addOrReplaceChild("cloak", CubeListBuilder.create()
                .texOffs(64, 32).addBox(-5.5F, 0.0F, -3.0F, 11.0F, 4.0F, 6.0F,
                    new CubeDeformation(0.2F)),
            PartPose.offset(0.0F, -12.0F, 0.0F));
        torso.addOrReplaceChild("backpack", CubeListBuilder.create()
                .texOffs(96, 0).addBox(-3.0F, -9.0F, 2.5F, 6.0F, 7.0F, 3.0F),
            PartPose.ZERO);
        // The carried sack. Distinct from the decorative `backpack` above:
        // this one is hidden unless the settler is actually carrying, and its
        // size is the load. Pivot sits at the top-back of the torso so the
        // scale grows DOWNWARD and outward -- a sack hangs, it does not
        // inflate around its own middle.
        torso.addOrReplaceChild("sack", CubeListBuilder.create()
                .texOffs(0, 17).addBox(-4.0F, 0.0F, 0.0F, 8.0F, 8.0F, 5.0F),
            PartPose.offset(0.0F, -10.5F, 2.5F));
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

        return LayerDefinition.create(mesh, 128, 64);
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
        Profession profession = entity.getProfession();
        hood.visible = profession == Profession.NONE || profession == Profession.GUARD;
        hatBrim.visible = profession == Profession.FARMER;

        SettlerActivity activity = entity.getActivity();
        boolean climbing = entity.onClimbable();
        boolean lowHealth = entity.getHealth() < entity.getMaxHealth() * 0.4F;
        boolean night = entity.dayPhase() == SettlerEntity.DayPhase.REST;
        boolean dark = entity.level().getRawBrightness(entity.blockPosition(), 0) <= 4;

        if (climbing) {
            animate(entity.climbState, SettlerAnimations.CLIMB_LADDER, ageInTicks);
        } else if (activity == SettlerActivity.HAULING_LOG && entity.haulState.isStarted()) {
            // HAUL_LOG is a full self-contained gait -- its own legs, torso,
            // head, cloak and root, not just an arm overlay (RELEASE_GATE
            // HIGH-1). It must fully REPLACE WALK, the same way CLIMB_LADDER
            // does above: applying WALK first and HAUL_LOG's "locked" carry
            // arms on top of it (vanilla's animate() is additive) defeated
            // the whole point of a locked carry -- the arms visibly swung
            // with WALK's oscillation underneath. root().resetPose() at the
            // top of this method already leaves every part clean, so no
            // parts are shared with WALK here and no extra reset is needed.
            animate(entity.haulState, SettlerAnimations.HAUL_LOG, ageInTicks);
        } else {
            // Locomotion: mutually exclusive alternatives to WALK, picked by
            // priority since animateWalk always writes legs+arms+torso+cloak
            // and vanilla's animate() is additive -- only one may run.
            var locomotion = SettlerAnimations.WALK;
            if (activity == SettlerActivity.FLEEING) {
                locomotion = SettlerAnimations.RUN_PANIC;
            } else if (lowHealth) {
                locomotion = SettlerAnimations.WALK_LIMP;
            } else if (night && dark && profession != Profession.GUARD
                && activity != SettlerActivity.RESTING && activity != SettlerActivity.SLEEPING) {
                locomotion = SettlerAnimations.CREEP_NIGHT;
            } else if (activity == SettlerActivity.CARRYING) {
                // WALK_LADEN replaces WALK's legs/torso/cloak/root/head
                // whenever carrying (catalogue §1.2). animateWalk scales its
                // whole output by limbSwingAmount, so it fades to nothing on
                // its own while stopped -- no extra "moving" check needed to
                // get the catalogue's "suppressed while stopped" behaviour.
                locomotion = SettlerAnimations.WALK_LADEN;
            } else if (activity == SettlerActivity.TRAVELING) {
                locomotion = SettlerAnimations.WALK_HURRIED;
            }
            animateWalk(locomotion, limbSwing, limbSwingAmount, 2.0F, 2.5F);

            if (activity == SettlerActivity.PATROLLING && entity.patrolState.isStarted()) {
                // GUARD_PATROL overrides WALK's arm swing with a locked
                // pommel-hand pose -- reset the two arm parts first, since
                // vanilla's animate() adds onto the current pose rather than
                // replacing it. Gated on the AnimationState itself (not a
                // re-derived limbSwingAmount threshold, which used a
                // different cutoff than the state's own animateWhen
                // condition and could reset the arms with nothing applied
                // to replace them -- RELEASE_GATE LOW-4).
                rightArm.resetPose();
                leftArm.resetPose();
                animate(entity.patrolState, SettlerAnimations.GUARD_PATROL, ageInTicks);
            } else if (entity.carryState.isStarted()) {
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
        animate(entity.farmState, SettlerAnimations.FARM_TILL, ageInTicks);
        animate(entity.chopState, SettlerAnimations.CHOP, ageInTicks);
        animate(entity.eatState, SettlerAnimations.EAT, ageInTicks);
        animate(entity.restState, SettlerAnimations.REST, ageInTicks);
        animate(entity.meleeState, SettlerAnimations.MELEE, ageInTicks);
        animate(entity.celebrateState, SettlerAnimations.CELEBRATE, ageInTicks);
        animate(entity.plantState, SettlerAnimations.FARM_PLANT, ageInTicks);
        animate(entity.harvestState, SettlerAnimations.FARM_HARVEST, ageInTicks);
        animate(entity.waterState, SettlerAnimations.FARM_WATER, ageInTicks);
        animate(entity.limbState, SettlerAnimations.LIMB_BRANCHES, ageInTicks);
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

        if (entity.shieldState.isStarted()) {
            // SHIELD_BLOCK is a full self-contained guard hold (its own
            // legs/torso/arms/head/root/cloak, the same full set GUARD_STANCE
            // owns) -- reset every part both clips touch first, or the
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
            animate(entity.shieldState, SettlerAnimations.SHIELD_BLOCK, ageInTicks);
        } else {
            animate(entity.stanceState, SettlerAnimations.GUARD_STANCE, ageInTicks);
        }

        // Head tracking layers additively over the keyframes, damped per the
        // catalogue's damping table (§17.4 check 24) -- most-specific first.
        float damp;
        if (activity == SettlerActivity.SLEEPING) {
            damp = 0.0F;
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

        applySack(entity, limbSwing, limbSwingAmount);
    }

    /**
     * The load, made visible. Both references make you open a screen to learn
     * what a worker is carrying; a settlement should be readable by looking
     * at it, so the sack's size, its sag and the carrier's lean are all one
     * function of {@code carryFraction()} (D-A2b-2).
     *
     * <p>Continuous scale on one cube rather than three swap-in tiers: tiers
     * cost three UV regions and read as popping, and the blocky silhouette
     * survives a smooth scale perfectly well.
     */
    private void applySack(SettlerEntity entity, float limbSwing,
                           float limbSwingAmount) {
        float fill = entity.carryFraction();
        sack.visible = fill > 0.0F;
        // One shape on the back, never two. The decorative pack occupies the
        // same volume as the sack, so showing both would intersect; a settler
        // who shoulders a load has visibly swapped pack for sack.
        backpack.visible = !sack.visible;
        if (!sack.visible) {
            return;
        }
        float size = SACK_MIN_SCALE + (SACK_MAX_SCALE - SACK_MIN_SCALE) * fill;
        sack.xScale = size;
        sack.yScale = size;
        sack.zScale = size;
        // It hangs: a heavier sack sits lower and pulls further off the back.
        sack.y += fill * 0.9F;
        sack.z += fill * 0.6F;
        // Secondary motion -- the load lags the stride instead of riding
        // rigidly on the back.
        sack.xRot += Mth.cos(limbSwing * 0.6662F) * 0.06F * limbSwingAmount * fill;
        sack.zRot += Mth.sin(limbSwing * 0.3331F) * 0.05F * limbSwingAmount * fill;
        // The weight is in the spine, not just the prop: a full sack bends
        // the carrier, an almost-empty one barely does.
        torso.xRot += SACK_MAX_LEAN * fill;
        head.xRot -= SACK_MAX_LEAN * fill * 0.6F;
    }

    @Override
    public void translateToHand(HumanoidArm side, PoseStack pose) {
        root.translateAndRotate(pose);
        torso.translateAndRotate(pose);
        (side == HumanoidArm.RIGHT ? rightArm : leftArm).translateAndRotate(pose);
    }
}
