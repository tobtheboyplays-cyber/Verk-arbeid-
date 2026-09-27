package com.hearthstead.client.conversation;

import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.client.ui2.Ui2Tips;
import com.hearthstead.conversation.BarterMath;
import com.hearthstead.conversation.net.ConvActionPayload;
import com.hearthstead.conversation.net.ConvBarterPayload;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

/**
 * The barter table, Bannerlord style: your goods on the left, theirs on the
 * right, the two offers in the middle and "their satisfaction" below. Click
 * (or drag) goods into an offer: left click moves the whole stack, right
 * click one item; click an offered good to take it back. "Accept" lights
 * only when they are satisfied. The server re-checks both inventories and
 * moves everything exactly once.
 */
public final class BarterScreen extends Screen {
    private static final int CELL = 18;
    private static final int COLS = 6;

    private ConvBarterPayload data;
    private int[] give = new int[ConvBarterPayload.MINE_SLOTS];
    private int[] take = new int[ConvBarterPayload.MAX_THEIRS];
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private static final int CENTER_W = 132;
    private Ui2Button acceptButton;
    private Component flash = Component.empty();
    private long flashNanos;
    private int dragFromMine = -1;
    /** Accept was sent for this revision; Accept stays disabled until the server answers. */
    private int pendingRevision = -1;
    private int dragFromTheirs = -1;
    // Layout (computed in render).
    private int mineX, mineY, theirsX, theirsY, offerX, offerY, wantX, wantY, buttonsY;

    public BarterScreen(ConvBarterPayload data) {
        super(Component.translatable("conversation.hearthstead.barter.title"));
        update(data);
    }

    public int session() {
        return data.session();
    }

    public void update(ConvBarterPayload next) {
        boolean reset = data == null || next.result() != 0;
        data = next;
        if (reset) {
            give = new int[ConvBarterPayload.MINE_SLOTS];
            take = new int[ConvBarterPayload.MAX_THEIRS];
        }
        if (next.result() == 1) {
            setFlash(Component.translatable("conversation.hearthstead.barter.deal"));
            com.hearthstead.client.sound.HsSound.ui("convo.deal", null, 0.7F, 1.0F);
        }
        if (next.result() == 2) setFlash(Component.translatable("conversation.hearthstead.barter.refused"));
    }

