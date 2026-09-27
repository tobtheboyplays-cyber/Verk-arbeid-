package com.hearthstead.client.screen;

import com.hearthstead.building.BuildingType;
import com.hearthstead.client.QaClientObserver;
import com.hearthstead.client.QaUiInspectable;
import com.hearthstead.client.command.SummonClient;
import com.hearthstead.client.render.SettlerRenderer;
import com.hearthstead.client.ui.HsButton;
import com.hearthstead.client.ui.HsMotion;
import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.BannerSheetLayout;
import com.hearthstead.client.ui2.JobIcons;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.client.ui2.Ui2Tabs;
import com.hearthstead.client.ui2.Ui2WoodKey;
import com.hearthstead.client.ui2.map.MarkerTrack;
import com.hearthstead.client.ui2.map.RealmMapClient;
import com.hearthstead.entity.ArcherRank;
import com.hearthstead.entity.Attribute;
import com.hearthstead.entity.GuardExperience;
import com.hearthstead.entity.GuardRank;
import com.hearthstead.entity.JobAttributeProfile;
import com.hearthstead.entity.JobEffects;
import com.hearthstead.entity.LifeNeed;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.SkillLevels;
import com.hearthstead.entity.Trait;
import com.hearthstead.logistics.StopReason;
import com.hearthstead.network.RealmMapStatus;
import com.hearthstead.network.SettlerActionPayload;
import com.hearthstead.network.SettlerSnapshotPayload;
import com.hearthstead.settlement.development.PostRaidUpgrade;
import com.hearthstead.settlement.equipment.EquipmentRequest;
import com.hearthstead.settlement.gear.GearGate;
import com.hearthstead.settlement.gear.GearTier;
import com.hearthstead.settlement.gear.GearTiers;
import com.hearthstead.settlement.state.BlessingId;
import com.hearthstead.settlement.work.FisherProgression;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * The settler sheet, in the Banner screen's material language: a walnut
 * board with iron corners, a burgundy crest carrying the settler's job icon,
 * the settler's name in serif small caps, and two parchment panels set into
 * the wood -- the living settler on the left (a slow turntable you can drag,
 * with their needs underneath) and three calm pages on the right: Overview
 * (what they are doing, where they work and sleep, what you can do about
 * it), Skills (trade level, attributes, job focus, traits) and Gear (worn
 * equipment with the Gear Tier locks, the bag and the tier ladder).
 *
 * <h2>Two sources, never confused</h2>
 *
 * <p>Name, profession, current activity, health, the three needs, worn
 * equipment and the gear clearance are synced entity data and are read off
 * {@link #settler} every frame. Attributes, traits, employment, home, the
 * bag and the settler's standing live server-side and arrive as a
 * {@link SettlerSnapshotPayload} -- once when the sheet opens and again after
 * every action. The sheet draws only what it was told: no fake widget and no
 * placeholder stat. A row whose data does not exist for this settler is not
 * drawn at all.
 *
 * <h2>Traits show only what they verifiably do</h2>
 *
 * <p>{@link Trait} carries eight multiplier fields, but only four are read by
 * gameplay ({@code growth}, {@code moraleDecay}, {@code moraleGain},
 * {@code hunger}) plus the flat {@code SLOW_START} penalty; {@link
 * #wiredEffects} reports only those. A trait without a wired effect keeps its
 * flavour line instead of a fabricated number.
 *
 * <h2>Nothing measured per frame</h2>
 *
 * <p>Every string the sheet draws is translated, fitted (ellipsized, with the
 * full text as a hover tooltip when cut) and measured once, when the
 * snapshot, window size, locale or one of the few live inputs changes. The
 * draw methods (between the {@code draw: begin/end} markers) only paint.
 */
public class SettlerScreen extends Screen implements QaUiInspectable {

    /** The player walking away (not the settler walking about) closes the sheet. */
    private static final double PLAYER_LEAVE_DIST_SQR = 1.5 * 1.5;
    /** Generous: couriers, lumberers and patrolling guards keep moving while read. */
    private static final double SETTLER_LEAVE_DIST_SQR = 24.0 * 24.0;
    private static final BlessingId[] BLESSING_IDS = BlessingId.values();
    private static final int BAG_SLOTS = SettlerEntity.BAG_SIZE;
    private static final int TRAIT_SLOTS = 2;

    // -- sheet geometry: one spacing scale (4 / 8 / 12 / 16) -------------------
    static final int MIN_W = 304;
    static final int MIN_H = 224;
    /** The Banner's approved footprint cap (see BannerSheetLayout.MAX_WIDTH). */
    static final int MAX_W = BannerSheetLayout.MAX_WIDTH;
    static final int MAX_H = BannerSheetLayout.MAX_HEIGHT;
    static final int FRAME = BannerSheetLayout.FRAME;
    static final int HEADER_H = 28;
    static final int GUTTER = 8;
    static final int PAD = 6;
    static final int NEED_ROW = 12;
    static final int NEED_ROWS = 4;
    static final int FOOTER_H = 18;
    static final int TEXT_BUTTON_H = 12;
    static final int WIDE_PAGE_MIN = 300;
    static final int COUNTERS = 2;
    private static final int SLOT = 18;
    private static final int SLOT_STEP = 20;
    private static final int CHIP_W = 17;
    private static final int CHIP_H = 12;
    private static final int CHIP_STEP = 19;
    private static final int SECTION_H = 14;

    private static final EquipmentSlot[] WORN = {
        EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET,
        EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND
    };
    private static final ResourceLocation HEART = ResourceLocation.withDefaultNamespace("hud/heart/full");
    private static final ResourceLocation HEART_CONTAINER = ResourceLocation.withDefaultNamespace("hud/heart/container");
    private static final ItemStack BED_ICON = new ItemStack(Items.RED_BED);
    private static final ItemStack NO_POST_ICON = new ItemStack(Items.OAK_SIGN);

    protected final SettlerEntity settler;
    private SettlerSnapshotPayload snapshot;
    /** Role metadata may arrive after the separate inspection snapshot. */
    private Profession renderedProfession;

    // -- render model cache ------------------------------------------------------
    private SheetView cachedView;
    private SettlerSnapshotPayload cachedViewSnapshot;
    private int cachedViewWidth = -1;
    private int cachedViewHeight = -1;
    private int cachedViewCombatExperience = -1;
    private int cachedViewCarryCapacity = Integer.MIN_VALUE;
    private StopReason cachedViewStopReason;
    private boolean cachedViewWorking;
    private int cachedViewTradeXp = -1;
    private int cachedViewLifeNeed = -1;
    private int cachedViewClearance = Integer.MIN_VALUE;
    private int cachedViewBuildSites = Integer.MIN_VALUE;
    private Font cachedViewFont;
    private String cachedViewLanguage = "";
    /** Numeric need labels are stable between their rendered integer changes. */
    private final NeedValueCache needValueCache = new NeedValueCache();
    private final LiveText live = new LiveText();
    /** Rebuilt with the cached view when snapshot, size or locale changes. */
    private Component blessingStatusLine = Component.empty();
    private boolean hasBlessings;

    // -- serif text (cached per string and GUI scale inside Ui2Serif) ------------
    private final Ui2Serif.Text nameText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private final Ui2Serif.Text[] headings = new Ui2Serif.Text[14];


    private int left;
    private int top;
    private Layout layout;
    /** Set while drawing a hovered non-widget region; rendered once, last. */
    private List<Component> pendingTooltip;
    /** Player position when this sheet was shown; cleared in removed(). */
    private net.minecraft.world.phys.Vec3 playerAnchor;
    /** Suppresses CLOSE while this exact sheet temporarily opens a child tab. */
    private boolean openingChild;
    private boolean uiSoundActive;
    /** True only between a work-zone click and its server-authored zone reply. */
    private boolean workZoneRequestPending;

    // -- portrait turntable ----------------------------------------------------------
    private float portraitOffset = 0.0F;
    private float swayPhase = 0.0F;
    private long lastFrameMs = -1L;
    private boolean draggingPortrait;
    private double dragLastX;

    // -- widgets (stable slots keep keyboard focus across a role rebuild) -----------
    private HsButton closeButton;
    private HsButton dismissButton;
    private HsButton appointButton;
    private HsButton inventoryButton;
    private HsButton workplaceButton;
    private HsButton workZoneButton;
    private HsButton locateButton;
    private HsButton mapButton;
    private HsButton requestListButton;
    private HsButton guardOrderButton;
    private HsButton summonButton;
    private String summonReasonShown;

    // Motion only: first-open intro (4 px slide + fade); created once, survives re-init.
    private HsMotion.ScreenIntro hsIntro;
    private boolean hsIntroRendering;

    public SettlerScreen(SettlerEntity settler) {
        super(Component.literal(settler.getSettlerName()));
        this.settler = settler;
    }

    // ============================================================== lifecycle ===

    /**
     * A fresh snapshot from the server replaces what is on screen. Guarded by
     * entity id even though only one settler screen is ever open at a time --
     * a snapshot in flight when the player closes this screen and opens a
     * different settler's must never land on the wrong one.
     */
    public void update(SettlerSnapshotPayload fresh) {
        if (!acceptsSnapshot(fresh)) {
            return;
        }
        if (fresh.refusal().isPresent()) {
            // A rejected child request leaves this sheet in place. Reset
            // even when the same refusal is repeated.
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
        playerAnchor = null;
        draggingPortrait = false;
        super.removed();
    }

    @Override
    protected void init() {
        openingChild = false;
        // Resizes keep the anchor; a fresh open or a return from a child tab
        // (both pass through removed()) re-anchors at the current position.
        if (playerAnchor == null && minecraft != null && minecraft.player != null) {
            playerAnchor = minecraft.player.position();
        }
        invalidateView();
        rebuild();
        if (!uiSoundActive) {
            uiSoundActive = true;
            HsUi.playOpenSound();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (settler.isRemoved() || !settler.isAlive()
            || minecraft.player.distanceToSqr(settler) > SETTLER_LEAVE_DIST_SQR
            || (playerAnchor != null && minecraft.player.position()
                .distanceToSqr(playerAnchor) > PLAYER_LEAVE_DIST_SQR)) {
            onClose();
            return;
        }
        refreshSummonButton();
        // UPDATE can precede vanilla entity metadata. Refresh both the cached
        // role/layout and role-dependent widgets when that metadata catches up.
        if (renderedProfession != settler.getProfession()) {
            HsButton[] previous = actionWidgets();
            int focusedAction = -1;
            for (int index = 0; index < previous.length; index++) {
                if (previous[index] != null && previous[index] == getFocused()) {
                    focusedAction = index;
                    break;
                }
            }
            rebuild();
            HsButton[] current = actionWidgets();
            if (focusedAction >= 0 && current[focusedAction] != null
                && current[focusedAction].active && current[focusedAction].visible) {
                setFocused(current[focusedAction]);
            }
        }
    }

    /** Stable action slots keep surviving keyboard focus on the new widget. */
    private HsButton[] actionWidgets() {
        return new HsButton[]{dismissButton, closeButton, requestListButton,
            guardOrderButton, appointButton, inventoryButton, workZoneButton,
            workplaceButton, locateButton, mapButton, summonButton};
    }

    // ================================================================ widgets ===

    private void rebuild() {
        Profession profession = settler.getProfession();
        if (renderedProfession != profession) {
            invalidateView();
        }
        renderedProfession = profession;
        clearWidgets();
        SheetView view = view();
        Layout l = view.layout;
        layout = l;
        left = (width - l.panelWidth) / 2;
        top = (height - l.totalHeight) / 2;
        closeButton = null;
        dismissButton = null;
        appointButton = null;
        inventoryButton = null;
        workplaceButton = null;
        workZoneButton = null;
        locateButton = null;
        mapButton = null;
        requestListButton = null;
        guardOrderButton = null;
        summonButton = null;

        UiRect close = l.close;
        closeButton = new Ui2WoodKey(left + close.x(), top + close.y(), close.width(), close.height(),
            Component.literal("×"), this::onClose);
        closeButton.setTooltip(Tooltip.create(Component.translatable("hearthstead.settler.close")));
        addRenderableWidget(closeButton);

        // Owner, 26 Sep: ONE page, no tabs and no scrolling; the inventory
        // lives behind Shift + right-click on the settler, not on this sheet.
        buildOnePageWidgets(l, view);
    }

    /*
     * One-page sheet (owner, 26 Sep 23:45): general knowledge on the left
     * (small portrait, trade level and XP, Right now, needs, work and home),
     * the job's attributes on the right (primary and secondary first, with
     * what they do), Blessings along the bottom, one action row in the footer.
     * Page-local rows; nothing scrolls.
     */
    static final int PORTRAIT_W = 40;
    static final int PORTRAIT_H = 52;
    static final int COL_GAP = 16;
    static final int L_TRADE = 0;
    static final int L_STATUS = 16;
    static final int L_NEXT = 27;
    static final int L_ACTIVITY = 38;
    static final int L_NEEDS = 58;
    static final int L_WORK = 86;
    static final int L_LINKS = 104;
    static final int L_HOME = 120;
    static final int R_FOCUS = 0;
    static final int R_ATTR = 60;
    static final int R_ATTR_CELLS = R_ATTR + 14;
    static final int ATTR_ROW_H = 12;
    static final int R_MORE = R_ATTR_CELLS + ATTR_ROW_H * 4 + 2;
    static final int BLESS_Y = 140;
    static final int ONE_PAGE_H = BLESS_Y + 22;

    static int columnWidth(int pageWidth) {
        return (pageWidth - COL_GAP) / 2;
    }

    private void buildOnePageWidgets(Layout l, SheetView view) {
        Profession profession = settler.getProfession();
        boolean manage = snapshot != null && snapshot.canManage();
        UiRect p = l.page;
        int pageLeft = left + p.x();
        int pageTop = top + p.y();
        int colW = columnWidth(p.width());

        // Work links under the Work line: the plaque, then the role's own screen.
        actionH = LINK_BTN_H;
        int x = pageLeft + 20;
        int y = pageTop + L_LINKS - 1;
        int limit = pageLeft + colW;
        if (!view.workplaceMissing) {
            Component open = Component.translatable("hearthstead.settler.sheet.open_workplace");
            workplaceButton = textAction(x, y, limit - x, open,
                () -> requestChild(SettlerActionPayload.Kind.OPEN_WORKPLACE));
            workplaceButton.active = manage;
            tip(workplaceButton, Component.translatable("hearthstead.settler.control.workplace.tip"));
            x += workplaceButton.getWidth() + GUTTER;
        }
        if (profession == Profession.FARMER || profession == Profession.LUMBERER) {
            Component zone = Component.translatable("hearthstead.settler.sheet.work_zone");
            workZoneButton = textAction(x, y, limit - x, zone, this::requestWorkZone);
            workZoneButton.active = manage;
            tip(workZoneButton, Component.translatable("hearthstead.settler.control.work_zone.tip"));
            x += workZoneButton.getWidth() + GUTTER;
        }
        if (profession == Profession.COURIER) {
            requestListButton = textAction(x, y, limit - x,
                Component.translatable("hearthstead.equipment.requests"), this::openRequestList);
            requestListButton.active = manage;
            tip(requestListButton, Component.translatable("hearthstead.settler.sheet.requests.tip"));
        } else if (profession == Profession.GUARD || profession == Profession.ARCHER) {
            guardOrderButton = textAction(x, y, limit - x,
                Component.translatable("hearthstead.guard.command.open"), this::openGuardOrders);
            guardOrderButton.active = manage;
            tip(guardOrderButton, Component.translatable("hearthstead.guard.command.open_tip"));
            if (profession == Profession.GUARD
                && com.hearthstead.client.captain.CaptainClient.isHero(settler)) {
                // Hero Captain (plan/CAPTAIN.md): name, loadout, cape, specials.
                int cx = x + guardOrderButton.getWidth() + GUTTER;
                HsButton captainButton = textAction(cx, y, limit - cx,
                    Component.translatable("hearthstead.captain.panel.open"),
                    () -> com.hearthstead.client.captain.CaptainClient.open(settler));
                tip(captainButton, Component.translatable("hearthstead.captain.panel.open_tip"));
            }
        } else if (profession.martial()) {
            // Battle roles take live field orders: R for melee troops, G for ranged ones.
            String key = switch (profession) {
                case ARCHER, RUNE_MAGE -> "key.hearthstead.command_ranged";
                default -> "key.hearthstead.command_melee";
            };
            guardOrderButton = textAction(x, y, limit - x,
                Component.translatable("hearthstead.guard.command.open"), this::openGuardOrders);
            guardOrderButton.active = false;
            guardOrderButton.setTooltip(Tooltip.create(Component.translatable(
                "hearthstead.guard.command.role_tip", Component.keybind(key))));
        }

        // Footer: Summon, Locate, Mark on map; Dismiss red at the right.
        UiRect f = l.footer;
        int fy = top + f.y() + (FOOTER_H - FOOTER_BTN_H) / 2;
        actionH = FOOTER_BTN_H;
        int fx = left + f.x();
        int fRight = left + f.right();
        if (profession.employed()) {
            Component label = Component.translatable("hearthstead.employ.dismiss");
            int w = Ui2Button.textWidth(font, label);
            dismissButton = Ui2Button.dangerText(fRight - w, fy, w, FOOTER_BTN_H, label,
                () -> act(SettlerActionPayload.Kind.DISMISS));
            dismissButton.active = manage;
            tip(dismissButton, view.dismissTooltip);
            addRenderableWidget(dismissButton);
            fRight -= w + GUTTER;
        }
        summonButton = textAction(fx, fy, fRight - fx,
            Component.translatable("hearthstead.settler.sheet.summon"), this::summon);
        refreshSummonButton();
        fx += summonButton.getWidth() + GUTTER;
        locateButton = textAction(fx, fy, fRight - fx,
            Component.translatable("hearthstead.settler.sheet.locate"), () -> act(SettlerActionPayload.Kind.LOCATE));
        locateButton.active = manage;
        tip(locateButton, Component.translatable("hearthstead.settler.control.locate.tip"));
        fx += locateButton.getWidth() + GUTTER;
        mapButton = textAction(fx, fy, fRight - fx,
            Component.translatable("hearthstead.settler.sheet.map"), this::markOnMap);
        mapButton.active = snapshot != null;
        tip(mapButton, Component.translatable("hearthstead.settler.sheet.map.tip", title));
        footerHintX = fx + mapButton.getWidth() + GUTTER + 4 - left;
        actionH = LINK_BTN_H;
        footerHintRight = fRight - left;

        // The retired Mayor seat keeps an inert, hidden slot so focus bookkeeping is stable.
        Component appoint = Component.translatable("hearthstead.settler.appoint");
        appointButton = Ui2Button.secondary(left + f.x(), fy, Ui2Button.textWidth(font, appoint), TEXT_BUTTON_H,
            appoint, () -> act(SettlerActionPayload.Kind.APPOINT));
        appointButton.active = false;
        appointButton.visible = false;
        appointButton.setTooltip(Tooltip.create(view.appointTooltip));
        addRenderableWidget(appointButton);
    }

    /** Where the muted "Shift + right-click: inventory" hint may sit in the footer (panel-local). */
    private int footerHintX;
    private int footerHintRight;

    /** Framed action buttons: 16 px in the footer, 14 px for the Work links. */
    static final int FOOTER_BTN_H = 16;
    static final int LINK_BTN_H = 14;
    private int actionH = LINK_BTN_H;

    private HsButton textAction(int x, int y, int maxW, Component label, Runnable action) {
        int w = Math.max(16, Math.min(Ui2Button.textWidth(font, label), Math.max(16, maxW)));
        HsButton b = Ui2Button.secondary(x, y, w, actionH, label, action);
        addRenderableWidget(b);
        return b;
    }

    /**
     * QA Q-024: an enabled button explains what it does; a disabled one says
     * why it is disabled (still loading, or not a settler this player may
     * direct: a traveler outside the settlement, spectator or adventure mode).
     */
    private void tip(HsButton button, Component actionTip) {
        button.setTooltip(Tooltip.create(button.active ? actionTip : disabledReason()));
    }

    private Component disabledReason() {
        return Component.translatable(snapshot == null ? "hearthstead.settler.loading"
            : "hearthstead.settler.sheet.reason.not_managed");
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && layout != null && layout.portrait.contains(mouseX - left, mouseY - top)) {
            draggingPortrait = true;
            dragLastX = mouseX;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (draggingPortrait) {
            portraitOffset += (float) (mouseX - dragLastX) * 2.2F;
            dragLastX = mouseX;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (draggingPortrait && button == 0) {
            draggingPortrait = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ================================================================ actions ===

    private boolean appointEnabled() {
        return snapshot != null && snapshot.canManage() && !snapshot.isMayor()
            && (!snapshot.mourning() || snapshot.mayorVacant());
    }

    private Component appointTooltip() {
        if (snapshot == null || !snapshot.canManage()) {
            return Component.translatable("hearthstead.settler.appoint.tip.no_settlement");
        }
        if (snapshot.isMayor()) {
            return Component.translatable("hearthstead.mayor.refused.already");
        }
        if (snapshot.mourning() && !snapshot.mayorVacant()) {
            return Component.translatable("hearthstead.mayor.refused.mourning");
        }
        return Component.translatable(snapshot.mourning()
                ? "hearthstead.settler.appoint.tip.mourning_vacancy"
                : "hearthstead.settler.appoint.tip", title, boonName());
    }

    private void act(SettlerActionPayload.Kind kind) {
        if (snapshot != null) {
            PacketDistributor.sendToServer(
                new SettlerActionPayload(snapshot.entityId(), snapshot.settlerId(),
                    snapshot.sessionId(), kind, snapshot.revision()));
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

    /** The Banner's map opens on this settler the next time it is opened (map lane hook). */
    private void markOnMap() {
        HearthScreen.requestMapFocus(settler.getUUID());
        if (minecraft != null && minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.translatable(
                "hearthstead.settler.sheet.map.done", title), true);
            HsUi.playConfirmSound();
        }
    }

    /** Summon (command lane): the settler is outlined and walks or runs to you. */
    private void summon() {
        if (SummonClient.unavailableReason(settler).isEmpty()) {
            SummonClient.request(settler);
            HsUi.playConfirmSound();
            refreshSummonButton();
        }
    }

    /**
     * Client-known refusals disable the button with their reason (asleep,
     * already summoned); server-only ones (other dimension, no route, no
     * permission) come back on the action bar after the click.
     */
    private void refreshSummonButton() {
        if (summonButton == null) {
            return;
        }
        java.util.Optional<Component> reason = snapshot == null || !snapshot.canManage()
            ? java.util.Optional.of(disabledReason()) : SummonClient.unavailableReason(settler);
        boolean active = snapshot != null && reason.isEmpty();
        String shown = reason.map(Component::getString).orElse("");
        if (active != summonButton.active || !shown.equals(summonReasonShown)) {
            summonButton.active = active;
            summonReasonShown = shown;
            summonButton.setTooltip(Tooltip.create(reason.orElse(
                Component.translatable("hearthstead.settler.sheet.summon.tip", title))));
        }
    }

    // ================================================================== render ===

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        if (hsIntro == null) hsIntro = new HsMotion.ScreenIntro();
        if (!hsIntroRendering && !hsIntro.done()) {
            hsIntroRendering = true;
            try {
                hsIntro.render(g, 4.0F, () -> render(g, mouseX, mouseY, partialTick));
            } finally {
                hsIntroRendering = false;
            }
            return;
        }
        // A light scrim keeps the world readable behind the sheet without
        // paying Minecraft's full-screen blur every frame.
        Ui2Surface.scrim(g, 0, 0, width, height);
        pendingTooltip = null;
        SheetView view = view();
        refreshLiveText(view);
        advanceTurntable();

        drawChrome(g, view);
        drawHeader(g, mouseX, mouseY, view);
        drawPortrait(g, mouseX, mouseY);
        drawNeeds(g, mouseX, mouseY, view);
        drawOnePage(g, mouseX, mouseY, view);

        HsUi.widgets(this, g, mouseX, mouseY, partialTick);

        if (pendingTooltip != null && !pendingTooltip.isEmpty()) {
            g.renderComponentTooltip(font, pendingTooltip, mouseX, mouseY);
        }
    }

    /** Idle sway (a slow look around) plus whatever the player dragged in. */
    private void advanceTurntable() {
        long now = Util.getMillis();
        float dt = lastFrameMs < 0 ? 0.0F : Math.min(0.1F, (now - lastFrameMs) / 1000.0F);
        lastFrameMs = now;
        if (!draggingPortrait && HsMotion.enabled) {
            swayPhase += dt * 0.5F;
        }
    }

    private float portraitAngle() {
        return 20.0F + portraitOffset + 32.0F * Mth.sin(swayPhase);
    }

    // ---- draw: begin (paint only; every label below was fitted in buildView) ----

    private void drawChrome(GuiGraphics g, SheetView view) {
        Layout l = view.layout;
        BannerChrome.panel(g, left, top, l.panelWidth, l.totalHeight);
        int ruleY = top + l.headerRuleY;
        g.fill(left + l.header.x(), ruleY, left + l.header.right(), ruleY + 1, BannerChrome.PLATE_SHADOW);
        g.fill(left + l.header.x(), ruleY + 1, left + l.header.right(), ruleY + 2, BannerChrome.PLATE_HIGHLIGHT);
        for (int i = 0; i < l.counters.length; i++) {
            UiRect c = l.counters[i];
            // No trade (unassigned, Mayor): no Level counter, not an empty box.
            if (c != null && (i == 0 || view.showLevel)) {
                BannerChrome.counterBox(g, left + c.x(), top + c.y(), c.width(), c.height());
            }
        }
        BannerChrome.parchment(g, left + l.left.x(), top + l.left.y(), l.left.width(), l.left.height());
        crest(g, left + l.crest.x(), top + l.crest.y(), l.crest.width(), l.crest.height(),
            settler.getProfession());
    }

    private void drawHeader(GuiGraphics g, int mouseX, int mouseY, SheetView view) {
        Layout l = view.layout;
        UiRect t = l.title;
        nameText.fit(font, view.name, t.width());
        nameText.draw(g, font, left + t.x(), top + t.y() + 5, BannerChrome.TEXT_ON_WOOD);
        g.drawString(font, view.subtitle.fit().text(), left + t.x(), top + t.y() + 16,
            BannerChrome.TEXT_ON_WOOD_MUTED, false);
        if (view.subtitle.clipped() && hover(mouseX, mouseY, left + t.x(), top + t.y() + 15, t.width(), 10)) {
            pendingTooltip = view.subtitle.tooltip();
        }
        if (nameText.width() > 0 && view.nameClippedTooltip != null
            && hover(mouseX, mouseY, left + t.x(), top + t.y() + 2, t.width(), 13)) {
            pendingTooltip = view.nameClippedTooltip;
        }

        // Counter 0: health (live), counter 1: trade level or combat tier.
        UiRect c0 = l.counters[0];
        int cx = left + c0.x();
        int cy = top + c0.y();
        g.blitSprite(HEART_CONTAINER, cx + 5, cy + 7, 9, 9);
        g.blitSprite(HEART, cx + 5, cy + 7, 9, 9);
        drawCounterText(g, l, cx, cy, view.healthCaption, live.healthShort);
        if (hover(mouseX, mouseY, cx, cy, c0.width(), c0.height())) {
            pendingTooltip = live.healthTooltip;
        }
        UiRect c1 = l.counters[1];
        if (c1 != null && view.showLevel) {
            int lx = left + c1.x();
            int ly = top + c1.y();
            JobIcons.draw(g, settler.getProfession(), lx + 3, ly + 3, 16);
            drawCounterText(g, l, lx, ly, view.levelCaption, view.levelValue);
            if (hover(mouseX, mouseY, lx, ly, c1.width(), c1.height())) {
                pendingTooltip = view.trade.tooltip();
            }
        }
    }

    private void drawCounterText(GuiGraphics g, Layout l, int x, int y, HsUi.FittedLabel caption,
                                 HsUi.FittedLabel value) {
        if (l.narrowCounters) {
            g.drawString(font, value.text(), x + 21, y + 8, BannerChrome.TEXT_ON_WOOD, false);
        } else {
            g.drawString(font, caption.text(), x + 21, y + 3, BannerChrome.TEXT_ON_WOOD_MUTED, false);
            g.drawString(font, value.text(), x + 21, y + 12, BannerChrome.TEXT_ON_WOOD, false);
        }
    }

    private void drawPortrait(GuiGraphics g, int mouseX, int mouseY) {
        UiRect p = layout.portrait;
        int x = left + p.x();
        int y = top + p.y();
        // Slim walnut frame and a light inner line, then the settler on inset linen.
        g.fill(x - 2, y - 2, x + p.width() + 2, y + p.height() + 2, BannerChrome.PLATE_HIGHLIGHT);
        g.fill(x - 1, y - 1, x + p.width() + 1, y + p.height() + 1, Ui2Palette.RULE);
        g.fill(x, y, x + p.width(), y + p.height(), Ui2Palette.INSET);
        int groundY = y + p.height() - Math.max(6, p.height() / 9);
        g.fill(x + 4, groundY, x + p.width() - 4, groundY + 1, Ui2Palette.RULE);
        float scale = Math.min(p.height() * 0.33F, p.width() * 0.62F);
        float angle = portraitAngle();
        g.enableScissor(x, y, x + p.width(), y + p.height());
        SettlerRenderer.withoutPortraitLabels(settler, () -> renderTurned(g,
            x + p.width() / 2.0F, groundY - scale * settler.getBbHeight() / 2.0F, scale, angle));
        g.disableScissor();
        if (hover(mouseX, mouseY, x, y, p.width(), p.height()) && !draggingPortrait) {
            pendingTooltip = cachedView.portraitTooltip;
        }
    }

    private void drawNeeds(GuiGraphics g, int mouseX, int mouseY, SheetView view) {
        UiRect n = view.layout.needs;
        int x = left + n.x();
        int y = top + n.y();
        // 2 x 2: Health, Hunger / Energy, Morale.
        int w = (n.width() - 8) / 2;
        int x2 = x + w + 8;
        int y2 = y + NEED_ROW + 2;
        float maximum = settler.getMaxHealth();
        float healthRatio = maximum > 0.0F ? settler.getHealth() / maximum : 0.0F;
        drawNeedRow(g, x, y, w, view.needLabels[0], live.healthShort, healthRatio,
            healthColour(healthRatio), -1.0F);
        if (live.wellFedHealing) {
            // A small forest plus beside "Health": healing from a full belly.
            int px = x + view.needLabels[0].width() + 3;
            g.fill(px + 1, y + 1, px + 2, y + 6, Ui2Palette.FOREST);
            g.fill(px, y + 3, px + 3, y + 4, Ui2Palette.FOREST);
        }
        if (hover(mouseX, mouseY, x, y, w, NEED_ROW)) {
            pendingTooltip = live.healthTooltip;
        }
        float hunger = settler.getHunger();
        float energy = settler.getEnergy();
        float morale = settler.getMorale();
        drawNeedRow(g, x2, y, w, view.needLabels[1], needValueCache.valueFor(0, hunger, font),
            hunger / 100.0F, needColour(hunger / 100.0F), SettlerEntity.WELL_FED_HUNGER / 100.0F);
        if (hover(mouseX, mouseY, x2, y, w, NEED_ROW)) {
            pendingTooltip = view.hungerTooltip;
        }
        drawNeedRow(g, x, y2, w, view.needLabels[2], needValueCache.valueFor(1, energy, font),
            energy / 100.0F, needColour(energy / 100.0F), -1.0F);
        if (hover(mouseX, mouseY, x, y2, w, NEED_ROW)) {
            pendingTooltip = view.energyTooltip;
        }
        drawNeedRow(g, x2, y2, w, view.needLabels[3], needValueCache.valueFor(2, morale, font),
            morale / 100.0F, needColour(morale / 100.0F), -1.0F);
        if (hover(mouseX, mouseY, x2, y2, w, NEED_ROW)) {
            pendingTooltip = view.moraleTooltip;
        }
    }

    private void drawNeedRow(GuiGraphics g, int x, int y, int w, HsUi.FittedLabel label,
                             HsUi.FittedLabel value, float ratio, int colour, float tick) {
        g.drawString(font, label.text(), x, y, Ui2Palette.INK_MUTED, false);
        g.drawString(font, value.text(), x + w - value.width(), y,
            colour == Ui2Palette.DANGER ? Ui2Palette.DANGER : Ui2Palette.INK, false);
        Ui2Surface.progress(g, x, y + 9, w, ratio, colour);
        if (tick > 0.0F) {
            int tx = x + Math.round(w * tick);
            g.fill(tx, y + 8, tx + 1, y + 11, Ui2Palette.INK_MUTED);
        }
    }

    private void drawOnePage(GuiGraphics g, int mouseX, int mouseY, SheetView view) {
        UiRect p = layout.page;
        int ox = left + p.x();
        int oy = top + p.y();
        int w = p.width();
        int ph = p.height();
        int colW = columnWidth(w);
        int mx = mouseX;
        int my = mouseY;

        // ---- left column: general knowledge -------------------------------------------------
        int tx = ox + PORTRAIT_W + 8;
        int tw = colW - PORTRAIT_W - 8;
        g.enableScissor(ox, oy - 1, ox + colW, oy + ph);
        TradeView trade = view.trade;
        if (trade.shown()) {
            g.drawString(font, trade.level().text(), tx, oy + L_TRADE, Ui2Palette.INK, false);
            Ui2Surface.progress(g, tx, oy + L_TRADE + 10, tw, trade.progress(), Ui2Palette.GOLD);
            if (hover(mx, my, tx, oy + L_TRADE, tw, 13)) pendingTooltip = trade.tooltip();
        }
        statusGlyph(g, live.status, tx, oy + L_STATUS + 1);
        g.drawString(font, live.statusWord.text(), tx + 9, oy + L_STATUS, live.statusColour, false);
        g.drawString(font, view.nextAction.fit().text(), tx, oy + L_NEXT, view.nextActionColour, false);
        g.drawString(font, live.activity.text(), tx, oy + L_ACTIVITY, Ui2Palette.INK_MUTED, false);
        if (hover(mx, my, tx, oy + L_STATUS - 1, tw, 32)) pendingTooltip = view.statusTooltip;

        int y = oy + L_WORK;
        g.renderItem(view.workplaceIcon, ox, y);
        g.drawString(font, view.workplaceName.fit().text(), ox + 20, y + 4, Ui2Palette.INK, false);
        if (hover(mx, my, ox, y, colW, 16)) pendingTooltip = view.workplaceTooltip;
        y = oy + L_HOME;
        g.renderItem(BED_ICON, ox, y);
        g.drawString(font, view.homeName.fit().text(), ox + 20, y + 4,
            view.homeWarn ? Ui2Palette.DANGER : Ui2Palette.INK, false);
        if (hover(mx, my, ox, y, colW, 16)) pendingTooltip = view.homeTooltip;
        g.disableScissor();

        // ---- right column: the job's attributes ---------------------------------------------
        int rx = ox + colW + COL_GAP;
        Ui2Surface.ruleVertical(g, rx - COL_GAP / 2, oy, Math.min(ph, BLESS_Y - 6));
        g.enableScissor(rx, oy - 1, ox + w, oy + ph);
        heading(g, 5, view.headJobFocus, rx, oy + R_FOCUS, colW);
        y = oy + R_FOCUS + SECTION_H;
        int shownImpacts = 0;
        for (JobImpactView impact : view.jobImpacts) {
            if (shownImpacts == 2) break;
            g.fill(rx, y, rx + 2, y + 19, impact.highlight().colour());
            g.drawString(font, impact.label().text(), rx + 5, y, impact.highlight().colour(), false);
            g.drawString(font, impact.effect().text(), rx + 5, y + 10,
                impact.live() ? Ui2Palette.INK : Ui2Palette.INK_MUTED, false);
            if (hover(mx, my, rx, y, colW, 20)) pendingTooltip = impact.tooltip();
            y += 22;
            shownImpacts++;
        }
        if (shownImpacts == 0) {
            g.drawString(font, view.noFocus.text(), rx, y, Ui2Palette.INK_MUTED, false);
        }
        heading(g, 4, view.headAttributes, rx, oy + R_ATTR, colW);
        if (snapshot == null) {
            g.drawString(font, view.loading.text(), rx, oy + R_ATTR_CELLS, Ui2Palette.INK_MUTED, false);
        } else {
            int cellW = (colW - 8) / 2;
            for (int i = 0; i < view.attributes.length; i++) {
                AttributeView a = view.attributes[i];
                int cx = rx + (i % 2) * (cellW + 8);
                int cy = oy + R_ATTR_CELLS + (i / 2) * ATTR_ROW_H;
                if (cy + 9 > oy + ph) break;
                int labelColour = a.highlight() == JobAttributeHighlight.PRIMARY ? Ui2Palette.FOREST
                    : a.highlight() == JobAttributeHighlight.SECONDARY ? Ui2Palette.GOLD : Ui2Palette.INK;
                g.fill(cx, cy, cx + 1, cy + 8, a.highlight().isJobFocus() ? a.highlight().colour() : Ui2Palette.RULE);
                g.drawString(font, a.label().text(), cx + 3, cy, labelColour, false);
                if (a.knack()) Ui2Surface.alertGlyph(g, cx + cellW - a.value().width() - 6, cy + 2, Ui2Palette.GOLD);
                g.drawString(font, a.value().text(), cx + cellW - a.value().width(), cy, Ui2Palette.INK, false);
                if (hover(mx, my, cx, cy - 1, cellW, ATTR_ROW_H)) pendingTooltip = a.tooltip();
            }
        }
        if (R_MORE + 9 <= ph) {
            g.drawString(font, view.moreHint.text(), rx, oy + R_MORE, Ui2Palette.INK_MUTED, false);
            if (hover(mx, my, rx, oy + R_MORE - 1, view.moreHint.width() + 4, 10)) pendingTooltip = view.moreTooltip;
        }
        g.disableScissor();

        // ---- Blessings: the seals bound to this settler (display only) ----------------------
        if (BLESS_Y + 18 <= ph) {
            y = oy + BLESS_Y;
            Ui2Surface.rule(g, ox, y - 3, w);
            heading(g, 3, view.headBlessings, ox, y, 64);
            int bx = ox + 66;
            for (int i = 0; i < view.blessingStacks.length; i++) {
                Ui2Surface.slotWell(g, bx, y);
                if (view.blessingActive[i]) {
                    g.renderItem(view.blessingStacks[i], bx + 1, y + 1);
                } else {
                    g.fill(bx + 1, y + 1, bx + SLOT - 1, y + SLOT - 1, 0x22000000);
                }
                if (hover(mx, my, bx, y, SLOT, SLOT)) pendingTooltip = view.blessingTips[i];
                bx += SLOT + 2;
            }
            g.drawString(font, view.blessingSummary.text(), bx + 6, y + 5,
                view.anyBlessing ? Ui2Palette.GOLD : Ui2Palette.INK_MUTED, false);
            if (hover(mx, my, bx + 6, y, view.blessingSummary.width(), SLOT)) pendingTooltip = view.blessingHowTo;
        }

        // Footer hint: the inventory now opens with Shift + right-click on the settler.
        int hintRoom = footerHintRight - footerHintX;
        if (hintRoom > 20) {
            int hx = left + footerHintRight - Math.min(hintRoom, view.inventoryHint.width());
            g.drawString(font, view.inventoryHint.text(), hx,
                top + layout.footer.y() + (FOOTER_H - 8) / 2, Ui2Palette.INK_MUTED, false);
        }
    }

    /** Serif small-caps section heading with a hairline to the column's edge. */
    private void heading(GuiGraphics g, int slot, String text, int x, int y, int w) {
        Ui2Serif.Text t = headings[slot];
        if (t == null) {
            t = new Ui2Serif.Text(Ui2Serif.Size.HEADING);
            headings[slot] = t;
        }
        t.fit(font, text, w);
        t.draw(g, font, x, y + 2, Ui2Palette.INK_SOFT);
        int rx = x + t.width() + 5;
        if (rx < x + w) Ui2Surface.rule(g, rx, y + 6, x + w - rx);
    }

    private static void lockMark(GuiGraphics g, int x, int y) {
        g.pose().pushPose();
        g.pose().translate(0.0F, 0.0F, 250.0F);
        g.fill(x + 1, y + 1, x + 17, y + 17, 0x40A0302A);
        g.fill(x + 10, y + 9, x + 18, y + 18, Ui2Palette.PAPER);
        BannerChrome.outline(g, x + 10, y + 9, 8, 9, Ui2Palette.DANGER);
        Ui2Surface.lockGlyph(g, x + 12, y + 10, Ui2Palette.DANGER);
        g.pose().popPose();
    }

    /** A faint outline of what belongs in an empty worn slot. */
    private static void emptySlotHint(GuiGraphics g, int index, int x, int y) {
        int c = 0x40806848;
        switch (index) {
            case 0 -> { g.fill(x + 5, y + 5, x + 13, y + 7, c); g.fill(x + 4, y + 7, x + 6, y + 11, c); g.fill(x + 12, y + 7, x + 14, y + 11, c); }
            case 1 -> { g.fill(x + 4, y + 4, x + 14, y + 6, c); g.fill(x + 6, y + 6, x + 12, y + 14, c); }
            case 2 -> { g.fill(x + 5, y + 4, x + 13, y + 7, c); g.fill(x + 5, y + 7, x + 8, y + 14, c); g.fill(x + 10, y + 7, x + 13, y + 14, c); }
            case 3 -> { g.fill(x + 4, y + 9, x + 8, y + 14, c); g.fill(x + 10, y + 9, x + 14, y + 14, c); }
            case 4 -> { for (int i = 0; i < 9; i++) g.fill(x + 4 + i, y + 13 - i, x + 6 + i, y + 14 - i, c); }
            default -> { g.fill(x + 5, y + 4, x + 13, y + 12, c); g.fill(x + 7, y + 12, x + 11, y + 14, c); }
        }
    }

    /** Status glyph paired with the status colour, as on the map card. */
    private static void statusGlyph(GuiGraphics g, RealmMapStatus status, int x, int y) {
        int colour = HearthScreen.statusColor(status);
        switch (status) {
            case WORKING -> Ui2Surface.checkGlyph(g, x, y, colour);
            case WALKING, SLEEPING, IDLE -> Ui2Surface.pendingGlyph(g, x, y, colour);
            default -> Ui2Surface.alertGlyph(g, x, y, colour);
        }
    }

    /** Burgundy crest hanging from the frame (the kit's cloth), carrying the job icon as its device. */
    private static void crest(GuiGraphics g, int x, int y, int w, int h, Profession profession) {
        int top = BannerChrome.crestCloth(g, x, y, w, h);
        // The device: the job icon on a small linen roundel.
        int cx = x + (w - 16) / 2;
        int cy = top + 6;
        g.fill(cx - 1, cy, cx + 17, cy + 16, BannerChrome.LINEN);
        g.fill(cx, cy - 1, cx + 16, cy + 17, BannerChrome.LINEN);
        JobIcons.draw(g, profession, cx, cy, 16);
    }

    // ---- draw: end -------------------------------------------------------------

    /** Draws the live settler turned to {@code angle} degrees (0 = facing you). */
    private void renderTurned(GuiGraphics g, float cx, float cy, float scale, float angle) {
        Quaternionf pose = new Quaternionf().rotateZ((float) Math.PI);
        Quaternionf camera = new Quaternionf();
        pose.mul(camera);
        float bodyRot = settler.yBodyRot;
        float bodyRotO = settler.yBodyRotO;
        float yRot = settler.getYRot();
        float xRot = settler.getXRot();
        float headRotO = settler.yHeadRotO;
        float headRot = settler.yHeadRot;
        settler.yBodyRot = 180.0F + angle;
        settler.yBodyRotO = settler.yBodyRot;
        settler.setYRot(180.0F + angle);
        settler.setXRot(0.0F);
        settler.yHeadRot = settler.getYRot();
        settler.yHeadRotO = settler.getYRot();
        float entityScale = settler.getScale();
        Vector3f offset = new Vector3f(0.0F, settler.getBbHeight() / 2.0F, 0.0F);
        try {
            InventoryScreen.renderEntityInInventory(g, cx, cy, scale / entityScale, offset, pose, camera, settler);
        } finally {
            settler.yBodyRot = bodyRot;
            settler.yBodyRotO = bodyRotO;
            settler.setYRot(yRot);
            settler.setXRot(xRot);
            settler.yHeadRotO = headRotO;
            settler.yHeadRot = headRot;
        }
    }

    private boolean lockedFor(ItemStack stack) {
        return GearGate.relevant(settler, stack) && !GearGate.allows(settler, stack);
    }

    /** Hover-only: the item's own tooltip plus its Gear Tier verdict. */
    private List<Component> gearTooltip(ItemStack stack) {
        List<Component> lines = new ArrayList<>(minecraft == null || minecraft.level == null
            ? List.of(stack.getHoverName()) : Screen.getTooltipFromItem(minecraft, stack));
        if (!GearGate.relevant(settler, stack)) {
            return lines;
        }
        GearTier tier = GearTiers.gearTierOf(stack);
        GearGate.Clearance c = GearGate.clearance(settler);
        if (c.allows(tier.level())) {
            lines.add(Component.translatable("hearthstead.gear.item.ok", tier.level(),
                tier.displayName()).withStyle(ChatFormatting.DARK_GREEN));
        } else {
            lines.add(Component.translatable("hearthstead.gear.item.locked", tier.level(),
                tier.displayName()).withStyle(ChatFormatting.RED));
            lines.add(Component.translatable("hearthstead.gear.item.needs",
                GearGate.missing(c, tier)).withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    private static int needColour(float ratio) {
        return ratio < 0.25F ? Ui2Palette.DANGER : ratio < 0.5F ? Ui2Palette.AMBER : Ui2Palette.FOREST;
    }

    private static int healthColour(float ratio) {
        return ratio <= 0.30F ? Ui2Palette.DANGER : ratio < 0.65F ? Ui2Palette.AMBER : Ui2Palette.FOREST;
    }

    private static boolean hover(int mouseX, int mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    // ============================================================ live text ===

    /**
     * Text that follows live entity data between snapshots: the map status
     * word, the activity, and health. Rebuilt only when its input changes.
     */
    private static final class LiveText {
        int statusId = Integer.MIN_VALUE;
        int activityId = Integer.MIN_VALUE;
        int activityWidth = -1;
        RealmMapStatus status = RealmMapStatus.IDLE;
        HsUi.FittedLabel statusWord = new HsUi.FittedLabel(Component.empty(), 0);
        int statusColour = Ui2Palette.INK_MUTED;
        HsUi.FittedLabel activity = new HsUi.FittedLabel(Component.empty(), 0);
        int healthTenths = -1;
        int maxTenths = -1;
        boolean wellFedHealing;
        HsUi.FittedLabel healthShort = new HsUi.FittedLabel(Component.empty(), 0);
        List<Component> healthTooltip = List.of();
        String language = "";
    }

    private void refreshLiveText(SheetView view) {
        MarkerTrack track = RealmMapClient.track(settler.getUUID());
        SettlerActivity activity = settler.getActivity();
        RealmMapStatus status = track != null ? RealmMapStatus.byWireId(track.statusId)
            : RealmMapStatus.classify(activity, false, false);
        String language = currentLanguage();
        int textWidth = view.statusTextWidth;
        if (status.ordinal() != live.statusId || activity.ordinal() != live.activityId
            || textWidth != live.activityWidth || !language.equals(live.language)) {
            live.statusId = status.ordinal();
            live.activityId = activity.ordinal();
            live.activityWidth = textWidth;
            live.status = status;
            live.statusWord = HsUi.fitLabel(font, Component.literal(HearthScreen.statusWord(status)),
                Math.max(1, textWidth - 9));
            live.statusColour = HearthScreen.statusColor(status);
            live.activity = HsUi.fitLabel(font, activity.displayName(), Math.max(1, textWidth));
        }
        float current = settler.getHealth();
        float maximum = settler.getMaxHealth();
        int tenths = Math.round(current * 10.0F);
        int maxTenths = Math.round(maximum * 10.0F);
        boolean healing = current < maximum && settler.getHunger() >= SettlerEntity.WELL_FED_HUNGER;
        if (tenths != live.healthTenths || maxTenths != live.maxTenths || healing != live.wellFedHealing
            || !language.equals(live.language)) {
            live.healthTenths = tenths;
            live.maxTenths = maxTenths;
            live.wellFedHealing = healing;
            live.healthShort = HsUi.fitLabel(font, Component.literal(
                Math.round(current) + "/" + Math.round(maximum)), 40);
            List<Component> tip = new ArrayList<>(3);
            tip.add(Component.translatable("hearthstead.gui.health_current_max",
                String.format(java.util.Locale.ROOT, "%.1f", tenths / 10.0F),
                String.format(java.util.Locale.ROOT, "%.1f", maxTenths / 10.0F)));
            tip.add(Component.translatable(healing ? "hearthstead.settler.sheet.well_fed.healing"
                : "hearthstead.settler.sheet.well_fed.rule").withStyle(healing
                    ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            live.healthTooltip = List.copyOf(tip);
        }
        live.language = language;
    }

    // ============================================================== view model ===

    private SheetView view() {
        String language = currentLanguage();
        int combatExperience = settler.combatExperience();
        int carryCapacity = settler.getCarryCapacity();
        if (cachedView == null || cachedViewStopReason != settler.logisticsStopReason()
            || cachedViewWorking != isWorkingActivity()
            || cachedViewTradeXp != settler.tradeXp()
            || cachedViewLifeNeed != settler.lifeNeed()
            || cachedViewClearance != settler.gearClearancePacked()
            || cachedViewBuildSites != com.hearthstead.client.builder.BuildSitesClient.version()
            || !viewCacheMatches(cachedViewSnapshot,
            cachedViewWidth, cachedViewHeight, cachedViewCombatExperience,
            cachedViewCarryCapacity, cachedViewFont, cachedViewLanguage,
            snapshot, width, height, combatExperience, carryCapacity, font,
            language)) {
            SheetView rebuilt = buildView();
            cachedViewSnapshot = snapshot;
            cachedViewWidth = width;
            cachedViewHeight = height;
            cachedViewCombatExperience = combatExperience;
            cachedViewCarryCapacity = carryCapacity;
            cachedViewStopReason = settler.logisticsStopReason();
            cachedViewWorking = isWorkingActivity();
            cachedViewTradeXp = settler.tradeXp();
            cachedViewLifeNeed = settler.lifeNeed();
            cachedViewClearance = settler.gearClearancePacked();
            cachedViewBuildSites = com.hearthstead.client.builder.BuildSitesClient.version();
            cachedViewFont = font;
            cachedViewLanguage = language;
            cachedView = rebuilt;
        }
        return cachedView;
    }

    private void invalidateView() {
        cachedView = null;
    }

    private String currentLanguage() {
        return minecraft == null ? ""
            : minecraft.getLanguageManager().getSelected();
    }

    /** Pure cache key for this screen's immutable render view. */
    static boolean viewCacheMatches(Object cachedSnapshot, int cachedWidth,
                                    int cachedHeight, int cachedCombatExperience,
                                    int cachedCarryCapacity, Object cachedFont,
                                    String cachedLanguage,
                                    Object snapshot, int width, int height,
                                    int combatExperience, int carryCapacity,
                                    Object font, String language) {
        return cachedSnapshot == snapshot
            && cachedWidth == width
            && cachedHeight == height
            && cachedCombatExperience == combatExperience
            && cachedCarryCapacity == carryCapacity
            && cachedFont == font
            && cachedLanguage.equals(language);
    }

    /** Green status is reserved for a recorded work motion. */
    private boolean isWorkingActivity() {
        String activity = settler.getActivity().name();
        return activity.startsWith("WORK_")
            || activity.equals("HAULING_LOG") || activity.equals("CARRYING")
            || activity.equals("SORTING") || activity.equals("GATHERING_LOG")
            || activity.equals("COLLECTING_ITEMS")
            || activity.equals("STORE_CRAFT_OUTPUT");
    }

    /** Fits {@code text} into {@code width}; keeps the full text for a hover when cut. */
    private Txt txt(Component text, int width) {
        HsUi.FittedLabel fit = HsUi.fitLabel(font, text, Math.max(1, width));
        boolean clipped = fit.text() != text;
        return new Txt(fit, clipped, clipped ? List.of(text) : List.of());
    }

    /** All allocations here are paid only when {@link #view()} invalidates. */
    private SheetView buildView() {
        Layout layout = layoutFor(width, height);
        SheetView v = new SheetView();
        v.layout = layout;
        Profession profession = settler.getProfession();
        boolean mayor = snapshot != null && snapshot.isMayor();
        Component noProfession = Component.translatable("hearthstead.profession.none");
        Component job = profession.employed() ? profession.displayName() : noProfession;
        Component building = buildingName();
        int pageW = layout.page.width();
        boolean wide = layout.widePage;
        int colW = wide ? (pageW - 16) / 2 : pageW;

        // -- header ---------------------------------------------------------------
        v.name = settler.getSettlerName();
        MutableComponent subtitle = Component.empty().append(mayor ? Component.translatable(
            "hearthstead.settler.mayor_mark") : job);
        if (profession.martial() && snapshot != null) {
            Component rank = rankName(profession);
            if (rank != null) subtitle.append("  ·  ").append(rank);
        }
        if (mayor && profession.employed()) {
            subtitle.append("  ·  ").append(job);
        }
        v.subtitle = txt(subtitle, layout.title.width());
        v.nameClippedTooltip = new Ui2Serif.Text(Ui2Serif.Size.TITLE).set(font, v.name).width()
            > layout.title.width() ? List.of(Component.literal(v.name)) : null;
        int counterText = layout.counters[0].width() - 22;
        v.healthCaption = HsUi.fitLabel(font, Component.translatable("hearthstead.settler.sheet.health"),
            Math.max(1, counterText));
        v.portraitTooltip = List.of(Component.translatable("hearthstead.settler.sheet.portrait.tip")
            .withStyle(ChatFormatting.GRAY));

        // -- needs (left column) ------------------------------------------------------
        int needW = layout.needs.width();
        v.needLabels = new HsUi.FittedLabel[]{
            HsUi.fitLabel(font, Component.translatable("hearthstead.settler.sheet.health"), needW - 30),
            HsUi.fitLabel(font, Component.translatable("hearthstead.gui.hunger"), needW - 24),
            HsUi.fitLabel(font, Component.translatable("hearthstead.gui.energy"), needW - 24),
            HsUi.fitLabel(font, Component.translatable("hearthstead.gui.morale"), needW - 24)
        };
        v.hungerTooltip = List.of(Component.translatable("hearthstead.gui.hunger"),
            Component.translatable("hearthstead.settler.sheet.hunger.tip",
                Math.round(SettlerEntity.WELL_FED_HUNGER)).withStyle(ChatFormatting.GRAY));
        v.energyTooltip = List.of(Component.translatable("hearthstead.gui.energy"),
            Component.translatable("hearthstead.settler.sheet.energy.tip").withStyle(ChatFormatting.GRAY));
        v.moraleTooltip = List.of(Component.translatable("hearthstead.gui.morale"),
            Component.translatable("hearthstead.settler.sheet.morale.tip").withStyle(ChatFormatting.GRAY));

        // -- section headings -------------------------------------------------------------
        v.headRightNow = Component.translatable("hearthstead.settler.sheet.right_now").getString();
        v.headWork = Component.translatable("hearthstead.settler.sheet.work").getString();
        v.headHome = Component.translatable("hearthstead.settler.sheet.home").getString();
        v.headStanding = Component.translatable("hearthstead.settler.sheet.standing").getString();
        v.headAttributes = Component.translatable("hearthstead.settler.compact.attributes").getString();
        v.headJobFocus = Component.translatable("hearthstead.settler.sheet.job_focus").getString();
        v.headTraits = Component.translatable("hearthstead.settler.sheet.traits").getString();
        v.headWorn = Component.translatable("hearthstead.settler.sheet.worn").getString();
        v.headBag = Component.translatable("hearthstead.settler.bag").getString();
        v.headWanted = Component.translatable("hearthstead.settler.sheet.wanted").getString();
        v.headCarrying = Component.translatable("hearthstead.settler.sheet.carrying").getString();
        v.carryNone = HsUi.fitLabel(font, Component.translatable(snapshot == null
            ? "hearthstead.settler.loading" : "hearthstead.settler.sheet.carrying.none"), pageW);
        v.loading = HsUi.fitLabel(font, Component.translatable("hearthstead.settler.loading"), pageW);

        // -- attributes, traits, job focus ----------------------------------------------
        JobAttributeProfile jobProfile = JobAttributeProfile.find(profession).orElse(null);
        JobAttributeProfile.Importance[] jobImportance = new JobAttributeProfile.Importance[Attribute.COUNT];
        JobAttributeHighlight[] jobHighlights = new JobAttributeHighlight[Attribute.COUNT];
        java.util.Arrays.fill(jobHighlights, JobAttributeHighlight.NONE);
        if (jobProfile != null) {
            int coreSlot = 0;
            for (JobAttributeProfile.Slot slot : jobProfile.slots()) {
                int ordinal = slot.attribute().ordinal();
                jobImportance[ordinal] = slot.importance();
                jobHighlights[ordinal] = slot.importance() == JobAttributeProfile.Importance.CORE
                    ? (coreSlot++ == 0 ? JobAttributeHighlight.PRIMARY : JobAttributeHighlight.SECONDARY)
                    : JobAttributeHighlight.SUPPORT;
            }
        }
        v.attributes = new AttributeView[Attribute.COUNT];
        v.traits = new TraitView[TRAIT_SLOTS];
        v.jobImpacts = new JobImpactView[0];
        int cellW = (pageW - 12) / 2;
        if (snapshot != null) {
            for (Attribute attribute : Attribute.ALL) {
                int ordinal = attribute.ordinal();
                boolean knack = ordinal == snapshot.knackOrdinal();
                JobAttributeProfile.Importance importance = jobImportance[ordinal];
                JobAttributeHighlight highlight = jobHighlights[ordinal];
                Component name = attribute.displayName();
                int value = snapshot.attributeValues().get(ordinal);
                HsUi.FittedLabel valueLabel = HsUi.fitLabel(font, Component.literal(Integer.toString(value)), 24);
                int labelBox = Math.max(1, cellW - valueLabel.width() - 6 - (highlight.isJobFocus() ? 5 : 0)
                    - (knack ? 8 : 0));
                Component compact = Component.translatable("hearthstead.attribute." + attribute.key() + ".compact");
                Component abbr = Component.translatable("hearthstead.attribute." + attribute.key() + ".abbr");
                Component shown = font.width(name) <= labelBox ? name
                    : font.width(compact) <= labelBox ? compact : abbr;
                List<Component> tooltip = new ArrayList<>(6);
                Component tooltipName = knack
                    ? Component.translatable("hearthstead.settler.attribute_knack", name) : name;
                tooltip.add(tooltipName.copy().append(Component.literal("  " + value + " / 100"))
                    .withStyle(knack ? ChatFormatting.GOLD : ChatFormatting.WHITE));
                appendActualAttributeEffects(tooltip, attribute, value, profession);
                tooltip.add(attribute.trainedBy().copy().withStyle(ChatFormatting.GRAY));
                if (highlight == JobAttributeHighlight.PRIMARY) {
                    tooltip.add(Component.translatable("hearthstead.settler.compact.job.core", job)
                        .withStyle(ChatFormatting.GREEN));
                } else if (highlight == JobAttributeHighlight.SECONDARY) {
                    tooltip.add(Component.translatable("hearthstead.settler.compact.job.secondary", job)
                        .withStyle(ChatFormatting.GOLD));
                } else if (importance == JobAttributeProfile.Importance.SUPPORT) {
                    tooltip.add(Component.translatable("hearthstead.settler.compact.job.support", job)
                        .withStyle(ChatFormatting.GRAY));
                }
                if (profession.employed() && !profession.martial()) {
                    int tradeLevel = SkillLevels.levelOf(settler.tradeXp());
                    if (highlight == JobAttributeHighlight.PRIMARY) {
                        tooltip.add(primaryBonusLine(profession, attribute, tradeLevel, value));
                    } else if (highlight == JobAttributeHighlight.SECONDARY) {
                        tooltip.add(secondaryBonusLine(profession, attribute, tradeLevel, value));
                    }
                    if (attribute == Attribute.WITS) {
                        tooltip.add(witsBonusLine(value));
                    }
                }
                v.attributes[ordinal] = new AttributeView(HsUi.fitLabel(font, shown, labelBox), valueLabel,
                    value / 100.0F, knack, highlight, List.copyOf(tooltip));
            }

            int traitW = wide ? colW : pageW;
            List<Integer> traitOrdinals = snapshot.traitOrdinals();
            int visibleTraits = Math.min(TRAIT_SLOTS, traitOrdinals.size());
            for (int slot = 0; slot < visibleTraits; slot++) {
                Trait trait = Trait.ALL[traitOrdinals.get(slot)];
                Component name = trait.displayName();
                Component description = trait.describe();
                List<EffectView> effects = new ArrayList<>(3);
                for (Effect effect : wiredEffects(trait)) {
                    effects.add(new EffectView(HsUi.fitLabel(font, effect.text(), traitW),
                        toneColour(effect.tone())));
                }
                v.traits[slot] = new TraitView(HsUi.fitLabel(font, name, traitW),
                    HsUi.fitLabel(font, description, traitW), List.copyOf(effects),
                    List.of(name, description.copy().withStyle(ChatFormatting.GRAY)));
            }
            v.jobImpacts = buildJobImpacts(jobProfile, job, wide ? colW - 5 : pageW - 5);

            // -- the bag, with Gear Tier locks ------------------------------------------
            List<Integer> ids = snapshot.bagItemIds();
            List<Integer> counts = snapshot.bagCounts();
            v.bagStacks = new ItemStack[BAG_SLOTS];
            v.bagLocked = new boolean[BAG_SLOTS];
            @SuppressWarnings("unchecked")
            List<Component>[] bagTips = new List[BAG_SLOTS];
            int carried = 0;
            for (int slot = 0; slot < BAG_SLOTS; slot++) {
                int count = slot < counts.size() ? counts.get(slot) : 0;
                ItemStack stack = ItemStack.EMPTY;
                if (count > 0) {
                    int itemId = slot < ids.size() ? ids.get(slot) : 0;
                    stack = new ItemStack(BuiltInRegistries.ITEM.byId(itemId), count);
                    carried += count;
                }
                v.bagStacks[slot] = stack;
                v.bagLocked[slot] = !stack.isEmpty() && lockedFor(stack);
                bagTips[slot] = stack.isEmpty() ? List.of() : List.copyOf(gearTooltip(stack));
            }
            v.bagTooltips = bagTips;
            // Stout Straps batching: a waiting second load is carried too (synced carry load).
            int stowed = Math.max(0, settler.getCarryLoad() - carried);
            // One unit (owner QA, 26 Sep): items carried against the item capacity; a
            // stowed batch counts toward it and is named after it.
            v.bagCount = HsUi.fitLabel(font, Component.literal((carried + stowed) + " / "
                + settler.getCarryCapacity() + " items" + (stowed > 0 ? " · " + stowed + " stowed" : "")),
                stowed > 0 ? 120 : 80);
            rebuildBlessingStatus();
        } else {
            v.bagStacks = new ItemStack[BAG_SLOTS];
            java.util.Arrays.fill(v.bagStacks, ItemStack.EMPTY);
            v.bagLocked = new boolean[BAG_SLOTS];
            @SuppressWarnings("unchecked")
            List<Component>[] bagTips = new List[BAG_SLOTS];
            java.util.Arrays.fill(bagTips, List.of());
            v.bagTooltips = bagTips;
            v.bagCount = HsUi.fitLabel(font, Component.empty(), 1);
            blessingStatusLine = Component.empty();
            hasBlessings = false;
        }

        // -- trade level / combat tier ------------------------------------------------------
        v.trade = buildTradeView(profession, pageW);
        v.showLevel = v.trade.shown();
        if (v.showLevel) {
            boolean martial = profession.martial();
            int counterW = layout.counters[1] == null ? 30 : layout.counters[1].width() - 22;
            v.levelCaption = HsUi.fitLabel(font, Component.translatable(martial
                ? "hearthstead.settler.sheet.tier" : "hearthstead.settler.sheet.level"), Math.max(1, counterW));
            v.levelValue = HsUi.fitLabel(font, Component.literal(Integer.toString(martial
                ? GuardExperience.tierOf(settler.combatExperience()).level()
                : SkillLevels.levelOf(settler.tradeXp()))), Math.max(1, counterW));
        } else {
            v.levelCaption = HsUi.fitLabel(font, Component.empty(), 1);
            v.levelValue = v.levelCaption;
        }
        v.skillsFooter = snapshot == null ? null : HsUi.fitLabel(font,
            Component.translatable("hearthstead.settler.sheet.skills_footer"), layout.footer.width());

        // -- right now: request, logistics blocker, life need, refusal ------------------------
        int nowTextW = colW;
        Component refusal = null;
        v.requestStack = ItemStack.EMPTY;
        Component requestDisplayName = Component.empty();
        Component requestName = Component.translatable("hearthstead.settler.request.none");
        Component requestInstruction = Component.translatable("hearthstead.settler.request.none.instruction");
        List<Component> requestTooltip = List.of();
        if (snapshot != null) {
            Component actionRefusal = snapshot.refusal().orElse(null);
            refusal = actionRefusal == null ? null : Component.translatable(
                "hearthstead.settler.compact.action_error", actionRefusal);
            if (snapshot.requestedItemId() >= 0) {
                ItemStack request = new ItemStack(BuiltInRegistries.ITEM.byId(snapshot.requestedItemId()));
                if (!request.isEmpty()) {
                    v.requestStack = request;
                    requestDisplayName = requestedEquipmentName(request);
                    requestName = Component.translatable("hearthstead.settler.request.item", requestDisplayName);
                    EquipmentRequest.Reason[] reasons = EquipmentRequest.Reason.values();
                    EquipmentRequest.Reason reason = snapshot.requestReasonOrdinal() >= 0
                        && snapshot.requestReasonOrdinal() < reasons.length
                            ? reasons[snapshot.requestReasonOrdinal()] : EquipmentRequest.Reason.MISSING;
                    String reasonKey = switch (reason) {
                        case MISSING -> "missing";
                        case WRONG_TOOL -> "wrong";
                        case WORN -> "worn";
                    };
                    Component reasonInstruction = Component.translatable(
                        "hearthstead.settler.request." + reasonKey + ".instruction");
                    boolean workplaceSupply = !snapshot.employerBuildingId().isEmpty();
                    requestInstruction = workplaceSupply
                        ? Component.translatable("hearthstead.settler.request.workplace.instruction",
                            requestDisplayName, building)
                        : reasonInstruction;
                    List<Component> tip = new ArrayList<>();
                    tip.add(Component.translatable("hearthstead.settler.request.tooltip", requestDisplayName));
                    if (workplaceSupply) {
                        tip.add(Component.translatable("hearthstead.settler.request.workplace.tooltip",
                            requestDisplayName, building).withStyle(ChatFormatting.GRAY));
                        if (reason != EquipmentRequest.Reason.MISSING) {
                            tip.add(reasonInstruction.copy().withStyle(ChatFormatting.GRAY));
                        }
                    } else {
                        tip.add(reasonInstruction.copy().withStyle(ChatFormatting.GRAY));
                    }
                    requestTooltip = List.copyOf(tip);
                }
            }
        }
        if (!v.requestStack.isEmpty()) {
            nowTextW -= 22;
        }
        StopReason logisticsStop = settler.logisticsStopReason();
        Component logisticsInstruction = logisticsStop == StopReason.NONE ? Component.empty()
            : Component.translatable("hearthstead.settler.fix."
                + logisticsStop.name().toLowerCase(java.util.Locale.ROOT));
        int lifeNeed = settler.lifeNeed();
        Component lifeNeedWords = lifeNeedLine(lifeNeed);
        boolean showLifeNeed = v.requestStack.isEmpty() && logisticsStop == StopReason.NONE
            && !lifeNeedWords.getString().isEmpty();
        com.hearthstead.network.BuilderPayloads.Site builderSite = profession == Profession.BUILDER
            ? com.hearthstead.client.builder.BuildSitesClient.siteOf(settler.getUUID()) : null;
        Component next;
        int nextColour;
        List<Component> statusTip = new ArrayList<>(4);
        if (!v.requestStack.isEmpty()) {
            next = Component.translatable("hearthstead.settler.compact.status.needs", requestDisplayName);
            nextColour = Ui2Palette.AMBER;
            statusTip.addAll(requestTooltip);
        } else if (logisticsStop != StopReason.NONE) {
            next = logisticsStop.displayName();
            nextColour = logisticsStop.isWaiting() ? Ui2Palette.AMBER : Ui2Palette.DANGER;
            statusTip.add(logisticsStop.displayName().copy().withStyle(ChatFormatting.GOLD));
            statusTip.add(logisticsInstruction.copy().withStyle(ChatFormatting.GRAY));
        } else if (showLifeNeed) {
            next = lifeNeedWords;
            nextColour = LifeNeed.critical(lifeNeed) ? Ui2Palette.DANGER : Ui2Palette.AMBER;
            statusTip.add(lifeNeedWords.copy().withStyle(LifeNeed.critical(lifeNeed)
                ? ChatFormatting.RED : ChatFormatting.GOLD));
            Component fix = lifeNeedFix(lifeNeed);
            if (!fix.getString().isEmpty()) statusTip.add(fix.copy().withStyle(ChatFormatting.GRAY));
        } else if (builderSite != null) {
            // Builder lane: the same headline his build site shows.
            next = Component.literal(builderSite.label() + ": ")
                .append(com.hearthstead.client.builder.BuildSitesClient.status(builderSite));
            nextColour = Ui2Palette.INK_SOFT;
            statusTip.add(next);
            Component missing = com.hearthstead.client.builder.BuildSitesClient.missingLine(builderSite);
            if (!missing.getString().isEmpty()) statusTip.add(missing.copy().withStyle(ChatFormatting.GRAY));
        } else if (isWorkingActivity()) {
            next = Component.translatable("hearthstead.settler.sheet.all_well");
            nextColour = Ui2Palette.INK_MUTED;
        } else {
            next = Component.translatable("hearthstead.settler.sheet.nothing_needed");
            nextColour = Ui2Palette.INK_MUTED;
        }
        if (refusal != null) {
            statusTip.add(refusal.copy().withStyle(ChatFormatting.GOLD));
        }
        v.nextAction = txt(next, nowTextW);
        v.nextActionColour = nextColour;
        v.statusTooltip = List.copyOf(statusTip.isEmpty() ? List.of(next) : statusTip);
        v.statusTextWidth = nowTextW - 0;
        v.lifeNeed = showLifeNeed ? lifeNeed : LifeNeed.NONE;
        v.refusalLines = refusal == null ? List.of() : HsUi.fitLines(font, refusal, colW - 8);

        // -- work -----------------------------------------------------------------------------
        boolean employer = snapshot != null && !snapshot.employerBuildingId().isEmpty();
        v.workplaceMissing = !employer;
        v.workplaceIcon = employer ? buildingEmblem(snapshot.employerBuildingId()) : NO_POST_ICON;
        v.workplaceName = txt(employer ? building : Component.translatable(profession.employed()
            ? "hearthstead.settler.sheet.no_workplace" : "hearthstead.employ.unemployed"), colW - 20);
        Component detail;
        if (snapshot == null) {
            detail = Component.translatable("hearthstead.settler.loading");
        } else if (employer && profession == Profession.GUARD) {
            detail = Component.translatable(snapshot.guardWatchNight()
                ? "hearthstead.settler.watch_night" : "hearthstead.settler.watch_day");
        } else if (employer) {
            detail = job;
        } else {
            detail = Component.translatable(profession.employed()
                ? "hearthstead.settler.sheet.no_workplace.tip" : "hearthstead.settler.sheet.unassigned.tip");
        }
        v.workplaceDetail = txt(detail, colW - 20);
        List<Component> workTip = new ArrayList<>(3);
        workTip.add(employer ? Component.translatable("hearthstead.settler.employed_at", building)
            : v.workplaceName.fullOrFit());
        workTip.add(detail.copy().withStyle(ChatFormatting.GRAY));
        String roleKey = "hearthstead.role." + profession.key() + ".desc";
        if (net.minecraft.client.resources.language.I18n.exists(roleKey)) {
            workTip.add(Component.translatable(roleKey).withStyle(ChatFormatting.GRAY));
        }
        v.workplaceTooltip = List.copyOf(workTip);

        // -- home -------------------------------------------------------------------------------
        boolean hasBed = snapshot != null && snapshot.hasBed();
        String homeId = snapshot == null ? "" : snapshot.homeBuildingId();
        Component homeName;
        Component homeDetail;
        if (snapshot == null) {
            homeName = Component.translatable("hearthstead.settler.loading");
            homeDetail = Component.empty();
        } else if (!homeId.isEmpty()) {
            BuildingType type = BuildingType.byId(homeId);
            homeName = type == null ? Component.literal(homeId) : type.displayName();
            homeDetail = Component.translatable("hearthstead.settler.sheet.home.bed");
        } else if (hasBed) {
            homeName = Component.translatable("hearthstead.settler.sheet.home.loose_bed");
            homeDetail = Component.translatable("hearthstead.settler.sheet.home.loose_bed.tip");
        } else {
            homeName = Component.translatable("hearthstead.settler.sheet.home.none");
            homeDetail = lifeNeedFix(LifeNeed.HOMELESS);
        }
        v.homeWarn = snapshot != null && !hasBed;
        v.homeName = txt(homeName, colW - 20);
        v.homeDetail = txt(homeDetail, colW - 20);
        v.homeTooltip = List.of(homeName, homeDetail.copy().withStyle(ChatFormatting.GRAY));

        // -- standing -------------------------------------------------------------------------------
        if (mayor) {
            Component boon = boonName();
            v.mayorLine = txt(Component.translatable(snapshot.mayorSettling()
                ? "hearthstead.settler.mayor_settling" : "hearthstead.settler.mayor_badge", boon), colW - 10);
            v.mayorTooltip = List.of(boonDescription());
        }
        if (snapshot != null && (profession == Profession.GUARD || profession == Profession.ARCHER)) {
            int experience = settler.combatExperience();
            GuardExperience.Tier tier = GuardExperience.tierOf(experience);
            Component line = tier == GuardExperience.Tier.HERO
                ? Component.translatable("hearthstead.settler.combat_progress_max", tier.level(), experience)
                : Component.translatable("hearthstead.settler.combat_progress", tier.level(), experience,
                    GuardExperience.nextThreshold(experience));
            Attribute rankAttribute = profession == Profession.GUARD ? Attribute.STRENGTH : Attribute.DEXTERITY;
            int rankValue = snapshot.attributeValues().get(rankAttribute.ordinal());
            Component abilityRank = profession == Profession.GUARD
                ? GuardRank.of(rankValue).displayName() : ArcherRank.of(rankValue).displayName();
            v.combatLine = txt(line, colW);
            v.combatTooltip = List.of(line, Component.translatable("hearthstead.settler.combat_rank_tip",
                abilityRank, rankAttribute.displayName()).withStyle(ChatFormatting.GRAY));
        }
        if (snapshot != null && hasBlessings) {
            v.blessingLine = txt(blessingStatusLine, colW);
        }

        // -- gear ---------------------------------------------------------------------------------
        GearGate.Clearance clearance = GearGate.clearance(settler);
        v.tierChips = new HsUi.FittedLabel[GearTier.MAX + 1];
        @SuppressWarnings("unchecked")
        List<Component>[] tierTips = new List[GearTier.MAX + 1];
        for (int t = 0; t <= GearTier.MAX; t++) {
            v.tierChips[t] = HsUi.fitLabel(font, Component.literal(Integer.toString(t)), 10);
            tierTips[t] = tierLines(clearance, GearTier.of(t));
        }
        v.tierTooltips = tierTips;
        GearTier nextTier = clearance.nextLocked();
        v.allTiersOpen = nextTier == null;
        Component nextLine = nextTier == null ? Component.translatable("hearthstead.gear.top")
            : Component.translatable("hearthstead.gear.next.short", Component.translatable(
                "hearthstead.gear.tooltip.title", nextTier.level(), nextTier.displayName()));
        int nextW = wide || pageW - (GearTier.MAX + 1) * CHIP_STEP - 4 >= 60
            ? (wide ? colW : pageW) - (GearTier.MAX + 1) * CHIP_STEP - 4 : pageW;
        Txt nextFit = txt(nextLine, Math.max(1, nextW));
        Component unlock = GearGate.nextUnlock(clearance);
        v.nextUnlock = new Txt(nextFit.fit(), nextFit.clipped(),
            unlock == null ? List.of(nextLine) : List.of(nextLine, unlock.copy().withStyle(ChatFormatting.GRAY)));
        v.wornEmptyTooltips = wornEmptyTooltips();
        v.requestName = txt(requestName, colW - 22);
        v.requestInstruction = txt(requestInstruction, colW - 22);
        v.requestTooltip = requestTooltip.isEmpty() ? List.of(requestName) : requestTooltip;

        // -- footer tooltips ------------------------------------------------------------------------
        v.dismissTooltip = Component.translatable("hearthstead.settler.dismiss.tip", title, building);
        v.appointTooltip = appointTooltip();

        // -- one-page extras: hover line, footer hint, Blessings ------------------------------------
        List<Component> more = new ArrayList<>();
        boolean anyTrait = false;
        if (v.traits != null) {
            for (TraitView trait : v.traits) {
                if (trait == null) continue;
                if (!anyTrait) {
                    more.add(Component.literal(v.headTraits).withStyle(ChatFormatting.GOLD));
                    anyTrait = true;
                }
                more.addAll(trait.tooltip());
            }
        }
        if (v.trade.bonusCount() > 0) more.addAll(v.trade.bonusTooltip());
        if (v.combatLine != null) more.addAll(v.combatTooltip);
        if (!v.refusalLines.isEmpty() && v.homeTooltip != null) more.addAll(v.homeTooltip);
        if (more.isEmpty()) more.add(Component.translatable("hearthstead.settler.sheet.more_none"));
        v.moreTooltip = List.copyOf(more);
        int oneColW = columnWidth(pageW);
        v.moreHint = HsUi.fitLabel(font, Component.translatable("hearthstead.settler.sheet.more_hint"), oneColW);
        v.noFocus = HsUi.fitLabel(font, Component.translatable("hearthstead.settler.sheet.no_focus"), oneColW);
        v.inventoryHint = HsUi.fitLabel(font, Component.translatable("hearthstead.settler.sheet.inventory_hint"),
            pageW / 2);
        v.headBlessings = Component.translatable("hearthstead.settler.sheet.blessings").getString();
        int n = BLESSING_IDS.length;
        v.blessingStacks = new ItemStack[n];
        v.blessingActive = new boolean[n];
        @SuppressWarnings("unchecked")
        List<Component>[] tips = new List[n];
        MutableComponent summary = Component.empty();
        for (int i = 0; i < n; i++) {
            BlessingId blessing = BLESSING_IDS[i];
            int rank = snapshot == null ? 0 : snapshot.blessingRank(blessing);
            v.blessingStacks[i] = com.hearthstead.item.BlessingSealItem.stackFor(blessing);
            v.blessingActive[i] = rank > 0;
            Component name = Component.translatable("hearthstead.blessing." + blessing.id() + ".name");
            tips[i] = rank > 0
                ? List.of(name.copy().append(" " + roman(rank)).withStyle(ChatFormatting.GOLD),
                    Component.translatable("hearthstead.blessing." + blessing.id() + ".effect")
                        .withStyle(ChatFormatting.GRAY))
                : List.of(name.copy().withStyle(ChatFormatting.GRAY),
                    Component.translatable("hearthstead.blessing." + blessing.id() + ".effect")
                        .withStyle(ChatFormatting.DARK_GRAY),
                    Component.translatable("hearthstead.settler.sheet.blessing_unbound"));
            if (rank > 0) {
                if (v.anyBlessing) summary.append(Component.literal(" \u00b7 "));
                summary.append(Component.translatable("hearthstead.blessing." + blessing.id() + ".short"))
                    .append(" " + roman(rank));
                v.anyBlessing = true;
            }
        }
        v.blessingTips = tips;
        int summaryW = Math.max(20, pageW - 66 - n * (SLOT + 2) - 8);
        v.blessingSummary = HsUi.fitLabel(font, v.anyBlessing ? summary
            : Component.translatable("hearthstead.settler.sheet.no_blessings"), summaryW);
        v.blessingHowTo = List.of(Component.translatable("hearthstead.settler.sheet.blessings_how"));
        return v;
    }

    private List<Component>[] wornEmptyTooltips() {
        String[] keys = {"head", "chest", "legs", "feet", "mainhand", "offhand"};
        @SuppressWarnings("unchecked")
        List<Component>[] out = new List[keys.length];
        for (int i = 0; i < keys.length; i++) {
            out[i] = List.of(Component.translatable("hearthstead.settler.sheet.slot." + keys[i]),
                Component.translatable("hearthstead.settler.sheet.slot.empty").withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    private static List<Component> tierLines(GearGate.Clearance c, GearTier tier) {
        List<Component> lines = new ArrayList<>(4);
        lines.add(Component.translatable("hearthstead.gear.tooltip.title", tier.level(), tier.displayName()));
        lines.add(Component.translatable("hearthstead.gear.tooltip.items", tier.itemsName())
            .withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable(c.personalMet(tier.level())
                ? "hearthstead.gear.tooltip.personal.met" : "hearthstead.gear.tooltip.personal.missing",
            tier.personalRequirement(c.role())));
        Component know = tier.knowledgeRequirement();
        lines.add(know == null ? Component.translatable("hearthstead.gear.tooltip.knowledge.none")
            : Component.translatable(c.knowledgeMet(tier.level())
                ? "hearthstead.gear.tooltip.knowledge.met" : "hearthstead.gear.tooltip.knowledge.missing", know));
        return List.copyOf(lines);
    }

    /** Guard rank (Man-at-Arms ...) or archer rank from the snapshot's attribute. */
    private Component rankName(Profession profession) {
        if (GearGate.roleOf(profession) == GearTier.Role.GUARD) {
            return GuardRank.of(snapshotAttribute(Attribute.STRENGTH)).displayName();
        }
        if (profession == Profession.ARCHER) {
            return ArcherRank.of(snapshotAttribute(Attribute.DEXTERITY)).displayName();
        }
        return null;
    }

    private static ItemStack buildingEmblem(String typeId) {
        BuildingType type = BuildingType.byId(typeId);
        return type == null ? new ItemStack(Items.OAK_DOOR) : new ItemStack(type.emblem());
    }

    private static int toneColour(HsUi.Tone tone) {
        return tone == HsUi.Tone.GOOD ? Ui2Palette.FOREST
            : tone == HsUi.Tone.WARN ? Ui2Palette.AMBER
            : tone == HsUi.Tone.BAD ? Ui2Palette.DANGER : Ui2Palette.GOLD;
    }

    // ============================================================== page plans ===

    // ============================================================ data helpers ===
    // (ported unchanged from the previous dossier: only wired effects are shown)

    /**
     * Two core job attributes, each with its effect. Live effects show the
     * number a gameplay system actually reads; role priorities say so plainly.
     */
    private JobImpactView[] buildJobImpacts(JobAttributeProfile profile, Component job, int columnWidth) {
        if (snapshot == null || profile == null) {
            return new JobImpactView[0];
        }
        List<JobImpactView> impacts = new ArrayList<>(2);
        for (JobAttributeProfile.Slot slot : profile.slots()) {
            if (slot.importance() != JobAttributeProfile.Importance.CORE) {
                continue;
            }
            int value = snapshot.attributeValues().get(slot.attribute().ordinal());
            Component fullLabel = Component.translatable("hearthstead.settler.compact.job.value",
                slot.attribute().displayName(), value);
            boolean primary = impacts.isEmpty();
            Component bandLabel = Component.literal(primary ? "PRIMARY · " : "SECONDARY · ")
                .append(slot.attribute().displayName()).append(Component.literal(" " + value));
            JobImpactEvidence evidence = jobImpactEvidence(profile.profession(), slot);
            Component effect;
            Component bandEffect;
            Component detail;
            if (evidence == JobImpactEvidence.LIVE && profile.profession() == Profession.LUMBERER
                && slot.effect() == JobAttributeProfile.EffectId.LUMBER_CONTACTS) {
                int contacts = JobEffects.lumberContacts(value);
                int capacity = settler.getCarryCapacity();
                effect = Component.translatable("hearthstead.settler.compact.job.lumber.live", contacts, capacity);
                bandEffect = Component.translatable("hearthstead.settler.compact.job.lumber.band", contacts, capacity);
                detail = Component.translatable("hearthstead.settler.compact.job.lumber.live.detail", contacts, capacity);
            } else if (evidence == JobImpactEvidence.LIVE && slot.attribute() == Attribute.STAMINA) {
                int currentPace = (int) Math.round(JobEffects.workPace(settler.getEnergy(), value) * 100.0D);
                int minimumPace = (int) Math.round(JobEffects.minimumPace(value) * 100.0D);
                effect = Component.literal("Move speed: " + currentPace + "% now; " + minimumPace + "% when exhausted.");
                bandEffect = Component.literal("Exhausted speed " + minimumPace + "%");
                detail = Component.literal("At zero Energy, move speed stays at least " + minimumPace + "%.");
            } else if (evidence == JobImpactEvidence.LIVE && profile.profession() == Profession.GUARD
                && slot.attribute() == Attribute.STRENGTH) {
                GuardRank rank = GuardRank.of(value);
                double damage = rank.ordinal() * GuardRank.MELEE_EDGE_PER_RANK;
                effect = Component.literal(String.format(java.util.Locale.ROOT,
                    "%s · +%.1f melee damage", rank.displayName().getString(), damage));
                bandEffect = Component.literal(String.format(java.util.Locale.ROOT, "Melee damage +%.1f", damage));
                detail = Component.literal("Current rank bonus; weapon damage is added separately.");
            } else if (evidence == JobImpactEvidence.LIVE && profile.profession() == Profession.ARCHER
                && slot.attribute() == Attribute.DEXTERITY) {
                ArcherRank rank = ArcherRank.of(value);
                boolean marksman = rank.atLeast(ArcherRank.MARKSMAN);
                int damage = marksman ? 25 : 0;
                float spread = marksman ? ArcherRank.MARKSMAN_INACCURACY : ArcherRank.BASE_INACCURACY;
                effect = Component.literal(rank.displayName().getString() + " · arrow damage +" + damage
                    + "% · spread " + spread);
                bandEffect = Component.literal("+" + damage + "% · spread " + spread);
                detail = Component.literal("At Sharpshooter, every 4th volley becomes a Power Shot.");
            } else if (evidence == JobImpactEvidence.LIVE && profile.profession() == Profession.FISHER
                && slot.attribute() == Attribute.DEXTERITY) {
                String cycleSeconds = fisherCycleSeconds(value);
                effect = Component.literal("Base cast: " + cycleSeconds + " seconds");
                bandEffect = Component.literal("Cast " + cycleSeconds + " sec");
                detail = Component.literal("Base casting time; walking and delivery add time. "
                    + fisherCatchDetail(value));
            } else if (evidence == JobImpactEvidence.LIVE && slot.attribute() == Attribute.WITS
                && slot.effect() == JobAttributeProfile.EffectId.LEARNING_RATE) {
                double trainingBonus = value * 0.5D;
                effect = Component.literal(String.format(java.util.Locale.ROOT,
                    "Training progress: +%.1f%%", trainingBonus));
                bandEffect = Component.literal(String.format(java.util.Locale.ROOT, "Training +%.1f%%", trainingBonus));
                detail = Component.literal("All attributes train 0.5% faster per Wits.");
            } else {
                // Attributes lane: every slot names a real effect (plan/ATTRIBUTES.md).
                String line = slotEffectLine(slot, value, primary);
                effect = Component.literal(line);
                bandEffect = Component.literal(line);
                detail = Component.translatable(com.hearthstead.client.ui.JobProfileUi.effectKey(slot.effect()));
            }
            List<Component> tooltip = List.of(
                fullLabel.copy().withStyle(ChatFormatting.WHITE),
                Component.translatable(primary ? "hearthstead.settler.compact.job.core"
                    : "hearthstead.settler.compact.job.secondary", job).withStyle(ChatFormatting.GOLD),
                effect,
                detail.copy().withStyle(evidence == JobImpactEvidence.LIVE
                    ? ChatFormatting.GREEN : ChatFormatting.GRAY));
            impacts.add(new JobImpactView(
                HsUi.fitLabel(font, bandLabel, columnWidth),
                HsUi.fitLabel(font, bandEffect, columnWidth),
                evidence == JobImpactEvidence.LIVE,
                primary ? JobAttributeHighlight.PRIMARY : JobAttributeHighlight.SECONDARY,
                tooltip));
        }
        return impacts.toArray(JobImpactView[]::new);
    }

    private static String fisherCatchDetail(int dexterity) {
        return switch (FisherProgression.tier(dexterity)) {
            case 0 -> "At 25 Dexterity: Superior catches become possible.";
            case 1 -> "Superior catches unlocked. At 50: Exceptional catches.";
            case 2 -> "Exceptional catches unlocked. At 75: Masterwork catches.";
            case 3 -> "Masterwork catches unlocked. At 90: Legendary catches.";
            default -> "Legendary catches unlocked.";
        };
    }

    private static String fisherCycleSeconds(int dexterity) {
        return String.format(java.util.Locale.ROOT, "%.1f", FisherProgression.cycleTicks(dexterity) / 20.0D);
    }

    /** Explain consumers that actually run, never a planned job-profile bonus. */
    private void appendActualAttributeEffects(List<Component> tooltip, Attribute attribute,
                                              int value, Profession profession) {
        appendAttributeAffects(tooltip, attribute, value);
        tooltip.add(profession.displayName().copy().withStyle(ChatFormatting.GOLD));
        if (attribute == Attribute.WITS) {
            effectLine(tooltip, String.format(java.util.Locale.ROOT, "Training progress: +%.1f%%", value * .5));
            effectLine(tooltip, "+0.5% per Wits; other modifiers also apply.");
            return;
        }
        if (attribute == Attribute.STAMINA) {
            effectLine(tooltip, String.format(java.util.Locale.ROOT, "Move speed at current Energy: %.0f%%",
                JobEffects.workPace(settler.getEnergy(), value) * 100));
            effectLine(tooltip, String.format(java.util.Locale.ROOT, "Move speed at zero Energy: %.1f%%",
                JobEffects.minimumPace(value) * 100));
            effectLine(tooltip, String.format(java.util.Locale.ROOT, "Carrying slowdown reduced by %.1f%%",
                25D * value / com.hearthstead.entity.SettlerAttributes.CEILING));
            return;
        }
        if (attribute == Attribute.STRENGTH && profession == Profession.LUMBERER) {
            effectLine(tooltip, "Axe contacts per log: " + JobEffects.lumberContacts(value));
            effectLine(tooltip, value < 25 ? "At 25 Strength: 3 contacts (now 4)."
                : value < 70 ? "At 70 Strength: 2 contacts (now 3)." : "Best contact count reached: 2.");
            effectLine(tooltip, "Current sack limit: " + settler.getCarryCapacity() + " items.");
            effectLine(tooltip, haulGearText());
            effectLine(tooltip, "Strength adds 1 item per 10 points.");
            effectLine(tooltip, "Traits and saved upgrades also affect capacity.");
            return;
        }
        if (attribute == Attribute.STRENGTH && profession == Profession.GUARD) {
            GuardRank rank = GuardRank.of(value);
            tooltip.add(rank.displayName().copy().withStyle(ChatFormatting.GREEN));
            effectLine(tooltip, String.format(java.util.Locale.ROOT, "Rank melee bonus: +%.1f damage",
                rank.ordinal() * GuardRank.MELEE_EDGE_PER_RANK));
            if (rank.ordinal() + 1 < GuardRank.values().length) {
                GuardRank next = GuardRank.values()[rank.ordinal() + 1];
                effectLine(tooltip, "Next rank: " + next.threshold() + " Strength.");
            }
            effectLine(tooltip, "Weapon damage is added separately.");
            return;
        }
        if (attribute == Attribute.DEXTERITY && profession == Profession.FISHER) {
            effectLine(tooltip, "Base cast: " + fisherCycleSeconds(value) + " seconds.");
            effectLine(tooltip, "Walking and delivery add time to each catch.");
            effectLine(tooltip, "Technique: " + FisherProgression.technique(value).replace('_', ' ') + ".");
            effectLine(tooltip, fisherCatchDetail(value));
            return;
        }
        if (attribute == Attribute.DEXTERITY && profession == Profession.ARCHER) {
            ArcherRank rank = ArcherRank.of(value);
            tooltip.add(rank.displayName().copy().withStyle(ChatFormatting.GREEN));
            boolean marksman = rank.atLeast(ArcherRank.MARKSMAN);
            effectLine(tooltip, "Arrow base damage: " + (marksman ? "+25%" : "+0%"));
            effectLine(tooltip, "Shot spread: " + (marksman ? ArcherRank.MARKSMAN_INACCURACY
                : ArcherRank.BASE_INACCURACY) + " (lower is better).");
            if (rank.atLeast(ArcherRank.SHARPSHOOTER)) {
                effectLine(tooltip, "Every 4th volley: Power Shot, x2.5 damage.");
                effectLine(tooltip, "Power Shot adds 25 draw ticks (1.25s).");
            }
            if (rank.atLeast(ArcherRank.MASTER)) {
                effectLine(tooltip, "Every 5th volley: 3 arrows.");
                effectLine(tooltip, "Power Shot takes priority when both are due.");
            } else {
                ArcherRank next = ArcherRank.values()[rank.ordinal() + 1];
                effectLine(tooltip, "Next rank: " + next.threshold() + " Dexterity.");
            }
            return;
        }
        if (attribute == Attribute.STRENGTH && profession == Profession.COURIER) {
            effectLine(tooltip, "Current sack limit: " + settler.getCarryCapacity() + " items.");
            effectLine(tooltip, haulGearText());
        }
    }

    /**
     * Attributes lane (plan/ATTRIBUTES.md): one "Affects: ..." line naming
     * everything this attribute really changes, then the current value of
     * each scaled effect at this settler's number.
     */
    private void appendAttributeAffects(List<Component> tooltip, Attribute attribute, int value) {
        double strength = com.hearthstead.entity.AttributeConfig.configured();
        List<String> names = new ArrayList<>();
        for (com.hearthstead.entity.AttributeEffects.Effect e
                : com.hearthstead.entity.AttributeEffects.effectsOf(attribute)) {
            names.add(Component.translatable(e.translationKey()).getString());
        }
        for (String key : com.hearthstead.entity.AttributeEffects.structuralEffectKeys(attribute)) {
            names.add(Component.translatable("hearthstead.attribute.effect." + key).getString());
        }
        tooltip.add(Component.translatable("hearthstead.attribute.affects", String.join(", ", names))
            .withStyle(ChatFormatting.AQUA));
        for (com.hearthstead.entity.AttributeEffects.Effect e
                : com.hearthstead.entity.AttributeEffects.effectsOf(attribute)) {
            effectLine(tooltip, Component.translatable(e.translationKey()).getString() + " "
                + com.hearthstead.entity.AttributeEffects.format(e, value, strength));
        }
    }

    /** One job-focus line for a slot: its effect name and the value at this number. */
    private static String slotEffectLine(JobAttributeProfile.Slot slot, int value, boolean primary) {
        double strength = com.hearthstead.entity.AttributeConfig.configured();
        String name = Component.translatable(
            com.hearthstead.client.ui.JobProfileUi.effectKey(slot.effect())).getString();
        return switch (slot.effect()) {
            case WORK_PACE -> String.format(java.util.Locale.ROOT, "%s -%.0f%%", name, 100.0D
                * (primary ? com.hearthstead.entity.AttributeEffects.JOB_FIT_PRIMARY
                    : com.hearthstead.entity.AttributeEffects.JOB_FIT_SECONDARY)
                * com.hearthstead.entity.AttributeEffects.curve(value)
                * com.hearthstead.entity.AttributeEffects.clampStrength(strength));
            case LEARNING_RATE -> String.format(java.util.Locale.ROOT, "%s +%.0f%%", name, value * 0.5D);
            default -> JobAttributeProfile.scaledEffect(slot.effect())
                .map(e -> name + " " + com.hearthstead.entity.AttributeEffects.format(e, value, strength))
                .orElse(name);
        };
    }

    /** Sack tier and Hand Cart, from the synced haul-gear projection. */
    private String haulGearText() {
        Component sack = Component.translatable("hearthstead.haul.sack."
            + com.hearthstead.settlement.development.HaulGear.tierKey(settler.sackTier()));
        return (settler.getProfession() == Profession.COURIER && settler.hasHandCart()
            ? Component.translatable("hearthstead.haul.gear.cart", sack)
            : Component.translatable("hearthstead.haul.gear", sack)).getString();
    }

    private static void effectLine(List<Component> tooltip, String text) {
        tooltip.add(Component.literal(text).withStyle(ChatFormatting.GRAY));
    }

    /**
     * The buff/malus chips a trait actually delivers -- see the class doc.
     * Reads only the four multipliers a gameplay system reads back plus the
     * flat {@code SLOW_START} penalty.
     */
    private static List<Effect> wiredEffects(Trait trait) {
        List<Effect> out = new ArrayList<>(3);
        addPercent(out, trait.growth(), true, "hearthstead.trait.effect.growth");
        addPercent(out, trait.moraleDecay(), false, "hearthstead.trait.effect.morale_decay");
        addPercent(out, trait.moraleGain(), true, "hearthstead.trait.effect.morale_gain");
        addPercent(out, trait.hunger(), false, "hearthstead.trait.effect.hunger");
        // Attributes lane: speed, sight and work are live now (plan/ATTRIBUTES.md Part 5).
        addPercent(out, trait.speed(), true, "hearthstead.trait.effect.speed");
        addPercent(out, trait.sight(), true, "hearthstead.trait.effect.sight");
        addPercent(out, trait.work(), true, "hearthstead.trait.effect.work");
        addPercent(out, trait.carry(), true, "hearthstead.trait.effect.carry");
        if (trait.has(Trait.Flag.SLOW_START)) {
            out.add(new Effect(Component.translatable("hearthstead.trait.effect.slow_start"), HsUi.Tone.WARN));
        }
        return out;
    }

    /**
     * @param higherIsBetter whether a ratio above 1.0 is the buff (growth,
     *                       moraleGain) or the malus (moraleDecay, hunger)
     */
    private static void addPercent(List<Effect> out, float ratio, boolean higherIsBetter, String key) {
        int pct = Math.round((ratio - 1.0F) * 100.0F);
        if (pct == 0) {
            return;
        }
        boolean good = higherIsBetter == (pct > 0);
        String signed = (pct > 0 ? "+" : "") + pct;
        out.add(new Effect(Component.translatable(key, signed), good ? HsUi.Tone.GOOD : HsUi.Tone.WARN));
    }

    /** One trait's plain-language, tone-coloured buff or malus line. */
    private record Effect(Component text, HsUi.Tone tone) {
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
            ranks.append(Component.translatable("hearthstead.blessing." + blessing.id()
                + (activeRanks == BLESSING_IDS.length ? ".short" : ".name")));
            ranks.append(Component.literal(" " + roman(rank)));
            any = true;
        }
        hasBlessings = any;
        blessingStatusLine = any
            ? Component.translatable("hearthstead.blessing.status", ranks)
            : Component.translatable("hearthstead.blessing.status.none");
    }

    // -------------------------------------------- trade level, bonuses, needs --

    private static final HsUi.FittedLabel EMPTY_LABEL = new HsUi.FittedLabel(Component.empty(), 0);
    private static final int BONUS_INLINE_MAX = 3;

    /**
     * Read-only projection of the settler's trade level (or combat tier for
     * martial roles), its XP bar and the bonuses currently acting on them.
     */
    private record TradeView(boolean shown, HsUi.FittedLabel level, HsUi.FittedLabel xp, float progress,
                             List<Component> tooltip, HsUi.FittedLabel bonuses,
                             List<Component> bonusTooltip, int bonusCount, String qa) {
        static final TradeView NONE = new TradeView(false, EMPTY_LABEL, EMPTY_LABEL, 0.0F, List.of(),
            EMPTY_LABEL, List.of(), 0, "none");
    }

    /** One active bonus: a short inline label and its full tooltip line. */
    private record Bonus(Component inline, Component detail) {
    }

    private static String percent(double fraction) {
        double pct = fraction * 100.0D;
        return Math.abs(pct - Math.rint(pct)) < 0.05D
            ? Math.round(pct) + "%"
            : String.format(java.util.Locale.ROOT, "%.1f%%", pct);
    }

    private int snapshotAttribute(Attribute attribute) {
        return snapshot == null || attribute == null ? 0 : snapshot.attributeValues().get(attribute.ordinal());
    }

    /** Trade-level bonus lines from SkillLevels.describeBonuses (wired only). */
    private static void tradeBonuses(List<Bonus> out, Profession profession, int level, int primaryValue,
                                     int secondaryValue, int wits) {
        for (Component line : SkillLevels.describeBonuses(profession, level, primaryValue, secondaryValue, wits)) {
            out.add(new Bonus(line, line));
        }
    }

    /**
     * [quality] "Crafts at: Fine–Superior" for a trade whose workshop makes
     * graded goods: the grades each at least 10% likely at this trade level
     * and primary attribute, in a level-1 workshop with bare hands
     * (CraftedQuality#usualRange). Null for every other trade.
     */
    @javax.annotation.Nullable
    private static Component craftsAtLine(Profession profession, int level, int primaryValue) {
        switch (profession) {
            case BAKER, COOK, BUTCHER, SMITH, SAWYER, CARPENTER, MASON, FLETCHER,
                 WEAVER, TANNER, BREWER, ARMOURER -> { }
            default -> {
                return null;
            }
        }
        int[] range = com.hearthstead.settlement.work.CraftedQuality.usualRange(level, primaryValue, 1, 0);
        MutableComponent low = Component.translatable("hearthstead.goods_quality." + com.hearthstead.event.GoodsQualityTooltip.key(range[0]))
            .withStyle(com.hearthstead.event.GoodsQualityTooltip.color(range[0]));
        if (range[0] == range[1]) {
            return Component.translatableWithFallback("hearthstead.settler.crafts_at.one", "Crafts at: %s", low)
                .withStyle(ChatFormatting.GRAY);
        }
        MutableComponent high = Component.translatable("hearthstead.goods_quality." + com.hearthstead.event.GoodsQualityTooltip.key(range[1]))
            .withStyle(com.hearthstead.event.GoodsQualityTooltip.color(range[1]));
        return Component.translatableWithFallback("hearthstead.settler.crafts_at", "Crafts at: %s – %s", low, high)
            .withStyle(ChatFormatting.GRAY);
    }

    /** First level at which this job's primary bonus appears (0 = never). */
    private static int primaryStartLevel(Profession profession) {
        for (int level = 2; level <= SkillLevels.MAX_LEVEL; level++) {
            if (!SkillLevels.describeBonuses(profession, level, 50, 0, 0).isEmpty()) return level;
        }
        return 0;
    }

    /** First level at which this job's secondary bonus appears (0 = never). */
    private static int secondaryStartLevel(Profession profession) {
        for (int level = 2; level <= SkillLevels.MAX_LEVEL; level++) {
            if (!SkillLevels.describeBonuses(profession, level, 0, 99, 0).isEmpty()) return level;
        }
        return 0;
    }

    /** Owned Development bonuses that act on this profession, then on everyone. */
    private void developmentBonuses(List<Bonus> out, Profession profession) {
        if (snapshot == null) {
            return;
        }
        switch (profession) {
            case LUMBERER -> {
                upgrade(out, PostRaidUpgrade.SHARPENED_AXES, "Felling -" + PostRaidUpgrade.SHARPENED_AXES_PERCENT + "%",
                    "fells each tree " + PostRaidUpgrade.SHARPENED_AXES_PERCENT + "% faster");
                upgrade(out, PostRaidUpgrade.WORKER_PACKS, "Pack x1.5", "carries half again as much per trip");
            }
            case FARMER -> upgrade(out, PostRaidUpgrade.WORKER_PACKS, "Pack x1.5",
                "carries half again as much per trip");
            case FISHER -> upgrade(out, PostRaidUpgrade.FISHERS_NETS, "Casts -" + PostRaidUpgrade.FISHERS_NETS_PERCENT + "%",
                "each cast cycle " + PostRaidUpgrade.FISHERS_NETS_PERCENT + "% shorter");
            case COURIER -> {
                upgrade(out, PostRaidUpgrade.COURIER_SATCHEL, "Carry +" + PostRaidUpgrade.COURIER_SATCHEL_BONUS,
                    "+" + PostRaidUpgrade.COURIER_SATCHEL_BONUS + " items per trip");
                upgrade(out, PostRaidUpgrade.HAND_CART, "Carry +" + PostRaidUpgrade.HAND_CART_BONUS,
                    "+" + PostRaidUpgrade.HAND_CART_BONUS + " items per trip");
                upgrade(out, PostRaidUpgrade.STOUT_STRAPS, "Carry +" + PostRaidUpgrade.STOUT_STRAPS_BONUS,
                    "+" + PostRaidUpgrade.STOUT_STRAPS_BONUS + " items per trip");
            }
            case GUARD, ARCHER -> {
                if (profession == Profession.GUARD) {
                    upgrade(out, PostRaidUpgrade.GUARD_ARMS_IRON, "Iron kit",
                        "dressed one armour tier earlier from Veteran");
                } else {
                    upgrade(out, PostRaidUpgrade.ARCHER_LONGBOW_DRILL, "Longbow",
                        "longer range and faster draw when unposted");
                }
                upgrade(out, PostRaidUpgrade.GUARD_DRILL, "Combat XP +" + PostRaidUpgrade.GUARD_DRILL_PERCENT + "%",
                    "+" + PostRaidUpgrade.GUARD_DRILL_PERCENT + "% combat experience");
                if (snapshot.ownsShieldDoctrine()) {
                    out.add(new Bonus(Component.literal("Doctrine +" + PostRaidUpgrade.SHIELD_DOCTRINE_XP_PERCENT + "%"),
                        Component.translatableWithFallback("hearthstead.settler.bonus.shield_doctrine",
                            "Shield Doctrine: +%s%% combat experience", PostRaidUpgrade.SHIELD_DOCTRINE_XP_PERCENT)));
                }
            }
            default -> {
            }
        }
        upgrade(out, PostRaidUpgrade.WARM_HEARTH, "Night hunger -" + PostRaidUpgrade.WARM_HEARTH_PERCENT + "%",
            "loses " + PostRaidUpgrade.WARM_HEARTH_PERCENT + "% less hunger at night");
        upgrade(out, PostRaidUpgrade.STURDY_BEDS, "Bed morale +" + PostRaidUpgrade.STURDY_BEDS_MORALE,
            "+" + PostRaidUpgrade.STURDY_BEDS_MORALE + " morale with a claimed bed");
        upgrade(out, PostRaidUpgrade.FEATHER_QUILTS, "Sleep +" + PostRaidUpgrade.FEATHER_QUILTS_PERCENT + "%",
            "regains " + PostRaidUpgrade.FEATHER_QUILTS_PERCENT + "% more energy asleep");
    }

    private void upgrade(List<Bonus> out, PostRaidUpgrade upgrade, String inline, String effect) {
        if (snapshot != null && snapshot.ownsUpgrade(upgrade)) {
            out.add(new Bonus(Component.literal(inline), upgrade.displayName().copy().append(": " + effect)));
        }
    }

    /** "a · b · c +N", keeping as many whole entries as fit in {@code box}. */
    private HsUi.FittedLabel inlineBonuses(List<Bonus> bonuses, int box) {
        if (bonuses.isEmpty() || box <= 8) {
            return EMPTY_LABEL;
        }
        int shownMax = Math.min(BONUS_INLINE_MAX, bonuses.size());
        for (int shown = shownMax; shown >= 1; shown--) {
            MutableComponent line = Component.empty();
            for (int i = 0; i < shown; i++) {
                if (i > 0) {
                    line.append(" · ");
                }
                line.append(bonuses.get(i).inline());
            }
            int rest = bonuses.size() - shown;
            if (rest > 0) {
                line.append(" +" + rest);
            }
            if (font.width(line) <= box || shown == 1) {
                return HsUi.fitLabel(font, line, box);
            }
        }
        return EMPTY_LABEL;
    }

    private TradeView buildTradeView(Profession profession, int pageWidth) {
        if (snapshot == null || font == null) {
            return TradeView.NONE;
        }
        Attribute primary = SkillLevels.primaryOf(profession).orElse(null);
        Attribute secondary = SkillLevels.secondaryOf(profession).orElse(null);
        int primaryValue = snapshotAttribute(primary);
        int secondaryValue = snapshotAttribute(secondary);
        int wits = snapshotAttribute(Attribute.WITS);
        boolean trade = profession.employed() && !profession.martial();
        boolean martial = profession.martial();
        List<Bonus> bonuses = new ArrayList<>();
        List<Component> tooltip = new ArrayList<>();
        Component levelText = Component.empty();
        Component xpText = Component.empty();
        float progress = 0.0F;
        String qa;
        if (trade) {
            int xp = settler.tradeXp();
            int level = SkillLevels.levelOf(xp);
            progress = SkillLevels.progress(xp);
            levelText = Component.translatableWithFallback("hearthstead.settler.trade_level", "%s · Level %s",
                profession.displayName(), level);
            if (level >= SkillLevels.MAX_LEVEL) {
                xpText = Component.translatableWithFallback("hearthstead.settler.trade_xp_max", "%s XP · max", xp);
            } else {
                int base = SkillLevels.xpForLevel(level);
                int step = SkillLevels.xpForLevel(level + 1) - base;
                xpText = Component.translatableWithFallback("hearthstead.settler.trade_xp", "%s / %s XP",
                    xp - base, step);
            }
            tooltip.add(levelText.copy().withStyle(ChatFormatting.WHITE));
            tooltip.add(level >= SkillLevels.MAX_LEVEL
                ? Component.translatableWithFallback("hearthstead.settler.trade_xp_max.tip",
                    "Master of the trade (%s XP)", xp).withStyle(ChatFormatting.GRAY)
                : Component.translatableWithFallback("hearthstead.settler.trade_xp.tip",
                    "%s XP in total; level %s at %s XP", xp, level + 1,
                    SkillLevels.xpForLevel(level + 1)).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatableWithFallback("hearthstead.settler.trade_xp.source",
                "Grows only from finished work in this trade.").withStyle(ChatFormatting.DARK_GRAY));
            Component craftsAt = craftsAtLine(profession, level, primaryValue);
            if (craftsAt != null) {
                tooltip.add(craftsAt);
            }
            tradeBonuses(bonuses, profession, level, primaryValue, secondaryValue, wits);
            if (primary != null) {
                tooltip.add(primaryBonusLine(profession, primary, level, primaryValue));
            }
            if (secondary != null) {
                tooltip.add(secondaryBonusLine(profession, secondary, level, secondaryValue));
            }
            tooltip.add(witsBonusLine(wits));
            qa = "trade:" + level + ":" + xp;
        } else if (martial) {
            int experience = settler.combatExperience();
            GuardExperience.Tier tier = GuardExperience.tierOf(experience);
            int next = GuardExperience.nextThreshold(experience);
            int base = tier.threshold();
            progress = tier == GuardExperience.Tier.HERO || next <= base ? 1.0F
                : Mth.clamp((experience - base) / (float) (next - base), 0.0F, 1.0F);
            levelText = Component.translatableWithFallback("hearthstead.settler.combat_level", "%s · Tier %s",
                profession.displayName(), tier.level());
            xpText = tier == GuardExperience.Tier.HERO
                ? Component.translatableWithFallback("hearthstead.settler.trade_xp_max", "%s XP · max", experience)
                : Component.translatableWithFallback("hearthstead.settler.trade_xp", "%s / %s XP",
                    experience - base, next - base);
            tooltip.add(levelText.copy().withStyle(ChatFormatting.WHITE));
            tooltip.add(Component.translatableWithFallback("hearthstead.settler.combat_xp.tip",
                "%s combat XP in total; next tier at %s", experience, next).withStyle(ChatFormatting.GRAY));
            Component rank = rankName(profession);
            if (rank != null) {
                tooltip.add(Component.translatable("hearthstead.settler.sheet.rank", rank)
                    .withStyle(ChatFormatting.GOLD));
            }
            qa = "combat:" + tier.level() + ":" + experience;
        } else {
            return TradeView.NONE;
        }
        developmentBonuses(bonuses, profession);

        HsUi.FittedLabel xp = HsUi.fitLabel(font, xpText, Math.min(76, pageWidth / 3));
        HsUi.FittedLabel level = HsUi.fitLabel(font, levelText, Math.max(1, pageWidth - 20 - xp.width() - 6));
        List<Component> bonusTooltip = new ArrayList<>(bonuses.size() + 1);
        bonusTooltip.add(Component.translatableWithFallback("hearthstead.settler.bonus.title", "Active bonuses")
            .withStyle(ChatFormatting.GOLD));
        for (Bonus bonus : bonuses) {
            bonusTooltip.add(bonus.detail().copy().withStyle(ChatFormatting.GRAY));
        }
        HsUi.FittedLabel inline = inlineBonuses(bonuses, pageWidth - 20);
        if (bonuses.isEmpty()) {
            inline = HsUi.fitLabel(font, Component.translatableWithFallback("hearthstead.settler.bonus.none",
                "No bonuses yet"), Math.max(1, pageWidth - 20));
            bonusTooltip.add(Component.translatableWithFallback("hearthstead.settler.bonus.none.tip",
                "Trade levels and Development bonuses will show here.").withStyle(ChatFormatting.GRAY));
        }
        return new TradeView(true, level, xp, progress, List.copyOf(tooltip), inline,
            List.copyOf(bonusTooltip), bonuses.size(), qa);
    }

    private static Component primaryBonusLine(Profession profession, Attribute primary, int level, int value) {
        int start = primaryStartLevel(profession);
        boolean wired = start > 0;
        List<Component> lines = wired ? SkillLevels.describeBonuses(profession, level, value, 0, 0) : List.of();
        return (!lines.isEmpty()
            ? Component.translatableWithFallback("hearthstead.settler.bonus.primary", "%s (primary): %s",
                primary.displayName(), lines.get(0))
            : wired
                ? Component.translatableWithFallback("hearthstead.settler.bonus.primary.soon",
                    "%s (primary): job bonus from level %s", primary.displayName(), start)
                : Component.translatableWithFallback("hearthstead.settler.bonus.primary.trains",
                    "%s (primary): trained by every finished job", primary.displayName()))
            .withStyle(ChatFormatting.GREEN);
    }

    private static Component secondaryBonusLine(Profession profession, Attribute secondary, int level, int value) {
        int start = secondaryStartLevel(profession);
        boolean wired = start > 0;
        List<Component> lines = wired ? SkillLevels.describeBonuses(profession, level, 0, value, 0) : List.of();
        return (!lines.isEmpty()
            ? Component.translatableWithFallback("hearthstead.settler.bonus.secondary", "%s (secondary): %s",
                secondary.displayName(), lines.get(lines.size() - 1))
            : wired
                ? Component.translatableWithFallback("hearthstead.settler.bonus.secondary.soon",
                    "%s (secondary): side bonus from level %s", secondary.displayName(), start)
                : Component.translatableWithFallback("hearthstead.settler.bonus.secondary.trains",
                    "%s (secondary): trained at half rate by this job", secondary.displayName()))
            .withStyle(ChatFormatting.GOLD);
    }

    private static Component witsBonusLine(int wits) {
        double learn = SkillLevels.witsXpBonus(wits);
        return (learn > 0.0D
            ? Component.translatableWithFallback("hearthstead.settler.bonus.wits", "Learns %s faster (%s)",
                percent(learn), Attribute.WITS.displayName())
            : Component.translatableWithFallback("hearthstead.settler.bonus.wits.none",
                "%s: learns faster above %s", Attribute.WITS.displayName(),
                com.hearthstead.entity.SettlerAttributes.START_CAP))
            .withStyle(ChatFormatting.AQUA);
    }

    /** Life need in words for the status line; empty when none. */
    static Component lifeNeedLine(int code) {
        return switch (code) {
            case LifeNeed.HUNGRY_NO_FOOD -> Component.translatableWithFallback(
                "hearthstead.settler.life_need.hungry_no_food", "Hungry — the Banner's stores are empty");
            case LifeNeed.HOMELESS -> Component.translatableWithFallback(
                "hearthstead.settler.life_need.homeless", "No bed for the night");
            case LifeNeed.FRIGHTENED -> Component.translatableWithFallback(
                "hearthstead.settler.life_need.frightened", "Frightened — shaken by the raid");
            case LifeNeed.EXHAUSTED -> Component.translatableWithFallback(
                "hearthstead.settler.life_need.exhausted", "Exhausted — needs rest");
            case LifeNeed.WANTS_TAVERN -> Component.translatableWithFallback(
                "hearthstead.settler.life_need.wants_tavern", "Wants a Tavern for the evening");
            case LifeNeed.TAVERN_NO_FOOD -> Component.translatableWithFallback(
                "hearthstead.settler.life_need.tavern_no_food", "No food at the Tavern");
            default -> Component.empty();
        };
    }

    /** What the player can do about the life need; empty when nothing. */
    static Component lifeNeedFix(int code) {
        return switch (code) {
            case LifeNeed.HUNGRY_NO_FOOD -> Component.translatableWithFallback(
                "hearthstead.settler.life_need.hungry_no_food.fix", "Put food in the Banner's stores.");
            case LifeNeed.HOMELESS -> Component.translatableWithFallback(
                "hearthstead.settler.life_need.homeless.fix", "Build a house with a free bed.");
            case LifeNeed.FRIGHTENED -> Component.translatableWithFallback(
                "hearthstead.settler.life_need.frightened.fix", "Calms down once the threat has passed.");
            case LifeNeed.EXHAUSTED -> Component.translatableWithFallback(
                "hearthstead.settler.life_need.exhausted.fix", "Let them sleep; a bed restores energy.");
            case LifeNeed.WANTS_TAVERN -> Component.translatableWithFallback(
                "hearthstead.settler.life_need.wants_tavern.fix", "Build a Tavern for evening company.");
            case LifeNeed.TAVERN_NO_FOOD -> Component.translatableWithFallback(
                "hearthstead.settler.life_need.tavern_no_food.fix",
                "Put ready meals and empty bottles in a Tavern chest; a Courier refills it from the Banner.");
            default -> Component.empty();
        };
    }

    static JobImpactEvidence jobImpactEvidence(Profession profession, JobAttributeProfile.Slot slot) {
        // Attributes lane (plan/ATTRIBUTES.md): the profile's own evidence
        // status decides; only LIVE_VERIFIED effects are sold as live.
        if (slot.status().mayDescribeAsLive()) {
            return JobImpactEvidence.LIVE;
        }
        if (slot.attribute() == Attribute.WITS && slot.effect() == JobAttributeProfile.EffectId.LEARNING_RATE) {
            return JobImpactEvidence.LIVE;
        }
        if (profession == Profession.LUMBERER && slot.effect() == JobAttributeProfile.EffectId.LUMBER_CONTACTS) {
            return JobImpactEvidence.LIVE;
        }
        if ((profession == Profession.GUARD && slot.attribute() == Attribute.STRENGTH)
            || (profession == Profession.ARCHER && slot.attribute() == Attribute.DEXTERITY)
            || (profession == Profession.FISHER && slot.attribute() == Attribute.DEXTERITY)) {
            return JobImpactEvidence.LIVE;
        }
        return JobImpactEvidence.ROLE_PRIORITY;
    }

    /** Both call sites already guard {@code snapshot != null} before reaching here. */
    private Component boonName() {
        return Component.translatable("hearthstead.mayor.boon." + snapshot.boonKey());
    }

    private Component boonDescription() {
        return Component.translatable("hearthstead.mayor.boon." + snapshot.boonKey() + ".desc");
    }

    /**
     * The server publishes a concrete preferred item, while Lumberer accepts
     * every axe in the supplied item tag. Keep the player-facing request as
     * broad as the authoritative acceptance rule.
     */
    private Component requestedEquipmentName(ItemStack request) {
        if (settler.getProfession() == Profession.LUMBERER && request.is(Items.IRON_AXE)) {
            return Component.translatable("hearthstead.settler.request.category.axe");
        }
        return request.getHoverName();
    }

    private Component buildingName() {
        if (snapshot == null || snapshot.employerBuildingId().isEmpty()) {
            return Component.translatable("hearthstead.employ.unemployed");
        }
        BuildingType type = BuildingType.byId(snapshot.employerBuildingId());
        return type == null ? Component.literal(snapshot.employerBuildingId()) : type.displayName();
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
    public String qaUiState() {
        SheetView current = view();
        ItemStack request = current.requestStack;
        String equipment = request.isEmpty() ? "ready"
            : "needs_" + BuiltInRegistries.ITEM.getKey(request.getItem());
        int reason = snapshot == null ? -1 : snapshot.requestReasonOrdinal();
        return "work=" + settler.getActivity().name() + ",equipment="
            + equipment + ",reason=" + reason + ",panel=" + left + ":" + top
            + ":" + current.layout.panelWidth + ":" + current.layout.totalHeight + ",mode=SHEET"
            + ",page=ONE,scroll=0/0,traits="
            + (snapshot == null ? 0 : snapshot.traitOrdinals().size())
            + ",mayor=" + (snapshot != null && snapshot.isMayor())
            + ",profession=" + (renderedProfession == null ? "unbuilt" : renderedProfession.name())
            + ",dismiss=" + (dismissButton != null && dismissButton.visible)
            + ",workZone=" + (workZoneButton != null && workZoneButton.active)
            + ",trade=" + current.trade.qa()
            + ",bonuses=" + current.trade.bonusCount()
            + ",lifeNeed=" + settler.lifeNeed()
            + ",statusNeed=" + current.lifeNeed
            + ",home=" + (snapshot == null ? "?" : snapshot.hasBed() ? snapshot.homeBuildingId() + "+bed" : "none");
    }

    // ================================================================== layout ===

    record UiRect(int x, int y, int width, int height) {
        int right() {
            return x + width;
        }

        int bottom() {
            return y + height;
        }

        boolean contains(double px, double py) {
            return px >= x && px < x + width && py >= y && py < y + height;
        }

        boolean overlaps(UiRect o) {
            return x < o.x + o.width && x + width > o.x && y < o.y + o.height && y + height > o.y;
        }
    }

    /** Pure sheet geometry in panel-local pixels; see {@link #layoutFor}. */
    static final class Layout {
        int panelWidth;
        int totalHeight;
        boolean narrowCounters;
        boolean widePage;
        UiRect header;
        UiRect crest;
        UiRect title;
        UiRect close;
        UiRect[] counters;
        int headerRuleY;
        UiRect body;
        UiRect left;
        UiRect portrait;
        UiRect needs;
        UiRect right;
        UiRect page;
        UiRect footer;
    }

    /**
     * The sheet uses most of the window up to 640x360 and never less than
     * 304x224 (the 320x240 viewport of GUI scale 4 on a small window, or of
     * 1280x960 at scale 4). Header like the Banner screen; below it the
     * portrait column (27% of the body, 84-150 px) and the page column.
     */
    static Layout layoutFor(int viewportWidth, int viewportHeight) {
        Layout l = new Layout();
        int w = Math.max(MIN_W, Math.min(MAX_W, viewportWidth - 16));
        int h = Math.max(MIN_H, Math.min(MAX_H, viewportHeight - 16));
        l.panelWidth = Math.min(w, Math.max(1, viewportWidth));
        l.totalHeight = Math.min(h, Math.max(1, viewportHeight));
        w = l.panelWidth;
        h = l.totalHeight;
        UiRect inner = new UiRect(FRAME, FRAME, w - FRAME * 2, h - FRAME * 2);
        l.header = new UiRect(inner.x() + PAD, inner.y() + 4, inner.width() - PAD * 2, HEADER_H);
        l.crest = new UiRect(inner.x() + PAD, -2, 26, HEADER_H + 10);
        l.close = new UiRect(w - FRAME - GUTTER - 11, l.header.y() + 1, 11, 11);
        l.narrowCounters = w < 460;
        int counterW = l.narrowCounters ? 50 : 62;
        int countersX = l.close.x() - GUTTER - counterW * COUNTERS;
        l.counters = new UiRect[COUNTERS];
        for (int i = 0; i < COUNTERS; i++) {
            l.counters[i] = new UiRect(countersX + i * counterW, l.header.y() + 3, counterW - 4, 22);
        }
        int titleX = l.crest.right() + GUTTER;
        l.title = new UiRect(titleX, l.header.y() + 1, Math.max(40, countersX - GUTTER - titleX), 26);
        l.headerRuleY = l.header.bottom() + 2;
        int bodyTop = l.headerRuleY + 4;
        int bodyBottom = inner.bottom() - 4;
        l.body = new UiRect(inner.x() + PAD, bodyTop, inner.width() - PAD * 2, Math.max(1, bodyBottom - bodyTop));
        // One parchment body (owner, 26 Sep): a small portrait top-left, the
        // content page over the whole body, one action row as the footer.
        l.left = l.body;
        l.right = l.body;
        l.footer = new UiRect(l.body.x() + PAD, l.body.bottom() - PAD - FOOTER_H, l.body.width() - PAD * 2,
            FOOTER_H);
        int pageTop = l.body.y() + PAD;
        l.page = new UiRect(l.body.x() + PAD, pageTop, l.body.width() - PAD * 2,
            Math.max(1, l.footer.y() - 2 - pageTop));
        l.portrait = new UiRect(l.page.x() + 1, l.page.y() + 1, PORTRAIT_W - 2, PORTRAIT_H - 2);
        l.needs = new UiRect(l.page.x(), l.page.y() + L_NEEDS, columnWidth(l.page.width()), NEED_ROW * 2 + 2);
        l.widePage = l.page.width() >= WIDE_PAGE_MIN;
        return l;
    }

    // =========================================================== view records ===

    private enum JobAttributeHighlight {
        NONE(0),
        PRIMARY(Ui2Palette.FOREST),
        SECONDARY(Ui2Palette.GOLD),
        SUPPORT(0);

        private final int colour;

        JobAttributeHighlight(int colour) {
            this.colour = colour;
        }

        private int colour() {
            return colour;
        }

        private boolean isJobFocus() {
            return this == PRIMARY || this == SECONDARY;
        }
    }

    enum JobImpactEvidence {
        LIVE,
        ROLE_PRIORITY
    }

    /** A fitted label plus the full text as a tooltip when the fit had to cut it. */
    private record Txt(HsUi.FittedLabel fit, boolean clipped, List<Component> tooltip) {
        Component fullOrFit() {
            return tooltip.isEmpty() ? fit.text() : tooltip.get(0);
        }
    }

    private record AttributeView(HsUi.FittedLabel label, HsUi.FittedLabel value, float ratio, boolean knack,
                                 JobAttributeHighlight highlight, List<Component> tooltip) {
    }

    private record JobImpactView(HsUi.FittedLabel label, HsUi.FittedLabel effect, boolean live,
                                 JobAttributeHighlight highlight, List<Component> tooltip) {
    }

    private record EffectView(HsUi.FittedLabel text, int colour) {
    }

    /** Snapshot-authored trait row; its effect list is never rebuilt in render. */
    private record TraitView(HsUi.FittedLabel name, HsUi.FittedLabel description, List<EffectView> effects,
                             List<Component> tooltip) {
    }

    /** Immutable-by-convention render projection, replaced as a unit. */
    private static final class SheetView {
        Layout layout;
        String name = "";
        List<Component> nameClippedTooltip;
        Txt subtitle;
        HsUi.FittedLabel healthCaption;
        boolean showLevel;
        HsUi.FittedLabel levelCaption;
        HsUi.FittedLabel levelValue;
        List<Component> portraitTooltip = List.of();
        HsUi.FittedLabel[] needLabels;
        List<Component> hungerTooltip = List.of();
        List<Component> energyTooltip = List.of();
        List<Component> moraleTooltip = List.of();
        String headRightNow = "";
        String headWork = "";
        String headHome = "";
        String headStanding = "";
        String headAttributes = "";
        String headJobFocus = "";
        String headTraits = "";
        String headWorn = "";
        String headBag = "";
        String headWanted = "";
        String headCarrying = "";
        HsUi.FittedLabel carryNone;
        HsUi.FittedLabel loading;
        AttributeView[] attributes;
        TraitView[] traits;
        JobImpactView[] jobImpacts;
        TradeView trade = TradeView.NONE;
        HsUi.FittedLabel skillsFooter;
        ItemStack requestStack = ItemStack.EMPTY;
        Txt nextAction;
        int nextActionColour;
        List<Component> statusTooltip = List.of();
        int statusTextWidth;
        int lifeNeed;
        List<FormattedCharSequence> refusalLines = List.of();
        boolean workplaceMissing;
        ItemStack workplaceIcon = ItemStack.EMPTY;
        Txt workplaceName;
        Txt workplaceDetail;
        List<Component> workplaceTooltip = List.of();
        Txt homeName;
        Txt homeDetail;
        boolean homeWarn;
        List<Component> homeTooltip = List.of();
        Txt mayorLine;
        List<Component> mayorTooltip = List.of();
        Txt combatLine;
        List<Component> combatTooltip = List.of();
        Txt blessingLine;
        ItemStack[] bagStacks;
        boolean[] bagLocked;
        List<Component>[] bagTooltips;
        HsUi.FittedLabel bagCount;
        HsUi.FittedLabel[] tierChips;
        List<Component>[] tierTooltips;
        boolean allTiersOpen;
        Txt nextUnlock;
        List<Component>[] wornEmptyTooltips;
        Txt requestName;
        Txt requestInstruction;
        List<Component> requestTooltip = List.of();
        Component dismissTooltip = Component.empty();
        Component appointTooltip = Component.empty();
        /** One-page sheet: traits, bonuses and record behind one hover line. */
        HsUi.FittedLabel moreHint;
        List<Component> moreTooltip = List.of();
        HsUi.FittedLabel noFocus;
        HsUi.FittedLabel inventoryHint;
        String headBlessings = "";
        ItemStack[] blessingStacks = new ItemStack[0];
        boolean[] blessingActive = new boolean[0];
        List<Component>[] blessingTips;
        boolean anyBlessing;
        HsUi.FittedLabel blessingSummary;
        List<Component> blessingHowTo = List.of();
    }

    /** Screen-owned cache for the integer labels beside need bars. */
    static final class NeedValueCache {
        private static final int VALUE_BOX = 32;
        private final NeedValueFitter fitter;
        private final int[] renderedValues = {
            Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE
        };
        private final Font[] fonts = new Font[renderedValues.length];
        private final HsUi.FittedLabel[] labels = new HsUi.FittedLabel[renderedValues.length];

        NeedValueCache() {
            this(HsUi::fitLabel);
        }

        NeedValueCache(NeedValueFitter fitter) {
            this.fitter = fitter;
        }

        HsUi.FittedLabel valueFor(int slot, float value, Font font) {
            if (slot < 0 || slot >= renderedValues.length) {
                throw new IllegalArgumentException("unknown need slot " + slot);
            }
            int rendered = (int) value;
            if (renderedValues[slot] != rendered || fonts[slot] != font) {
                renderedValues[slot] = rendered;
                fonts[slot] = font;
                Component component = Component.literal(Integer.toString(rendered) + (slot == 3 ? "%" : ""));
                labels[slot] = fitter.fit(font, component, VALUE_BOX);
            }
            return labels[slot];
        }
    }

    @FunctionalInterface
    interface NeedValueFitter {
        HsUi.FittedLabel fit(Font font, Component component, int width);
    }
}
