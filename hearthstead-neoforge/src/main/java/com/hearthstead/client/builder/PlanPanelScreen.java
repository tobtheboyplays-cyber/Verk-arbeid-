package com.hearthstead.client.builder;

import com.hearthstead.building.BuildingType;
import com.hearthstead.client.ui2.BannerSheetLayout;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Frame;
import com.hearthstead.client.ui2.Ui2FrameLayout;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Serif;
import com.hearthstead.item.BuildingPlans;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The small, non-pausing placement panel of a Building Plan (MineColonies
 * style): the ghost stays anchored while these buttons move it relative to
 * where the player faces, raise or lower it, turn and mirror it. Confirm hands
 * the spot to the Builder's normal validation and confirm sheet. Hide closes
 * the panel so the player can walk and look; right-clicking the plan again
 * brings it back. Keys: arrows, PgUp/PgDn, R (Shift+R the other way), M,
 * Enter, Esc (cancel).
 */
public final class PlanPanelScreen extends Screen {

    private static final int W = 248;
    private static final int H = 196;
    private static final int KEY = 20;

    private final Ui2Serif.Text titleText = new Ui2Serif.Text(Ui2Serif.Size.TITLE);
    private Ui2FrameLayout frame;
    private final List<int[]> keyBoxes = new ArrayList<>();

    public PlanPanelScreen() {
        super(Component.translatable("hearthstead.building_plan.panel.title"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        keyBoxes.clear();
        int x = Math.max(4, width - W - 8);
        int y = Math.max(4, height - H - 8);
        frame = Ui2FrameLayout.at(x, y, Math.min(W, width - 8), Math.min(H, height - 8), true);
        addRenderableWidget(Ui2Frame.closeKey(frame, Component.translatable("hearthstead.building_plan.panel.hide"),
            this::hide));
        BannerSheetLayout.Rect c = frame.content();
        // Direction pad (relative to where you face), left.
        int px = c.x();
        int py = c.y();
        key(px, py, "«", "hearthstead.building_plan.panel.rotate_left", () -> PlanPlacement.rotate(-1));
        key(px + KEY + 2, py, "▲", "hearthstead.building_plan.panel.forward", () -> PlanPlacement.nudge(1, 0, 0));
        key(px + (KEY + 2) * 2, py, "»", "hearthstead.building_plan.panel.rotate_right",
            () -> PlanPlacement.rotate(1));
        key(px, py + KEY + 2, "◀", "hearthstead.building_plan.panel.left", () -> PlanPlacement.nudge(0, -1, 0));
        key(px + KEY + 2, py + KEY + 2, "▼", "hearthstead.building_plan.panel.back",
            () -> PlanPlacement.nudge(-1, 0, 0));
        key(px + (KEY + 2) * 2, py + KEY + 2, "▶", "hearthstead.building_plan.panel.right",
            () -> PlanPlacement.nudge(0, 1, 0));
        // Height and mirror, right of the pad.
        int rx = px + (KEY + 2) * 3 + 8;
        int rw = c.right() - rx;
        wide(rx, py, rw, "hearthstead.building_plan.panel.up", () -> PlanPlacement.nudge(0, 0, 1));
        wide(rx, py + KEY + 2, rw, "hearthstead.building_plan.panel.down", () -> PlanPlacement.nudge(0, 0, -1));
        wide(rx, py + (KEY + 2) * 2, rw, "hearthstead.building_plan.panel.mirror", PlanPlacement::toggleMirror);
        BannerSheetLayout.Rect f = frame.footer();
        int confirmW = 84;
        int bw = Math.max(30, (f.width() - confirmW - 8) / 2);
        addRenderableWidget(Ui2Button.dangerText(f.x(), f.y(), bw, 20,
            Component.translatable("hearthstead.building_plan.cancel"), this::cancelPlacement));
        addRenderableWidget(Ui2Button.secondary(f.x() + bw + 4, f.y(), bw, 20,
            Component.translatable("hearthstead.building_plan.panel.hide"), this::hide));
        addRenderableWidget(Ui2Button.banner(f.right() - confirmW, f.y(), confirmW, 20,
            Component.translatable("hearthstead.building_plan.panel.confirm"), this::confirm));
    }

    private void key(int x, int y, String glyph, String tipKey, Runnable action) {
        keyBoxes.add(new int[] {x, y, KEY, KEY});
        Ui2Button b = Ui2Button.secondary(x, y, KEY, KEY, Component.literal(glyph), action);
        b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(tipKey)));
        addRenderableWidget(b);
    }

