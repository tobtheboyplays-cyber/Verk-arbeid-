package com.hearthstead.client.command;

import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Surface;
import com.hearthstead.network.FieldOrderStatePayload;
import com.hearthstead.settlement.guard.FieldOrderRules.Group;
import com.hearthstead.settlement.guard.FieldOrderRules.Kind;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Command HUD in the Banner style (dark wood inset, light text on wood):
 * <ul>
 *   <li>one small chip per role right of the hotbar while it holds an order:
 *       role icon, short order, and "heard/total" (warm red when some cannot
 *       reach their spot);</li>
 *   <li>while a command key is held: a two-line hint above the hotbar naming
 *       the order and the keys (release, scroll width, Esc);</li>
 *   <li>hold R or G: that group's order strip, framed numbered chips
 *       (R: follow me, back to posts; G: hold fire, follow me, back to posts,
 *       resupply), the wheel moving a gold highlight.</li>
 * </ul>
 * The one-line confirmation ("Knights: hold the line (4/6 heard)") is the
 * server's action-bar message, directly above the hotbar.
 */
public final class CommandHud {
    private static final int WARN = com.hearthstead.client.ui2.Ui2Hud.WARN;

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.options.hideGui || mc.screen != null || mc.player.isSpectator()) return;
        Font font = mc.font;
        int w = g.guiWidth();
        int h = g.guiHeight();
        renderChips(g, font, w, h);
        CommandKeys.Preview preview = CommandKeys.preview(delta.getGameTimeDeltaPartialTick(false));
        if (preview != null) {
            renderPreviewHint(g, font, w, h, preview);
        } else if (CommandKeys.menuOpen()) {
            renderMenu(g, font, w, h);
        }
    }

    private static void renderChips(GuiGraphics g, Font font, int w, int h) {
        int x = w / 2 + 91 + 6;
        Minecraft mc = Minecraft.getInstance();
        boolean offhandRight = mc.player.getMainArm() == net.minecraft.world.entity.HumanoidArm.LEFT
            && !mc.player.getOffhandItem().isEmpty();
        if (offhandRight || mc.options.attackIndicator().get() == net.minecraft.client.AttackIndicatorStatus.HOTBAR) {
            // Clear the right-hand offhand slot / hotbar attack meter instead of drawing over it.
            x = w / 2 + 91 + 29 + 4;
        }
        int y = h - 13;
        for (Group role : Group.ROLES) {
            FieldOrderStatePayload.GroupLine line = CommandClientState.line(role);
            if (line == null) continue;
            Kind kind = Kind.fromWire(line.kind()).orElse(Kind.LINE);
            Component label = CommandStyle.shortLabel(role, kind, line.holdFire());
            String count = line.heard() + "/" + line.total();
            int chipW = 3 + 9 + 3 + font.width(label) + 4 + font.width(count) + 4;
            if (x + chipW > w - 2) {
                // Narrow screens: drop the label, keep icon and count.
                label = Component.empty();
                chipW = 3 + 9 + 3 + font.width(count) + 4;
            }
            plate(g, x, y, chipW, 12);
            Ui2Surface.icon(g, CommandStyle.roleIcon(role), x + 3, y + 2, 8);
            int tx = x + 15;
            if (!label.getString().isEmpty()) {
                g.drawString(font, label, tx, y + 2, BannerChrome.TEXT_ON_WOOD, false);
                tx += font.width(label) + 4;
            }
            g.drawString(font, count, tx, y + 2, line.unreachable() > 0 ? WARN : CommandStyle.argb(role), false);
            y -= 14;
            if (y < h / 2) break;
        }
    }

    private static void renderPreviewHint(GuiGraphics g, Font font, int w, int h,
                                          CommandKeys.Preview preview) {
        CommandAim.Target target = preview.target();
        Group group = target.group();
        Component title;
        int titleColor = BannerChrome.TEXT_ON_WOOD;
        if (!target.valid()) {
            title = target.problem();
            titleColor = WARN;
        } else if (preview.soldiers() == 0) {
            title = Component.translatable("hearthstead.command.hud.none_near", CommandStyle.groupName(group));
            titleColor = WARN;
        } else {
            Component what = target.kind() == Kind.ATTACK
                ? Component.translatable("hearthstead.command.hud.attack." + (group.ranged() ? "ranged" : "melee"),
                    target.enemyName())
                : CommandStyle.shortLabel(group, target.kind(), false);
            title = Component.translatable("hearthstead.command.hud.title", CommandStyle.groupName(group), what,
                preview.soldiers());
        }
        boolean formation = target.valid() && target.kind() == Kind.LINE && !group.multi();
        Component keys = formation
            ? Component.translatable("hearthstead.command.hud.keys_width", preview.width())
            : Component.translatable("hearthstead.command.hud.keys");
        int boxW = Math.max(font.width(title), font.width(keys)) + 22;
        int x = (w - boxW) / 2;
        int y = h - 92;
        plate(g, x, y, boxW, 24);
        Ui2Surface.icon(g, CommandStyle.icon(group, target.kind()), x + 4, y + 3, 8);
        g.drawString(font, title, x + 16, y + 3, titleColor, false);
        g.drawString(font, keys, x + 16, y + 13, BannerChrome.TEXT_ON_WOOD_MUTED, false);
    }

    private static void renderMenu(GuiGraphics g, Font font, int w, int h) {
        Group group = CommandKeys.menuGroup();
        if (group == null) return;
        java.util.List<CommandKeys.MenuItem> items = CommandKeys.menuItems(group);
        boolean dry = items.contains(CommandKeys.MenuItem.RESUPPLY) && CommandKeys.archerDry();
        Component title = CommandStyle.groupName(group);
        int gap = 4;
        int chipH = 14;
        int[] widths = new int[items.size()];
        int total = font.width(title) + 8;
        for (int i = 0; i < items.size(); i++) {
            Component label = Component.translatable(items.get(i).labelKey());
            widths[i] = 3 + font.width(String.valueOf(i + 1)) + 3 + 9 + 3 + font.width(label) + 4;
            total += widths[i] + gap;
        }
        total -= gap;
        int x = (w - total - 10) / 2;
        int y = h - 94;
        plate(g, x, y, total + 10, chipH + 6);
        g.drawString(font, title, x + 5, y + 6, CommandStyle.argb(group), false);
        int cx = x + 5 + font.width(title) + 8;
        for (int i = 0; i < items.size(); i++) {
            CommandKeys.MenuItem item = items.get(i);
            Component label = Component.translatable(item.labelKey());
            boolean lit = i == CommandKeys.menuHighlight();
            int cy = y + 3;
            g.fill(cx, cy, cx + widths[i], cy + chipH, lit ? 0x66201408 : 0x33000000);
            g.renderOutline(cx, cy, widths[i], chipH, lit ? BannerChrome.GOLD_EDGE : 0x80806040);
            int tx = cx + 3;
            g.drawString(font, String.valueOf(i + 1), tx, cy + 3, BannerChrome.GOLD_EDGE, false);
            tx += font.width(String.valueOf(i + 1)) + 3;
            Ui2Surface.icon(g, menuIcon(item), tx, cy + 3, 8);
            tx += 12;
            int color = item == CommandKeys.MenuItem.RESUPPLY
                ? (dry ? WARN : BannerChrome.TEXT_ON_WOOD_MUTED) : BannerChrome.TEXT_ON_WOOD;
            g.drawString(font, label, tx, cy + 3, color, false);
            cx += widths[i] + gap;
        }
    }

    private static net.minecraft.world.item.ItemStack menuIcon(CommandKeys.MenuItem item) {
        return switch (item) {
            case HOLD_FIRE -> CommandStyle.icon(Group.ARCHERS,
                CommandKeys.holdingFire() ? Kind.FIRE_AT_WILL : Kind.HOLD_FIRE);
            case FOLLOW -> CommandStyle.icon(Group.KNIGHTS, Kind.FOLLOW);
            case RETURN -> CommandStyle.icon(Group.KNIGHTS, Kind.RETURN);
            case RESUPPLY -> CommandStyle.icon(Group.ARCHERS, Kind.RESUPPLY);
        };
    }

    private static void plate(GuiGraphics g, int x, int y, int w, int h) {
        com.hearthstead.client.ui2.Ui2Hud.plate(g, x, y, w, h);
    }

    private CommandHud() {
    }
}
