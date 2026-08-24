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
    }

    @Override
    public void translateToHand(HumanoidArm side, PoseStack pose) {
        root.translateAndRotate(pose);
        torso.translateAndRotate(pose);
        (side == HumanoidArm.RIGHT ? rightArm : leftArm).translateAndRotate(pose);
    }
}
