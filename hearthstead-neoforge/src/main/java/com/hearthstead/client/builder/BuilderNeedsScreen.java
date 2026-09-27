package com.hearthstead.client.builder;

import com.hearthstead.client.ui2.*;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.network.BuilderNeedsPayload;
import com.hearthstead.network.BuilderNeedsRequest;
import com.hearthstead.network.BuilderPayloads;
import com.hearthstead.network.SettlerNetwork;
import com.hearthstead.settlement.builder.BuildPhase;
import com.hearthstead.settlement.builder.BuildStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/** Builder Shift-click sheet: no tabs, no scrolling, and real stock only. */
public final class BuilderNeedsScreen extends Screen {
    private BuilderNeedsPayload data;
    private BuilderNeedsLayout layout;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private int age, refresh;
    private List<Component> tooltip;

    public BuilderNeedsScreen(BuilderNeedsPayload data) {
        super(Component.translatable("hearthstead.builder.needs.title"));
        this.data = data;
    }

    public static void accept(BuilderNeedsPayload payload) {
        Minecraft mc = Minecraft.getInstance();
        if (payload.opening()) {
            mc.setScreen(new BuilderNeedsScreen(payload));
        } else if (mc.screen instanceof BuilderNeedsScreen screen
            && screen.data.sessionId().equals(payload.sessionId())
            && screen.data.builderId().equals(payload.builderId())) {
            screen.data = payload;
            screen.age = 0;
        }
    }

    @Override protected void init() {
        layout = BuilderNeedsLayout.forViewport(width, height);
        addRenderableWidget(Ui2Frame.closeKey(layout.frame(), this::onClose));
        Rect button = layout.inventory();
        addRenderableWidget(Ui2Button.secondary(button.x(), button.y(), button.width(), button.height(),
            Component.translatable("hearthstead.settler.sheet.inventory"), () -> send(BuilderNeedsRequest.INVENTORY)));
    }

    @Override public boolean isPauseScreen() { return false; }
    @Override public void renderBackground(GuiGraphics g, int x, int y, float partialTick) { }

