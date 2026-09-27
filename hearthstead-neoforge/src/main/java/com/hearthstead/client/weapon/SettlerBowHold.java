package com.hearthstead.client.weapon;

import com.hearthstead.HearthsteadServerConfig;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.ArmedModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import org.joml.Quaternionf;

/**
 * Settler-side hold for the VANILLA bow (owner, 26 Sep): archers, hunters and the Captain carry
 * the bow 1.22x the vanilla held size, in one of three holds. Players keep vanilla's bow.
 *
 * <ul>
 *   <li>{@link State#IDLE}: hanging at the side, upper limb back over the shoulder.</li>
 *   <li>{@link State#LOW_READY}: across the front of the hips in the OFF (left) hand, tips left /
 *       right, string toward the belly, the bow's back forward and tilted down (owner reference) --
 *       archers on patrol, in combat, out of ammo or retreating (the anim lane's stance gate) and not
 *       drawing. The nocked arrow ({@code bow_pulling_0}) returns once the final arm pose lands.</li>
 *   <li>{@link State#DRAWING}: {@code isUsingItem} (ArcherAttackGoal / HunterWorkGoal): upright in
 *       the extended fist, string to the chest; vanilla's pull frames switch as usual.</li>
 * </ul>
 * Rendered with {@link ItemDisplayContext#NONE} after the settler's own {@code translateToHand}
 * (so clip wrist keys still apply) and ItemInHandLayer's hand offset, then the solved transform.
 * Transforms: tools/weapons/gen_bows.py solve_vanilla_settler_bow() (settler_bow_hold.json);
 * clearance audit: settler_bow_clearance.json. Hooked from SettlerRenderer's item layer.
 */
public final class SettlerBowHold {
    public enum State { IDLE, LOW_READY, DRAWING }

    /**
     * One hold: which hand draws the main-hand bow, and the transform after ItemInHandLayer's chain.
     * {@code mirrored}: values in item-model JSON convention (x translation and y/z rotation are
     * mirrored for a left hand, like ItemTransform.apply). Raw holds (the anim lane's left-hand
     * solves) are applied exactly as given.
     */
    record Hold(boolean offHand, float[] t, float[] r, float scale, boolean mirrored, boolean nocked,
                boolean looseArrow, float[] matrix, float[] arrowMatrix) {
        Hold(boolean offHand, float[] t, float[] r, float scale, boolean mirrored, boolean nocked,
             boolean looseArrow) {
            this(offHand, t, r, scale, mirrored, nocked, looseArrow, null, null);
        }
    }

