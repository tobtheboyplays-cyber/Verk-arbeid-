package com.hearthstead.client.screen;

import com.hearthstead.Hearthstead;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.menu.HearthMenu;
import com.hearthstead.network.HearthMayorAction;
import com.hearthstead.network.HearthMayorSnapshot;
import com.hearthstead.settlement.Mayor;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The settlement ledger: stats column on parchment, communal stores grid,
 * player inventory below. Texture is 256x256; screen is 220x222.
 *
 * <h2>The Mayor tab</h2>
 *
 * <p>Two small tabs stick out above the window's top-left corner, like a
 * creative-inventory tab: "Settlement" (the default view above) and
 * "Mayor". They live outside the parchment image on purpose -- the
 * communal storage grid and player inventory are real, always-interactive
 * slots owned by {@link HearthMenu}, and a tab that hid or covered them
 * without disabling them would be a control that lies about what clicking
 * it does. So the Mayor tab opens a second panel beside the window instead
 * of replacing anything inside it: nothing about the existing slots moves.
 *
 * <p>The panel is drawn entirely from a {@link HearthMayorSnapshot} the
 * server sends back for a {@link HearthMayorAction}, exactly
 * {@code PlaqueScreen}'s discipline: this screen holds no opinion about who
 * is eligible or what a candidate would bring, and every Appoint click sends
 * back the revision it was drawn from so a press made against a seat that
 * has since changed hands is refused rather than applied blind.
 */
public class HearthScreen extends AbstractContainerScreen<HearthMenu> {
    private static final ResourceLocation TEXTURE =
        Hearthstead.id("textures/gui/hearth_screen.png");

    private static final int INK = 0xFF3F3024;
    private static final int INK_SOFT = 0xFF69573C;
    private static final int PARCHMENT_LIGHT = 0xFFEFE0BD;

    // Stat rows (icon + value) in the left column.
    private static final int STAT_X = 14;
    private static final int STAT_Y = 34;
    private static final int STAT_ROW_H = 16;
    private static final int STAT_W = 84;

    // Morale bar geometry.
    private static final int BAR_X = 14;
    private static final int BAR_Y = 103;
    private static final int BAR_W = 80;
    private static final int BAR_H = 8;

    // -- the Mayor tab: two folder tabs above the window's top-left corner --
    // 50 clipped "Settlement" (51px) and "Ordfører" (47px) against its
    // labelIn box (TAB_W - 8); 64 clears both with a few px to spare.
    private static final int TAB_W = 64;
    private static final int TAB_H = 14;
    private static final int TAB_GAP = 3;

    // -- the Mayor tab's popout panel, laid out exactly like PlaqueScreen's
    //    card list (same PAD/SCROLL_W/CARD proportions) plus a status block
    //    up top for the seat itself. --
    private static final int MAYOR_PANEL_W = 256;
    private static final int MAYOR_PAD = HsUiTokens.PAD;
    private static final int MAYOR_GAP = 6;
    private static final int MAYOR_TITLE_Y = 12;
    private static final int MAYOR_DIV1_Y = 26;
    private static final int MAYOR_STATUS_Y = 32;
    private static final int MAYOR_STATUS_H = 48;
    private static final int MAYOR_DIV2_Y = MAYOR_STATUS_Y + MAYOR_STATUS_H + 6;
    private static final int MAYOR_LABEL_Y = MAYOR_DIV2_Y + 8;
    private static final int MAYOR_LIST_TOP = MAYOR_LABEL_Y + 12;
    private static final int MAYOR_ROWS = 3;
    private static final int MAYOR_CARD_H = 38;
    private static final int MAYOR_CARD_STEP = MAYOR_CARD_H + 4;
    private static final int MAYOR_LIST_H = MAYOR_ROWS * MAYOR_CARD_STEP - 4;
    private static final int MAYOR_FOOT = MAYOR_LIST_TOP + MAYOR_LIST_H + 6;
    // The footer sometimes wraps to two lines -- "Appointing someone new
    // stands Gislebert the Younger down" measured 299px against a 240px box
    // (59px over) in English, 249px (9px over) in Norwegian, both from the
    // rare "crowded settlement" long-name fallback. Two lines clears both
    // with room to spare; reserved unconditionally like the rest of this
    // panel's fixed shape.
    private static final int MAYOR_FOOTER_H = HsUiTokens.TEXT_H + 9;
    private static final int MAYOR_PANEL_H = MAYOR_FOOT + 8 + MAYOR_FOOTER_H + 8;

