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
                boolean looseArrow) {
    }

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
            && hold(stateOf(settler, settler.getMainArm())).offHand();
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
        boolean hunter = p == Profession.HUNTER && a == SettlerActivity.WORK_HUNT;
        return (archer || hunter) && settler.hasPhysicalMainhandBow();
    }

    /** Replaces ItemInHandLayer.renderArmWithItem for a bow in a settler's hand. */
    public static void render(ArmedModel model, LivingEntity entity, ItemStack stack, HumanoidArm arm,
                              PoseStack pose, MultiBufferSource buffers, int light, ItemInHandRenderer renderer) {
        SettlerEntity settler = (SettlerEntity) entity;
        if (!(stack.getItem() instanceof BowItem)) {
            return;                                   // the offhand is holding the low-ready bow
        }
        State state = stateOf(settler, arm);
        Hold h = hold(state);
        if (h.offHand()) {
            arm = arm.getOpposite();                  // a right-handed archer's bow hand
        }
        boolean left = arm == HumanoidArm.LEFT;
        float side = left ? -1.0F : 1.0F;
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
        BakedModel nocked = h.nocked() ? nockedModel() : null;
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

    private static BakedModel nockedModel() {
        BakedModel m = Minecraft.getInstance().getModelManager().getModel(BOW_NOCKED);
        return m == Minecraft.getInstance().getModelManager().getMissingModel() ? null : m;
    }
}
