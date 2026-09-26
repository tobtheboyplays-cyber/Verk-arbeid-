package com.hearthstead.client.motion;

/**
 * The bend-bone contract shared by the renderer, the JSON clips and any
 * external authoring tool (Blockbench, the Blender pipeline).
 *
 * <p>Each limb has one EMPTY child part at its midpoint whose rotation is
 * the joint bend: {@code right_forearm}/{@code left_forearm} at arm-local
 * (0, 4, 0) -- the elbow of the -2..10 arm -- and {@code right_shin}/
 * {@code left_shin} at leg-local (0, 6, 0) -- the knee of the 0..12 leg.
 * Java sign: elbow flexion is NEGATIVE X (the forearm swings forward and up
 * like an arm raise), knee flexion is POSITIVE X (the heel goes back).
 * Rotation composes like every ModelPart: {@code rotationZYX(z, y, x)}.
 */
public final class MotionBones {
    public static final int RIGHT_FOREARM = 0;
    public static final int LEFT_FOREARM = 1;
    public static final int RIGHT_SHIN = 2;
    public static final int LEFT_SHIN = 3;

    public static final String[] BEND_BONES = {
        "right_forearm", "left_forearm", "right_shin", "left_shin"};
    public static final String[] LIMB_BONES = {
        "right_arm", "left_arm", "right_leg", "left_leg"};
    /** Joint pivot along the limb's local Y, in model pixels. */
    public static final float[] BEND_PIVOT_Y = {4.0F, 4.0F, 6.0F, 6.0F};

    private MotionBones() {
    }

    public static int bendIndex(String bone) {
        for (int i = 0; i < BEND_BONES.length; i++) {
            if (BEND_BONES[i].equals(bone)) {
                return i;
            }
        }
        return -1;
    }
}
