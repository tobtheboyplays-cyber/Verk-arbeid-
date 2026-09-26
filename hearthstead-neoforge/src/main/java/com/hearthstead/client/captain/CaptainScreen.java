package com.hearthstead.client.captain;

import com.hearthstead.client.ui2.BannerChrome;
import com.hearthstead.client.ui2.Ui2Button;
import com.hearthstead.client.ui2.Ui2Palette;
import com.hearthstead.client.ui2.Ui2Type;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.entity.combat.captain.CaptainLoadout;
import com.hearthstead.entity.combat.captain.CaptainPayloads;
import com.hearthstead.entity.combat.captain.CaptainSpecial;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;

import javax.annotation.Nullable;

/**
 * The Captain panel (opened from his settler sheet): his name, his weapon
 * loadout, his cape and plume, and his specials with their cooldowns. Every
 * button only ASKS the server; the panel redraws from the server's answer.
 */
public class CaptainScreen extends Screen {
    private static final int W = 300;
    private static final int H = 214;

    private final SettlerEntity captain;
    @Nullable private CaptainPayloads.State state;
    private EditBox nameBox;
    private int left;
    private int top;

    public CaptainScreen(SettlerEntity captain) {
        super(Component.translatable("hearthstead.captain.panel.title"));
        this.captain = captain;
        this.state = CaptainClient.of(captain.getId());
    }

    public int entityId() {
        return captain.getId();
    }

    void accept(CaptainPayloads.State payload) {
        this.state = payload;
        rebuildWidgets();
    }

    @Override
    protected void init() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        String keep = nameBox == null ? captain.getSettlerName() : nameBox.getValue();
        nameBox = new EditBox(font, left + 14, top + 32, 170, 16,
            Component.translatable("hearthstead.captain.panel.name"));
        nameBox.setMaxLength(CaptainPayloads.MAX_NAME);
        nameBox.setValue(keep);
        addRenderableWidget(nameBox);
        addRenderableWidget(Ui2Button.primary(left + 190, top + 31, 96, 18,
            Component.translatable("hearthstead.captain.panel.rename"),
            () -> CaptainClient.send(captain.getId(), CaptainPayloads.Action.RENAME, 0, nameBox.getValue())));

        int current = state == null ? -1 : state.loadout();
        int held = state == null ? -1 : state.heldLoadout();
        CaptainLoadout[] all = CaptainLoadout.values();
        for (int i = 0; i < all.length; i++) {
            CaptainLoadout l = all[i];
            int bx = left + 14 + (i % 3) * 92;
            int by = top + 70 + (i / 3) * 22;
            Component label = Component.translatable("hearthstead.captain.loadout." + l.key());
            Ui2Button b = l.wireId() == current && held == current
                ? Ui2Button.primary(bx, by, 88, 18, label, () -> { })
                : Ui2Button.secondary(bx, by, 88, 18, label,
                    () -> CaptainClient.send(captain.getId(), CaptainPayloads.Action.LOADOUT, l.wireId(), ""));
            b.setTooltip(Tooltip.create(Component.translatable("hearthstead.captain.loadout." + l.key() + ".tip")));
            addRenderableWidget(b);
        }
        int cape = state == null ? -1 : state.cape();
        Component capeLabel = cape < 0 ? Component.translatable("hearthstead.captain.panel.cape_default")
            : Component.translatable("color.minecraft." + DyeColor.byId(cape).getName());
        addRenderableWidget(Ui2Button.secondary(left + 14, top + 186, 130, 18,
            Component.translatable("hearthstead.captain.panel.cape", capeLabel),
            () -> CaptainClient.send(captain.getId(), CaptainPayloads.Action.CAPE, cape >= 15 ? -1 : cape + 1, "")));
        boolean plume = state == null || state.plume();
        addRenderableWidget(Ui2Button.secondary(left + 150, top + 186, 136, 18,
            Component.translatable(plume ? "hearthstead.captain.panel.plume_on" : "hearthstead.captain.panel.plume_off"),
            () -> CaptainClient.send(captain.getId(), CaptainPayloads.Action.PLUME, plume ? 0 : 1, "")));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(g);
        BannerChrome.parchment(g, left, top, W, H);
        Ui2Type.title(g, font, Component.translatable("hearthstead.captain.panel.heading",
            captain.getSettlerName()), left + 14, top + 12, Ui2Palette.INK);
        Ui2Type.caption(g, font, Component.translatable("hearthstead.captain.panel.loadout"), left + 14, top + 58);
        Ui2Type.caption(g, font, Component.translatable("hearthstead.captain.panel.specials"), left + 14, top + 118);
        if (state == null || !state.hero()) {
            Ui2Type.body(g, font, Component.translatable("hearthstead.captain.panel.not_hero"), left + 14, top + 132);
        } else {
            int y = top + 130;
            int x = left + 14;
            CaptainSpecial[] all = CaptainSpecial.values();
            int shown = 0;
            for (int i = 0; i < all.length && i < state.cooldowns().length; i++) {
                int cd = state.cooldowns()[i];
                if (cd < 0) {
                    continue;
                }
                Component name = Component.translatable("hearthstead.captain.special." + all[i].id());
                Component when = all[i].oncePerFight()
                    ? Component.translatable(cd > 0 ? "hearthstead.captain.panel.spent" : "hearthstead.captain.panel.ready")
                    : cd > 0 ? Component.translatable("hearthstead.captain.panel.cooldown", (cd + 19) / 20)
                    : Component.translatable("hearthstead.captain.panel.ready");
                int col = shown % 2;
                int row = shown / 2;
                g.drawString(font, Component.empty().append(name).append(": ").append(when),
                    x + col * 140, y + row * 11, cd > 0 ? Ui2Palette.INK_MUTED : Ui2Palette.INK, false);
                shown++;
            }
            if (state.rearmTicks() > 0) {
                Ui2Type.caption(g, font, Component.translatable("hearthstead.captain.panel.rearming"),
                    left + 14, top + 172);
            }
            if (state.heldLoadout() < 0) {
                Ui2Type.caption(g, font, Component.translatable("hearthstead.captain.panel.no_kit"),
                    left + 150, top + 172);
            }
        }
        if (state != null && !state.feedback().isEmpty()) {
            Ui2Type.right(g, font, Component.translatable(state.feedback()), left + W - 14, top + 12,
                state.feedback().contains("refused") ? 0xFF9C3B2E : Ui2Palette.FOREST);
        }
        super.render(g, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Drawn in render(): the parchment sits over a dimmed world.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        if (state != null && state.prompt()) {
            CaptainClient.send(captain.getId(), CaptainPayloads.Action.DISMISS_PROMPT, 0, "");
        }
        super.onClose();
    }
}
