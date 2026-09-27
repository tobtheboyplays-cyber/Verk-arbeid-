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
    /** First material row shown: every row stays reachable by scrolling (Builder UI v2). */
    private int matScroll;
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
        // Back is sized to its label so the overwrite toggle beside it keeps its whole label.
        Component backLabel = Component.translatable("hearthstead.builder.ui.back");
        int backW = Math.max(48, Ui2Button.textWidth(font, backLabel));
        addRenderableWidget(Ui2Button.secondary(f.x(), by, backW, 20, backLabel, this::back));
        if (v.ok()) {
            addRenderableWidget(Ui2Button.banner(f.right() - 100, by, 100, 20,
                Component.translatable("hearthstead.builder.ui.confirm_build"), this::confirm));
            if (v.playerBlockCount() > 0) {
                addRenderableWidget(Ui2Button.secondary(f.x() + backW + 6, by, f.width() - backW - 6 - 100 - 8, 20,
                    Component.translatable(allowOverwrite ? "hearthstead.builder.ui.overwrite_on"
                        : "hearthstead.builder.ui.overwrite_off"), () -> {
                            allowOverwrite = !allowOverwrite;
                            rebuildWidgets();
                        }));
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        matScroll = Math.max(0, matScroll - (int) Math.signum(sy));
        return true;
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
            java.util.List<BuilderPayloads.Stock> mats = v.materials();
            Component needHeading = Component.translatable("hearthstead.builder.ui.col.need");
            Component hutHeading = Component.translatable("hearthstead.builder.ui.col.hut");
            Component warehouseHeading = Component.translatable("hearthstead.builder.ui.col.warehouse");
            int needWidth = font.width(needHeading);
            int hutWidth = font.width(hutHeading);
            int warehouseWidth = font.width(warehouseHeading);
            for (BuilderPayloads.Stock stock : mats) {
                needWidth = Math.max(needWidth, font.width(String.valueOf(stock.needed())));
                hutWidth = Math.max(hutWidth, font.width(String.valueOf(stock.inHut())));
                warehouseWidth = Math.max(warehouseWidth, font.width(String.valueOf(stock.inWarehouse())));
            }
            // Pack measured columns from the page's inner right edge. Keep the
            // scroll arrow in its own gutter and count anchors stable while scrolling.
            int arrowX = px + pw - Ui2FrameLayout.PAD - font.width("▲");
            int c3 = arrowX - Ui2FrameLayout.S - warehouseWidth;
            int c2 = c3 - Ui2FrameLayout.M - hutWidth;
            int c1 = c2 - Ui2FrameLayout.M - needWidth;
            g.drawString(font, needHeading, c1, y, Ui2Palette.INK_MUTED, false);
            g.drawString(font, hutHeading, c2, y, Ui2Palette.INK_MUTED, false);
            g.drawString(font, warehouseHeading, c3, y, Ui2Palette.INK_MUTED, false);
            y += 11;
            int rows = Math.max(1, (py + ph - 12 - y) / 11 + 1);
            matScroll = Math.max(0, Math.min(matScroll, Math.max(0, mats.size() - rows)));
            if (matScroll > 0) {
                g.drawString(font, "▲", arrowX, y - 11, Ui2Palette.INK_MUTED, false);
            }
            int shown = 0;
            for (int i = matScroll; i < mats.size(); i++) {
                BuilderPayloads.Stock s = mats.get(i);
                if (y > py + ph - 12) {
                    String more = "▼ " + (mats.size() - matScroll - shown);
                    g.drawString(font, more, px + pw - 4 - font.width(more), y - 1, Ui2Palette.INK_MUTED, false);
                    break;
                }
                shown++;
                Ui2Surface.icon(g, new ItemStack(s.item()), x, y - 3, 12);
                g.drawString(font, font.plainSubstrByWidth(s.item().getDescription().getString(), Math.max(0, c1 - x - 18)),
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
