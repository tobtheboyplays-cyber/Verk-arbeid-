package com.hearthstead.client.screen;

import com.hearthstead.building.BuildingType;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Trait;
import com.hearthstead.network.SettlerActionPayload;
import com.hearthstead.network.SettlerSnapshotPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * The settler inspection screen: who they are, what they are made of, and
 * what a player standing in front of them can do about it.
 *
 * <h2>Two sources, never confused</h2>
 *
 * <p>Name, profession, current activity and the three needs are already
 * synced entity data (see {@code SettlerEntity}) — this screen reads them
 * straight off {@link #settler} every frame, exactly the way the old card
 * did. Attributes, traits and the settler's place in the settlement (who
 * employs them, what shift a guard stands, whether they hold the mayoral
 * seat) live server-side only, because rolling attributes again on the
 * client would describe a <i>different</i> person. Those arrive once as a
 * {@link SettlerSnapshotPayload} — sent right after the screen opens, and
 * again after every action — and the screen draws only what it was told,
 * the same discipline {@code PlaqueScreen} keeps.
 *
 * <h2>Traits show only what they verifiably do</h2>
 *
 * <p>{@link Trait} carries eight multiplier fields, but only four are ever
 * read by any gameplay system today — {@code growth} ({@code
 * SettlerEntity#train}), {@code moraleDecay}/{@code moraleGain} ({@code
 * SettlerEntity#addMorale}) and {@code hunger} ({@code SettlerEntity}'s own
 * hunger tick) — plus the flat {@code SLOW_START} penalty ({@code
 * SettlerEntity#applySlowStart}). {@code carry}, {@code speed}, {@code work}
 * and {@code sight}, and every {@code Trait.Flag} besides {@code
 * SLOW_START}, are declared on the enum but consumed nowhere in production
 * code (verified by search — see the builder's report). Showing those as
 * quantified buffs would be exactly the false claim the "ingen tydelige
 * buffs" complaint is about, only dressed up: a player would read "+25%
 * carry" and find nothing changes. So {@link #wiredEffects} only ever
 * reports the four multipliers and the one flag that a gameplay system
 * actually reads; a trait with none of those still gets its existing
 * flavour line ({@link Trait#describe()}), just not a fabricated number.
 *
 * <h2>A fixed shape, so the panel never resizes under the mouse</h2>
 *
 * <p>The mayor badge, the trait cards, the refusal banner and the bag are
 * all optional or variable-length content, but {@link #layout} reserves
 * their rows unconditionally — a settler with one trait simply leaves the
 * second card blank rather than the whole panel growing and shrinking as
 * settlers or actions change. A window that resizes while you are using it
 * reads as broken; a little unused space when a row is absent does not.
 */
public class SettlerScreen extends Screen {

    // -- geometry: vanilla metrics (20px buttons, 4px grid), see the
    //    minecraft-ui skill. Text boxes are generous and rely on
    //    HsUi.labelIn's ellipsis as the safety net for long translations. --
    // 224 clipped the mayor badge's "settling in" sentence -- "Ordfører —
    // Nøysomt arbeid (setter seg inn)" measured 224px against its 200px box
    // (colW - 8), 24px over. 256 carries that box to 232px, clearing it
    // (and the English worst case, 175px) with margin; every other box on
    // this panel derives from panelW/colW and only gains room.
    /**
     * Narrow fallback: one column, the shape this sheet has always had.
     * Only reached when the viewport cannot give {@link #PANEL_W_WIDE} its
     * width, which at the default GUI scales means a very small window.
     */
    private static final int PANEL_W_NARROW = 256;
    /**
     * Two columns side by side, which is how this sheet FITS.
     *
     * <p>Stacked in one column the panel is 322px tall. At guiScale 3 on a
     * 1280x720 window the viewport is 240 -- so the sheet did not fit, and
     * the scroll that was added to cope with that is the "excessive
     * scrolling" complaint. Standing the two halves beside each other instead
     * takes it to about 219, which fits that viewport outright and every
     * larger one with room to spare.
     *
     * <p>180 per column is measured, not chosen: the widest attribute name in
     * either language is "Utholdenhet" at 59px, its value reads "100 / 100"
     * at 50px, and the bar between them is 40 -- 157px of content in a 180px
     * column. Two of those plus three paddings is 384, which clears the 426px
     * a guiScale-3 720p viewport gives.
     */
    private static final int PANEL_W_WIDE = 384;
    private static final int COL_W_WIDE = 180;
    private static final int PAD = HsUiTokens.PAD;
    private static final int GUTTER = HsUiTokens.GUTTER;

    /** This screen's chosen width and column width, decided in {@link #init}. */
    private int panelW = PANEL_W_NARROW;
    private int colW = PANEL_W_NARROW - 2 * PAD;
    private boolean wide;
    /** One compact text row: glyph height plus a hair of breathing room. */
    private static final int ROW = 12;

    private static final int PORTRAIT_W = 50;
    private static final int PORTRAIT_H = 56;
    private static final int HEADER_H = PORTRAIT_H;
    private static final int HEADER_TEXT_X = PAD + PORTRAIT_W + 8;
    /** Derived per layout: the identity text box beside the portrait. */
    private int headerTextW = PANEL_W_NARROW - HEADER_TEXT_X - PAD;
    /** The mayor mark's own cap -- both languages' "Mayor"/"Ordfører" clear it. */
    private static final int MAYOR_MARK_W = 40;

    // The value column reads "100 / 100" at its widest, which measures 50px;
    // 52 keeps it off the bar beside it. The bar is 40 -- wide enough to read
    // a quarter-turn at a glance, narrow enough that name, bar and number all
    // fit the 180px column (59 + 4 + 40 + 4 + 52 = 159).
    private static final int ATTR_VALUE_W = 52;
    private static final int ATTR_BAR_W = 40;
    private static final int ATTR_BAR_H = 6;
    private static final int NEED_LABEL_W = 44;
    private static final int NEED_PCT_W = 26;
    private static final int NEED_BAR_H = 6;
    private static final int MAYOR_BADGE_H = ROW + 2;

    // -- traits: one card per trait slot, fixed shape regardless of how many
    //    a given settler rolled (see Trait.roll -- always 1, one time in ten,
    //    2). Each card is exactly two lines: the trait's name, and one
    //    content line that is either its wired buff/malus chips (tone
    //    coloured, magnitude shown) or -- when a trait has none, see the
    //    class doc -- its existing flavour sentence. Fixed height either way,
    //    so the panel's shape never depends on which traits a settler has.
    private static final int TRAIT_SLOTS = 2;
    private static final int TRAIT_CARD_H = 26;
    private static final int TRAIT_CARD_PAD = 4;

    // -- the bag: a fixed-shape row of BAG_SIZE ghost slots, the same 18px
    //    slot HsUi and StorageScreen already use. Reserved unconditionally
    //    (see the class doc and layout()) so an empty bag is a row of empty
    //    slots under its own label rather than a hole in the panel.
    private static final int BAG_SLOTS = SettlerEntity.BAG_SIZE;
    private static final int BAG_SLOT_STEP = HsUiTokens.SLOT + 2;

    private static final int BTN_W = 64;

    protected final SettlerEntity settler;
    private SettlerSnapshotPayload snapshot;
    private int left;
    private int top;
    // -- scroll: only load-bearing when the fixed-shape panel does not fit
    //    the current viewport (guiScale 3 or 4 on a modest window; see
    //    init()). At guiScale 1-2 maxScroll is 0 and every field below is
    //    inert, top staying the plain centred value it always was. --
    private int contentHeight;
    private int baseTop;
    private int scrollOffset;
    private int maxScroll;
    /** Set while drawing a hovered non-widget region; rendered once, last. */
    private List<Component> pendingTooltip;
    /** Built by {@link #rebuild}, read by {@link #render} -- never per frame. */
    private Layout cachedLayout;
    private final Component[] attrValueCache = new Component[Attribute.COUNT];
    private final int[] attrValueShown = new int[Attribute.COUNT];
    private final ItemStack[] bagStacks = new ItemStack[BAG_SLOTS];

    public SettlerScreen(SettlerEntity settler) {
        super(Component.literal(settler.getSettlerName()));
        this.settler = settler;
    }

    /**
     * A fresh snapshot from the server replaces what is on screen. Guarded by
     * entity id even though only one settler screen is ever open at a time —
     * a snapshot in flight when the player closes this screen and opens a
     * different settler's must never land on the wrong one.
     */
    public void update(SettlerSnapshotPayload fresh) {
        if (fresh.entityId() == settler.getId()) {
            this.snapshot = fresh;
            rebuildBagStacks();
            rebuild();
        }
    }

    /**
     * The bag's stacks, built once per snapshot instead of once per frame.
     *
     * <p>A settler's bag changes when they pick something up; rebuilding six
     * ItemStacks sixty times a second to draw the same six items is garbage
     * with no reader.
     */
    private void rebuildBagStacks() {
        List<Integer> ids = snapshot.bagItemIds();
        List<Integer> counts = snapshot.bagCounts();
        for (int i = 0; i < BAG_SLOTS; i++) {
            int count = i < counts.size() ? counts.get(i) : 0;
            bagStacks[i] = count <= 0
                ? ItemStack.EMPTY
                : new ItemStack(BuiltInRegistries.ITEM.byId(
                    i < ids.size() ? ids.get(i) : 0), count);
        }
    }

    @Override
    protected void init() {
        // Two columns whenever the viewport can hold them, one when it
        // cannot. Decided here and nowhere else, so the panel's shape is
        // settled before a single row is placed.
        wide = width >= PANEL_W_WIDE + 2 * PAD;
        panelW = wide ? PANEL_W_WIDE : PANEL_W_NARROW;
        colW = wide ? COL_W_WIDE : PANEL_W_NARROW - 2 * PAD;
        headerTextW = colW - PORTRAIT_W - 8;
        left = (width - panelW) / 2;
        contentHeight = layout(0).totalHeight;
        // The panel is a fixed shape (see class doc) sized for its roomiest
        // content, not for the tightest viewport a player can have open. At
        // guiScale 3 on a 1280x720 window the viewport is 240px tall against
        // this panel's 322 -- centring unconditionally, as this used to,
        // clipped 41px off BOTH edges: the whole header identity block (the
        // settler's name and profession -- exactly what a player opens this
        // sheet to read) vanished off the top, and both footer buttons,
        // Close included, vanished off the bottom, with no on-screen sign
        // that either existed. That is not a cosmetic crop; Escape still
        // closed the screen, but nothing on it said so (found live,
        // 2026-08-26, guiScale-3 finding, sheet_00_none_try1.png and
        // sheet_blur_check_*.png).
        //
        // Centring stays exactly as it was whenever the panel fits
        // (maxScroll == 0, true today at guiScale 1-2 and at any wider
        // window). Only when it does not fit does the panel anchor near the
        // top instead -- the header is visible the instant the sheet opens,
        // matching the two fields the owner actually checks each sheet for
        // ("is the name there", "is the profession named correctly") -- and
        // mouseScrolled below walks the rest of the panel, footer included,
        // into view.
        maxScroll = Math.max(0, contentHeight - height + PAD * 2);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
        baseTop = maxScroll == 0 ? (height - contentHeight) / 2 : PAD;
        top = baseTop - scrollOffset;
        rebuild();
    }

    /**
     * Only reachable once the panel has overflowed the viewport (see
     * {@link #init} -- a scrollbar that cannot move is not a feature, the
     * same guard {@code ResearchScreen} and {@code HandbookScreen} apply to
     * their own lists). Rebuilding after every change moves the footer
     * buttons' real hitboxes along with what is drawn, rather than
     * scrolling the picture while leaving the clickable area behind.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0) {
            int before = scrollOffset;
            scrollOffset = Mth.clamp(scrollOffset - (int) Math.signum(scrollY) * ROW, 0, maxScroll);
            if (before != scrollOffset) {
                top = baseTop - scrollOffset;
                rebuild();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------ widgets ---

    private void rebuild() {
        clearWidgets();
        // The one place a Layout is built. render() reads this field instead
        // of recomputing: a fresh Layout object every frame is 80 bytes of
        // garbage sixty times a second to describe rows that only move when
        // the window resizes or the sheet scrolls -- both of which land here.
        cachedLayout = layout(top);
        Layout l = cachedLayout;

        boolean employed = settler.getProfession().employed();
        if (employed) {
            HsButton dismiss = HsButton.danger(left + l.colBX, l.footerTop, BTN_W,
                HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.employ.dismiss"),
                () -> act(SettlerActionPayload.Kind.DISMISS));
            dismiss.active = snapshot != null && snapshot.canManage();
            dismiss.setTooltip(Tooltip.create(Component.translatable(
                "hearthstead.settler.dismiss.tip", title, buildingName())));
            addRenderableWidget(dismiss);
        }
        addRenderableWidget(HsButton.normal(left + l.colBX + colW - BTN_W, l.footerTop, BTN_W,
            HsUiTokens.BUTTON_H, Component.translatable("hearthstead.settler.close"),
            this::onClose));

        HsButton appoint = HsButton.normal(left + l.colBX, l.appointTop, colW,
            HsUiTokens.BUTTON_H, Component.translatable("hearthstead.settler.appoint"),
            () -> act(SettlerActionPayload.Kind.APPOINT));
        appoint.active = appointEnabled();
        appoint.setTooltip(Tooltip.create(appointTooltip()));
        addRenderableWidget(appoint);
    }

    private boolean appointEnabled() {
        return snapshot != null && snapshot.canManage() && !snapshot.isMayor()
            && !snapshot.mourning();
    }

    private Component appointTooltip() {
        if (snapshot == null || !snapshot.canManage()) {
            return Component.translatable("hearthstead.settler.appoint.tip.no_settlement");
        }
        if (snapshot.isMayor()) {
            return Component.translatable("hearthstead.mayor.refused.already");
        }
        if (snapshot.mourning()) {
            return Component.translatable("hearthstead.mayor.refused.mourning");
        }
        return Component.translatable("hearthstead.settler.appoint.tip", title, boonName());
    }

    private void act(SettlerActionPayload.Kind kind) {
        if (snapshot != null) {
            PacketDistributor.sendToServer(
                new SettlerActionPayload(settler.getId(), kind, snapshot.revision()));
        }
    }

    // ------------------------------------------------------------- drawing ---

    /**
     * A scrim, not a blur.
     *
     * <p>Vanilla's plain-Screen background runs a full-screen post-process
     * blur every frame; its own container screens do not. Measured at 110 of
     * this screen's 117ms per frame with the entire panel skipped. See
     * {@code HsUi#BACKGROUND_POLICY} for the measurement and the reasoning.
     */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(g);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        pendingTooltip = null;
        if (cachedLayout == null) {
            rebuild();
        }
        Layout l = cachedLayout;
        int a = left + l.colAX;
        int b = left + l.colBX;

        if (!com.hearthstead.client.debug.UiBenchCommand.panelBody) {
            return; // QA bisect: background only, nothing of this screen drawn
        }
        HsUi.window(g, left, top, panelW, l.totalHeight);

        // -- column A ------------------------------------------------------
        drawHeader(g, mouseX, mouseY, l);
        drawMayorBadge(g, a, l.mayorBadgeTop, mouseX, mouseY);

        HsUi.divider(g, a, l.dividerA, colW);
        drawNeeds(g, a, l.needsTop);

        HsUi.divider(g, a, l.dividerB, colW);
        drawAttributes(g, a, l.attributesTop, mouseX, mouseY);

        // -- column B ------------------------------------------------------
        // dividerC is -1 in two-column mode: a rule across the top of its own
        // column separates the frame from the first row, which is not a
        // semantic group boundary.
        if (l.dividerC >= 0) {
            HsUi.divider(g, b, l.dividerC, colW);
        }
        drawTraits(g, b, l.traitsTop, mouseX, mouseY);

        HsUi.divider(g, b, l.dividerPerson, colW);
        drawEmployment(g, b, l.employmentTop);
        drawRefusal(g, b, l.refusalTop);

        HsUi.divider(g, b, l.dividerBag, colW);
        drawBag(g, b, l.bagLabelTop, l.bagSlotsTop);

        HsUi.divider(g, b, l.dividerD, colW);

        HsUi.widgets(this, g, mouseX, mouseY, partialTick);

        if (pendingTooltip != null && !pendingTooltip.isEmpty()) {
            g.renderComponentTooltip(font, pendingTooltip, mouseX, mouseY);
        }
    }

    private void drawHeader(GuiGraphics g, int mouseX, int mouseY, Layout l) {
        int px = left + PAD;
        int py = l.nameTop;
        HsUi.inset(g, px, py, PORTRAIT_W, PORTRAIT_H);
        // The settler looks toward the mouse — the same lively touch vanilla
        // uses for the player preview in the inventory screen.
        if (com.hearthstead.client.debug.UiBenchCommand.entityPreview) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, px + 2, py + 2,
                px + PORTRAIT_W - 2, py + PORTRAIT_H - 2, 22, 0.0625F, mouseX, mouseY, settler);
        }

        // Identity block, in the order the citizen-card recipe asks for:
        // name, then the profession badge in trade colour, with the mayor
        // mark riding the same row as the name when it applies (see class
        // doc -- the badge below carries its own accent card already).
        int tx = left + HEADER_TEXT_X;
        boolean mayor = snapshot != null && snapshot.isMayor();
        int nameBox = headerTextW;
        if (mayor) {
            Component mark = Component.translatable("hearthstead.settler.mayor_mark");
            int markW = Math.min(font.width(mark) + 6, MAYOR_MARK_W);
            int markX = tx + headerTextW - markW;
            HsUi.badge(g, font, mark, markX, l.nameTop, markW, HsUiTokens.ACCENT & 0xFFFFFF);
            nameBox = headerTextW - markW - 4;
        }
        HsUi.labelIn(g, font, title, tx, l.nameTop, nameBox, HsUiTokens.TEXT_STRONG);

        Profession profession = settler.getProfession();
        Component job = profession.employed() ? profession.displayName()
            : Component.translatable("hearthstead.profession.none");
        int professionColor = 0xFF000000 | profession.color();
        HsUi.badge(g, font, job, tx, l.professionTop, headerTextW, professionColor);

        HsUi.labelIn(g, font, Component.translatable("hearthstead.gui.doing",
            settler.getActivity().displayName()), tx, l.activityTop, headerTextW,
            HsUiTokens.TEXT_MUTED);
    }

    private void drawMayorBadge(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        if (snapshot == null || !snapshot.isMayor()) {
            return; // the row is reserved (a fixed shape); simply left blank
        }
        HsUi.card(g, x, y, colW, MAYOR_BADGE_H, false);
        Component line = Component.translatable(snapshot.mayorSettling()
                ? "hearthstead.settler.mayor_settling" : "hearthstead.settler.mayor_badge",
            boonName());
        HsUi.labelIn(g, font, line, x + 4, y + 2, colW - 8, HsUiTokens.ACCENT);
        if (hover(mouseX, mouseY, x, y, colW, MAYOR_BADGE_H)) {
            pendingTooltip = List.of(boonDescription());
        }
    }

    private void drawNeeds(GuiGraphics g, int x, int y) {
        drawNeed(g, x, y, "hearthstead.gui.hunger", settler.getHunger());
        drawNeed(g, x, y + ROW, "hearthstead.gui.energy", settler.getEnergy());
        drawNeed(g, x, y + ROW * 2, "hearthstead.gui.morale", settler.getMorale());
    }

    private void drawNeed(GuiGraphics g, int x, int y, String labelKey, float value) {
        HsUi.labelIn(g, font, Component.translatable(labelKey), x, y, NEED_LABEL_W,
            HsUiTokens.TEXT);
        int barX = x + NEED_LABEL_W;
        int barW = colW - NEED_LABEL_W - NEED_PCT_W - GUTTER;
        float ratio = Mth.clamp(value, 0.0F, 100.0F) / 100.0F;
        HsUi.bar(g, barX, y, barW, NEED_BAR_H, ratio, HsUi.Tone.of(ratio));
        HsUi.right(g, font, Component.literal(String.valueOf((int) value)), x + colW, y,
            HsUiTokens.TEXT_MUTED);
    }

    private void drawAttributes(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        if (snapshot == null) {
            HsUi.labelIn(g, font, Component.translatable("hearthstead.settler.loading"),
                x, y + ROW * 2, colW, HsUiTokens.TEXT_MUTED);
            return;
        }
        int valueX = x + colW;
        int barX = valueX - ATTR_VALUE_W - GUTTER - ATTR_BAR_W;
        for (Attribute attribute : Attribute.ALL) {
            int rowY = y + attribute.ordinal() * ROW;
            boolean knack = attribute.ordinal() == snapshot.knackOrdinal();
            // The knack is marked by COLOUR, not by a suffix. "(naturlig
            // lag)" cost 70px of a 180px column to repeat what the accent
            // already says, and the tooltip names it in words for anyone the
            // colour does not reach -- colour is never the only channel.
            HsUi.labelIn(g, font, attribute.displayName(), x, rowY,
                barX - x - GUTTER, knack ? HsUiTokens.ACCENT : HsUiTokens.TEXT);

            int value = snapshot.attributeValues().get(attribute.ordinal());
            float ratio = Mth.clamp(value / 100.0F, 0.0F, 1.0F);
            // ACCENT, not Tone.of(ratio): an attribute is a MAGNITUDE that
            // grows with training, not a status that is good or bad right
            // now. Tone.of would paint every attribute of every newly
            // arrived settler in the crimson that elsewhere means "this is
            // going wrong" -- 8/100 strength is a beginner, not an alarm.
            // It is the same tone the pips this replaced always used.
            HsUi.bar(g, barX, rowY + 1, ATTR_BAR_W, ATTR_BAR_H, ratio, HsUi.Tone.ACCENT);
            // The owner asked for the number, in so many words: "25 / 100".
            // The bar stays because a bar is what a 0-100 continuous value
            // reads as at a glance; the number stays because an exact figure
            // is the thing you compare two settlers by. Right-aligned, so a
            // column of them lines up on the units digit.
            HsUi.right(g, font, attributeValue(attribute.ordinal(), value), valueX, rowY,
                knack ? HsUiTokens.ACCENT : HsUiTokens.TEXT);

            if (hover(mouseX, mouseY, x, rowY, colW, ROW)) {
                // Tiered: what the attribute governs (why it matters), then
                // what raises it (grey, secondary) -- the same "name, then
                // grey description" tooltip shape as the Hearth ledger. The
                // knack line is appended only for the one attribute that has
                // it, since that is the fact the accent colour is carrying.
                Component role =
                    Component.translatable("hearthstead.attribute." + attribute.key() + ".role");
                Component trained = attribute.trainedBy().copy().withStyle(ChatFormatting.GRAY);
                pendingTooltip = knack
                    ? List.of(role, trained,
                        Component.translatable("hearthstead.settler.attribute_knack_tip")
                            .withStyle(ChatFormatting.GRAY))
                    : List.of(role, trained);
            }
        }
    }

    /**
     * "25 / 100", cached per attribute.
     *
     * <p>An attribute moves when a settler trains, which is minutes apart;
     * building the same five strings sixty times a second to say so is the
     * per-frame garbage this pass exists to remove.
     */
    private Component attributeValue(int ordinal, int value) {
        if (attrValueCache[ordinal] == null || attrValueShown[ordinal] != value) {
            attrValueShown[ordinal] = value;
            attrValueCache[ordinal] = Component.literal(value + " / 100");
        }
        return attrValueCache[ordinal];
    }

    /**
     * One card per reserved trait slot (see the class doc for why the count
     * of slots never depends on how many traits this settler actually has).
     * Each card is a name row and one content row: the trait's wired
     * buff/malus chips when it has any, its existing flavour line when it
     * has none — see {@link #wiredEffects}.
     */
    private void drawTraits(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        if (snapshot == null) {
            return;
        }
        List<Integer> ordinals = snapshot.traitOrdinals();
        for (int slot = 0; slot < TRAIT_SLOTS; slot++) {
            int cardY = y + slot * (TRAIT_CARD_H + GUTTER);
            if (slot >= ordinals.size()) {
                continue; // reserved but blank -- the settler has only one trait
            }
            Trait trait = Trait.ALL[ordinals.get(slot)];
            boolean hovered = hover(mouseX, mouseY, x, cardY, colW, TRAIT_CARD_H);
            HsUi.card(g, x, cardY, colW, TRAIT_CARD_H, hovered);
            int tx = x + TRAIT_CARD_PAD;
            int limit = x + colW - TRAIT_CARD_PAD;
            HsUi.labelIn(g, font, trait.displayName(), tx, cardY + TRAIT_CARD_PAD,
                colW - 2 * TRAIT_CARD_PAD, HsUiTokens.TEXT_STRONG);
            int lineY = cardY + TRAIT_CARD_PAD + HsUiTokens.LINE_GAP;
            List<Effect> effects = wiredEffects(trait);
            if (effects.isEmpty()) {
                HsUi.labelIn(g, font, trait.describe(), tx, lineY,
                    colW - 2 * TRAIT_CARD_PAD, HsUiTokens.TEXT_MUTED);
            } else {
                drawEffectChips(g, effects, tx, lineY, limit);
            }
            if (hovered) {
                pendingTooltip = List.of(trait.displayName(),
                    trait.describe().copy().withStyle(ChatFormatting.GRAY));
            }
        }
    }

    /** One trait's chips, packed left to right; the same defensive
     *  cursor-and-limit break the old comma-separated trait line used, so an
     *  unexpectedly long translation stops cleanly instead of overrunning
     *  the card. */
    private void drawEffectChips(GuiGraphics g, List<Effect> effects, int x, int y, int limit) {
        int cursor = x;
        for (int i = 0; i < effects.size(); i++) {
            Effect effect = effects.get(i);
            int w = font.width(effect.text());
            if (cursor + w > limit) {
                break;
            }
            g.drawString(font, effect.text(), cursor, y, effect.tone().colour(), true);
            cursor += w;
            if (i < effects.size() - 1) {
                String sep = "   ";
                int sepW = font.width(sep);
                if (cursor + sepW > limit) {
                    break;
                }
                cursor += sepW;
            }
        }
    }

    /**
     * The buff/malus chips a trait actually delivers — see the class doc.
     * Reads straight off {@link Trait}'s own multiplier fields, but only the
     * four a gameplay system reads back ({@code growth}, {@code
     * moraleDecay}, {@code moraleGain}, {@code hunger}) plus the flat
     * {@code SLOW_START} penalty; the rest are declared on the enum but
     * consumed nowhere, so showing them here would be a claim this screen
     * cannot back.
     */
    private static List<Effect> wiredEffects(Trait trait) {
        List<Effect> out = new ArrayList<>(3);
        addPercent(out, trait.growth(), true, "hearthstead.trait.effect.growth");
        addPercent(out, trait.moraleDecay(), false, "hearthstead.trait.effect.morale_decay");
        addPercent(out, trait.moraleGain(), true, "hearthstead.trait.effect.morale_gain");
        addPercent(out, trait.hunger(), false, "hearthstead.trait.effect.hunger");
        if (trait.has(Trait.Flag.SLOW_START)) {
            out.add(new Effect(Component.translatable("hearthstead.trait.effect.slow_start"),
                HsUi.Tone.WARN));
        }
        return out;
    }

    /**
     * @param higherIsBetter whether a ratio above 1.0 is the buff (growth,
     *                       moraleGain) or the malus (moraleDecay, hunger,
     *                       where LESS is the good outcome)
     */
    private static void addPercent(List<Effect> out, float ratio, boolean higherIsBetter,
                                   String key) {
        int pct = Math.round((ratio - 1.0F) * 100.0F);
        if (pct == 0) {
            return;
        }
        boolean good = higherIsBetter == (pct > 0);
        String signed = (pct > 0 ? "+" : "") + pct;
        out.add(new Effect(Component.translatable(key, signed),
            good ? HsUi.Tone.GOOD : HsUi.Tone.WARN));
    }

    /** One trait's plain-language, tone-coloured buff or malus line. */
    private record Effect(Component text, HsUi.Tone tone) {
    }

    private void drawEmployment(GuiGraphics g, int x, int y) {
        if (snapshot == null) {
            return;
        }
        Component line;
        if (snapshot.employerBuildingId().isEmpty()) {
            line = Component.translatable("hearthstead.employ.unemployed");
        } else if (settler.getProfession() == Profession.GUARD) {
            line = Component.translatable("hearthstead.settler.employed_watch", buildingName(),
                Component.translatable(snapshot.guardWatchNight()
                    ? "hearthstead.settler.watch_night" : "hearthstead.settler.watch_day"));
        } else {
            line = Component.translatable("hearthstead.settler.employed_at", buildingName());
        }
        HsUi.labelIn(g, font, line, x, y, colW, HsUiTokens.TEXT);
    }

    /**
     * Word-wrapped rather than {@link HsUi#labelIn} — a refusal reason is a
     * full sentence a player needs to actually read (why did nothing happen
     * when I clicked?), and the longest ones measured up to 57px over a
     * single-line box in English, 35px over in Norwegian. Truncating a
     * reason with an ellipsis can read as a different, shorter explanation,
     * which is exactly the kind of misleading cut {@code labelIn} exists to
     * avoid causing — so this row wraps instead. Two lines covers every
     * refusal string in both languages with room to spare (see
     * {@link #layout}, which reserves the space unconditionally).
     */
    private void drawRefusal(GuiGraphics g, int x, int y) {
        if (snapshot == null) {
            return;
        }
        snapshot.refusal().ifPresent(refusal ->
            g.drawWordWrap(font, refusal, x, y, colW, HsUiTokens.WARN));
    }

    /**
     * The settler's bag: what they are actually carrying, as real ghost
     * slots (a slot background, the item, and its vanilla count overlay) —
     * read-only, nothing here is clickable or moves an item. Chest truth:
     * every slot here is a real {@code ItemStack} in {@code SettlerEntity}'s
     * bag container, not a display fiction, so this can never disagree with
     * what a hearth deposit actually collects.
     */
    private void drawBag(GuiGraphics g, int x, int labelY, int slotsY) {
        HsUi.labelIn(g, font, Component.translatable("hearthstead.settler.bag"), x, labelY,
            colW, HsUiTokens.TEXT);
        if (snapshot == null) {
            return;
        }
        for (int i = 0; i < BAG_SLOTS; i++) {
            int slotX = x + i * BAG_SLOT_STEP;
            HsUi.slot(g, slotX, slotsY);
            ItemStack stack = bagStacks[i];
            if (stack == null || stack.isEmpty()) {
                continue; // an empty slot: the slot sprite alone says so
            }
            g.renderItem(stack, slotX + 1, slotsY + 1);
            g.renderItemDecorations(font, stack, slotX + 1, slotsY + 1);
        }
    }

    // -------------------------------------------------------------- helpers --

    /** Both call sites already guard {@code snapshot != null} before reaching here. */
    private Component boonName() {
        return Component.translatable("hearthstead.mayor.boon." + snapshot.boonKey());
    }

    private Component boonDescription() {
        return Component.translatable("hearthstead.mayor.boon." + snapshot.boonKey() + ".desc");
    }

    private Component buildingName() {
        if (snapshot == null || snapshot.employerBuildingId().isEmpty()) {
            return Component.translatable("hearthstead.employ.unemployed");
        }
        BuildingType type = BuildingType.byId(snapshot.employerBuildingId());
        return type == null ? Component.literal(snapshot.employerBuildingId())
            : type.displayName();
    }

    private static boolean hover(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (settler.isRemoved() || minecraft.player.distanceToSqr(settler) > 64) {
            onClose();
        }
    }

    // --------------------------------------------------------------- layout --

    /**
     * Row positions for a fixed panel shape. Deliberately unconditional (it
     * does not look at {@link #snapshot}) so the panel's height is decided
     * once, in {@link #init}, and never changes again for the life of the
     * screen — see the class doc.
     */
    private Layout layout(int originY) {
        Layout l = new Layout();
        l.colAX = PAD;
        l.colBX = wide ? PAD + colW + PAD : PAD;

        int y = originY + PAD;

        // -- column A: who this person IS ----------------------------------
        l.nameTop = y;
        l.professionTop = y + ROW;
        l.activityTop = y + ROW * 2;
        y += HEADER_H + GUTTER;

        l.mayorBadgeTop = y;
        y += MAYOR_BADGE_H + GUTTER;

        l.dividerA = y;
        y += HsUiTokens.DIVIDER_H + GUTTER;

        l.needsTop = y;
        y += ROW * 3 + GUTTER;

        l.dividerB = y;
        y += HsUiTokens.DIVIDER_H + GUTTER;

        l.attributesTop = y;
        y += ROW * Attribute.COUNT + GUTTER;

        int columnABottom = y;

        // -- column B: what they DO ----------------------------------------
        // Beside column A when there is width for it, underneath it when
        // there is not. The leading divider only earns its place in the
        // stacked case: at the top of its own column it would be a rule
        // separating the panel's edge from its first row.
        if (wide) {
            y = originY + PAD;
            l.dividerC = -1;
        } else {
            l.dividerC = y;
            y += HsUiTokens.DIVIDER_H + GUTTER;
        }

        l.traitsTop = y;
        y += TRAIT_SLOTS * (TRAIT_CARD_H + GUTTER);

        // A second divider between "who they are" (traits) and "what they
        // do" (employment, refusal) -- both are real semantic groups, and
        // the panel is tall enough now that the extra rule earns its place
        // rather than crowding the one above it.
        l.dividerPerson = y;
        y += HsUiTokens.DIVIDER_H + GUTTER;

        l.employmentTop = y;
        y += ROW + GUTTER;
        l.refusalTop = y;
        // Two rows: the longest refusal sentences wrap to two lines (see
        // drawRefusal). Reserved unconditionally, same fixed-shape discipline
        // as the mayor badge above.
        y += ROW * 2 + GUTTER;

        // A third: "what they carry" is its own group too, same reasoning.
        l.dividerBag = y;
        y += HsUiTokens.DIVIDER_H + GUTTER;

        l.bagLabelTop = y;
        y += ROW;
        l.bagSlotsTop = y;
        // Reserved unconditionally at BAG_SLOTS wide, same fixed-shape
        // discipline as everything else in this layout -- an empty bag is
        // still BAG_SLOTS empty slots, never a shorter row.
        y += HsUiTokens.SLOT + GUTTER;

        l.dividerD = y;
        y += HsUiTokens.DIVIDER_H + GUTTER;

        l.appointTop = y;
        y += HsUiTokens.BUTTON_H + GUTTER;

        l.footerTop = y;
        y += HsUiTokens.BUTTON_H;

        l.totalHeight = Math.max(y, columnABottom) - originY + PAD;
        return l;
    }

    /** Plain data holder; see {@link #layout}. */
    private static final class Layout {
        /** Left column x, and the second column's x (equal when stacked). */
        int colAX;
        int colBX;
        int nameTop;
        int professionTop;
        int activityTop;
        int mayorBadgeTop;
        int dividerA;
        int needsTop;
        int dividerB;
        int attributesTop;
        int dividerC;
        int traitsTop;
        int dividerPerson;
        int employmentTop;
        int refusalTop;
        int dividerBag;
        int bagLabelTop;
        int bagSlotsTop;
        int dividerD;
        int appointTop;
        int footerTop;
        int totalHeight;
    }
}
