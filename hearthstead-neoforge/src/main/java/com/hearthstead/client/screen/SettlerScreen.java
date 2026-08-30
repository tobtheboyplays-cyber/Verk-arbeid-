package com.hearthstead.client.screen;

import com.hearthstead.building.BuildingType;
import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui.HsUiTokens;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.ArcherRank;
import com.hearthstead.entity.GuardExperience;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.JobEffects;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.Trait;
import com.hearthstead.network.SettlerActionPayload;
import com.hearthstead.network.SettlerSnapshotPayload;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.state.BlessingId;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
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
 * <h2>A compact shape derived from the authoritative snapshot</h2>
 *
 * <p>The mayor badge, combat progress, refusal banner and second trait are
 * conditional rows. {@link #layout} includes only content present in the
 * latest server snapshot, then keeps that geometry stable until a new
 * snapshot arrives. This removes the large dead areas civilians used to show
 * without letting ordinary scrolling resize controls under the pointer.
 */
public class SettlerScreen extends Screen implements QaUiInspectable {

    // -- geometry: vanilla metrics (20px buttons, 4px grid), see the
    //    minecraft-ui skill. Text boxes are generous and rely on
    //    HsUi.labelIn's ellipsis as the safety net for long translations. --
    // 224 clipped the mayor badge's "settling in" sentence -- "Ordfører —
    // Nøysomt arbeid (setter seg inn)" measured 224px against its 200px box
    // (CONTENT_W - 8), 24px over. 256 carries that box to 232px, clearing it
    // (and the English worst case, 175px) with margin; every other box on
    // this panel derives from PANEL_W/CONTENT_W and only gains room.
    // The overview is deliberately wide and shallow: the player compares a
    // person, not a document. 336x340 is the reviewed compact target and fits
    // a 1920x1080 client at GUI scale 3 without vertical scrolling.
    private static final int PANEL_W = 336;
    private static final BlessingId[] BLESSING_IDS = BlessingId.values();
    private static final int PAD = HsUiTokens.PAD;
    private static final int GUTTER = HsUiTokens.GUTTER;
    private static final int CONTENT_W = PANEL_W - 2 * PAD;
    /** One compact text row: glyph height plus a hair of breathing room. */
    private static final int ROW = 12;

    private static final int PORTRAIT_W = 44;
    private static final int PORTRAIT_H = 48;
    private static final int HEADER_H = PORTRAIT_H;
    private static final int HEADER_TEXT_X = PAD + PORTRAIT_W + 8;
    private static final int HEADER_TEXT_W = PANEL_W - HEADER_TEXT_X - PAD;
    /** The mayor mark's own cap -- both languages' "Mayor"/"Ordfører" clear it. */
    private static final int MAYOR_MARK_W = 40;

    // Eight attributes are a stable 2x4 first-page grid. The number remains
    // the primary signal; the tiny bar and tone only reinforce it. Keeping
    // the geometry fixed prevents a new attribute or a translated name from
    // turning the Overview into an eight-row scrolling wall.
    private static final int ATTRIBUTE_COLUMNS = 2;
    private static final int ATTRIBUTE_ROWS = 4;
    private static final int ATTRIBUTE_CELL_H = 22;
    private static final int ATTRIBUTE_GAP = 2;
    private static final int ATTRIBUTE_COL_W = (CONTENT_W - GUTTER) / 2;
    private static final int ATTRIBUTE_VALUE_W = 48;
    private static final int NEED_LABEL_W = 44;
    private static final int NEED_PCT_W = 26;
    private static final int NEED_BAR_H = 6;
    private static final int CURRENT_REQUEST_CARD_H = 34;
    private static final int SUMMARY_COL_W = (CONTENT_W - GUTTER) / 2;
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
    //    slot HsUi and StorageScreen already use. Reserved even when empty so
    //    an empty bag is a row of empty
    //    slots under its own label rather than a hole in the panel.
    private static final int BAG_SLOTS = SettlerEntity.BAG_SIZE;
    private static final int BAG_SLOT_STEP = HsUiTokens.SLOT + 2;

    private static final int BTN_W = 64;
    private static final int REQUEST_BTN_W = 96;
    private static final int CONTROL_BTN_W = (CONTENT_W - GUTTER * 3) / 4;

    protected final SettlerEntity settler;
    private SettlerSnapshotPayload snapshot;
    /**
     * Render-only data whose inputs are stable between network updates. The
     * model uses layout offsets relative to {@link #top}, so scrolling never
     * rebuilds it.
     */
    private CachedView cachedView;
    private SettlerSnapshotPayload cachedViewSnapshot;
    private int cachedViewWidth = -1;
    private int cachedViewHeight = -1;
    private int cachedViewCombatExperience = -1;
    private String cachedViewLanguage = "";
    /** Work Pace changes slowly; avoid allocating a new percent label per frame. */
    private int cachedWorkPacePercent = -1;
    private Component cachedWorkPaceValue = Component.empty();
    /** Rebuilt with the cached view when snapshot, size or locale changes. */
    private Component blessingStatusLine = Component.empty();
    private boolean hasBlessings;
    private int left;
    private int top;
    // -- scroll: only load-bearing when the content-shaped panel does not fit
    //    the current viewport (guiScale 3 or 4 on a modest window; see
    //    init()). At guiScale 1-2 maxScroll is 0 and every field below is
    //    inert, top staying the plain centred value it always was. --
    private int contentHeight;
    private int baseTop;
    private int scrollOffset;
    private int maxScroll;
    /** Set while drawing a hovered non-widget region; rendered once, last. */
    private List<Component> pendingTooltip;
    /** Suppresses CLOSE while this exact sheet temporarily opens a child tab. */
    private boolean openingChild;
    private boolean uiSoundActive;
    private HsButton dismissButton;
    private HsButton closeButton;
    private HsButton requestListButton;
    private HsButton guardOrderButton;
    private HsButton appointButton;
    private HsButton inventoryButton;
    private HsButton editWorkZoneButton;
    private HsButton workplaceButton;
    private HsButton locateButton;
    /** True only between an Overview click and its server-authored zone reply. */
    private boolean workZoneRequestPending;

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
        if (!acceptsSnapshot(fresh)) {
            return;
        }
        if (fresh.refusal().isPresent()) {
            // A rejected child request leaves this Overview in place. Reset
            // even when the same refusal is repeated and the render snapshot
            // is byte-for-byte unchanged.
            openingChild = false;
            workZoneRequestPending = false;
        }
        if (fresh.equals(snapshot)) {
            return;
        }
        this.snapshot = fresh;
        invalidateView();
        // A Courier request list keeps this sheet as its live parent.
        // Cache server updates while hidden; init() rebuilds on return.
        if (minecraft != null && minecraft.screen == this) {
            rebuild();
        }
    }

    /** Exact-target guard used by update-only network deliveries. */
    public boolean acceptsSnapshot(SettlerSnapshotPayload fresh) {
        if (fresh == null || settler.getId() != fresh.entityId()
            || !settler.getUUID().equals(fresh.settlerId())) {
            return false;
        }
        if (snapshot == null) {
            return fresh.delivery() == SettlerSnapshotPayload.Delivery.OPEN;
        }
        return snapshot.sessionId().equals(fresh.sessionId())
            && snapshot.settlerId().equals(fresh.settlerId());
    }

    /** Tell the server this sheet is no longer a legitimate refresh viewer. */
    @Override
    public void removed() {
        if (!openingChild && snapshot != null && minecraft != null
            && minecraft.getConnection() != null) {
            PacketDistributor.sendToServer(new SettlerActionPayload(
                snapshot.entityId(), snapshot.settlerId(), snapshot.sessionId(),
                SettlerActionPayload.Kind.CLOSE, snapshot.revision()));
        }
        if (uiSoundActive) {
            uiSoundActive = false;
            HsUi.playCloseSound();
        }
        super.removed();
    }

    @Override
    protected void init() {
        openingChild = false;
        left = (width - PANEL_W) / 2;
        rebuild();
        if (!uiSoundActive) {
            uiSoundActive = true;
            HsUi.playOpenSound();
        }
    }

    /**
     * Only reachable once the panel has overflowed the viewport (see
     * {@link #init} -- a scrollbar that cannot move is not a feature, the
     * same guard {@code ResearchScreen} and {@code HandbookScreen} apply to
     * their own lists). Scrolling only translates the existing footer
     * buttons' hitboxes; it does not clear or allocate the widget tree.
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (maxScroll > 0) {
            int before = scrollOffset;
            scrollOffset = Mth.clamp(scrollOffset - (int) Math.signum(scrollY) * ROW, 0, maxScroll);
            if (before != scrollOffset) {
                QaClientObserver.markUiTransition("settler_scroll");
                top = baseTop - scrollOffset;
                relayoutWidgets();
                return true;
            }
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ------------------------------------------------------------ widgets ---

    private void rebuild() {
        clearWidgets();
        CachedView view = view();
        Layout l = view.layout;
        contentHeight = l.totalHeight;
        maxScroll = Math.max(0, contentHeight - height + PAD * 2);
        scrollOffset = Mth.clamp(scrollOffset, 0, maxScroll);
        baseTop = maxScroll == 0 ? (height - contentHeight) / 2 : PAD;
        top = baseTop - scrollOffset;
        dismissButton = null;
        closeButton = null;
        requestListButton = null;
        guardOrderButton = null;
        appointButton = null;
        inventoryButton = null;
        editWorkZoneButton = null;
        workplaceButton = null;
        locateButton = null;

        inventoryButton = HsButton.normal(left + PAD, top + l.controlsTop,
            CONTROL_BTN_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.settler.control.inventory"),
            () -> requestChild(SettlerActionPayload.Kind.OPEN_INVENTORY));
        inventoryButton.active = snapshot != null && snapshot.canManage();
        inventoryButton.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.settler.control.inventory.tip")));
        addRenderableWidget(inventoryButton);

        locateButton = HsButton.normal(left + PAD + (CONTROL_BTN_W + GUTTER),
            top + l.controlsTop, CONTROL_BTN_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.settler.control.locate", title),
            () -> act(SettlerActionPayload.Kind.LOCATE));
        locateButton.active = snapshot != null;
        locateButton.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.settler.control.locate.tip")));
        addRenderableWidget(locateButton);

        workplaceButton = HsButton.normal(left + PAD + 2 * (CONTROL_BTN_W + GUTTER),
            top + l.controlsTop, CONTROL_BTN_W,
            HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.settler.control.workplace"),
            () -> requestChild(SettlerActionPayload.Kind.OPEN_WORKPLACE));
        workplaceButton.active = snapshot != null && snapshot.canManage()
            && !snapshot.employerBuildingId().isEmpty();
        workplaceButton.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.settler.control.workplace.tip")));
        addRenderableWidget(workplaceButton);

        editWorkZoneButton = HsButton.normal(
            left + PAD + 3 * (CONTROL_BTN_W + GUTTER),
            top + l.controlsTop,
            CONTROL_BTN_W, HsUiTokens.BUTTON_H,
            Component.translatable("hearthstead.settler.control.work_zone"),
            this::requestWorkZone);
        editWorkZoneButton.active = snapshot != null && snapshot.canManage()
            && (settler.getProfession() == Profession.FARMER
                || settler.getProfession() == Profession.LUMBERER);
        editWorkZoneButton.setTooltip(Tooltip.create(Component.translatable(
            "hearthstead.settler.control.work_zone.tip")));
        addRenderableWidget(editWorkZoneButton);

        boolean employed = settler.getProfession().employed();
        if (employed) {
            dismissButton = HsButton.danger(left + PAD, top + l.footerTop, BTN_W,
                HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.employ.dismiss"),
                () -> act(SettlerActionPayload.Kind.DISMISS));
            dismissButton.active = snapshot != null && snapshot.canManage();
            dismissButton.setTooltip(Tooltip.create(view.dismissTooltip));
            addRenderableWidget(dismissButton);
        }
        closeButton = HsButton.normal(left + PANEL_W - PAD - BTN_W,
            top + l.footerTop, BTN_W,
            HsUiTokens.BUTTON_H, Component.translatable("hearthstead.settler.close"),
            this::onClose);
        addRenderableWidget(closeButton);
        if (settler.getProfession() == Profession.COURIER) {
            requestListButton = HsButton.normal(
                left + (PANEL_W - REQUEST_BTN_W) / 2,
                top + l.footerTop, REQUEST_BTN_W, HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.equipment.requests"),
                this::openRequestList);
            requestListButton.active = snapshot != null;
            addRenderableWidget(requestListButton);
        }
        if (settler.getProfession().martial()) {
            guardOrderButton = HsButton.normal(
                left + (PANEL_W - REQUEST_BTN_W) / 2,
                top + l.footerTop, REQUEST_BTN_W, HsUiTokens.BUTTON_H,
                Component.translatable("hearthstead.guard.command.open"),
                this::openGuardOrders);
            guardOrderButton.active = snapshot != null && snapshot.canManage();
            guardOrderButton.setTooltip(Tooltip.create(Component.translatable(
                "hearthstead.guard.command.open_tip")));
            addRenderableWidget(guardOrderButton);
        }

        appointButton = HsButton.normal(left + PAD, top + l.appointTop, CONTENT_W,
            HsUiTokens.BUTTON_H, Component.translatable("hearthstead.settler.appoint"),
            () -> act(SettlerActionPayload.Kind.APPOINT));
        appointButton.active = appointEnabled();
        appointButton.setTooltip(Tooltip.create(view.appointTooltip));
        addRenderableWidget(appointButton);
    }

    /** Scrolling changes geometry only; it never reallocates the widget tree. */
    private void relayoutWidgets() {
        Layout l = view().layout;
        if (dismissButton != null) {
            dismissButton.setX(left + PAD);
            dismissButton.setY(top + l.footerTop);
        }
        if (closeButton != null) {
            closeButton.setX(left + PANEL_W - PAD - BTN_W);
            closeButton.setY(top + l.footerTop);
        }
        if (requestListButton != null) {
            requestListButton.setX(left + (PANEL_W - REQUEST_BTN_W) / 2);
            requestListButton.setY(top + l.footerTop);
        }
        if (guardOrderButton != null) {
            guardOrderButton.setX(left + (PANEL_W - REQUEST_BTN_W) / 2);
            guardOrderButton.setY(top + l.footerTop);
        }
        if (appointButton != null) {
            appointButton.setX(left + PAD);
            appointButton.setY(top + l.appointTop);
        }
        if (inventoryButton != null) {
            inventoryButton.setX(left + PAD);
            inventoryButton.setY(top + l.controlsTop);
        }
        if (locateButton != null) {
            locateButton.setX(left + PAD + CONTROL_BTN_W + GUTTER);
            locateButton.setY(top + l.controlsTop);
        }
        if (workplaceButton != null) {
            workplaceButton.setX(left + PAD + 2 * (CONTROL_BTN_W + GUTTER));
            workplaceButton.setY(top + l.controlsTop);
        }
        if (editWorkZoneButton != null) {
            editWorkZoneButton.setX(left + PAD + 3 * (CONTROL_BTN_W + GUTTER));
            editWorkZoneButton.setY(top + l.controlsTop);
        }
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
                new SettlerActionPayload(snapshot.entityId(), snapshot.settlerId(),
                    snapshot.sessionId(), kind, snapshot.revision()));
        }
    }

    // ------------------------------------------------------------- drawing ---

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        pendingTooltip = null;
        CachedView view = view();
        Layout l = view.layout;

        HsUi.window(g, left, top, PANEL_W, l.totalHeight);
        drawHeader(g, mouseX, mouseY, l, view);
        HsUi.divider(g, left + PAD, top + l.dividerA, CONTENT_W);
        drawNeeds(g, left + PAD, top + l.needsTop, SUMMARY_COL_W, view);
        int requestX = left + PAD + SUMMARY_COL_W + GUTTER;
        drawCurrentRequest(g, requestX, top + l.currentRequestLabelTop,
            top + l.currentRequestCardTop, SUMMARY_COL_W,
            mouseX, mouseY, view);

        HsUi.divider(g, left + PAD, top + l.dividerB, CONTENT_W);
        drawAttributes(g, left + PAD, top + l.attributesTop, mouseX, mouseY, view);

        HsUi.divider(g, left + PAD, top + l.dividerC, CONTENT_W);
        drawTraits(g, left + PAD, top + l.traitsTop, mouseX, mouseY, view);

        HsUi.divider(g, left + PAD, top + l.dividerPerson, CONTENT_W);
        drawEmployment(g, left + PAD, top + l.employmentTop, view);
        drawCombatProgress(g, left + PAD, top + l.combatProgressTop,
            mouseX, mouseY, view);
        drawBlessings(g, left + PAD, top + l.blessingsTop);
        drawRefusal(g, left + PAD, top + l.refusalTop, view);

        HsUi.divider(g, left + PAD, top + l.dividerD, CONTENT_W);

        HsUi.widgets(this, g, mouseX, mouseY, partialTick);

        if (pendingTooltip != null && !pendingTooltip.isEmpty()) {
            g.renderComponentTooltip(font, pendingTooltip, mouseX, mouseY);
        }
    }

    private void drawHeader(GuiGraphics g, int mouseX, int mouseY, Layout l,
                            CachedView view) {
        int px = left + PAD;
        int py = top + l.nameTop;
        HsUi.inset(g, px, py, PORTRAIT_W, PORTRAIT_H);
        // The settler looks toward the mouse — the same lively touch vanilla
        // uses for the player preview in the inventory screen.
        InventoryScreen.renderEntityInInventoryFollowsMouse(g, px + 2, py + 2,
            px + PORTRAIT_W - 2, py + PORTRAIT_H - 2, 22, 0.0625F, mouseX, mouseY, settler);

        // Identity block, in the order the citizen-card recipe asks for:
        // name, then the profession badge in trade colour, with the mayor
        // mark riding the same row as the name when it applies (see class
        // doc -- the badge below carries its own accent card already).
        int tx = left + HEADER_TEXT_X;
        boolean mayor = snapshot != null && snapshot.isMayor();
        int nameBox = HEADER_TEXT_W;
        if (mayor) {
            Component mark = view.mayorMark;
            int markW = Math.min(font.width(mark) + 6, MAYOR_MARK_W);
            int markX = tx + HEADER_TEXT_W - markW;
            HsUi.badge(g, font, mark, markX, top + l.nameTop, markW,
                HsUiTokens.ACCENT & 0xFFFFFF);
            nameBox = HEADER_TEXT_W - markW - 4;
        }
        HsUi.labelIn(g, font, title, tx, top + l.nameTop, nameBox,
            HsUiTokens.TEXT_STRONG);

        Profession profession = settler.getProfession();
        Component job = profession.employed() ? profession.displayName()
            : view.noProfession;
        int professionColor = 0xFF000000 | profession.color();
        HsUi.badge(g, font, job, tx, top + l.professionTop, HEADER_TEXT_W,
            professionColor);

        HsUi.labelIn(g, font, Component.translatable("hearthstead.gui.doing",
            settler.getActivity().displayName()), tx, top + l.activityTop, HEADER_TEXT_W,
            HsUiTokens.TEXT_MUTED);
    }

    private void openRequestList() {
        if (minecraft == null || snapshot == null
            || settler.getProfession() != Profession.COURIER) {
            return;
        }
        openingChild = true;
        minecraft.setScreen(new EquipmentRequestListScreen(this,
            snapshot.entityId(), snapshot.settlerId(), snapshot.sessionId()));
    }

    private void openGuardOrders() {
        if (minecraft == null || snapshot == null
            || !settler.getProfession().martial()) {
            return;
        }
        openingChild = true;
        minecraft.setScreen(new GuardOrderScreen(this, snapshot.entityId(),
            snapshot.settlerId(), snapshot.sessionId()));
    }

    private void drawMayorBadge(GuiGraphics g, int x, int y, int mouseX, int mouseY,
                                CachedView view) {
        if (snapshot == null || !snapshot.isMayor()) {
            return; // the row is reserved (a fixed shape); simply left blank
        }
        HsUi.card(g, x, y, CONTENT_W, MAYOR_BADGE_H, false);
        HsUi.labelIn(g, font, view.mayorBadgeLine, x + 4, y + 2,
            CONTENT_W - 8, HsUiTokens.ACCENT);
        if (hover(mouseX, mouseY, x, y, CONTENT_W, MAYOR_BADGE_H)) {
            pendingTooltip = view.mayorTooltip;
        }
    }

    private void requestChild(SettlerActionPayload.Kind kind) {
        if (snapshot == null) {
            return;
        }
        openingChild = true;
        act(kind);
    }

    private void requestWorkZone() {
        if (snapshot == null) {
            return;
        }
        workZoneRequestPending = true;
        openingChild = true;
        act(SettlerActionPayload.Kind.EDIT_WORK_ZONE);
    }

    /**
     * Consumes only the response to this screen's own pending Edit Work Zone
     * request. Called by WorkZoneClient after a server-authored
     * TARGET_SELECTED snapshot arrives, never on an optimistic click.
     */
    public boolean consumeAcceptedWorkZoneSelection() {
        if (!workZoneRequestPending || snapshot == null) {
            return false;
        }
        workZoneRequestPending = false;
        openingChild = true;
        return true;
    }

    private void drawNeeds(GuiGraphics g, int x, int y, int width, CachedView view) {
        drawNeed(g, x, y, width, view.needLabels[0], settler.getHunger());
        drawNeed(g, x, y + ROW, width, view.needLabels[1], settler.getEnergy());
        drawNeed(g, x, y + ROW * 2, width, view.needLabels[2], settler.getMorale());
        int stamina = snapshot == null ? 0
            : snapshot.attributeValues().get(Attribute.STAMINA.ordinal());
        int pace = Mth.clamp((int) Math.round(JobEffects.workPace(
            settler.getEnergy(), stamina) * 100.0D), 0, 100);
        if (pace != cachedWorkPacePercent) {
            cachedWorkPacePercent = pace;
            cachedWorkPaceValue = Component.literal(pace + "%");
        }
        drawNeed(g, x, y + ROW * 3, width, view.needLabels[3], pace,
            cachedWorkPaceValue);
    }

    private void drawNeed(GuiGraphics g, int x, int y, int width,
                          Component label, float value) {
        drawNeed(g, x, y, width, label, value,
            Component.literal(String.valueOf((int) value)));
    }

    private void drawNeed(GuiGraphics g, int x, int y, int width, Component label,
                          float value, Component valueText) {
        HsUi.labelIn(g, font, label, x, y, NEED_LABEL_W, HsUiTokens.TEXT);
        int barX = x + NEED_LABEL_W;
        int barW = width - NEED_LABEL_W - NEED_PCT_W - GUTTER;
        float ratio = Mth.clamp(value, 0.0F, 100.0F) / 100.0F;
        HsUi.bar(g, barX, y, barW, NEED_BAR_H, ratio, HsUi.Tone.of(ratio));
        HsUi.right(g, font, valueText, x + width, y,
            HsUiTokens.TEXT_MUTED);
    }

    private void drawAttributes(GuiGraphics g, int x, int y, int mouseX, int mouseY,
                                CachedView view) {
        if (snapshot == null) {
            HsUi.labelIn(g, font, view.loading,
                x, y + ROW * 2, CONTENT_W, HsUiTokens.TEXT_MUTED);
            return;
        }
        for (int i = 0; i < view.attributes.length; i++) {
            AttributeView attribute = view.attributes[i];
            int column = i % ATTRIBUTE_COLUMNS;
            int row = i / ATTRIBUTE_COLUMNS;
            int cellX = x + column * (ATTRIBUTE_COL_W + GUTTER);
            int cellY = y + row * (ATTRIBUTE_CELL_H + ATTRIBUTE_GAP);
            boolean hovered = hover(mouseX, mouseY, cellX, cellY,
                ATTRIBUTE_COL_W, ATTRIBUTE_CELL_H);
            HsUi.card(g, cellX, cellY, ATTRIBUTE_COL_W, ATTRIBUTE_CELL_H,
                hovered);
            int textX = cellX + 4;
            HsUi.labelIn(g, font, attribute.label, textX, cellY + 4,
                ATTRIBUTE_COL_W - ATTRIBUTE_VALUE_W - 8,
                attribute.knack ? HsUiTokens.ACCENT : HsUiTokens.TEXT);
            HsUi.right(g, font, attribute.valueText,
                cellX + ATTRIBUTE_COL_W - 4, cellY + 4,
                attribute.tone.colour());
            HsUi.bar(g, textX, cellY + 14, ATTRIBUTE_COL_W - 8, 3,
                attribute.ratio, attribute.tone);
            if (hovered) {
                // Tiered: what the attribute governs (why it matters), then
                // what raises it (grey, secondary) -- the same "name, then
                // grey description" tooltip shape as the Hearth ledger.
                pendingTooltip = attribute.tooltip;
            }
        }
    }

    /**
     * One card per reserved trait slot (see the class doc for why the count
     * of slots never depends on how many traits this settler actually has).
     * Each card is a name row and one content row: the trait's wired
     * buff/malus chips when it has any, its existing flavour line when it
     * has none — see {@link #wiredEffects}.
     */
    private void drawTraits(GuiGraphics g, int x, int y, int mouseX, int mouseY,
                            CachedView view) {
        if (snapshot == null) {
            return;
        }
        for (int slot = 0; slot < TRAIT_SLOTS; slot++) {
            int cardW = (CONTENT_W - GUTTER) / 2;
            int cardX = x + slot * (cardW + GUTTER);
            int cardY = y;
            TraitView trait = view.traits[slot];
            if (trait == null) {
                continue; // reserved but blank -- the settler has only one trait
            }
            boolean hovered = hover(mouseX, mouseY, cardX, cardY, cardW, TRAIT_CARD_H);
            HsUi.card(g, cardX, cardY, cardW, TRAIT_CARD_H, hovered);
            int tx = cardX + TRAIT_CARD_PAD;
            int limit = cardX + cardW - TRAIT_CARD_PAD;
            HsUi.labelIn(g, font, trait.name, tx, cardY + TRAIT_CARD_PAD,
                cardW - 2 * TRAIT_CARD_PAD, HsUiTokens.TEXT_STRONG);
            int lineY = cardY + TRAIT_CARD_PAD + HsUiTokens.LINE_GAP;
            if (trait.effects.isEmpty()) {
                HsUi.labelIn(g, font, trait.description, tx, lineY,
                    cardW - 2 * TRAIT_CARD_PAD, HsUiTokens.TEXT_MUTED);
            } else {
                drawEffectChips(g, trait.effects, tx, lineY, limit);
            }
            if (hovered) {
                pendingTooltip = trait.tooltip;
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

    private void drawEmployment(GuiGraphics g, int x, int y, CachedView view) {
        if (snapshot == null) {
            return;
        }
        HsUi.labelIn(g, font, view.employmentLine, x, y, CONTENT_W, HsUiTokens.TEXT);
    }

    private void drawCurrentRequest(GuiGraphics g, int x, int labelY,
                                    int cardY, int width, int mouseX, int mouseY,
                                    CachedView view) {
        HsUi.labelIn(g, font, view.currentRequestLabel, x, labelY,
            width, HsUiTokens.TEXT_STRONG);
        boolean hovered = hover(mouseX, mouseY, x, cardY, width,
            CURRENT_REQUEST_CARD_H);
        HsUi.card(g, x, cardY, width, CURRENT_REQUEST_CARD_H, hovered);

        int textX = x + 6;
        if (!view.currentRequestStack.isEmpty()) {
            HsUi.slot(g, x + 5, cardY + 7);
            g.renderItem(view.currentRequestStack, x + 6, cardY + 8);
            textX = x + 29;
        }
        int textWidth = x + width - 6 - textX;
        HsUi.labelIn(g, font, view.currentRequestName, textX, cardY + 6,
            textWidth, view.currentRequestStack.isEmpty()
                ? HsUiTokens.GOOD : HsUiTokens.WARN);
        HsUi.labelIn(g, font, view.currentRequestInstruction, textX,
            cardY + 18, textWidth, HsUiTokens.TEXT_MUTED);
        if (hovered && !view.currentRequestTooltip.isEmpty()) {
            pendingTooltip = view.currentRequestTooltip;
        }
    }

    /** Kill XP is live entity data; its cached component rebuilds only on change. */
    private void drawCombatProgress(GuiGraphics g, int x, int y,
                                    int mouseX, int mouseY, CachedView view) {
        Profession profession = settler.getProfession();
        if (snapshot == null
            || (profession != Profession.GUARD && profession != Profession.ARCHER)) {
            return; // the compact civilian layout does not reserve this row
        }
        HsUi.labelIn(g, font, view.combatProgressLine, x, y, CONTENT_W,
            HsUiTokens.ACCENT);
        if (hover(mouseX, mouseY, x, y, CONTENT_W, ROW)) {
            pendingTooltip = view.combatProgressTooltip;
        }
    }

    /**
     * Permanent target status from the server-authored inspection snapshot.
     * Zero ranks stay out of the line; the empty state remains explicit.
     * Short translated names keep the worst-case three-rank line within the
     * fixed 240px content width at GUI scales 2–4.
     */
    private void drawBlessings(GuiGraphics g, int x, int y) {
        if (snapshot == null) {
            return;
        }
        HsUi.labelIn(g, font, blessingStatusLine, x, y, CONTENT_W,
            hasBlessings ? HsUiTokens.ACCENT : HsUiTokens.TEXT_MUTED);
    }

    /** No per-frame component churn: rebuilt only with the cached view. */
    private void rebuildBlessingStatus() {
        MutableComponent ranks = Component.empty();
        int activeRanks = 0;
        for (BlessingId blessing : BLESSING_IDS) {
            activeRanks += snapshot.blessingRank(blessing) > 0 ? 1 : 0;
        }
        boolean any = false;
        for (BlessingId blessing : BLESSING_IDS) {
            int rank = snapshot.blessingRank(blessing);
            if (rank <= 0) {
                continue;
            }
            if (any) {
                ranks.append(Component.literal(" • "));
            }
            ranks.append(Component.translatable("hearthstead.blessing."
                + blessing.id() + (activeRanks == BLESSING_IDS.length
                    ? ".short" : ".name")));
            ranks.append(Component.literal(" " + roman(rank)));
            any = true;
        }
        hasBlessings = any;
        blessingStatusLine = any
            ? Component.translatable("hearthstead.blessing.status", ranks)
            : Component.translatable("hearthstead.blessing.status.none");
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
     * {@link #layout}, which reserves it only while a refusal is present).
     */
    private void drawRefusal(GuiGraphics g, int x, int y, CachedView view) {
        if (snapshot == null || view.refusal == null) {
            return;
        }
        g.drawWordWrap(font, view.refusal, x, y, CONTENT_W, HsUiTokens.WARN);
    }

    /**
     * The settler's bag: what they are actually carrying, as real ghost
     * slots (a slot background, the item, and its vanilla count overlay) —
     * read-only, nothing here is clickable or moves an item. Chest truth:
     * every slot here is a real {@code ItemStack} in {@code SettlerEntity}'s
     * bag container, not a display fiction, so this can never disagree with
     * what a hearth deposit actually collects.
     */
    private void drawBag(GuiGraphics g, int x, int labelY, int slotsY, CachedView view) {
        HsUi.labelIn(g, font, view.bagLabel, x, labelY,
            CONTENT_W, HsUiTokens.TEXT);
        if (snapshot == null) {
            return;
        }
        for (int i = 0; i < BAG_SLOTS; i++) {
            int slotX = x + i * BAG_SLOT_STEP;
            HsUi.slot(g, slotX, slotsY);
            ItemStack stack = view.bagStacks[i];
            if (stack.isEmpty()) {
                continue; // an empty slot: the slot sprite alone says so
            }
            g.renderItem(stack, slotX + 1, slotsY + 1);
            g.renderItemDecorations(font, stack, slotX + 1, slotsY + 1);
        }
    }

    // -------------------------------------------------------------- helpers --

    /**
     * Returns the immutable render model for the current stable inputs. A
     * translated component resolves through the active language, but rebuilding
     * on an actual locale switch also refreshes cached widths/tooltips and keeps
     * this cache safe if those components gain eager formatting later.
     */
    private CachedView view() {
        String language = minecraft == null
            ? ""
            : minecraft.getLanguageManager().getSelected();
        int combatExperience = settler.combatExperience();
        if (cachedView == null || cachedViewSnapshot != snapshot
            || cachedViewWidth != width || cachedViewHeight != height
            || cachedViewCombatExperience != combatExperience
            || !cachedViewLanguage.equals(language)) {
            CachedView rebuilt = buildView();
            cachedViewSnapshot = snapshot;
            cachedViewWidth = width;
            cachedViewHeight = height;
            cachedViewCombatExperience = combatExperience;
            cachedViewLanguage = language;
            cachedView = rebuilt;
        }
        return cachedView;
    }

    private void invalidateView() {
        cachedView = null;
    }

    /** All allocations here are paid only when {@link #view()} invalidates. */
    private CachedView buildView() {
        Profession profession = settler.getProfession();
        boolean mayor = snapshot != null && snapshot.isMayor();
        int traitRows = snapshot == null ? 1 : Math.max(1,
            Math.min(TRAIT_SLOTS, snapshot.traitOrdinals().size()));
        boolean combatRow = profession == Profession.GUARD
            || profession == Profession.ARCHER;
        boolean refusalRows = snapshot != null && snapshot.refusal().isPresent();
        Layout layout = layout(0, mayor, traitRows, combatRow, refusalRows);
        Component[] needLabels = {
            Component.translatable("hearthstead.gui.hunger"),
            Component.translatable("hearthstead.gui.energy"),
            Component.translatable("hearthstead.gui.morale"),
            Component.translatable("hearthstead.gui.work_pace")
        };
        AttributeView[] attributes = new AttributeView[Attribute.COUNT];
        TraitView[] traits = new TraitView[TRAIT_SLOTS];
        ItemStack[] bagStacks = new ItemStack[BAG_SLOTS];
        for (int i = 0; i < BAG_SLOTS; i++) {
            bagStacks[i] = ItemStack.EMPTY;
        }

        Component loading = Component.translatable("hearthstead.settler.loading");
        Component noProfession = Component.translatable("hearthstead.profession.none");
        Component bagLabel = Component.translatable("hearthstead.settler.bag");
        Component mayorMark = Component.translatable("hearthstead.settler.mayor_mark");
        Component mayorBadgeLine = Component.empty();
        List<Component> mayorTooltip = List.of();
        Component employmentLine = Component.empty();
        Component combatProgressLine = Component.empty();
        List<Component> combatProgressTooltip = List.of();
        Component refusal = null;
        Component currentRequestLabel = Component.translatable(
            "hearthstead.settler.current_request");
        ItemStack currentRequestStack = ItemStack.EMPTY;
        Component currentRequestName = Component.translatable(
            "hearthstead.settler.request.none");
        Component currentRequestInstruction = Component.translatable(
            "hearthstead.settler.request.none.instruction");
        List<Component> currentRequestTooltip = List.of();

        Component building = buildingName();
        Component dismissTooltip = Component.translatable(
            "hearthstead.settler.dismiss.tip", title, building);
        Component appointTooltip = appointTooltip();

        if (snapshot == null) {
            blessingStatusLine = Component.empty();
            hasBlessings = false;
        } else {
            for (Attribute attribute : Attribute.ALL) {
                int ordinal = attribute.ordinal();
                boolean knack = ordinal == snapshot.knackOrdinal();
                Component name = attribute.displayName();
                int value = snapshot.attributeValues().get(ordinal);
                Component label = name;
                Component tooltipName = knack
                    ? Component.translatable("hearthstead.settler.attribute_knack", name)
                    : name;
                Component valueText = Component.literal(value + " / 100");
                List<Component> tooltip = List.of(
                    tooltipName.copy().append(Component.literal("  " + value
                        + " / 100")).withStyle(knack
                            ? ChatFormatting.GOLD : ChatFormatting.WHITE),
                    Component.translatable("hearthstead.attribute."
                        + attribute.key() + ".role"),
                    attribute.trainedBy().copy().withStyle(ChatFormatting.GRAY));
                attributes[ordinal] = new AttributeView(label, valueText,
                    value / 100.0F, attributeTone(value), knack, tooltip);
            }

            List<Integer> traitOrdinals = snapshot.traitOrdinals();
            int visibleTraits = Math.min(TRAIT_SLOTS, traitOrdinals.size());
            for (int slot = 0; slot < visibleTraits; slot++) {
                Trait trait = Trait.ALL[traitOrdinals.get(slot)];
                Component name = trait.displayName();
                Component description = trait.describe();
                List<Effect> effects = List.copyOf(wiredEffects(trait));
                traits[slot] = new TraitView(name, description, effects,
                    List.of(name,
                        description.copy().withStyle(ChatFormatting.GRAY)));
            }

            List<Integer> ids = snapshot.bagItemIds();
            List<Integer> counts = snapshot.bagCounts();
            for (int slot = 0; slot < BAG_SLOTS; slot++) {
                int count = slot < counts.size() ? counts.get(slot) : 0;
                if (count > 0) {
                    int itemId = slot < ids.size() ? ids.get(slot) : 0;
                    bagStacks[slot] = new ItemStack(
                        BuiltInRegistries.ITEM.byId(itemId), count);
                }
            }

            Component boon = boonName();
            if (snapshot.isMayor()) {
                mayorBadgeLine = Component.translatable(snapshot.mayorSettling()
                        ? "hearthstead.settler.mayor_settling"
                        : "hearthstead.settler.mayor_badge",
                    boon);
                mayorTooltip = List.of(boonDescription());
            }

            if (snapshot.employerBuildingId().isEmpty()) {
                employmentLine = Component.translatable("hearthstead.employ.unemployed");
            } else if (settler.getProfession() == Profession.GUARD) {
                employmentLine = Component.translatable(
                    "hearthstead.settler.employed_watch", building,
                    Component.translatable(snapshot.guardWatchNight()
                        ? "hearthstead.settler.watch_night"
                        : "hearthstead.settler.watch_day"));
            } else {
                employmentLine = Component.translatable(
                    "hearthstead.settler.employed_at", building);
            }
            if (profession == Profession.GUARD || profession == Profession.ARCHER) {
                int experience = settler.combatExperience();
                GuardExperience.Tier tier = GuardExperience.tierOf(experience);
                combatProgressLine = tier == GuardExperience.Tier.HERO
                    ? Component.translatable("hearthstead.settler.combat_progress_max",
                        tier.level(), experience)
                    : Component.translatable("hearthstead.settler.combat_progress",
                        tier.level(), experience,
                        GuardExperience.nextThreshold(experience));
                Attribute rankAttribute = profession == Profession.GUARD
                    ? Attribute.STRENGTH : Attribute.DEXTERITY;
                int rankValue = snapshot.attributeValues().get(rankAttribute.ordinal());
                Component abilityRank = profession == Profession.GUARD
                    ? GuardRank.of(rankValue).displayName()
                    : ArcherRank.of(rankValue).displayName();
                combatProgressTooltip = List.of(Component.translatable(
                    "hearthstead.settler.combat_rank_tip", abilityRank,
                    rankAttribute.displayName()).withStyle(ChatFormatting.GRAY));
            }
            refusal = snapshot.refusal().orElse(null);
            if (snapshot.requestedItemId() >= 0) {
                currentRequestStack = new ItemStack(BuiltInRegistries.ITEM.byId(
                    snapshot.requestedItemId()));
                if (!currentRequestStack.isEmpty()) {
                    currentRequestName = Component.translatable(
                        "hearthstead.settler.request.item",
                        currentRequestStack.getHoverName());
                    EquipmentRequest.Reason[] reasons =
                        EquipmentRequest.Reason.values();
                    EquipmentRequest.Reason reason = snapshot.requestReasonOrdinal() >= 0
                        && snapshot.requestReasonOrdinal() < reasons.length
                            ? reasons[snapshot.requestReasonOrdinal()]
                            : EquipmentRequest.Reason.MISSING;
                    String reasonKey = switch (reason) {
                        case MISSING -> "missing";
                        case WRONG_TOOL -> "wrong";
                        case WORN -> "worn";
                    };
                    currentRequestInstruction = Component.translatable(
                        "hearthstead.settler.request." + reasonKey + ".instruction");
                    currentRequestTooltip = List.of(
                        Component.translatable("hearthstead.settler.request.tooltip",
                            currentRequestStack.getHoverName()),
                        currentRequestInstruction.copy().withStyle(ChatFormatting.GRAY));
                }
            }
            rebuildBlessingStatus();
        }

        return new CachedView(layout, needLabels, attributes, traits, bagStacks,
            loading, noProfession, bagLabel, mayorMark, mayorBadgeLine,
            mayorTooltip, employmentLine, combatProgressLine,
            combatProgressTooltip, refusal, dismissTooltip,
            appointTooltip, currentRequestLabel, currentRequestStack,
            currentRequestName, currentRequestInstruction,
            currentRequestTooltip);
    }

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

    private static String roman(int rank) {
        return switch (rank) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            default -> "—";
        };
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

    @Override
    public String qaUiState() {
        CachedView current = view();
        ItemStack request = current.currentRequestStack;
        String equipment = request.isEmpty() ? "ready"
            : "needs_" + BuiltInRegistries.ITEM.getKey(request.getItem());
        int reason = snapshot == null ? -1 : snapshot.requestReasonOrdinal();
        return "work=" + settler.getActivity().name() + ",equipment="
            + equipment + ",reason=" + reason + ",panel=" + left + ":" + top
            + ":" + PANEL_W + ":" + contentHeight + ",scroll=" + scrollOffset
            + "/" + maxScroll + ",traits="
            + (snapshot == null ? 0 : snapshot.traitOrdinals().size())
            + ",mayor=" + (snapshot != null && snapshot.isMayor());
    }

    // --------------------------------------------------------------- layout --

    /**
     * Row positions for one immutable render projection. Optional rows are
     * selected while the authoritative snapshot is cached; ordinary frames
     * and scrolling reuse this exact geometry until that snapshot changes.
     */
    private static Layout layout(int originY, boolean showMayorBadge,
                                 int traitRows, boolean showCombat,
                                 boolean showRefusal) {
        Layout l = new Layout();
        int y = originY + PAD;

        l.nameTop = y;
        l.professionTop = y + ROW;
        l.activityTop = y + ROW * 2;
        y += HEADER_H + GUTTER;

        // The prominent MAYOR mark is part of the identity header. Keeping a
        // second full-width mayor row made the common overview taller without
        // adding another decision.
        l.mayorBadgeTop = y;

        l.dividerA = y;
        y += HsUiTokens.DIVIDER_H + GUTTER;

        // Needs and the current blocker answer sibling questions and share one
        // stable summary band instead of becoming a scrolling column.
        l.needsTop = y;
        l.currentRequestLabelTop = y;
        l.currentRequestCardTop = y + ROW;
        y += Math.max(ROW * 4, ROW + CURRENT_REQUEST_CARD_H) + GUTTER;

        l.dividerB = y;
        y += HsUiTokens.DIVIDER_H + GUTTER;

        l.attributesTop = y;
        y += ATTRIBUTE_ROWS * (ATTRIBUTE_CELL_H + ATTRIBUTE_GAP);

        l.dividerC = y;
        y += HsUiTokens.DIVIDER_H + GUTTER;

        l.traitsTop = y;
        y += TRAIT_CARD_H + GUTTER;

        // A second divider between "who they are" (traits) and "what they
        // do" (employment, refusal) -- both are real semantic groups, and
        // the panel is tall enough now that the extra rule earns its place
        // rather than crowding the one above it.
        l.dividerPerson = y;
        y += HsUiTokens.DIVIDER_H + GUTTER;

        l.employmentTop = y;
        y += ROW + GUTTER;
        l.combatProgressTop = y;
        if (showCombat) {
            y += ROW + GUTTER;
        }
        l.blessingsTop = y;
        y += ROW + GUTTER;
        l.refusalTop = y;
        if (showRefusal) {
            // Two rows: the longest refusal sentences wrap to two lines.
            y += ROW * 2 + GUTTER;
        }

        // Inventory has a real server-authoritative child screen. Duplicating
        // its slot row here cost 34 vertical pixels and encouraged players to
        // mistake read-only ghosts for usable slots.
        l.dividerBag = y;
        l.bagLabelTop = y;
        l.bagSlotsTop = y;
        l.dividerD = y;
        y += HsUiTokens.DIVIDER_H + GUTTER;

        l.controlsTop = y;
        y += HsUiTokens.BUTTON_H + GUTTER;

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
        int currentRequestLabelTop;
        int currentRequestCardTop;
        int dividerB;
        int attributesTop;
        int dividerC;
        int traitsTop;
        int dividerPerson;
        int employmentTop;
        int combatProgressTop;
        int blessingsTop;
        int refusalTop;
        int dividerBag;
        int bagLabelTop;
        int bagSlotsTop;
        int dividerD;
        int controlsTop;
        int appointTop;
        int footerTop;
        int totalHeight;
    }

    /** Snapshot-authored attribute row, including its hover payload. */
    private static HsUi.Tone attributeTone(int value) {
        if (value <= 9) {
            return HsUi.Tone.BAD;
        }
        if (value <= 39) {
            return HsUi.Tone.WARN;
        }
        if (value <= 79) {
            return HsUi.Tone.GOOD;
        }
        return HsUi.Tone.ACCENT;
    }

    private record AttributeView(Component label, Component valueText,
                                 float ratio, HsUi.Tone tone, boolean knack,
                                 List<Component> tooltip) {
    }

    /** Snapshot-authored trait card; its effect list is never rebuilt in render. */
    private record TraitView(Component name, Component description,
                             List<Effect> effects, List<Component> tooltip) {
    }

    /**
     * Immutable-by-convention render projection. Arrays never escape this
     * screen and are replaced as a unit when the cache key changes.
     */
    private record CachedView(Layout layout, Component[] needLabels,
                              AttributeView[] attributes, TraitView[] traits,
                              ItemStack[] bagStacks, Component loading,
                              Component noProfession, Component bagLabel,
                              Component mayorMark, Component mayorBadgeLine,
                              List<Component> mayorTooltip,
                              Component employmentLine,
                              Component combatProgressLine,
                              List<Component> combatProgressTooltip,
                              Component refusal,
                              Component dismissTooltip,
                              Component appointTooltip,
                              Component currentRequestLabel,
                              ItemStack currentRequestStack,
                              Component currentRequestName,
                              Component currentRequestInstruction,
                              List<Component> currentRequestTooltip) {
    }
}