    private static final int MAYOR_CARD_X = MAYOR_PAD;
    private static final int MAYOR_CARD_W =
        MAYOR_PANEL_W - 2 * MAYOR_PAD - HsUiTokens.SCROLL_W - 2;
    private static final int MAYOR_BTN_W = 64;
    private static final int MAYOR_BTN_X = MAYOR_CARD_X + MAYOR_CARD_W - MAYOR_BTN_W - 8;
    private static final int MAYOR_TEXT_X = MAYOR_CARD_X + 10;
    // Measured against "Gislebert the Younger" (111px, the crowded-settlement
    // long-name fallback) -- 110 clipped it by 1px. 114 clears it and still
    // leaves a 2px gap before the pips column at MAYOR_BTN_X - 34.
    private static final int MAYOR_NAME_BOX = MAYOR_BTN_X - MAYOR_TEXT_X - 36;
    private static final int MAYOR_LINE_BOX = MAYOR_BTN_X - MAYOR_TEXT_X - 6;

    private boolean mayorTabOpen;
    private HearthMayorSnapshot mayorSnapshot;
    private int mayorScroll;
    private int mayorPanelLeft;
    private int mayorPanelTop;

    public HearthScreen(HearthMenu menu, Inventory playerInventory, Component title) {
        super(menu, playerInventory, title);
        imageWidth = 220;
        imageHeight = 222;
        inventoryLabelX = HearthMenu.PLAYER_INV_X + 1;
        inventoryLabelY = HearthMenu.PLAYER_INV_Y - 11;
        titleLabelY = -1000; // we draw our own header
    }

    @Override
    protected void init() {
        super.init();
        rebuildSeatWidgets();
    }

    /**
     * A fresh Mayor snapshot arrived. Called from the client payload handler
     * whenever this screen is the one open -- see {@code ClientHooks}.
     */
    public void updateMayor(HearthMayorSnapshot fresh) {
        this.mayorSnapshot = fresh;
        rebuildSeatWidgets();
    }

    // ------------------------------------------------------------ widgets ---

    private void rebuildSeatWidgets() {
        clearWidgets();
        addRenderableWidget(new SeatTabButton(leftPos + 6, topPos - TAB_H, TAB_W, TAB_H,
            Component.translatable("hearthstead.gui.tab.settlement"), !mayorTabOpen, () -> {
                mayorTabOpen = false;
                rebuildSeatWidgets();
            }));
        addRenderableWidget(new SeatTabButton(leftPos + 6 + TAB_W + TAB_GAP, topPos - TAB_H,
            TAB_W, TAB_H, Component.translatable("hearthstead.gui.tab.mayor"), mayorTabOpen, () -> {
                mayorTabOpen = true;
                requestMayorData();
                rebuildSeatWidgets();
            }));

        if (!mayorTabOpen) {
            return;
        }
        updateMayorPanelPosition();
        if (mayorSnapshot == null) {
            return;
        }
        List<HearthMayorSnapshot.Candidate> candidates = mayorSnapshot.candidates();
        int rows = candidates.size();
        mayorScroll = Math.max(0, Math.min(mayorScroll, Math.max(0, rows - MAYOR_ROWS)));
        boolean canAppoint = !mayorSnapshot.mourning();
        for (int row = 0; row < MAYOR_ROWS && row + mayorScroll < rows; row++) {
            HearthMayorSnapshot.Candidate candidate = candidates.get(row + mayorScroll);
            int y = mayorPanelTop + MAYOR_LIST_TOP + row * MAYOR_CARD_STEP;
            HsButton appoint = HsButton.normal(mayorPanelLeft + MAYOR_BTN_X, y + 4, MAYOR_BTN_W,
                HsUiTokens.BUTTON_H, Component.translatable("hearthstead.mayor.appoint"),
                () -> appointAction(candidate.id()));
            appoint.active = canAppoint;
            // A disabled control always says why (D-014).
            appoint.setTooltip(Tooltip.create(canAppoint
                ? Component.translatable("hearthstead.mayor.appoint.tip", candidate.name())
                : Component.translatable("hearthstead.mayor.refused.mourning")));
            addRenderableWidget(appoint);
        }
    }

