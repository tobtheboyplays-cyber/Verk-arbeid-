package com.hearthstead.client.screen;

import com.hearthstead.Hearthstead;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/** The settler's handbook: six short pages, everything lang-driven. */
public class HandbookScreen extends Screen {
    private static final ResourceLocation TEXTURE =
        Hearthstead.id("textures/gui/handbook.png");

    public static final int PAGES = 6;
    private static final int BOOK_W = 200;
    private static final int BOOK_H = 176;
    private static final int INK = 0xFF3F3024;
    private static final int TEXT_W = 164;

    private int page;
    private int left;
    private int top;
    private Button prevButton;
    private Button nextButton;

    public HandbookScreen() {
        super(Component.translatable("hearthstead.guide.title"));
    }

    @Override
    protected void init() {
        left = (width - BOOK_W) / 2;
        top = (height - BOOK_H) / 2;
        prevButton = addRenderableWidget(Button.builder(Component.literal("<"),
                b -> turnTo(page - 1))
            .bounds(left + 12, top + BOOK_H - 24, 20, 16).build());
        nextButton = addRenderableWidget(Button.builder(Component.literal(">"),
                b -> turnTo(page + 1))
            .bounds(left + BOOK_W - 32, top + BOOK_H - 24, 20, 16).build());
        updateButtons();
    }

    private void turnTo(int newPage) {
        page = Math.floorMod(newPage, PAGES);
        updateButtons();
    }

    private void updateButtons() {
        prevButton.active = page > 0;
        nextButton.active = page < PAGES - 1;
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY,
                                 float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        graphics.blit(TEXTURE, left, top, 0, 0, BOOK_W, BOOK_H);

        Component pageTitle =
            Component.translatable("hearthstead.guide.page" + (page + 1) + ".title");
        graphics.drawString(font, pageTitle,
            left + (BOOK_W - font.width(pageTitle)) / 2, top + 14, INK, false);

        Component body =
            Component.translatable("hearthstead.guide.page" + (page + 1) + ".body");
        graphics.drawWordWrap(font, body, left + 18, top + 30, TEXT_W, INK);

        String counter = (page + 1) + " / " + PAGES;
        graphics.drawString(font, counter,
            left + (BOOK_W - font.width(counter)) / 2, top + BOOK_H - 20, INK, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
