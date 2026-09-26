package com.hearthstead.client.builder;

import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.BannerSheetLayout;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.client.ui2.Ui2Type;
import com.hearthstead.network.BuilderPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/**
 * "Before you confirm" (BUILDER lane): what this order will cost, what the
 * hut and warehouse hold of it, what the Builder will clear and fill, and
 * which of YOUR blocks are in the way -- all from the server's validation,
 * before anything is committed. Player blocks are never overwritten unless
 * the box is ticked here.
 */
public final class BuilderConfirmScreen extends Screen {

    private final BuilderPayloads.Validation v;
    private boolean allowOverwrite;
    private int x0;
    private int y0;
    private int w;
    private int h;
    private Ui2FrameLayout frame;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);

    public BuilderConfirmScreen(BuilderPayloads.Validation validation) {
        super(Component.translatable("hearthstead.builder.ui.confirm"));
        this.v = validation;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        w = Math.min(width - 16, 340);
        h = Math.min(height - 16, 230);
        x0 = (width - w) / 2;
        y0 = (height - h) / 2;
        frame = Ui2FrameLayout.at(x0, y0, w, h, false);
        addRenderableWidget(Ui2Frame.closeKey(frame, this::back));
        // Standard footer on the page: text actions left, the burgundy primary right.
        BannerSheetLayout.Rect f = frame.footer();
        int by = f.y();
        addRenderableWidget(Ui2Button.secondary(f.x(), by, 80, 20, Component.translatable("hearthstead.builder.ui.back"),
            this::back));
        if (v.ok()) {
            addRenderableWidget(Ui2Button.banner(f.right() - 100, by, 100, 20,
                Component.translatable("hearthstead.builder.ui.confirm_build"), this::confirm));
            if (v.playerBlockCount() > 0) {
                addRenderableWidget(Ui2Button.secondary(f.x() + 84, by, f.width() - 84 - 100 - 8, 20,
                    Component.translatable(allowOverwrite ? "hearthstead.builder.ui.overwrite_on"
                        : "hearthstead.builder.ui.overwrite_off"), () -> {
                            allowOverwrite = !allowOverwrite;
                            rebuildWidgets();
                        }));
            }
        }
    }

    private void confirm() {
        switch (v.kind()) {
            case BuilderPayloads.Validation.BLUEPRINT -> {
                BuilderClientState.placeBlueprint(v.subject(), v.a(), v.rotation(), v.mirror(), allowOverwrite);
                BuilderPlacement.cancel();
            }
            case BuilderPayloads.Validation.LINE -> {
                BuilderClientState.placeLine(v.subject(), v.a(), v.b(), v.flag(), v.number());
                BuilderPlacement.cancel();
            }
            case BuilderPayloads.Validation.UPGRADE -> BuilderClientState.orderUpgrade(v.target());
            case BuilderPayloads.Validation.DECONSTRUCT -> BuilderClientState.deconstruct(v.target(), true);
            default -> {
            }
        }
        onClose();
    }

    private void back() {
        BuilderPlacement.resume();
        onClose();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Keep the world (and the ghost) visible behind the sheet.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ui2Frame.draw(g, frame);
        Ui2Frame.title(g, font, frame, titleText, title.getString(), null);
        // Content area above the footer (the page itself is drawn by the frame).
        int px = frame.page().x();
        int py = frame.page().y();
        int pw = frame.page().width();
        int ph = frame.page().height() - Ui2FrameLayout.BUTTON_H - Ui2FrameLayout.M - Ui2FrameLayout.PAD;
        int x = px + 8;
        int y = py + 8;
        if (!v.ok()) {
            Ui2Surface.alertGlyph(g, x, y, Ui2Palette.DANGER);
            g.drawWordWrap(font, Component.translatable(v.reasonKey(), v.reasonArgs().toArray()), x + 10, y,
                pw - 26, Ui2Palette.DANGER);
        } else {
            g.drawString(font, Component.translatable("hearthstead.builder.ui.summary", v.steps(), v.clears(), v.fills()),
                x, y, Ui2Palette.INK, false);
            y += 12;
            if (v.playerBlockCount() > 0) {
                Ui2Surface.alertGlyph(g, x, y, Ui2Palette.BURGUNDY);
                BlockPosText first = v.playerBlocks().isEmpty() ? null
                    : new BlockPosText(v.playerBlocks().get(0).getX(), v.playerBlocks().get(0).getY(),
                        v.playerBlocks().get(0).getZ());
                g.drawWordWrap(font, Component.translatable("hearthstead.builder.ui.player_blocks",
                    v.playerBlockCount(), first == null ? "" : first.text()), x + 10, y, pw - 26, Ui2Palette.BURGUNDY);
                y += 22;
            }
            if (v.kind() == BuilderPayloads.Validation.UPGRADE && !v.reasonArgs().isEmpty()) {
                StringBuilder hand = new StringBuilder();
                for (String key : v.reasonArgs()) {
                    if (hand.length() > 0) {
                        hand.append(", ");
                    }
                    hand.append(Component.translatable(key, "", "").getString().replace(" /", "").trim());
                }
                g.drawWordWrap(font, Component.translatable("hearthstead.builder.ui.hand_only", hand.toString()),
                    x, y, pw - 16, Ui2Palette.INK_MUTED);
                y += 22;
            }
            int c1 = px + pw - 132;
            int c2 = px + pw - 88;
            int c3 = px + pw - 40;
            g.drawString(font, Component.translatable("hearthstead.builder.ui.col.need"), c1, y, Ui2Palette.INK_MUTED, false);
            g.drawString(font, Component.translatable("hearthstead.builder.ui.col.hut"), c2, y, Ui2Palette.INK_MUTED, false);
            g.drawString(font, Component.translatable("hearthstead.builder.ui.col.warehouse"), c3 - 12, y,
                Ui2Palette.INK_MUTED, false);
            y += 11;
            for (BuilderPayloads.Stock s : v.materials()) {
                if (y > py + ph - 12) {
                    g.drawString(font, "…", x, y, Ui2Palette.INK_MUTED, false);
                    break;
                }
                Ui2Surface.icon(g, new ItemStack(s.item()), x, y - 3, 12);
                g.drawString(font, font.plainSubstrByWidth(s.item().getDescription().getString(), c1 - x - 18),
                    x + 14, y, Ui2Palette.INK, false);
                boolean short_ = s.needed() > s.inHut() + s.inWarehouse();
                g.drawString(font, String.valueOf(s.needed()), c1, y, short_ ? Ui2Palette.DANGER : Ui2Palette.INK, false);
                g.drawString(font, String.valueOf(s.inHut()), c2, y, Ui2Palette.INK, false);
                g.drawString(font, String.valueOf(s.inWarehouse()), c3, y, Ui2Palette.INK, false);
                y += 11;
            }
        }
        for (var child : renderables) {
            child.render(g, mouseX, mouseY, partialTick);
        }
    }

    private record BlockPosText(int x, int y, int z) {
        String text() {
            return x + ", " + y + ", " + z;
        }
    }
}
