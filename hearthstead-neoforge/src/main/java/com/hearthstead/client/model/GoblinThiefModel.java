package com.hearthstead.client.model;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.RaiderEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.*;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.HumanoidArm;
import java.util.Map;
import java.util.WeakHashMap;

/** Dedicated demo silhouette. Only the synchronized thief flag selects this rig. */
public final class GoblinThiefModel extends HierarchicalModel<RaiderEntity> implements ArmedModel {
    public static final ModelLayerLocation LAYER = new ModelLayerLocation(Hearthstead.id("goblin_thief"), "main");
    private final ModelPart root, torso, head, leftArm, rightArm, leftLeg, rightLeg, pouch;
    private final ModelPart[] animatedParts;
    private final Map<RaiderEntity, Transition> transitions = new WeakHashMap<>();
    /** Motion engine: authored stage clips with bending elbows/knees (when present). */
    private final com.hearthstead.client.motion.LimbMotion motion;
    private static final class Transition {
        int stage;
        float changedAt;
        float[] from, last;
        Transition(int stage,float age) { this.stage=stage; changedAt=age-6; }
    }

    public GoblinThiefModel(ModelPart baked) {
        root = baked.getChild("root"); torso = root.getChild("torso");
        head = torso.getChild("head"); leftArm = torso.getChild("left_arm");
        rightArm = torso.getChild("right_arm"); leftLeg = root.getChild("left_leg");
        rightLeg = root.getChild("right_leg"); pouch = torso.getChild("pouch");
        animatedParts=new ModelPart[]{torso,head,leftArm,rightArm,leftLeg,rightLeg,pouch};
        motion = new com.hearthstead.client.motion.LimbMotion(this, root, torso, head,
            new ModelPart[] {rightArm, leftArm, rightLeg, leftLeg},
            new ModelPart[] {rightArm.getChild("right_forearm"), leftArm.getChild("left_forearm"),
                rightLeg.getChild("right_shin"), leftLeg.getChild("left_shin")},
            new com.hearthstead.client.motion.BendableLimb[] {
                new com.hearthstead.client.motion.BendableLimb(0, 64, -1.5F, 0, -1.5F, 3, 9, 3, 0, false, 256, 128, 4.5F),
                new com.hearthstead.client.motion.BendableLimb(0, 64, -1.5F, 0, -1.5F, 3, 9, 3, 0, false, 256, 128, 4.5F),
                new com.hearthstead.client.motion.BendableLimb(0, 64, -1.5F, 0, -1.5F, 3, 10, 3, 0, false, 256, 128, 5.0F),
                new com.hearthstead.client.motion.BendableLimb(0, 64, -1.5F, 0, -1.5F, 3, 10, 3, 0, false, 256, 128, 5.0F)},
            new ModelPart[][] {{}, {}, {rightLeg.getChild("boot")}, {leftLeg.getChild("boot")}});
    }

    private static CubeListBuilder box(int u, int v, float x, float y, float z, float w, float h, float d) {
        return CubeListBuilder.create().texOffs(u*2,v*2).addBox(x,y,z,w,h,d);
    }