    private void setFlash(Component text) {
        flash = text;
        flashNanos = System.nanoTime();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The standard window: your goods, the two offers and theirs on one parchment page. */
    static Ui2FrameLayout layoutFor(int viewportW, int viewportH) {
        int gridW = COLS * CELL;
        int w = gridW * 2 + CENTER_W + 24 + (Ui2FrameLayout.FRAME + Ui2FrameLayout.MARGIN + Ui2FrameLayout.PAD) * 2;
        int h = 6 * CELL + 58 + 62;
        int x = Math.max(0, (viewportW - w) / 2);
        int y = Math.max(0, (viewportH - h) / 2);
        return Ui2FrameLayout.at(x, y, w, h, false);
    }

    @Override
    protected void init() {
        Ui2FrameLayout f = layoutFor(width, height);
        addRenderableWidget(Ui2Frame.closeKey(f, this::onClose));
        Rect[] b = f.footerButtons(3, 76);
        buttonsY = b[0].y();
        addRenderableWidget(Ui2Tips.tip(Ui2Button.secondary(b[0].x(), b[0].y(), b[0].width(), b[0].height(),
            Component.translatable("conversation.hearthstead.barter.leave"), this::onClose),
            Component.literal("Leave the table (Esc)")));
        addRenderableWidget(Ui2Tips.tip(Ui2Button.secondary(b[1].x(), b[1].y(), b[1].width(), b[1].height(),
            Component.translatable("conversation.hearthstead.barter.reset"), () -> {
                give = new int[ConvBarterPayload.MINE_SLOTS];
                take = new int[ConvBarterPayload.MAX_THEIRS];
            }), Component.literal("Take both offers back")));
        acceptButton = addRenderableWidget(Ui2Button.banner(b[2].x(), b[2].y(), b[2].width(), b[2].height(),
            Component.translatable("conversation.hearthstead.barter.accept"), this::accept));
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ui2Surface.scrim(g, 0, 0, width, height);
    }

    @Override
    public void onClose() {
        ConversationClient.barterBack(data.session());
    }

    private long given() {
        long v = 0;
        for (int i = 0; i < give.length && i < data.mine().size(); i++) v += (long) give[i] * data.mineValues().get(i);
        return v;
    }

    private long taken() {
        long v = 0;
        for (int i = 0; i < take.length && i < data.theirs().size(); i++) v += (long) take[i] * data.theirValues().get(i);
        return v;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        Font font = Minecraft.getInstance().font;
        int gridW = COLS * CELL;
        int centerW = CENTER_W;
        Ui2FrameLayout f = layoutFor(width, height);
        int px = f.x();
        int panelW = f.width();
        Rect content = f.content();
        Ui2Frame.draw(g, f);
        Ui2Frame.title(g, font, f, titleText, title.getString(), null);
        mineX = content.x();
        mineY = content.y() + 11;
        theirsX = content.right() - gridW;
        theirsY = mineY;
        g.drawString(font, Component.translatable("conversation.hearthstead.barter.yours"), mineX, content.y(),
            Ui2Palette.INK_SOFT, false);
        Component theirLabel = Component.translatable("conversation.hearthstead.barter.theirs", data.name());
        g.drawString(font, theirLabel, theirsX + gridW - font.width(theirLabel), content.y(), Ui2Palette.INK_SOFT, false);
        // Your goods (what is left after the offer).
        for (int i = 0; i < ConvBarterPayload.MINE_SLOTS; i++) {
            ItemStack stack = i < data.mine().size() ? data.mine().get(i) : ItemStack.EMPTY;
            int left = stack.isEmpty() ? 0 : stack.getCount() - give[i];
            cell(g, font, mineX + (i % COLS) * CELL, mineY + (i / COLS) * CELL, stack, left, mouseX, mouseY);
        }
        for (int i = 0; i < ConvBarterPayload.MAX_THEIRS; i++) {
            ItemStack stack = i < data.theirs().size() ? data.theirs().get(i) : ItemStack.EMPTY;
            int left = stack.isEmpty() ? 0 : stack.getCount() - take[i];
            cell(g, font, theirsX + (i % COLS) * CELL, theirsY + (i / COLS) * CELL, stack, left, mouseX, mouseY);
        }
        // Centre: the two offers.
        int cx = px + (panelW - centerW) / 2;
        offerX = cx;
        offerY = mineY + 10;
        wantX = cx + centerW - 3 * CELL;
        wantY = offerY;
        g.drawString(font, Component.translatable("conversation.hearthstead.barter.you_offer"), offerX, mineY, Ui2Palette.GOLD, false);
        Component theyOffer = Component.translatable("conversation.hearthstead.barter.they_offer");
        g.drawString(font, theyOffer, wantX + 3 * CELL - font.width(theyOffer), mineY, Ui2Palette.GOLD, false);
        List<int[]> offered = offered(give, data.mine().size());
        for (int k = 0; k < 9; k++) {
            int ox = offerX + (k % 3) * CELL;
            int oy = offerY + (k / 3) * CELL;
            if (k < offered.size()) cell(g, font, ox, oy, data.mine().get(offered.get(k)[0]), offered.get(k)[1], mouseX, mouseY);
            else cell(g, font, ox, oy, ItemStack.EMPTY, 0, mouseX, mouseY);
        }
        List<int[]> wanted = offered(take, data.theirs().size());
        for (int k = 0; k < 9; k++) {
            int ox = wantX + (k % 3) * CELL;
            int oy = wantY + (k / 3) * CELL;
            if (k < wanted.size()) cell(g, font, ox, oy, data.theirs().get(wanted.get(k)[0]), wanted.get(k)[1], mouseX, mouseY);
            else cell(g, font, ox, oy, ItemStack.EMPTY, 0, mouseX, mouseY);
        }
        g.drawString(font, "→", cx + centerW / 2 - 3, offerY + CELL + 5, Ui2Palette.GOLD, false);
        centred(g, font, Component.literal(BarterMath.label(given()) + " c"), offerX + 27, offerY + 3 * CELL + 3, Ui2Palette.INK_MUTED);
        centred(g, font, Component.literal(BarterMath.label(taken()) + " c"), wantX + 27, wantY + 3 * CELL + 3, Ui2Palette.INK_MUTED);
        // Satisfaction.
        int sy = offerY + 3 * CELL + 16;
        g.fill(cx, sy, cx + centerW, sy + 30, Ui2Palette.INSET);
        Component sat = Component.translatable("conversation.hearthstead.barter.satisfaction");
        g.drawString(font, sat, cx + (centerW - font.width(sat)) / 2, sy + 3, Ui2Palette.INK, false);
        float satisfaction = BarterMath.satisfaction(given(), taken(), data.relation());
        boolean ok = BarterMath.acceptable(given(), taken(), data.relation());
        boolean waiting = pendingRevision == data.revision();
        int barX = cx + 6;
        int barW = centerW - 12;
        g.fill(barX - 1, sy + 15, barX + barW + 1, sy + 23, Ui2Palette.RULE_STRONG);
        g.fill(barX, sy + 16, barX + barW, sy + 22, Ui2Palette.TRACK);
        int fill = Math.round(barW * Mth.clamp(satisfaction / 1.5F, 0.0F, 1.0F));
        g.fill(barX, sy + 16, barX + fill, sy + 22, ok ? Ui2Palette.FOREST_HIGHLIGHT : Ui2Palette.DANGER_HIGHLIGHT);
        int line = barX + Math.round(barW / 1.5F);
        g.fill(line, sy + 14, line + 1, sy + 24, Ui2Palette.GOLD);
        // Buttons: widgets in the standard footer; Accept says why it is locked.
        if (acceptButton != null) {
            Ui2Tips.enable(acceptButton, ok && !waiting,
                Component.literal("Offer this deal (Enter)"),
                Component.literal(waiting ? "Waiting for their answer" : "They are not satisfied with this deal yet"));
        }
        for (var child : renderables) child.render(g, mouseX, mouseY, partialTick);
        if (!flash.getString().isEmpty() && System.nanoTime() - flashNanos < 2_500_000_000L) {
            centred(g, font, flash, px + panelW / 2, buttonsY - 12, Ui2Palette.GOLD);
        }
        // Tooltip for hovered goods.
        ItemStack hovered = hoveredStack(mouseX, mouseY);
        if (!hovered.isEmpty()) g.renderTooltip(font, hovered, mouseX, mouseY);
    }

    private void cell(GuiGraphics g, Font font, int x, int y, ItemStack stack, int count, int mouseX, int mouseY) {
        Ui2Surface.slotWell(g, x, y);
        if (mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL) {
            g.fill(x + 1, y + 1, x + CELL - 1, y + CELL - 1, Ui2Palette.ROW_HOVER);
        }
        if (stack.isEmpty() || count <= 0) return;
        g.renderItem(stack, x + 1, y + 1);
        g.renderItemDecorations(font, stack.copyWithCount(Math.min(99, count)), x + 1, y + 1,
            count == 1 ? null : String.valueOf(count));
    }

    /** Centred, unshadowed ink on parchment. */
    private static void centred(GuiGraphics g, Font font, Component text, int cx, int y, int color) {
        g.drawString(font, text, cx - font.width(text) / 2, y, color, false);
    }

    /** (index, count) pairs of what is in an offer, in slot order. */
    private static List<int[]> offered(int[] counts, int size) {
        List<int[]> out = new ArrayList<>();
        for (int i = 0; i < counts.length && i < size; i++) if (counts[i] > 0) out.add(new int[] {i, counts[i]});
        return out;
    }

    private int mineAt(double mx, double my) {
        return gridIndex(mx, my, mineX, mineY, ConvBarterPayload.MINE_SLOTS);
    }

    private int theirsAt(double mx, double my) {
        return gridIndex(mx, my, theirsX, theirsY, ConvBarterPayload.MAX_THEIRS);
    }

    private static int gridIndex(double mx, double my, int gx, int gy, int count) {
        if (mx < gx || my < gy) return -1;
        int col = (int) ((mx - gx) / CELL);
        int row = (int) ((my - gy) / CELL);
        if (col >= COLS) return -1;
        int index = row * COLS + col;
        return index < count ? index : -1;
    }

    private int offerAt(double mx, double my, int gx, int gy) {
        if (mx < gx || my < gy || mx >= gx + 3 * CELL || my >= gy + 3 * CELL) return -1;
        return (int) ((my - gy) / CELL) * 3 + (int) ((mx - gx) / CELL);
    }

    private ItemStack hoveredStack(int mx, int my) {
        int m = mineAt(mx, my);
        if (m >= 0 && m < data.mine().size()) return data.mine().get(m);
        int t = theirsAt(mx, my);
        if (t >= 0 && t < data.theirs().size()) return data.theirs().get(t);
        return ItemStack.EMPTY;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        boolean one = button == GLFW.GLFW_MOUSE_BUTTON_RIGHT;
        int m = mineAt(mx, my);
        if (m >= 0 && m < data.mine().size() && !data.mine().get(m).isEmpty()) {
            dragFromMine = m;
            moveMine(m, one);
            return true;
        }
        int t = theirsAt(mx, my);
        if (t >= 0 && t < data.theirs().size() && !data.theirs().get(t).isEmpty()) {
            dragFromTheirs = t;
            moveTheirs(t, one);
            return true;
        }
        int o = offerAt(mx, my, offerX, offerY);
        List<int[]> offered = offered(give, data.mine().size());
        if (o >= 0 && o < offered.size()) {
            int slot = offered.get(o)[0];
            give[slot] = one ? Math.max(0, give[slot] - 1) : 0;
            return true;
        }
        int w = offerAt(mx, my, wantX, wantY);
        List<int[]> wanted = offered(take, data.theirs().size());
        if (w >= 0 && w < wanted.size()) {
            int index = wanted.get(w)[0];
            take[index] = one ? Math.max(0, take[index] - 1) : 0;
            return true;
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        dragFromMine = -1;
        dragFromTheirs = -1;
        return super.mouseReleased(mx, my, button);
    }

    /** Dragging across goods keeps adding them to the offer (a quick sweep). */
    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        int m = mineAt(mx, my);
        if (dragFromMine >= 0 && m >= 0 && m != dragFromMine && m < data.mine().size() && give[m] == 0
            && !data.mine().get(m).isEmpty()) {
            dragFromMine = m;
            moveMine(m, false);
        }
        int t = theirsAt(mx, my);
        if (dragFromTheirs >= 0 && t >= 0 && t != dragFromTheirs && t < data.theirs().size() && take[t] == 0
            && !data.theirs().get(t).isEmpty()) {
            dragFromTheirs = t;
            moveTheirs(t, false);
        }
        return true;
    }

    private void moveMine(int slot, boolean one) {
        int have = data.mine().get(slot).getCount();
        give[slot] = one ? Math.min(have, give[slot] + 1) : have;
    }

    private void moveTheirs(int index, boolean one) {
        int have = data.theirs().get(index).getCount();
        take[index] = one ? Math.min(have, take[index] + 1) : have;
    }

    private void accept() {
        if (!BarterMath.acceptable(given(), taken(), data.relation()) || pendingRevision == data.revision()) return;
        pendingRevision = data.revision();
        List<ConvActionPayload.Line> giveLines = new ArrayList<>();
        for (int i = 0; i < give.length; i++) if (give[i] > 0) giveLines.add(new ConvActionPayload.Line(i, give[i]));
        List<ConvActionPayload.Line> takeLines = new ArrayList<>();
        for (int i = 0; i < take.length; i++) if (take[i] > 0) takeLines.add(new ConvActionPayload.Line(i, take[i]));
        ConversationClient.send(ConvActionPayload.accept(data.session(), data.revision(), giveLines, takeLines));
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            accept();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }
}
