package com.hearthstead.client.builder;

import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.BannerSheetLayout;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Type;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/** Names a surveyed box before it is saved as a design (Survey Rod). */
public final class DesignNameScreen extends Screen {

    private final BlockPos a;
    private final BlockPos b;
    private EditBox name;
    private int x0;
    private int y0;
    private Ui2FrameLayout frame;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);

    public DesignNameScreen(BlockPos a, BlockPos b) {
        super(Component.translatable("hearthstead.builder.design.title"));
        this.a = a;
        this.b = b;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        frame = layoutFor(width, height);
        x0 = frame.x();
        y0 = frame.y();
        BannerSheetLayout.Rect c = frame.content();
        BannerSheetLayout.Rect f = frame.footer();
        addRenderableWidget(Ui2Frame.closeKey(frame, this::onClose));
        name = new EditBox(font, c.x(), c.y(), c.width(), 18, Component.translatable("hearthstead.builder.design.name"));
        name.setMaxLength(40);
        addRenderableWidget(name);
        setInitialFocus(name);
        addRenderableWidget(Ui2Button.secondary(f.x(), f.y(), 80, 20,
            Component.translatable("hearthstead.builder.ui.back"), this::onClose));
        addRenderableWidget(Ui2Button.banner(f.right() - 100, f.y(), 100, 20,
            Component.translatable("hearthstead.builder.design.save"), () -> {
                SurveyRodClient.save(a, b, name.getValue());
                onClose();
            }));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int sx = Math.abs(a.getX() - b.getX()) + 1;
        int sy = Math.abs(a.getY() - b.getY()) + 1;
        int sz = Math.abs(a.getZ() - b.getZ()) + 1;
        Ui2Frame.draw(g, frame);
        Ui2Frame.title(g, font, frame, titleText, title.getString(),
            Component.translatable("hearthstead.builder.design.size", sx, sy, sz));
        super.render(g, mouseX, mouseY, partialTick);
        if (name.getValue().isEmpty() && !name.isFocused()) {
            g.drawString(font, Component.translatable("hearthstead.builder.design.name"), name.getX() + 4,
                name.getY() + 5, Ui2Palette.INK_MUTED, false);
        }
    }

    /** Standard window: subtitle header (the design's size), name box, footer. */
    static Ui2FrameLayout layoutFor(int viewportW, int viewportH) {
        return Ui2FrameLayout.centred(viewportW, viewportH, 240, 118, true);
    }
}
