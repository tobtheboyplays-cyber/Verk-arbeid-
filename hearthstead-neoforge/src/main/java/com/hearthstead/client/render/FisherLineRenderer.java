package com.hearthstead.client.render;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.model.SettlerModel;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.registry.ModItems;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Fisher v3: the line, the float and the catch, drawn for the seated rod
 * fisher (owner 27 Sep: "actually see that he is fishing"). Presentation only:
 * no FishingHook entity, no loot, nothing that can hook a player. The server
 * keeps the whole catch; this reads the synced cycle phase through
 * {@link FisherCastClock} and the posed model (rod tip = the real held
 * fishers_rod through the same ItemInHandLayer chain; left palm).
 *
 * <ul>
 *   <li>cast: the float dangles under the tip, flies out on the whip (0.80 s)
 *       and splashes down (1.25 s) on the water cell 3-6 blocks ahead;</li>
 *   <li>wait: the float rides the swell with the odd nibble; slack line with sag;</li>
 *   <li>bite: the float is pulled under (splash), the taut line drags it in to
 *       the bank while the fish fights;</li>
 *   <li>land: a perch comes out of the water on a swinging arc to the chest
 *       and rides the left hand from the grab (0.78 s).</li>
 * </ul>
 * Vanilla sound events are used at runtime (throw, splash, retrieve); every
 * model and texture drawn here is ours.
 */
public final class FisherLineRenderer {
    private static final ResourceLocation WOOL =
        Hearthstead.id("textures/item/material/line_wool.png");
    private static final ModelPart FLOAT_TOP = part(CubeListBuilder.create()
        .texOffs(0, 0).addBox(-1.1F, -2.2F, -1.1F, 2.2F, 1.6F, 2.2F));
    private static final ModelPart FLOAT_BOTTOM = part(CubeListBuilder.create()
        .texOffs(0, 0).addBox(-1.1F, -0.6F, -1.1F, 2.2F, 1.4F, 2.2F));
    private static final ModelPart FLOAT_STEM = part(CubeListBuilder.create()
        .texOffs(0, 0).addBox(-0.3F, -3.5F, -0.3F, 0.6F, 1.3F, 0.6F));
    /** Rod tip in the fishers_rod item model (px): tip-top at y 30 on the x=z=8 axis. */
    private static final float TIP_X = 8F / 16F, TIP_Y = 30F / 16F, TIP_Z = 8F / 16F;
    /** Model px (seated rig) where the landed fish swings in to the left hand (fisher_v3_spec.FISH_GRAB). */
    private static final float GRAB_X = 2.5F, GRAB_Y = 5.0F, GRAB_Z = -9.5F;
    private static final int LINE_COLOUR = 0xFF2E2A24;
    private static ItemStack fishStack;

    private FisherLineRenderer() {
    }

    private static ModelPart part(CubeListBuilder cubes) {
        MeshDefinition mesh = new MeshDefinition();
        mesh.getRoot().addOrReplaceChild("part", cubes, PartPose.ZERO);
        return LayerDefinition.create(mesh, 16, 16).bakeRoot().getChild("part");
    }

    /** True while the settler is on the chair with the rod out (the server's WORK_FISH cycle). */
    public static boolean fishing(SettlerEntity actor) {
        return actor.getProfession() == Profession.FISHER
            && actor.getActivity() == SettlerActivity.WORK_FISH
            && actor.getFisherCycleTick() >= 0
            && actor.getMainHandItem().is(ModItems.FISHERS_ROD.get());
    }