    public static LayerDefinition createBodyLayer() {
        MeshDefinition mesh = new MeshDefinition();
        PartDefinition root = mesh.getRoot().addOrReplaceChild("root", CubeListBuilder.create(), PartPose.offset(0,24,0));
        PartDefinition torso = root.addOrReplaceChild("torso", box(32,0,-3.5F,-9,-2,7,9,4), PartPose.offset(0,-10,0));
        torso.addOrReplaceChild("belt",box(64,0,-3.65F,-2,-2.15F,7.3F,1.3F,4.3F),PartPose.ZERO);
        PartDefinition head = torso.addOrReplaceChild("head",box(0,0,-4,-8,-4,8,8,8),PartPose.offset(0,-9,0));
        head.addOrReplaceChild("hood_top",box(32,0,-4.6F,-9,-4.5F,9.2F,2,9),PartPose.ZERO);
        head.addOrReplaceChild("hood_back",box(32,0,-4.6F,-7,3.5F,9.2F,7.5F,1),PartPose.ZERO);
        head.addOrReplaceChild("hood_left",box(32,0,3.8F,-7,-4.5F,1,7.5F,8),PartPose.ZERO);
        head.addOrReplaceChild("hood_right",box(32,0,-4.8F,-7,-4.5F,1,7.5F,8),PartPose.ZERO);
        head.addOrReplaceChild("left_ear",box(0,32,0,-1.3F,-1,4,2.6F,2),PartPose.offsetAndRotation(4,-4,0,0,0,-.18F));
        head.addOrReplaceChild("right_ear",box(0,32,-4,-1.3F,-1,4,2.6F,2),PartPose.offsetAndRotation(-4,-4,0,0,0,.18F));
        head.addOrReplaceChild("nose",box(0,32,-1.75F,-3.85F,-6.05F,3.5F,2.15F,2.2F),PartPose.ZERO);
        head.addOrReplaceChild("eyes",CubeListBuilder.create().texOffs(192,0)
            .addBox(-2.8F,-4.5F,-4.1F,1.6F,1.3F,.2F).texOffs(192,0)
            .addBox(1.2F,-4.5F,-4.1F,1.6F,1.3F,.2F),PartPose.ZERO);
        head.addOrReplaceChild("left_brow",box(32,32,-1.1F,-.35F,-.15F,2.2F,.7F,.3F),PartPose.offsetAndRotation(1.9F,-4.95F,-4.2F,0,0,-.22F));
        head.addOrReplaceChild("right_brow",box(32,32,-1.1F,-.35F,-.15F,2.2F,.7F,.3F),PartPose.offsetAndRotation(-1.9F,-5.45F,-4.2F,0,0,.06F));
        head.addOrReplaceChild("left_pupil",box(32,32,0,0,0,.55F,1.1F,.2F),PartPose.offset(1.75F,-4.4F,-4.32F));
        head.addOrReplaceChild("right_pupil",box(32,32,0,0,0,.55F,1.1F,.2F),PartPose.offset(-2.10F,-4.4F,-4.32F));
        head.addOrReplaceChild("smirk",box(32,32,-1.4F,-.15F,0,2.8F,.3F,.2F),PartPose.offsetAndRotation(.65F,-1.5F,-4.15F,0,0,-.18F));
        torso.addOrReplaceChild("left_arm",box(0,32,-1.5F,0,-1.5F,3,9,3),PartPose.offset(5,-8,0))
            .addOrReplaceChild("left_forearm",CubeListBuilder.create(),PartPose.offset(0,4.5F,0));
        torso.addOrReplaceChild("right_arm",box(0,32,-1.5F,0,-1.5F,3,9,3),PartPose.offset(-5,-8,0))
            .addOrReplaceChild("right_forearm",CubeListBuilder.create(),PartPose.offset(0,4.5F,0));
        PartDefinition pouch = torso.addOrReplaceChild("pouch",box(64,32,-1.6F,1,-1.2F,3.2F,2.8F,2.4F),PartPose.offset(3,-2,-3));
        pouch.addOrReplaceChild("pouch_neck",box(64,32,-.8F,0,-.7F,1.6F,1.3F,1.4F),PartPose.ZERO);
        pouch.addOrReplaceChild("pouch_bottom",box(64,32,-1.15F,3.8F,-.9F,2.3F,.6F,1.8F),PartPose.ZERO);
        pouch.addOrReplaceChild("pouch_tie",box(64,0,-1.15F,.25F,-.95F,2.3F,.45F,1.9F),PartPose.ZERO);
        torso.addOrReplaceChild("strap",box(64,0,-.45F,-4.3F,-.15F,.9F,8.6F,.3F),PartPose.offsetAndRotation(0,-5,-2.18F,0,0,.57F));
        for (int side : new int[]{-1,1}) {
            PartDefinition leg = root.addOrReplaceChild(side<0?"right_leg":"left_leg",
                box(0,32,-1.5F,0,-1.5F,3,10,3),PartPose.offset(side*2.1F,-10,0));
            leg.addOrReplaceChild("boot",box(32,32,-1.7F,6,-2.3F,3.4F,4,4),PartPose.ZERO);
            leg.addOrReplaceChild(side<0?"right_shin":"left_shin",CubeListBuilder.create(),PartPose.offset(0,5,0));
        }
        return LayerDefinition.create(mesh,256,128);
    }

    @Override public ModelPart root() { return root; }

    @Override public void translateToHand(HumanoidArm side, PoseStack pose) {
        root.translateAndRotate(pose);
        torso.translateAndRotate(pose);
        (side == HumanoidArm.RIGHT ? rightArm : leftArm).translateAndRotate(pose);
        motion.applyHand(side == HumanoidArm.RIGHT ? com.hearthstead.client.motion.LimbMotion.RIGHT_ARM
            : com.hearthstead.client.motion.LimbMotion.LEFT_ARM, pose);
    }

    @Override
    public void renderToBuffer(PoseStack pose, com.mojang.blaze3d.vertex.VertexConsumer buffer,
                               int light, int overlay, int color) {
        motion.render(pose, buffer, light, overlay, color);
    }

