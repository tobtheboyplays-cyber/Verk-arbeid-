package com.hearthstead.client.builder;

import com.hearthstead.building.BuildingType;
import com.hearthstead.client.ui2.BannerSheetLayout;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.item.BuildingPlans;
import com.hearthstead.network.BuilderPayloads;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Right-click with a Building Plan: the Builder's styles for that building
 * as up to five cards in one row (owner: no tabs, no scrolling). Each card
 * shows the 3D preview, the style's name, its size and the main materials,
 * or why it is locked. Clicking a card anchors its ghost in the world
 * ({@link PlanPlacement}) and opens the placement panel.
 */
public final class PlanStyleScreen extends Screen {

    static final int MAX_CARDS = 5;
    private static final int GAP = 6;

    private final String typeId;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private Ui2FrameLayout frame;
    private List<BuilderPayloads.CatalogEntry> entries = List.of();
    private int seenVersion = -1;

    public PlanStyleScreen(String typeId) {
        super(Component.translatable("hearthstead.building_plan.pick.title"));
        this.typeId = typeId;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        frame = Ui2FrameLayout.centred(width, height, 464, 256, true);
        addRenderableWidget(Ui2Frame.closeKey(frame, this::onClose));
        BannerSheetLayout.Rect f = frame.footer();
        addRenderableWidget(Ui2Button.secondary(f.x(), f.y(), 70, 20,
            Component.translatable("hearthstead.building_plan.cancel"), this::onClose));
        refreshEntries();
    }

    /** The building's style presets from the Builder's catalog (at most five, smallest first). */
    static List<BuilderPayloads.CatalogEntry> stylesFor(BuilderPayloads.Catalog catalog, String typeId) {
        if (catalog == null) {
            return List.of();
        }
        List<BuilderPayloads.CatalogEntry> out = new ArrayList<>();
        for (BuilderPayloads.CatalogEntry e : catalog.entries()) {
            if ("building".equals(e.kind()) && typeId.equals(e.group())) {
                out.add(e);
            }
        }
        out.sort(Comparator.comparingInt(BuilderPayloads.CatalogEntry::blocks)
            .thenComparing(BuilderPayloads.CatalogEntry::id));
        return out.size() > MAX_CARDS ? List.copyOf(out.subList(0, MAX_CARDS)) : out;
    }

    private void refreshEntries() {
        if (seenVersion != BuilderClientState.catalogVersion()) {
            seenVersion = BuilderClientState.catalogVersion();
            entries = stylesFor(BuilderClientState.catalog(), typeId);
        }
    }

    @Override
    public void tick() {
        refreshEntries();
    }

    private BannerSheetLayout.Rect cardsArea() {
        BannerSheetLayout.Rect c = frame.content();
        return new BannerSheetLayout.Rect(c.x(), c.y(), c.width(), Math.max(40, frame.footer().y() - 6 - c.y()));
    }

    private int cardWidth() {
        return (cardsArea().width() - GAP * (MAX_CARDS - 1)) / MAX_CARDS;
    }

    private int cardX(int i) {
        BannerSheetLayout.Rect a = cardsArea();
        int cw = cardWidth();
        int n = Math.max(1, entries.size());
        int used = n * cw + (n - 1) * GAP;
        return a.x() + (a.width() - used) / 2 + i * (cw + GAP);
    }