    /**
     * ARCHER v5 LONGBOW (owner-approved 26 Sep, anim lane + weapons lane): the vanilla bow STRETCHED along
     * its limbs into a longbow (27 px span), across the hips in the LEFT hand, with a separate long nocked
     * arrow pointing forward-down that the right hand pinches. Not a plain T.R.S display, so the full
     * matrix is given: column-major, block units, acting on ItemRenderer model coordinates, applied in the
     * hand frame translateToHand(LEFT) . Rx(-90) Ry(180) T(-1,2,-10)/16 with ItemDisplayContext.NONE.
     * Solved on the ARCHER_STANCE arm pose (anim-overkill/lr_v5_reach.py -> archer_low_v5r.json; the
     * weapons lane's longbow search + clearance audit, bow and arrow clip 0.0 px, nock within the right
     * arm's reach). Only used when the settler plays the authored archer clips (motion engine on).
     */
    static final Hold ARCHER_LOW_V5 = new Hold(true, null, null, 1.0F, false, false, true,
        new float[] {1.204554F, 0.030963F, 0.129148F, 0.0F, 0.675196F, 0.643789F, -0.854699F, 0.0F,
            -0.081036F, 0.825624F, 0.557872F, 0.0F, 0.227451F, -0.191234F, 0.121983F, 1.0F},
        new float[] {0.172835F, 0.188420F, -0.951829F, 0.0F, -0.788801F, -0.142575F, -0.643954F, 0.0F,
            -0.212288F, 0.712003F, 0.102398F, 0.0F, 0.099251F, -0.132527F, -0.268198F, 1.0F});
    /**
     * ARCHER v5 DRAWING (weapons lane tools/weapons/draw_v5.json): the drawing bow mildly stretched
     * (26 px) so the longbow does not shrink at the low -> draw switch; grip on the ARCHER_DRAW full-draw
     * fist, the pulling_2 nock on the right-jaw anchor (0.09 px), pull frames 0.0 px clip.
     */
    static final Hold ARCHER_DRAW_V5 = new Hold(true, null, null, 1.0F, false, false, false,
        new float[] {-0.999090F, 0.773333F, -0.181136F, 0.0F, -0.832020F, -0.671127F, -0.726867F, 0.0F,
            -0.472407F, -0.397658F, 0.907912F, 0.0F, -0.043717F, 0.105171F, 0.045544F, 1.0F}, null);
    /** ARCHER_RELOAD: the fetched arrow is nocked at 0.66 s; before that the bow shows no nocked arrow. */
    static final long ARCHER_RELOAD_NOCK_MS = 660L;
    /**
     * HUNTER v2 (anim lane, owner 26 Sep): the hunter's SHORT hunting bow (vanilla bow at 0.95) in the LEFT hand,
     * low in front of the hips while stalking, an arrow nocked and pinched by the right hand (the old hunter
     * rendered the bow in the drawing hand). Solved on HUNTER_READY / HUNTER_STALK's arm pose
     * (anim-overkill/hunter_low_disp.json: weapons-lane clearance audit, bow and arrow clip 0.0 px, nock inside
     * the right arm's reach). Matrix convention as {@link #ARCHER_LOW_V5}.
     */
    static final Hold HUNTER_LOW_V2 = new Hold(true, null, null, 1.0F, false, false, true,
        new float[] {0.915022F, -0.086189F, 0.191049F, 0.000000F, 0.206415F, 0.603564F, -0.718964F, 0.000000F, -0.056151F, 0.734004F, 0.600069F, 0.000000F, 0.222283F, -0.210139F, 0.119093F, 1.000000F},
        new float[] {0.130088F, 0.263547F, -0.825999F, 0.000000F, -0.772521F, -0.142107F, -0.441546F, 0.000000F, -0.231661F, 0.689331F, 0.183456F, 0.000000F, 0.078197F, -0.144905F, -0.159182F, 1.000000F});
    /** HUNTER v2 drawing: upright in the left fist on HUNTER_DRAW's full-draw arm, the pulling_2 nock at the jaw. */
    static final Hold HUNTER_DRAW_V2 = new Hold(true, new float[] {0.2094F, 1.3946F, 0.132F},
        new float[] {5.337F, -13.122F, 132.816F}, 0.95F, false, false, false);
    /** HUNTER_DRAW: the release at 0.70 s (tick 14); the next arrow is nocked at 1.12 s. */
    static final long HUNTER_RELEASE_MS = 700L;
    static final long HUNTER_NOCK_MS = 1120L;
    /** The hunter's own pull frames: pulling_1 / pulling_2 at 0.65 / 0.9 of its 14-tick draw. */
    public static final ModelResourceLocation BOW_PULL_1 =
        ModelResourceLocation.standalone(ResourceLocation.withDefaultNamespace("item/bow_pulling_1"));
    public static final ModelResourceLocation BOW_PULL_2 =
        ModelResourceLocation.standalone(ResourceLocation.withDefaultNamespace("item/bow_pulling_2"));

