package com.hearthstead.client.ui2.map;

import com.hearthstead.client.ui.HsMotion;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerActivity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.RealmMapLayoutPayload;
import com.hearthstead.network.RealmMapStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The Banner screen's live realm map: parchment terrain, claim, building
 * footprints, the Banner, players and every loaded settler, with pan, zoom,
 * hover and selection.
 *
 * <p>Display only. It reads {@link RealmMapClient} (server projections) and
 * the client's own loaded chunks; it never sends anything itself -- the
 * screen owns the subscription and the focus request.
 *
 * <p>Frame discipline: every per-frame buffer (marker and footprint screen
 * positions, disc row tables) is preallocated; transforms are applied to
 * the current pose matrix in place and undone, so the render path creates
 * no objects of its own. Tooltip lines are rebuilt only when what is hovered
 * changes.
 */
public final class RealmMapView {
    public interface Listener {
        /** Selection changed through the map (click, keyboard, clear). */
        void onMapSelection();
    }

    public static final int CONTROL = 13;
    static final int CAPACITY = 256;
    static final float HIT_RADIUS = 6.0F;
    static final long SELECT_MS = 200L;
    private static final float Z_MARKERS = 300.0F;

    private final Listener listener;
    private final RealmMapCamera camera = new RealmMapCamera();
    private RealmMapTerrain terrain;
    private int boundCenterX;
    private int boundCenterZ;
    private int boundRadius = -1;
    private int boundW = -1;
    private int boundH = -1;
    private double boundScale = -1;
    private boolean placed;

    // Selection and hover.
    private UUID selectedSettler;
    private UUID selectedBuilding;
    private long selectedAtMs;
    private boolean follow;
    private int highlightProfession = -1;
    private UUID hoveredSettler;
    private int hoveredBuilding = -1;
    private boolean hoveredBanner;
    private int hoveredTalker = -1;
    private int selectedTalkerEntity = Integer.MIN_VALUE;
    private int hoveredControl = -1;
    private boolean legendOpen;
    // Patrol routes (PATROL ROUTES lane): last frame's waypoint dots, for hover.
    private static final int PATROL_DOTS = 96;
    private final float[] patrolDotX = new float[PATROL_DOTS];
    private final float[] patrolDotY = new float[PATROL_DOTS];
    private final int[] patrolDotRoute = new int[PATROL_DOTS];
    private final int[] patrolDotIndex = new int[PATROL_DOTS];
    private int patrolDotCount;
    private int hoveredPatrolRoute = -1;
    private int hoveredPatrolIndex = -1;
    private UUID patrolSettlement;

    // Drag.
    private boolean pressed;
    private boolean dragging;
    private double pressX;
    private double pressY;

    // Last frame geometry (absolute GUI coordinates).
    private int viewX;
    private int viewY;
    private int viewW;
    private int viewH;
    private double guiScale = 1.0D;

    // Per-frame screen positions for hit testing.
    private final float[] markerX = new float[CAPACITY];
    private final float[] markerY = new float[CAPACITY];
    private final MarkerTrack[] markerTrack = new MarkerTrack[CAPACITY];
    private int markerCount;
    private final float[] bLeft = new float[CAPACITY];
    private final float[] bTop = new float[CAPACITY];
    private final float[] bRight = new float[CAPACITY];
    private final float[] bBottom = new float[CAPACITY];
    private final int[] bIndex = new int[CAPACITY];
    private int buildingCount;
    private float bannerX = Float.NaN;
    private float bannerY = Float.NaN;

    // Disc row half-widths by diameter (index = diameter).
    private final int[][] discRows = new int[24][];

    // Tooltip cache.
    private List<FormattedCharSequence> tooltip = List.of();

    // Frame parameters for the batched passes (set each frame, read by the reusable runnables).
    private GuiGraphics pg;
    private RealmMapLayoutPayload pLayout;
    private Minecraft pMc;
    private int pX;
    private int pY;
    private int pW;
    private int pH;
    private double pVcx;
    private double pVcy;
    private long pNow;
    private int pPass;
    private final Runnable groundPass = () -> {
        drawVeil(pg, pLayout, pX, pY, pW, pH, pVcx, pVcy);
        drawNightTint(pg, pMc, pX, pY, pW, pH);
        drawClaim(pg, pLayout, pVcx, pVcy);
    };
    private final Runnable figurePass = this::runFigurePass;

    public RealmMapView(Listener listener) {
        this.listener = listener;
    }

    // ---------------------------------------------------------- selection ---

    public UUID selectedSettler() {
        return selectedSettler;
    }

    public UUID selectedBuilding() {
        return selectedBuilding;
    }

    public boolean following() {
        return follow && selectedSettler != null;
    }

    public int highlightProfession() {
        return highlightProfession;
    }

    public void setHighlightProfession(int professionId) {
        highlightProfession = professionId;
    }

    public void selectSettler(UUID id, boolean glide) {
        boolean changed = id != null && !id.equals(selectedSettler);
        if (id != null) selectedTalkerEntity = Integer.MIN_VALUE;
        selectedSettler = id;
        selectedBuilding = null;
        if (changed) selectedAtMs = Util.getMillis();
        if (id == null) follow = false;
        if (glide && id != null) {
            MarkerTrack track = RealmMapClient.track(id);
            if (track != null) camera.glideTo(track.x(Util.getMillis()), track.z(Util.getMillis()));
        }
    }

    public void selectBuilding(UUID id, boolean glide) {
        boolean changed = id != null && !id.equals(selectedBuilding);
        if (id != null) selectedTalkerEntity = Integer.MIN_VALUE;
        selectedBuilding = id;
        selectedSettler = null;
        follow = false;
        if (changed) selectedAtMs = Util.getMillis();
        if (glide && id != null) {
            RealmMapLayoutPayload.BuildingEntry b = building(id);
            if (b != null) camera.glideTo((b.minX() + b.maxX() + 1) / 2.0D, (b.minZ() + b.maxZ() + 1) / 2.0D);
        }
    }

    public void clearSelection() {
        selectedTalkerEntity = Integer.MIN_VALUE;
        selectedSettler = null;
        selectedBuilding = null;
        follow = false;
    }

    public void toggleFollow() {
        follow = selectedSettler != null && !follow;
    }

    public void centerOnBanner() {
        RealmMapLayoutPayload layout = RealmMapClient.layout();
        if (layout == null) return;
        follow = false;
        camera.glideTo(layout.centerX() + 0.5D, layout.centerZ() + 0.5D);
    }

    public boolean zoom(int delta) {
        return camera.zoomBy(delta, 0, 0);
    }

    public float zoomLevel() {
        return camera.targetZoom();
    }

    public static RealmMapLayoutPayload.BuildingEntry building(UUID id) {
        RealmMapLayoutPayload layout = RealmMapClient.layout();
        if (layout == null || id == null) return null;
        for (RealmMapLayoutPayload.BuildingEntry b : layout.buildings()) {
            if (b.id().equals(id)) return b;
        }
        return null;
    }

    /** Releases the terrain texture. The view can render again afterwards. */
    public void close() {
        if (terrain != null) {
            terrain.close();
            terrain = null;
        }
        boundRadius = -1;
        placed = false;
    }

    // ------------------------------------------------------------ render ---

    public void render(GuiGraphics g, Font font, int x, int y, int w, int h, int mouseX, int mouseY,
                       UUID settlementId, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        viewX = x;
        viewY = y;
        viewW = w;
        viewH = h;
        guiScale = mc.getWindow().getGuiScale();
        long now = Util.getMillis();
        g.fill(x, y, x + w, y + h, RealmMapPalette.UNKNOWN);
        RealmMapLayoutPayload layout = RealmMapClient.hasData(settlementId) ? RealmMapClient.layout() : null;
        if (layout == null || mc.level == null) {
            markerCount = 0;
            buildingCount = 0;
            patrolDotCount = 0;
            g.drawString(font, WAITING, x + (w - font.width(WAITING)) / 2, y + h / 2 - 4, Ui2Palette.INK_MUTED, false);
            drawFrame(g, x, y, w, h);
            return;
        }
        long perfStart = System.nanoTime();
        bind(layout, w, h);
        terrain.tick(mc.level);
        updatePings(layout, now);
        updateTrail(mc, now, partialTick);
        if (following()) {
            MarkerTrack track = RealmMapClient.track(selectedSettler);
            if (track != null) camera.glideTo(worldX(mc, track, now, partialTick), worldZ(mc, track, now, partialTick));
        }
        camera.update(now, HsMotion.enabled);
        double vcx = x + w / 2.0D;
        double vcy = y + h / 2.0D;
        updateHover(mouseX, mouseY);

        g.enableScissor(x, y, x + w, y + h);
        drawTerrain(g, vcx, vcy);
        // Fill-only layers are batched into one draw (GuiGraphics managed mode).
        pg = g;
        pLayout = layout;
        pMc = mc;
        pX = x;
        pY = y;
        pW = w;
        pH = h;
        pVcx = vcx;
        pVcy = vcy;
        pNow = now;
        g.drawManaged(groundPass);
        drawBuildings(g, layout, vcx, vcy);
        Matrix4f m = g.pose().last().pose();
        m.translate(0, 0, Z_MARKERS);
        drawPings(g, now, vcx, vcy);
        drawBanner(g, layout, vcx, vcy);
        drawPatrolRoutes(g, font, mc, now, vcx, vcy, settlementId, partialTick);
        drawTrail(g, vcx, vcy);
        drawSummonLines(g, mc, now, vcx, vcy, partialTick);
        drawPlayers(g, mc, vcx, vcy, partialTick);
        drawRaiders(g, now, vcx, vcy);
        drawMarkers(g, mc, now, vcx, vcy, partialTick);
        drawTalkers(g, font, now, vcx, vcy);
        drawSelectionLabel(g, font, layout, now);
        m.translate(0, 0, -Z_MARKERS);
        g.disableScissor();

        m.translate(0, 0, Z_MARKERS);
        drawRaidEdge(g, now, x, y, w, h);
        drawChrome(g, font, layout, x, y, w, h);
        m.translate(0, 0, -Z_MARKERS);
        perfSample(perfStart, now);
    }

    private void bind(RealmMapLayoutPayload layout, int w, int h) {
        boolean geometry = layout.centerX() != boundCenterX || layout.centerZ() != boundCenterZ
            || layout.radius() != boundRadius;
        if (terrain == null || geometry && !terrain.covers(layout.centerX(), layout.centerZ(), layout.radius())) {
            if (terrain != null) terrain.close();
            terrain = RealmMapTerrain.create(layout.centerX(), layout.centerZ(), layout.radius());
        }
        if (geometry || w != boundW || h != boundH || guiScale != boundScale) {
            boundCenterX = layout.centerX();
            boundCenterZ = layout.centerZ();
            boundRadius = layout.radius();
            boundW = w;
            boundH = h;
            boundScale = guiScale;
            float fit = Math.min(w, h) / (float) (layout.radius() * 2 + 12);
            camera.setSteps(RealmMapCamera.zoomSteps((int) Math.round(guiScale), fit));
            camera.setBounds(terrain.originX(), terrain.originZ(), terrain.originX() + terrain.size(),
                terrain.originZ() + terrain.size());
            if (!placed) {
                float start = 1.0F;
                for (float step : camera.steps()) if (step <= Math.max(fit, camera.steps()[0])) start = step;
                camera.jumpTo(layout.centerX() + 0.5D, layout.centerZ() + 0.5D, start);
                placed = true;
            }
        }
    }

    private void drawTerrain(GuiGraphics g, double vcx, double vcy) {
        float z = camera.zoom();
        float tx = RealmIcons.snap((float) camera.screenX(terrain.originX(), vcx), guiScale);
        float ty = RealmIcons.snap((float) camera.screenY(terrain.originZ(), vcy), guiScale);
        Matrix4f m = g.pose().last().pose();
        m.translate(tx, ty, 0).scale(z, z, 1.0F);
        int size = terrain.size();
        g.blit(terrain.location(), 0, 0, 0.0F, 0.0F, size, size, size, size);
        m.scale(1.0F / z, 1.0F / z, 1.0F).translate(-tx, -ty, 0);
    }

    /** Parchment veil over everything outside the claim circle, row by row. */
    private void drawVeil(GuiGraphics g, RealmMapLayoutPayload layout, int x, int y, int w, int h,
                          double vcx, double vcy) {
        double cx = layout.centerX() + 0.5D;
        double cz = layout.centerZ() + 0.5D;
        double r = layout.radius();
        double csx = camera.screenX(cx, vcx);
        float zoom = camera.zoom();
        int runStart = -1;
        for (int row = y; row < y + h; row++) {
            double dz = camera.worldZ(row + 0.5D, vcy) - cz;
            if (Math.abs(dz) >= r) {
                if (runStart < 0) runStart = row;
                continue;
            }
            if (runStart >= 0) {
                g.fill(x, runStart, x + w, row, RealmMapPalette.OUTSIDE_VEIL);
                runStart = -1;
            }
            double half = Math.sqrt(r * r - dz * dz) * zoom;
            int left = (int) Math.round(csx - half);
            int right = (int) Math.round(csx + half);
            if (left > x) g.fill(x, row, Math.min(left, x + w), row + 1, RealmMapPalette.OUTSIDE_VEIL);
            if (right < x + w) g.fill(Math.max(right, x), row, x + w, row + 1, RealmMapPalette.OUTSIDE_VEIL);
        }
        if (runStart >= 0) g.fill(x, runStart, x + w, y + h, RealmMapPalette.OUTSIDE_VEIL);
    }