    private static boolean authored(net.minecraft.client.animation.AnimationDefinition def) {
        return com.hearthstead.client.motion.MotionLibrary.override(def) != null;
    }

    /**
     * Authored stage clips on the motion engine: same synced stage and stage
     * clock as the procedural path, gaits distance-clocked. Returns false (and
     * poses nothing) when the engine is off or the stage has no authored clip.
     */
    private boolean authoredStage(RaiderEntity entity, int stage, float limbAmount, float age, float yaw, float pitch) {
        if (!motion.engine()) return false;
        float elapsed = (float) (entity.level().getGameTime() - entity.goblinThiefStageStartedAt())
            + age - entity.tickCount;
        switch (stage) {
            case 1 -> {
                if (!authored(GoblinAnimations.GOBLIN_STEAL)) return false;
                motion.play(GoblinAnimations.GOBLIN_STEAL, Mth.clamp(elapsed, 0, 60) / 20.0F, 1.0F, null);
            }
            case 2 -> {
                if (!authored(GoblinAnimations.GOBLIN_FLEE)) return false;
                motion.walk(GoblinAnimations.GOBLIN_FLEE, limbAmount, 2.5F);
            }
            case 3 -> {
                if (!authored(GoblinAnimations.GOBLIN_PICK_LOCK)) return false;
                motion.play(GoblinAnimations.GOBLIN_PICK_LOCK, Mth.clamp(elapsed, 0, 40) / 20.0F, 1.0F, null);
            }
            default -> {
                if (!authored(GoblinAnimations.GOBLIN_LURK) || !authored(GoblinAnimations.GOBLIN_SNEAK)) return false;
                float gait = Mth.clamp(limbAmount * 2.5F, 0.0F, 1.0F);
                motion.walk(GoblinAnimations.GOBLIN_SNEAK, limbAmount, 2.5F);
                motion.play(GoblinAnimations.GOBLIN_LURK, (age + entity.getId() % 60) / 20.0F, 1.0F - gait, null);
            }
        }
        head.yRot += Mth.clamp(yaw, -55, 55) * Mth.DEG_TO_RAD;
        head.xRot += Mth.clamp(pitch, -30, 30) * Mth.DEG_TO_RAD;
        return true;
    }

