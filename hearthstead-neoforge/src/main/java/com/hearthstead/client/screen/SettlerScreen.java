package com.hearthstead.client.screen;

import com.hearthstead.Hearthstead;
import com.hearthstead.entity.Profession;
import com.hearthstead.entity.SettlerEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;

/**
 * Concise settler card: profession, current doing, and three live need bars,
 * read directly from synced entity data every frame.
 */
public class SettlerScreen extends Screen {
    private static final ResourceLocation TEXTURE =
        Hearthstead.id("textures/gui/settler_card.png");

    private static final int CARD_W = 176;
    private static final int CARD_H = 120;
    private static final int INK = 0xFF3F3024;
    private static final int INK_SOFT = 0xFF69573C;

    private static final int BAR_W = 96;
    private static final int BAR_H = 7;

    protected final SettlerEntity settler;
    private int left;
    private int top;

    public SettlerScreen(SettlerEntity settler) {
        super(Component.literal(settler.getSettlerName()));
        this.settler = settler;
    }

    @Override
    protected void init() {
        left = (width - CARD_W) / 2;
        top = (height - CARD_H) / 2;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.blit(TEXTURE, left, top, 0, 0, CARD_W, CARD_H);

        Profession profession = settler.getProfession();

        // Name centered in the header band.
        graphics.drawString(font, title, left + (CARD_W - font.width(title)) / 2,
            top + 9, 0xFFEFE0BD, false);

        // Profession and activity lines.
        Component job = profession.employed()
            ? profession.displayName()
            : Component.translatable("hearthstead.profession.none");
        graphics.drawString(font, job, left + 14, top + 26,
            0xFF000000 | profession.color(), false);
        Component doing = Component.translatable("hearthstead.gui.doing",
            settler.getActivity().displayName());
        graphics.drawString(font, doing, left + 14, top + 38, INK_SOFT, false);

        // Need bars.
        drawBar(graphics, 0, "hearthstead.gui.hunger", settler.getHunger(), 0xFFC9A83C);
        drawBar(graphics, 1, "hearthstead.gui.energy", settler.getEnergy(), 0xFF6A93B0);
        float morale = settler.getMorale();
        drawBar(graphics, 2, "hearthstead.gui.morale", morale, moraleColor(morale));
    }

    private void drawBar(GuiGraphics graphics, int row, String labelKey, float value,
                         int color) {
        int y = top + 54 + row * 19;
        int x = left + 14;
        graphics.drawString(font, Component.translatable(labelKey), x, y, INK, false);
        int barX = x + 52;
        int barY = y - 1;
        // Track from the sheet strip; fill as a flat colored inset.
        graphics.blit(TEXTURE, barX, barY, 0, 120, BAR_W, BAR_H + 2);
        int fill = (int) (Mth.clamp(value, 0, 100) * (BAR_W - 2) / 100.0F);
        graphics.fill(barX + 1, barY + 1, barX + 1 + fill, barY + BAR_H + 1, color);
        String pct = String.valueOf((int) value);
        graphics.drawString(font, pct, barX + BAR_W + 4, y, INK_SOFT, false);
    }

    private static int moraleColor(float morale) {
        if (morale < 25) {
            return 0xFFA03535;
        }
        if (morale < 50) {
            return 0xFFC07A35;
        }
        if (morale < 75) {
            return 0xFFC9A83C;
        }
        return 0xFF5B8A4A;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void tick() {
        if (settler.isRemoved()
            || minecraft.player.distanceToSqr(settler) > 64) {
            onClose();
        }
    }
}