    @Override public void tick() {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) return;
        var entity = minecraft.level.getEntity(data.entityId());
        if (!(entity instanceof SettlerEntity builder) || !builder.isAlive()
            || !builder.getUUID().equals(data.builderId()) || builder.getProfession() != Profession.BUILDER
            || minecraft.player.distanceToSqr(builder) > SettlerNetwork.SHEET_REACH_SQUARED || ++age > 160) {
            onClose();
            return;
        }
        if (++refresh >= 40) {
            refresh = 0;
            send(BuilderNeedsRequest.REFRESH);
        }
    }

    private void send(int action) {
        PacketDistributor.sendToServer(new BuilderNeedsRequest(data.entityId(), data.builderId(), data.sessionId(), action));
    }

    @Override public void removed() {
        if (minecraft != null && minecraft.getConnection() != null) send(BuilderNeedsRequest.CLOSE);
        super.removed();
    }

    @Override public void render(GuiGraphics g, int mx, int my, float partialTick) {
        tooltip = null;
        Ui2Frame.draw(g, layout.frame());
        Ui2Frame.title(g, font, layout.frame(), titleText, title.getString(), null);
        boolean idle = data.total() == 0;
        line(g, layout.site(), Component.translatable(idle ? "hearthstead.builder.needs.idle"
            : data.queued() ? "hearthstead.builder.needs.next" : "hearthstead.builder.needs.site",
            idle ? data.builderName() : data.siteName()), Ui2Palette.INK, mx, my);
        Component stage = data.phase() < 0 ? Component.translatable("hearthstead.builder.needs.no_stage")
            : Component.translatable("hearthstead.builder.needs.phase." + BuildPhase.byOrdinal(data.phase()).key());
        line(g, layout.stage(), Component.translatable("hearthstead.builder.needs.progress", stage,
            data.done(), data.total()), Ui2Palette.INK, mx, my);
        line(g, layout.heading(), Component.translatable("hearthstead.builder.needs.materials"), Ui2Palette.INK, mx, my);
        int[] columns = layout.columnStarts();
        String[] headings = {"need", "hut", "warehouse", "way", "missing"};
        for (int i = 0; i < headings.length; i++) {
            line(g, new Rect(columns[i], layout.columns().y(), columns[i + 1] - columns[i] - 2, 12),
                Component.translatable("hearthstead.builder.needs." + headings[i]), Ui2Palette.INK, mx, my);
        }
        if (inside(layout.columns(), mx, my)) tooltip = List.of(Component.translatable("hearthstead.builder.needs.counts_help"));
        int shown = Math.min(layout.rows().size(), data.materials().size());
        for (int i = 0; i < shown; i++) {
            Rect row = layout.rows().get(i);
            BuilderPayloads.Stock stock = data.materials().get(i);
            int color = stock.shortfall() > 0 ? Ui2Palette.DANGER : Ui2Palette.INK;
            g.renderItem(new ItemStack(stock.item()), row.x(), row.y());
            line(g, new Rect(row.x() + 18, row.y() + 4, Math.max(1, columns[0] - row.x() - 20), 12),
                stock.item().getDescription(), color, mx, my);
            int[] counts = {stock.needed(), stock.inHut(), stock.inWarehouse(), stock.onTheWay(), stock.shortfall()};
            for (int c = 0; c < counts.length; c++) {
                line(g, new Rect(columns[c], row.y() + 4, columns[c + 1] - columns[c] - 2, 12),
                    Component.literal(Integer.toString(counts[c])), color, mx, my);
            }
            if (inside(row, mx, my)) tooltip = List.of(material(stock));
        }
        if (data.materialCount() > shown) {
            line(g, layout.overflow(), Component.translatable("hearthstead.builder.needs.more", data.materialCount() - shown),
                Ui2Palette.INK, mx, my);
            if (inside(layout.overflow(), mx, my)) {
                tooltip = new ArrayList<>();
                int end = Math.min(data.materials().size(), shown + tooltipRows());
                for (int i = shown; i < end; i++) tooltip.add(material(data.materials().get(i)));
                if (data.materialCount() > end) tooltip.add(Component.translatable("hearthstead.builder.needs.more", data.materialCount() - end));
            }
        } else if (shown == 0) {
            line(g, layout.overflow(), Component.translatable("hearthstead.builder.needs.no_materials"), Ui2Palette.INK, mx, my);
        }
        Component help = data.help().isEmpty() ? Component.translatable("hearthstead.builder.needs.no_help")
            : Component.translatable("hearthstead.builder.needs.help", help(data.help().getFirst()), data.helpCount());
        line(g, layout.help(), help, data.helpCount() > 0 ? Ui2Palette.DANGER : Ui2Palette.INK, mx, my);
        if (!data.help().isEmpty() && inside(layout.help(), mx, my)) {
            tooltip = new ArrayList<>();
            int end = Math.min(data.help().size(), tooltipRows());
            for (int i = 0; i < end; i++) tooltip.add(help(data.help().get(i)));
            if (data.helpCount() > end) tooltip.add(Component.translatable("hearthstead.builder.needs.more", data.helpCount() - end));
        }
        line(g, layout.status(), idle ? Component.translatable("hearthstead.builder.needs.waiting")
            : BuildStatus.byOrdinal(data.status()).describe(data.statusArgs()), Ui2Palette.INK, mx, my);
        super.render(g, mx, my, partialTick);
        if (tooltip != null) {
            List<net.minecraft.util.FormattedCharSequence> lines = new ArrayList<>();
            int maxLines = Math.max(1, (height - 24) / 10);
            for (Component text : tooltip) {
                for (var wrapped : font.split(text, Math.max(120, Math.min(420, width - 30)))) {
                    if (lines.size() < maxLines) lines.add(wrapped);
                }
            }
            g.renderTooltip(font, lines, mx, my);
        }
    }

    private int tooltipRows() { return Math.max(1, (height - 40) / 24); }
    private Component material(BuilderPayloads.Stock stock) {
        return Component.translatable("hearthstead.builder.needs.material_line", stock.item().getDescription(),
            stock.needed(), stock.inHut(), stock.inWarehouse(), stock.onTheWay(), stock.shortfall());
    }
    private Component help(BuilderNeedsPayload.Help help) {
        return Component.translatable("hearthstead.builder.needs.block", Component.translatable(help.blockKey()),
            help.pos().getX(), help.pos().getY(), help.pos().getZ());
    }
    private void line(GuiGraphics g, Rect area, Component text, int color, int mx, int my) {
        g.drawString(font, font.plainSubstrByWidth(text.getString(), Math.max(1, area.width())), area.x(), area.y(), color, false);
        if (inside(area, mx, my)) tooltip = List.of(text);
    }
    private static boolean inside(Rect area, int x, int y) {
        return x >= area.x() && y >= area.y() && x < area.right() && y < area.bottom();
    }
}
