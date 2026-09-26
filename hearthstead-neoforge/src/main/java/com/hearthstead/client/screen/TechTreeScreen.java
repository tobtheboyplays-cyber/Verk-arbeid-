package com.hearthstead.client.screen;

import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Type;
import com.hearthstead.entity.Profession;
import com.hearthstead.item.JobEmblemItem;
import com.hearthstead.network.TechTreeActionPayload;
import com.hearthstead.network.TechTreeSnapshotPayload;
import com.hearthstead.settlement.development.DevelopmentNode;
import com.hearthstead.settlement.development.TechTree;
import com.hearthstead.settlement.techtree.EffectRegistry;
import com.hearthstead.settlement.techtree.TechCosts;
import com.hearthstead.settlement.techtree.TechEffect;
import com.hearthstead.settlement.techtree.TechNodeDef;
import com.hearthstead.settlement.techtree.TechTreeData;
import com.mojang.math.Axis;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.Mth;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The v3 tech tree at the Banner: the owner-approved polished radial map
 * (v5, 26 Sep). The Banner seal sits in the middle on a compass rose; ranks
 * are tree-ring bands (Hamlet inside, Castle outside) whose Crown charters
 * are wax seals on the top spoke; each branch owns a wedge. Semantic zoom:
 * LOD 0 status dots + rim progress, LOD 1 medallions + short names, LOD 2
 * full detail with cost chips (150 ms crossfades, hysteresis). Filter chips
 * rotate a wedge to the top; the rank ladder zooms to a band. The side panel
 * holds cost, milestones, requires and the Research button.
 * Layout: {@link TechTreeRadialLayout}; spec: tools/techtree_radial_preview.py.
 */
public class TechTreeScreen extends Screen {
    private static final float MIN_ZOOM = 0.08F;
    private static final float MAX_ZOOM = 2.4F;
    private static final int GOLD = Ui2Palette.GOLD;
    private static final int READY = Ui2Palette.FOREST;
    private static final int AVAILABLE = Ui2Palette.AMBER;
    private static final int LOCKED = Ui2Palette.INK_DISABLED;
    private static final int LOCKED_FILL = Ui2Palette.DISABLED_FILL;
    private static final int BLOCKED = Ui2Palette.DANGER;
    private static final int PLANNED = Ui2Palette.DISABLED_BORDER;
    private static final int STUDYING = Ui2Palette.STATUS_BLUE;
    private static final int EDGE_IDLE = Ui2Palette.INK_MUTED;

    private TechTreeSnapshotPayload snapshot;
    private final TechTreeData data = TechTreeData.get();
    private final Map<String, TechTreeSnapshotPayload.NodeState> states = new HashMap<>();
    private final TechTreeRadialLayout layout = TechTreeRadialLayout.compute(data);
    private String filter = "all";
    /** Survival QA U8: the Journey's next tech node, preselected with a ribbon. */
    private String journeyNode = "";
    /** Two-step Research: armed node and when the confirm window closes. */
    @Nullable
    private String armedId;
    private long armedUntil;
    private float rot;
    private float targetRot;
    private int lod = 1;
    private final float[] lodAlpha = {0.0F, 1.0F, 0.0F};
    private int guiScale = 3;

    // Layout (screen space), on the standard Bannerhold frame.
    private com.hearthstead.client.ui2.Ui2FrameLayout frame;
    private final com.hearthstead.client.ui2.Ui2Serif.Text titleText =
        new com.hearthstead.client.ui2.Ui2Serif.Text(com.hearthstead.client.ui2.Ui2Serif.Size.TITLE);
    private boolean legendOpen;
    private int viewX;
    private int viewY;
    private int viewW;
    private int viewH;
    private int sideX;
    private int sideW;

    // Camera: world point at the viewport centre + zoom, eased toward targets.
    private float camX;
    private float camY;
    private float zoom = 0.5F;
    private float targetCamX;
    private float targetCamY;
    private float targetZoom = 0.5F;
    private long lastFrame = -1L;
    private boolean dragging;
    private double dragDistance;

    @Nullable
    private String hovered;
    @Nullable
    private String selected;
    private Set<String> hoverPath = Set.of();
    private final Map<String, Float> glow = new HashMap<>();

    private Ui2Button researchButton;
    private List<PanelRow> panelRows = List.of();
    private int panelScroll;
    private Component toast = Component.empty();
    private int toastColor = Ui2Palette.INK;
    private long toastUntil;

    public TechTreeScreen(TechTreeSnapshotPayload snapshot) {
        super(Component.translatableWithFallback("hearthstead.techtree.title", "Tech Tree"));
        accept(snapshot);
    }

    public boolean accepts(TechTreeSnapshotPayload fresh) {
        return fresh != null && snapshot != null
            && fresh.settlementId().equals(snapshot.settlementId());
    }

    public void update(TechTreeSnapshotPayload fresh) {
        accept(fresh);
        if (!fresh.feedbackKey().isEmpty()) {
            TechTreeSnapshotPayload.NodeState learned = states.get(fresh.feedbackArg());
            showToast(resultText(fresh.feedbackKey(), fresh.feedbackArg()),
                fresh.feedbackGood() ? Ui2Palette.FOREST : Ui2Palette.DANGER);
            if (fresh.feedbackGood()) {
                HsUi.playConfirmSound();
                if (learned != null) {
                    glow.put(learned.id(), 2.5F);
                }
            } else {
                HsUi.playErrorSound();
            }
        }
        rebuildPanel();
    }

    private void accept(TechTreeSnapshotPayload fresh) {
        snapshot = fresh;
        journeyNode = fresh.journeyNode();
        states.clear();
        for (TechTreeSnapshotPayload.NodeState node : fresh.nodes()) {
            states.put(node.id(), node);
        }
    }

    // --------------------------------------------------------------- layout