    private int cardAt(double mx, double my) {
        BannerSheetLayout.Rect a = cardsArea();
        if (my < a.y() || my >= a.bottom()) {
            return -1;
        }
        for (int i = 0; i < entries.size(); i++) {
            int x = cardX(i);
            if (mx >= x && mx < x + cardWidth()) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (super.mouseClicked(mx, my, button)) {
            return true;
        }
        int i = cardAt(mx, my);
        if (button == 0 && i >= 0) {
            pick(entries.get(i));
            return true;
        }
        return false;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_5 && key - GLFW.GLFW_KEY_1 < entries.size()) {
            pick(entries.get(key - GLFW.GLFW_KEY_1));
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    private void pick(BuilderPayloads.CatalogEntry e) {
        if (!e.lockKey().isEmpty()) {
            return;
        }
        PlanPlacement.begin(typeId, e.id(), styleName(e));
    }

    static String styleName(BuilderPayloads.CatalogEntry e) {
        if (e.preset() != null && !e.preset().isBlank()) {
            return e.preset();
        }
        return e.name() == null || e.name().isBlank() ? e.id() : e.name();
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ui2Frame.scrim(g, width, height);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderBackground(g, mouseX, mouseY, partialTick);
        Ui2Frame.draw(g, frame);
        BuildingType type = BuildingPlans.byId(typeId);
        Component name = type == null ? Component.literal(typeId)
            : Component.translatable("item.hearthstead.building_plan.named", type.displayName());
        Ui2Frame.title(g, font, frame, titleText, name.getString(),
            Component.translatable("hearthstead.building_plan.pick.subtitle"));
        BuilderPayloads.Catalog catalog = BuilderClientState.catalog();
        BannerSheetLayout.Rect a = cardsArea();
        if (catalog == null || entries.isEmpty()) {
            Component msg = Component.translatable(catalog == null ? "hearthstead.building_plan.loading"
                : !catalog.enabled() ? "hearthstead.building_plan.disabled" : "hearthstead.building_plan.no_styles");
            g.drawCenteredString(font, msg, a.x() + a.width() / 2, a.y() + a.height() / 2 - 4, Ui2Palette.INK_MUTED);
        } else {
            int hovered = cardAt(mouseX, mouseY);
            for (int i = 0; i < entries.size(); i++) {
                drawCard(g, entries.get(i), i, cardX(i), a.y(), cardWidth(), a.height(), i == hovered);
            }
        }
        // Footer note, right of Cancel.
        BannerSheetLayout.Rect f = frame.footer();
        Component note = Component.translatable(catalog != null && !catalog.hasBuilder()
            ? "hearthstead.building_plan.pick.no_builder" : "hearthstead.building_plan.pick.hint");
        List<FormattedCharSequence> lines = font.split(note, f.width() - 80);
        int ny = f.y() + (f.height() - lines.size() * 9) / 2 + 1;
        for (FormattedCharSequence line : lines) {
            g.drawString(font, line, f.right() - font.width(line), ny, Ui2Palette.INK_MUTED, false);
            ny += 9;
        }
        for (var child : renderables) {
            child.render(g, mouseX, mouseY, partialTick);
        }
        int hovered = cardAt(mouseX, mouseY);
        if (hovered >= 0 && hovered < entries.size()) {
            // Anchored outside the card row (below the picker when it fits) so the
            // material list never covers the neighbouring cards.
            List<FormattedCharSequence> tip = new ArrayList<>();
            for (Component line : tooltip(entries.get(hovered))) {
                tip.add(line.getVisualOrderText());
            }
            int cx = cardX(hovered);
            int cw = cardWidth();
            g.renderTooltip(font, tip, (sw, sh, mx, my, tw, th) -> {
                int[] p = PlanTooltipPlacement.place(sw, sh, frame.x(), frame.y(), frame.width(), frame.height(),
                    cx, cw, tw, th);
                return new org.joml.Vector2i(p[0], p[1]);
            }, mouseX, mouseY);
        }
    }

    private void drawCard(GuiGraphics g, BuilderPayloads.CatalogEntry e, int index, int x, int y, int w, int h,
                          boolean hover) {
        boolean locked = !e.lockKey().isEmpty();
        g.fill(x, y, x + w, y + h, hover && !locked ? Ui2Palette.GOLD : Ui2Palette.RULE_STRONG);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, Ui2Palette.PAPER_DEEP);
        if (hover && !locked) {
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, Ui2Palette.ROW_HOVER);
        }
        int pad = 4;
        int inner = w - pad * 2;
        int thumbH = Math.max(24, Math.min(inner, h - 78));
        g.fill(x + pad, y + pad, x + pad + inner, y + pad + thumbH, Ui2Palette.INSET);
        BuilderPayloads.Preview preview = PlanPreviews.get(e.id());
        if (preview != null) {
            PlanThumbs.render(g, preview, x + pad, y + pad, inner, thumbH, hover);
        } else {
            g.drawCenteredString(font, "…", x + w / 2, y + pad + thumbH / 2 - 4, Ui2Palette.INK_MUTED);
        }
        // Number key badge.
        g.drawString(font, String.valueOf(index + 1), x + pad + 2, y + pad + 2, Ui2Palette.INK_MUTED, false);
        int ty = y + pad + thumbH + 4;
        for (String line : twoLines(styleName(e), inner)) {
            g.drawString(font, line, x + pad, ty, locked ? Ui2Palette.INK_DISABLED : Ui2Palette.INK, false);
            ty += 9;
        }
        ty += 1;
        g.drawString(font, font.plainSubstrByWidth(e.sizeX() + "×" + e.sizeZ() + ", " + e.sizeY() + " high",
            inner), x + pad, ty, Ui2Palette.INK_MUTED, false);
        ty += 11;
        if (locked) {
            Ui2Surface.lockGlyph(g, x + pad, ty + 1, Ui2Palette.DANGER);
            List<FormattedCharSequence> why = font.split(Component.translatable(e.lockKey()), inner - 8);
            for (int i = 0; i < Math.min(3, why.size()) && ty + 9 <= y + h - 2; i++) {
                g.drawString(font, why.get(i), x + pad + 8, ty, Ui2Palette.DANGER, false);
                ty += 9;
            }
            return;
        }
        // Main materials: two columns of icon + count.
        int col = inner / 2;
        int shown = 0;
        for (BuilderPayloads.ItemLine line : e.materials()) {
            int row = shown / 2;
            int cy = ty + row * 13;
            if (cy + 12 > y + h - 2) {
                break;
            }
            int cx = x + pad + (shown % 2) * col;
            Ui2Surface.icon(g, new ItemStack(line.item()), cx, cy, 12);
            g.drawString(font, font.plainSubstrByWidth(String.valueOf(line.count()), col - 15), cx + 14, cy + 2,
                Ui2Palette.INK, false);
            shown++;
        }
    }

