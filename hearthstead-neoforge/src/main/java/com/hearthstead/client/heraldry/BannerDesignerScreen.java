package com.hearthstead.client.heraldry;

import com.hearthstead.client.render.SettlerRenderer;
import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.client.ui2.Ui2Tabs;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.heraldry.BannerDesignPayloads;
import com.hearthstead.heraldry.BannerShape;
import com.hearthstead.heraldry.HeraldryCatalog;
import com.hearthstead.heraldry.VillageDesign;
import com.hearthstead.registry.ModEntities;
import com.hearthstead.settlement.gear.GearGate;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The Banner designer: field colour, cloth shape, presets and up to six
 * pattern layers, with a live preview of the banner and of a guard wearing
 * the colours. A fixed, compact window (304 x 200 GUI px, like the Banner's
 * other dialogs) centred on screen; it never grows with the window.
 *
 * <p>Nothing here changes the world. Only "Raise banner" sends the design,
 * and the server validates and saves it. Cancel (or Esc) keeps what flies.
 */
public final class BannerDesignerScreen extends Screen {
    public static final int WIDTH = 304;
    public static final int HEIGHT = 220;
    /** The kingdom-name row above the stage and tabs. */
    static final int NAME_ROW = 20;
    static final int STAGE_W = 96;
    static final int CELL_W = 12;
    static final int CELL_H = 24;
    static final int GRID_COLS = 12;
    static final int GRID_ROWS_VISIBLE = 2;

    private enum Tab { PRESETS, CLOTH, LAYERS }

    private final BlockPos pos;
    private final boolean placed;
    private VillageDesign saved;
    private VillageDesign draft;
    private Tab tab = Tab.PRESETS;
    private int selectedLayer;
    private int gridScroll;
    private Component notice;
    private long noticeUntil;
    private boolean sent;
    private String savedName;
    private String nameDraft;
    @Nullable
    private net.minecraft.client.gui.components.EditBox nameBox;
    @Nullable
    private Ui2Button confirmButton;

    private Ui2FrameLayout frame;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private final Ui2Tabs tabs = new Ui2Tabs();
    @Nullable
    private SettlerEntity dummyGuard;

    public BannerDesignerScreen(BlockPos pos, VillageDesign design, boolean placed, String name) {
        super(Component.translatable(placed ? "hearthstead.heraldry.title.placed" : "hearthstead.heraldry.title"));
        this.pos = pos;
        this.placed = placed;
        this.saved = design;
        this.savedName = name == null ? "" : name;
        this.nameDraft = this.savedName;
        this.draft = trimmed(design);
        this.selectedLayer = draft.layers().isEmpty() ? -1 : draft.layers().size() - 1;
    }

    public BlockPos pos() {
        return pos;
    }

    /** Another player raised new colours on this Banner: show them and say who. */
    public void refresh(VillageDesign design, String changedBy, String name) {
        saved = design;
        draft = trimmed(design);
        if (name != null && !name.isEmpty()) {
            savedName = name;
            nameDraft = name;
        }
        selectedLayer = Math.min(selectedLayer, draft.layers().size() - 1);
        notice = Component.translatable("hearthstead.heraldry.changed_by", changedBy);
        noticeUntil = Util.getMillis() + 8000L;
        rebuildWidgets();
    }