    @Override
    protected void init() {
        int margin = width < 520 ? 2 : 6;
        frame = com.hearthstead.client.ui2.Ui2FrameLayout.at(margin, margin + 2, width - margin * 2,
            height - margin * 2 - 2, false);
        var page = frame.page();
        sideW = Mth.clamp(Math.round(page.width() * 0.3F), 150, 240);
        sideX = page.right() - sideW - 4;
        viewX = page.x() + 1;
        viewY = page.y() + 1;
        viewW = sideX - 5 - viewX;
        viewH = page.height() - 2;
        boolean first = lastFrame < 0;
        guiScale = Math.max(1, (int) Math.round(net.minecraft.client.Minecraft.getInstance().getWindow().getGuiScale()));
        if (first) {
            resetView();
            zoom = targetZoom;
            camX = targetCamX;
            camY = targetCamY;
            lod = zoom * guiScale < 1.25F ? 0 : zoom * guiScale >= 3.4F ? 2 : 1;
            java.util.Arrays.fill(lodAlpha, 0.0F);
            lodAlpha[lod] = 1.0F;
            if (!journeyNode.isEmpty() && layout.pos(journeyNode) != null) {
                selected = journeyNode;
                camX = targetCamX = wx(journeyNode) * 0.5F;
                camY = targetCamY = wy(journeyNode) * 0.5F;
            } else {
                selected = firstReady();
            }
        }
        researchButton = Ui2Button.banner(sideX + 6, viewY + viewH - 26,
            sideW - 12, 20, Component.literal("Research"), this::research);
        addRenderableWidget(researchButton);
        addRenderableWidget(com.hearthstead.client.ui2.Ui2Frame.closeKey(frame, this::onClose));
        var close = frame.close();
        var legendKey = new com.hearthstead.client.ui2.Ui2WoodKey(close.x() - 15, close.y(), 11, 11,
            Component.literal("?"), () -> legendOpen = !legendOpen);
        legendKey.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
            Component.translatableWithFallback("hearthstead.techtree.legend_key",
                "Legend and controls (?)")));
        addRenderableWidget(legendKey);
        rebuildPanel();
    }

    @Nullable
    private String firstReady() {
        for (TechNodeDef def : data.nodes()) {
            if (status(def.id()) == TechTree.Status.READY) {
                return def.id();
            }
        }
        return null;
    }

    // --------------------------------------------------------------- render

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        tickCamera();
        renderBackground(g, mouseX, mouseY, partialTick);
        com.hearthstead.client.ui2.Ui2Frame.board(g, frame);
        var page = frame.page();
        BannerChrome.parchment(g, page.x(), page.y(), page.width(), page.height());
        com.hearthstead.client.ui2.Ui2Frame.crest(g, frame);
        renderHeader(g);
        updateHover(mouseX, mouseY);
        g.enableScissor(viewX, viewY, viewX + viewW, viewY + viewH);
        hitTargets.clear();
        hitIds.clear();
        renderBands(g);
        renderWedges(g);
        renderRings(g);
        renderRimOrnament(g);
        renderEdges(g);
        renderCentre(g);
        renderNodes(g);
        renderSeals(g);
        renderSpokeLabels(g);
        renderRimPlates(g);
        renderVignette(g);
        renderChips(g, mouseX, mouseY);
        renderLadder(g, mouseX, mouseY);
        renderToast(g);
        if (legendOpen) {
            renderMiniLegend(g);
        }
        g.disableScissor();
        renderSide(g, mouseX, mouseY);
        HsUi.widgets(this, g, mouseX, mouseY, partialTick);
        if (armedId != null && researchButton != null && researchButton.visible) {
            float pulse = 0.5F + 0.5F * Mth.sin(Util.getMillis() / 120.0F);
            BannerChrome.outline(g, researchButton.getX() - 2, researchButton.getY() - 2,
                researchButton.getWidth() + 4, researchButton.getHeight() + 4,
                withAlpha(Ui2Palette.GOLD, Math.round(0x90 + 0x6F * pulse)));
        }
        if (hovered != null && !hovered.equals(selected)) {
            renderHoverTip(g, hovered, mouseX, mouseY);
        }
    }

    private void tickCamera() {
        long now = Util.getMillis();
        float dt = lastFrame < 0 ? 0.016F : Math.min(0.1F, (now - lastFrame) / 1000.0F);
        lastFrame = now;
        float k = 1.0F - (float) Math.exp(-dt * 14.0F);
        float kr = 1.0F - (float) Math.exp(-dt * 12.0F);
        double limit = layout.outer();
        targetCamX = (float) Mth.clamp(targetCamX, -limit, limit);
        targetCamY = (float) Mth.clamp(targetCamY, -limit, limit);
        if (!dragging) {
            camX += (targetCamX - camX) * k;
            camY += (targetCamY - camY) * k;
        }
        zoom += (targetZoom - zoom) * k;
        float dr = Mth.wrapDegrees(targetRot - rot);
        rot += dr * kr;
        if (Math.abs(dr) < 0.01F) {
            rot = targetRot;
        }
        updateLod(dt);
        if (armedId != null && now >= armedUntil) {
            armedId = null;
            if (researchButton != null) {
                researchButton.setMessage(Component.translatableWithFallback("hearthstead.techtree.research", "Research"));
            }
        }
        glow.replaceAll((id, v) -> Math.max(0.0F, v - dt));
        glow.values().removeIf(v -> v <= 0.0F);
    }

    private void renderHeader(GuiGraphics g) {
        var t = frame.title();
        Component coins = Component.translatableWithFallback("hearthstead.techtree.coins",
            "%s Coins", snapshot.coins());
        Component rank = Component.translatableWithFallback("hearthstead.techtree.rank",
            "Rank: %s", rankName());
        int boxW = font.width(coins) + 24;
        int boxX = frame.close().x() - 21 - boxW;
        int rankX = boxX - 10 - font.width(rank);
        com.hearthstead.client.ui2.Ui2Frame.title(g, font, frame, titleText,
            title.getString(), null);
        int y = t.y() + (t.height() - 16) / 2;
        if (rankX > t.x() + titleText.width() + 12) {
            g.drawString(font, rank, rankX, y + 4, BannerChrome.TEXT_ON_WOOD_MUTED, false);
        }
        if (boxX > t.x() + titleText.width() + 8) {
            BannerChrome.counterBox(g, boxX, y, boxW, 16);
            g.pose().pushPose();
            g.pose().translate(boxX + 2, y + 1, 0);
            g.pose().scale(0.875F, 0.875F, 1.0F);
            g.renderItem(new ItemStack(coinItem()), 0, 0);
            g.pose().popPose();
            g.drawString(font, coins, boxX + 19, y + 4, BannerChrome.TEXT_ON_WOOD, false);
        }
    }

    private static net.minecraft.world.item.Item coinItem() {
        return BuiltInRegistries.ITEM.getOptional(
            ResourceLocation.fromNamespaceAndPath("hearthstead", "gold_coin")).orElse(Items.GOLD_NUGGET);
    }

    private Component rankName() {
        String[] seals = {"kingdom_crown", "castle_charter", "town_charter", "first_raid_aftermath"};
        String[] names = {"Kingdom", "Castle", "Town", "Village"};
        for (int i = 0; i < seals.length; i++) {
            if (status(seals[i]) == TechTree.Status.LEARNED) {
                return Component.translatableWithFallback("hearthstead.techtree.rank." + i, names[i]);
            }
        }
        return Component.translatableWithFallback("hearthstead.techtree.rank.hamlet", "Hamlet");
    }

    // =================================================================
    // Radial graph (v5, owner-approved): tree-ring rank bands, branch
    // wedges, wax charter seals on the top spoke, semantic zoom.
    // Layout: TechTreeRadialLayout (spec: tools/techtree_radial_preview.py)
    // =================================================================

    private static final int WAX = 0xFF8C2A24;
    private static final int PATH_INK = 0xFF6B4A2A;
    private static final int WARM = 0xFFE8C98A;
    private static final String[] RANK_NAMES = {"Hamlet", "Village", "Town", "Castle", "Kingdom"};
    private static final String[] SEAL_IDS = {"settlement_charter", "first_raid_aftermath", "town_charter",
        "castle_charter", "kingdom_crown"};
    private static final Map<String, net.minecraft.world.item.Item> BRANCH_MARK = Map.of(
        "watch", Items.IRON_SWORD, "logistics", Items.CHEST, "commons", Items.BREAD,
        "craft", Items.IRON_PICKAXE);
    private static final Set<String> STOP_WORDS = Set.of("the", "of", "&", "and", "to", "a");

    /** Current rank as a tier 1..5 (Hamlet..Kingdom) from the learned seals. */
    private int currentRank() {
        for (int t = 5; t >= 2; t--) {
            if (status(SEAL_IDS[t - 1]) == TechTree.Status.LEARNED) {
                return t;
            }
        }
        return 1;
    }

    private float labelScale() {
        return Math.min(1.0F, 2.0F / guiScale);
    }

    private float wx(String id) {
        TechTreeRadialLayout.Polar p = layout.pos(id);
        return p == null ? 0.0F : (float) p.x(rot);
    }

    private float wy(String id) {
        TechTreeRadialLayout.Polar p = layout.pos(id);
        return p == null ? 0.0F : (float) p.y(rot);
    }

    private float px(double r, double angle) {
        return sx((float) (Math.sin(Math.toRadians(angle + rot)) * r));
    }

    private float py(double r, double angle) {
        return sy((float) (-Math.cos(Math.toRadians(angle + rot)) * r));
    }

    private boolean faded(String branch) {
        return !"all".equals(filter) && !filter.equals(branch) && !"crown".equals(branch);
    }

    private float detail() {
        return lodAlpha[1] + lodAlpha[2];
    }

    /** Opening view: the Banner in the middle, the current and next rank rings in view. */
    private float defaultZoom() {
        int cur = currentRank();
        double radius = layout.bandEdge(Math.min(4, cur + 1)) + 24.0;
        float fit = (float) (Math.min(viewW, viewH - 34) / (2.0 * radius));
        return Mth.clamp(Math.max(fit, 1.4F / guiScale), 0.08F, 3.0F);
    }

    private void resetView() {
        filter = "all";
        targetRot = 0.0F;
        targetZoom = defaultZoom();
        targetCamX = 0.0F;
        targetCamY = 0.0F;
    }

    private void applyFilter(String key) {
        if ("all".equals(key)) {
            resetView();
            return;
        }
        TechTreeRadialLayout.Sector sector = layout.sector(key);
        if (sector == null) {
            return;
        }
        filter = key;
        targetRot = (float) -sector.mid();
        int cur = currentRank();
        double rMid = (layout.bandEdge(Math.max(0, cur - 1)) + layout.bandEdge(Math.min(4, cur + 1))) / 2.0;
        targetZoom = Math.max(defaultZoom(), 1.6F / guiScale);
        targetCamX = 0.0F;
        targetCamY = (float) -rMid;
    }

    private void zoomToRank(int tier) {
        double radius = layout.bandEdge(Math.min(4, tier)) + 24.0;
        targetZoom = Mth.clamp((float) (Math.min(viewW, viewH - 34) / (2.0 * radius)), 0.08F, 3.0F);
        targetCamX = 0.0F;
        targetCamY = 0.0F;
    }

    /** Semantic zoom with hysteresis: LOD 0 dots, 1 icons + names, 2 full detail. */
    private void updateLod(float dt) {
        float eff = zoom * guiScale;
        if (lod == 0 && eff >= 1.35F) {
            lod = 1;
        } else if (lod == 1 && eff < 1.15F) {
            lod = 0;
        } else if (lod == 1 && eff >= 3.5F) {
            lod = 2;
        } else if (lod == 2 && eff < 3.2F) {
            lod = 1;
        }
        float step = dt / 0.15F;
        for (int i = 0; i < 3; i++) {
            float target = i == lod ? 1.0F : 0.0F;
            lodAlpha[i] += Mth.clamp(target - lodAlpha[i], -step, step);
        }
    }

    private void renderBands(GuiGraphics g) {
        int cur = currentRank();
        for (int t = 1; t <= 4; t++) {
            int color;
            if (t == cur) {
                color = withAlpha(WARM, 0x55);
            } else if (t < cur) {
                color = withAlpha(Ui2Palette.PAPER_DEEP, t % 2 == 1 ? 0x70 : 0x30);
            } else {
                color = withAlpha(0xFFB9B2A4, t % 2 == 1 ? 0x40 : 0x28);
            }
            if (ladderHover == t - 1) {
                color = withAlpha(WARM, 0x80);
            }
            annulus(g, layout.bandEdge(t - 1), layout.bandEdge(t), 0.0, 360.0, color);
        }
    }

    private void renderWedges(GuiGraphics g) {
        double inner = layout.bandEdge(0);
        double outer = layout.bandEdge(4);
        for (String b : TechTreeRadialLayout.SECTORS) {
            TechTreeRadialLayout.Sector sec = layout.sector(b);
            TechTreeData.Branch branch = data.branch(b);
            int color = branch == null ? Ui2Palette.GOLD : branch.color();
            annulus(g, inner, outer, sec.a0(), sec.a1(), withAlpha(color, faded(b) ? 0x06 : 0x10));
            line(g, px(inner, sec.a0()), py(inner, sec.a0()), px(outer, sec.a0()), py(outer, sec.a0()), 1.0F,
                withAlpha(Ui2Palette.INK_MUTED, 0x90));
            float d = detail();
            if (d > 0.05F && !faded(b)) {
                double rm = (layout.rankStart(2) + layout.rankStart(4)) / 2.0;
                float mx = px(rm, sec.mid());
                float my = py(rm, sec.mid());
                float size = 90.0F * zoom;
                // Watermark: the branch emblem as a faint tinted silhouette.
                int wm = withAlpha(color, Math.round(0x16 * Math.min(1.0F, d)));
                disc(g, mx, my, size * 0.55F, wm);
                disc(g, mx, my, size * 0.42F, withAlpha(Ui2Palette.PAPER, Math.round(0x10 * Math.min(1.0F, d))));
            }
        }
    }

    private void renderRings(GuiGraphics g) {
        float cx = sx(0);
        float cy = sy(0);
        for (int t = 1; t <= 4; t++) {
            float r = (float) layout.bandEdge(t) * zoom;
            circle(g, cx, cy, r, withAlpha(Ui2Palette.INK, 0x70));
            circle(g, cx, cy, r + 2.0F, withAlpha(Ui2Palette.INK, 0x38));
        }
    }

    private void renderRimOrnament(GuiGraphics g) {
        float cx = sx(0);
        float cy = sy(0);
        float r = (float) layout.bandEdge(4) * zoom;
        circle(g, cx, cy, r + 1.5F, withAlpha(Ui2Palette.INK, 0x90));
        circle(g, cx, cy, r + 4.5F, withAlpha(Ui2Palette.GOLD, 0xD0));
        circle(g, cx, cy, r + 6.0F, withAlpha(Ui2Palette.INK, 0x60));
        float d = detail();
        if (d > 0.05F) {
            for (int ang = 45; ang < 360; ang += 90) {
                double a = Math.toRadians(ang);
                float x = cx + (float) Math.sin(a) * (r + 3.0F);
                float y = cy - (float) Math.cos(a) * (r + 3.0F);
                diamond(g, x, y, 4.0F, withAlpha(Ui2Palette.GOLD, Math.round(0xFF * Math.min(1, d))));
                diamond(g, x, y, 2.0F, withAlpha(Ui2Palette.GOLD_SOFT, Math.round(0xFF * Math.min(1, d))));
            }
        }
    }

    private void renderCentre(GuiGraphics g) {
        float cx = sx(0);
        float cy = sy(0);
        float R = (float) TechTreeRadialLayout.CENTRE * zoom;
        long now = Util.getMillis();
        float pulse = 0.5F + 0.5F * Mth.sin(now / 1400.0F);
        for (int q = 8; q > 0; q--) {
            disc(g, cx, cy, R + 4 + q * 3 * Math.max(zoom, 0.4F), withAlpha(WARM, 0x0A + Math.round(4 * pulse)));
        }
        for (int ang = 0; ang < 360; ang += 15) {
            double a = Math.toRadians(ang);
            float l0 = R * 2.3F;
            float l1 = R * (ang % 90 == 0 ? 2.6F : 2.45F);
            line(g, cx + (float) Math.sin(a) * l0, cy - (float) Math.cos(a) * l0,
                cx + (float) Math.sin(a) * l1, cy - (float) Math.cos(a) * l1, 1.0F, withAlpha(Ui2Palette.INK_MUTED, 0x90));
        }
        for (int ang = 0; ang < 360; ang += 45) {
            boolean major = ang % 90 == 0;
            float len = R * (major ? 2.1F : 1.6F);
            float w = R * (major ? 0.28F : 0.2F);
            double a = Math.toRadians(ang);
            double s = Math.toRadians(ang + 90);
            float tx = cx + (float) Math.sin(a) * len;
            float ty = cy - (float) Math.cos(a) * len;
            float b1x = cx + (float) Math.sin(s) * w;
            float b1y = cy - (float) Math.cos(s) * w;
            float b2x = cx - (float) Math.sin(s) * w;
            float b2y = cy + (float) Math.cos(s) * w;
            quad(g, tx, ty, b1x, b1y, b2x, b2y, b2x, b2y,
                withAlpha(major ? Ui2Palette.GOLD : Ui2Palette.INK_MUTED, major ? 0x90 : 0x60));
        }
        disc(g, cx, cy, R + 5, withAlpha(Ui2Palette.GOLD_SOFT, 0x50));
        disc(g, cx, cy, R + 2, withAlpha(Ui2Palette.GOLD, 0xE0));
        disc(g, cx, cy, R, Ui2Palette.GOLD_SOFT);
        dottedCircle(g, cx, cy, R - 3, Ui2Palette.GOLD);
        TechNodeDef root = data.node("settlement_charter");
        if (root != null) {
            drawIcon(g, root, cx - R * 0.6F, cy - R * 0.6F, R * 1.2F);
        }
        hitTargets.add(new float[] {cx, cy, R + 2});
        hitIds.add("settlement_charter");
    }

    private void renderEdges(GuiGraphics g) {
        String focus = hovered != null ? hovered : selected;
        Set<String> path = new HashSet<>();
        if (focus != null) {
            path.addAll(data.ancestors(focus));
            path.add(focus);
        }
        float d = detail();
        long now = Util.getMillis();
        for (TechNodeDef def : data.nodes()) {
            if ("crown".equals(def.branch())) {
                continue;
            }
            for (String reqId : def.requires()) {
                TechNodeDef req = data.node(reqId);
                if (req == null || !req.branch().equals(def.branch())) {
                    continue; // cross-branch needs: chip + highlight, never a line
                }
                boolean strong = path.contains(def.id()) && path.contains(reqId);
                if (!strong && d < 0.05F) {
                    continue;
                }
                boolean fromLearned = status(reqId) == TechTree.Status.LEARNED;
                boolean both = fromLearned && status(def.id()) == TechTree.Status.LEARNED;
                int color;
                if (strong) {
                    color = fromLearned ? PATH_INK : branchColor(def);
                } else {
                    int base = both ? PATH_INK : EDGE_IDLE;
                    int alpha = both ? 0xB0 : 0x55;
                    if (both) {
                        alpha += Math.round(0x18 * Mth.sin(now / 900.0F + def.id().hashCode()));
                    }
                    color = withAlpha(base, Math.round(Mth.clamp(alpha, 0, 255) * Math.min(1, d)
                        * (faded(def.branch()) ? 0.35F : 1.0F)));
                }
                List<TechTreeRadialLayout.Polar> pts = layout.edge(reqId, def.id());
                float thick = strong ? 2.0F : 1.0F;
                for (int i = 1; i < pts.size(); i++) {
                    TechTreeRadialLayout.Polar a = pts.get(i - 1);
                    TechTreeRadialLayout.Polar b = pts.get(i);
                    line(g, px(a.r(), a.angle()), py(a.r(), a.angle()), px(b.r(), b.angle()),
                        py(b.r(), b.angle()), thick, color);
                }
                if ((strong || lodAlpha[2] > 0.5F) && pts.size() >= 2) {
                    TechTreeRadialLayout.Polar a = pts.get(pts.size() - 2);
                    TechTreeRadialLayout.Polar b = pts.get(pts.size() - 1);
                    float x1 = px(b.r(), b.angle());
                    float y1 = py(b.r(), b.angle());
                    float ang = (float) Math.atan2(y1 - py(a.r(), a.angle()), x1 - px(a.r(), a.angle()));
                    for (float spread : new float[] {0.5F, -0.5F}) {
                        line(g, x1 - Mth.cos(ang + spread) * 4, y1 - Mth.sin(ang + spread) * 4, x1, y1,
                            strong ? 1.5F : 1.0F, color);
                    }
                }
            }
        }
    }

    private final List<float[]> hitTargets = new ArrayList<>();
    private final List<String> hitIds = new ArrayList<>();
    private final List<float[]> labelBoxes = new ArrayList<>();
    private final Map<String, List<String>> wrapCache = new HashMap<>();

    private void renderNodes(GuiGraphics g) {
        String focus = hovered != null ? hovered : selected;
        Set<String> needsHi = new HashSet<>();
        if (focus != null && data.node(focus) != null) {
            TechNodeDef fd = data.node(focus);
            for (String r : fd.requires()) {
                TechNodeDef rd = data.node(r);
                if (rd != null && !rd.branch().equals(fd.branch()) && !"crown".equals(rd.branch())) {
                    needsHi.add(r);
                }
            }
        }
        long now = Util.getMillis();
        float a0 = lodAlpha[0];
        float d = detail();
        float k = labelScale();
        List<TechNodeDef> order = new ArrayList<>();
        for (TechNodeDef def : data.nodes()) {
            if (!"crown".equals(def.branch())) {
                order.add(def);
            }
        }
        order.sort((x, y) -> Integer.compare(labelPriority(x.id(), focus), labelPriority(y.id(), focus)));
        labelBoxes.clear();
        for (TechNodeDef def : order) {
            float x = sx(wx(def.id()));
            float y = sy(wy(def.id()));
            float R = Math.max(7.0F, (float) TechTreeRadialLayout.MED * zoom);
            labelBoxes.add(new float[] {x - R, y - R, x + R, y + R});
        }
        List<float[]> clasps = new ArrayList<>();
        if (detail() > 0.05F) {
            Set<String> pairs = new HashSet<>();
            for (TechNodeDef def : order) {
                for (String ex : def.excludes()) {
                    String key = def.id().compareTo(ex) < 0 ? def.id() + "|" + ex : ex + "|" + def.id();
                    if (layout.pos(ex) == null || faded(def.branch()) || !pairs.add(key)) {
                        continue;
                    }
                    float ax = sx(wx(def.id()));
                    float ay = sy(wy(def.id()));
                    float bx = sx(wx(ex));
                    float by = sy(wy(ex));
                    float R = Math.max(7.0F, (float) TechTreeRadialLayout.MED * zoom);
                    float ck = Math.max(k, 0.5F);
                    float mx = (ax + bx) / 2;
                    float my = Math.min(ay, by) - R - 5 * ck;
                    clasps.add(new float[] {mx, my, ck, ax, ay, bx, by});
                    labelBoxes.add(new float[] {mx - 8 * ck, my - 5 * ck, mx + 8 * ck, my + 5 * ck});
                }
            }
        }
        for (TechNodeDef def : order) {
            String id = def.id();
            float x = sx(wx(id));
            float y = sy(wy(id));
            if (x < viewX - 60 || x > viewX + viewW + 60 || y < viewY - 60 || y > viewY + viewH + 60) {
                continue;
            }
            TechTree.Status status = status(id);
            boolean fade = faded(def.branch());
            float fm = fade ? 0.3F : 1.0F;
            int ring = ringColor(status);
            int fill = fillColor(def, status);
            // LOD 0: status dots
            if (a0 > 0.02F) {
                int al = Math.round(0xFF * a0 * fm);
                disc(g, x, y, 4.0F, withAlpha(ring, al));
                disc(g, x, y, 3.0F, withAlpha(status == TechTree.Status.LEARNED ? ring : fill, al));
                hitTargets.add(new float[] {x, y, 5.0F});
                hitIds.add(id);
            }
            if (d < 0.02F) {
                continue;
            }
            float R = Math.max(7.0F, (float) TechTreeRadialLayout.MED * zoom);
            boolean lift = id.equals(hovered);
            float ly = lift ? y - 1.0F : y;
            int al = Math.round(0xFF * Math.min(1.0F, d) * fm);
            if (lift) {
                disc(g, x + 1.0F, y + 1.5F, R + 1.5F, withAlpha(0xFF201408, Math.round(0x50 * Math.min(1.0F, d))));
            }
            if (id.equals(focus)) {
                disc(g, x, ly, R + 4.0F, withAlpha(Ui2Palette.INK, Math.round(0x70 * Math.min(1.0F, d))));
            }
            if (needsHi.contains(id)) {
                disc(g, x, ly, R + 4.0F, withAlpha(Ui2Palette.FOREST, Math.round(0x80 * Math.min(1.0F, d))));
            }
            if (status == TechTree.Status.READY) {
                float pulse = 0.5F + 0.5F * Mth.sin(now / 520.0F);
                disc(g, x, ly, R + 2.5F + pulse * 2.0F, withAlpha(READY, Math.round((0x30 + 0x30 * pulse) * Math.min(1, d) * fm)));
            }
            Float g0 = glow.get(id);
            if (g0 != null) {
                disc(g, x, ly, R + 4 + (2.5F - g0) * 6, withAlpha(GOLD, Math.round(0x90 * g0 / 2.5F)));
            }
            ResourceLocation roundel = com.hearthstead.client.techtree.TechTreeIcons.custom(id);
            if (roundel != null) {
                // Crisp pixel art: the 32-texel roundel at a whole number of
                // 16-physical-pixel steps, on the physical pixel grid.
                float phys = Math.max(16.0F, Math.round(2 * R * guiScale / 16.0F) * 16.0F);
                R = phys / (2.0F * guiScale);
                x = Math.round(x * guiScale) / (float) guiScale;
                ly = Math.round(ly * guiScale) / (float) guiScale;
            }
            disc(g, x, ly, R + 1.5F, withAlpha(ring, al));
            if (roundel == null) {
                disc(g, x, ly, R, withAlpha(fill, al));
                if (status == TechTree.Status.PLANNED) {
                    hatch(g, x, ly, R, withAlpha(Ui2Palette.INK_MUTED, Math.round(0x70 * Math.min(1, d))));
                }
            }
            if (status == TechTree.Status.STUDYING) {
                TechTreeSnapshotPayload.NodeState st = states.get(id);
                float frac = st == null || st.studyTotal() <= 0 ? 0 : (float) st.studyDone() / st.studyTotal();
                arc(g, x, ly, R + 3, frac, STUDYING);
            }
            if (d > 0.35F && roundel != null) {
                // Style C: the painted roundel IS the medallion face.
                boolean dim = fade || status == TechTree.Status.LOCKED || status == TechTree.Status.PLANNED;
                com.hearthstead.client.techtree.TechTreeIcons.draw(g, roundel, x - R, ly - R, 2 * R,
                    dim ? 0.55F : 1.0F);
                if (status == TechTree.Status.PLANNED) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 200);
                    hatch(g, x, ly, R, withAlpha(Ui2Palette.INK_MUTED, Math.round(0x60 * Math.min(1, d))));
                    g.pose().popPose();
                }
                if (status == TechTree.Status.LEARNED) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 205);
                    float t = (Util.getMillis() % 4000L) / 4000.0F;
                    for (int q = -3; q <= 3; q++) {
                        double a = Math.toRadians(-135 + q * 9 + 12 * Mth.sin(t * 6.283F));
                        float gx = x + (float) Math.cos(a) * (R + 0.5F);
                        float gy = ly + (float) Math.sin(a) * (R + 0.5F);
                        g.fill(Math.round(gx), Math.round(gy), Math.round(gx) + 1, Math.round(gy) + 1,
                            withAlpha(0xFFFFF1C4, 0xE0 - Math.abs(q) * 0x28));
                    }
                    g.pose().popPose();
                }
                if (!fade) {
                    badge(g, status, x + R * 0.62F, ly - R * 0.95F);
                }
            } else if (d > 0.35F) {
                float size = R * 1.25F;
                drawIcon(g, def, x - size / 2, ly - size / 2, size);
                if (fade || status == TechTree.Status.LOCKED || status == TechTree.Status.PLANNED) {
                    g.pose().pushPose();
                    g.pose().translate(0, 0, 200);
                    disc(g, x, ly, R - 0.5F, withAlpha(Ui2Palette.DISABLED_FILL, fade ? 0xA0 : 0x60));
                    g.pose().popPose();
                }
                if (!fade) {
                    badge(g, status, x + R * 0.62F, ly - R * 0.95F);
                }
            }
            hitTargets.add(new float[] {x, y, R + 2.0F});
            hitIds.add(id);
            if (id.equals(journeyNode) && status != TechTree.Status.LEARNED) {
                journeyRibbon(g, x, ly - R - 2.0F, Math.min(1.0F, d));
            }
            if (fade) {
                continue;
            }
            // Names (LOD 1+): word wrap, collision culled by priority.
            float maxW = (float) (TechTreeRadialLayout.S * zoom / k - 6.0);
            List<String> lines = wrapName(def, maxW);
            float bw = 0;
            for (String line : lines) {
                bw = Math.max(bw, font.width(line) * k);
            }
            float lh = lines.size() * 9 * k;
            // below, right, left, above: first spot that is free
            float[][] spots = {
                {x, ly + R + 3.0F},
                {x + R + 3 + bw / 2, ly - lh / 2},
                {x - R - 3 - bw / 2, ly - lh / 2},
                {x, ly - R - 3 - lh}};
            boolean important = id.equals(focus) || id.equals(journeyNode) || status == TechTree.Status.READY
                || status == TechTree.Status.AVAILABLE || status == TechTree.Status.STUDYING;
            float[] chosen = null;
            for (float[] spot : spots) {
                float[] box = {spot[0] - bw / 2 - 1, spot[1] - 1, spot[0] + bw / 2 + 1, spot[1] + lh};
                if (!clashes(box)) {
                    chosen = spot;
                    labelBoxes.add(box);
                    break;
                }
                if (!important) {
                    break; // only important labels move; the rest show on hover
                }
            }
            if (chosen == null) {
                if (!important) {
                    continue;
                }
                chosen = spots[0];
            }
            x = chosen[0];
            float ty = chosen[1];
            int ink = status == TechTree.Status.LOCKED || status == TechTree.Status.PLANNED
                ? Ui2Palette.INK_MUTED : Ui2Palette.INK;
            int tal = Math.round(0xFF * Math.min(1.0F, d));
            g.pose().pushPose();
            g.pose().translate(0, 0, 250);
            for (String line : lines) {
                float w = font.width(line) * k;
                g.fill(Math.round(x - w / 2 - 1), Math.round(ty - 1), Math.round(x + w / 2 + 1),
                    Math.round(ty + 8 * k + 1), withAlpha(Ui2Palette.PAPER, Math.round(0xC8 * Math.min(1, d))));
                text(g, line, x - w / 2, ty, withAlpha(ink, tal), k);
                ty += 9 * k;
            }
            // LOD 2: cost chips + one-line effect
            if (lodAlpha[2] > 0.05F) {
                int a2 = Math.round(0xFF * lodAlpha[2]);
                List<DevelopmentNode.Cost> costs = safeCosts(def);
                TechTreeSnapshotPayload.NodeState st = states.get(id);
                float rowW = 0;
                int shown = Math.min(4, costs.size());
                for (int i = 0; i < shown; i++) {
                    rowW += 8 + font.width(String.valueOf(costs.get(i).count())) * k + 3;
                }
                float cx = x - rowW / 2;
                ty += 1;
                for (int i = 0; i < shown; i++) {
                    DevelopmentNode.Cost cost = costs.get(i);
                    int have = st != null && i < st.have().length ? st.have()[i] : 0;
                    g.pose().pushPose();
                    g.pose().translate(cx, ty, 0);
                    g.pose().scale(0.45F, 0.45F, 1.0F);
                    g.renderItem(new ItemStack(cost.item()), 0, 0);
                    g.pose().popPose();
                    text(g, String.valueOf(cost.count()), cx + 8, ty + 1,
                        withAlpha(have >= cost.count() ? Ui2Palette.FOREST : Ui2Palette.INK_SOFT, a2), k);
                    cx += 8 + font.width(String.valueOf(cost.count())) * k + 3;
                }
                ty += 8;
                String effect = shortEffect(def, maxW);
                if (!effect.isEmpty()) {
                    float w = font.width(effect) * k;
                    text(g, effect, x - w / 2, ty, withAlpha(Ui2Palette.INK_MUTED, a2), k);
                }
            }
            g.pose().popPose();
        }
        // OR clasps: a small bracket over the pair with the plate at its middle.
        int ca = Math.round(0xFF * Math.min(1.0F, detail()));
        for (float[] c : clasps) {
            float mx = c[0];
            float my = c[1];
            float ck = c[2];
            g.pose().pushPose();
            g.pose().translate(0, 0, 260);
            line(g, c[3], c[4] - 4, c[3], my, 1.0F, withAlpha(Ui2Palette.DANGER, ca / 2));
            line(g, c[5], c[6] - 4, c[5], my, 1.0F, withAlpha(Ui2Palette.DANGER, ca / 2));
            line(g, c[3], my, c[5], my, 1.0F, withAlpha(Ui2Palette.DANGER, ca / 2));
            g.fill(Math.round(mx - 7 * ck), Math.round(my - 4 * ck), Math.round(mx + 7 * ck), Math.round(my + 4 * ck),
                withAlpha(Ui2Palette.DANGER, ca));
            text(g, "OR", mx - 5.5F * ck, my - 3.5F * ck, withAlpha(Ui2Palette.ON_ACCENT, ca), ck);
            g.pose().popPose();
        }
    }

    private boolean clashes(float[] box) {
        for (float[] b : labelBoxes) {
            if (!(box[2] <= b[0] || box[0] >= b[2] || box[3] <= b[1] || box[1] >= b[3])) {
                return true;
            }
        }
        return false;
    }

    /** A small gold "Journey" ribbon hanging above a medallion. */
    private void journeyRibbon(GuiGraphics g, float cx, float bottom, float alpha) {
        float k = labelScale();
        Component text = Component.translatableWithFallback("hearthstead.techtree.journey_ribbon", "Journey");
        float w = font.width(text) * k + 8;
        float h = 9 * k + 3;
        float x0 = cx - w / 2;
        float y0 = bottom - h;
        int a = Math.round(0xFF * alpha);
        g.pose().pushPose();
        g.pose().translate(0, 0, 240);
        quad(g, x0 - 3, y0, x0, y0 + h / 2, x0 - 3, y0 + h, x0, y0 + h, withAlpha(Ui2Palette.GOLD, a));
        quad(g, x0 + w, y0, x0 + w + 3, y0, x0 + w, y0 + h / 2, x0 + w + 3, y0 + h, withAlpha(Ui2Palette.GOLD, a));
        g.fill(Math.round(x0), Math.round(y0), Math.round(x0 + w), Math.round(y0 + h), withAlpha(Ui2Palette.GOLD, a));
        g.fill(Math.round(x0), Math.round(y0), Math.round(x0 + w), Math.round(y0) + 1, withAlpha(Ui2Palette.GOLD_SOFT, a));
        text(g, text.getString(), x0 + 4, y0 + 2, withAlpha(Ui2Palette.ON_ACCENT, a), k);
        g.pose().popPose();
    }

    private int labelPriority(String id, @Nullable String focus) {
        if (id.equals(focus)) {
            return -1;
        }
        return switch (status(id)) {
            case READY -> 0;
            case STUDYING -> 1;
            case AVAILABLE -> 2;
            case LEARNED -> 3;
            case BLOCKED -> 4;
            case LOCKED -> 5;
            default -> 6;
        };
    }

    private void badge(GuiGraphics g, TechTree.Status status, float bx, float by) {
        int color;
        String glyph;
        switch (status) {
            case LEARNED -> { color = GOLD; glyph = "✔"; }
            case READY -> { color = READY; glyph = "!"; }
            case BLOCKED -> { color = BLOCKED; glyph = "x"; }
            case PLANNED -> { color = PLANNED; glyph = "?"; }
            case STUDYING -> { color = STUDYING; glyph = "⌛"; }
            case LOCKED -> { color = LOCKED; glyph = null; }
            default -> { return; }
        }
        g.pose().pushPose();
        g.pose().translate(0, 0, 230);
        g.fill(Math.round(bx), Math.round(by), Math.round(bx) + 7, Math.round(by) + 7, color);
        if (glyph != null) {
            text(g, glyph, bx + 1, by, Ui2Palette.ON_ACCENT, 0.75F);
        } else {
            com.hearthstead.client.ui2.Ui2Surface.lockGlyph(g, Math.round(bx) + 1, Math.round(by) + 1, Ui2Palette.ON_ACCENT);
        }
        g.pose().popPose();
    }

    private List<String> wrapName(TechNodeDef def, float maxW) {
        String key = def.id() + "|" + Math.round(maxW);
        return wrapCache.computeIfAbsent(key, ignored -> wrapWords(def.shortName().getString(), maxW));
    }

    /** Word-boundary wrap, 2 lines max, never ending a line on "the"/"&"/"of"; ellipsis. */
    private List<String> wrapWords(String text, float maxW) {
        String[] words = text.replace(" (Warehouse ", " (W").split(" ");
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : words) {
            String next = cur.length() == 0 ? w : cur + " " + w;
            if (font.width(next) <= maxW || cur.length() == 0) {
                cur = new StringBuilder(next);
            } else {
                out.add(cur.toString());
                cur = new StringBuilder(w);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        for (int q = 0; q < out.size() - 1; q++) {
            String line = out.get(q);
            int sp = line.lastIndexOf(' ');
            if (sp > 0 && STOP_WORDS.contains(line.substring(sp + 1).toLowerCase(java.util.Locale.ROOT))) {
                out.set(q, line.substring(0, sp));
                out.set(q + 1, line.substring(sp + 1) + " " + out.get(q + 1));
            }
        }
        if (out.size() > 2) {
            out = new ArrayList<>(List.of(out.get(0), String.join(" ", out.subList(1, out.size()))));
        }
        for (int q = 0; q < out.size(); q++) {
            String line = out.get(q);
            if (font.width(line) > maxW) {
                while (line.length() > 2 && font.width(line + "…") > maxW) {
                    line = line.substring(0, line.length() - 1);
                }
                out.set(q, line + "…");
            }
        }
        return out;
    }

    private String shortEffect(TechNodeDef def, float maxW) {
        String o = def.offersText().getString();
        int colon = o.indexOf(':');
        if (colon >= 0 && colon < o.length() - 1) {
            o = o.substring(colon + 1).trim();
        }
        for (char c : new char[] {'.', ',', ';'}) {
            int i = o.indexOf(c);
            if (i > 0) {
                o = o.substring(0, i);
            }
        }
        if (font.width(o) > maxW) {
            while (o.length() > 3 && font.width(o + "…") > maxW) {
                o = o.substring(0, o.length() - 1);
            }
            o = o + "…";
        }
        return o;
    }

    private void renderSeals(GuiGraphics g) {
        long now = Util.getMillis();
        float d = Math.max(detail(), lodAlpha[0]);
        for (int t = 2; t <= 5; t++) {
            String id = SEAL_IDS[t - 1];
            TechNodeDef def = data.node(id);
            if (def == null || layout.pos(id) == null) {
                continue;
            }
            double gapWorld = Math.min(
                Math.abs(layout.bandEdge(t - 1) - layout.bandEdge(Math.max(0, t - 2))),
                t <= 4 ? Math.abs(layout.bandEdge(t) - layout.bandEdge(t - 1)) : 999.0);
            float R = Mth.clamp((float) Math.min(13.0 * zoom, gapWorld * zoom * 0.42), 3.5F, 16.0F);
            // Stagger alternate seals left of the spoke when rings are tight.
            double r0 = layout.pos(id).r();
            double off = gapWorld * zoom < 2 * R + 3 && t % 2 == 1
                ? -Math.toDegrees((2.2 * R) / Math.max(1.0, r0 * zoom)) : 0.0;
            float x = px(r0, off);
            float y = py(r0, off);
            TechTree.Status status = status(id);
            boolean learned = status == TechTree.Status.LEARNED;
            int wax = learned ? Ui2Palette.GOLD : WAX;
            if (learned) {
                disc(g, x, y, R + 5, withAlpha(WARM, 0x60));
            } else if (status == TechTree.Status.READY || status == TechTree.Status.STUDYING
                || status == TechTree.Status.AVAILABLE) {
                float pulse = 0.5F + 0.5F * Mth.sin(now / 600.0F);
                disc(g, x, y, R + 3 + pulse * 2, withAlpha(Ui2Palette.FOREST, Math.round(0x30 + 0x30 * pulse)));
            }
            if (id.equals(hovered != null ? hovered : selected)) {
                disc(g, x, y, R + 5, withAlpha(Ui2Palette.INK, 0x60));
            }
            for (int j = 0; j < 12; j++) {
                double a = Math.toRadians(j * 30);
                disc(g, x + (float) Math.cos(a) * R * 0.78F, y + (float) Math.sin(a) * R * 0.78F, R * 0.34F, wax);
            }
            disc(g, x, y, R * 0.9F, wax);
            disc(g, x, y, R * 0.66F, darken(wax, 0.8F));
            dottedCircle(g, x, y, R * 0.58F, withAlpha(lighten(wax, 0.4F), 0xC0));
            drawIcon(g, def, x - R * 0.45F, y - R * 0.45F, R * 0.9F);
            if (status == TechTree.Status.STUDYING) {
                TechTreeSnapshotPayload.NodeState st = states.get(id);
                float frac = st == null || st.studyTotal() <= 0 ? 0 : (float) st.studyDone() / st.studyTotal();
                arc(g, x, y, R + 3, frac, STUDYING);
            }
            hitTargets.add(new float[] {x, y, R + 2});
            hitIds.add(id);
        }
    }

    /** Rank names written along each band, beside the divider spokes. */
    private void renderSpokeLabels(GuiGraphics g) {
        float k = labelScale();
        int cur = currentRank();
        float d = detail();
        List<Double> spokes = new ArrayList<>();
        for (String b : TechTreeRadialLayout.SECTORS) {
            spokes.add(layout.sector(b).a0());
        }
        for (int s = 0; s < spokes.size(); s++) {
            float alpha = s == 0 ? 1.0F : Math.min(1.0F, d);
            if (alpha < 0.03F) {
                continue;
            }
            for (int t = 1; t <= 4; t++) {
                double rMid = (layout.bandEdge(t - 1) + layout.bandEdge(t)) / 2.0;
                String name = lodAlpha[0] > 0.5F ? RANK_NAMES[t - 1].toUpperCase(java.util.Locale.ROOT) : RANK_NAMES[t - 1];
                int color = t == cur ? Ui2Palette.GOLD : withAlpha(Ui2Palette.INK_MUTED, 0xC0);
                textOnArc(g, name, rMid, spokes.get(s) + 1.2 + (s == 0 ? TechTreeRadialLayout.SEAL_PAD : 0),
                    withAlpha(color, Math.round(((color >>> 24) & 0xFF) * alpha)), k);
            }
        }
    }

    private void textOnArc(GuiGraphics g, String text, double r, double startAngle, int color, float k) {
        double screenR = Math.max(1.0, r * zoom);
        double ang = startAngle;
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            float w = font.width(ch) * k;
            double mid = ang + Math.toDegrees((w / 2.0) / screenR);
            float x = px(r, mid);
            float y = py(r, mid);
            g.pose().pushPose();
            g.pose().translate(x, y, 245);
            g.pose().mulPose(Axis.ZP.rotationDegrees((float) (mid + rot)));
            g.pose().scale(k, k, 1.0F);
            g.drawString(font, ch, -font.width(ch) / 2, -4, color, false);
            g.pose().popPose();
            ang += Math.toDegrees(w / screenR);
        }
    }

    private void renderRimPlates(GuiGraphics g) {
        double outer = layout.bandEdge(4) + 10;
        float k = lodAlpha[0] > 0.5F ? 1.0F : labelScale();
        for (String b : TechTreeRadialLayout.SECTORS) {
            TechTreeRadialLayout.Sector sec = layout.sector(b);
            TechTreeData.Branch branch = data.branch(b);
            int color = branch == null ? Ui2Palette.GOLD : branch.color();
            int total = 0;
            int done = 0;
            for (TechNodeDef def : data.nodes()) {
                if (def.branch().equals(b)) {
                    total++;
                    if (status(def.id()) == TechTree.Status.LEARNED) {
                        done++;
                    }
                }
            }
            int steps = 60;
            for (int j = 0; j < steps; j++) {
                double t0 = sec.a0() + TechTreeRadialLayout.PAD + (sec.a1() - sec.a0() - 2 * TechTreeRadialLayout.PAD) * j / steps;
                double t1 = sec.a0() + TechTreeRadialLayout.PAD + (sec.a1() - sec.a0() - 2 * TechTreeRadialLayout.PAD) * (j + 1) / steps;
                int c = j < steps * done / Math.max(1, total) ? color : withAlpha(color, 0x40);
                line(g, px(outer, t0), py(outer, t0), px(outer, t1), py(outer, t1), 3.0F,
                    faded(b) ? withAlpha(color, 0x30) : c);
            }
            if (faded(b)) {
                continue;
            }
            Component label = Component.translatableWithFallback("hearthstead.techtree.branch." + b,
                branch == null ? b : branch.name());
            Component sub = Component.translatableWithFallback("hearthstead.techtree.progress",
                "%s/%s learned", done, total);
            float w = Math.max(font.width(label), font.width(sub)) * k + 16;
            float x = px(outer + 18 / Math.max(zoom, 0.3F), sec.mid());
            float y = py(outer + 18 / Math.max(zoom, 0.3F), sec.mid());
            x = Mth.clamp(x, viewX + w / 2 + 4, viewX + viewW - w / 2 - 4);
            y = Mth.clamp(y, viewY + 26, viewY + viewH - 30);
            g.pose().pushPose();
            g.pose().translate(0, 0, 270);
            g.fill(Math.round(x - w / 2 - 1), Math.round(y - 9), Math.round(x + w / 2 + 1), Math.round(y + 11 * k + 1),
                darken(color, 0.8F));
            g.fill(Math.round(x - w / 2), Math.round(y - 8), Math.round(x + w / 2), Math.round(y + 11 * k),
                Ui2Palette.FRAME_INNER);
            g.pose().pushPose();
            g.pose().translate(x - w / 2 + 2, y - 7, 0);
            g.pose().scale(0.75F, 0.75F, 1.0F);
            g.renderItem(new ItemStack(BRANCH_MARK.getOrDefault(b, Items.PAPER)), 0, 0);
            g.pose().popPose();
            text(g, label.getString(), x - w / 2 + 15, y - 6, darken(color, 0.75F), k);
            text(g, sub.getString(), x - w / 2 + 15, y - 6 + 9 * k, Ui2Palette.INK_MUTED, k);
            g.pose().popPose();
        }
    }

    private void renderVignette(GuiGraphics g) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 275);
        for (int i = 0; i < 10; i++) {
            int c = withAlpha(0xFF3A2A18, Math.round(0x22 * (1 - i / 10.0F)));
            g.fill(viewX, viewY + i, viewX + viewW, viewY + i + 1, c);
            g.fill(viewX, viewY + viewH - i - 1, viewX + viewW, viewY + viewH - i, c);
            g.fill(viewX + i, viewY, viewX + i + 1, viewY + viewH, c);
            g.fill(viewX + viewW - i - 1, viewY, viewX + viewW - i, viewY + viewH, c);
        }
        g.pose().popPose();
    }

    private void renderChips(GuiGraphics g, int mouseX, int mouseY) {
        chipRects.clear();
        chipKeys.clear();
        int x = viewX + 4;
        int y = viewY + 4;
        g.pose().pushPose();
        g.pose().translate(0, 0, 290);
        List<String> keys = new ArrayList<>();
        keys.add("all");
        keys.addAll(TechTreeRadialLayout.SECTORS);
        int stripW = 2;
        for (String key : keys) {
            Component lbl = "all".equals(key) ? Component.translatableWithFallback("hearthstead.techtree.filter.all", "All")
                : Component.translatableWithFallback("hearthstead.techtree.branch_short." + key, shortBranch(key));
            stripW += font.width(lbl) + 11;
        }
        g.fill(x - 2, y - 2 + 2, x + stripW + 2, y + 13 + 2, 0x38201408);
        g.fill(x - 2, y - 2, x + stripW, y + 13, Ui2Palette.PAPER);
        BannerChrome.outline(g, x - 2, y - 2, stripW + 2, 15, Ui2Palette.RULE_STRONG);
        for (String key : keys) {
            TechTreeData.Branch branch = data.branch(key);
            Component label = "all".equals(key)
                ? Component.translatableWithFallback("hearthstead.techtree.filter.all", "All")
                : Component.translatableWithFallback("hearthstead.techtree.branch_short." + key, shortBranch(key));
            int w = font.width(label) + 8;
            boolean on = key.equals(filter);
            boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 11;
            int color = branch == null ? Ui2Palette.INK_SOFT : branch.color();
            g.fill(x, y, x + w, y + 11, on ? color : hover ? Ui2Palette.PAPER_DEEP : Ui2Palette.FRAME_INNER);
            BannerChrome.outline(g, x, y, w, 11, on ? color : Ui2Palette.RULE_STRONG);
            g.drawString(font, label, x + 4, y + 2, on ? Ui2Palette.ON_ACCENT : Ui2Palette.INK_SOFT, false);
            chipRects.add(new int[] {x, y, w, 11});
            chipKeys.add(key);
            x += w + 3;
        }
        g.pose().popPose();
    }

    private void renderLadder(GuiGraphics g, int mouseX, int mouseY) {
        ladderRects.clear();
        int cur = currentRank();
        String arrow = " ▸ ";
        int total = 12;
        for (int i = 0; i < RANK_NAMES.length; i++) {
            total += font.width(rankLabel(i)) + (i < RANK_NAMES.length - 1 ? font.width(arrow) : 0);
        }
        int x = viewX + (viewW - total) / 2;
        int y = viewY + viewH - 14;
        g.pose().pushPose();
        g.pose().translate(0, 0, 290);
        g.fill(x - 2, y, x + total + 4, y + 13, 0x38201408);
        g.fill(x - 2, y - 2, x + total + 2, y + 11, Ui2Palette.PAPER);
        BannerChrome.outline(g, x - 2, y - 2, total + 4, 13, Ui2Palette.RULE_STRONG);
        x += 6;
        ladderHover = -1;
        for (int i = 0; i < RANK_NAMES.length; i++) {
            Component name = rankLabel(i);
            int w = font.width(name);
            boolean on = i + 1 == cur;
            boolean hover = mouseX >= x - 3 && mouseX < x + w + 3 && mouseY >= y - 1 && mouseY < y + 10;
            if (hover) {
                ladderHover = i;
            }
            if (on || hover) {
                g.fill(x - 3, y - 1, x + w + 3, y + 10, withAlpha(WARM, on ? 0xC0 : 0x70));
            }
            g.drawString(font, name, x, y + 1, on ? Ui2Palette.INK : i + 1 < cur ? Ui2Palette.INK_SOFT
                : Ui2Palette.INK_MUTED, false);
            ladderRects.add(new int[] {x - 3, y - 1, w + 6, 11});
            x += w;
            if (i < RANK_NAMES.length - 1) {
                g.drawString(font, arrow, x, y + 1, Ui2Palette.INK_MUTED, false);
                x += font.width(arrow);
            }
        }
        g.pose().popPose();
    }

    private Component rankLabel(int i) {
        return Component.translatableWithFallback("hearthstead.techtree.rankname." + i, RANK_NAMES[i]);
    }

    private final List<int[]> chipRects = new ArrayList<>();
    private final List<String> chipKeys = new ArrayList<>();
    private final List<int[]> ladderRects = new ArrayList<>();
    private int ladderHover = -1;

    // ------------------------------------------------------------ drawing kit

    private void drawIcon(GuiGraphics g, TechNodeDef def, float x, float y, float size) {
        ResourceLocation custom = com.hearthstead.client.techtree.TechTreeIcons.custom(def.id());
        if (custom != null) {
            com.hearthstead.client.techtree.TechTreeIcons.draw(g, custom, x, y, size);
            return;
        }
        if (MISSING_ICON_WARNED.add(def.id())) {
            com.hearthstead.Hearthstead.LOGGER.warn("Tech tree node {} has no custom icon "
                + "(textures/gui/techtree/node/{}.png); using its item icon", def.id(), def.id());
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(size / 16.0F, size / 16.0F, 1.0F);
        g.renderItem(icon(def), 0, 0);
        g.pose().popPose();
    }

    private static final Set<String> MISSING_ICON_WARNED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void text(GuiGraphics g, String text, float x, float y, int color, float k) {
        if (((color >>> 24) & 0xFF) < 8) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x, y, 0.0F);
        g.pose().scale(k, k, 1.0F);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    private void annulus(GuiGraphics g, double r0, double r1, double a0, double a1, int color) {
        if (((color >>> 24) & 0xFF) == 0 || r1 <= r0) {
            return;
        }
        int steps = Math.max(12, (int) ((a1 - a0) / 3.0));
        for (int k = 0; k < steps; k++) {
            double t0 = a0 + (a1 - a0) * k / steps;
            double t1 = a0 + (a1 - a0) * (k + 1) / steps;
            quad(g, px(r0, t0), py(r0, t0), px(r1, t0), py(r1, t0), px(r1, t1), py(r1, t1),
                px(r0, t1), py(r0, t1), color);
        }
    }

    private static void circle(GuiGraphics g, float cx, float cy, float r, int color) {
        int steps = Math.max(48, Math.round(r * 0.8F));
        float px0 = cx;
        float py0 = cy - r;
        for (int q = 1; q <= steps; q++) {
            double a = 2 * Math.PI * q / steps;
            float x = cx + (float) Math.sin(a) * r;
            float y = cy - (float) Math.cos(a) * r;
            line(g, px0, py0, x, y, 1.0F, color);
            px0 = x;
            py0 = y;
        }
    }

    private static void diamond(GuiGraphics g, float x, float y, float d, int color) {
        quad(g, x, y - d, x - d, y, x, y + d, x + d, y, color);
    }

    /** A filled quad (degenerate for triangles), wound like GuiGraphics.fill. */
    private static void quad(GuiGraphics g, float x1, float y1, float x2, float y2, float x3, float y3,
                             float x4, float y4, int color) {
        org.joml.Matrix4f m = g.pose().last().pose();
        com.mojang.blaze3d.vertex.VertexConsumer vc =
            g.bufferSource().getBuffer(net.minecraft.client.renderer.RenderType.gui());
        float area = (x2 - x1) * (y3 - y1) - (y2 - y1) * (x3 - x1);
        if (area > 0) {
            vc.addVertex(m, x1, y1, 0).setColor(color);
            vc.addVertex(m, x4, y4, 0).setColor(color);
            vc.addVertex(m, x3, y3, 0).setColor(color);
            vc.addVertex(m, x2, y2, 0).setColor(color);
        } else {
            vc.addVertex(m, x1, y1, 0).setColor(color);
            vc.addVertex(m, x2, y2, 0).setColor(color);
            vc.addVertex(m, x3, y3, 0).setColor(color);
            vc.addVertex(m, x4, y4, 0).setColor(color);
        }
    }

    private void renderMiniLegend(GuiGraphics g) {
        Object[][] keys = {
            {"learned", "Learned", GOLD}, {"ready", "Ready to research", READY},
            {"available", "Needs more (see panel)", AVAILABLE}, {"locked", "Locked", LOCKED},
            {"blocked", "Closed: other choice taken", BLOCKED}, {"planned", "Planned: not in game yet", PLANNED},
            {"studying", "Being studied", STUDYING}};
        String[][] help = {{"drag", "Drag: pan"}, {"scroll", "Wheel: zoom"}, {"click", "Click: details"},
            {"home", "Home: recentre"}, {"enter", "Enter: research"}};
        int w = 0;
        for (Object[] key : keys) {
            w = Math.max(w, font.width(Component.translatableWithFallback(
                "hearthstead.techtree.legend." + key[0], (String) key[1])) + 16);
        }
        int helpW = 0;
        for (String[] h : help) {
            helpW = Math.max(helpW, font.width(Component.translatableWithFallback(
                "hearthstead.techtree.help." + h[0], h[1])));
        }
        boolean twoCols = viewW >= w + helpW + 30;
        int rows = twoCols ? Math.max(keys.length, help.length) : keys.length + help.length + 1;
        int plateW = twoCols ? w + helpW + 22 : Math.max(w, helpW) + 12;
        int plateH = 16 + rows * 11;
        int x = viewX + 6;
        int y = viewY + viewH - plateH - 6;
        g.pose().pushPose();
        g.pose().translate(0, 0, 300);
        g.fill(x - 1, y - 1, x + plateW + 1, y + plateH + 1, Ui2Palette.RULE_STRONG);
        g.fill(x, y, x + plateW, y + plateH, Ui2Palette.PAPER);
        g.drawString(font, Component.translatableWithFallback("hearthstead.techtree.legend.title", "Legend"),
            x + 6, y + 4, Ui2Palette.INK_MUTED, false);
        int ry = y + 15;
        for (Object[] key : keys) {
            disc(g, x + 10, ry + 4, 4, (Integer) key[2]);
            if ("planned".equals(key[0])) {
                dottedCircle(g, x + 10, ry + 4, 5.5F, PLANNED);
            }
            g.drawString(font, Component.translatableWithFallback("hearthstead.techtree.legend." + key[0],
                (String) key[1]), x + 18, ry, Ui2Palette.INK_SOFT, false);
            ry += 11;
        }
        int hx = twoCols ? x + w + 16 : x + 6;
        int hy = twoCols ? y + 15 : ry + 4;
        for (String[] h : help) {
            g.drawString(font, Component.translatableWithFallback("hearthstead.techtree.help." + h[0], h[1]),
                hx, hy, Ui2Palette.INK_MUTED, false);
            hy += 11;
        }
        g.pose().popPose();
    }

    private void renderToast(GuiGraphics g) {
        if (Util.getMillis() >= toastUntil) {
            return;
        }
        int w = Math.min(viewW - 20, font.width(toast) + 16);
        int x = viewX + (viewW - w) / 2;
        int y = viewY + 8;
        g.pose().pushPose();
        g.pose().translate(0, 0, 320);
        g.fill(x - 1, y - 1, x + w + 1, y + 17, toastColor);
        g.fill(x, y, x + w, y + 16, Ui2Palette.FRAME_INNER);
        g.fill(x, y, x + 2, y + 16, toastColor);
        FormattedCharSequence line = font.split(toast, w - 12).isEmpty() ? FormattedCharSequence.EMPTY
            : font.split(toast, w - 12).get(0);
        g.drawString(font, line, x + 8, y + 4, toastColor, false);
        g.pose().popPose();
    }

    // ----------------------------------------------------------- side panel

    private record PanelRow(@Nullable FormattedCharSequence text, int color, ItemStack icon,
                            float progress, int height, @Nullable String link) {
    }

    private List<Component> chips = List.of();
    private int chipColor = Ui2Palette.INK_MUTED;

    private static String shortBranch(String id) {
        return switch (id) {
            case "watch" -> "Watch";
            case "logistics" -> "Logistics";
            case "commons" -> "Commons";
            case "craft" -> "Craft";
            default -> "Crown";
        };
    }

    private void rebuildPanel() {
        if (researchButton == null) {
            return;
        }
        List<PanelRow> rows = new ArrayList<>();
        int w = sideW - 16;
        TechNodeDef def = selected == null ? null : data.node(selected);
        if (def == null) {
            text(rows, Component.translatableWithFallback("hearthstead.techtree.pick",
                "Click a node to see what it costs and what it gives."), Ui2Palette.INK_SOFT, w);
            researchButton.visible = false;
            panelRows = rows;
            return;
        }
        TechTreeSnapshotPayload.NodeState st = states.get(def.id());
        TechTree.Status status = status(def.id());
        TechTreeData.Branch branch = data.branch(def.branch());
        String tierName = def.tier() - 1 < data.tiers().size() && def.tier() >= 1
            ? data.tiers().get(def.tier() - 1).name() : String.valueOf(def.tier());
        chips = List.of(
            Component.translatableWithFallback("hearthstead.techtree.branch_short." + def.branch(),
                shortBranch(def.branch())),
            Component.translatableWithFallback("hearthstead.techtree.tier." + def.tier(), tierName),
            typeName(def));
        chipColor = branch == null ? Ui2Palette.INK_MUTED : darken(branch.color(), 0.8F);
        rows.add(new PanelRow(null, -3, ItemStack.EMPTY, -1, 15, null));
        if (status == TechTree.Status.PLANNED) {
            rows.add(new PanelRow(null, -4, ItemStack.EMPTY, -1, 15, null));
        }
        if (def.id().equals(journeyNode) && status != TechTree.Status.LEARNED) {
            rows.add(new PanelRow(null, -5, ItemStack.EMPTY, -1, 15, null));
        }
        text(rows, def.offersText(), Ui2Palette.INK, w);
        rows.add(new PanelRow(null, 0, ItemStack.EMPTY, -1, 4, null));
        // Status line
        if (status != TechTree.Status.PLANNED) {
            text(rows, statusLine(def, st, status), statusColor(status), w);
        }
        if (status == TechTree.Status.STUDYING && st != null && st.studyTotal() > 0) {
            rows.add(new PanelRow(null, STUDYING, ItemStack.EMPTY,
                (float) st.studyDone() / st.studyTotal(), 6, null));
        }
        // Cost
        if (status != TechTree.Status.LEARNED && status != TechTree.Status.STUDYING) {
            header(rows, "cost", "Cost", w);
            List<DevelopmentNode.Cost> costs = safeCosts(def);
            if (costs.isEmpty()) {
                text(rows, Component.translatableWithFallback("hearthstead.techtree.free", "Free"),
                    Ui2Palette.INK_SOFT, w);
            }
            for (int i = 0; i < costs.size(); i++) {
                DevelopmentNode.Cost cost = costs.get(i);
                int have = st != null && i < st.have().length ? st.have()[i] : 0;
                boolean ok = have >= cost.count();
                Component line = ok
                    ? Component.literal("\u2714 " + cost.count() + " ").append(cost.displayName())
                    : Component.literal(cost.count() + " ").append(cost.displayName())
                        .append(Component.translatableWithFallback("hearthstead.techtree.have",
                            " (have %s)", Math.min(have, 9999)));
                rows.add(new PanelRow(line.getVisualOrderText(), ok ? Ui2Palette.FOREST : Ui2Palette.DANGER,
                    new ItemStack(cost.item()), -1, 16, null));
            }
            Component spend = journeySpendWarning(def);
            if (spend != null) {
                rows.add(new PanelRow(null, 0, ItemStack.EMPTY, -1, 3, null));
                text(rows, spend, Ui2Palette.DANGER, w);
            }
            if (def.studyDays() > 0) {
                text(rows, Component.translatableWithFallback("hearthstead.techtree.study",
                    "Then %s in-game day(s) of study", def.studyDays()), Ui2Palette.INK_SOFT, w);
            }
        }
        // Gates
        if (st != null && !st.gates().isEmpty() && status != TechTree.Status.LEARNED) {
            header(rows, "gates", "Milestones", w);
            for (TechTreeSnapshotPayload.Gate gate : st.gates()) {
                boolean met = gate.progress() >= gate.target();
                text(rows, gateLine(gate), met ? Ui2Palette.FOREST : Ui2Palette.INK_SOFT, w);
                rows.add(new PanelRow(null, met ? Ui2Palette.FOREST : Ui2Palette.AMBER, ItemStack.EMPTY,
                    Math.min(1.0F, gate.progress() / (float) Math.max(1, gate.target())), 5, null));
            }
        }
        // Requires / pick one
        if (!def.requires().isEmpty()) {
            header(rows, "requires", "Requires", w);
            for (String req : def.requires()) {
                TechNodeDef r = data.node(req);
                boolean ok = status(req) == TechTree.Status.LEARNED;
                Component name = (ok ? Component.literal("\u2714 ") : Component.literal("\u2022 "))
                    .append(r == null ? Component.literal(req) : r.displayName());
                if (r != null && !r.branch().equals(def.branch())) {
                    name = name.copy().append(Component.literal(" (" + shortBranch(r.branch()) + ")")
                        .withColor(Ui2Palette.INK_MUTED));
                }
                rows.add(new PanelRow(name.getVisualOrderText(), ok ? Ui2Palette.FOREST : Ui2Palette.INK,
                    ItemStack.EMPTY, -1, 10, req));
            }
        }
        if (!def.excludes().isEmpty()) {
            header(rows, "choice", "Pick one", w);
            for (String ex : def.excludes()) {
                TechNodeDef other = data.node(ex);
                Component line = Component.translatableWithFallback("hearthstead.techtree.excludes",
                    "Choosing this locks %s forever", other == null ? Component.literal(ex) : other.displayName());
                for (FormattedCharSequence seq : font.split(line, w)) {
                    rows.add(new PanelRow(seq, BLOCKED, ItemStack.EMPTY, -1, 10, ex));
                }
            }
        }
        // What it does
        List<TechEffect> effects = EffectRegistry.get().effects(def.id());
        if (!effects.isEmpty()) {
            header(rows, "unlocks", "In game", w);
            Set<String> seen = new HashSet<>();
            for (TechEffect effect : effects) {
                Component line = effect.describe();
                if (seen.add(line.getString())) {
                    text(rows, Component.literal("• ").append(line), Ui2Palette.INK_SOFT, w);
                }
            }
        }
        if (!def.flavor().isEmpty()) {
            rows.add(new PanelRow(null, 0, ItemStack.EMPTY, -1, 4, null));
            text(rows, def.flavorText().copy().withStyle(s -> s.withItalic(true)), Ui2Palette.INK_MUTED, w);
        }
        panelRows = rows;
        panelScroll = 0;
        // Research button
        researchButton.visible = status != TechTree.Status.LEARNED;
        researchButton.setMessage(status == TechTree.Status.STUDYING
            ? Component.translatableWithFallback("hearthstead.techtree.studying_btn", "Studying\u2026")
            : Component.translatableWithFallback("hearthstead.techtree.research", "Research"));
        com.hearthstead.client.ui2.Ui2Tips.enable(researchButton, status == TechTree.Status.READY,
            Component.translatableWithFallback("hearthstead.techtree.research_tip",
                "Pay the cost from the Banner, your pack and the Warehouse"),
            statusLine(def, st, status));
    }

    /**
     * "This uses the Coin your Journey step needs (Timber Rights)": shown when
     * learning {@code def} would leave fewer Coins than the Journey's next
     * node costs.
     */
    @Nullable
    private Component journeySpendWarning(TechNodeDef def) {
        if (journeyNode.isEmpty() || def.id().equals(journeyNode) || def.coins() <= 0
            || status(journeyNode) == TechTree.Status.LEARNED) {
            return null;
        }
        TechNodeDef journey = data.node(journeyNode);
        if (journey == null || journey.coins() <= 0 || snapshot.coins() - def.coins() >= journey.coins()) {
            return null;
        }
        return Component.translatableWithFallback("hearthstead.techtree.journey_spend",
            "This uses the Coin your Journey step needs (%s)", journey.displayName());
    }

    /** "1 Coin + 8 any log" for the confirm button. */
    private Component costSummary(TechNodeDef def) {
        List<DevelopmentNode.Cost> costs = safeCosts(def);
        if (costs.isEmpty()) {
            return Component.translatableWithFallback("hearthstead.techtree.free", "Free");
        }
        net.minecraft.network.chat.MutableComponent out = Component.empty();
        for (int i = 0; i < costs.size(); i++) {
            if (i > 0) {
                out.append(" + ");
            }
            out.append(costs.get(i).count() + " ").append(costs.get(i).displayName());
        }
        return out;
    }

    private List<DevelopmentNode.Cost> safeCosts(TechNodeDef def) {
        try {
            return TechCosts.costs(def);
        } catch (RuntimeException badData) {
            return List.of();
        }
    }

    private void text(List<PanelRow> rows, Component text, int color, int w) {
        for (FormattedCharSequence seq : font.split(text, w)) {
            rows.add(new PanelRow(seq, color, ItemStack.EMPTY, -1, 10, null));
        }
    }

    private final Map<String, com.hearthstead.client.ui2.Ui2Serif.Text> headings = new HashMap<>();

    /** A serif section heading; the heading text rides in {@code link} (not clickable). */
    private void header(List<PanelRow> rows, String key, String fallback, int w) {
        rows.add(new PanelRow(null, 0, ItemStack.EMPTY, -1, 2, null));
        rows.add(new PanelRow(null, -2, ItemStack.EMPTY, -1, 13,
            Component.translatableWithFallback("hearthstead.techtree.section." + key, fallback).getString()));
    }

    private void renderSide(GuiGraphics g, int mouseX, int mouseY) {
        int x = sideX;
        int y = viewY;
        int h = viewH;
        g.fill(x - 3, y + 4, x - 2, y + h - 4, Ui2Palette.RULE_STRONG);
        g.fill(x, y, x + sideW, y + h, 0x40D8CCB2);
        TechNodeDef def = selected == null ? null : data.node(selected);
        int ty = y + 6;
        if (def != null) {
            drawIcon(g, def, x + 6, ty, 16.0F);
            List<FormattedCharSequence> title = font.split(def.displayName(), sideW - 34);
            for (int i = 0; i < Math.min(2, title.size()); i++) {
                g.drawString(font, title.get(i), x + 26, ty + (title.size() == 1 ? 4 : i * 9),
                    Ui2Palette.INK, false);
            }
            ty += 22;
            g.fill(x + 6, ty - 3, x + sideW - 6, ty - 2, Ui2Palette.RULE);
        }
        int bottom = researchButton != null && researchButton.visible
            ? researchButton.getY() - 4 : y + h - 6;
        g.enableScissor(x + 1, ty, x + sideW - 1, bottom);
        int total = 0;
        for (PanelRow row : panelRows) {
            total += row.height;
        }
        int maxScroll = Math.max(0, total - (bottom - ty));
        panelScroll = Mth.clamp(panelScroll, 0, maxScroll);
        int ry = ty - panelScroll;
        for (PanelRow row : panelRows) {
            if (ry + row.height >= ty && ry <= bottom) {
                if (row.progress >= 0) {
                    g.fill(x + 8, ry + 1, x + sideW - 8, ry + 3, Ui2Palette.TRACK);
                    g.fill(x + 8, ry + 1, x + 8 + Math.round((sideW - 16) * row.progress), ry + 3, row.color);
                } else if (row.color == -3) {
                    int cx = x + 8;
                    for (int i = 0; i < chips.size(); i++) {
                        int cw = font.width(chips.get(i)) + 6;
                        if (cx + cw > x + sideW - 6) {
                            break;
                        }
                        cx += com.hearthstead.client.ui2.Ui2Surface.badge(g, font, chips.get(i), cx, ry + 1,
                            i == 0 ? chipColor : Ui2Palette.INK_MUTED) + 3;
                    }
                } else if (row.color == -5) {
                    com.hearthstead.client.ui2.Ui2Surface.badge(g, font, Component.translatableWithFallback(
                        "hearthstead.techtree.journey_badge", "Journey: next step"), x + 8, ry + 1, Ui2Palette.GOLD);
                } else if (row.color == -4) {
                    com.hearthstead.client.ui2.Ui2Surface.badge(g, font, Component.translatableWithFallback(
                        "hearthstead.techtree.planned_tag", "PLANNED: not in the game yet"), x + 8, ry + 1,
                        Ui2Palette.AMBER);
                } else if (row.color == -2 && row.link != null) {
                    com.hearthstead.client.ui2.Ui2Frame.heading(g, font,
                        headings.computeIfAbsent(row.link, k -> new com.hearthstead.client.ui2.Ui2Serif.Text(
                            com.hearthstead.client.ui2.Ui2Serif.Size.HEADING)),
                        row.link, x + 8, ry, sideW - 16);
                } else if (row.text != null) {
                    int tx = x + 8;
                    if (!row.icon.isEmpty()) {
                        g.renderItem(row.icon, tx, ry);
                        tx += 18;
                    }
                    boolean hover = row.link != null && row.color != -2 && mouseX >= x && mouseX < x + sideW
                        && mouseY >= ry && mouseY < ry + row.height;
                    g.drawString(font, row.text, tx, ry + (row.icon.isEmpty() ? 1 : 4),
                        hover ? Ui2Palette.GOLD : row.color, false);
                }
            }
            ry += row.height;
        }
        g.disableScissor();
        if (panelScroll < maxScroll) {
            Component more = Component.translatableWithFallback("hearthstead.techtree.more", "more \u25be");
            int mw = font.width(more) + 6;
            g.fill(x + sideW - 10 - mw, bottom - 10, x + sideW - 8, bottom, Ui2Palette.PAPER_DEEP);
            g.drawString(font, more, x + sideW - 7 - mw, bottom - 9, Ui2Palette.INK_MUTED, false);
        }
        if (maxScroll > 0) {
            int trackH = bottom - ty;
            int thumb = Math.max(10, trackH * trackH / (trackH + maxScroll));
            int thumbY = ty + Math.round((trackH - thumb) * (panelScroll / (float) maxScroll));
            g.fill(x + sideW - 4, thumbY, x + sideW - 2, thumbY + thumb, Ui2Palette.RULE_STRONG);
        }
    }

    @Nullable
    private String panelLinkAt(double mouseX, double mouseY) {
        if (mouseX < sideX || mouseX >= sideX + sideW || selected == null) {
            return null;
        }
        int ry = viewY + 6 + 22 - panelScroll;
        for (PanelRow row : panelRows) {
            if (row.link != null && row.color != -2 && mouseY >= ry && mouseY < ry + row.height) {
                return row.link;
            }
            ry += row.height;
        }
        return null;
    }

    private void renderHoverTip(GuiGraphics g, String id, int mouseX, int mouseY) {
        TechNodeDef def = data.node(id);
        if (def == null) {
            return;
        }
        List<FormattedCharSequence> lines = new ArrayList<>();
        lines.add(def.displayName().getVisualOrderText());
        TechTree.Status status = status(id);
        lines.addAll(font.split(statusLine(def, states.get(id), status), 180));
        for (String r : def.requires()) {
            TechNodeDef rd = data.node(r);
            if (rd != null && !rd.branch().equals(def.branch()) && !"crown".equals(rd.branch())) {
                lines.add(Component.translatableWithFallback("hearthstead.techtree.needs", "Needs: %s (%s)",
                    rd.displayName(), shortBranch(rd.branch())).withColor(
                        status(r) == TechTree.Status.LEARNED ? Ui2Palette.FOREST_HIGHLIGHT : 0xFFE0B070)
                    .getVisualOrderText());
            }
        }
        g.renderTooltip(font, lines, mouseX, mouseY);
    }

    // ------------------------------------------------------------ text/data

    private Component statusLine(TechNodeDef def, @Nullable TechTreeSnapshotPayload.NodeState st,
                                 TechTree.Status status) {
        String arg = st == null ? "" : st.reasonArg();
        TechNodeDef other = data.node(arg);
        Component argName = other != null ? other.displayName() : Component.literal(arg);
        return switch (status) {
            case LEARNED -> Component.translatableWithFallback("hearthstead.techtree.status.learned", "Learned");
            case READY -> Component.translatableWithFallback("hearthstead.techtree.status.ready",
                "Ready to research");
            case STUDYING -> {
                long left = st == null ? 0 : Math.max(0, st.studyTotal() - st.studyDone());
                yield Component.translatableWithFallback("hearthstead.techtree.status.studying",
                    "Studying: %s in-game days left", String.format(java.util.Locale.ROOT, "%.1f",
                        left / (double) TechTree.DAY_TICKS));
            }
            case PLANNED -> Component.translatableWithFallback("hearthstead.techtree.status.planned",
                "Planned: not in the game yet");
            case BLOCKED -> Component.translatableWithFallback("hearthstead.techtree.status.blocked",
                "Closed: you chose %s", argName);
            case LOCKED -> Component.translatableWithFallback("hearthstead.techtree.status.locked",
                "Needs %s first", argName);
            case QUARANTINED -> Component.translatableWithFallback(
                "hearthstead.techtree.status.quarantined", "This settlement's records need repair");
            case AVAILABLE -> st == null ? Component.empty() : resultText(st.reason(), arg);
        };
    }

    private Component resultText(String key, String arg) {
        TechNodeDef other = data.node(arg);
        Component name = other != null ? other.displayName() : Component.literal(arg);
        String fallback = switch (key.substring(key.lastIndexOf('.') + 1)) {
            case "learned" -> "Learned: %s";
            case "study_started" -> "Study begun: %s";
            case "stale" -> "The tree changed a moment ago. Try again.";
            case "read_only" -> "You can't spend the village's stores right now.";
            case "quarantined" -> "This settlement's records need repair.";
            case "already" -> "Already learned.";
            case "studying" -> "Already being studied.";
            case "planned" -> "Planned: not in the game yet.";
            case "excluded" -> "Closed: the other choice was taken.";
            case "locked" -> "Its requirements are not learned yet.";
            case "gate" -> "Milestones not reached yet.";
            case "materials" -> "Not enough Coins or goods (Banner, your pack and Warehouse).";
            case "auto" -> "Stamps itself when its milestone is met.";
            case "disabled" -> "The new tech tree is switched off on this server.";
            default -> "Can't research this now.";
        };
        return Component.translatableWithFallback(key, fallback, name);
    }

    private Component gateLine(TechTreeSnapshotPayload.Gate gate) {
        int p = gate.progress();
        int t = gate.target();
        return switch (gate.kind()) {
            case "objective" -> {
                com.hearthstead.settlement.development.DevelopmentObjective objective = null;
                for (var o : com.hearthstead.settlement.development.DevelopmentObjective.values()) {
                    if (o.id().equals(gate.detail())) {
                        objective = o;
                    }
                }
                yield objective == null ? Component.literal(gate.detail() + " " + p + "/" + t)
                    : objective.progressText(p, t);
            }
            case "settlers" -> Component.translatableWithFallback("hearthstead.techtree.gate.settlers",
                "Settlers: %s/%s", p, t);
            case "raids_won" -> Component.translatableWithFallback("hearthstead.techtree.gate.raids_won",
                "Raids held: %s/%s", p, t);
            case "first_raid" -> Component.translatableWithFallback("hearthstead.techtree.gate.first_raid",
                "Survive your first raid");
            case "branch_nodes" -> {
                String[] parts = gate.detail().split(":");
                yield Component.translatableWithFallback("hearthstead.techtree.gate.branch_nodes",
                    "Branches with %s Village nodes learned: %s/%s",
                    parts.length > 1 ? parts[1] : "?", p, t);
            }
            case "owns_any" -> {
                List<String> names = new ArrayList<>();
                for (String id : gate.detail().split(",")) {
                    TechNodeDef d = data.node(id);
                    names.add(d == null ? id : d.displayName().getString());
                }
                yield Component.translatableWithFallback("hearthstead.techtree.gate.owns_any",
                    "Own one of: %s", String.join(" or ", names));
            }
            default -> Component.literal(gate.kind() + " " + p + "/" + t);
        };
    }

    private Component typeName(TechNodeDef def) {
        String fallback = switch (def.type()) {
            case "capstone" -> "Capstone";
            case "choice" -> "Pick one";
            case "bonus" -> "Bonus";
            default -> "Unlock";
        };
        return Component.translatableWithFallback("hearthstead.techtree.type." + def.type(), fallback);
    }

    private TechTree.Status status(String id) {
        TechTreeSnapshotPayload.NodeState st = states.get(id);
        return st == null ? TechTree.Status.PLANNED : TechTree.Status.byOrdinal(st.status());
    }

    private final Map<String, ItemStack> icons = new HashMap<>();

    private ItemStack icon(TechNodeDef def) {
        return icons.computeIfAbsent(def.id(), ignored -> computeIcon(def));
    }

    private static ItemStack computeIcon(TechNodeDef def) {
        String mapped = TechTreeData.get().iconItem(def.id());
        if (mapped != null) {
            var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(mapped));
            if (item.isPresent() && item.get() != Items.AIR) {
                return new ItemStack(item.get());
            }
        }
        for (TechEffect effect : EffectRegistry.get().effects(def.id())) {
            if (effect instanceof TechEffect.UnlockProfession p) {
                ItemStack stack = JobEmblemItem.stackFor(p.profession());
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
        }
        if (def.legacyNode()) {
            DevelopmentNode node = DevelopmentNode.byId(def.legacyId());
            if (node != null) {
                for (Profession profession : node.professions()) {
                    ItemStack stack = JobEmblemItem.stackFor(profession);
                    if (!stack.isEmpty()) {
                        return stack;
                    }
                }
            }
        }
        for (TechNodeDef.GoodsLine line : def.goods()) {
            if (!line.isTag()) {
                var item = BuiltInRegistries.ITEM.getOptional(ResourceLocation.parse(line.item()));
                if (item.isPresent() && item.get() != Items.AIR) {
                    return new ItemStack(item.get());
                }
            }
        }
        return new ItemStack(switch (def.branch()) {
            case "watch" -> Items.IRON_SWORD;
            case "logistics" -> Items.CHEST;
            case "commons" -> Items.BREAD;
            case "craft" -> Items.IRON_PICKAXE;
            default -> Items.WHITE_BANNER;
        });
    }

    private int branchColor(TechNodeDef def) {
        TechTreeData.Branch branch = data.branch(def.branch());
        return branch == null ? Ui2Palette.GOLD : branch.color();
    }

    private int ringColor(TechTree.Status status) {
        return switch (status) {
            case LEARNED -> GOLD;
            case READY -> READY;
            case AVAILABLE -> AVAILABLE;
            case BLOCKED -> BLOCKED;
            case PLANNED -> PLANNED;
            case STUDYING -> STUDYING;
            default -> LOCKED;
        };
    }

    private int statusColor(TechTree.Status status) {
        return switch (status) {
            case LEARNED -> Ui2Palette.GOLD;
            case READY -> Ui2Palette.FOREST;
            case AVAILABLE, STUDYING -> Ui2Palette.AMBER;
            case BLOCKED, QUARANTINED -> Ui2Palette.DANGER;
            default -> Ui2Palette.INK_MUTED;
        };
    }

    private int fillColor(TechNodeDef def, TechTree.Status status) {
        return switch (status) {
            case LEARNED -> Ui2Palette.GOLD_SOFT;
            case READY, AVAILABLE, STUDYING -> Ui2Palette.FRAME_INNER;
            case BLOCKED -> Ui2Palette.PAPER_DEEP;
            default -> LOCKED_FILL;
        };
    }

    private void updateHover(int mouseX, int mouseY) {
        String found = null;
        if (overView(mouseX, mouseY) && !dragging && !overControls(mouseX, mouseY)) {
            float best = Float.MAX_VALUE;
            for (int i = 0; i < hitTargets.size(); i++) {
                float[] t = hitTargets.get(i);
                float dx = t[0] - mouseX;
                float dy = t[1] - mouseY;
                float dd = dx * dx + dy * dy;
                if (dd <= t[2] * t[2] && dd < best) {
                    best = dd;
                    found = hitIds.get(i);
                }
            }
        }
        if (found == null ? hovered != null : !found.equals(hovered)) {
            hovered = found;
            if (found == null) {
                hoverPath = Set.of();
            } else {
                Set<String> path = new HashSet<>(data.ancestors(found));
                path.add(found);
                hoverPath = path;
            }
        }
    }

    private boolean overControls(double mx, double my) {
        for (int[] r : chipRects) {
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                return true;
            }
        }
        for (int[] r : ladderRects) {
            if (mx >= r[0] && mx < r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        for (int i = 0; i < chipRects.size(); i++) {
            int[] r = chipRects.get(i);
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                applyFilter(chipKeys.get(i));
                HsUi.playConfirmSound();
                return true;
            }
        }
        for (int i = 0; i < ladderRects.size(); i++) {
            int[] r = ladderRects.get(i);
            if (mouseX >= r[0] && mouseX < r[0] + r[2] && mouseY >= r[1] && mouseY < r[1] + r[3]) {
                zoomToRank(i + 1);
                HsUi.playConfirmSound();
                return true;
            }
        }
        String link = panelLinkAt(mouseX, mouseY);
        if (link != null) {
            select(link, true);
            return true;
        }
        if (button == 0 && overView(mouseX, mouseY)) {
            dragging = true;
            dragDistance = 0;
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragging && button == 0) {
            dragDistance += Math.abs(dx) + Math.abs(dy);
            camX -= (float) dx / zoom;
            camY -= (float) dy / zoom;
            targetCamX = camX;
            targetCamY = camY;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == 0) {
            dragging = false;
            if (dragDistance < 4.0D) {
                updateHover((int) mouseX, (int) mouseY);
                if (hovered != null) {
                    select(hovered, false);
                }
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= sideX && mouseX < sideX + sideW && mouseY >= viewY && mouseY < viewY + viewH) {
            panelScroll -= (int) Math.round(scrollY * 18);
            return true;
        }
        if (overView(mouseX, mouseY) && scrollY != 0) {
            float before = targetZoom;
            float next = Mth.clamp(before * (float) Math.pow(1.18D, scrollY), MIN_ZOOM, MAX_ZOOM);
            float cx = viewX + viewW / 2.0F;
            float cy = viewY + viewH / 2.0F;
            float wxp = targetCamX + (float) (mouseX - cx) / before;
            float wyp = targetCamY + (float) (mouseY - cy) / before;
            targetCamX = wxp - (float) (mouseX - cx) / next;
            targetCamY = wyp - (float) (mouseY - cy) / next;
            targetZoom = next;
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        float step = 90.0F / Math.max(0.2F, targetZoom);
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> targetCamX -= step;
            case GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> targetCamX += step;
            case GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> targetCamY -= step;
            case GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> targetCamY += step;
            case GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> targetZoom = Mth.clamp(targetZoom * 1.2F, MIN_ZOOM, MAX_ZOOM);
            case GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> targetZoom = Mth.clamp(targetZoom / 1.2F, MIN_ZOOM, MAX_ZOOM);
            case GLFW.GLFW_KEY_SLASH -> legendOpen = !legendOpen;
            case GLFW.GLFW_KEY_HOME -> resetView();
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                if (researchButton != null && researchButton.active && researchButton.visible) {
                    research();
                }
            }
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        return true;
    }

    private void select(String id, boolean glide) {
        if (!id.equals(armedId)) {
            armedId = null;
        }
        selected = id;
        rebuildPanel();
        TechNodeDef def = data.node(id);
        if (def == null || layout.pos(id) == null) {
            return;
        }
        float x = sx(wx(id));
        float y = sy(wy(id));
        boolean offscreen = x < viewX + 30 || x > viewX + viewW - 30 || y < viewY + 30 || y > viewY + viewH - 30;
        if (glide || offscreen) {
            targetCamX = wx(id);
            targetCamY = wy(id);
        }
    }

    private void research() {
        if (selected == null || status(selected) != TechTree.Status.READY) {
            return;
        }
        long now = Util.getMillis();
        boolean confirmed = selected.equals(armedId) && now < armedUntil;
        if (!confirmed && !hasShiftDown()) {
            // Survival QA U8: the first click only arms; the second (within 3 s) spends.
            armedId = selected;
            armedUntil = now + 3000L;
            TechNodeDef def = data.node(selected);
            Component label = Component.translatableWithFallback("hearthstead.techtree.confirm", "Confirm \u2013 %s",
                def == null ? Component.empty() : costSummary(def));
            researchButton.setMessage(Component.literal(font.plainSubstrByWidth(label.getString(),
                researchButton.getWidth() - 12)));
            HsUi.playConfirmSound();
            return;
        }
        armedId = null;
        PacketDistributor.sendToServer(new TechTreeActionPayload(snapshot.hearthPos(),
            snapshot.settlementId(), TechTreeActionPayload.LEARN, selected, snapshot.revision()));
        researchButton.active = false;
    }

    private void showToast(Component text, int color) {
        toast = text;
        toastColor = color;
        toastUntil = Util.getMillis() + 3500L;
    }

    @Override
    public void removed() {
        super.removed();
        if (snapshot != null) {
            PacketDistributor.sendToServer(new TechTreeActionPayload(snapshot.hearthPos(),
                snapshot.settlementId(), TechTreeActionPayload.CLOSE, "", snapshot.revision()));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean overView(double x, double y) {
        return x >= viewX && x < viewX + viewW && y >= viewY && y < viewY + viewH;
    }

    // -------------------------------------------------------- draw helpers

    private float sx(float worldX) {
        return viewX + viewW / 2.0F + (worldX - camX) * zoom;
    }

    private float sy(float worldY) {
        return viewY + viewH / 2.0F + (worldY - camY) * zoom;
    }

    /** Filled circle by scanlines (sub-pixel centre, pixel-art edge). */
    private static void disc(GuiGraphics g, float cx, float cy, float r, int color) {
        if (r <= 0.5F) {
            return;
        }
        int top = (int) Math.floor(cy - r);
        int bottom = (int) Math.ceil(cy + r);
        for (int y = top; y < bottom; y++) {
            float dy = y + 0.5F - cy;
            float span = r * r - dy * dy;
            if (span <= 0) {
                continue;
            }
            float half = (float) Math.sqrt(span);
            int x0 = Math.round(cx - half);
            int x1 = Math.round(cx + half);
            if (x1 > x0) {
                g.fill(x0, y, x1, y + 1, color);
            }
        }
    }

    /** Diagonal hatching inside a circle (the Planned look). */
    private static void hatch(GuiGraphics g, float cx, float cy, float r, int color) {
        int top = (int) Math.floor(cy - r);
        int bottom = (int) Math.ceil(cy + r);
        for (int y = top; y < bottom; y++) {
            float dy = y + 0.5F - cy;
            float span = r * r - dy * dy;
            if (span <= 0) {
                continue;
            }
            float half = (float) Math.sqrt(span);
            int x0 = Math.round(cx - half);
            int x1 = Math.round(cx + half);
            for (int x = x0; x < x1; x++) {
                if (((x + y) & 3) == 0) {
                    g.fill(x, y, x + 1, y + 1, color);
                }
            }
        }
    }

    private static void dottedCircle(GuiGraphics g, float cx, float cy, float r, int color) {
        if (r < 2) {
            return;
        }
        int dots = Mth.clamp(Math.round(r * 0.9F), 12, 720);
        for (int i = 0; i < dots; i += 2) {
            double a = i * Math.PI * 2.0D / dots;
            int x = Math.round(cx + (float) Math.cos(a) * r);
            int y = Math.round(cy + (float) Math.sin(a) * r);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }

    private static void arc(GuiGraphics g, float cx, float cy, float r, float frac, int color) {
        int steps = Math.max(12, Math.round(r * 4));
        int lit = Math.round(steps * Mth.clamp(frac, 0.0F, 1.0F));
        for (int i = 0; i < steps; i++) {
            double a = -Math.PI / 2 + i * Math.PI * 2.0D / steps;
            int x = Math.round(cx + (float) Math.cos(a) * r);
            int y = Math.round(cy + (float) Math.sin(a) * r);
            g.fill(x - 1, y - 1, x + 1, y + 1, i < lit ? color : 0x50B07D2A);
        }
    }

    /** A straight line of the given thickness through a rotated fill. */
    private static void line(GuiGraphics g, float x1, float y1, float x2, float y2, float thick,
                             int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        if (len < 0.5F) {
            return;
        }
        g.pose().pushPose();
        g.pose().translate(x1, y1, 0.0F);
        g.pose().mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        g.pose().scale(len / 64.0F, thick / 4.0F, 1.0F);
        g.fill(0, -2, 64, 2, color);
        g.pose().popPose();
    }

    private static void dashed(GuiGraphics g, float x1, float y1, float x2, float y2, int color,
                               float thick) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        int dashes = Math.max(1, Math.round(len / 7.0F));
        for (int i = 0; i < dashes; i += 2) {
            float a = i / (float) dashes;
            float b = Math.min(1.0F, (i + 1) / (float) dashes);
            line(g, x1 + dx * a, y1 + dy * a, x1 + dx * b, y1 + dy * b, thick, color);
        }
    }

    private static int withAlpha(int color, int alpha) {
        return (Mth.clamp(alpha, 0, 255) << 24) | (color & 0xFFFFFF);
    }

    private static int darken(int color, float f) {
        int r = Math.round(((color >> 16) & 0xFF) * f);
        int g = Math.round(((color >> 8) & 0xFF) * f);
        int b = Math.round((color & 0xFF) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int lighten(int color, float f) {
        int r = (color >> 16) & 0xFF;
        int g = (color >> 8) & 0xFF;
        int b = color & 0xFF;
        r = Math.round(r + (255 - r) * f);
        g = Math.round(g + (255 - g) * f);
        b = Math.round(b + (255 - b) * f);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** QA seam: selected node and camera. */
    public String qaUiState() {
        return "techtree selected=" + selected + " zoom=" + zoom + " cam=" + camX + "," + camY
            + " revision=" + snapshot.revision();
    }
}
