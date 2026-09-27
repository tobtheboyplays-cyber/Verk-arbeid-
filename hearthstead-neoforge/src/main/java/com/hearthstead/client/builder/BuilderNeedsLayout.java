package com.hearthstead.client.builder;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import java.util.ArrayList;
import java.util.List;

/** One bounded page. Rows adapt to the viewport; hidden data has an overflow tooltip. */
public record BuilderNeedsLayout(Ui2FrameLayout frame, Rect site, Rect stage, Rect heading,
                                 Rect columns, List<Rect> rows, Rect overflow, Rect help, Rect status,
                                 Rect inventory) {
    public static final int MAX_WIDTH = 464, MAX_HEIGHT = 256;
    public static BuilderNeedsLayout forViewport(int width, int height) {
        Ui2FrameLayout frame = Ui2FrameLayout.centred(width, height, MAX_WIDTH, MAX_HEIGHT, false);
        Rect body = frame.body(false, true);
        int x = body.x(), y = body.y(), w = body.width();
        Rect site = new Rect(x, y, w, 12);
        Rect stage = new Rect(x, y + 12, w, 12);
        Rect heading = new Rect(x, y + 24, w, 12);
        Rect columns = new Rect(x, y + 36, w, 12);
        int count = Math.max(0, Math.min(4, (body.height() - 88) / 16));
        List<Rect> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) rows.add(new Rect(x, y + 48 + i * 16, w, 16));
        int bottom = y + 48 + count * 16;
        return new BuilderNeedsLayout(frame, site, stage, heading, columns, List.copyOf(rows),
            new Rect(x, bottom, w, 12), new Rect(x, bottom + 12, w, 14),
            new Rect(x, bottom + 26, w, 14), frame.footerButtons(1, 100)[0]);
    }

    /** Relative starts for Need, Hut/bag, Warehouse, On way, Missing. */
    public int[] columnStarts() {
        int metricWidth = Math.min(232, columns.width() - 70);
        int first = columns.right() - metricWidth;
        return new int[] {first, first + metricWidth * 36 / 232, first + metricWidth * 84 / 232,
            first + metricWidth * 144 / 232, first + metricWidth * 188 / 232, columns.right()};
    }
}
