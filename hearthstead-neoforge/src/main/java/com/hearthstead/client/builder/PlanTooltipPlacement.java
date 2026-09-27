package com.hearthstead.client.builder;

/**
 * Where the Blueprint style picker puts a card's material tooltip so it never
 * covers the other cards (owner, 27 Sep: the list covered cards 4 and 5).
 * Pure arithmetic, so it is unit-tested without a client.
 *
 * <p>Order of preference, each only if the whole tooltip (plus its drawn border)
 * fits on screen: below the picker frame, beside the frame on the hovered card's
 * side, beside it on the other side, above the frame. If nothing fits (a very
 * small window), it is placed over the hovered card's column and clamped to the
 * screen, which is the least-bad overlap.
 */
public final class PlanTooltipPlacement {

    /** Vanilla draws the tooltip background 4 px outside its text box; keep 2 px more air. */
    static final int MARGIN = 6;

    private PlanTooltipPlacement() {
    }

    /**
     * @return {x, y} of the tooltip's text box (the value a vanilla tooltip positioner returns)
     */
    public static int[] place(int screenW, int screenH, int frameX, int frameY, int frameW, int frameH,
                              int cardX, int cardW, int tipW, int tipH) {
        int frameRight = frameX + frameW;
        int frameBottom = frameY + frameH;
        int centredX = clamp(cardX + cardW / 2 - tipW / 2, MARGIN, screenW - tipW - MARGIN);
        boolean leftHalf = cardX + cardW / 2 < frameX + frameW / 2;
        int sideY = clamp(frameY, MARGIN, screenH - tipH - MARGIN);

        // 1. Below the whole picker, under the hovered card.
        int belowY = frameBottom + MARGIN;
        if (belowY + tipH + MARGIN <= screenH && tipW + 2 * MARGIN <= screenW) {
            return new int[] {centredX, belowY};
        }
        // 2./3. Beside the picker, nearest side first.
        int leftX = frameX - MARGIN - tipW;
        int rightX = frameRight + MARGIN;
        boolean leftFits = leftX >= MARGIN && tipH + 2 * MARGIN <= screenH;
        boolean rightFits = rightX + tipW + MARGIN <= screenW && tipH + 2 * MARGIN <= screenH;
        if (leftHalf ? leftFits : rightFits) {
            return new int[] {leftHalf ? leftX : rightX, sideY};
        }
        if (leftHalf ? rightFits : leftFits) {
            return new int[] {leftHalf ? rightX : leftX, sideY};
        }
        // 4. Above the picker.
        int aboveY = frameY - MARGIN - tipH;
        if (aboveY >= MARGIN && tipW + 2 * MARGIN <= screenW) {
            return new int[] {centredX, aboveY};
        }
        // 5. Nothing fits: over the hovered card's own column, clamped to the screen.
        return new int[] {centredX, clamp(frameY, MARGIN, screenH - tipH - MARGIN)};
    }

    private static int clamp(int v, int lo, int hi) {
        return hi < lo ? lo : Math.max(lo, Math.min(hi, v));
    }
}