    /** Prefers the right of the window; falls back left, then clamps on-screen. */
    private void updateMayorPanelPosition() {
        int preferred = leftPos + imageWidth + MAYOR_GAP;
        if (preferred + MAYOR_PANEL_W > width) {
            int leftSide = leftPos - MAYOR_GAP - MAYOR_PANEL_W;
            preferred = leftSide >= 0 ? leftSide : Math.max(0, width - MAYOR_PANEL_W);
        }
        mayorPanelLeft = preferred;
        mayorPanelTop = topPos - (MAYOR_PANEL_H - imageHeight) / 2;
    }

    private void requestMayorData() {
        PacketDistributor.sendToServer(
            new HearthMayorAction(HearthMayorAction.Kind.REFRESH, new UUID(0, 0), 0));
    }

    private void appointAction(UUID id) {
        if (mayorSnapshot != null) {
            PacketDistributor.sendToServer(
                new HearthMayorAction(HearthMayorAction.Kind.APPOINT, id, mayorSnapshot.revision()));
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (mayorTabOpen && mayorSnapshot != null) {
            int rows = mayorSnapshot.candidates().size();
            if (rows > MAYOR_ROWS && mouseX >= mayorPanelLeft
                && mouseX <= mayorPanelLeft + MAYOR_PANEL_W
                && mouseY >= mayorPanelTop && mouseY <= mayorPanelTop + MAYOR_PANEL_H) {
                int before = mayorScroll;
                mayorScroll = Math.max(0, Math.min(rows - MAYOR_ROWS, mayorScroll - (int) Math.signum(dy)));
                if (before != mayorScroll) {
                    rebuildSeatWidgets();
                    return true;
                }
            }
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    // ------------------------------------------------------------- drawing ---

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;
        graphics.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight);
        if (mayorTabOpen) {
            renderMayorPanel(graphics, mouseX, mouseY);
        }
    }

    private void renderMayorPanel(GuiGraphics graphics, int mouseX, int mouseY) {
        int pl = mayorPanelLeft;
        int pt = mayorPanelTop;
        HsUi.window(graphics, pl, pt, MAYOR_PANEL_W, MAYOR_PANEL_H);
        HsUi.centred(graphics, font, Component.translatable("hearthstead.mayor.tab.title"),
            pl + MAYOR_PANEL_W / 2, pt + MAYOR_TITLE_Y, HsUiTokens.TEXT_STRONG);
        HsUi.divider(graphics, pl + MAYOR_PAD, pt + MAYOR_DIV1_Y, MAYOR_PANEL_W - 2 * MAYOR_PAD);

        drawMayorStatus(graphics, pl, pt);

        HsUi.divider(graphics, pl + MAYOR_PAD, pt + MAYOR_DIV2_Y, MAYOR_PANEL_W - 2 * MAYOR_PAD);
        HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.candidates.title"),
            pl + MAYOR_PAD, pt + MAYOR_LABEL_Y, MAYOR_PANEL_W - 2 * MAYOR_PAD, HsUiTokens.TEXT_MUTED);

        if (mayorSnapshot == null) {
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.loading"),
                pl + MAYOR_PAD, pt + MAYOR_LIST_TOP, MAYOR_PANEL_W - 2 * MAYOR_PAD,
                HsUiTokens.TEXT_MUTED);
            return;
        }