    /** The claim as a light surveyor's dashed boundary with a soft shadow, legible on any ground. */
    private void drawClaim(GuiGraphics g, RealmMapLayoutPayload layout, double vcx, double vcy) {
        double csx = camera.screenX(layout.centerX() + 0.5D, vcx);
        double csy = camera.screenY(layout.centerZ() + 0.5D, vcy);
        double rs = layout.radius() * camera.zoom();
        int n = Math.max(48, (int) (Math.PI * 2 * rs));
        int lastX = Integer.MIN_VALUE;
        int lastY = Integer.MIN_VALUE;
        for (int pass = 0; pass < 2; pass++) {
            lastX = Integer.MIN_VALUE;
            int color = pass == 0 ? RealmMapPalette.CLAIM_DASH_SHADOW : RealmMapPalette.CLAIM_DASH;
            int off = pass == 0 ? 1 : 0;
            for (int i = 0; i < n; i++) {
                if (i % 6 >= 4) continue;
                double a = i * Math.PI * 2 / n;
                int px = (int) Math.floor(csx + Math.cos(a) * rs);
                int py = (int) Math.floor(csy + Math.sin(a) * rs);
                if (px == lastX && py == lastY) continue;
                lastX = px;
                lastY = py;
                g.fill(px + off, py + off, px + off + 1, py + off + 1, color);
            }
        }
    }

    /** Footprints as thin inked outlines over the real roofs, each with a dark icon badge. */
    private void drawBuildings(GuiGraphics g, RealmMapLayoutPayload layout, double vcx, double vcy) {
        buildingCount = 0;
        List<RealmMapLayoutPayload.BuildingEntry> buildings = layout.buildings();
        float icon = RealmIcons.itemGuiSize(1, guiScale);
        int badge = (int) Math.ceil(icon) + 4;
        for (int i = 0; i < buildings.size() && buildingCount < CAPACITY; i++) {
            RealmMapLayoutPayload.BuildingEntry b = buildings.get(i);
            float l = (float) camera.screenX(b.minX(), vcx);
            float t = (float) camera.screenY(b.minZ(), vcy);
            float r = (float) camera.screenX(b.maxX() + 1, vcx);
            float bt = (float) camera.screenY(b.maxZ() + 1, vcy);
            if (r < viewX - 12 || l > viewX + viewW + 12 || bt < viewY - 12 || t > viewY + viewH + 12) continue;
            int il = Math.round(l);
            int it = Math.round(t);
            int ir = Math.max(il + 2, Math.round(r));
            int ib = Math.max(it + 2, Math.round(bt));
            boolean selected = b.id().equals(selectedBuilding);
            boolean hovered = hoveredBuilding == i;
            int ink = b.valid() ? RealmMapPalette.BUILDING_INK : RealmMapPalette.BUILDING_INVALID;
            if (selected || hovered) g.fill(il, it, ir, ib, RealmMapPalette.BUILDING_WASH);
            outline(g, il, it, ir, ib, RealmMapPalette.withAlpha(ink, selected || hovered ? 1.0F : 0.55F));
            if (selected) {
                outline(g, il - 1, it - 1, ir + 1, ib + 1, RealmMapPalette.SELECT_RING);
                outline(g, il - 2, it - 2, ir + 2, ib + 2, RealmMapPalette.SELECT_RING);
            }
            int cx = Math.round((l + r) / 2.0F);
            int cy = Math.round((t + bt) / 2.0F);
            int bx = cx - badge / 2;
            int by = cy - badge / 2;
            badge(g, bx, by, badge, badge);
            RealmIcons.itemCentered(g, RealmIcons.building(b.typeId()), bx + badge / 2.0F, by + badge / 2.0F, 1,
                guiScale, 0);
            if (!b.valid()) {
                Ui2Surface.alertGlyph(g, bx + badge - 3, by - 3, RealmMapPalette.BUILDING_INVALID);
            }
            // Hit area: the footprint, grown to at least the badge.
            bLeft[buildingCount] = Math.min(il, bx);
            bTop[buildingCount] = Math.min(it, by);
            bRight[buildingCount] = Math.max(ir, bx + badge);
            bBottom[buildingCount] = Math.max(ib, by + badge);
            bIndex[buildingCount] = i;
            buildingCount++;
        }
    }

    private void drawBanner(GuiGraphics g, RealmMapLayoutPayload layout, double vcx, double vcy) {
        bannerX = (float) camera.screenX(layout.centerX() + 0.5D, vcx);
        bannerY = (float) camera.screenY(layout.centerZ() + 0.5D, vcy);
        int bx = Math.round(bannerX);
        int by = Math.round(bannerY);
        drawBannerGlyph(g, bx, by, hoveredBanner, com.hearthstead.client.heraldry.BannerDesignerClient.designAt(
            new net.minecraft.core.BlockPos(layout.centerX(), layout.centerY(), layout.centerZ())));
    }

    /**
     * The Banner marker in the village's own colours and cloth shape: field
     * cloth, trim device, and the shape's tail. Falls back to the burgundy
     * glyph when the Banner is not loaded on this client.
     */
    public static void drawBannerGlyph(GuiGraphics g, int x, int y, boolean lit,
                                       @javax.annotation.Nullable com.hearthstead.heraldry.VillageDesign design) {
        if (design == null) {
            drawBannerGlyph(g, x, y, lit);
            return;
        }
        int ink = RealmMapPalette.MARKER_OUTLINE;
        g.fill(x - 2, y, x + 3, y + 1, 0x55201408);
        g.fill(x, y - 12, x + 1, y + 1, ink);
        g.fill(x - 2, y - 12, x + 7, y - 11, ink);
        int cloth = 0xFF000000 | design.base().getTextureDiffuseColor();
        int trim = 0xFF000000 | design.trim().getTextureDiffuseColor();
        g.fill(x + 1, y - 11, x + 7, y - 5, ink);
        g.fill(x + 2, y - 11, x + 6, y - 6, cloth);
        switch (design.shape()) {
            case STRAIGHT -> {
                g.fill(x + 1, y - 5, x + 7, y - 3, ink);
                g.fill(x + 2, y - 6, x + 6, y - 4, cloth);
            }
            case SWALLOWTAIL -> {
                g.fill(x + 1, y - 5, x + 3, y - 3, ink);
                g.fill(x + 5, y - 5, x + 7, y - 3, ink);
                g.fill(x + 2, y - 6, x + 3, y - 4, cloth);
                g.fill(x + 5, y - 6, x + 6, y - 4, cloth);
            }
            case POINTED, PENNANT -> {
                g.fill(x + 2, y - 5, x + 6, y - 4, ink);
                g.fill(x + 3, y - 4, x + 5, y - 3, ink);
                g.fill(x + 3, y - 6, x + 5, y - 4, cloth);
            }
            case TONGUED -> {
                g.fill(x + 1, y - 5, x + 7, y - 4, ink);
                g.fill(x + 1, y - 4, x + 2, y - 3, ink);
                g.fill(x + 3, y - 4, x + 5, y - 3, ink);
                g.fill(x + 6, y - 4, x + 7, y - 3, ink);
                g.fill(x + 2, y - 6, x + 6, y - 5, cloth);
            }
        }
        if (lit) g.fill(x + 2, y - 11, x + 6, y - 10, 0x40FFFFFF);
        g.fill(x + 3, y - 9, x + 5, y - 7, trim);
    }

    /**
     * A small pennant on a pole planted at (x, y): the pole foot is the
     * exact Banner block. Burgundy cloth, gold hem, notched tail.
     */
    public static void drawBannerGlyph(GuiGraphics g, int x, int y, boolean lit) {
        int ink = RealmMapPalette.MARKER_OUTLINE;
        // Shadow on the ground.
        g.fill(x - 2, y, x + 3, y + 1, 0x55201408);
        // Pole.
        g.fill(x, y - 12, x + 1, y + 1, ink);
        g.fill(x - 2, y - 12, x + 7, y - 11, ink);
        // Cloth with ink edge.
        int cloth = lit ? 0xFF9A3A33 : 0xFF7A2E2A;
        g.fill(x + 1, y - 11, x + 7, y - 4, ink);
        g.fill(x + 2, y - 11, x + 6, y - 5, cloth);
        g.fill(x + 1, y - 4, x + 3, y - 3, ink);
        g.fill(x + 5, y - 4, x + 7, y - 3, ink);
        g.fill(x + 2, y - 5, x + 3, y - 4, cloth);
        g.fill(x + 5, y - 5, x + 6, y - 4, cloth);
        // Gold device.
        g.fill(x + 3, y - 9, x + 5, y - 7, 0xFFCDB57E);
    }

    private void drawPlayers(GuiGraphics g, Minecraft mc, double vcx, double vcy, float partialTick) {
        List<AbstractClientPlayer> players = mc.level.players();
        for (int i = 0; i < players.size(); i++) {
            AbstractClientPlayer p = players.get(i);
            double px = Mth.lerp(partialTick, p.xo, p.getX());
            double pz = Mth.lerp(partialTick, p.zo, p.getZ());
            float sx = (float) camera.screenX(px, vcx);
            float sy = (float) camera.screenY(pz, vcy);
            if (sx < viewX - 6 || sx > viewX + viewW + 6 || sy < viewY - 6 || sy > viewY + viewH + 6) continue;
            boolean self = p == mc.player;
            sx = RealmIcons.snap(sx, guiScale);
            sy = RealmIcons.snap(sy, guiScale);
            int cx = (int) Math.floor(sx);
            int cy = (int) Math.floor(sy);
            float fx = sx - cx;
            float fy = sy - cy;
            Matrix4f m = g.pose().last().pose();
            m.translate(fx, fy, 0);
            int body = self ? Ui2Palette.INK : Ui2Palette.INK_SOFT;
            // Diamond: players read differently from round settler tokens.
            g.fill(cx - 3, cy, cx + 4, cy + 1, RealmMapPalette.MARKER_HALO);
            g.fill(cx, cy - 3, cx + 1, cy + 4, RealmMapPalette.MARKER_HALO);
            g.fill(cx - 2, cy - 1, cx + 3, cy + 2, RealmMapPalette.MARKER_HALO);
            g.fill(cx - 1, cy - 2, cx + 2, cy + 3, RealmMapPalette.MARKER_HALO);
            g.fill(cx - 2, cy, cx + 3, cy + 1, body);
            g.fill(cx, cy - 2, cx + 1, cy + 3, body);
            g.fill(cx - 1, cy - 1, cx + 2, cy + 2, body);
            // Facing tick.
            float yaw = p.getViewYRot(partialTick) * Mth.DEG_TO_RAD;
            int tickX = Math.round(-Mth.sin(yaw) * 4.0F);
            int tickY = Math.round(Mth.cos(yaw) * 4.0F);
            g.fill(cx + tickX, cy + tickY, cx + tickX + 1, cy + tickY + 1, body);
            m.translate(-fx, -fy, 0);
        }
    }

    // Summons: while a settler this player summoned walks over, a dashed gold line runs from them to you.
    private List<com.hearthstead.network.SummonStatePayload.Row> summonRows = List.of();
    private long summonRowsMs;

    private void drawSummonLines(GuiGraphics g, Minecraft mc, long now, double vcx, double vcy, float partialTick) {
        if (now - summonRowsMs > 250L) {
            summonRowsMs = now;
            summonRows = com.hearthstead.client.command.SummonClient.rows();
        }
        if (summonRows.isEmpty() || mc.player == null) return;
        double px = camera.screenX(Mth.lerp(partialTick, mc.player.xo, mc.player.getX()), vcx);
        double py = camera.screenY(Mth.lerp(partialTick, mc.player.zo, mc.player.getZ()), vcy);
        for (int i = 0; i < summonRows.size(); i++) {
            com.hearthstead.network.SummonStatePayload.Row row = summonRows.get(i);
            if (row.arrived()) continue;
            MarkerTrack t = RealmMapClient.track(row.settlerId());
            if (t == null) continue;
            double sx = camera.screenX(worldX(mc, t, now, partialTick), vcx);
            double sy = camera.screenY(worldZ(mc, t, now, partialTick), vcy);
            double dx = px - sx;
            double dy = py - sy;
            double len = Math.sqrt(dx * dx + dy * dy);
            if (len < 6.0D) continue;
            int steps = (int) len;
            // Marching dashes toward you.
            int shift = HsMotion.enabled ? (int) ((now / 90L) % 6L) : 0;
            for (int k = 4; k < steps - 4; k++) {
                if (((k - shift) % 6 + 6) % 6 >= 3) continue;
                int x = (int) Math.round(sx + dx * k / len);
                int y = (int) Math.round(sy + dy * k / len);
                g.fill(x + 1, y + 1, x + 2, y + 2, 0x80201408);
                g.fill(x, y, x + 1, y + 1, 0xFFE0BE6E);
            }
            // Arrowhead at your end.
            double ux = dx / len;
            double uy = dy / len;
            int tipX = (int) Math.round(px - ux * 5.0D);
            int tipY = (int) Math.round(py - uy * 5.0D);
            for (int k = 1; k <= 3; k++) {
                int bx = (int) Math.round(tipX - ux * k - uy * k);
                int by = (int) Math.round(tipY - uy * k + ux * k);
                int cx = (int) Math.round(tipX - ux * k + uy * k);
                int cy = (int) Math.round(tipY - uy * k - ux * k);
                g.fill(bx, by, bx + 1, by + 1, 0xFFE0BE6E);
                g.fill(cx, cy, cx + 1, cy + 1, 0xFFE0BE6E);
            }
            g.fill(tipX, tipY, tipX + 1, tipY + 1, 0xFFE0BE6E);
        }
    }

