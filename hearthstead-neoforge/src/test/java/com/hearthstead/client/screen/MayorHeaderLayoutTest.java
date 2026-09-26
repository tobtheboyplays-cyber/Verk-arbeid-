package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Mayor's Seat header (survival QA s2-046: status lines were cut in a
 * 135px box). Row 1: serif title, then status line 1 right-aligned up to the
 * close key; row 2: status line 2 across the header. Checked at GUI 2-4.
 */
class MayorHeaderLayoutTest {
    /** Minecraft font is at most 6px per character; "In mourning — 2d 22h left" is 25. */
    private static final int MOURNING_LINE_PX = 25 * 6;
    /** "The Mayor's boon remains unavailable until mourning ends." at ~5.5px average. */
    private static final int BOON_LINE_PX = 57 * 55 / 10;

    @Test
    void headerRowsFitWithoutOverlapAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            int pw = HearthScreen.mayorLayoutFor(v[0], v[1]).panelWidth();
            int titleW = HearthScreen.mayorTitleBox(pw);
            int[] lines = HearthScreen.mayorHeaderLines(pw, titleW);
            Rect title = new Rect(12, 9, titleW, 12);
            Rect line1 = new Rect(lines[0], 13, lines[1] - lines[0], 9);
            Rect close = new Rect(pw - 12 - 11, 11, 11, 11);
            Rect line2 = new Rect(lines[2], 28, lines[3] - lines[2], 9);
            Rect header = new Rect(0, 6, pw, 36);
            for (Rect r : List.of(title, line1, close, line2)) Ui2LayoutAssert.inside(header, r, at);
            Ui2LayoutAssert.disjoint(List.of(title, line1, close), at + " row 1");
            Ui2LayoutAssert.disjoint(List.of(line2, title, close), at + " row 2");
            assertTrue(line1.width() >= MOURNING_LINE_PX,
                "mourning countdown fits unshortened at " + at + " (" + line1.width() + "px)");
            assertTrue(line2.width() >= BOON_LINE_PX,
                "boon line fits unshortened at " + at + " (" + line2.width() + "px)");
        }
    }
}