    /** 0.9 (vanilla item/bow third-person scale) x 1.22. */
    static final float SCALE = 1.098F;
    /** IDLE: main (right) hand, hanging at the side (weapons lane, settler_bow_hold.json). */
    static final Hold IDLE = new Hold(false, new float[] {0.0F, -5.7806F, 0.472F},
        new float[] {0.0F, -88.647F, -75.9F}, SCALE, true, false, false);
    /**
     * LOW READY (owner reference; right-handed archer): the bow in the LEFT hand across the hips, string
     * to the belly, back forward; an arrow loosely nocked on the resting string, pointing forward and
     * ~38 deg down. The anim lane's solve (anim-overkill/archer_low_ready.json, raw left-hand convention)
     * turned 4 deg yaw / 2 deg roll about the grip and scaled 0.95 by the weapons lane so the string and
     * arrow clear the hunched belly (tools/weapons/tweak_low_ready.py -> low_ready_final.json, 0.0 px).
     * The arrow is a separate vanilla arrow sprite (the nocked bow_pulling_0 frame pulls the string into
     * the belly).
     */
    static final Hold LOW_READY = new Hold(true, new float[] {3.5141F, -1.297F, 2.3128F},
        new float[] {-79.721F, -23.721F, -8.696F}, 0.95F, false, false, true);
    /**
     * DRAWING: the right-handed archer draws with the bow in the LEFT hand (anim lane, archer_draw_left.json,
     * raw convention): upright, small cant, the pulling_2 nock at the right-jaw anchor.
     */
    static final Hold DRAWING = new Hold(true, new float[] {-0.4775F, 1.6807F, 0.722F},
        new float[] {22.639F, -26.926F, 143.63F}, SCALE, false, false, false);

    /** Loose arrow on the resting string: nock at item/bow sprite px (9.5, 7.5), along (-1, 1). */
    static final float[] ARROW_NOCK = {9.5F, 7.5F, 9.0F};
    /** Vanilla arrow sprite: nock (3.5, 2.5) -> head (13.5, 13.5), 47.7 deg; the bow's back is 135 deg. */
    static final float[] ARROW_SPRITE_NOCK = {3.5F, 2.5F, 8.0F};
    static final float ARROW_ROT_Z = 87.3F;
    static final float ARROW_SCALE = 0.8F;
    private static final ItemStack ARROW = new ItemStack(net.minecraft.world.item.Items.ARROW);

    static Hold hold(State state) {
        return switch (state) {
            case IDLE -> IDLE;
            case LOW_READY -> LOW_READY;
            case DRAWING -> DRAWING;
        };
    }

    /** The archer's authored v5 cycle (motion engine) gets the longbow; the hunter and the Captain keep theirs. */
    static Hold hold(State state, SettlerEntity settler) {
        if (settler.getProfession() == Profession.ARCHER
            && com.hearthstead.client.motion.MotionSettings.engineEnabled()) {
            if (state == State.LOW_READY) return ARCHER_LOW_V5;
            if (state == State.DRAWING) return ARCHER_DRAW_V5;
        }
        if (settler.getProfession() == Profession.HUNTER
            && com.hearthstead.client.motion.MotionSettings.engineEnabled()) {
            if (state == State.LOW_READY) return HUNTER_LOW_V2;
            if (state == State.DRAWING) return HUNTER_DRAW_V2;
        }
        return hold(state);
    }

    /** The vanilla nocked-arrow frame, registered as a standalone model by {@link WeaponClient}. */
    public static final ModelResourceLocation BOW_NOCKED =
        ModelResourceLocation.standalone(ResourceLocation.withDefaultNamespace("item/bow_pulling_0"));

    private SettlerBowHold() {
    }

    /**
     * True when this stack in a settler's hand is drawn (or hidden) by this hold instead of vanilla's:
     * any bow, and the OFFHAND item while the main-hand bow is at low ready (the bow sits in that hand).
     */
    public static boolean applies(LivingEntity entity, ItemStack stack) {
        if (!(entity instanceof SettlerEntity settler) || !HearthsteadServerConfig.captainWeaponsEnabled()) {
            return false;
        }
        if (stack.getItem() instanceof BowItem) {
            return true;
        }
        return stack == settler.getOffhandItem() && settler.getMainHandItem().getItem() instanceof BowItem
            && hold(stateOf(settler, settler.getMainArm()), settler).offHand();
    }