    private double worldX(Minecraft mc, MarkerTrack track, long now, float partialTick) {
        Entity e = track.entityId >= 0 ? mc.level.getEntity(track.entityId) : null;
        if (e instanceof SettlerEntity s && s.getUUID().equals(track.id)) {
            return Mth.lerp(partialTick, s.xo, s.getX());
        }
        return track.x(now);
    }

    private double worldZ(Minecraft mc, MarkerTrack track, long now, float partialTick) {
        Entity e = track.entityId >= 0 ? mc.level.getEntity(track.entityId) : null;
        if (e instanceof SettlerEntity s && s.getUUID().equals(track.id)) {
            return Mth.lerp(partialTick, s.zo, s.getZ());
        }
        return track.z(now);
    }

    /** Figure scale in GUI pixels per sprite pixel: 1 when zoomed out, 2 when close. */
    public int figureScale() {
        return camera.zoom() < 1.75F ? 1 : 2;
    }

    private void drawMarkers(GuiGraphics g, Minecraft mc, long now, double vcx, double vcy, float partialTick) {
        markerCount = 0;
        crowdCount = 0;
        MarkerTrack[] tracks = RealmMapClient.tracks();
        int s = figureScale();
        int m = headScale();
        for (int i = 0; i < tracks.length && markerCount < CAPACITY; i++) {
            MarkerTrack t = tracks[i];
            float sx = (float) camera.screenX(worldX(mc, t, now, partialTick), vcx);
            float sy = (float) camera.screenY(worldZ(mc, t, now, partialTick), vcy);
            if (sx < viewX - 16 || sx > viewX + viewW + 16 || sy < viewY - 16 || sy > viewY + viewH + 28) continue;
            markerX[markerCount] = sx;
            markerY[markerCount] = sy;
            markerTrack[markerCount] = t;
            markerCount++;
            animate(t, now, m);
        }
        fanOut(now, m, s);
        int selectedIndex = -1;
        for (int i = 0; i < markerCount; i++) {
            markerX[i] = RealmIcons.snap(markerX[i], guiScale);
            markerY[i] = RealmIcons.snap(markerY[i], guiScale);
            if (markerTrack[i].id.equals(selectedSettler)) selectedIndex = i;
        }
        // Back to front by feet position so nearer figures overlap farther ones (insertion sort, no allocation).
        for (int i = 0; i < markerCount; i++) order[i] = i;
        for (int i = 1; i < markerCount; i++) {
            int v = order[i];
            int j = i - 1;
            while (j >= 0 && markerY[order[j]] > markerY[v]) {
                order[j + 1] = order[j];
                j--;
            }
            order[j + 1] = v;
        }
        pSelected = selectedIndex;
        pScale = s;
        pHead = m;
        pg = g;
        pMc = mc;
        pNow = now;
        if (selectedIndex >= 0) {
            drawSelectionRing(g, markerX[selectedIndex], markerY[selectedIndex] - markerCentreLift(), s, now);
        }
        if (m > 0) {
            // Bodies (fills) batched in one draw, then the faces, then tools over heads and pips (batched again).
            pPass = 0;
            g.drawManaged(figurePass);
            pPass = 1;
            runFigurePass();
            pPass = 2;
            g.drawManaged(figurePass);
        } else {
            pPass = -1;
            runFigurePass();
        }
        drawCrowdTags(g);
    }

    private static final float RUN_SPEED = 5.2F;

    /**
     * Once per frame per figure: settles the facing (with a dead zone so a
     * straight north-south walker does not flicker) and, at the close zoom,
     * advances the walk cycle by the real ground speed and picks the view.
     */
    private void animate(MarkerTrack t, long now, int m) {
        long dt = t.animMs == 0L ? 0L : Math.min(120L, Math.max(0L, now - t.animMs));
        t.animMs = now;
        if (Math.abs(t.dirX()) > 0.15F) t.facingLeft = t.dirX() < 0.0F;
        if (m < 3) return;
        RealmMapStatus status = RealmMapStatus.byWireId(t.statusId);
        float speed = t.speed(now);
        boolean moving = speed > 0.25F || status == RealmMapStatus.WALKING || status == RealmMapStatus.FLEEING;
        if (!moving) {
            t.view = SettlerFigure.FRONT;
            return;
        }
        boolean run = status == RealmMapStatus.FLEEING || speed > RUN_SPEED;
        float cps = SettlerFigure.cyclesPerSecond(Math.max(speed, 1.6F), m, camera.zoom(), guiScale, run);
        t.walkPhase = (t.walkPhase + dt / 1000.0F * cps) % 1.0F;
        float ax = Math.abs(t.dirX());
        float az = Math.abs(t.dirZ());
        if (ax > 0.62F) {
            t.view = SettlerFigure.SIDE;
        } else if (az > 0.8F) {
            t.view = t.dirZ() < 0.0F ? SettlerFigure.BACK : SettlerFigure.FRONT;
        }
    }

    // Crowds: figures standing on top of each other fan out side by side (eased); at the far zoom a
    // crowd of more than four shows three and a "+N" tag. Scratch arrays are allocated once.
    private final int[] cluster = new int[CAPACITY];
    private final int[] clusterSize = new int[CAPACITY];
    private final int[] clusterSeen = new int[CAPACITY];
    private final int[] crowdOf = new int[CAPACITY];
    private final float[] clusterX = new float[CAPACITY];
    private final float[] clusterY = new float[CAPACITY];
    private final float[] crowdX = new float[CAPACITY];
    private final float[] crowdY = new float[CAPACITY];
    private final int[] crowdN = new int[CAPACITY];
    private final String[] crowdLabels = new String[CAPACITY];
    private int crowdCount;
    private long fanMs;

