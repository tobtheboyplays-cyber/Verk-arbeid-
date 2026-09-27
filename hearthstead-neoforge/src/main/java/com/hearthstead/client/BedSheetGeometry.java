package com.hearthstead.client;

import com.hearthstead.network.BedMarkersPayload;
import net.minecraft.core.Direction;

/**
 * Pure geometry and colour rules for the bed status sheet. The sheet is a
 * tinted blanket laid just above the vanilla blanket (top at 9/16): the whole
 * foot half, and the head half up to the pillow (the pillow covers the 7/16
 * nearest the head end). Rectangles are in the block's own local frame
 * (0..1 on X and Z), so the renderer can light each half with its own block.
 */
public final class BedSheetGeometry {
    /** Vanilla blanket top: 3 px legs + 6 px mattress. */
    public static final float BLANKET_TOP = 9F / 16F;
    /** Raised just enough to never z-fight with the bed model. */
    public static final float SHEET_Y = BLANKET_TOP + 0.0125F;
    /** Bottom of the thin fold that hangs over the mattress sides. */
    public static final float FOLD_BOTTOM = 6F / 16F;
    /** Outward offset of the fold from the bed's side faces. */
    public static final float OUTSET = 0.01F;
    /** How far the blanket reaches onto the head half, from the foot side. */
    public static final float HEAD_BLANKET = 9F / 16F;
    /** Folds are shaded a little darker so the edge reads as cloth turning down. */
    public static final float FOLD_SHADE = 0.8F;

    /** Muted medieval palette, tuned brighter than the UI tokens so it survives world lighting. */
    public static final int YELLOW_OCHRE = 0xD6AA3E; // free and valid
    public static final int MEADOW_GREEN = 0x6C9A4E; // taken and valid
    public static final int BRICK_RED = 0xB0473A;    // not in a valid room

    private BedSheetGeometry() {}

    /** Status to sheet colour: yellow free, green taken, red not in a valid room. */
    public static int tint(byte state) {
        return switch (state) {
            case BedMarkersPayload.FREE -> YELLOW_OCHRE;
            case BedMarkersPayload.ASSIGNED -> MEADOW_GREEN;
            case BedMarkersPayload.INVALID -> BRICK_RED;
            default -> throw new IllegalArgumentException("Bed state " + state);
        };
    }

    /**
     * Fills {@code out} with {minX, minZ, maxX, maxZ} of the sheet top on one bed
     * half, local to that half's block. {@code facing} is the bed's FACING (foot
     * toward head). The two long sides and the foot end stick out by OUTSET so
     * the top meets the folds.
     */
    public static void sheetRect(Direction facing, boolean head, float[] out) {
        if (facing.getAxis() == Direction.Axis.Y) throw new IllegalArgumentException("Bed facing " + facing);
        float alongMin, alongMax;
        boolean positive = facing.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        if (head) {
            // The foot-side edge of the head block is the side opposite FACING.
            alongMin = positive ? 0F : 1F - HEAD_BLANKET;
            alongMax = positive ? HEAD_BLANKET : 1F;
        } else {
            // The foot end is the far side from the head; the fold hangs there.
            alongMin = positive ? -OUTSET : 0F;
            alongMax = positive ? 1F : 1F + OUTSET;
        }
        float acrossMin = -OUTSET, acrossMax = 1F + OUTSET;
        if (facing.getAxis() == Direction.Axis.Z) {
            out[0] = acrossMin; out[1] = alongMin; out[2] = acrossMax; out[3] = alongMax;
        } else {
            out[0] = alongMin; out[1] = acrossMin; out[2] = alongMax; out[3] = acrossMax;
        }
    }

    /** Number of hanging folds on a half: both long sides, plus the foot end on the foot half. */
    public static int foldCount(boolean head) { return head ? 2 : 3; }

    /** The outward direction of fold {@code index} (0..foldCount-1). */
    public static Direction foldSide(Direction facing, boolean head, int index) {
        return switch (index) {
            case 0 -> facing.getClockWise();
            case 1 -> facing.getCounterClockWise();
            case 2 -> {
                if (head) throw new IllegalArgumentException("Head half has two folds");
                yield facing.getOpposite();
            }
            default -> throw new IllegalArgumentException("Fold " + index);
        };
    }

    /** Scales a 0xRRGGBB colour channel-wise (for the darker fold). */
    public static int shade(int rgb, float factor) {
        int r = Math.min(255, Math.round(((rgb >> 16) & 0xFF) * factor));
        int g = Math.min(255, Math.round(((rgb >> 8) & 0xFF) * factor));
        int b = Math.min(255, Math.round((rgb & 0xFF) * factor));
        return r << 16 | g << 8 | b;
    }
}