        List<HearthMayorSnapshot.Candidate> candidates = mayorSnapshot.candidates();
        if (candidates.isEmpty()) {
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.candidates.empty"),
                pl + MAYOR_PAD, pt + MAYOR_LIST_TOP, MAYOR_PANEL_W - 2 * MAYOR_PAD,
                HsUiTokens.TEXT_MUTED);
        }
        for (int row = 0; row < MAYOR_ROWS && row + mayorScroll < candidates.size(); row++) {
            HearthMayorSnapshot.Candidate candidate = candidates.get(row + mayorScroll);
            int y = pt + MAYOR_LIST_TOP + row * MAYOR_CARD_STEP;
            boolean hovered = mouseX >= pl + MAYOR_CARD_X && mouseX <= pl + MAYOR_CARD_X + MAYOR_CARD_W
                && mouseY >= y && mouseY <= y + MAYOR_CARD_H;
            HsUi.card(graphics, pl + MAYOR_CARD_X, y, MAYOR_CARD_W, MAYOR_CARD_H, hovered);
            HsUi.labelIn(graphics, font, Component.literal(candidate.name()),
                pl + MAYOR_TEXT_X, y + 6, MAYOR_NAME_BOX, HsUiTokens.TEXT_STRONG);
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.would_bring",
                    boonName(candidate.boonKey())),
                pl + MAYOR_TEXT_X, y + 20, MAYOR_LINE_BOX, HsUiTokens.TEXT_MUTED);
            HsUi.pips(graphics, pl + MAYOR_BTN_X - 34, y + 9,
                Math.min(5, candidate.knack() * 5 / 100), 5, HsUi.Tone.ACCENT);
        }

        int rows = candidates.size();
        HsUi.scrollbar(graphics, pl + MAYOR_PANEL_W - MAYOR_PAD - HsUiTokens.SCROLL_W,
            pt + MAYOR_LIST_TOP, MAYOR_LIST_H,
            rows == 0 ? 1.0F : Math.min(1.0F, (float) MAYOR_ROWS / rows),
            rows <= MAYOR_ROWS ? 0.0F : (float) mayorScroll / (rows - MAYOR_ROWS), false);

        HsUi.divider(graphics, pl + MAYOR_PAD, pt + MAYOR_FOOT, MAYOR_PANEL_W - 2 * MAYOR_PAD);
        // Word-wrapped, not labelIn -- the "stands X down" sentence can carry
        // the current mayor's full (possibly long) name, and ellipsising a
        // name mid-sentence here reads as a different, shorter sentence
        // rather than a merely-truncated one. See MAYOR_FOOTER_H.
        graphics.drawWordWrap(font, mayorFooter(), pl + MAYOR_PAD, pt + MAYOR_FOOT + 7,
            MAYOR_PANEL_W - 2 * MAYOR_PAD, HsUiTokens.ACCENT);
    }

    /**
     * The seat itself: who holds it (name, boon, tenure or settling
     * countdown), or that it is vacant, or that the settlement is in
     * mourning and the reason Appoint is disabled below.
     */
    private void drawMayorStatus(GuiGraphics graphics, int pl, int pt) {
        int x = pl + MAYOR_PAD;
        int y = pt + MAYOR_STATUS_Y;
        int w = MAYOR_PANEL_W - 2 * MAYOR_PAD;
        HsUi.inset(graphics, x, y, w, MAYOR_STATUS_H);
        if (mayorSnapshot == null) {
            return;
        }
        long now = currentGameTime();
        if (mayorSnapshot.mourning()) {
            long remaining = Math.max(0, mayorSnapshot.mourningUntil() - now);
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.mourning.active",
                formatTicks(remaining)), x + 6, y + 8, w - 12, HsUiTokens.WARN);
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.refused.mourning"),
                x + 6, y + 22, w - 12, HsUiTokens.TEXT_MUTED);
            return;
        }
        if (!mayorSnapshot.hasMayor()) {
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.vacant"),
                x + 6, y + 8, w - 12, HsUiTokens.TEXT_STRONG);
            HsUi.labelIn(graphics, font, Component.translatable("hearthstead.mayor.vacant.hint"),
                x + 6, y + 22, w - 12, HsUiTokens.TEXT_MUTED);
            return;
        }

        HsUi.labelIn(graphics, font, Component.literal(mayorSnapshot.mayorName()),
            x + 6, y + 4, w - 12, HsUiTokens.TEXT_STRONG);
        long settlingRemaining = Math.max(0, (mayorSnapshot.mayorSince() + Mayor.SETTLING_TICKS) - now);
        boolean settling = settlingRemaining > 0;
        Component boonLine = settling
            ? Component.translatable("hearthstead.mayor.brings_pending", boonName(mayorSnapshot.boonKey()))
            : Component.translatable("hearthstead.mayor.brings_now", boonName(mayorSnapshot.boonKey()));
        HsUi.labelIn(graphics, font, boonLine, x + 6, y + 15, w - 12,
            settling ? HsUiTokens.TEXT_MUTED : HsUiTokens.ACCENT);
        HsUi.labelIn(graphics, font, boonDesc(mayorSnapshot.boonKey()), x + 6, y + 26, w - 12,
            HsUiTokens.TEXT_MUTED);
        Component tenureLine = settling
            ? Component.translatable("hearthstead.mayor.current.settling", formatTicks(settlingRemaining))
            : Component.translatable("hearthstead.mayor.current.tenure",
                formatTicks(Math.max(0, now - mayorSnapshot.mayorSince())));
        HsUi.labelIn(graphics, font, tenureLine, x + 6, y + 37, w - 12, HsUiTokens.TEXT_MUTED);
    }

    private Component mayorFooter() {
        if (mayorSnapshot == null) {
            return Component.translatable("hearthstead.mayor.loading");
        }
        if (mayorSnapshot.mourning()) {
            return Component.translatable("hearthstead.mayor.refused.mourning");
        }
        if (mayorSnapshot.candidates().isEmpty()) {
            return Component.translatable("hearthstead.mayor.candidates.empty");
        }
        return mayorSnapshot.hasMayor()
            ? Component.translatable("hearthstead.mayor.footer.swap", mayorSnapshot.mayorName())
            : Component.translatable("hearthstead.mayor.vacant.hint");
    }

    private static Component boonName(String key) {
        return Component.translatable("hearthstead.mayor.boon." + key);
    }

    private static Component boonDesc(String key) {
        return Component.translatable("hearthstead.mayor.boon." + key + ".desc");
    }

    /** "2d 4h", "4h", or "soon" -- ticks-to-days uses Minecraft's own 24000-tick day. */
    private static Component formatTicks(long ticks) {
        if (ticks <= 0) {
            return Component.translatable("hearthstead.mayor.time.soon");
        }
        long days = ticks / 24000L;
        long hours = (ticks % 24000L) / 1000L;
        if (days > 0) {
            return Component.translatable("hearthstead.mayor.time.days_hours", days, hours);
        }
        if (hours > 0) {
            return Component.translatable("hearthstead.mayor.time.hours", hours);
        }
        return Component.translatable("hearthstead.mayor.time.soon");
    }

    private long currentGameTime() {
        var level = net.minecraft.client.Minecraft.getInstance().level;
        return level != null ? level.getGameTime() : 0L;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // Header: settlement name, centered in the title band.
        String name = menu.getSettlementName();
        Component header = name == null || name.isEmpty()
            ? Component.translatable("container.hearthstead.hearth")
            : Component.literal(name);
        graphics.drawString(font, header,
            (imageWidth - font.width(header)) / 2, 10, PARCHMENT_LIGHT, false);

        graphics.drawString(font, Component.translatable("hearthstead.gui.stores"),
            HearthMenu.COMMUNAL_X + 1, HearthMenu.COMMUNAL_Y - 11, INK, false);
        graphics.drawString(font, playerInventoryTitle,
            inventoryLabelX, inventoryLabelY, INK, false);

        // Stat rows.
        int pop = menu.get(HearthMenu.DATA_POPULATION);
        int cap = menu.get(HearthMenu.DATA_CAPACITY);
        int employed = menu.get(HearthMenu.DATA_EMPLOYED);
        int food = menu.get(HearthMenu.DATA_FOOD);
        int radius = menu.get(HearthMenu.DATA_RADIUS);
        drawStat(graphics, 0, 0, pop + " / " + cap);
        drawStat(graphics, 1, 16, employed + " / " + pop);
        drawStat(graphics, 2, 32, String.valueOf(food));
        drawStat(graphics, 3, 48, radius + " m");

        // Morale bar with semantic color.
        int morale = Mth.clamp(menu.get(HearthMenu.DATA_MORALE), 0, 100);
        graphics.drawString(font, Component.translatable("hearthstead.gui.morale"),
            STAT_X, BAR_Y - 10, INK, false);
        int fillColor = moraleColor(morale);
        int fill = morale * (BAR_W - 2) / 100;
        graphics.fill(BAR_X + 1, BAR_Y + 1, BAR_X + 1 + fill, BAR_Y + BAR_H - 1, fillColor);
        // Quarter tick marks over the fill.
        for (int q = 1; q < 4; q++) {
            int tx = BAR_X + q * BAR_W / 4;
            graphics.fill(tx, BAR_Y + 1, tx + 1, BAR_Y + BAR_H - 1, 0x33000000);
        }
        Component band = moraleBand(morale);
        graphics.drawString(font, band, BAR_X + BAR_W + 4, BAR_Y, fillColor | 0xFF000000, false);

        // Alert banner / recruit progress share the strip under the bar.
        if (menu.get(HearthMenu.DATA_ALERT) == 1) {
            boolean blink = (System.currentTimeMillis() / 400) % 2 == 0;
            graphics.drawString(font, Component.translatable("hearthstead.gui.alert"),
                STAT_X, BAR_Y + 14, blink ? 0xFFA03030 : 0xFF702020, false);
        } else {
            int recruit = menu.get(HearthMenu.DATA_RECRUIT);
            if (recruit > 0) {
                graphics.drawString(font,
                    Component.translatable("hearthstead.gui.recruit_progress"),
                    STAT_X, BAR_Y + 13, INK_SOFT, false);
                graphics.fill(BAR_X, BAR_Y + 23, BAR_X + BAR_W, BAR_Y + 26, 0xFF54432F);
                graphics.fill(BAR_X, BAR_Y + 23, BAR_X + recruit * BAR_W / 100,
                    BAR_Y + 26, 0xFFC9A83C);
            }
        }
    }

    /** One icon (from the texture's icon strip at u=224) + value text. */
    private void drawStat(GuiGraphics graphics, int row, int iconV, String value) {
        int y = STAT_Y + row * STAT_ROW_H;
        graphics.blit(TEXTURE, STAT_X, y, 224, iconV, 12, 12);
        graphics.drawString(font, value, STAT_X + 17, y + 2, INK, false);
    }

    private static int moraleColor(int morale) {
        if (morale < 25) {
            return 0xFFA03535;
        }
        if (morale < 50) {
            return 0xFFC07A35;
        }
        if (morale < 75) {
            return 0xFFC9A83C;
        }
        return 0xFF5B8A4A;
    }

    private static Component moraleBand(int morale) {
        String key = morale < 25 ? "miserable" : morale < 50 ? "uneasy"
            : morale < 75 ? "content" : "joyful";
        return Component.translatable("hearthstead.morale." + key);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        renderStatTooltips(graphics, mouseX, mouseY);
    }

    private void renderStatTooltips(GuiGraphics graphics, int mouseX, int mouseY) {
        int localX = mouseX - leftPos;
        int localY = mouseY - topPos;
        List<Component> lines = new ArrayList<>();
        if (localX >= STAT_X && localX < STAT_X + STAT_W) {
            int row = (localY - STAT_Y) / STAT_ROW_H;
            if (localY >= STAT_Y && row >= 0 && row < 4
                && localY < STAT_Y + 4 * STAT_ROW_H) {
                String key = switch (row) {
                    case 0 -> "population";
                    case 1 -> "employed";
                    case 2 -> "food";
                    default -> "radius";
                };
                lines.add(Component.translatable("hearthstead.gui.tooltip." + key));
                lines.add(Component.translatable("hearthstead.gui.tooltip." + key + ".desc")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            } else if (localY >= BAR_Y - 10 && localY < BAR_Y + BAR_H + 2) {
                lines.add(Component.translatable("hearthstead.gui.tooltip.morale"));
                lines.add(Component.translatable("hearthstead.gui.tooltip.morale.desc")
                    .withStyle(net.minecraft.ChatFormatting.GRAY));
            } else if (menu.get(HearthMenu.DATA_ALERT) != 1
                && menu.get(HearthMenu.DATA_RECRUIT) > 0
                && localY >= BAR_Y + 12 && localY < BAR_Y + 27) {
                lines.add(Component.translatable("hearthstead.gui.tooltip.recruit",
                    menu.get(HearthMenu.DATA_RECRUIT)));
            }
        }
        if (!lines.isEmpty()) {
            graphics.renderComponentTooltip(font, lines, mouseX, mouseY);
        }
    }

    /** A folder tab sticking above the window's edge, like vanilla's creative tabs. */
    private static final class SeatTabButton extends AbstractButton {
        private final boolean selected;
        private final Runnable onPress;

        private SeatTabButton(int x, int y, int w, int h, Component label,
                              boolean selected, Runnable onPress) {
            super(x, y, w, h, label);
            this.selected = selected;
            this.onPress = onPress;
        }

        @Override
        public void onPress() {
            onPress.run();
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY,
                                    float partialTick) {
            HsUi.tab(graphics, getX(), getY(), getWidth(), getHeight(), selected);
            var font = net.minecraft.client.Minecraft.getInstance().font;
            HsUi.labelIn(graphics, font, getMessage(),
                getX() + 4, getY() + (getHeight() - HsUiTokens.TEXT_H) / 2,
                getWidth() - 8,
                selected ? HsUiTokens.TEXT : HsUiTokens.TEXT_MUTED);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
