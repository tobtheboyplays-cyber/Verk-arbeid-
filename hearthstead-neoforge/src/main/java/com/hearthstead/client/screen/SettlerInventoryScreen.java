package com.hearthstead.client.screen;

import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.render.SettlerRenderer;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.JobIcons;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import java.util.List;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.menu.SettlerInventoryMenu;
import com.hearthstead.settlement.gear.GearGate;
import com.hearthstead.settlement.gear.GearTier;
import com.hearthstead.settlement.gear.GearTiers;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * Real slot UI for a settler's persisted inventory.
 *
 * <p>There are intentionally no synthetic item buttons here. The eight top
 * slots are the server entity's bag and the lower slots are the player's
 * ordinary inventory, so drag, split, hotbar swap and shift-click all use
 * vanilla's proven container transactions.
 *
 * <p>Drawn as a sibling of the settler sheet: the standard walnut board,
 * the burgundy crest carrying the job icon, the settler's name in serif
 * small caps on the wood, the wooden close key and one parchment page.
 * The page holds the field-kit rail (portrait, bag space) on the left and
 * the bag, gear tiers, request status and player inventory on the right.
 * Slot positions are the menu's; the frame is built around them.
 */
public final class SettlerInventoryScreen
        extends AbstractContainerScreen<SettlerInventoryMenu>
        implements QaUiInspectable {
    /** 280 before; 8 wider so the gear strip and request status stay on the page. */
    static final int WIDTH = 288;
    static final int HEIGHT = 222;
    /**
     * The board starts this far above the container image so the header and
     * its rule sit above the bag row while the menu's slot offsets stay put.
     */
    static final int LIFT = 8;
    private static final int REQUEST_Y = 84;
    private static final int REQUEST_H = 34;

    // Page blocks, image-relative (renderLabels coordinates).
    static final int RAIL_X = 18;
    static final int RAIL_W = 68;
    static final int DIVIDER_X = 91;
    static final int LABEL_Y = 33;
    static final Rect RAIL_TITLE_RECT = new Rect(RAIL_X, LABEL_Y, RAIL_W, 8);
    static final Rect PROFESSION_RECT = new Rect(RAIL_X, 45, RAIL_W, 8);
    static final Rect PORTRAIT_RECT = new Rect(16, 58, 72, 58);
    static final Rect RAIL_BAG_RECT = new Rect(RAIL_X, 124, RAIL_W, 8);
    static final Rect OCCUPANCY_RECT = new Rect(RAIL_X, 136, RAIL_W, 8);
    static final Rect PIPS_RECT = new Rect(RAIL_X, 148, SettlerInventoryMenu.SETTLER_SLOTS * 8 - 2, 3);
    static final Rect RAIL_RULE_RECT = new Rect(RAIL_X, 156, RAIL_W, 1);
    static final Rect ACTION_RECT = new Rect(RAIL_X, 166, RAIL_W, 8);
    static final Rect DETAIL_RECT = new Rect(RAIL_X, 178, RAIL_W, 8);

    private static final int GEAR_X = 176;
    private static final int GEAR_Y = 44;
    private static final int CHIP_W = 17;
    private static final int CHIP_H = 13;
    private static final int CHIP_STEP = 19;
    private static final int GEAR_TEXT_W = 92;
    static final Rect BAG_LABEL_RECT = new Rect(SettlerInventoryMenu.BAG_X, LABEL_Y,
        GEAR_X - 8 - SettlerInventoryMenu.BAG_X, 8);
    static final Rect GEAR_LABEL_RECT = new Rect(GEAR_X, LABEL_Y, GEAR_TEXT_W, 8);
    /** The five chips including the gold outline of the usable one. */
    static final Rect GEAR_CHIPS_RECT = new Rect(GEAR_X - 1, GEAR_Y - 1,
        GearTier.MAX * CHIP_STEP + CHIP_W + 2, CHIP_H + 2);
    static final Rect GEAR_LINES_RECT = new Rect(GEAR_X, GEAR_Y + 17, GEAR_TEXT_W, 18);
    static final Rect REQUEST_RECT = new Rect(94, REQUEST_Y, WIDTH - 16 - 94, REQUEST_H);
    static final Rect INVENTORY_LABEL_RECT = new Rect(SettlerInventoryMenu.PLAYER_X,
        SettlerInventoryMenu.PLAYER_Y - 9, WIDTH - 16 - SettlerInventoryMenu.PLAYER_X, 8);

    private boolean uiSoundActive;
    private RequestCopy requestCopy;
    private Ui2FrameLayout frame;
    private final Ui2Serif.Text nameText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private static final Component BAG_LABEL = Component.translatable("hearthstead.settler.inventory.bag");
    private static final Component RAIL_TITLE = Component.translatable("hearthstead.settler.inventory.rail.title");
    private static final Component RAIL_BAG = Component.translatable("hearthstead.settler.inventory.rail.bag");
    private static final Component RAIL_ACTION = Component.translatable("hearthstead.settler.inventory.rail.action");
    private static final Component RAIL_DETAIL = Component.translatable("hearthstead.settler.inventory.rail.action.detail");
    private final HsUi.FittedLabelCache bagLabelCache = new HsUi.FittedLabelCache();
    private final HsUi.FittedLabelCache inventoryLabelCache = new HsUi.FittedLabelCache();
    private final HsUi.FittedLabelCache professionLabelCache = new HsUi.FittedLabelCache();
    private final HsUi.FittedLabelCache railTitleCache = new HsUi.FittedLabelCache();
    private final HsUi.FittedLabelCache railBagCache = new HsUi.FittedLabelCache();
    private final HsUi.FittedLabelCache railActionCache = new HsUi.FittedLabelCache();
    private final HsUi.FittedLabelCache railDetailCache = new HsUi.FittedLabelCache();
    private int cachedBagUsed = -1;
    private Component bagOccupancy = Component.empty();

    public SettlerInventoryScreen(SettlerInventoryMenu menu,
                                  Inventory playerInventory,
                                  Component title) {
        super(menu, playerInventory, title);
        imageWidth = WIDTH;
        imageHeight = HEIGHT;
        titleLabelX = 8;
        titleLabelY = 10;
        inventoryLabelX = SettlerInventoryMenu.PLAYER_X;
        inventoryLabelY = SettlerInventoryMenu.PLAYER_Y - 9;
    }

    @Override
    protected void init() {
        super.init();
        // Centre the whole board (image + LIFT), then keep the image under it.
        topPos = imageTop(height);
        frame = frameAt(leftPos, topPos);
        addRenderableWidget(Ui2Frame.closeKey(frame, this::onClose));
        if (!uiSoundActive) {
            uiSoundActive = true;
            HsUi.playOpenSound();
        }
    }

    @Override
    public void removed() {
        if (uiSoundActive) {
            uiSoundActive = false;
            HsUi.playCloseSound();
        }
        super.removed();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY,
                       float partialTick) {
        // NeoForge's AbstractContainerScreen#render already invokes this
        // screen's renderBackground (transparent field + renderBg). Calling it
        // here as well painted the complete 44-slot inventory twice per frame
        // and was the last surviving double-background path in Hearthstead.
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        List<Component> gearTip = gearTooltipAt(mouseX - leftPos, mouseY - topPos);
        if (!gearTip.isEmpty()) {
            graphics.renderComponentTooltip(font, gearTip, mouseX, mouseY);
        } else if (requestCopy != null && REQUEST_RECT.contains(mouseX - leftPos, mouseY - topPos)) {
            graphics.renderComponentTooltip(font, requestCopy.tooltip(), mouseX, mouseY);
        } else if (frame.title().contains(mouseX, mouseY)) {
            graphics.renderTooltip(font, title, mouseX, mouseY);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled) {
            QaClientObserver.markUiTransition("settler_inventory_click");
        }
        return handled;
    }

    /** The lifted header is part of the window: clicks there never throw the carried stack. */
    @Override
    protected boolean hasClickedOutside(double mouseX, double mouseY, int left, int top, int button) {
        return mouseX < left || mouseY < top - LIFT
            || mouseX >= left + imageWidth || mouseY >= top + imageHeight;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick,
                            int mouseX, int mouseY) {
        Ui2Frame.draw(graphics, frame);
        SettlerEntity settler = menu.settler();
        if (settler != null) crestDevice(graphics, settler);
        Ui2Frame.title(graphics, font, frame, nameText, title.getString(), null);
        Ui2Surface.ruleVertical(graphics, leftPos + DIVIDER_X, frame.page().y() + Ui2FrameLayout.S,
            frame.page().height() - Ui2FrameLayout.S * 2);
        if (settler != null) {
            Rect p = PORTRAIT_RECT;
            SettlerRenderer.withoutPortraitLabels(settler, () ->
                InventoryScreen.renderEntityInInventoryFollowsMouse(graphics,
                    leftPos + p.x(), topPos + p.y(), leftPos + p.right(), topPos + p.bottom(),
                    26, 0.0625F, mouseX, mouseY, settler));
        }
        for (var slot : menu.slots) {
            Ui2Surface.slotWell(graphics, leftPos + slot.x - 1, topPos + slot.y - 1);
        }
        boolean needsEquipment = settler != null && !settler.requestedEquipmentIcon().isEmpty();
        Rect r = REQUEST_RECT;
        Ui2Frame.status(graphics, font, new Rect(leftPos + r.x(), topPos + r.y(), r.width(), r.height()),
            Component.empty(), needsEquipment ? Ui2Frame.Tone.WAIT : Ui2Frame.Tone.GOOD);
    }

    /** The job icon on a small linen roundel, like the settler sheet's crest. */
    private void crestDevice(GuiGraphics graphics, SettlerEntity settler) {
        Rect c = frame.crest();
        int size = 12;
        int x = c.x() + (c.width() - size) / 2;
        int y = c.y() + 8;
        graphics.fill(x - 1, y - 1, x + size + 1, y + size + 1, Ui2Palette.PAPER);
        JobIcons.draw(graphics, settler.getProfession(), x, y, size);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        String language = minecraft.getLanguageManager().getSelected();
        // The name is on the wood (renderBg); page text is ink without shadow.
        HsUi.FittedLabel shownBag = bagLabelCache.fit(font, BAG_LABEL, BAG_LABEL_RECT.width(), language);
        graphics.drawString(font, shownBag.text(), BAG_LABEL_RECT.x(), BAG_LABEL_RECT.y(),
            Ui2Palette.INK_MUTED, false);
        HsUi.FittedLabel shownInventory = inventoryLabelCache.fit(font, playerInventoryTitle,
            INVENTORY_LABEL_RECT.width(), language);
        Ui2Surface.sectionHeader(graphics, font, shownInventory.text(), inventoryLabelX, inventoryLabelY,
            INVENTORY_LABEL_RECT.width());
        ink(graphics, railTitleCache, RAIL_TITLE, RAIL_TITLE_RECT, Ui2Palette.INK, language);
        ink(graphics, railBagCache, RAIL_BAG, RAIL_BAG_RECT, Ui2Palette.INK_MUTED, language);
        int used = 0;
        // Exactly eight real bag slots; never scans world entities or settlement inventories.
        for (int i = 0; i < SettlerInventoryMenu.SETTLER_SLOTS; i++) {
            if (menu.getSlot(i).hasItem()) {
                used++;
            }
        }
        if (used != cachedBagUsed) {
            cachedBagUsed = used;
            bagOccupancy = Component.literal(used + " / " + SettlerInventoryMenu.SETTLER_SLOTS);
        }
        graphics.drawString(font, bagOccupancy, OCCUPANCY_RECT.x(), OCCUPANCY_RECT.y(), Ui2Palette.INK, false);
        for (int slot = 0; slot < SettlerInventoryMenu.SETTLER_SLOTS; slot++) {
            int px = PIPS_RECT.x() + slot * 8;
            graphics.fill(px, PIPS_RECT.y(), px + 6, PIPS_RECT.bottom(),
                menu.getSlot(slot).hasItem() ? Ui2Palette.GOLD : Ui2Palette.TRACK);
        }
        Ui2Surface.rule(graphics, RAIL_RULE_RECT.x(), RAIL_RULE_RECT.y(), RAIL_RULE_RECT.width());
        ink(graphics, railActionCache, RAIL_ACTION, ACTION_RECT, Ui2Palette.INK, language);
        ink(graphics, railDetailCache, RAIL_DETAIL, DETAIL_RECT, Ui2Palette.INK_MUTED, language);

        SettlerEntity settler = menu.settler();
        if (settler != null) {
            HsUi.FittedLabel shownProfession = professionLabelCache.fit(font,
                settler.getProfession().displayName(), RAIL_W, language);
            graphics.drawString(font, shownProfession.text(), PROFESSION_RECT.x(), PROFESSION_RECT.y(),
                Ui2Palette.INK_SOFT, false);
        }
        ItemStack request = settler == null ? ItemStack.EMPTY : settler.requestedEquipmentIcon();
        // Status text runs from the strip's glyph (x + 14); the item icon, when
        // there is one, sits there and the text moves right of it.
        int statusTextX = REQUEST_RECT.x() + 14;
        int statusTextW = REQUEST_RECT.right() - 4 - statusTextX;
        if (requestCopy == null || requestCopy.font() != font || !requestCopy.language().equals(language)
            || !ItemStack.isSameItemSameComponents(requestCopy.item(), request)) {
            Component line = request.isEmpty()
                ? Component.translatable("hearthstead.settler.request.none")
                : Component.translatable("hearthstead.settler.inventory.request", request.getHoverName());
            Component instruction = Component.translatable(request.isEmpty()
                ? "hearthstead.settler.request.none.instruction" : "hearthstead.settler.inventory.authority");
            requestCopy = new RequestCopy(request.copy(), font, language,
                firstLines(font.split(line, request.isEmpty() ? statusTextW : statusTextW - 18)),
                HsUi.fitLabel(font, instruction, statusTextW), List.of(line, instruction));
        }
        int textX = request.isEmpty() ? statusTextX : statusTextX + 18;
        if (!request.isEmpty()) {
            graphics.renderItem(request, statusTextX, REQUEST_Y + 2);
        }
        HsUi.drawLines(graphics, font, requestCopy.lines(), textX, REQUEST_Y + 4, Ui2Palette.INK);
        graphics.drawString(font, requestCopy.instruction().text(), statusTextX, REQUEST_Y + 23,
            Ui2Palette.INK_MUTED, false);
        if (settler != null && GearGate.enabled()) {
            renderGearStrip(graphics, settler);
            renderBagLocks(graphics, settler);
        }
    }

    /** Page text: fitted to its block, ink, no shadow. */
    private void ink(GuiGraphics graphics, HsUi.FittedLabelCache cache, Component text, Rect r, int colour,
                     String language) {
        graphics.drawString(font, cache.fit(font, text, r.width(), language).text(), r.x(), r.y(), colour, false);
    }

    // ------------------------------------------------------------ gear tiers ---
    //
    // Gear Tier strip (GearTier / GearGate): five chips T0..T4 beside the bag,
    // open chips forest, locked chips muted with the padlock, and the next
    // unlock underneath. Reads only the settler's synced clearance.

    private static final Component GEAR_TITLE = Component.translatable("hearthstead.gear.title");

    private void renderGearStrip(GuiGraphics graphics, SettlerEntity settler) {
        GearGate.Clearance c = GearGate.clearance(settler);
        graphics.drawString(font, GEAR_TITLE, GEAR_LABEL_RECT.x(), GEAR_LABEL_RECT.y(), Ui2Palette.INK_MUTED, false);
        int usable = c.usable();
        for (int t = 0; t <= GearTier.MAX; t++) {
            int x = GEAR_X + t * CHIP_STEP;
            boolean open = c.allows(t);
            if (open && t == usable) {
                graphics.fill(x - 1, GEAR_Y - 1, x + CHIP_W + 1, GEAR_Y + CHIP_H + 1, Ui2Palette.GOLD);
            }
            graphics.fill(x, GEAR_Y, x + CHIP_W, GEAR_Y + CHIP_H,
                open ? Ui2Palette.FOREST : Ui2Palette.DISABLED_FILL);
            Component label = Component.translatable("hearthstead.gear.chip", t);
            int w = font.width(label);
            graphics.drawString(font, label, open ? x + (CHIP_W - w) / 2 : x + 6,
                GEAR_Y + 3, open ? Ui2Palette.ON_ACCENT : Ui2Palette.INK_MUTED, false);
            if (!open) {
                Ui2Surface.lockGlyph(graphics, x + 1, GEAR_Y + 3, Ui2Palette.INK_SOFT);
            }
        }
        GearTier next = c.nextLocked();
        Component line1 = next == null ? Component.translatable("hearthstead.gear.top")
            : Component.translatable("hearthstead.gear.next.short", tierTitle(next));
        graphics.drawString(font, HsUi.fitLabel(font, line1, GEAR_TEXT_W).text(), GEAR_X, GEAR_Y + 17,
            next == null ? Ui2Palette.FOREST : Ui2Palette.INK_SOFT, false);
        if (next != null) {
            graphics.drawString(font, HsUi.fitLabel(font, GearGate.missing(c, next), GEAR_TEXT_W).text(),
                GEAR_X, GEAR_Y + 27, Ui2Palette.INK_MUTED, false);
        }
    }

    private static Component tierTitle(GearTier tier) {
        return Component.translatable("hearthstead.gear.tooltip.title", tier.level(),
            tier.displayName());
    }

    /** Danger wash and a padlock over pack items this settler may not use yet. */
    private void renderBagLocks(GuiGraphics graphics, SettlerEntity settler) {
        graphics.pose().pushPose();
        graphics.pose().translate(0.0F, 0.0F, 300.0F);
        int wash = 0x55000000 | (Ui2Palette.DANGER & 0xFFFFFF);
        for (int i = 0; i < SettlerInventoryMenu.SETTLER_SLOTS; i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.isEmpty() || !GearGate.relevant(settler, stack)
                || GearGate.allows(settler, stack)) {
                continue;
            }
            int x = menu.getSlot(i).x;
            int y = menu.getSlot(i).y;
            graphics.fill(x, y, x + 16, y + 16, wash);
            graphics.fill(x + 10, y + 8, x + 17, y + 17, Ui2Palette.PAPER);
            Ui2Surface.lockGlyph(graphics, x + 11, y + 9, Ui2Palette.DANGER);
        }
        graphics.pose().popPose();
    }

    /** Chip and next-unlock tooltips, in screen-relative coordinates. */
    private List<Component> gearTooltipAt(int x, int y) {
        SettlerEntity settler = menu.settler();
        if (settler == null || !GearGate.enabled()) {
            return List.of();
        }
        GearGate.Clearance c = GearGate.clearance(settler);
        if (y >= GEAR_Y && y < GEAR_Y + CHIP_H && x >= GEAR_X
            && x < GEAR_X + GearTier.MAX * CHIP_STEP + CHIP_W) {
            int t = (x - GEAR_X) / CHIP_STEP;
            if (x - GEAR_X - t * CHIP_STEP < CHIP_W) {
                return tierLines(c, GearTier.of(t));
            }
        }
        if (y >= GEAR_Y + 16 && y < GEAR_Y + 37 && x >= GEAR_X && x < WIDTH - 8) {
            Component next = GearGate.nextUnlock(c);
            return next == null ? List.of() : List.of(next);
        }
        return List.of();
    }

    private static List<Component> tierLines(GearGate.Clearance c, GearTier tier) {
        List<Component> lines = new java.util.ArrayList<>();
        lines.add(tierTitle(tier));
        lines.add(Component.translatable("hearthstead.gear.tooltip.items", tier.itemsName())
            .withStyle(net.minecraft.ChatFormatting.GRAY));
        lines.add(Component.translatable(c.personalMet(tier.level())
                ? "hearthstead.gear.tooltip.personal.met"
                : "hearthstead.gear.tooltip.personal.missing",
            tier.personalRequirement(c.role())));
        Component know = tier.knowledgeRequirement();
        lines.add(know == null ? Component.translatable("hearthstead.gear.tooltip.knowledge.none")
            : Component.translatable(c.knowledgeMet(tier.level())
                    ? "hearthstead.gear.tooltip.knowledge.met"
                    : "hearthstead.gear.tooltip.knowledge.missing", know));
        return lines;
    }

    @Override
    protected List<Component> getTooltipFromContainerItem(ItemStack stack) {
        List<Component> lines = super.getTooltipFromContainerItem(stack);
        SettlerEntity settler = menu.settler();
        if (settler == null || !GearGate.relevant(settler, stack)) {
            return lines;
        }
        GearTier tier = GearTiers.gearTierOf(stack);
        GearGate.Clearance c = GearGate.clearance(settler);
        List<Component> out = new java.util.ArrayList<>(lines);
        if (c.allows(tier.level())) {
            out.add(Component.translatable("hearthstead.gear.item.ok", tier.level(),
                tier.displayName()).withStyle(net.minecraft.ChatFormatting.DARK_GREEN));
        } else {
            out.add(Component.translatable("hearthstead.gear.item.locked", tier.level(),
                tier.displayName()).withStyle(net.minecraft.ChatFormatting.RED));
            out.add(Component.translatable("hearthstead.gear.item.needs",
                GearGate.missing(c, tier)).withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        return out;
    }

    private static <T> List<T> firstLines(List<T> lines) {
        return List.copyOf(lines.subList(0, Math.min(2, lines.size())));
    }

    private record RequestCopy(ItemStack item, Font font, String language,
                               List<net.minecraft.util.FormattedCharSequence> lines, HsUi.FittedLabel instruction,
                               List<Component> tooltip) { }

    @Override
    public String qaUiState() {
        SettlerEntity settler = menu.settler();
        ItemStack request = settler == null ? ItemStack.EMPTY
            : settler.requestedEquipmentIcon();
        String equipment = request.isEmpty() ? "ready"
            : "needs_" + BuiltInRegistries.ITEM.getKey(request.getItem());
        int bagUsed = 0;
        for (int slot = 0; slot < SettlerInventoryMenu.SETTLER_SLOTS; slot++) {
            if (menu.getSlot(slot).hasItem()) {
                bagUsed++;
            }
        }
        return "equipment=" + equipment + ",bagUsed=" + bagUsed + "/"
            + SettlerInventoryMenu.SETTLER_SLOTS + ",requestCard=" + REQUEST_Y
            + ":" + REQUEST_H + ",inventoryLabelY=" + inventoryLabelY
            + ",labelGap=" + (inventoryLabelY - (REQUEST_Y + REQUEST_H));
    }

    // ------------------------------------------------------------ layout ---

    /** Container image top for a viewport: the whole board (image + LIFT) is centred. */
    static int imageTop(int viewportHeight) {
        return (viewportHeight - (HEIGHT + LIFT)) / 2 + LIFT;
    }

    /** The standard frame around the container image, lifted by {@link #LIFT}. */
    static Ui2FrameLayout frameAt(int leftPos, int topPos) {
        return Ui2FrameLayout.at(leftPos, topPos - LIFT, WIDTH, HEIGHT + LIFT, false);
    }

    /** Every non-slot page block, image-relative, for the layout test. */
    static List<Rect> pageBlocks() {
        return List.of(RAIL_TITLE_RECT, PROFESSION_RECT, PORTRAIT_RECT, RAIL_BAG_RECT, OCCUPANCY_RECT,
            PIPS_RECT, RAIL_RULE_RECT, ACTION_RECT, DETAIL_RECT, BAG_LABEL_RECT, GEAR_LABEL_RECT,
            GEAR_CHIPS_RECT, GEAR_LINES_RECT, REQUEST_RECT, INVENTORY_LABEL_RECT);
    }
}
