package com.hearthstead.client.ui;

import com.hearthstead.Hearthstead;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

/**
 * Screen-specific physical identity fragments.
 *
 * <p>All resources and colours are static. These helpers perform a bounded
 * number of blits/fills and never allocate, rebuild layout or generate art in
 * the steady render path. Dynamic text, items, hit boxes and authority remain
 * owned by the calling screen.
 */
public final class HsIdentityUi {
    private static final ResourceLocation HEARTH_EMBLEM = identity("hearth_emblem");
    private static final ResourceLocation HEARTH_SURVEYOR_CORE =
        identity("hearth_surveyor_core");
    private static final ResourceLocation HEARTH_SURVEYOR_CHAPTERS =
        identity("hearth_surveyor_chapters");
    public static final int HEARTH_CORE_WIDTH = 320;
    public static final int HEARTH_CORE_HEIGHT = 220;
    private static final int CHAPTER_SIZE = 20;

    private HsIdentityUi() {
    }

    private static ResourceLocation identity(String name) {
        return ResourceLocation.fromNamespaceAndPath(Hearthstead.MODID,
            "textures/gui/identity/" + name + ".png");
    }

    public static void hearthEmblem(GuiGraphics graphics, int x, int y, int size) {
        graphics.blit(HEARTH_EMBLEM, x, y, size, size,
            0.0F, 0.0F, 32, 32, 32, 32);
    }

    /** One immutable art layer around the menu-owned 60-slot geometry. */
    public static void hearthSurveyorDesk(GuiGraphics graphics, int x, int y) {
        graphics.blit(HEARTH_SURVEYOR_CORE, x, y,
            HEARTH_CORE_WIDTH, HEARTH_CORE_HEIGHT,
            0.0F, 0.0F, HEARTH_CORE_WIDTH, HEARTH_CORE_HEIGHT,
            HEARTH_CORE_WIDTH, HEARTH_CORE_HEIGHT);
    }

    /**
     * Draws one chapter seal from a static five-column, three-state sheet.
     * Rows are idle, hover/focus and selected; hover lifts exactly one pixel.
     */
    public static void hearthChapterSeal(GuiGraphics graphics, int x, int y,
                                         int chapter, boolean selected,
                                         boolean hoveredOrFocused) {
        int state = selected ? 2 : hoveredOrFocused ? 1 : 0;
        int renderX = state == 2 ? x - 2 : x;
        int renderY = state == 2 ? y - 2 : state == 1 ? y - 1 : y;
        int renderSize = state == 2 ? CHAPTER_SIZE + 4 : CHAPTER_SIZE;
        graphics.blit(HEARTH_SURVEYOR_CHAPTERS, renderX, renderY,
            renderSize, renderSize,
            chapter * CHAPTER_SIZE, state * CHAPTER_SIZE,
            CHAPTER_SIZE, CHAPTER_SIZE, 100, 60);
    }

}