    @Override public void setupAnim(RaiderEntity entity,float limbSwing,float limbAmount,float age,float yaw,float pitch) {
        root.getAllParts().forEach(ModelPart::resetPose);
        motion.begin(entity, age);
        // Scripted clip (finisher victim etc.) owns the whole body; see MotionOverrides.
        if (motion.playScripted(entity, age)) {
            return;
        }
        int stage=entity.goblinThiefStage();
        if (authoredStage(entity, stage, limbAmount, age, yaw, pitch)) {
            blendTransition(entity, stage, age);
            float poke = Mth.clamp(attackTime, 0.0F, 1.0F);
            if (poke > 0.001F && authored(GoblinAnimations.GOBLIN_POKE)) {
                // Additive over the stage pose; contact at 0.15 s = the server's tick 3.
                motion.play(GoblinAnimations.GOBLIN_POKE, poke * 0.3F, 1.0F, null);
            }
            return;
        }
        double dx=entity.getX()-entity.xo,dz=entity.getZ()-entity.zo;
        float moving=Math.min(Mth.clamp(limbAmount,0,1),(float)Math.sqrt(dx*dx+dz*dz)*6);
        // Flee cadence comes from real horizontal movement, so braking into a
        // route corner shortens the visible stride instead of windmilling in place.
        float cadence=stage==2 ? Mth.lerp(moving,.84F,1.08F) : .82F;
        float step=Mth.sin(limbSwing*cadence)*moving;
        head.yRot=Mth.clamp(yaw,-55,55)*Mth.DEG_TO_RAD;
        head.xRot=Mth.clamp(pitch,-30,30)*Mth.DEG_TO_RAD;
        // Stage two has no crouch flag: keep its carry run visibly upright.
        torso.xRot=stage==2?.10F:.12F;
        leftLeg.xRot=step*(stage==2?.72F:.4F); rightLeg.xRot=-leftLeg.xRot;
        torso.y+=(1-Mth.cos(limbSwing*(stage==2?cadence*2F:1.64F)))*.14F*moving;
        if(stage==3) {
            float elapsed=Mth.clamp((float)(entity.level().getGameTime()
                -entity.goblinThiefStageStartedAt())+age-entity.tickCount,0,40);
            float reach=ease(elapsed/6);
            float turn=Mth.sin(elapsed*.65F)*.09F*reach;
            leftLeg.xRot=rightLeg.xRot=0;
            torso.xRot=.14F;
            head.xRot=.12F;
            leftArm.xRot=Mth.lerp(reach,-.28F,-1.35F);
            rightArm.xRot=Mth.lerp(reach,-.28F,-1.45F)+turn;
            leftArm.yRot=.16F; rightArm.yRot=-.18F;
            rightArm.zRot=turn;
        } else if(stage==1) {
            // The sixty-tick theft window shares its start with the server.
            // Anticipate0..12, work latch12..48, grip/recover48..60; no
            // decorative Coin appears before the authoritative transfer.
            float elapsed=Mth.clamp((float)(entity.level().getGameTime()
                -entity.goblinThiefStageStartedAt())+age-entity.tickCount,0,60);
            float reach=ease(elapsed/12);
            float recovery=ease((elapsed-48)/12);
            float latch=Mth.sin(elapsed*.45F)*.065F*reach*(1-recovery);
            leftLeg.xRot=rightLeg.xRot=0;
            torso.xRot=Mth.lerp(reach,.12F,.26F)-recovery*.06F;
            head.xRot=Mth.lerp(reach,head.xRot,.18F);
            leftArm.xRot=Mth.lerp(reach,-.28F,-1.1F)+latch+recovery*.22F;
            rightArm.xRot=Mth.lerp(reach,-.28F,-1.25F)-latch+recovery*.3F;
            leftArm.yRot=.12F; rightArm.yRot=-.12F;
        } else if(stage==2) {
            // Flee with one hand over the real-cargo pouch, other arm balancing.
            leftArm.xRot=-.4F; leftArm.zRot=.3F;
            rightArm.xRot=step*.7F-.2F; rightArm.zRot=-.16F;
            pouch.zRot=step*.05F;
        } else {
            leftArm.xRot=-.28F-step*.2F; rightArm.xRot=-.28F+step*.2F;
            float glance=Mth.sin(age*.035F);
            head.yRot+=Math.copySign(Math.max(0,Math.abs(glance)-.7F),glance)*1.1F;
            leftArm.zRot=.10F; rightArm.zRot=-.10F;
        }
        // The synchronized vanilla flag, not a broad stage label, owns the crouch silhouette.
        if (entity.isShiftKeyDown()) {
            torso.xRot += .16F;
            torso.y += 1.15F;
            head.xRot += .08F;
            leftLeg.xRot *= .55F;
            rightLeg.xRot *= .55F;
        }
        blendTransition(entity,stage,age);
        // The hand swing sent by DefensivePokeGoal is layered after stable
        // locomotion/stage interpolation, so it stays attached to the real arm.
        // A six-tick swing peaks at tick three, the server's contact frame.
        float swing = Mth.sin(Mth.clamp(attackTime, 0.0F, 1.0F) * Mth.PI);
        if (swing > 0.001F) {
            rightArm.xRot -= swing * 1.55F;
            rightArm.yRot -= swing * .28F;
            torso.yRot += swing * .10F;
        }
    }

    private static float ease(float value) {
        float t=Mth.clamp(value,0,1); return t*t*(3-2*t);
    }

    private void blendTransition(RaiderEntity entity,int stage,float age) {
        // Renderer models are shared: interpolation history must belong to
        // the entity, never to whichever goblin happened to render last.
        Transition state=transitions.computeIfAbsent(entity,key->new Transition(stage,age));
        if(state.stage!=stage) {
            state.stage=stage; state.changedAt=age;
            state.from=state.last==null?null:state.last.clone();
        }
        ModelPart[] parts=animatedParts;
        float blend=ease((age-state.changedAt)/6);
        if(state.last==null) state.last=new float[parts.length*4];
        float[] pose=state.last;
        for(int i=0;i<parts.length;i++) {
            ModelPart part=parts[i]; int index=i*4;
            if(state.from!=null&&blend<1) {
                part.xRot=Mth.lerp(blend,state.from[index],part.xRot);
                part.yRot=Mth.lerp(blend,state.from[index+1],part.yRot);
                part.zRot=Mth.lerp(blend,state.from[index+2],part.zRot);
                part.y=Mth.lerp(blend,state.from[index+3],part.y);
            }
            pose[index]=part.xRot; pose[index+1]=part.yRot;
            pose[index+2]=part.zRot; pose[index+3]=part.y;
        }
        state.last=pose;
        if(blend>=1) state.from=null;
    }
}
