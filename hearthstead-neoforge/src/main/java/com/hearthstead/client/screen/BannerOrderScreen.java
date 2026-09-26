package com.hearthstead.client.screen;

import com.hearthstead.client.ui.HsUi;
import com.hearthstead.client.ui2.BannerSheetLayout.Rect;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.client.ui2.Ui2Tips;
import com.hearthstead.network.BannerOrderActionPayload;
import com.hearthstead.network.BannerOrderMenuPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BannerItem;
import net.minecraft.world.item.DyeColor;
import net.neoforged.neoforge.network.PacketDistributor;
import java.util.UUID;

/**
 * Four commands, one click; the world stays visible around the standard
 * window and the game never pauses.
 */
public final class BannerOrderScreen extends Screen {
    static final int PREF_W = 300;
    static final int PREF_H = 180;
    static final int COMMAND_W = 96;
    static final int STATUS_H = 16;

    private final BannerOrderMenuPayload menu;
    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private Layout layout;

    public BannerOrderScreen(BannerOrderMenuPayload menu) {
        super(Component.translatable("hearthstead.banner.orders"));
        this.menu = menu;
    }

    public UUID token() {
        return menu.token();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        layout = layoutFor(width, height);
        command(layout.move(), "move", BannerOrderActionPayload.MOVE, menu.ground());
        command(layout.follow(), "follow", BannerOrderActionPayload.FOLLOW, true);
        command(layout.attack(), "attack", BannerOrderActionPayload.ATTACK, menu.ground() || !menu.enemy().isEmpty());
        command(layout.hold(), "hold", BannerOrderActionPayload.HOLD, menu.ground());
        if (!menu.canCommand()) {
            Rect r = layout.takeover();
            Ui2Button[] self = new Ui2Button[1];
            Ui2Button takeover = Ui2Button.banner(r.x(), r.y(), r.width(), r.height(),
                Component.translatable("hearthstead.banner.takeover"), () -> {
                    PacketDistributor.sendToServer(new BannerOrderActionPayload(BannerOrderActionPayload.TAKEOVER,
                        menu.token(), BannerOrderActionPayload.NONE));
                    self[0].active = false;
                });
            self[0] = takeover;
            Ui2Tips.tip(takeover, Component.translatable("hearthstead.banner.takeover_hint"));
            addRenderableWidget(takeover);
        }
        addRenderableWidget(Ui2Frame.closeKey(layout.frame(), this::onClose));
    }

    private void command(Rect r, String key, int action, boolean target) {
        Ui2Button button = Ui2Button.secondary(r.x(), r.y(), r.width(), r.height(),
            Component.translatable("hearthstead.banner." + key), () -> {
                PacketDistributor.sendToServer(new BannerOrderActionPayload(action, menu.token(),
                    BannerOrderActionPayload.NONE));
                onClose();
            });
        boolean enabled = menu.canCommand() && menu.members() > 0 && target;
        String reason = !menu.canCommand() ? "takeover_hint" : menu.members() <= 0 ? "no_team" : "aim";
        Ui2Tips.enable(button, enabled, Component.translatable("hearthstead.banner.hint." + key),
            Component.translatable("hearthstead.banner." + reason));
        addRenderableWidget(button);
    }

    @Override
    public void tick() {
        if (minecraft == null || minecraft.player == null || !minecraft.player.isAlive()
            || !(minecraft.player.getMainHandItem().getItem() instanceof BannerItem item)
            || item.getColor().getId() != menu.color()) onClose();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ui2FrameLayout frame = layout.frame();
        Ui2Frame.draw(g, frame);
        Component team = Component.translatable("hearthstead.banner.team",
            Component.translatable("hearthstead.banner.color." + DyeColor.byId(menu.color()).getName()));
        Component subtitle = team.copy().append("  ·  ")
            .append(Component.translatable("hearthstead.banner.members", menu.members()));
        if (!menu.canCommand()) {
            subtitle = subtitle.copy().append("  ·  ")
                .append(Component.translatable("hearthstead.banner.commander", menu.leader()));
        }
        Ui2Frame.title(g, font, frame, titleText, title.getString(), subtitle);

        // The command cross meets at a quiet ink mark.
        Rect c = layout.centre();
        g.drawString(font, "+", c.x() + (c.width() - font.width("+")) / 2, c.y() + (c.height() - 8) / 2,
            Ui2Palette.INK_MUTED, false);

        boolean hasTarget = !menu.enemy().isEmpty() || menu.ground();
        Component target = !menu.enemy().isEmpty() ? Component.literal(menu.enemy()) : menu.ground()
            ? Component.literal(menu.point().getX() + ", " + menu.point().getY() + ", " + menu.point().getZ())
            : Component.translatable("hearthstead.banner.aim");
        Rect s = layout.status();
        Ui2Frame.status(g, font, s, HsUi.fitLabel(font, target, s.width() - 18).text(),
            hasTarget ? Ui2Frame.Tone.GOOD : Ui2Frame.Tone.WAIT);
        if (menu.canCommand()) {
            Rect f = frame.footer();
            Component hint = HsUi.fitLabel(font, Component.translatable("hearthstead.banner.close"), f.width()).text();
            g.drawString(font, hint, f.x(), f.y() + (f.height() - 8) / 2, Ui2Palette.INK_MUTED, false);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    /** Pure geometry for the command window (tested at GUI 2-4). */
    static Layout layoutFor(int viewportW, int viewportH) {
        Ui2FrameLayout frame = Ui2FrameLayout.centred(viewportW, viewportH, PREF_W, PREF_H, true);
        Rect body = frame.body(false, true);
        Rect status = new Rect(body.x(), body.bottom() - STATUS_H, body.width(), STATUS_H);
        int gap = Ui2FrameLayout.S;
        int rowH = Ui2FrameLayout.TEXT_BUTTON_H;
        int padH = rowH * 3 + gap * 2;
        int avail = status.y() - Ui2FrameLayout.M - body.y();
        int padTop = body.y() + Math.max(0, (avail - padH) / 2);
        int w = Math.max(1, Math.min(COMMAND_W, (body.width() - Ui2FrameLayout.M * 2) / 3));
        int cx = body.x() + body.width() / 2;
        int x0 = cx - w / 2;
        int midY = padTop + rowH + gap;
        Rect move = new Rect(x0, padTop, w, rowH);
        Rect follow = new Rect(x0 - Ui2FrameLayout.M - w, midY, w, rowH);
        Rect centre = new Rect(x0, midY, w, rowH);
        Rect attack = new Rect(x0 + w + Ui2FrameLayout.M, midY, w, rowH);
        Rect hold = new Rect(x0, midY + rowH + gap, w, rowH);
        Rect[] footer = frame.footerButtons(1, 132);
        return new Layout(frame, move, follow, attack, hold, centre, status, footer[0]);
    }

    record Layout(Ui2FrameLayout frame, Rect move, Rect follow, Rect attack, Rect hold, Rect centre,
                  Rect status, Rect takeover) {
    }
}
