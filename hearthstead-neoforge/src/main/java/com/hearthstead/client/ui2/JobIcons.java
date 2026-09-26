package com.hearthstead.client.ui2;

import com.hearthstead.entity.Profession;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.Map;

/**
 * Hand-authored 16x16 job icons, one per {@link Profession}
 * ({@code textures/gui/job/<key>.png}, drawn pixel by pixel in
 * {@code tools/ui/job_icons.py}). Every Profession has one, including the
 * battle roles and NONE ("unassigned").
 *
 * <p>Draw them at 16 or 8 GUI px (or any size whose physical pixels are a
 * whole multiple of 16) and they stay pixel-perfect at every GUI scale.
 */
public final class JobIcons {
    public static final int SIZE = 16;
    private static final Map<Profession, ResourceLocation> TEXTURES = new EnumMap<>(Profession.class);

    private JobIcons() {
    }

    public static ResourceLocation texture(Profession profession) {
        Profession p = profession == null ? Profession.NONE : profession;
        return TEXTURES.computeIfAbsent(p, key -> ResourceLocation.fromNamespaceAndPath(
            "hearthstead", "textures/gui/job/" + key.key() + ".png"));
    }

    /** Draws the icon for {@code profession} at (x, y), {@code size} GUI px square. */
    public static void draw(GuiGraphics g, Profession profession, int x, int y, int size) {
        g.blit(texture(profession), x, y, size, size, 0.0F, 0.0F, SIZE, SIZE, SIZE, SIZE);
    }

    /** Wire-id convenience for roster and marker rows. */
    public static void draw(GuiGraphics g, int professionId, int x, int y, int size) {
        draw(g, Profession.byId(professionId), x, y, size);
    }
}
