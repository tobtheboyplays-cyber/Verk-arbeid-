package com.hearthstead.client.builder;

import com.hearthstead.client.ui2.Ui2FrameLayout;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Blueprint picker tooltip must not cover the neighbouring style cards (owner screenshot, 27 Sep). */
class PlanTooltipPlacementTest {

    private static final int GAP = 6;
    private static final int CARDS = PlanStyleScreen.MAX_CARDS;
    /** The drawn tooltip background reaches 4 px outside its text box. */
    private static final int BORDER = 4;

    @Test
    void tooltipNeverCoversAnotherCardWhenThereIsRoomOutsideThePicker() {
        int[][] viewports = {{960, 540}, {640, 360}, {854, 480}, {1280, 720}, {683, 384}};
        int[][] tips = {{150, 142}, {200, 150}, {110, 60}, {120, 32}};
        for (int[] v : viewports) {
            Ui2FrameLayout frame = Ui2FrameLayout.centred(v[0], v[1], 464, 256, true);
            int[][] cards = cards(frame);
            for (int[] t : tips) {
                boolean room = frame.bottom() + PlanTooltipPlacement.MARGIN * 2 + t[1] <= v[1]
                    || frame.x() - PlanTooltipPlacement.MARGIN * 2 - t[0] >= 0
                    || frame.right() + PlanTooltipPlacement.MARGIN * 2 + t[0] <= v[0]
                    || frame.y() - PlanTooltipPlacement.MARGIN * 2 - t[1] >= 0;
                for (int h = 0; h < CARDS; h++) {
                    int[] p = PlanTooltipPlacement.place(v[0], v[1], frame.x(), frame.y(), frame.width(),
                        frame.height(), cards[h][0], cards[h][2], t[0], t[1]);
                    int x0 = p[0] - BORDER, y0 = p[1] - BORDER, x1 = p[0] + t[0] + BORDER, y1 = p[1] + t[1] + BORDER;
                    assertTrue(x0 >= 0 && y0 >= 0 && x1 <= v[0] && y1 <= v[1],
                        "on screen " + v[0] + "x" + v[1] + " tip " + t[0] + "x" + t[1]);
                    if (!room) {
                        continue;
                    }
                    for (int o = 0; o < CARDS; o++) {
                        if (o == h) {
                            continue;
                        }
                        int[] c = cards[o];
                        boolean overlap = x0 < c[0] + c[2] && x1 > c[0] && y0 < c[1] + c[3] && y1 > c[1];
                        assertFalse(overlap, "tooltip of card " + (h + 1) + " covers card " + (o + 1)
                            + " at " + v[0] + "x" + v[1] + " tip " + t[0] + "x" + t[1]);
                    }
                }
            }
        }
    }

    @Test
    void preferredSpotIsBelowThePickerAt1080pGui2ForAShortList() {
        Ui2FrameLayout frame = Ui2FrameLayout.centred(960, 540, 464, 256, true);
        int[][] cards = cards(frame);
        int[] p = PlanTooltipPlacement.place(960, 540, frame.x(), frame.y(), frame.width(), frame.height(),
            cards[3][0], cards[3][2], 120, 60);
        assertTrue(p[1] >= frame.bottom(), "below the frame");
    }

    /** Same geometry as PlanStyleScreen.cardsArea/cardX/cardWidth with five cards: {x, y, w, h}. */
    private static int[][] cards(Ui2FrameLayout frame) {
        var c = frame.content();
        int areaH = Math.max(40, frame.footer().y() - 6 - c.y());
        int cw = (c.width() - GAP * (CARDS - 1)) / CARDS;
        int used = CARDS * cw + (CARDS - 1) * GAP;
        int[][] out = new int[CARDS][];
        for (int i = 0; i < CARDS; i++) {
            out[i] = new int[] {c.x() + (c.width() - used) / 2 + i * (cw + GAP), c.y(), cw, areaH};
        }
        return out;
    }
}
