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
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.neoforged.neoforge.network.PacketDistributor;

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
 * <h2>A fixed shape, so the panel never resizes under the mouse</h2>
 *
 * <p>The mayor badge and the refusal banner are optional content, but
 * {@link #layout} reserves their rows unconditionally — a settler who is not
 * mayor simply leaves that row blank rather than the whole panel growing and
 * shrinking as actions are taken. A window that resizes while you are using
 * it reads as broken; a little unused space when a row is absent does not.
 */
public class SettlerScreen extends Screen {

    // -- geometry: vanilla metrics (20px buttons, 4px grid), see the
    //    minecraft-ui skill. Text boxes are generous and rely on
    //    HsUi.labelIn's ellipsis as the safety net for long translations. --
    // 224 clipped the mayor badge's "settling in" sentence -- "Ordfører —
    // Nøysomt arbeid (setter seg inn)" measured 224px against its 200px box
    // (CONTENT_W - 8), 24px over. 256 carries that box to 232px, clearing it
    // (and the English worst case, 175px) with margin; every other box on
    // this panel derives from PANEL_W/CONTENT_W and only gains room.
    private static final int PANEL_W = 256;
    private static final int PAD = HsUiTokens.PAD;
    private static final int GUTTER = HsUiTokens.GUTTER;
    private static final int CONTENT_W = PANEL_W - 2 * PAD;
    /** One compact text row: glyph height plus a hair of breathing room. */
    private static final int ROW = 12;

    private static final int PORTRAIT_W = 50;
    private static final int PORTRAIT_H = 56;
    private static final int HEADER_H = PORTRAIT_H;
    private static final int HEADER_TEXT_X = PAD + PORTRAIT_W + 8;
    private static final int HEADER_TEXT_W = PANEL_W - HEADER_TEXT_X - PAD;

    // Measured against the widest attribute name plus the knack suffix in
    // both languages ("Utholdenhet (naturlig lag)", 129px) -- 128 clipped it
    // by a single pixel. 140 leaves a few px of margin and still sits clear
    // of the pips column that starts at ATTR_LABEL_W + 4.
    private static final int ATTR_LABEL_W = 140;
    private static final int NEED_LABEL_W = 44;
    private static final int NEED_PCT_W = 26;
    private static final int NEED_BAR_H = 6;
    private static final int MAYOR_BADGE_H = ROW + 2;

    private static final int BTN_W = 64;

    protected final SettlerEntity settler;
    private SettlerSnapshotPayload snapshot;
    private int left;
    private int top;
    /** Set while drawing a hovered non-widget region; rendered once, last. */
    private Component pendingTooltip;

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
            rebuild();
        }
    }

    @Override
    protected void init() {
        left = (width - PANEL_W) / 2;
        top = (height - layout(0).totalHeight) / 2;
        rebuild();
    }

    // ------------------------------------------------------------ widgets ---

    private void rebuild() {
        clearWidgets();
        Layout l = layout(top);

        boolean employed = settler.getProfession().employed();
        if (employed) {
            HsButton dismiss = HsButton.danger(left + PAD, l.footerTop, BTN_W,
                HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.employ.dismiss"),
                () -> act(SettlerActionPayload.Kind.DISMISS));
            dismiss.active = snapshot != null && snapshot.canManage();
            dismiss.setTooltip(Tooltip.create(Component.translatable(
                "hearthstead.settler.dismiss.tip", title, buildingName())));
            addRenderableWidget(dismiss);
        }
        addRenderableWidget(HsButton.normal(left + PANEL_W - PAD - BTN_W, l.footerTop, BTN_W,
            HsUiTokens.BUTTON_H, Component.translatable("hearthstead.settler.close"),
            this::onClose));

        HsButton appoint = HsButton.normal(left + PAD, l.appointTop, CONTENT_W,
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

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        pendingTooltip = null;
        Layout l = layout(top);

        HsUi.window(g, left, top, PANEL_W, l.totalHeight);
        drawHeader(g, mouseX, mouseY, l);
        drawMayorBadge(g, left + PAD, l.mayorBadgeTop, mouseX, mouseY);

        HsUi.divider(g, left + PAD, l.dividerA, CONTENT_W);
        drawNeeds(g, left + PAD, l.needsTop);

        HsUi.divider(g, left + PAD, l.dividerB, CONTENT_W);
        drawAttributes(g, left + PAD, l.attributesTop, mouseX, mouseY);

        HsUi.divider(g, left + PAD, l.dividerC, CONTENT_W);
        drawTraits(g, left + PAD, l.traitsTop, mouseX, mouseY);
        drawEmployment(g, left + PAD, l.employmentTop);
        drawRefusal(g, left + PAD, l.refusalTop);

        HsUi.divider(g, left + PAD, l.dividerD, CONTENT_W);

        super.render(g, mouseX, mouseY, partialTick);

        if (pendingTooltip != null) {
            g.renderTooltip(font, pendingTooltip, mouseX, mouseY);
        }
    }

    private void drawHeader(GuiGraphics g, int mouseX, int mouseY, Layout l) {
        int px = left + PAD;
        int py = l.nameTop;
        HsUi.inset(g, px, py, PORTRAIT_W, PORTRAIT_H);
        // The settler looks toward the mouse — the same lively touch vanilla
        // uses for the player preview in the inventory screen.
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, px + 2, py + 2,
            px + PORTRAIT_W - 2, py + PORTRAIT_H - 2, 22, 0.0625F, mouseX, mouseY, settler);

        int tx = left + HEADER_TEXT_X;
        HsUi.labelIn(g, font, title, tx, l.nameTop, HEADER_TEXT_W, HsUiTokens.TEXT_STRONG);

        Profession profession = settler.getProfession();
        int professionColor = 0xFF000000 | profession.color();
        // A small swatch beside the word carries the same colour a working
        // settler's outfit does, so the two read as the same fact.
        g.fill(tx, l.professionTop + 1, tx + 6, l.professionTop + 7, professionColor);
        Component job = profession.employed() ? profession.displayName()
            : Component.translatable("hearthstead.profession.none");
        HsUi.labelIn(g, font, job, tx + 9, l.professionTop, HEADER_TEXT_W - 9, professionColor);

        HsUi.labelIn(g, font, Component.translatable("hearthstead.gui.doing",
            settler.getActivity().displayName()), tx, l.activityTop, HEADER_TEXT_W,
            HsUiTokens.TEXT_MUTED);
    }

    private void drawMayorBadge(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        if (snapshot == null || !snapshot.isMayor()) {
            return; // the row is reserved (a fixed shape); simply left blank
        }
        HsUi.card(g, x, y, CONTENT_W, MAYOR_BADGE_H, false);
        Component line = Component.translatable(snapshot.mayorSettling()
                ? "hearthstead.settler.mayor_settling" : "hearthstead.settler.mayor_badge",
            boonName());
        HsUi.labelIn(g, font, line, x + 4, y + 2, CONTENT_W - 8, HsUiTokens.ACCENT);
        if (hover(mouseX, mouseY, x, y, CONTENT_W, MAYOR_BADGE_H)) {
            pendingTooltip = boonDescription();
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
        int barW = CONTENT_W - NEED_LABEL_W - NEED_PCT_W - GUTTER;
        float ratio = Mth.clamp(value, 0.0F, 100.0F) / 100.0F;
        HsUi.bar(g, barX, y, barW, NEED_BAR_H, ratio, HsUi.Tone.of(ratio));
        HsUi.right(g, font, Component.literal(String.valueOf((int) value)), x + CONTENT_W, y,
            HsUiTokens.TEXT_MUTED);
    }

    private void drawAttributes(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        if (snapshot == null) {
            HsUi.labelIn(g, font, Component.translatable("hearthstead.settler.loading"),
                x, y + ROW * 2, CONTENT_W, HsUiTokens.TEXT_MUTED);
            return;
        }
        for (Attribute attribute : Attribute.ALL) {
            int rowY = y + attribute.ordinal() * ROW;
            boolean knack = attribute.ordinal() == snapshot.knackOrdinal();
            Component label = knack
                ? Component.translatable("hearthstead.settler.attribute_knack",
                    attribute.displayName())
                : attribute.displayName();
            HsUi.labelIn(g, font, label, x, rowY, ATTR_LABEL_W,
                knack ? HsUiTokens.ACCENT : HsUiTokens.TEXT);
            // Mirrors SettlerAttributes#pips exactly -- the client only has
            // the raw 0..100 value, never the object that method lives on.
            int value = snapshot.attributeValues().get(attribute.ordinal());
            int pips = Mth.clamp(Math.round(value / 20.0F), 0, 5);
            HsUi.pips(g, x + ATTR_LABEL_W + 4, rowY + 1, pips, 5, HsUi.Tone.ACCENT);
            if (hover(mouseX, mouseY, x, rowY, CONTENT_W, ROW)) {
                pendingTooltip = attribute.trainedBy();
            }
        }
    }

    private void drawTraits(GuiGraphics g, int x, int y, int mouseX, int mouseY) {
        if (snapshot == null) {
            return;
        }
        List<Integer> ordinals = snapshot.traitOrdinals();
        int cursor = x;
        int limit = x + CONTENT_W;
        for (int i = 0; i < ordinals.size(); i++) {
            Trait trait = Trait.ALL[ordinals.get(i)];
            Component label = trait.displayName();
            int w = font.width(label);
            if (cursor + w > limit) {
                break; // extremely long trait names in some future locale: stop rather than overrun
            }
            boolean hovered = hover(mouseX, mouseY, cursor, y, w, ROW);
            g.drawString(font, label, cursor, y, hovered ? HsUiTokens.TEXT_STRONG : HsUiTokens.TEXT,
                true);
            if (hovered) {
                pendingTooltip = trait.describe();
            }
            cursor += w;
            if (i < ordinals.size() - 1) {
                String separator = ", ";
                g.drawString(font, separator, cursor, y, HsUiTokens.TEXT_MUTED, true);
                cursor += font.width(separator);
            }
        }
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
        HsUi.labelIn(g, font, line, x, y, CONTENT_W, HsUiTokens.TEXT);
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
            g.drawWordWrap(font, refusal, x, y, CONTENT_W, HsUiTokens.WARN));
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
    private static Layout layout(int originY) {
        Layout l = new Layout();
        int y = originY + PAD;

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

        l.dividerC = y;
        y += HsUiTokens.DIVIDER_H + GUTTER;

        l.traitsTop = y;
        y += ROW;
        l.employmentTop = y;
        y += ROW + GUTTER;
        l.refusalTop = y;
        // Two rows: the longest refusal sentences wrap to two lines (see
        // drawRefusal). Reserved unconditionally, same fixed-shape discipline
        // as the mayor badge above.
        y += ROW * 2 + GUTTER;

        l.dividerD = y;
        y += HsUiTokens.DIVIDER_H + GUTTER;

        l.appointTop = y;
        y += HsUiTokens.BUTTON_H + GUTTER;

        l.footerTop = y;
        y += HsUiTokens.BUTTON_H;

        l.totalHeight = y - originY + PAD;
        return l;
    }

    /** Plain data holder; see {@link #layout}. */
    private static final class Layout {
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
        int employmentTop;
        int refusalTop;
        int dividerD;
        int appointTop;
        int footerTop;
        int totalHeight;
    }
}
