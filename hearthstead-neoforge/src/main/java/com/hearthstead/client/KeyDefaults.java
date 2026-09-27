package com.hearthstead.client;

import org.lwjgl.glfw.GLFW;

import java.util.List;

/**
 * The default keys of every Bannerhold key binding, in one pure table (owner,
 * 27 Sep: "2 keys + handbook"). The key classes read their defaults from here
 * and {@code KeyDefaultsTest} checks them against the vanilla 1.21.1 defaults
 * and the friend mods' real defaults (read from their jars, see
 * plan/state/keybinds.md), so a clash fails the build instead of a Sunday.
 */
public final class KeyDefaults {
    /** GLFW code of an unbound key. */
    public static final int UNBOUND = GLFW.GLFW_KEY_UNKNOWN;

    /** Command the melee troops: Knights, Spearmen, Longswordsmen, Healers. */
    public static final int COMMAND_MELEE = GLFW.GLFW_KEY_R;
    /** Command the ranged troops: Archers, Rune Mages. */
    public static final int COMMAND_RANGED = GLFW.GLFW_KEY_G;
    /** Open the Handbook: J for "journal" (Y is Xaero's Minimap settings key). */
    public static final int HANDBOOK = GLFW.GLFW_KEY_J;
    /** Finish: unbound, it then shares the melee command key. */
    public static final int FINISHER = UNBOUND;

    public record Binding(String name, int key) {
    }

    /** Every KeyMapping Bannerhold registers, in Controls order. */
    public static final List<Binding> ALL = List.of(
        new Binding("key.hearthstead.command_melee", COMMAND_MELEE),
        new Binding("key.hearthstead.command_ranged", COMMAND_RANGED),
        new Binding("key.hearthstead.handbook", HANDBOOK),
        new Binding("key.hearthstead.finisher", FINISHER));

    private KeyDefaults() {
    }
}