    /** The name in at most two lines; a longer one ends in an ellipsis (full name in the tooltip). */
    private List<String> twoLines(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (var part : font.getSplitter().splitLines(text, width, net.minecraft.network.chat.Style.EMPTY)) {
            lines.add(part.getString());
        }
        if (lines.size() <= 2) {
            return lines;
        }
        String rest = String.join(" ", lines.subList(1, lines.size())).trim();
        String cut = font.plainSubstrByWidth(rest, width - font.width("…")).trim();
        return List.of(lines.get(0), cut + "…");
    }

    private List<Component> tooltip(BuilderPayloads.CatalogEntry e) {
        List<Component> out = new ArrayList<>();
        out.add(Component.literal(e.name() == null || e.name().isBlank() ? styleName(e) : e.name()));
        out.add(Component.translatable("hearthstead.building_plan.pick.size", e.sizeX(), e.sizeZ(), e.sizeY(),
            e.blocks()).withStyle(net.minecraft.ChatFormatting.GRAY));
        if (!e.lockKey().isEmpty()) {
            out.add(Component.translatable(e.lockKey()).withStyle(net.minecraft.ChatFormatting.RED));
            return out;
        }
        int n = 0;
        for (BuilderPayloads.ItemLine line : e.materials()) {
            if (n++ >= 10) {
                out.add(Component.translatable("hearthstead.building_plan.pick.more", e.materials().size() - 10)
                    .withStyle(net.minecraft.ChatFormatting.DARK_GRAY));
                break;
            }
            out.add(Component.literal(line.count() + " × ").append(line.item().getDescription())
                .withStyle(net.minecraft.ChatFormatting.GRAY));
        }
        out.add(Component.translatable("hearthstead.building_plan.pick.click").withStyle(net.minecraft.ChatFormatting.GOLD));
        return out;
    }
}