    public static State stateOf(SettlerEntity settler, HumanoidArm arm) {
        InteractionHand hand = arm == settler.getMainArm() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND;
        if (settler.isUsingItem() && settler.getUsedItemHand() == hand) {
            return State.DRAWING;
        }
        if (hand == InteractionHand.MAIN_HAND && lowReady(settler)) {
            return State.LOW_READY;
        }
        return State.IDLE;
    }

    /** The anim lane's archer stance gate (26 Sep): archer (or hunter on the hunt) with a real bow. */
    static boolean lowReady(SettlerEntity settler) {
        Profession p = settler.getProfession();
        SettlerActivity a = settler.getActivity();
        boolean archer = p == Profession.ARCHER
            && (a == SettlerActivity.PATROLLING || a == SettlerActivity.COMBAT
                || a == SettlerActivity.OUT_OF_AMMO || a == SettlerActivity.RETREATING);
        boolean hunter = p == Profession.HUNTER && (a == SettlerActivity.WORK_HUNT
            // Hunter v2: the bow is carried low in the left hand, arrow nocked, while stalking
            || a == SettlerActivity.TRACKING_GAME && com.hearthstead.client.motion.MotionSettings.engineEnabled());
        return (archer || hunter) && settler.hasPhysicalMainhandBow();
    }

    /** Replaces ItemInHandLayer.renderArmWithItem for a bow in a settler's hand. */
    public static void render(ArmedModel model, LivingEntity entity, ItemStack stack, HumanoidArm arm,
                              PoseStack pose, MultiBufferSource buffers, int light, ItemInHandRenderer renderer) {
        SettlerEntity settler = (SettlerEntity) entity;
        if (!(stack.getItem() instanceof BowItem)) {
            return;                                   // the offhand is holding the low-ready bow
        }
        if (settler.getProfession() == Profession.HUNTER && settler.pickupState.isStarted()
            && com.hearthstead.client.motion.MotionSettings.engineEnabled()) {
            return;                                   // HUNTER_TAKE_KILL: both hands on the kill, the bow slung
        }
        State state = stateOf(settler, arm);
        Hold h = hold(state, settler);
        if (h.offHand()) {
            arm = arm.getOpposite();                  // a right-handed archer's bow hand
        }
        boolean left = arm == HumanoidArm.LEFT;
        float side = left ? -1.0F : 1.0F;
        if (h.matrix() != null) {
            renderMatrixHold(h, settler, stack, arm, left, side, model, pose, buffers, light, renderer);
            return;
        }
        float m = h.mirrored() ? side : 1.0F;
        float[] t = h.t();
        float[] r = h.r();
        pose.pushPose();
        model.translateToHand(arm, pose);
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.translate(side / 16.0F, 0.125F, -0.625F);
        pose.translate(m * t[0] / 16.0F, t[1] / 16.0F, t[2] / 16.0F);
        pose.mulPose(new Quaternionf().rotationXYZ(r[0] * Mth.DEG_TO_RAD, m * r[1] * Mth.DEG_TO_RAD,
            m * r[2] * Mth.DEG_TO_RAD));
        pose.scale(h.scale(), h.scale(), h.scale());
        BakedModel nocked = h.nocked() ? nockedModel() : h == HUNTER_DRAW_V2 ? hunterPullModel(settler) : null;
        if (nocked != null) {
            Minecraft.getInstance().getItemRenderer().render(stack, ItemDisplayContext.NONE, left, pose, buffers,
                light, OverlayTexture.NO_OVERLAY, nocked);
        } else {
            renderer.renderItem(entity, stack, ItemDisplayContext.NONE, left, pose, buffers, light);
        }
        if (h.looseArrow()) {
            // bow model space (px, origin at the model corner) -> the nock, turned along the bow's back
            pose.pushPose();
            pose.translate((ARROW_NOCK[0] - 8.0F) / 16.0F, (ARROW_NOCK[1] - 8.0F) / 16.0F,
                (ARROW_NOCK[2] - 8.0F) / 16.0F);
            pose.mulPose(Axis.ZP.rotationDegrees(ARROW_ROT_Z));
            pose.scale(ARROW_SCALE, ARROW_SCALE, ARROW_SCALE);
            pose.translate((8.0F - ARROW_SPRITE_NOCK[0]) / 16.0F, (8.0F - ARROW_SPRITE_NOCK[1]) / 16.0F,
                (8.0F - ARROW_SPRITE_NOCK[2]) / 16.0F);
            Minecraft.getInstance().getItemRenderer().renderStatic(entity, ARROW, ItemDisplayContext.NONE, left,
                pose, buffers, entity.level(), light, OverlayTexture.NO_OVERLAY, entity.getId());
            pose.popPose();
        }
        pose.popPose();
    }