    private void fanOut(long now, int m, int s) {
        float reach = m > 0 ? (float) (6.0D * m / guiScale) : 3.0F * s;
        float spacing = m > 0 ? (float) (9.0D * m / guiScale) : 4.0F * s;
        int n = markerCount;
        // Single-link grouping (union-find): a dense pile becomes one crowd, not several overlapping ones.
        for (int i = 0; i < n; i++) cluster[i] = i;
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (Math.abs(markerX[j] - markerX[i]) < reach && Math.abs(markerY[j] - markerY[i]) < reach) {
                    int a = root(i);
                    int b = root(j);
                    if (a != b) cluster[Math.max(a, b)] = Math.min(a, b);
                }
            }
        }
        int clusters = 0;
        for (int i = 0; i < n; i++) {
            int r = root(i);
            if (r == i) {
                clusterSeen[i] = clusters++;
                int c = clusterSeen[i];
                clusterSize[c] = 0;
                clusterX[c] = 0.0F;
                clusterY[c] = 0.0F;
            }
        }
        for (int i = 0; i < n; i++) {
            int c = clusterSeen[root(i)];
            clusterSize[c]++;
            clusterX[c] += markerX[i];
            clusterY[c] += markerY[i];
        }
        // Resolve every member to its crowd index before reusing clusterSeen as the per-crowd slot counter.
        for (int i = 0; i < n; i++) crowdOf[i] = clusterSeen[root(i)];
        for (int c = 0; c < clusters; c++) clusterSeen[c] = 0;
        for (int i = 0; i < n; i++) cluster[i] = crowdOf[i];
        for (int c = 0; c < clusters; c++) {
            clusterX[c] /= clusterSize[c];
            clusterY[c] /= clusterSize[c];
        }
        float ease = !HsMotion.enabled || fanMs == 0L ? 1.0F : Math.min(1.0F, (now - fanMs) / 90.0F);
        fanMs = now;
        int write = 0;
        for (int i = 0; i < n; i++) {
            MarkerTrack t = markerTrack[i];
            int c = cluster[i];
            int k = clusterSeen[c]++;
            int size = clusterSize[c];
            float tx = 0.0F;
            float ty = 0.0F;
            boolean hidden = false;
            if (size > 1) {
                // Pawns stack past four (three shown); head figures past eight (two rows, seven shown).
                boolean stacked = size > (m == 0 ? 4 : 8);
                int shown = stacked ? (m == 0 ? 3 : 7) : size;
                boolean keep = t.id.equals(selectedSettler) || t.id.equals(hoveredSettler);
                hidden = stacked && k >= shown && !keep;
                int slot = Math.min(k, shown - 1);
                int perRow = 4;
                int row = slot / perRow;
                int col = slot % perRow;
                int inRow = Math.min(perRow, shown - row * perRow);
                tx = clusterX[c] + (col - (inRow - 1) / 2.0F) * spacing - markerX[i];
                ty = clusterY[c] - row * spacing * (m > 0 ? 0.9F : 0.6F) - markerY[i];
                if (stacked && k == 0 && crowdCount < CAPACITY) {
                    crowdX[crowdCount] = clusterX[c] + (Math.min(4, shown) / 2.0F) * spacing + 2.0F;
                    crowdY[crowdCount] = clusterY[c] - (m == 0 ? 4.0F * s : (float) (10.0D * m / guiScale));
                    crowdN[crowdCount] = size - shown;
                    crowdCount++;
                }
            }
            t.fanX += (tx - t.fanX) * ease;
            t.fanY += (ty - t.fanY) * ease;
            if (hidden) continue;
            markerX[write] = markerX[i] + t.fanX;
            markerY[write] = markerY[i] + t.fanY;
            markerTrack[write] = t;
            write++;
        }
        markerCount = write;
    }

    private int root(int i) {
        while (cluster[i] != i) {
            cluster[i] = cluster[cluster[i]];
            i = cluster[i];
        }
        return i;
    }

    private void drawCrowdTags(GuiGraphics g) {
        if (crowdCount == 0) return;
        Font font = Minecraft.getInstance().font;
        for (int i = 0; i < crowdCount; i++) {
            int n = Math.max(1, Math.min(CAPACITY - 1, crowdN[i]));
            String text = crowdLabels[n];
            if (text == null) {
                text = "+" + n;
                crowdLabels[n] = text;
            }
            int w = font.width(text) + 4;
            int x = Math.round(crowdX[i]);
            int y = Math.round(crowdY[i]) - 5;
            g.fill(x - 1, y - 1, x + w + 1, y + 10, RealmMapPalette.LABEL_EDGE);
            g.fill(x, y, x + w, y + 9, RealmMapPalette.LABEL_PAPER);
            g.drawString(font, text, x + 2, y + 1, Ui2Palette.INK, false);
        }
    }

    private int pSelected;
    private int pScale;
    private int pHead;

    /** One pass over the visible figures in back-to-front order: dimmed, normal, hovered, selected. */
    private void runFigurePass() {
        for (int stage = 0; stage < 4; stage++) {
            for (int k = 0; k < markerCount; k++) {
                int i = order[k];
                MarkerTrack t = markerTrack[i];
                boolean dim = highlightProfession >= 0 && t.professionId != highlightProfession;
                boolean hovered = t.id.equals(hoveredSettler);
                boolean selected = i == pSelected;
                int want = selected ? 3 : hovered ? 2 : dim ? 0 : 1;
                if (want != stage) continue;
                if (pHead > 0) {
                    drawHeadFigure(pg, pMc, i, t, pHead, dim && !selected, hovered, pNow, pPass);
                } else {
                    drawFigure(pg, markerX[i], markerY[i], t, pScale, dim && !selected, hovered, pNow);
                }
            }
        }
    }

    private final int[] order = new int[CAPACITY];

    /**
     * Physical pixels per face texel for the head figures, or 0 when zoomed
     * out far enough that the small pawns read better. Heads hold one zoom
     * step below 1:1; 3 (the close walk cycle) at the top two steps. Integer,
     * so every face texel is an exact block of screen pixels.
     */
    public int headScale() {
        float z = camera.zoom();
        if (z < headMinZoom() - 0.01F) return 0;
        return z < 2.5F ? 2 : 3;
    }

    private float headMinZoom() {
        float[] steps = camera.steps();
        for (int i = 1; i < steps.length; i++) {
            if (steps[i] >= 0.99F) return Math.min(0.99F, steps[i - 1]);
        }
        return 0.99F;
    }

    /** GUI pixels from a marker's feet up to its visual centre (for rings and hit tests). */
    float markerCentreLift() {
        int m = headScale();
        return m > 0 ? (float) (7.5 * m / guiScale) : 3 * figureScale();
    }

    float markerHitRadius() {
        int m = headScale();
        return m > 0 ? (float) (6.0 * m / guiScale) + 1.5F : Math.max(HIT_RADIUS, 4 * figureScale() + 1);
    }

    // ---------------------------------------------------------- head figures

    private static final ResourceLocation NO_FACE = ResourceLocation.fromNamespaceAndPath("hearthstead", "none");
    private final java.util.HashMap<UUID, ResourceLocation> faces = new java.util.HashMap<>();
    private int facesVersion = Integer.MIN_VALUE;

    /**
     * The settler's real composed skin (face and hat layers), resolved once
     * per settler and roster version: the live entity's renderer texture when
     * tracked, else the same composition from the roster's appearance seed.
     * Composition itself is cached by SettlerTextureCache, so drawing a
     * frame is only two blits per head -- no texture work.
     */
    private ResourceLocation faceTexture(Minecraft mc, MarkerTrack t) {
        if (facesVersion != RealmMapClient.layoutVersion()) {
            faces.clear();
            facesVersion = RealmMapClient.layoutVersion();
        }
        ResourceLocation cached = faces.get(t.id);
        if (cached != null) return cached == NO_FACE ? null : cached;
        ResourceLocation found = null;
        Entity e = t.entityId >= 0 && mc.level != null ? mc.level.getEntity(t.entityId) : null;
        if (e instanceof SettlerEntity settler && settler.getUUID().equals(t.id)) {
            found = mc.getEntityRenderDispatcher().getRenderer(settler).getTextureLocation(settler);
        } else {
            RealmMapLayoutPayload.RosterEntry entry = RealmMapClient.roster(t.id);
            if (entry != null && entry.appearanceSeed() >= 0) {
                found = com.hearthstead.client.render.SettlerTextureCache.getOrCreateForSeed(entry.appearanceSeed(),
                    Profession.byId(t.professionId), entry.name());
                // New compositions are rate limited: null means "not yet", so ask again next frame.
                if (found == null) return null;
            }
        }
        faces.put(t.id, found == null ? NO_FACE : found);
        return found;
    }

    private static final int FIGURE_INK = 0xFF1E1610;
    private static final int HOVER_GLOW = 0xFFF2CF6B;
    private final SettlerFigure figure = new SettlerFigure();

    /** Silhouette outline width in physical pixels: about a third to half a GUI pixel, never less than one. */
    private int outlinePx() {
        return guiScale >= 3.0D ? 2 : 1;
    }

    /**
     * Lays out one settler's figure for this frame from its real state: the
     * mid zoom keeps the two-frame march exactly as before; the close zoom
     * uses the walk phase advanced in {@link #animate}. Deterministic for a
     * given time, so the three draw passes agree.
     */
    private void layoutFigure(MarkerTrack t, int m, RealmMapStatus status, long now, ResourceLocation face) {
        Profession profession = Profession.byId(t.professionId);
        boolean close = m >= 3;
        float speed = t.speed(now);
        boolean moving = speed > 0.25F || status == RealmMapStatus.WALKING || status == RealmMapStatus.FLEEING;
        boolean run = moving && close && (status == RealmMapStatus.FLEEING || speed > RUN_SPEED);
        int motion;
        if (moving) {
            motion = run ? SettlerFigure.RUN : SettlerFigure.WALK;
        } else if (status == RealmMapStatus.SLEEPING) {
            motion = SettlerFigure.SLEEP;
        } else if (status == RealmMapStatus.WORKING || status == RealmMapStatus.FIGHTING) {
            motion = SettlerFigure.WORK;
        } else {
            motion = SettlerFigure.IDLE;
        }
        long seed = t.id.getLeastSignificantBits();
        int frame;
        int workFrame = 0;
        int lift = 0;
        int look = 0;
        if (close) {
            frame = Math.min(5, (int) (t.walkPhase * 6.0F));
            if (motion == SettlerFigure.WORK) {
                long w = Math.floorMod(now + (seed & 1023L), 700L);
                workFrame = w < 320L ? 0 : w < 410L ? 1 : 2;
            } else if (motion == SettlerFigure.IDLE || motion == SettlerFigure.SLEEP) {
                long period = motion == SettlerFigure.SLEEP ? 3600L : 2400L;
                lift = Math.floorMod(now + (seed & 4095L), period) < period * 2 / 5 ? 1 : 0;
                if (motion == SettlerFigure.IDLE) {
                    long slot = Math.floorMod(now + (t.id.getMostSignificantBits() & 8191L), Long.MAX_VALUE) / 900L;
                    if (slot % 7L == 0L) look = ((slot / 7L) & 1L) == 0L ? 1 : -1;
                }
            }
        } else {
            // The mid march, as before: alternate legs every 150 ms, a one-pixel bob, idle bobs now and then.
            frame = (int) ((now / 150L + (seed & 7)) & 1);
            if (moving && frame == 1) {
                lift = 1;
            } else if (!moving) {
                long idle = (now / 700L + (t.id.getMostSignificantBits() & 15)) % 6;
                lift = idle == 0 ? 1 : 0;
            }
            if (motion == SettlerFigure.WORK) workFrame = ((now / 260L + (seed & 3)) & 1) == 0 ? 0 : 1;
        }
        int torso = profession == Profession.NONE ? 0xFF8E7A5C : 0xFF000000 | profession.color();
        FigureSkin skin = face == null ? null : FigureSkin.of(face);
        figure.build(m, close, close ? t.view : SettlerFigure.FRONT, t.facingLeft, motion, frame, workFrame, lift, look,
            SettlerFigure.toolFor(profession.key()), skin, torso, face != null, motion == SettlerFigure.SLEEP);
    }

    /**
     * A settler as a tiny person in their own clothes and face, carrying
     * their trade's tool. Drawn in physical pixels: pass 0 is the soft
     * ground shadow, the ink silhouette and the body colours (batched), pass
     * 1 the face and hat blits, pass 2 whatever sits over the head (a raised
     * arm and tool) and the state pip. Transforms are applied in place and
     * undone; nothing allocates.
     */
    private void drawHeadFigure(GuiGraphics g, Minecraft mc, int index, MarkerTrack t, int m, boolean dim,
                                boolean hovered, long now, int pass) {
        float sx = markerX[index];
        float sy = markerY[index];
        RealmMapStatus status = RealmMapStatus.byWireId(t.statusId);
        ResourceLocation face = faceTexture(mc, t);
        layoutFigure(t, m, status, now, face);
        SettlerFigure f = figure;
        float alpha = dim ? 0.35F : 1.0F;
        float inv = (float) (1.0 / guiScale);
        int o = outlinePx();
        int ink = RealmMapPalette.withAlpha(FIGURE_INK, alpha);
        Matrix4f mat = g.pose().last().pose();
        mat.translate(sx, sy, 0).scale(inv, inv, 1.0F);
        int u = m;
        if (pass == 0) {
            // Walking: two fading footprints behind, along the real heading.
            if (!dim && t.speed(now) > 0.25F) {
                for (int k = 1; k <= 2; k++) {
                    int fx = Math.round(-t.dirX() * (4 + k * 4) * u);
                    int fy = Math.round(-t.dirZ() * (4 + k * 4) * u);
                    g.fill(fx - u / 2, fy - u / 2, fx + u / 2 + 1, fy + u / 2 + 1,
                        RealmMapPalette.withAlpha(RealmMapPalette.STATUS_WALKING, k == 1 ? 0.6F : 0.3F));
                }
            }
            // Soft ground shadow: a light oval with a darker core.
            g.fill(-3 * u, -1, 3 * u, u, RealmMapPalette.withAlpha(0x30201408, alpha));
            g.fill(-2 * u, 0, 2 * u, u - 1, RealmMapPalette.withAlpha(0x30201408, alpha));
            drawFigureRects(g, f, false, hovered && !dim, o, ink, alpha);
        } else if (pass == 1) {
            if (face != null) {
                if (dim) g.setColor(1.0F, 1.0F, 1.0F, alpha);
                int hx = f.headX;
                if (f.headMirror) {
                    mat.scale(-1.0F, 1.0F, 1.0F);
                    hx = -(f.headX + f.headSize);
                }
                com.mojang.blaze3d.systems.RenderSystem.enableBlend();
                g.blit(face, hx, f.headY, f.headSize, f.headSize, f.faceU, f.faceV, 8, 8, 128, 64);
                g.blit(face, hx, f.headY, f.headSize, f.headSize, f.hatU, f.hatV, 8, 8, 128, 64);
                if (f.headMirror) mat.scale(-1.0F, 1.0F, 1.0F);
                if (dim) g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
            }
        } else if (pass == 2) {
            drawFigureRects(g, f, true, hovered && !dim, o, ink, alpha);
        }
        mat.scale((float) guiScale, (float) guiScale, 1.0F).translate(-sx, -sy, 0);
        if (dim || pass != 2) return;
        // A state pip only when something is wrong or they sleep (GUI space, beside the head).
        int gx = Math.round(sx + f.headRight * inv) + 1;
        int gy = Math.round(sy + f.headY * inv) - 1;
        drawPip(g, status, gx, gy);
    }

    /** One layer of a figure: hover glow, ink silhouette, then colours (with a local ink edge where flagged). */
    private static void drawFigureRects(GuiGraphics g, SettlerFigure f, boolean over, boolean glow, int o, int ink,
                                        float alpha) {
        int n = f.count;
        if (glow) {
            for (int i = 0; i < n; i++) {
                int fl = f.flags[i];
                if (((fl & SettlerFigure.OVER) != 0) != over || (fl & SettlerFigure.OUTLINE) == 0) continue;
                g.fill(f.x0[i] - o - 1, f.y0[i] - o - 1, f.x1[i] + o + 1, f.y1[i] + o + 1, HOVER_GLOW);
            }
        }
        for (int i = 0; i < n; i++) {
            int fl = f.flags[i];
            if (((fl & SettlerFigure.OVER) != 0) != over || (fl & SettlerFigure.OUTLINE) == 0) continue;
            g.fill(f.x0[i] - o, f.y0[i] - o, f.x1[i] + o, f.y1[i] + o, ink);
        }
        for (int i = 0; i < n; i++) {
            int fl = f.flags[i];
            if (((fl & SettlerFigure.OVER) != 0) != over) continue;
            if ((fl & SettlerFigure.EDGE) != 0) {
                g.fill(f.x0[i] - 1, f.y0[i], f.x0[i], f.y1[i], ink);
                g.fill(f.x1[i], f.y0[i], f.x1[i] + 1, f.y1[i], ink);
            }
            int c = f.color[i];
            if (c != 0) g.fill(f.x0[i], f.y0[i], f.x1[i], f.y1[i], RealmMapPalette.withAlpha(c, alpha));
        }
    }

    private static final int PIP_DANGER = 0xFFD2412F;
    private static final int PIP_STUCK = 0xFFE3A22C;
    private static final int PIP_SLEEP = 0xFF46607E;
    private static final int PIP_RIM = 0xFF1E1610;

    /**
     * Tiny state pips, only for the exceptional: red for fleeing or fighting,
     * amber for stuck or waiting, "zz" for resting. Working, walking and idle
     * settlers carry no pip -- their pose says it.
     */
    static void drawPip(GuiGraphics g, RealmMapStatus status, int gx, int gy) {
        switch (status) {
            case FLEEING, FIGHTING -> pip(g, gx, gy, PIP_DANGER);
            case STUCK -> pip(g, gx, gy, PIP_STUCK);
            case SLEEPING -> {
                sleepZ(g, gx, gy + 1);
                sleepZ(g, gx + 3, gy - 2);
            }
            default -> {
            }
        }
    }

    private static void pip(GuiGraphics g, int x, int y, int color) {
        g.fill(x, y - 1, x + 3, y + 4, PIP_RIM);
        g.fill(x - 1, y, x + 4, y + 3, PIP_RIM);
        g.fill(x, y, x + 3, y + 3, color);
        g.fill(x, y, x + 1, y + 1, 0x66FFFFFF);
    }

    private static void sleepZ(GuiGraphics g, int x, int y) {
        g.fill(x - 1, y - 1, x + 4, y + 4, RealmMapPalette.MARKER_HALO);
        g.fill(x, y, x + 3, y + 1, PIP_SLEEP);
        g.fill(x + 1, y + 1, x + 2, y + 2, PIP_SLEEP);
        g.fill(x, y + 2, x + 3, y + 3, PIP_SLEEP);
    }

    // Figure sprite, feet at (0, 0): head, shoulders and body in the trade's colour, two legs.
    // Each entry: dx0, dx1 (inclusive), dy, part (0 skin, 1 body, 2 legs).
    private static final int[][] FIGURE = {
        {0, 0, -5, 0},
        {-1, 1, -4, 1},
        {-1, 1, -3, 1},
        {-1, -1, -2, 2}, {1, 1, -2, 2},
        {-1, -1, -1, 2}, {1, 1, -1, 2}
    };
    private static final int SKIN = 0xFFE3B98F;

    /**
     * A little settler figure at a sub-pixel position: whole GUI pixels go
     * to the fills, the physical-pixel remainder is an in-place translate,
     * so a slow walker glides one physical pixel at a time.
     */
    private void drawFigure(GuiGraphics g, float sx, float sy, MarkerTrack t, int s, boolean dim, boolean hovered,
                            long now) {
        int cx = (int) Math.floor(sx);
        int cy = (int) Math.floor(sy);
        float fx = sx - cx;
        float fy = sy - cy;
        Matrix4f m = g.pose().last().pose();
        m.translate(fx, fy, 0);
        drawFigureAt(g, cx, cy, t, s, dim, hovered, now);
        m.translate(-fx, -fy, 0);
    }

    private void drawFigureAt(GuiGraphics g, int cx, int cy, MarkerTrack t, int s, boolean dim, boolean hovered,
                              long now) {
        RealmMapStatus status = RealmMapStatus.byWireId(t.statusId);
        Profession profession = Profession.byId(t.professionId);
        int body = profession == Profession.NONE ? 0xFFB9A682 : 0xFF000000 | profession.color();
        if (status == RealmMapStatus.SLEEPING) body = RealmMapPalette.darker(body, 0.7F);
        if (status == RealmMapStatus.IDLE) body = mix(body, 0xFFF3ECDD, 0.45F);
        int legs = RealmMapPalette.darker(body, 0.62F);
        int ink = status.needsAttention() ? RealmMapPalette.STATUS_ALERT
            : hovered ? 0xFF000000 : RealmMapPalette.MARKER_OUTLINE;
        float alpha = dim ? 0.35F : 1.0F;
        // Walking: two fading footfalls behind the figure along its real heading.
        if (!dim && status == RealmMapStatus.WALKING && t.speed(now) > 0.2F) {
            for (int k = 1; k <= 2; k++) {
                int tx = Math.round(cx - t.dirX() * (2 + k * 3) * s);
                int ty = Math.round(cy - t.dirZ() * (2 + k * 3) * s);
                g.fill(tx, ty, tx + s, ty + s, RealmMapPalette.withAlpha(RealmMapPalette.STATUS_WALKING,
                    k == 1 ? 0.75F : 0.4F));
            }
        }
        // Ground shadow, then a one-pixel ink outline (dilated sprite), then the sprite.
        g.fill(cx - 2 * s, cy, cx + 3 * s, cy + s, RealmMapPalette.withAlpha(0x60201408, alpha));
        int outline = RealmMapPalette.withAlpha(ink, alpha);
        for (int[] p : FIGURE) {
            g.fill(cx + p[0] * s - 1, cy + p[2] * s - 1, cx + (p[1] + 1) * s + 1, cy + (p[2] + 1) * s + 1, outline);
        }
        for (int[] p : FIGURE) {
            int c = p[3] == 0 ? SKIN : p[3] == 1 ? body : legs;
            g.fill(cx + p[0] * s, cy + p[2] * s, cx + (p[1] + 1) * s, cy + (p[2] + 1) * s,
                RealmMapPalette.withAlpha(c, alpha));
        }
        if (s >= 2 && !dim) {
            // A lighter shoulder line reads as a tunic at the close zoom.
            g.fill(cx - s, cy - 4 * s, cx + 2 * s, cy - 4 * s + 1, RealmMapPalette.withAlpha(0xFFFFFFFF, 0.25F));
        }
        if (dim) return;
        int gx = cx + 2 * s + 1;
        int gy = cy - 6 * s - 2;
        drawPip(g, status, gx, gy);
        // Trade badge beside the figure only while its trade is highlighted (hover and selection show a label).
        if (highlightProfession >= 0 && t.professionId == highlightProfession) {
            ItemStack icon = RealmIcons.profession(profession);
            if (!icon.isEmpty()) {
                float size = RealmIcons.itemGuiSize(1, guiScale);
                int bx = cx - 2 * s - 2 - (int) Math.ceil(size);
                int by = cy - 5 * s - 1;
                badge(g, bx, by, (int) Math.ceil(size) + 2, (int) Math.ceil(size) + 2);
                RealmIcons.itemCentered(g, icon, bx + (size + 2) / 2.0F, by + (size + 2) / 2.0F, 1, guiScale, 10.0F);
            }
        }
    }

    /** Dark rounded badge with a light rim, as on the reference map. */
    private static void badge(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x + 1, y, x + w - 1, y + h, RealmMapPalette.BADGE_RIM);
        g.fill(x, y + 1, x + w, y + h - 1, RealmMapPalette.BADGE_RIM);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, RealmMapPalette.BADGE);
    }

    private static int mix(int a, int b, float t) {
        int r = Math.round(((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
        int gr = Math.round(((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
        int bl = Math.round((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
        return 0xFF000000 | (r << 16) | (gr << 8) | bl;
    }

    private void drawSelectionRing(GuiGraphics g, float sx, float sy, int s, long now) {
        float p = HsMotion.enabled ? HsMotion.easeOutCubic((now - selectedAtMs) / (float) SELECT_MS) : 1.0F;
        int ring = 9 * s + 4 + Math.round((1.0F - p) * 8.0F);
        if ((ring & 1) == 0) ring++;
        int cx = Math.round(sx);
        int cy = Math.round(sy);
        int color = RealmMapPalette.withAlpha(RealmMapPalette.SELECT_RING, 0.35F + 0.65F * p);
        disc(g, cx, cy, ring, RealmMapPalette.withAlpha(0xFFF3ECDD, 0.35F * p));
        ring(g, cx, cy, ring, color);
        ring(g, cx, cy, ring - 2, color);
    }

    private void drawSelectionLabel(GuiGraphics g, Font font, RealmMapLayoutPayload layout, long now) {
        UUID id = selectedSettler != null ? selectedSettler : hoveredSettler;
        if (id == null) return;
        for (int i = 0; i < markerCount; i++) {
            if (!markerTrack[i].id.equals(id)) continue;
            RealmMapLayoutPayload.RosterEntry entry = RealmMapClient.roster(id);
            if (entry == null) return;
            String name = entry.name();
            ItemStack icon = RealmIcons.profession(markerTrack[i].professionId);
            float iconSize = RealmIcons.itemGuiSize(1, guiScale);
            int iconW = icon.isEmpty() ? 0 : (int) Math.ceil(iconSize) + 2;
            int w = font.width(name) + 6 + iconW;
            // Off the chart: no label (the scissor hid it before; a pinned label would mislead).
            if (markerX[i] < viewX || markerX[i] > viewX + viewW || markerY[i] < viewY
                || markerY[i] > viewY + viewH) return;
            int cx = Math.round(markerX[i]);
            // Kept inside the chart (the scissor would cut a name near the edge).
            int top = clampLabel(Math.round(markerY[i]) + 3, viewY + 2, viewY + viewH - 12);
            int left = clampLabel(cx - w / 2, viewX + 2, viewX + viewW - w - 2);
            g.fill(left - 1, top - 1, left + w + 1, top + 11, RealmMapPalette.LABEL_EDGE);
            g.fill(left, top, left + w, top + 10, RealmMapPalette.LABEL_PAPER);
            if (!icon.isEmpty()) {
                RealmIcons.itemCentered(g, icon, left + 2 + iconSize / 2.0F, top + 5.0F, 1, guiScale, 10.0F);
            }
            g.drawString(font, name, left + 3 + iconW, top + 1, Ui2Palette.INK, false);
            return;
        }
    }

    // ------------------------------------------------------ talk markers ---

    // Someone who wants to talk: the conversation lane's badge art (same as in the world), about the size of a building badge,
    // drawn above the settler figures at every zoom and pulsing gently. Positions kept for hover.
    private static final int TALK_BLUE_LIGHT = 0xFF5A93D6;
    private final float[] talkerX = new float[com.hearthstead.network.RealmMapMarkersPayload.MAX_TALKERS];
    private final float[] talkerY = new float[com.hearthstead.network.RealmMapMarkersPayload.MAX_TALKERS];
    private final int[] talkerIndex = new int[com.hearthstead.network.RealmMapMarkersPayload.MAX_TALKERS];
    private int talkerCount;
    private int talkerR = 5;

    private void drawTalkers(GuiGraphics g, Font font, long now, double vcx, double vcy) {
        talkerCount = 0;
        MarkerTrack[] talkers = RealmMapClient.talkers();
        if (talkers.length == 0) return;
        int d = talkDiameter();
        talkerR = d / 2;
        float pulse = HsMotion.enabled ? 0.5F + 0.5F * Mth.sin((now % 1800L) / 1800.0F * Mth.TWO_PI) : 0.5F;
        for (int i = 0; i < talkers.length && talkerCount < talkerX.length; i++) {
            MarkerTrack t = talkers[i];
            float sx = (float) camera.screenX(t.x(now), vcx);
            float sy = (float) camera.screenY(t.z(now), vcy);
            if (sx < viewX - d || sx > viewX + viewW + d || sy < viewY - 2 * d || sy > viewY + viewH + d) continue;
            int cx = Math.round(sx);
            // The marker floats above the person's head, like the in-world "!".
            int cy = Math.round(sy) - d;
            talkerX[talkerCount] = cx;
            talkerY[talkerCount] = cy;
            talkerIndex[talkerCount] = i;
            talkerCount++;
            boolean lit = hoveredTalker == i;
            boolean selected = t.entityId == selectedTalkerEntity;
            // Gentle pulse: a soft halo breathing in and out.
            disc(g, cx, cy, d + 4, RealmMapPalette.withAlpha(TALK_BLUE_LIGHT, 0.15F + 0.3F * pulse));
            if (selected) {
                ring(g, cx, cy, d + 6, RealmMapPalette.SELECT_RING);
                ring(g, cx, cy, d + 4, RealmMapPalette.SELECT_RING);
            }
            if (lit) disc(g, cx, cy, d + 2, 0xB0FFF3C4);
            drawBadge(g, RealmMapClient.talkerKind(i), cx + 0.5F, cy + 0.5F, d);
            if (selected || lit) {
                String name = RealmMapClient.talkerName(i);
                if (selected && !name.isEmpty()) {
                    int w = font.width(name) + 6;
                    int left = clampLabel(cx - w / 2, viewX + 2, viewX + viewW - w - 2);
                    int ty = clampLabel(cy + d / 2 + 4, viewY + 2, viewY + viewH - 12);
                    g.fill(left - 1, ty - 1, left + w + 1, ty + 11, RealmMapPalette.LABEL_EDGE);
                    g.fill(left, ty, left + w, ty + 10, RealmMapPalette.LABEL_PAPER);
                    g.drawString(font, name, left + 3, ty + 1, Ui2Palette.INK, false);
                }
            }
        }
    }

    // The conversation lane's badges, the same art as the in-world marker (talk, trade, parley, quest).
    private static final ResourceLocation[] BADGE_16 = {badge("talk_16"), badge("trade_16"), badge("parley_16"),
        badge("quest_16")};
    private static final ResourceLocation[] BADGE_32 = {badge("talk"), badge("trade"), badge("parley"),
        badge("quest")};

    private static ResourceLocation badge(String name) {
        return ResourceLocation.fromNamespaceAndPath("hearthstead", "textures/gui/marker/" + name + ".png");
    }

    private static int badgeSlot(int kind) {
        return switch (kind) {
            case com.hearthstead.network.RealmMapMarkersPayload.Talker.TRADE -> 1;
            case com.hearthstead.network.RealmMapMarkersPayload.Talker.PARLEY -> 2;
            case com.hearthstead.network.RealmMapMarkersPayload.Talker.QUEST -> 3;
            default -> 0;
        };
    }

    /**
     * Physical badge size for a GUI-pixel target: the multiple of 16 nearest
     * to it, so the 16 px or 32 px art is scaled by a whole number (crisp).
     */
    static int badgePhysical(float sizeGui, double guiScale) {
        return 16 * Math.max(1, Math.round(sizeGui * (float) guiScale / 16.0F));
    }

    /** Blits a badge centred on (cx, cy) in GUI space at about {@code sizeGui} GUI pixels, pixel-crisp. */
    private void drawBadge(GuiGraphics g, int kind, float cx, float cy, float sizeGui) {
        int phys = badgePhysical(sizeGui, guiScale);
        boolean big = phys % 32 == 0;
        int tex = big ? 32 : 16;
        ResourceLocation art = (big ? BADGE_32 : BADGE_16)[badgeSlot(kind)];
        float inv = (float) (1.0D / guiScale);
        float x = RealmIcons.snap(cx - phys * inv / 2.0F, guiScale);
        float y = RealmIcons.snap(cy - phys * inv / 2.0F, guiScale);
        Matrix4f mat = g.pose().last().pose();
        mat.translate(x, y, 0).scale(inv, inv, 1.0F);
        com.mojang.blaze3d.systems.RenderSystem.enableBlend();
        g.blit(art, 0, 0, phys, phys, 0.0F, 0.0F, tex, tex, tex, tex);
        mat.scale((float) guiScale, (float) guiScale, 1.0F).translate(-x, -y, 0);
    }

    /** About the size of a building badge, rounded to the crisp badge size; odd so halos centre. */
    private int talkDiameter() {
        int crisp = (int) Math.round(badgePhysical(buildingBadgeSize(), guiScale) / guiScale);
        return Math.max(7, crisp) | 1;
    }

    private int buildingBadgeSize() {
        int badge = (int) Math.ceil(RealmIcons.itemGuiSize(1, guiScale)) + 4;
        return badge | 1;
    }

    // ------------------------------------------------------------- life ---

    /** Night falls over the chart with the real game time: a soft ink wash, strongest at midnight. */
    private void drawNightTint(GuiGraphics g, Minecraft mc, int x, int y, int w, int h) {
        long t = Math.floorMod(mc.level.getDayTime(), 24000L);
        float dark;
        if (t < 12000L) {
            dark = 0.0F;
        } else if (t < 13800L) {
            dark = (t - 12000L) / 1800.0F;
        } else if (t < 22200L) {
            dark = 1.0F;
        } else {
            dark = 1.0F - (t - 22200L) / 1800.0F;
        }
        if (dark <= 0.01F) return;
        g.fill(x, y, x + w, y + h, ((int) (dark * 0x46) << 24) | 0x1B2440);
    }

    /** Raiders near the claim: dark figures ringed in red; the chart edge pulses while any are near. */
    private void drawRaiders(GuiGraphics g, long now, double vcx, double vcy) {
        MarkerTrack[] raiders = RealmMapClient.raiders();
        if (raiders.length == 0) return;
        for (int i = 0; i < raiders.length; i++) {
            MarkerTrack r = raiders[i];
            float sx = RealmIcons.snap((float) camera.screenX(r.x(now), vcx), guiScale);
            float sy = RealmIcons.snap((float) camera.screenY(r.z(now), vcy), guiScale);
            if (sx < viewX - 8 || sx > viewX + viewW + 8 || sy < viewY - 8 || sy > viewY + viewH + 12) continue;
            int cx = Math.round(sx);
            int cy = Math.round(sy);
            boolean captain = RealmMapClient.raiderIsCaptain(i);
            int d = captain ? 9 : 7;
            disc(g, cx, cy - 3, d + 4, 0xE0B3261E);
            disc(g, cx, cy - 3, d + 2, 0xFFF3ECDD);
            disc(g, cx, cy - 3, d, 0xFF2A1D18);
            g.fill(cx - 1, cy - 5, cx + 2, cy - 4, 0xFFB3261E);
            if (captain) g.fill(cx - 2, cy - 9, cx + 3, cy - 8, 0xFFCDB57E);
        }
    }

    private void drawRaidEdge(GuiGraphics g, long now, int x, int y, int w, int h) {
        if (RealmMapClient.raiders().length == 0) return;
        float p = HsMotion.enabled ? 0.5F + 0.5F * Mth.sin((now % 1600L) / 1600.0F * Mth.TWO_PI) : 1.0F;
        int c = ((int) (0x40 + 0x60 * p) << 24) | 0xB3261E;
        g.fill(x, y, x + w, y + 2, c);
        g.fill(x, y + h - 2, x + w, y + h, c);
        g.fill(x, y + 2, x + 2, y + h - 2, c);
        g.fill(x + w - 2, y + 2, x + w, y + h - 2, c);
    }

    // Event pings: a settler arrived or a building was declared, at the real place, fading out.
    private static final int PINGS = 16;
    private static final long PING_MS = 4000L;
    private final double[] pingX = new double[PINGS];
    private final double[] pingZ = new double[PINGS];
    private final long[] pingAt = new long[PINGS];
    private final int[] pingKind = new int[PINGS];
    private int pingNext;
    private final java.util.HashSet<UUID> knownIds = new java.util.HashSet<>();
    private int pingVersion = Integer.MIN_VALUE;
    private boolean pingPrimed;

    /** Diffs the roster and buildings once per layout version (not per frame) and queues pings. */
    private void updatePings(RealmMapLayoutPayload layout, long now) {
        if (pingVersion == RealmMapClient.layoutVersion()) return;
        pingVersion = RealmMapClient.layoutVersion();
        for (RealmMapLayoutPayload.BuildingEntry b : layout.buildings()) {
            if (knownIds.add(b.id()) && pingPrimed) {
                ping((b.minX() + b.maxX() + 1) / 2.0, (b.minZ() + b.maxZ() + 1) / 2.0, 1, now);
            }
        }
        for (RealmMapLayoutPayload.RosterEntry r : layout.roster()) {
            if (knownIds.add(r.id()) && pingPrimed) {
                MarkerTrack t = RealmMapClient.track(r.id());
                double px = t != null ? t.x(now) : layout.centerX() + 0.5;
                double pz = t != null ? t.z(now) : layout.centerZ() + 0.5;
                ping(px, pz, 0, now);
            }
        }
        pingPrimed = true;
    }

    private void ping(double x, double z, int kind, long now) {
        long wall = System.currentTimeMillis();
        if (wall - lastPingSound > 400L) {
            lastPingSound = wall;
            com.hearthstead.client.sound.HsSound.ui("ui.map_ping", null, 0.4F, kind == 1 ? 0.9F : 1.05F);
        }
        pingX[pingNext] = x;
        pingZ[pingNext] = z;
        pingAt[pingNext] = now;
        pingKind[pingNext] = kind;
        pingNext = (pingNext + 1) % PINGS;
    }

    private void drawPings(GuiGraphics g, long now, double vcx, double vcy) {
        for (int i = 0; i < PINGS; i++) {
            long age = now - pingAt[i];
            if (pingAt[i] == 0L || age < 0 || age > PING_MS) continue;
            float p = age / (float) PING_MS;
            int cx = (int) Math.round(camera.screenX(pingX[i], vcx));
            int cy = (int) Math.round(camera.screenY(pingZ[i], vcy));
            int ringD = 7 + Math.round(p * 18.0F);
            if ((ringD & 1) == 0) ringD++;
            int color = pingKind[i] == 0 ? RealmMapPalette.STATUS_WORKING : RealmMapPalette.STATUS_SLEEPING;
            ring(g, cx, cy, ringD, RealmMapPalette.withAlpha(color, 1.0F - p));
            ring(g, cx, cy, Math.max(3, ringD - 2), RealmMapPalette.withAlpha(color, (1.0F - p) * 0.5F));
        }
    }

    // Follow trail: the followed settler's last positions, sampled a few times a second.
    private static final int TRAIL = 24;
    private final double[] trailX = new double[TRAIL];
    private final double[] trailZ = new double[TRAIL];
    private int trailCount;
    private int trailNext;
    private long trailLastMs;
    private UUID trailFor;

    private void updateTrail(Minecraft mc, long now, float partialTick) {
        if (!following()) {
            trailCount = 0;
            trailFor = null;
            return;
        }
        MarkerTrack t = RealmMapClient.track(selectedSettler);
        if (t == null) return;
        if (!selectedSettler.equals(trailFor)) {
            trailFor = selectedSettler;
            trailCount = 0;
        }
        if (now - trailLastMs < 280L) return;
        trailLastMs = now;
        double x = worldX(mc, t, now, partialTick);
        double z = worldZ(mc, t, now, partialTick);
        int last = (trailNext - 1 + TRAIL) % TRAIL;
        if (trailCount > 0 && Math.abs(trailX[last] - x) + Math.abs(trailZ[last] - z) < 0.6) return;
        trailX[trailNext] = x;
        trailZ[trailNext] = z;
        trailNext = (trailNext + 1) % TRAIL;
        trailCount = Math.min(TRAIL, trailCount + 1);
    }

    private void drawTrail(GuiGraphics g, double vcx, double vcy) {
        for (int k = 0; k < trailCount; k++) {
            int idx = (trailNext - trailCount + k + TRAIL) % TRAIL;
            float age = (k + 1) / (float) trailCount;
            int px = (int) Math.round(camera.screenX(trailX[idx], vcx));
            int py = (int) Math.round(camera.screenY(trailZ[idx], vcy));
            int side = (k & 1) == 0 ? -1 : 1;
            g.fill(px + side, py, px + side + 1, py + 1, RealmMapPalette.withAlpha(RealmMapPalette.SELECT_RING,
                0.15F + 0.55F * age));
        }
    }

    // Patrol routes (PATROL ROUTES lane): each route a dotted line in its own ink with numbered
    // waypoint dots; each squad member wears a thin ring of that ink under its figure.
    private void drawPatrolRoutes(GuiGraphics g, Font font, Minecraft mc, long now, double vcx, double vcy,
                                  UUID settlementId, float partialTick) {
        patrolSettlement = settlementId;
        patrolDotCount = 0;
        com.hearthstead.network.PatrolSnapshotPayload patrols =
            com.hearthstead.client.patrol.PatrolClient.forSettlement(settlementId);
        if (patrols == null || patrols.routes().isEmpty()) return;
        boolean numbers = camera.zoom() >= 2.0F;
        for (int r = 0; r < patrols.routes().size(); r++) {
            com.hearthstead.network.PatrolSnapshotPayload.Route route = patrols.routes().get(r);
            int ink = com.hearthstead.settlement.guard.patrol.PatrolPalette.ink(route.color());
            List<net.minecraft.core.BlockPos> pts = route.waypoints();
            int legs = route.loop() ? pts.size() : pts.size() - 1;
            for (int i = 0; i < legs; i++) {
                net.minecraft.core.BlockPos a = pts.get(i);
                net.minecraft.core.BlockPos b = pts.get((i + 1) % pts.size());
                double ax = camera.screenX(a.getX() + 0.5D, vcx);
                double ay = camera.screenY(a.getZ() + 0.5D, vcy);
                double dx = camera.screenX(b.getX() + 0.5D, vcx) - ax;
                double dy = camera.screenY(b.getZ() + 0.5D, vcy) - ay;
                double len = Math.sqrt(dx * dx + dy * dy);
                int steps = (int) len;
                for (int k = 3; k < steps - 3; k++) {
                    if (k % 4 >= 2) continue;
                    int x = (int) Math.round(ax + dx * k / len);
                    int y = (int) Math.round(ay + dy * k / len);
                    g.fill(x + 1, y + 1, x + 2, y + 2, 0x60201408);
                    g.fill(x, y, x + 1, y + 1, ink);
                }
            }
            for (UUID member : route.squad()) {
                MarkerTrack t = RealmMapClient.track(member);
                if (t == null) continue;
                int sx = (int) Math.round(camera.screenX(worldX(mc, t, now, partialTick), vcx));
                int sy = (int) Math.round(camera.screenY(worldZ(mc, t, now, partialTick), vcy));
                ring(g, sx, sy, 11, ink);
            }
            for (int i = 0; i < pts.size(); i++) {
                net.minecraft.core.BlockPos p = pts.get(i);
                int x = (int) Math.round(camera.screenX(p.getX() + 0.5D, vcx));
                int y = (int) Math.round(camera.screenY(p.getZ() + 0.5D, vcy));
                boolean hover = route.id() == hoveredPatrolRoute && i == hoveredPatrolIndex;
                disc(g, x, y, hover ? 9 : 7, RealmMapPalette.MARKER_OUTLINE);
                disc(g, x, y, hover ? 7 : 5, ink);
                if (numbers || hover) {
                    String n = String.valueOf(i + 1);
                    int tx = x + 5;
                    int ty = y - 9;
                    g.fill(tx - 1, ty - 1, tx + font.width(n) + 1, ty + 8, RealmMapPalette.LABEL_PAPER);
                    g.drawString(font, n, tx, ty, ink, false);
                }
                if (patrolDotCount < PATROL_DOTS) {
                    patrolDotX[patrolDotCount] = x;
                    patrolDotY[patrolDotCount] = y;
                    patrolDotRoute[patrolDotCount] = route.id();
                    patrolDotIndex[patrolDotCount] = i;
                    patrolDotCount++;
                }
            }
        }
    }

    /** A route ink lifted toward white so it reads on the dark tooltip. */
    private static int patrolTooltipInk(int color) {
        return mix(com.hearthstead.settlement.guard.patrol.PatrolPalette.ink(color), 0xFFFFFFFF, 0.45F)
            & 0xFFFFFF;
    }

    // Perf evidence: average map render time, logged every ten seconds when -Dhearthstead.mapPerf=true.
    private static final boolean PERF = Boolean.getBoolean("hearthstead.mapPerf");
    private long perfNanos;
    private int perfFrames;
    private long perfSince;

    private void perfSample(long startNanos, long now) {
        if (!PERF) return;
        perfNanos += System.nanoTime() - startNanos;
        perfFrames++;
        if (perfSince == 0L) perfSince = now;
        if (now - perfSince >= 10_000L && perfFrames > 0) {
            com.hearthstead.Hearthstead.LOGGER.info("[realm-map] render avg {} us over {} frames, {} settlers, {} raiders",
                perfNanos / perfFrames / 1000L, perfFrames, RealmMapClient.tracks().length,
                RealmMapClient.raiders().length);
            perfNanos = 0L;
            perfFrames = 0;
            perfSince = now;
        }
    }

    // ------------------------------------------------------------ chrome ---

    private static final int CTRL_ZOOM_IN = 0;
    private static final int CTRL_ZOOM_OUT = 1;
    private static final int CTRL_CENTER = 2;
    private static final int CTRL_LEGEND = 3;

    private int controlX(int control) {
        return viewX + viewW - 6 - CONTROL;
    }

    /** Zoom in, zoom out, then (after a small gap) centre on the Banner and the "?" legend key. */
    private int controlY(int control) {
        return switch (control) {
            case CTRL_ZOOM_IN -> viewY + 6;
            case CTRL_ZOOM_OUT -> viewY + 6 + CONTROL;
            case CTRL_CENTER -> viewY + 6 + CONTROL * 2 + 4;
            default -> viewY + 6 + CONTROL * 3 + 8;
        };
    }

    private int controlAt(double mx, double my) {
        for (int c = 0; c < 4; c++) {
            if (RealmMapHitTest.inside(mx, my, controlX(c), controlY(c), CONTROL, CONTROL)) return c;
        }
        return -1;
    }

    private String title = "";

    /** The settlement name for the map's plaque (cheap when unchanged). */
    public void setTitle(String name) {
        title = name == null ? "" : name;
    }

    private void drawChrome(GuiGraphics g, Font font, RealmMapLayoutPayload layout, int x, int y, int w, int h) {
        drawFrame(g, x, y, w, h);
        drawCompass(g, font, x + 6, y + 6);
        // Zoom, centre and legend keys (top-right), each a small paper key; no labelled plates on the chart.
        for (int c = 0; c < 4; c++) {
            int cx = controlX(c);
            int cy = controlY(c);
            boolean enabled = c == CTRL_CENTER || c == CTRL_LEGEND
                || (c == CTRL_ZOOM_IN ? camera.zoomIndex() < camera.steps().length - 1 : camera.zoomIndex() > 0);
            boolean lit = enabled && (hoveredControl == c || c == CTRL_LEGEND && legendOpen);
            g.fill(cx, cy, cx + CONTROL, cy + CONTROL, RealmMapPalette.LABEL_EDGE);
            g.fill(cx + 1, cy + 1, cx + CONTROL - 1, cy + CONTROL - 1, lit ? 0xFFFBF7EE : RealmMapPalette.LABEL_PAPER);
            int ink = !enabled ? Ui2Palette.INK_DISABLED : lit ? Ui2Palette.INK : Ui2Palette.INK_SOFT;
            int mx = cx + CONTROL / 2;
            int my = cy + CONTROL / 2;
            if (c == CTRL_ZOOM_IN) {
                g.fill(mx - 3, my, mx + 4, my + 1, ink);
                g.fill(mx, my - 3, mx + 1, my + 4, ink);
            } else if (c == CTRL_ZOOM_OUT) {
                g.fill(mx - 3, my, mx + 4, my + 1, ink);
            } else if (c == CTRL_CENTER) {
                drawBannerGlyph(g, mx - 2, my + 5, lit);
            } else {
                // A pixel question mark.
                g.fill(mx - 1, my - 4, mx + 1, my - 3, ink);
                g.fill(mx - 2, my - 3, mx - 1, my - 2, ink);
                g.fill(mx + 1, my - 3, mx + 2, my - 1, ink);
                g.fill(mx, my - 1, mx + 1, my + 1, ink);
                g.fill(mx, my + 2, mx + 1, my + 3, ink);
            }
        }
        drawScaleBar(g, font, x + 6, y + h - 5);
        if (hoveredControl == CTRL_LEGEND || legendOpen) {
            drawLegend(g, font, controlX(CTRL_LEGEND) - 3, controlY(CTRL_LEGEND));
        }
        if (following()) {
            // Centred between the compass (left 30 px) and the zoom keys (right 30 px):
            // at 427x240 the chart is 169 px wide and "Following <16-char name>" (~150 px)
            // ran over both.
            int maxText = Math.max(20, w - 60 - 8);
            if (!selectedSettler.equals(followLabelFor) || maxText != followLabelMax) {
                RealmMapLayoutPayload.RosterEntry entry = RealmMapClient.roster(selectedSettler);
                String full = "Following " + (entry == null ? "settler" : entry.name());
                followLabel = font.width(full) <= maxText ? full
                    : font.plainSubstrByWidth(full, Math.max(1, maxText - font.width("..."))) + "...";
                followLabelFor = selectedSettler;
                followLabelMax = maxText;
            }
            String text = followLabel;
            int tw = font.width(text) + 8;
            int fx = x + (w - tw) / 2;
            int fy = y + 5;
            g.fill(fx - 1, fy - 1, fx + tw + 1, fy + 12, RealmMapPalette.SELECT_RING);
            g.fill(fx, fy, fx + tw, fy + 11, RealmMapPalette.LABEL_PAPER);
            g.drawString(font, text, fx + 4, fy + 2, RealmMapPalette.SELECT_RING, false);
        }
    }

    /** An engraved compass rose with N / E / S / W, top-left like a surveyed chart. */
    private static void drawCompass(GuiGraphics g, Font font, int x, int y) {
        int cx = x + 11;
        int cy = y + 13;
        int ink = 0xFF3B2E22;
        int soft = 0xB03B2E22;
        // Long north-south and east-west points.
        for (int i = 1; i <= 7; i++) {
            int wdt = Math.max(0, (7 - i) / 3);
            g.fill(cx - wdt, cy - i, cx + wdt + 1, cy - i + 1, ink);
            g.fill(cx - wdt, cy + i, cx + wdt + 1, cy + i + 1, soft);
            g.fill(cx + i, cy - wdt, cx + i + 1, cy + wdt + 1, soft);
            g.fill(cx - i, cy - wdt, cx - i + 1, cy + wdt + 1, soft);
        }
        // Short diagonals.
        for (int i = 1; i <= 3; i++) {
            g.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, soft);
            g.fill(cx - i, cy - i, cx - i + 1, cy - i + 1, soft);
            g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, soft);
            g.fill(cx - i, cy + i, cx - i + 1, cy + i + 1, soft);
        }
        g.fill(cx, cy, cx + 1, cy + 1, 0xFFF3ECDD);
        g.drawString(font, "N", cx - 2, cy - 17, ink, false);
    }

    private static final int[] SCALE_BLOCKS = {5, 10, 20, 25, 50, 100};
    private static final String[] SCALE_LABELS = {"5 blocks", "10 blocks", "20 blocks", "25 blocks",
        "50 blocks", "100 blocks"};
    private static final int SCALE_INK = 0xC02A2119;
    private static final int SCALE_SHADOW = 0x99F6F0E2;

    /** A small scale bar straight on the chart (no plate), bottom-left, half-transparent ink with a light shadow. */
    private void drawScaleBar(GuiGraphics g, Font font, int x, int bottom) {
        float zoom = camera.zoom();
        int scale = SCALE_BLOCKS.length - 1;
        for (int i = 0; i < SCALE_BLOCKS.length; i++) {
            if (SCALE_BLOCKS[i] * zoom >= 20) {
                scale = i;
                break;
            }
        }
        int len = Math.round(SCALE_BLOCKS[scale] * zoom);
        int y = bottom - 2;
        for (int pass = 0; pass < 2; pass++) {
            int d = pass == 0 ? 1 : 0;
            int c = pass == 0 ? SCALE_SHADOW : SCALE_INK;
            g.fill(x + d, y + d, x + len + d, y + 1 + d, c);
            g.fill(x + d, y - 2 + d, x + 1 + d, y + 1 + d, c);
            g.fill(x + len / 2 + d, y - 1 + d, x + len / 2 + 1 + d, y + 1 + d, c);
            g.fill(x + len - 1 + d, y - 2 + d, x + len + d, y + 1 + d, c);
            g.drawString(font, SCALE_LABELS[scale], x + len + 4 + d, y - 4 + d, c, false);
        }
    }

    private static final Component WAITING = Component.literal("Surveying the realm…");
    private UUID followLabelFor;
    private String followLabel = "";
    private int followLabelMax = -1;

    private static int clampLabel(int value, int min, int max) {
        return max < min ? min : Math.max(min, Math.min(max, value));
    }

    private static final String[] LEGEND = {"Resting", "Stuck or waiting", "Fleeing or fighting", "Wants to talk",
        "Building", "Claim", "Patrol route"};
    private int legendW;

    /** Legend popover left of the "?" key, top-aligned with it and kept inside the chart. */
    private void drawLegend(GuiGraphics g, Font font, int right, int keyTop) {
        if (legendW == 0) {
            for (String row : LEGEND) legendW = Math.max(legendW, font.width(row));
            legendW += 24;
        }
        int rowH = 11;
        int w = legendW;
        int h = LEGEND.length * rowH + 6;
        int left = right - w;
        int top = Math.max(viewY + 4, Math.min(keyTop, viewY + viewH - 4 - h));
        int bottom = top + h;
        g.fill(left - 1, top - 1, right + 1, bottom + 1, RealmMapPalette.LABEL_EDGE);
        g.fill(left, top, right, bottom, 0xFFF8F3E7);
        for (int i = 0; i < LEGEND.length; i++) {
            int rowTop = top + 3 + i * rowH;
            int cx = left + 8;
            switch (i) {
                case 0 -> drawPip(g, RealmMapStatus.SLEEPING, cx - 3, rowTop + 4);
                case 1 -> drawPip(g, RealmMapStatus.STUCK, cx - 1, rowTop + 3);
                case 2 -> drawPip(g, RealmMapStatus.FLEEING, cx - 1, rowTop + 3);
                case 3 -> drawBadge(g, com.hearthstead.network.RealmMapMarkersPayload.Talker.TALK, cx + 0.5F,
                    rowTop + 5.5F, 10.0F);
                case 4 -> badge(g, cx - 4, rowTop + 1, 9, 9);
                case 6 -> {
                    int ink = com.hearthstead.settlement.guard.patrol.PatrolPalette.ink(0);
                    for (int k = 0; k < 8; k += 4) g.fill(cx - 4 + k, rowTop + 5, cx - 2 + k, rowTop + 6, ink);
                    disc(g, cx + 4, rowTop + 5, 7, RealmMapPalette.MARKER_OUTLINE);
                    disc(g, cx + 4, rowTop + 5, 5, ink);
                }
                default -> {
                    for (int k = 0; k < 9; k += 3) {
                        g.fill(cx - 4 + k, rowTop + 5, cx - 2 + k, rowTop + 6, RealmMapPalette.CLAIM_DASH_SHADOW);
                    }
                    for (int k = 0; k < 9; k += 3) g.fill(cx - 4 + k, rowTop + 4, cx - 2 + k, rowTop + 5, 0xFFB9A682);
                }
            }
            g.drawString(font, LEGEND[i], left + 18, rowTop + 2, Ui2Palette.INK_SOFT, false);
        }
    }


    private static void drawFrame(GuiGraphics g, int x, int y, int w, int h) {
        // Dark hairline where the chart meets its parchment mat, and a soft inner shade.
        g.fill(x - 1, y - 1, x + w + 1, y, RealmMapPalette.MAP_FRAME);
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, RealmMapPalette.MAP_FRAME);
        g.fill(x - 1, y, x, y + h, RealmMapPalette.MAP_FRAME);
        g.fill(x + w, y, x + w + 1, y + h, RealmMapPalette.MAP_FRAME);
        g.fillGradient(x, y, x + w, y + 4, RealmMapPalette.MAP_INNER_SHADE, 0x00201408);
        g.fill(x, y, x + 2, y + h, 0x14201408);
    }

    // ------------------------------------------------------------- shapes ---

    /**
     * Half-widths per row of an odd-diameter pixel disc: row {@code i} spans
     * {@code [cx - half, cx + half]}. Cached per diameter (allocated once).
     */
    static int[] discRows(int d) {
        int n = Math.max(1, d | 1);
        int[] out = new int[n];
        double r = n / 2.0D;
        for (int row = 0; row < n; row++) {
            double y = row + 0.5D - r;
            double half = Math.sqrt(Math.max(0.0D, r * r - y * y));
            out[row] = Math.max(0, Math.min(n / 2, (int) Math.round(half - 0.5D)));
        }
        return out;
    }

    private int[] rows(int d) {
        int index = Math.max(1, Math.min(discRows.length - 1, d | 1));
        int[] cached = discRows[index];
        if (cached == null) {
            cached = discRows(index);
            discRows[index] = cached;
        }
        return cached;
    }

    /** Filled disc of odd diameter {@code d} centred on the pixel (cx, cy). */
    private void disc(GuiGraphics g, int cx, int cy, int d, int color) {
        if (d <= 0) return;
        int[] rows = rows(d);
        int top = cy - rows.length / 2;
        for (int row = 0; row < rows.length; row++) {
            int half = rows[row];
            g.fill(cx - half, top + row, cx + half + 1, top + row + 1, color);
        }
    }

    /** One-pixel ring of odd diameter {@code d}. */
    private void ring(GuiGraphics g, int cx, int cy, int d, int color) {
        int[] outer = rows(d);
        int[] inner = outer.length > 2 ? rows(outer.length - 2) : null;
        int top = cy - outer.length / 2;
        for (int row = 0; row < outer.length; row++) {
            int half = outer[row];
            int innerHalf = -1;
            int innerRow = row - 1;
            if (inner != null && innerRow >= 0 && innerRow < inner.length) innerHalf = inner[innerRow];
            if (innerHalf < 0) {
                g.fill(cx - half, top + row, cx + half + 1, top + row + 1, color);
            } else {
                g.fill(cx - half, top + row, cx - innerHalf, top + row + 1, color);
                g.fill(cx + innerHalf + 1, top + row, cx + half + 1, top + row + 1, color);
            }
        }
    }

    private static void outline(GuiGraphics g, int l, int t, int r, int b, int color) {
        g.fill(l, t, r, t + 1, color);
        g.fill(l, b - 1, r, b, color);
        g.fill(l, t + 1, l + 1, b - 1, color);
        g.fill(r - 1, t + 1, r, b - 1, color);
    }

    // -------------------------------------------------------------- hover ---

    private void updateHover(double mx, double my) {
        hoveredSettler = null;
        hoveredBuilding = -1;
        hoveredBanner = false;
        hoveredTalker = -1;
        hoveredControl = -1;
        hoveredPatrolRoute = -1;
        hoveredPatrolIndex = -1;
        if (!RealmMapHitTest.inside(mx, my, viewX, viewY, viewW, viewH) || dragging) return;
        hoveredControl = controlAt(mx, my);
        if (hoveredControl >= 0) return;
        // Talk markers sit above everything, so they win the hover.
        for (int i = talkerCount - 1; i >= 0; i--) {
            if (Math.abs(mx - talkerX[i]) <= talkerR + 1 && Math.abs(my - talkerY[i]) <= talkerR + 1) {
                hoveredTalker = talkerIndex[i];
                return;
            }
        }
        // Uses the previous frame's positions: one frame of latency is invisible.
        int marker = RealmMapHitTest.marker(markerX, markerY, markerCount, mx, my + markerCentreLift(),
            markerHitRadius());
        if (marker >= 0) {
            hoveredSettler = markerTrack[marker].id;
            return;
        }
        for (int i = patrolDotCount - 1; i >= 0; i--) {
            if (Math.abs(mx - patrolDotX[i]) <= 3.5D && Math.abs(my - patrolDotY[i]) <= 3.5D) {
                hoveredPatrolRoute = patrolDotRoute[i];
                hoveredPatrolIndex = patrolDotIndex[i];
                return;
            }
        }
        if (!Float.isNaN(bannerX) && Math.abs(mx - (bannerX + 3)) <= 5 && my >= bannerY - 13 && my <= bannerY + 1) {
            hoveredBanner = true;
            return;
        }
        int b = RealmMapHitTest.rect(bLeft, bTop, bRight, bBottom, buildingCount, mx, my, 1.0F);
        if (b >= 0) hoveredBuilding = bIndex[b];
    }

    public boolean isMouseOver(double mx, double my) {
        return RealmMapHitTest.inside(mx, my, viewX, viewY, viewW, viewH);
    }

    // -------------------------------------------------------------- input ---

    public boolean mouseClicked(double mx, double my, int button) {
        if (!isMouseOver(mx, my) || button != 0) return false;
        int control = controlAt(mx, my);
        if (control >= 0) {
            switch (control) {
                case CTRL_ZOOM_IN -> zoom(1);
                case CTRL_ZOOM_OUT -> zoom(-1);
                case CTRL_CENTER -> centerOnBanner();
                default -> legendOpen = !legendOpen;
            }
            playClick();
            return true;
        }
        pressed = true;
        dragging = false;
        pressX = mx;
        pressY = my;
        return true;
    }

    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!pressed || button != 0) return false;
        if (!dragging && (Math.abs(mx - pressX) > 3 || Math.abs(my - pressY) > 3)) {
            dragging = true;
            follow = false;
            camera.dragBy(mx - pressX, my - pressY);
            return true;
        }
        if (dragging) {
            camera.dragBy(dx, dy);
            long t = Util.getMillis();
            flingX = dx;
            flingY = dy;
            flingAt = t;
        }
        return true;
    }

    public boolean mouseReleased(double mx, double my, int button) {
        if (!pressed || button != 0) return false;
        pressed = false;
        if (dragging) {
            dragging = false;
            // A little inertia: a quick release carries the chart on briefly, then eases to rest.
            if (Util.getMillis() - flingAt < 80L && HsMotion.enabled) camera.panBy(-flingX * 5, -flingY * 5);
            return true;
        }
        updateHover(mx, my);
        if (hoveredTalker >= 0) {
            MarkerTrack[] talkers = RealmMapClient.talkers();
            if (hoveredTalker < talkers.length) {
                MarkerTrack t = talkers[hoveredTalker];
                selectedSettler = null;
                selectedBuilding = null;
                follow = false;
                selectedTalkerEntity = t.entityId;
                selectedAtMs = Util.getMillis();
                long now = Util.getMillis();
                camera.glideTo(t.x(now), t.z(now));
            }
        } else if (hoveredSettler != null) {
            selectSettler(hoveredSettler, false);
        } else if (hoveredBuilding >= 0) {
            RealmMapLayoutPayload layout = RealmMapClient.layout();
            if (layout != null && hoveredBuilding < layout.buildings().size()) {
                selectBuilding(layout.buildings().get(hoveredBuilding).id(), false);
            }
        } else if (hoveredBanner) {
            clearSelection();
            centerOnBanner();
        } else {
            clearSelection();
        }
        playClick();
        listener.onMapSelection();
        return true;
    }

    public boolean mouseScrolled(double mx, double my, double dy) {
        if (!isMouseOver(mx, my) || dy == 0) return false;
        double ax = mx - (viewX + viewW / 2.0D);
        double ay = my - (viewY + viewH / 2.0D);
        if (following()) {
            ax = 0;
            ay = 0;
        }
        camera.zoomBy(dy > 0 ? 1 : -1, ax, ay);
        return true;
    }

    private double flingX;
    private double flingY;
    private long flingAt;

    public boolean dragging() {
        return pressed;
    }

    /** Arrows pan, +/- zoom, C centres, F follows, Esc clears a selection. */
    public boolean keyPressed(int key) {
        int step = 40;
        switch (key) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> {
                follow = false;
                camera.panBy(-step, 0);
                return true;
            }
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> {
                follow = false;
                camera.panBy(step, 0);
                return true;
            }
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> {
                follow = false;
                camera.panBy(0, -step);
                return true;
            }
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> {
                follow = false;
                camera.panBy(0, step);
                return true;
            }
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> {
                zoom(1);
                return true;
            }
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> {
                zoom(-1);
                return true;
            }
            case GLFW.GLFW_KEY_C -> {
                centerOnBanner();
                return true;
            }
            case GLFW.GLFW_KEY_F -> {
                if (selectedSettler == null) return false;
                toggleFollow();
                listener.onMapSelection();
                return true;
            }
            case GLFW.GLFW_KEY_ESCAPE -> {
                if (selectedSettler == null && selectedBuilding == null) return false;
                clearSelection();
                listener.onMapSelection();
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static void playClick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getSoundManager() != null) {
            com.hearthstead.client.sound.HsSound.ui("ui.click",
                net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 0.18F, 1.0F);
        }
    }

    /** Wall-clock time of the last map ping sound, so a burst of new markers pings once. */
    private static long lastPingSound;

    // ----------------------------------------------------------- tooltip ---

    /** Paints the hover tooltip; call last in the frame. */
    public void renderTooltip(GuiGraphics g, Font font, int mouseX, int mouseY) {
        if (!isMouseOver(mouseX, mouseY) || dragging) return;
        int kind;
        UUID id = null;
        int a = 0;
        int b = 0;
        int c = 0;
        if (hoveredTalker >= 0) {
            kind = 5;
            a = hoveredTalker;
            b = RealmMapClient.talkerName(hoveredTalker).hashCode();
        } else if (hoveredControl >= 0) {
            kind = 1;
            a = hoveredControl;
        } else if (hoveredSettler != null) {
            MarkerTrack t = RealmMapClient.track(hoveredSettler);
            if (t == null) return;
            kind = 2;
            id = hoveredSettler;
            a = t.statusId;
            b = t.activityId;
            c = t.professionId;
        } else if (hoveredBuilding >= 0) {
            kind = 3;
            a = hoveredBuilding;
        } else if (hoveredBanner) {
            kind = 4;
        } else if (hoveredPatrolRoute >= 0) {
            kind = 6;
            a = hoveredPatrolRoute;
            b = hoveredPatrolIndex;
        } else {
            return;
        }
        int version = RealmMapClient.layoutVersion() * 31 + com.hearthstead.client.patrol.PatrolClient.version();
        if (kind != tipKind || a != tipA || b != tipB || c != tipC || version != tipVersion
            || (id == null ? tipId != null : !id.equals(tipId))) {
            tipKind = kind;
            tipId = id;
            tipA = a;
            tipB = b;
            tipC = c;
            tipVersion = version;
            tooltip = buildTooltip(font);
        }
        if (!tooltip.isEmpty()) g.renderTooltip(font, tooltip, mouseX, mouseY);
    }

    private int tipKind = -1;
    private UUID tipId;
    private int tipA;
    private int tipB;
    private int tipC;
    private int tipVersion;

    private List<FormattedCharSequence> buildTooltip(Font font) {
        List<Component> lines = new ArrayList<>(4);
        if (hoveredControl == CTRL_LEGEND) {
            return List.of();
        } else if (hoveredPatrolRoute >= 0) {
            com.hearthstead.network.PatrolSnapshotPayload patrols =
                com.hearthstead.client.patrol.PatrolClient.forSettlement(patrolSettlement);
            com.hearthstead.network.PatrolSnapshotPayload.Route route = patrols == null ? null
                : patrols.route(hoveredPatrolRoute);
            if (route != null) {
                lines.add(Component.literal(route.name() + " route").withStyle(ChatFormatting.WHITE));
                lines.add(Component.literal("Waypoint " + (hoveredPatrolIndex + 1) + " of " + route.waypoints().size()
                    + (route.loop() ? " · loop" : " · there and back")).withStyle(ChatFormatting.GRAY));
                if (route.squad().isEmpty()) {
                    lines.add(Component.literal("Nobody on this watch").withStyle(ChatFormatting.DARK_GRAY));
                } else {
                    RealmMapLayoutPayload.RosterEntry lead = RealmMapClient.roster(route.squad().get(0));
                    lines.add(Component.literal(route.squad().size() + " on patrol, led by "
                        + (lead == null ? "a guard" : lead.name())).withStyle(net.minecraft.network.chat.Style.EMPTY
                        .withColor(patrolTooltipInk(route.color()))));
                }
            }
        } else if (hoveredTalker >= 0) {
            String name = RealmMapClient.talkerName(hoveredTalker);
            String title = RealmMapClient.talkerTitle(hoveredTalker);
            MutableComponent who = Component.literal(name.isEmpty() ? "Someone" : name);
            if (!title.isEmpty() && net.minecraft.client.resources.language.I18n.exists(title)) {
                who.append(", ").append(Component.translatable(title));
            }
            String verb = switch (RealmMapClient.talkerKind(hoveredTalker)) {
                case com.hearthstead.network.RealmMapMarkersPayload.Talker.TRADE -> " — wants to trade";
                case com.hearthstead.network.RealmMapMarkersPayload.Talker.PARLEY -> " — offers a parley";
                case com.hearthstead.network.RealmMapMarkersPayload.Talker.QUEST -> " — has a task for you";
                default -> " — wants to talk";
            };
            lines.add(who.append(verb).withStyle(ChatFormatting.WHITE));
            lines.add(Component.literal("Walk up to them to answer").withStyle(ChatFormatting.GRAY));
        } else if (hoveredControl >= 0) {
            lines.add(Component.literal(switch (hoveredControl) {
                case CTRL_ZOOM_IN -> "Zoom in  (+ or scroll)";
                case CTRL_ZOOM_OUT -> "Zoom out  (- or scroll)";
                case CTRL_CENTER -> "Center on the Banner  (C)";
                default -> "What the marks mean";
            }));
        } else if (hoveredSettler != null) {
            MarkerTrack t = RealmMapClient.track(hoveredSettler);
            RealmMapLayoutPayload.RosterEntry entry = RealmMapClient.roster(hoveredSettler);
            if (t != null) {
                lines.add(Component.literal(entry == null ? "Settler" : entry.name()).withStyle(ChatFormatting.WHITE));
                lines.add(Profession.byId(t.professionId).displayName().copy().withStyle(ChatFormatting.GOLD)
                    .append(Component.literal(" · ").withStyle(ChatFormatting.GRAY))
                    .append(SettlerActivity.byId(t.activityId).displayName().copy().withStyle(ChatFormatting.GRAY)));
                com.hearthstead.network.PatrolSnapshotPayload.Route patrol =
                    com.hearthstead.client.patrol.PatrolClient.walking(patrolSettlement, hoveredSettler);
                if (patrol != null) {
                    lines.add(Component.literal("Patrolling: " + patrol.name() + " route").withStyle(
                        net.minecraft.network.chat.Style.EMPTY.withColor(patrolTooltipInk(patrol.color()))));
                }
                lines.add(statusLine(RealmMapStatus.byWireId(t.statusId)));
                lines.add(Component.literal("Click to select").withStyle(ChatFormatting.DARK_GRAY));
            }
        } else if (hoveredBuilding >= 0) {
            RealmMapLayoutPayload layout = RealmMapClient.layout();
            if (layout != null && hoveredBuilding < layout.buildings().size()) {
                RealmMapLayoutPayload.BuildingEntry b = layout.buildings().get(hoveredBuilding);
                lines.add(buildingName(b).copy().withStyle(ChatFormatting.WHITE));
                int workers = workersOf(layout, hoveredBuilding);
                com.hearthstead.building.BuildingType type = com.hearthstead.building.BuildingType.byId(b.typeId());
                boolean staffed = b.workerCapacity() > 0 && (type == null
                    || com.hearthstead.settlement.Employment.tradeOf(type) != Profession.NONE);
                if (staffed) {
                    lines.add(Component.literal(workers + " / " + b.workerCapacity() + " workers")
                        .withStyle(ChatFormatting.GRAY));
                }
                if (!b.valid()) lines.add(Component.literal("Not a valid room").withStyle(ChatFormatting.RED));
                lines.add(Component.literal("Click for details").withStyle(ChatFormatting.DARK_GRAY));
            }
        } else if (hoveredBanner) {
            RealmMapLayoutPayload layout = RealmMapClient.layout();
            lines.add(Component.literal("The Banner").withStyle(ChatFormatting.WHITE));
            if (layout != null) {
                lines.add(Component.literal("Claim radius " + layout.radius() + " blocks").withStyle(ChatFormatting.GRAY));
            }
        }
        List<FormattedCharSequence> out = new ArrayList<>(lines.size());
        for (Component line : lines) out.add(line.getVisualOrderText());
        return out;
    }

    public static Component statusLine(RealmMapStatus status) {
        return switch (status) {
            case WORKING -> Component.literal("● Working").withStyle(ChatFormatting.GREEN);
            case WALKING -> Component.literal("→ Walking").withStyle(ChatFormatting.AQUA);
            case IDLE -> Component.literal("○ Idle").withStyle(ChatFormatting.GRAY);
            case SLEEPING -> Component.literal("z Resting").withStyle(ChatFormatting.GOLD);
            case FLEEING -> Component.literal("! Fleeing").withStyle(ChatFormatting.RED);
            case STUCK -> Component.literal("◆ Stuck — no progress").withStyle(ChatFormatting.RED);
            case FIGHTING -> Component.literal("× Fighting").withStyle(ChatFormatting.RED);
        };
    }

    public static Component buildingName(RealmMapLayoutPayload.BuildingEntry b) {
        com.hearthstead.building.BuildingType type = com.hearthstead.building.BuildingType.byId(b.typeId());
        return type == null ? Component.literal(b.typeId()) : type.displayName();
    }

    public static int workersOf(RealmMapLayoutPayload layout, int buildingIndex) {
        int n = 0;
        for (RealmMapLayoutPayload.RosterEntry r : layout.roster()) if (r.workBuilding() == buildingIndex) n++;
        return n;
    }
}