    private static VillageDesign trimmed(VillageDesign design) {
        return design.layers().size() <= VillageDesign.MAX_LAYERS ? design
            : design.withLayers(design.layers().subList(0, VillageDesign.MAX_LAYERS));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ---------------------------------------------------------- layout ---

    public static Ui2FrameLayout layoutFor(int viewportW, int viewportH) {
        return Ui2FrameLayout.centred(viewportW, viewportH, WIDTH, HEIGHT, true);
    }

    private Rect stage() {
        Rect c = frame.content();
        return new Rect(c.x(), c.y() + NAME_ROW, STAGE_W,
            c.height() - NAME_ROW - Ui2FrameLayout.BUTTON_H - Ui2FrameLayout.M);
    }

    private Rect panel() {
        Rect c = frame.content();
        int x = c.x() + STAGE_W + Ui2FrameLayout.M;
        return new Rect(x, c.y() + NAME_ROW, c.right() - x, stage().height());
    }

    private void addNameRow() {
        Rect c = frame.content();
        boolean named = !savedName.isEmpty();
        int labelW = font.width(Component.translatable("hearthstead.heraldry.name.label")) + 6;
        Component randomLabel = Component.translatable("hearthstead.heraldry.name.random");
        int randomW = Ui2Button.textWidth(font, randomLabel);
        int boxX = c.x() + labelW;
        int boxW = Math.min(150, c.right() - boxX - randomW - 8);
        boolean focused = nameBox != null && nameBox.isFocused();
        nameBox = new net.minecraft.client.gui.components.EditBox(font, boxX, c.y(), boxW, 14,
            Component.translatable("hearthstead.heraldry.name.label"));
        nameBox.setMaxLength(com.hearthstead.heraldry.KingdomName.MAX + 8);
        nameBox.setValue(named ? nameDraft : "");
        nameBox.setEditable(named);
        nameBox.active = named;
        nameBox.setResponder(value -> {
            nameDraft = value;
            updateConfirm();
        });
        nameBox.setTooltip(Tooltip.create(Component.translatable(named ? "hearthstead.heraldry.name.tip"
            : "hearthstead.heraldry.name.unfounded")));
        addRenderableWidget(nameBox);
        if (focused) setFocused(nameBox);
        Ui2Button random = Ui2Button.secondary(boxX + boxW + 8, c.y() + 1, randomW, 12, randomLabel, () -> {
            String next = nameDraft;
            for (int i = 0; i < 8 && next.equalsIgnoreCase(nameDraft); i++) {
                next = com.hearthstead.settlement.SettlerNames.pickSettlementName(
                    net.minecraft.util.RandomSource.create());
            }
            nameDraft = next;
            if (nameBox != null) nameBox.setValue(next);
            updateConfirm();
        });
        random.active = named;
        random.setTooltip(Tooltip.create(Component.translatable("hearthstead.heraldry.name.random.tip")));
        addRenderableWidget(random);
    }

    /** Raise is refused locally while the name breaks the length or letter rules. */
    private void updateConfirm() {
        if (confirmButton == null) return;
        com.hearthstead.heraldry.KingdomName.Problem problem = savedName.isEmpty()
            ? com.hearthstead.heraldry.KingdomName.Problem.NONE
            : com.hearthstead.heraldry.KingdomName.checkShape(nameDraft);
        confirmButton.active = problem == com.hearthstead.heraldry.KingdomName.Problem.NONE;
        confirmButton.setTooltip(Tooltip.create(problem == com.hearthstead.heraldry.KingdomName.Problem.NONE
            ? Component.translatable("hearthstead.heraldry.raise.tip")
            : Component.translatable(com.hearthstead.heraldry.KingdomName.messageKey(problem),
                com.hearthstead.heraldry.KingdomName.normalize(nameDraft))));
    }

    private Rect body() {
        Rect p = panel();
        int top = p.y() + Ui2Tabs.HEIGHT + 6;
        return new Rect(p.x(), top, p.width(), p.bottom() - top);
    }

    @Override
    protected void init() {
        frame = layoutFor(width, height);
        addRenderableWidget(Ui2Frame.closeKey(frame, Component.translatable("hearthstead.heraldry.cancel.tip"),
            this::onClose));
        addNameRow();
        addTabs();
        switch (tab) {
            case PRESETS -> addPresets();
            case CLOTH -> addCloth();
            case LAYERS -> addLayers();
        }
        Rect f = frame.footer();
        Component cancelLabel = Component.translatable("hearthstead.heraldry.cancel");
        Ui2Button cancel = Ui2Button.secondary(f.x(), f.y() + 4, Ui2Button.textWidth(font, cancelLabel), 12,
            cancelLabel, this::onClose);
        cancel.setTooltip(Tooltip.create(Component.translatable("hearthstead.heraldry.cancel.tip")));
        addRenderableWidget(cancel);
        if (!draft.equals(saved) || !nameDraft.equals(savedName)) {
            Component revertLabel = Component.translatable("hearthstead.heraldry.revert");
            Ui2Button revert = Ui2Button.secondary(cancel.getX() + cancel.getWidth() + 10, f.y() + 4,
                Ui2Button.textWidth(font, revertLabel), 12, revertLabel, () -> {
                    draft = trimmed(saved);
                    nameDraft = savedName;
                    selectedLayer = draft.layers().isEmpty() ? -1 : draft.layers().size() - 1;
                    rebuildWidgets();
                });
            revert.setTooltip(Tooltip.create(Component.translatable("hearthstead.heraldry.revert.tip")));
            addRenderableWidget(revert);
        }
        Component raise = Component.translatable("hearthstead.heraldry.raise");
        int rw = Math.max(104, Ui2Button.filledWidth(font, raise) + 16);
        Ui2Button confirm = Ui2Button.banner(f.right() - rw, f.y(), rw, Ui2FrameLayout.BUTTON_H, raise,
            this::confirm);
        confirm.setTooltip(Tooltip.create(Component.translatable("hearthstead.heraldry.raise.tip")));
        addRenderableWidget(confirm);
        confirmButton = confirm;
        updateConfirm();
    }

    private void addTabs() {
        Rect p = panel();
        Component[] labels = {
            Component.translatable("hearthstead.heraldry.tab.presets"),
            Component.translatable("hearthstead.heraldry.tab.cloth"),
            Component.translatable("hearthstead.heraldry.tab.layers"),
        };
        boolean[] external = {false, false, false};
        int[] xs = Ui2Tabs.positions(font, labels, external, p.x());
        Tab[] order = Tab.values();
        for (int i = 0; i < labels.length; i++) {
            int w = Ui2Tabs.tabWidth(font, labels[i], false);
            Tab target = order[i];
            boolean selected = tab == target;
            if (selected) tabs.target(xs[i] + 2, w - 4);
            addRenderableWidget(new Ui2Tabs.Tab(xs[i], p.y(), w, labels[i], selected, false, () -> {
                tab = target;
                rebuildWidgets();
            }));
        }
    }

    // --------------------------------------------------------- presets ---

    private void addPresets() {
        Rect b = body();
        int cols = 6;
        int cw = 24;
        int ch = 40;
        int gap = (b.width() - cols * cw) / (cols - 1);
        List<HeraldryCatalog.Preset> presets = HeraldryCatalog.PRESETS;
        for (int i = 0; i < presets.size(); i++) {
            HeraldryCatalog.Preset preset = presets.get(i);
            int x = b.x() + (i % cols) * (cw + gap);
            int y = b.y() + (i / cols) * (ch + 2);
            boolean selected = draft.equals(preset.design());
            Cell cell = new Cell(x, y, cw, ch, Component.translatable(preset.translationKey()), selected,
                (g, c) -> HeraldryGui.drawBanner(g, preset.design(), c.getX() + 4, c.getY() + 4, 16),
                () -> {
                    draft = preset.design();
                    selectedLayer = draft.layers().isEmpty() ? -1 : draft.layers().size() - 1;
                    rebuildWidgets();
                });
            cell.setTooltip(Tooltip.create(Component.translatable(preset.translationKey())
                .append("\n").append(Component.translatable(preset.design().shape().translationKey())
                    .withStyle(net.minecraft.ChatFormatting.GRAY))));
            addRenderableWidget(cell);
        }
    }

    // ----------------------------------------------------------- cloth ---

    private void addCloth() {
        Rect b = body();
        addSwatches(b.x(), b.y() + 11, draft.base(), color -> {
            draft = draft.withBase(color);
            rebuildWidgets();
        }, "hearthstead.heraldry.field.tip");
        BannerShape[] shapes = BannerShape.values();
        int pw = 28;
        int gap = (b.width() - shapes.length * pw) / (shapes.length - 1);
        int y = b.y() + 37;
        for (int i = 0; i < shapes.length; i++) {
            BannerShape shape = shapes[i];
            Cell cell = new Cell(b.x() + i * (pw + gap), y, pw, 38, Component.translatable(shape.translationKey()),
                draft.shape() == shape,
                (g, c) -> HeraldryGui.drawBanner(g, draft.withShape(shape), c.getX() + 7, c.getY() + 3, 14),
                () -> {
                    draft = draft.withShape(shape);
                    rebuildWidgets();
                });
            cell.setTooltip(Tooltip.create(Component.translatable(shape.translationKey())));
            addRenderableWidget(cell);
        }
    }

    private void addSwatches(int x, int y, @Nullable DyeColor current, Consumer<DyeColor> pick, String tipKey) {
        for (int i = 0; i < 16; i++) {
            DyeColor color = DyeColor.byId(i);
            Swatch swatch = new Swatch(x + i * 10, y, color, color == current, current != null,
                () -> pick.accept(color));
            swatch.setTooltip(Tooltip.create(Component.translatable(tipKey,
                Component.translatable("color.minecraft." + color.getName()))));
            addRenderableWidget(swatch);
        }
    }

    // ---------------------------------------------------------- layers ---

    private void addLayers() {
        Rect b = body();
        List<VillageDesign.Layer> layers = draft.layers();
        for (int i = 0; i < layers.size(); i++) {
            int index = i;
            VillageDesign.Layer layer = layers.get(i);
            Chip chip = new Chip(b.x() + i * 15, b.y(), 14, 12, Component.literal(Integer.toString(i + 1)),
                layer.color(), i == selectedLayer, () -> {
                    selectedLayer = index;
                    rebuildWidgets();
                });
            chip.setTooltip(Tooltip.create(Component.translatable("hearthstead.heraldry.layer.tip", i + 1,
                Component.translatable(HeraldryCatalog.patternKey(layer.pattern())),
                Component.translatable("color.minecraft." + layer.color().getName()))));
            addRenderableWidget(chip);
        }
        boolean full = layers.size() >= VillageDesign.MAX_LAYERS;
        Chip add = new Chip(b.x() + layers.size() * 15, b.y(), 14, 12, Component.literal("+"), null, false,
            () -> {
                List<VillageDesign.Layer> next = new ArrayList<>(draft.layers());
                next.add(new VillageDesign.Layer(VillageDesign.vanilla(next.isEmpty() ? "border" : "circle"),
                    draft.trim()));
                draft = draft.withLayers(next);
                selectedLayer = next.size() - 1;
                rebuildWidgets();
            });
        add.active = !full;
        add.setTooltip(Tooltip.create(Component.translatable(full ? "hearthstead.heraldry.layer.full"
            : "hearthstead.heraldry.layer.add", VillageDesign.MAX_LAYERS)));
        addRenderableWidget(add);

        boolean has = selectedLayer >= 0 && selectedLayer < layers.size();
        int kx = b.right() - 3 * 13 + 1;
        Chip earlier = new Chip(kx, b.y(), 12, 12, Component.literal("<"), null, false, () -> move(-1));
        earlier.active = has && selectedLayer > 0;
        earlier.setTooltip(Tooltip.create(Component.translatable("hearthstead.heraldry.layer.earlier")));
        addRenderableWidget(earlier);
        Chip later = new Chip(kx + 13, b.y(), 12, 12, Component.literal(">"), null, false, () -> move(1));
        later.active = has && selectedLayer < layers.size() - 1;
        later.setTooltip(Tooltip.create(Component.translatable("hearthstead.heraldry.layer.later")));
        addRenderableWidget(later);
        Chip remove = new Chip(kx + 26, b.y(), 12, 12, Component.literal("×"), null, false, () -> {
            List<VillageDesign.Layer> next = new ArrayList<>(draft.layers());
            next.remove(selectedLayer);
            draft = draft.withLayers(next);
            selectedLayer = Math.min(selectedLayer, next.size() - 1);
            rebuildWidgets();
        });
        remove.active = has;
        remove.setTooltip(Tooltip.create(Component.translatable("hearthstead.heraldry.layer.remove")));
        addRenderableWidget(remove);

        VillageDesign.Layer current = has ? layers.get(selectedLayer) : null;
        if (has) {
            addSwatches(b.x(), b.y() + 16, current.color(), color -> replaceSelected(
                new VillageDesign.Layer(draft.layers().get(selectedLayer).pattern(), color)),
                "hearthstead.heraldry.layer_colour.tip");
        }

        // Pattern grid: pick for the selected layer, or add a layer when there is none.
        int total = HeraldryCatalog.PATTERNS.size();
        int rows = (total + GRID_COLS - 1) / GRID_COLS;
        gridScroll = Math.max(0, Math.min(gridScroll, rows - GRID_ROWS_VISIBLE));
        int gy = b.y() + 30;
        DyeColor ink = current != null ? current.color() : draft.trim();
        for (int r = 0; r < GRID_ROWS_VISIBLE; r++) {
            for (int col = 0; col < GRID_COLS; col++) {
                int index = (gridScroll + r) * GRID_COLS + col;
                if (index >= total) break;
                VillageDesign.Layer shown = new VillageDesign.Layer(HeraldryCatalog.patternId(index), ink);
                boolean selected = current != null && current.pattern().equals(shown.pattern());
                Cell cell = new Cell(b.x() + col * (CELL_W + 1), gy + r * (CELL_H + 1), CELL_W, CELL_H,
                    Component.translatable(HeraldryCatalog.patternKey(shown.pattern())), selected,
                    (g, c) -> HeraldryGui.drawPattern(g, draft.base(), shown, BannerShape.STRAIGHT,
                        c.getX() + 1, c.getY() + 2, 10),
                    () -> pickPattern(shown.pattern()));
                cell.setTooltip(Tooltip.create(Component.translatable(HeraldryCatalog.patternKey(shown.pattern()))));
                addRenderableWidget(cell);
            }
        }
    }

    private void pickPattern(net.minecraft.resources.ResourceLocation pattern) {
        List<VillageDesign.Layer> layers = draft.layers();
        if (selectedLayer >= 0 && selectedLayer < layers.size()) {
            replaceSelected(new VillageDesign.Layer(pattern, layers.get(selectedLayer).color()));
            return;
        }
        if (layers.size() >= VillageDesign.MAX_LAYERS) {
            return;
        }
        List<VillageDesign.Layer> next = new ArrayList<>(layers);
        next.add(new VillageDesign.Layer(pattern, draft.trim()));
        draft = draft.withLayers(next);
        selectedLayer = next.size() - 1;
        rebuildWidgets();
    }

    private void replaceSelected(VillageDesign.Layer layer) {
        List<VillageDesign.Layer> next = new ArrayList<>(draft.layers());
        next.set(selectedLayer, layer);
        draft = draft.withLayers(next);
        rebuildWidgets();
    }

    private void move(int delta) {
        int to = selectedLayer + delta;
        List<VillageDesign.Layer> next = new ArrayList<>(draft.layers());
        if (selectedLayer < 0 || to < 0 || to >= next.size()) return;
        VillageDesign.Layer moving = next.remove(selectedLayer);
        next.add(to, moving);
        draft = draft.withLayers(next);
        selectedLayer = to;
        rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double dx, double dy) {
        if (tab == Tab.LAYERS && body().contains(mouseX, mouseY) && dy != 0) {
            int rows = (HeraldryCatalog.PATTERNS.size() + GRID_COLS - 1) / GRID_COLS;
            int next = Math.max(0, Math.min(rows - GRID_ROWS_VISIBLE, gridScroll + (dy < 0 ? 1 : -1)));
            if (next != gridScroll) {
                gridScroll = next;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, dx, dy);
    }

    // --------------------------------------------------------- actions ---

    private void confirm() {
        if (!sent) {
            sent = true;
            String name = savedName.isEmpty() ? ""
                : com.hearthstead.heraldry.KingdomName.normalize(nameDraft);
            PacketDistributor.sendToServer(new BannerDesignPayloads.Action(pos, BannerDesignPayloads.Action.CONFIRM,
                draft, name.equals(savedName) ? "" : name));
        }
        super.onClose();
    }

    @Override
    public void onClose() {
        if (!sent) {
            sent = true;
            PacketDistributor.sendToServer(new BannerDesignPayloads.Action(pos, BannerDesignPayloads.Action.CLOSE,
                saved, ""));
        }
        super.onClose();
    }

    // ---------------------------------------------------------- render ---

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Screen.render draws this first, then the widgets on top.
        Ui2Frame.scrim(g, width, height);
        Ui2Frame.draw(g, frame);
        Ui2Frame.title(g, font, frame, titleText, title.getString(),
            Component.translatable("hearthstead.heraldry.subtitle"));
        Rect c = frame.content();
        g.drawString(font, Component.translatable("hearthstead.heraldry.name.label"), c.x(), c.y() + 3,
            Ui2Palette.INK_SOFT, false);
        renderStage(g, mouseX, mouseY);
        Rect p = panel();
        Ui2Surface.rule(g, p.x(), p.y() + Ui2Tabs.HEIGHT, p.width());
        tabs.render(g, p.y() + Ui2Tabs.HEIGHT - 2);
        Rect b = body();
        if (tab == Tab.CLOTH) {
            g.drawString(font, Component.translatable("hearthstead.heraldry.field",
                Component.translatable("color.minecraft." + draft.base().getName())), b.x(), b.y(),
                Ui2Palette.INK_SOFT, false);
            g.drawString(font, Component.translatable("hearthstead.heraldry.shape",
                Component.translatable(draft.shape().translationKey())), b.x(), b.y() + 26,
                Ui2Palette.INK_SOFT, false);
        } else if (tab == Tab.LAYERS) {
            if (selectedLayer < 0) {
                g.drawString(font, Component.translatable("hearthstead.heraldry.layer.none"), b.x(), b.y() + 17,
                    Ui2Palette.INK_MUTED, false);
            }
            int rows = (HeraldryCatalog.PATTERNS.size() + GRID_COLS - 1) / GRID_COLS;
            int gy = b.y() + 30;
            int trackH = GRID_ROWS_VISIBLE * (CELL_H + 1) - 1;
            int tx = b.right() - 2;
            g.fill(tx, gy, tx + 2, gy + trackH, Ui2Palette.TRACK);
            int thumbH = Math.max(6, trackH * GRID_ROWS_VISIBLE / rows);
            int thumbY = gy + (trackH - thumbH) * gridScroll / Math.max(1, rows - GRID_ROWS_VISIBLE);
            g.fill(tx, thumbY, tx + 2, thumbY + thumbH, Ui2Palette.INK_MUTED);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (notice != null && Util.getMillis() < noticeUntil) {
            Rect f = frame.footer();
            int w = font.width(notice);
            int nx = f.right() - 110 - w - 6;
            g.drawString(font, notice, Math.max(f.x() + 50, nx), f.y() + 6, Ui2Palette.AMBER, false);
        }
    }

    private void renderStage(GuiGraphics g, int mouseX, int mouseY) {
        Rect s = stage();
        g.fill(s.x(), s.y(), s.right(), s.bottom(), Ui2Palette.INSET);
        BannerChrome.outline(g, s.x(), s.y(), s.width(), s.height(), Ui2Palette.RULE_STRONG);
        // Banner on its iron rod.
        int bw = 28;
        int bx = s.x() + 8;
        int by = s.y() + 10;
        g.fill(bx - 3, by - 4, bx + bw + 3, by - 2, Ui2Palette.IRON_DARK);
        g.fill(bx - 2, by - 3, bx + bw + 2, by - 2, Ui2Palette.IRON_LIGHT);
        HeraldryGui.drawBanner(g, draft, bx, by - 2, bw);
        // A guard in these colours.
        int gx0 = s.x() + 44;
        int gy0 = s.y() + 4;
        int gx1 = s.right() - 2;
        int gy1 = s.y() + 70;
        renderGuard(g, gx0, gy0, gx1, gy1, mouseX, mouseY);
        // Caption: field and trim as the guards wear them.
        int cy = s.bottom() - 21;
        Ui2Surface.rule(g, s.x() + 4, cy - 4, s.width() - 8);
        caption(g, s.x() + 6, cy, "hearthstead.heraldry.caption.field", draft.base());
        caption(g, s.x() + 6, cy + 10, "hearthstead.heraldry.caption.trim", draft.trim());
    }

    private void caption(GuiGraphics g, int x, int y, String key, DyeColor color) {
        g.fill(x, y, x + 7, y + 7, Ui2Palette.INK_SOFT);
        g.fill(x + 1, y + 1, x + 6, y + 6, 0xFF000000 | color.getTextureDiffuseColor());
        Component text = Component.translatable(key, Component.translatable("color.minecraft." + color.getName()));
        g.drawString(font, text, x + 10, y, Ui2Palette.INK_SOFT, false);
    }

    private void renderGuard(GuiGraphics g, int x0, int y0, int x1, int y1, int mouseX, int mouseY) {
        SettlerEntity guard = previewGuard();
        if (guard == null) {
            return;
        }
        int packed = GearGate.packHeraldry(draft.base(), draft.trim());
        int clearance = guard.gearClearancePacked();
        int before = guard.heraldryPacked();
        guard.setGearProjection(clearance, packed);
        try {
            SettlerRenderer.withoutPortraitLabels(guard, () ->
                InventoryScreen.renderEntityInInventoryFollowsMouse(g, x0, y0, x1, y1, 28, 0.0625F,
                    mouseX, mouseY, guard));
        } finally {
            guard.setGearProjection(clearance, before);
        }
    }

    /** A real guard or archer near this Banner if one is in view, else a dressed stand-in. */
    @Nullable
    private SettlerEntity previewGuard() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return null;
        SettlerEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (SettlerEntity s : mc.level.getEntitiesOfClass(SettlerEntity.class, new AABB(pos).inflate(40),
            s -> s.isAlive() && GearGate.roleOf(s.getProfession())
                != com.hearthstead.settlement.gear.GearTier.Role.WORKER)) {
            double d = s.distanceToSqr(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
            if (d < bestD) {
                best = s;
                bestD = d;
            }
        }
        if (best != null) return best;
        if (dummyGuard == null || dummyGuard.level() != mc.level) {
            dummyGuard = ModEntities.SETTLER.get().create(mc.level);
            if (dummyGuard != null) {
                dummyGuard.setProfessionProjection(Profession.GUARD);
                dummyGuard.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
                dummyGuard.setItemSlot(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS));
                dummyGuard.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.CHAINMAIL_HELMET));
                dummyGuard.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            }
        }
        return dummyGuard;
    }

    // --------------------------------------------------------- widgets ---

    /** A framed picker cell that paints its own content (a mini banner). */
    static final class Cell extends AbstractButton {
        private final boolean selected;
        private final java.util.function.BiConsumer<GuiGraphics, Cell> paint;
        private final Runnable action;

        Cell(int x, int y, int w, int h, Component label, boolean selected,
             java.util.function.BiConsumer<GuiGraphics, Cell> paint, Runnable action) {
            super(x, y, w, h, label);
            this.selected = selected;
            this.paint = paint;
            this.action = action;
        }

        @Override
        public void onPress() {
            action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            boolean lit = active && isHoveredOrFocused();
            g.fill(x, y, x + w, y + h, selected ? Ui2Palette.ROW_SELECTED : lit ? Ui2Palette.ROW_HOVER : 0);
            paint.accept(g, this);
            if (selected) {
                BannerChrome.outline(g, x, y, w, h, Ui2Palette.BURGUNDY);
            } else if (lit) {
                BannerChrome.outline(g, x, y, w, h, Ui2Palette.GOLD);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** One dye swatch. */
    static final class Swatch extends AbstractButton {
        private final DyeColor color;
        private final boolean selected;
        private final Runnable action;

        Swatch(int x, int y, DyeColor color, boolean selected, boolean enabled, Runnable action) {
            super(x, y, 9, 9, Component.translatable("color.minecraft." + color.getName()));
            this.color = color;
            this.selected = selected;
            this.action = action;
            this.active = enabled;
        }

        @Override
        public void onPress() {
            action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            boolean lit = active && isHoveredOrFocused();
            g.fill(x, y, x + 9, y + 9, selected ? Ui2Palette.INK : lit ? Ui2Palette.GOLD : Ui2Palette.RULE_STRONG);
            g.fill(x + 1, y + 1, x + 8, y + 8, 0xFF000000 | color.getTextureDiffuseColor());
            if (selected) {
                g.fill(x + 3, y + 3, x + 6, y + 6, color == DyeColor.WHITE || color == DyeColor.YELLOW
                    || color == DyeColor.LIGHT_GRAY || color == DyeColor.LIME ? Ui2Palette.INK : 0xFFF5F0E3);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    /** A small layer chip or tool key: number with the layer's dye, "+", arrows, remove. */
    static final class Chip extends AbstractButton {
        @Nullable
        private final DyeColor color;
        private final boolean selected;
        private final Runnable action;

        Chip(int x, int y, int w, int h, Component label, @Nullable DyeColor color, boolean selected,
             Runnable action) {
            super(x, y, w, h, label);
            this.color = color;
            this.selected = selected;
            this.action = action;
        }

        @Override
        public void onPress() {
            action.run();
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int w = getWidth();
            int h = getHeight();
            boolean lit = active && isHoveredOrFocused();
            int edge = !active ? Ui2Palette.DISABLED_BORDER : selected ? Ui2Palette.BURGUNDY
                : lit ? Ui2Palette.GOLD : Ui2Palette.RULE_STRONG;
            g.fill(x, y, x + w, y + h, edge);
            g.fill(x + 1, y + 1, x + w - 1, y + h - 1, !active ? Ui2Palette.DISABLED_FILL
                : selected ? Ui2Palette.PAPER_DEEP : Ui2Palette.PAPER);
            if (color != null) {
                g.fill(x + 1, y + h - 3, x + w - 1, y + h - 1, 0xFF000000 | color.getTextureDiffuseColor());
            }
            var font = Minecraft.getInstance().font;
            int tw = font.width(getMessage());
            g.drawString(font, getMessage(), x + (w - tw + 1) / 2, y + 2,
                active ? Ui2Palette.INK : Ui2Palette.INK_DISABLED, false);
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }
}