    private static void renderMatrixHold(Hold h, SettlerEntity settler, ItemStack stack, HumanoidArm arm,
                                         boolean left, float side, ArmedModel model, PoseStack pose,
                                         MultiBufferSource buffers, int light, ItemInHandRenderer renderer) {
        pose.pushPose();
        model.translateToHand(arm, pose);
        pose.mulPose(Axis.XP.rotationDegrees(-90.0F));
        pose.mulPose(Axis.YP.rotationDegrees(180.0F));
        pose.translate(side / 16.0F, 0.125F, -0.625F);
        pose.pushPose();
        pose.mulPose(new org.joml.Matrix4f().set(h.matrix()));
        renderer.renderItem(settler, stack, ItemDisplayContext.NONE, left, pose, buffers, light);
        pose.popPose();
        // The nocked arrow: hidden while ARCHER_RELOAD / HUNTER_DRAW fetches the next one (its prop is in the right hand).
        boolean fetching = settler.getProfession() == Profession.HUNTER
            ? settler.huntState.isStarted() && settler.huntState.getAccumulatedTime() >= HUNTER_RELEASE_MS
                && settler.huntState.getAccumulatedTime() < HUNTER_NOCK_MS
            : settler.archerLooseState.isStarted()
                && settler.archerLooseState.getAccumulatedTime() < ARCHER_RELOAD_NOCK_MS;
        if (h.looseArrow() && h.arrowMatrix() != null && !fetching) {
            pose.pushPose();
            pose.mulPose(new org.joml.Matrix4f().set(h.arrowMatrix()));
            Minecraft.getInstance().getItemRenderer().renderStatic(settler, ARROW, ItemDisplayContext.NONE, left,
                pose, buffers, settler.level(), light, OverlayTexture.NO_OVERLAY, settler.getId());
            pose.popPose();
        }
        pose.popPose();
    }

    /** The hunter's pull frame on its own 14-tick draw (vanilla's predicate reads a 20-tick pull). */
    private static BakedModel hunterPullModel(SettlerEntity settler) {
        float pull = settler.getTicksUsingItem() / (float) com.hearthstead.entity.ai.HunterWorkGoal.HUNT_RELEASE_TICK;
        ModelResourceLocation loc = pull >= 0.9F ? BOW_PULL_2 : pull >= 0.65F ? BOW_PULL_1 : BOW_NOCKED;
        BakedModel m = Minecraft.getInstance().getModelManager().getModel(loc);
        return m == Minecraft.getInstance().getModelManager().getMissingModel() ? null : m;
    }

    private static BakedModel nockedModel() {
        BakedModel m = Minecraft.getInstance().getModelManager().getModel(BOW_NOCKED);
        return m == Minecraft.getInstance().getModelManager().getMissingModel() ? null : m;
    }
}