    /** Called by FisherOutfitLayer with the model-space pose of this settler. */
    public static void render(SettlerModel model, SettlerEntity actor, PoseStack pose,
                              MultiBufferSource buffers, int light, float partial, float age) {
        if (!fishing(actor) || actor.isInvisible()) {
            return;
        }
        FisherCastClock.Sample clock = FisherCastClock.sample(actor, age);
        if (clock == null) {
            return;
        }
        Level level = actor.level();
        FisherCastClock.State st = clock.state();
        if (st.waterCycle != st.cycle || st.water == null) {
            st.water = findWater(level, actor);
            st.waterCycle = st.cycle;
        }
        if (st.water == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        Matrix4f base = new Matrix4f(pose.last().pose());
        Vector3f tip = rodTip(model, actor, pose);
        if (tip == null) {
            return;
        }
        Vec3 tipW = new Vec3(tip.x + cam.x, tip.y + cam.y, tip.z + cam.z);
        // Only in the world: a screen preview (settler sheet) poses the model
        // somewhere else entirely, and must never draw a line into the lake.
        Vec3 feet = actor.getPosition(partial);
        if (tipW.distanceToSqr(feet) > 16.0) {
            return;
        }
        double waterY = st.water.getY() + level.getFluidState(st.water).getHeight(level, st.water);
        Vec3 target = new Vec3(st.water.getX() + 0.5, waterY, st.water.getZ() + 0.5);
        float yaw = actor.yBodyRot * Mth.DEG_TO_RAD;
        Vec3 facing = new Vec3(-Mth.sin(yaw), 0, Mth.cos(yaw));
        Vec3 shore = new Vec3(actor.getX() + facing.x * 1.35, waterY, actor.getZ() + facing.z * 1.35);
        float t = age + (actor.getId() % 37) * 3.1F;
        float swell = (Mth.sin(t * 0.13F) * 0.022F + Mth.sin(t * 0.305F + 1F) * 0.011F);
        float sec = clock.seconds();
        Vec3 bobber;
        Vec3 fish = null;
        float sag;
        switch (clock.window()) {
            case CAST -> {
                if (sec < FisherCastClock.RELEASE) {
                    st.releaseTip = null;
                    bobber = tipW.add(0, -0.32, 0);
                    sag = 0F;
                } else {
                    if (st.releaseTip == null) {
                        st.releaseTip = tipW;
                    }
                    float u = smooth((sec - FisherCastClock.RELEASE)
                        / (FisherCastClock.SPLASHDOWN - FisherCastClock.RELEASE));
                    bobber = st.releaseTip.lerp(target, u).add(0, 1.6 * Mth.sin(Mth.PI * u), 0);
                    if (sec >= FisherCastClock.SPLASHDOWN) {
                        bobber = target.add(0, swell, 0);
                    }
                    sag = sec < FisherCastClock.SPLASHDOWN ? 0F
                        : 0.5F * Mth.clamp((sec - FisherCastClock.SPLASHDOWN) / 0.3F, 0F, 1F);
                }
            }
            case WAIT -> {
                // Now and then a fish mouths the bait: two short dips, never in step between fishers.
                float nib = (t * 0.05F) % 9.3F;
                float dip = nib < 0.35F ? 0.06F * Mth.sin(nib / 0.35F * Mth.PI) : 0F;
                bobber = target.add(0, swell - dip, 0);
                sag = 0.5F;
            }
            case REEL -> {
                float dip = sec < 0.3F ? 0.2F * Mth.sin(sec / 0.3F * Mth.PI) : 0F;
                float u = smooth((sec - 0.4F) / 1.7F);
                Vec3 side = new Vec3(-facing.z, 0, facing.x);
                double run = 0.16 * Mth.sin(sec * 9F) * (1 - u * 0.5);
                bobber = target.lerp(shore, u).add(side.x * run, -dip - 0.04 - 0.025 * Mth.sin(sec * 13F), side.z * run);
                sag = sec < 0.3F ? 0.3F : 0.06F;
            }
            default -> {
                // Owner 27 Sep: the fish HANGS on the line under the rod tip and flaps
                // (0.45-1.75 s), then is swung in and grabbed (2.05 s).
                Vec3 grab = toWorld(base, cam, GRAB_X, GRAB_Y, GRAB_Z);
                Vec3 from = shore.add(0, -0.06, 0);
                float ft = age / 20F + (actor.getId() % 13) * 0.61F;
                if (sec >= FisherCastClock.GRAB) {
                    Vec3 palm = leftPalm(model, pose, cam);
                    fish = palm != null ? palm : grab;
                } else if (sec < FisherCastClock.LAND_UP) {
                    float u = smooth(sec / FisherCastClock.LAND_UP);
                    Vec3 hang = tipW.add(dangle(base, FisherCastClock.LAND_UP, ft));
                    fish = from.lerp(hang, u).add(0, 0.375 * Mth.sin(Mth.PI * u), 0);
                } else {
                    Vec3 hang = tipW.add(dangle(base, sec, ft));
                    fish = sec < FisherCastClock.LAND_SWING ? hang
                        : hang.lerp(grab, smooth((sec - FisherCastClock.LAND_SWING)
                            / (FisherCastClock.GRAB - FisherCastClock.LAND_SWING)));
                }
                Vec3 up = tipW.subtract(fish);
                bobber = fish.add(up.normalize().scale(Math.min(0.32, up.length())));
                sag = 0.02F;
            }
        }
        st.lastFish = fish;
        cues(level, actor, clock, st, target, shore);
        // line: tip -> float (-> fish), vanilla-style line strip with a hanging sag
        VertexConsumer lines = buffers.getBuffer(RenderType.lineStrip());
        PoseStack world = new PoseStack();
        drawLine(lines, world.last(), tipW.subtract(cam), bobber.subtract(cam), sag);
        if (fish != null) {
            VertexConsumer more = buffers.getBuffer(RenderType.lineStrip());
            drawLine(more, world.last(), bobber.subtract(cam), fish.subtract(cam), 0F);
        }
        // the float (our cuboids and wool tile, tinted)
        int floatLight = net.minecraft.client.renderer.LevelRenderer.getLightColor(level, BlockPos.containing(bobber));
        world.pushPose();
        world.translate(bobber.x - cam.x, bobber.y - cam.y, bobber.z - cam.z);
        // ModelPart cubes are already in px/16; y flips so the red cap rides on top, 1.6x for readability.
        world.scale(1.6F, -1.6F, 1.6F);
        world.mulPose(Axis.ZP.rotationDegrees(Mth.sin(t * 0.09F) * 6F));
        VertexConsumer wool = buffers.getBuffer(RenderType.entityCutoutNoCull(WOOL));
        FLOAT_TOP.render(world, wool, floatLight, OverlayTexture.NO_OVERLAY, 0xFFC0392B);
        FLOAT_BOTTOM.render(world, wool, floatLight, OverlayTexture.NO_OVERLAY, 0xFFF1EAD8);
        FLOAT_STEM.render(world, wool, floatLight, OverlayTexture.NO_OVERLAY, 0xFF5A4331);
        world.popPose();
        if (fish != null) {
            if (fishStack == null) {
                fishStack = new ItemStack(ModItems.RIVER_PERCH.get());
            }
            float ft = age / 20F + (actor.getId() % 13) * 0.61F;
            float[] fl = flap(ft);
            world.pushPose();
            world.translate(fish.x - cam.x, fish.y - cam.y, fish.z - cam.z);
            world.mulPose(Axis.YP.rotationDegrees(-actor.yBodyRot));
            if (sec < FisherCastClock.GRAB) {
                // hooked by the mouth: hangs head-up, the body thrashing about the line
                float k = sec < FisherCastClock.LAND_SWING ? 1F
                    : 1F - smooth((sec - FisherCastClock.LAND_SWING) / (FisherCastClock.GRAB - FisherCastClock.LAND_SWING));
                world.mulPose(Axis.YP.rotationDegrees(fl[0] * k));
                world.mulPose(Axis.XP.rotationDegrees(fl[1] * k));
                world.translate(0F, -0.2F, 0F);
                world.mulPose(Axis.ZP.rotationDegrees(90F));
            } else {
                // in the palm: laid across the hand, a last few kicks
                float kick = 12F * (float) Math.exp(-3.0 * (sec - FisherCastClock.GRAB)) * Mth.sin(ft * 30F);
                world.mulPose(Axis.YP.rotationDegrees(90F + kick));
            }
            world.scale(0.85F, 0.85F, 0.85F);
            mc.getItemRenderer().renderStatic(fishStack, ItemDisplayContext.NONE, light, OverlayTexture.NO_OVERLAY,
                world, buffers, level, actor.getId());
            world.popPose();
        }
    }

    /** Hooked-fish wriggle (fisher_v3_spec.flap): yaw, roll (deg) and a swing kick -- bursts that die down. */
    static float[] flap(float t) {
        float burst = 0.55F + 0.45F * Mth.sin(t * 2.3F) * Mth.sin(t * 1.1F + 0.7F);
        float yaw = 38F * burst * Mth.sin(t * 34F) + 12F * Mth.sin(t * 13F);
        float roll = 20F * burst * Mth.sin(t * 27F + 1.3F);
        float kick = 0.9F * burst * Mth.sin(t * 17F);
        return new float[] {yaw, roll, kick};
    }

    /** World offset of the dangling fish below the tip: 14 px of line, damped pendulum from the lift plus flap kicks. */
    private static Vec3 dangle(Matrix4f base, float sec, float ft) {
        float u = Math.max(0F, sec - FisherCastClock.LAND_UP);
        float ax = (float) (22.0 * Math.exp(-1.6 * u) * Math.sin(5.2 * u + 0.4)) + 5F * flap(ft)[2];
        float az = (float) (14.0 * Math.exp(-1.2 * u) * Math.cos(4.1 * u)) + 3F * flap(ft + 0.37F)[2];
        ax *= Mth.DEG_TO_RAD;
        az *= Mth.DEG_TO_RAD;
        float hang = 14F / 16F;
        // model space (+Y down, -Z forward) -> world through the posed entity matrix
        Vector3f d = base.transformDirection(hang * Mth.sin(ax), hang * Mth.cos(ax) * Mth.cos(az),
            -hang * Mth.sin(az), new Vector3f());
        float len = d.length();
        if (len > 1.0E-5F) {
            d.mul(hang / len);   // the entity pose may be scaled; the line length is in blocks
        }
        return new Vec3(d.x, d.y, d.z);
    }

    /** Beat sounds and water, once per crossing (vanilla sound events, client-local). */
    private static void cues(Level level, SettlerEntity actor, FisherCastClock.Sample clock,
                             FisherCastClock.State st, Vec3 target, Vec3 shore) {
        float before = clock.window() == st.lastCueWindow ? st.lastSeconds : -1F;
        float now = clock.seconds();
        st.lastCueWindow = clock.window();
        st.lastSeconds = now;
        RandomSource r = level.random;
        switch (clock.window()) {
            case CAST -> {
                if (crossed(before, now, FisherCastClock.RELEASE)) {
                    level.playLocalSound(actor.getX(), actor.getEyeY(), actor.getZ(), SoundEvents.FISHING_BOBBER_THROW,
                        SoundSource.NEUTRAL, 0.45F, 0.4F / (r.nextFloat() * 0.4F + 0.8F), false);
                }
                if (crossed(before, now, FisherCastClock.SPLASHDOWN)) {
                    level.playLocalSound(target.x, target.y, target.z, SoundEvents.FISHING_BOBBER_SPLASH,
                        SoundSource.NEUTRAL, 0.18F, 1.2F + r.nextFloat() * 0.2F, false);
                }
            }
            case REEL -> {
                if (crossed(before, now, 0.05F)) {
                    level.playLocalSound(target.x, target.y, target.z, SoundEvents.FISHING_BOBBER_SPLASH,
                        SoundSource.NEUTRAL, 0.35F, 1.0F + r.nextFloat() * 0.3F, false);
                    for (int i = 0; i < 6; i++) {
                        level.addParticle(ParticleTypes.BUBBLE, target.x + r.nextGaussian() * 0.15, target.y - 0.2,
                            target.z + r.nextGaussian() * 0.15, 0, 0.12, 0);
                    }
                }
                if (crossed(before, now, 0.45F)) {
                    level.playLocalSound(actor.getX(), actor.getEyeY(), actor.getZ(), SoundEvents.FISHING_BOBBER_RETRIEVE,
                        SoundSource.NEUTRAL, 0.5F, 0.9F + r.nextFloat() * 0.2F, false);
                }
                // the fish fights at the surface while it is drawn in
                if (now > 0.4F && now < 2.1F && r.nextInt(3) == 0) {
                    float u = smooth((now - 0.4F) / 1.7F);
                    Vec3 at = target.lerp(shore, u);
                    level.addParticle(ParticleTypes.FISHING, at.x + r.nextGaussian() * 0.1, at.y + 0.02,
                        at.z + r.nextGaussian() * 0.1, 0, 0.02, 0);
                }
            }
            case LAND -> {
                for (float flop : new float[] {0.55F, 0.98F, 1.42F, 2.12F}) {
                    if (crossed(before, now, flop)) {
                        level.playLocalSound(actor.getX(), actor.getEyeY(), actor.getZ(), SoundEvents.COD_FLOP,
                            SoundSource.NEUTRAL, 0.45F, 0.9F + r.nextFloat() * 0.3F, false);
                    }
                }
                if (now > FisherCastClock.LAND_UP && now < FisherCastClock.GRAB && st.lastFish != null && r.nextInt(4) == 0) {
                    Vec3 f = st.lastFish;
                    level.addParticle(r.nextBoolean() ? ParticleTypes.FALLING_WATER : ParticleTypes.SPLASH,
                        f.x + r.nextGaussian() * 0.08, f.y - 0.15, f.z + r.nextGaussian() * 0.08, 0, 0, 0);
                }
                if (crossed(before, now, 0.02F)) {
                    level.playLocalSound(shore.x, shore.y, shore.z, SoundEvents.FISHING_BOBBER_SPLASH,
                        SoundSource.NEUTRAL, 0.4F, 1.3F + r.nextFloat() * 0.2F, false);
                    for (int i = 0; i < 10; i++) {
                        level.addParticle(ParticleTypes.SPLASH, shore.x + r.nextGaussian() * 0.2, shore.y + 0.05,
                            shore.z + r.nextGaussian() * 0.2, r.nextGaussian() * 0.05, 0.2, r.nextGaussian() * 0.05);
                    }
                }
            }
            default -> {
            }
        }
    }

    private static boolean crossed(float before, float now, float mark) {
        return before < mark && now >= mark && now - mark < 0.5F;
    }

    private static float smooth(float x) {
        float c = Mth.clamp(x, 0F, 1F);
        return c * c * (3F - 2F * c);
    }

    /** Camera-relative tip of the held rod: SettlerModel hand + vanilla ItemInHandLayer + the item's display transform. */
    private static Vector3f rodTip(SettlerModel model, SettlerEntity actor, PoseStack pose) {
        ItemStack rod = actor.getMainHandItem();
        HumanoidArm arm = actor.getMainArm();
        boolean left = arm == HumanoidArm.LEFT;
        pose.pushPose();
        model.translateToHand(arm, pose);
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.translate((left ? -1F : 1F) / 16.0F, 0.125F, -0.625F);
        BakedModel baked = Minecraft.getInstance().getItemRenderer().getModel(rod, actor.level(), actor, actor.getId());
        baked.applyTransform(left ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
            pose, left);
        pose.translate(-0.5F, -0.5F, -0.5F);
        Vector3f tip = pose.last().pose().transformPosition(TIP_X, TIP_Y, TIP_Z, new Vector3f());
        pose.popPose();
        return tip;
    }

    private static Vec3 leftPalm(SettlerModel model, PoseStack pose, Vec3 cam) {
        pose.pushPose();
        model.translateToHand(HumanoidArm.LEFT, pose);
        Vector3f p = pose.last().pose().transformPosition(0F, 10.5F / 16F, -1F / 16F, new Vector3f());
        pose.popPose();
        return new Vec3(p.x + cam.x, p.y + cam.y, p.z + cam.z);
    }

    private static Vec3 toWorld(Matrix4f base, Vec3 cam, float x, float y, float z) {
        Vector3f p = base.transformPosition(x / 16F, y / 16F, z / 16F, new Vector3f());
        return new Vec3(p.x + cam.x, p.y + cam.y, p.z + cam.z);
    }

    /** Vanilla FishingHookRenderer-style strip; sag in blocks at the middle. */
    private static void drawLine(VertexConsumer lines, PoseStack.Pose pose, Vec3 from, Vec3 to, float sag) {
        int n = 16;
        float px = (float) from.x, py = (float) from.y, pz = (float) from.z;
        for (int i = 0; i <= n; i++) {
            float u = i / (float) n;
            float x = (float) Mth.lerp(u, from.x, to.x);
            float y = (float) Mth.lerp(u, from.y, to.y) - sag * 4F * u * (1F - u);
            float z = (float) Mth.lerp(u, from.z, to.z);
            float dx = x - px, dy = y - py, dz = z - pz;
            float len = Mth.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0E-4F) {
                dx = 0F; dy = 1F; dz = 0F; len = 1F;
            }
            lines.addVertex(pose, x, y, z).setColor(LINE_COLOUR).setNormal(pose, dx / len, dy / len, dz / len);
            px = x; py = y; pz = z;
        }
    }

    /** The water surface 3..6 blocks in front of the fisher (same probe as FxAtmosphere's splash), or null. */
    static BlockPos findWater(Level level, SettlerEntity settler) {
        float yaw = settler.yBodyRot * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw);
        double fz = Mth.cos(yaw);
        BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
        for (double d : new double[] {4.0D, 3.0D, 5.0D, 6.0D, 2.5D}) {
            int x = Mth.floor(settler.getX() + fx * d);
            int z = Mth.floor(settler.getZ() + fz * d);
            for (int y = Mth.floor(settler.getY()) + 1; y >= Mth.floor(settler.getY()) - 5; y--) {
                probe.set(x, y, z);
                if (level.getFluidState(probe).is(FluidTags.WATER)
                    && level.getBlockState(probe.above()).isAir()) {
                    return probe.immutable();
                }
            }
        }
        return null;
    }
}
