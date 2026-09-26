package com.hearthstead.client.screen;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2LayoutAssert;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CoinMerchantScreenLayoutTest {

    /** Vanilla 1.21.1 MerchantMenu slot offsets (server-owned; must never move). */
    private static List<Rect> merchantSlotWells() {
        List<Rect> wells = new ArrayList<>();
        wells.add(new Rect(136 - 1, 37 - 1, 18, 18));
        wells.add(new Rect(162 - 1, 37 - 1, 18, 18));
        wells.add(new Rect(220 - 1, 37 - 1, 18, 18));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) wells.add(new Rect(108 + col * 18 - 1, 84 + row * 18 - 1, 18, 18));
        }
        for (int col = 0; col < 9; col++) wells.add(new Rect(108 + col * 18 - 1, 142 - 1, 18, 18));
        return wells;
    }

    @Test
    void everySlotSitsOnTheParchmentAndNothingOverlapsAtGuiScaleTwoToFour() {
        for (int[] v : Ui2LayoutAssert.GUI_2_TO_4) {
            String at = v[0] + "x" + v[1];
            CoinMerchantScreen.Layout l = CoinMerchantScreen.layoutFor(v[0], v[1]);
            Ui2FrameLayout f = l.frame();
            Ui2LayoutAssert.inViewport(v[0], v[1], new Rect(f.x(), f.y(), f.width(), f.height()), at);
            // Vanilla centring of the slot image is kept horizontally.
            assertEquals((v[0] - CoinMerchantScreen.IMAGE_W) / 2, l.leftPos(), at);
            Rect content = l.localContent();
            List<Rect> pieces = new ArrayList<>();
            pieces.add(l.heading());
            for (int i = 0; i < CoinMerchantScreen.VISIBLE_ROWS; i++) pieces.add(l.row(i));
            pieces.add(l.scrollbar());
            pieces.add(l.detail());
            pieces.add(l.purse());
            pieces.add(l.inventoryLabel());
            List<Rect> wells = merchantSlotWells();
            for (Rect r : pieces) Ui2LayoutAssert.inside(content, r, at);
            for (Rect w : wells) Ui2LayoutAssert.inside(content, w, at + " slot well");
            List<Rect> all = new ArrayList<>(pieces);
            all.addAll(wells);
            Ui2LayoutAssert.disjoint(all, at);
            Rect close = f.close();
            Rect header = f.header();
            Ui2LayoutAssert.inside(header, close, at + " close key");
        }
    }
}
