package com.hearthstead.client.patrol;

import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.entity.Profession;
import com.hearthstead.network.PatrolActionPayload;
import com.hearthstead.network.PatrolSnapshotPayload;
import com.hearthstead.settlement.guard.patrol.PatrolPalette;
import com.hearthstead.settlement.guard.patrol.PatrolRules;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The Patrol Map's route screen: the settlement's routes on the left, the
 * selected route on the right -- name, loop, formation, "guards per shift",
 * who walks it right now, and the guard picker. Every change is a request;
 * what is drawn is always the server's last answer.
 */
public final class PatrolRouteScreen extends Screen {
    private static final int W = 330;
    private static final int H = 232;
    private static final int PAD = 10;
    private static final int LIST_W = 112;
    private static final int ROW_H = 16;
    private static final int GUARD_ROWS = 4;

    private PatrolSnapshotPayload snapshot;
    private int selectedId;
    private int guardPage;
    private EditBox nameBox;
    private int left;
    private int top;
    private Ui2FrameLayout frame;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);

    public PatrolRouteScreen(PatrolSnapshotPayload snapshot) {
        super(Component.literal("Patrol Routes"));
        this.snapshot = snapshot;
        this.selectedId = snapshot.selectedRoute() != PatrolSnapshotPayload.NO_ROUTE ? snapshot.selectedRoute()
            : snapshot.routes().isEmpty() ? PatrolSnapshotPayload.NO_ROUTE : snapshot.routes().get(0).id();
    }

    public UUID settlementId() {
        return snapshot.settlementId();
    }

    public void update(PatrolSnapshotPayload next) {
        // Keep a half-typed name across the server's refreshes (they arrive every few seconds).
        int before = selectedId;
        String typed = nameBox != null && nameBox.isFocused() ? nameBox.getValue() : null;
        snapshot = next;
        if (next.selectedRoute() != PatrolSnapshotPayload.NO_ROUTE) selectedId = next.selectedRoute();
        if (next.route(selectedId) == null) {
            selectedId = next.routes().isEmpty() ? PatrolSnapshotPayload.NO_ROUTE : next.routes().get(0).id();
        }
        rebuildWidgets();
        if (typed != null && before == selectedId && nameBox != null) {
            nameBox.setValue(typed);
            setFocused(nameBox);
        }
    }

    private PatrolSnapshotPayload.Route selected() {
        return snapshot.route(selectedId);
    }

    private void send(PatrolActionPayload.Kind kind, int routeId, int value) {
        PacketDistributor.sendToServer(PatrolActionPayload.of(snapshot.settlementId(), kind, routeId, value,
            snapshot.revision()));
    }

    @Override
    protected void init() {
        frame = frameFor(width, height);
        left = contentLeft(frame);
        top = contentTop(frame);
        addRenderableWidget(Ui2Frame.closeKey(frame, this::onClose));
        int listX = left + PAD;
        int newY = top + 28 + PatrolRules.MAX_ROUTES * ROW_H + 4;
        Ui2Button create = Ui2Button.secondary(listX, newY, LIST_W, 14, Component.literal("+ New route"),
            () -> send(PatrolActionPayload.Kind.CREATE, -1, 0));
        create.active = snapshot.routes().size() < PatrolRules.MAX_ROUTES;
        addRenderableWidget(create);

        PatrolSnapshotPayload.Route route = selected();
        if (route == null) return;
        int x = left + PAD + LIST_W + 10;
        int w = left + W - PAD - x;
        nameBox = new EditBox(font, x, top + 26, w - 58, 14, Component.literal("Route name"));
        nameBox.setMaxLength(PatrolRules.MAX_NAME_LENGTH);
        nameBox.setValue(route.name());
        addRenderableWidget(nameBox);
        addRenderableWidget(Ui2Button.secondary(x + w - 54, top + 26, 54, 14, Component.literal("Rename"),
            this::rename));

        int rowY = top + 60;
        addRenderableWidget(Ui2Button.secondary(x, rowY, 76, 14,
            Component.literal(route.loop() ? "Loop: closed" : "Loop: open"),
            () -> send(PatrolActionPayload.Kind.TOGGLE_LOOP, route.id(), 0)));
        addRenderableWidget(Ui2Button.secondary(x + 80, rowY, 96, 14,
            Component.literal("Formation: " + (route.formation() == 1 ? "pairs" : "column")),
            () -> send(PatrolActionPayload.Kind.SET_FORMATION, route.id(), route.formation() == 1 ? 0 : 1)));

        int shiftY = top + 78;
        Ui2Button minus = Ui2Button.secondary(x + w - 40, shiftY, 18, 14, Component.literal("−"),
            () -> send(PatrolActionPayload.Kind.SET_PER_SHIFT, route.id(), route.perShift() - 1));
        minus.active = route.perShift() > 0;
        Ui2Button plus = Ui2Button.secondary(x + w - 18, shiftY, 18, 14, Component.literal("+"),
            () -> send(PatrolActionPayload.Kind.SET_PER_SHIFT, route.id(), route.perShift() + 1));
        plus.active = route.perShift() < PatrolRules.MAX_PER_SHIFT;
        addRenderableWidget(minus);
        addRenderableWidget(plus);

        int pages = Math.max(1, (snapshot.guards().size() + GUARD_ROWS - 1) / GUARD_ROWS);
        guardPage = Math.max(0, Math.min(pages - 1, guardPage));
        if (pages > 1) {
            int pageY = top + 124 + GUARD_ROWS * ROW_H + 2;
            addRenderableWidget(Ui2Button.secondary(x, pageY, 30, 12, Component.literal("‹"), () -> {
                guardPage = Math.max(0, guardPage - 1);
                rebuildWidgets();
            }));
            addRenderableWidget(Ui2Button.secondary(x + 34, pageY, 30, 12, Component.literal("›"), () -> {
                guardPage = Math.min(pages - 1, guardPage + 1);
                rebuildWidgets();
            }));
        }
        addRenderableWidget(Ui2Button.danger(left + W - PAD - 70, top + H - 24, 70, 16,
            Component.literal("Delete route"), () -> send(PatrolActionPayload.Kind.DELETE, route.id(), 0)));
    }

    private void rename() {
        PatrolSnapshotPayload.Route route = selected();
        if (route == null || nameBox == null) return;
        PacketDistributor.sendToServer(new PatrolActionPayload(snapshot.settlementId(),
            PatrolActionPayload.Kind.RENAME, route.id(), 0, PatrolActionPayload.NONE, nameBox.getValue(),
            snapshot.revision()));
    }

    @Override
    public boolean keyPressed(int key, int scan, int modifiers) {
        if (nameBox != null && nameBox.isFocused() && (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER)) {
            rename();
            return true;
        }
        return super.keyPressed(key, scan, modifiers);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(g);
        Ui2Frame.draw(g, frame);
        Ui2Frame.title(g, font, frame, titleText, title.getString(), null);
        String count = snapshot.routes().size() + " / " + PatrolRules.MAX_ROUTES;
        g.drawString(font, count, frame.close().x() - Ui2FrameLayout.M - font.width(count),
            frame.title().y() + 7, BannerChrome.TEXT_ON_WOOD_MUTED, false);

        // Route list.
        int listX = left + PAD;
        for (int i = 0; i < snapshot.routes().size(); i++) {
            PatrolSnapshotPayload.Route r = snapshot.routes().get(i);
            int y = top + 26 + i * ROW_H;
            boolean hover = mouseX >= listX && mouseX < listX + LIST_W && mouseY >= y && mouseY < y + ROW_H;
            Ui2Surface.row(g, listX, y, LIST_W, ROW_H - 1, hover ? 1.0F : 0.0F, r.id() == selectedId);
            g.fill(listX + 5, y + 4, listX + 11, y + 10, PatrolPalette.ink(r.color()));
            String name = font.plainSubstrByWidth(r.name(), LIST_W - 36);
            g.drawString(font, name, listX + 15, y + 4, r.valid() ? Ui2Palette.INK : Ui2Palette.INK_MUTED, false);
            String n = r.squad().isEmpty() ? "" : String.valueOf(r.squad().size());
            if (!n.isEmpty()) {
                g.drawString(font, n, listX + LIST_W - 6 - font.width(n), y + 4, Ui2Palette.FOREST, false);
            }
        }
        if (snapshot.routes().isEmpty()) {
            HsUi.drawLines(g, font, font.split(Component.literal(
                "No routes yet. Right-click the ground with the Patrol Map to start one."), LIST_W - 4),
                listX + 2, top + 28, Ui2Palette.INK_MUTED);
        }
        Ui2Surface.ruleVertical(g, left + PAD + LIST_W + 4, top + 24, H - 52);

        PatrolSnapshotPayload.Route route = selected();
        int x = left + PAD + LIST_W + 10;
        int w = left + W - PAD - x;
        if (route != null) {
            double length = PatrolRules.length(route.waypoints(), route.loop());
            String facts = route.waypoints().size() + " of " + PatrolRules.MAX_WAYPOINTS + " waypoints · "
                + Math.round(length) + " blocks";
            g.drawString(font, facts, x, top + 46, route.valid() ? Ui2Palette.INK_SOFT : Ui2Palette.DANGER, false);
            if (!route.valid()) {
                g.drawString(font, route.waypoints().size() < PatrolRules.MIN_WAYPOINTS ? "Needs 2 waypoints"
                    : "Outside the limits", x + w - font.width("Outside the limits"), top + 46, Ui2Palette.DANGER,
                    false);
            }
            g.drawString(font, "Guards per shift: " + (route.perShift() == 0 ? "picked only" : route.perShift()),
                x, top + 81, Ui2Palette.INK, false);
            Ui2Surface.sectionHeader(g, font, Component.literal("WALKING NOW"), x, top + 98, w);
            g.drawString(font, font.plainSubstrByWidth(walkingLine(route), w), x, top + 109,
                route.squad().isEmpty() ? Ui2Palette.INK_MUTED : Ui2Palette.FOREST, false);
            Ui2Surface.sectionHeader(g, font, Component.literal("PICK GUARDS"), x, top + 120 - 2, w);
            renderGuards(g, route, x, top + 124 + 4, w, mouseX, mouseY);
        }
        String hint = "Ground: add waypoint · click 1: close loop · sneak: remove";
        g.drawString(font, font.plainSubstrByWidth(hint, W - PAD * 2 - 76), left + PAD, top + H - 19,
            Ui2Palette.INK_MUTED, false);
        // Widgets on top of the sheet (Screen.render would paint the background over it).
        HsUi.widgets(this, g, mouseX, mouseY, partialTick);
    }

    private String walkingLine(PatrolSnapshotPayload.Route route) {
        if (route.squad().isEmpty()) {
            return route.waypoints().size() < PatrolRules.MIN_WAYPOINTS ? "Nobody yet: set 2 waypoints"
                : "Nobody on watch for this route";
        }
        Map<UUID, String> names = names();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < route.squad().size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(names.getOrDefault(route.squad().get(i), "Guard"));
            if (i == 0) sb.append(" (leads)");
        }
        return sb.toString();
    }

    private Map<UUID, String> names() {
        Map<UUID, String> names = new HashMap<>();
        for (PatrolSnapshotPayload.Guard guard : snapshot.guards()) names.put(guard.id(), guard.name());
        return names;
    }

    private List<PatrolSnapshotPayload.Guard> pageGuards() {
        List<PatrolSnapshotPayload.Guard> all = snapshot.guards();
        List<PatrolSnapshotPayload.Guard> page = new ArrayList<>();
        for (int i = guardPage * GUARD_ROWS; i < all.size() && page.size() < GUARD_ROWS; i++) page.add(all.get(i));
        return page;
    }

    private void renderGuards(GuiGraphics g, PatrolSnapshotPayload.Route route, int x, int y, int w,
                              int mouseX, int mouseY) {
        List<PatrolSnapshotPayload.Guard> page = pageGuards();
        if (page.isEmpty()) {
            g.drawString(font, "No guards or foot soldiers yet.", x, y + 3, Ui2Palette.INK_MUTED, false);
            return;
        }
        for (int i = 0; i < page.size(); i++) {
            PatrolSnapshotPayload.Guard guard = page.get(i);
            int rowY = y + i * ROW_H;
            boolean picked = route.picked().contains(guard.id());
            String elsewhere = pickedElsewhere(guard.id(), route.id());
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + ROW_H;
            Ui2Surface.row(g, x, rowY, w, ROW_H - 1, hover ? 1.0F : 0.0F, picked);
            // check box
            g.fill(x + 4, rowY + 3, x + 13, rowY + 12, Ui2Palette.RULE_STRONG);
            g.fill(x + 5, rowY + 4, x + 12, rowY + 11, Ui2Palette.PAPER);
            if (picked) g.fill(x + 6, rowY + 5, x + 11, rowY + 10, Ui2Palette.FOREST);
            String name = font.plainSubstrByWidth(guard.name().isEmpty() ? "Guard" : guard.name(), 64);
            g.drawString(font, name, x + 17, rowY + 4, Ui2Palette.INK, false);
            String detail = Profession.byId(guard.professionId()).displayName().getString() + " · "
                + (guard.nightWatch() ? "night" : "day")
                + (elsewhere != null ? " · on " + elsewhere : guard.busy() ? " · busy" : "");
            detail = font.plainSubstrByWidth(detail, w - 86);
            g.drawString(font, detail, x + 84, rowY + 4, guard.onWatch() ? Ui2Palette.INK_SOFT : Ui2Palette.INK_MUTED,
                false);
        }
    }

    private String pickedElsewhere(UUID guard, int routeId) {
        for (PatrolSnapshotPayload.Route r : snapshot.routes()) {
            if (r.id() != routeId && r.picked().contains(guard)) return r.name();
        }
        return null;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            int listX = left + PAD;
            for (int i = 0; i < snapshot.routes().size(); i++) {
                int y = top + 26 + i * ROW_H;
                if (mouseX >= listX && mouseX < listX + LIST_W && mouseY >= y && mouseY < y + ROW_H) {
                    PatrolSnapshotPayload.Route r = snapshot.routes().get(i);
                    selectedId = r.id();
                    guardPage = 0;
                    send(PatrolActionPayload.Kind.SELECT, r.id(), 0);
                    rebuildWidgets();
                    HsUi.playConfirmSound();
                    return true;
                }
            }
            PatrolSnapshotPayload.Route route = selected();
            if (route != null) {
                int x = left + PAD + LIST_W + 10;
                int w = left + W - PAD - x;
                int y = top + 128;
                List<PatrolSnapshotPayload.Guard> page = pageGuards();
                for (int i = 0; i < page.size(); i++) {
                    int rowY = y + i * ROW_H;
                    if (mouseX >= x && mouseX < x + w && mouseY >= rowY && mouseY < rowY + ROW_H) {
                        PacketDistributor.sendToServer(new PatrolActionPayload(snapshot.settlementId(),
                            PatrolActionPayload.Kind.TOGGLE_MEMBER, route.id(), 0, page.get(i).id(), "",
                            snapshot.revision()));
                        HsUi.playConfirmSound();
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * The standard window around the route editor. The editor's own grid
     * (list, detail, guard picker) keeps its coordinates relative to
     * {@link #contentLeft}/{@link #contentTop}: the old sheet's body now sits
     * exactly on the frame's content area.
     */
    static Ui2FrameLayout frameFor(int viewportW, int viewportH) {
        int fw = W - PAD * 2 + (Ui2FrameLayout.FRAME + Ui2FrameLayout.MARGIN + Ui2FrameLayout.PAD) * 2;
        int fh = H + FRAME_EXTRA_H;
        int x = Math.max(0, (viewportW - fw) / 2);
        int y = Math.max(0, (viewportH - fh) / 2);
        return Ui2FrameLayout.at(x, y, fw, fh, false);
    }

    /** Old sheet body top (sheet y + 24) = frame content top; old body bottom (y + H - 8) = content bottom. */
    private static final int BODY_TOP = 24;
    private static final int FRAME_EXTRA_H = 30;

    static int contentLeft(Ui2FrameLayout frame) {
        return frame.content().x() - PAD;
    }

    static int contentTop(Ui2FrameLayout frame) {
        return frame.content().y() - BODY_TOP;
    }

    static int sheetWidth() {
        return W;
    }

    static int sheetHeight() {
        return H;
    }

    /** First waypoint of the selected route (tests, QA). */
    public BlockPos firstWaypoint() {
        PatrolSnapshotPayload.Route route = selected();
        return route == null || route.waypoints().isEmpty() ? null : route.waypoints().get(0);
    }
}
