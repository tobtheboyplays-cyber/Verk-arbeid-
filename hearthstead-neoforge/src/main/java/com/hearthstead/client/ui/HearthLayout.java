package com.hearthstead.client.ui;

/** Responsive Hearth workspace. Inventory slots appear only after the explicit Supplies action. */
public record HearthLayout(int width, int height, boolean compact, boolean supplies,
                          Rect navigation, Rect summary, Rect recruitment,
                          Rect drawer, int navigationHeight, int navigationStep,
                          int communalX, int communalY, int playerX, int playerY,
                          int hotbarY) {
    public record Rect(int x, int y, int width, int height) {
        public boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }
    }

    public static HearthLayout forViewport(int width, int height) {
        return forViewport(width, height, true);
    }

    public static HearthLayout forViewport(int width, int height, boolean supplies) {
        return create(Math.min(512, width - 16), Math.min(274, height - 16), supplies);
    }

    public static HearthLayout forImage(int width, int height) {
        return create(width, height, true);
    }

    private static HearthLayout create(int width, int height, boolean supplies) {
        int drawerX = width - 188;
        int playerX = width - 172;
        int navigationStep = (width - 16) / 5;
        Rect navigation = new Rect(8, 25, width - 16, 18);
        if (supplies) {
            int contentWidth = drawerX - 20;
            return new HearthLayout(width, height, width < 480 || height < 274, true,
                navigation, new Rect(16, 58, contentWidth, height - 120),
                new Rect(12, height - 52, drawerX - 20, 40),
                new Rect(drawerX, 46, 180, height - 54),
                18, navigationStep, playerX + 27, 56, playerX, height - 84, height - 24);
        }

        boolean compact = width < 480 || height < 274;
        Rect summary = new Rect(16, 58, width - 32, height - 70);
        int cardHeight = compact ? 64 : 76;
        int lowerY = summary.y() + cardHeight + 6;
        int lowerHeight = Math.max(24, summary.y() + summary.height() - lowerY);
        int leftWidth = (summary.width() - 6) / 2;
        Rect recruitment = new Rect(summary.x() + leftWidth + 6, lowerY,
            summary.width() - leftWidth - 6, lowerHeight);
        return new HearthLayout(width, height, compact, false, navigation, summary,
            recruitment, new Rect(0, 0, 0, 0), 18, navigationStep,
            playerX + 27, 56, playerX, height - 84, height - 24);
    }

    /** Real slot geometry; the screen parks hidden slots outside its hit area. */
    public Rect slot(int menuIndex) {
        if (menuIndex < 0 || menuIndex >= 60) {
            throw new IllegalArgumentException("Hearth slot index: " + menuIndex);
        }
        if (menuIndex < 24) {
            return new Rect(communalX + menuIndex % 6 * 18,
                communalY + menuIndex / 6 * 18, 16, 16);
        }
        int playerIndex = menuIndex - 24;
        return playerIndex < 27
            ? new Rect(playerX + playerIndex % 9 * 18,
                playerY + playerIndex / 9 * 18, 16, 16)
            : new Rect(playerX + (playerIndex - 27) * 18, hotbarY, 16, 16);
    }

    public Rect navigationEntry(int index) {
        if (index < 0 || index > 4) throw new IllegalArgumentException("Hearth tab index: " + index);
        int x = navigation.x() + index * navigationStep;
        int right = index == 4 ? navigation.x() + navigation.width() : x + navigationStep - 2;
        return new Rect(x, navigation.y(), Math.max(1, right - x), navigationHeight);
    }

    /** Home shows five equal operational cards and a lower attention panel. */
    public Rect stat(int index) {
        if (supplies) return new Rect(summary.x(), summary.y() + index * 14, summary.width(), 12);
        if (index < 0 || index > 5) throw new IllegalArgumentException("Hearth stat index: " + index);
        int cardHeight = compact ? 64 : 76;
        if (index < 5) {
            int gap = 4;
            int cardWidth = (summary.width() - gap * 4) / 5;
            int x = summary.x() + index * (cardWidth + gap);
            int right = index == 4 ? summary.x() + summary.width() : x + cardWidth;
            return new Rect(x, summary.y(), right - x, cardHeight);
        }
        int lowerY = summary.y() + cardHeight + 6;
        return new Rect(summary.x(), lowerY,
            recruitment.x() - summary.x() - 6, recruitment.height());
    }

    public Rect statIcon(int index) {
        if (supplies || index < 0 || index > 4) return new Rect(0, 0, 0, 0);
        Rect bounds = stat(index);
        int size = compact ? 26 : 32;
        return new Rect(bounds.x() + (bounds.width() - size) / 2,
            bounds.y() + 16, size, size);
    }

    public Rect statMeter(int index) {
        if (supplies || (index != 1 && index != 3 && index != 4)) return new Rect(0, 0, 0, 0);
        Rect bounds = stat(index);
        return new Rect(bounds.x() + 3, bounds.y() + bounds.height() - 3,
            Math.max(1, bounds.width() - 6), 3);
    }

    public int statLabelWidth(int index) {
        Rect bounds = stat(index);
        return Math.max(1, bounds.width() - 6);
    }

    public int minimumHomeStatLabelWidth() {
        if (supplies) return stat(1).width();
        int minimum = Integer.MAX_VALUE;
        for (int index = 0; index < 5; index++) minimum = Math.min(minimum, statLabelWidth(index));
        return minimum;
    }
}