    private void wide(int x, int y, int w, String labelKey, Runnable action) {
        keyBoxes.add(new int[] {x, y, w, KEY});
        addRenderableWidget(Ui2Button.secondary(x, y, w, KEY, Component.translatable(labelKey), action));
    }

    private void confirm() {
        if (!PlanPlacement.confirm()) {
            PlanPlacement.hint("hearthstead.building_plan.loading");
        }
        // The server's answer opens the Builder's confirm sheet in place of this panel.
    }

    private void hide() {
        onClose();
        PlanPlacement.hint("hearthstead.building_plan.panel.hidden");
    }

    private void cancelPlacement() {
        PlanPlacement.clear();
        onClose();
        PlanPlacement.hint("hearthstead.building_plan.panel.cancelled");
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        boolean shift = (mods & GLFW.GLFW_MOD_SHIFT) != 0;
        switch (key) {
            case GLFW.GLFW_KEY_ESCAPE -> cancelPlacement();
            case GLFW.GLFW_KEY_UP -> PlanPlacement.nudge(1, 0, 0);
            case GLFW.GLFW_KEY_DOWN -> PlanPlacement.nudge(-1, 0, 0);
            case GLFW.GLFW_KEY_LEFT -> PlanPlacement.nudge(0, -1, 0);
            case GLFW.GLFW_KEY_RIGHT -> PlanPlacement.nudge(0, 1, 0);
            case GLFW.GLFW_KEY_PAGE_UP -> PlanPlacement.nudge(0, 0, 1);
            case GLFW.GLFW_KEY_PAGE_DOWN -> PlanPlacement.nudge(0, 0, -1);
            case GLFW.GLFW_KEY_R -> PlanPlacement.rotate(shift ? -1 : 1);
            case GLFW.GLFW_KEY_M -> PlanPlacement.toggleMirror();
            case GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> confirm();
            default -> {
                return super.keyPressed(key, scan, mods);
            }
        }
        return true;
    }

    @Override
    public void tick() {
        if (!PlanPlacement.active()) {
            onClose();
        }
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Keep the world and the ghost fully visible.
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        Ui2Frame.draw(g, frame);
        BuildingType type = BuildingPlans.byId(PlanPlacement.typeId());
        String name = type == null ? "" : Component.translatable("item.hearthstead.building_plan.named",
            type.displayName()).getString();
        Component sub = Component.literal(PlanPlacement.styleLabel()
            + (PlanPlacement.mirrored() ? " · " + Component.translatable("hearthstead.building_plan.panel.mirrored")
                .getString() : ""));
        Ui2Frame.title(g, font, frame, titleText, name, sub);
        BannerSheetLayout.Rect c = frame.content();
        int ty = c.y() + (KEY + 2) * 3 + 4;
        Component legend = Component.translatable(PlanPlacement.ready() ? "hearthstead.building_plan.panel.keys"
            : "hearthstead.building_plan.loading");
        for (FormattedCharSequence line : font.split(legend, c.width())) {
            if (ty + 9 > frame.footer().y() - 2) {
                break;
            }
            g.drawString(font, line, c.x(), ty, Ui2Palette.INK_MUTED, false);
            ty += 9;
        }
        for (var child : renderables) {
            child.render(g, mouseX, mouseY, partialTick);
        }
    }
}
