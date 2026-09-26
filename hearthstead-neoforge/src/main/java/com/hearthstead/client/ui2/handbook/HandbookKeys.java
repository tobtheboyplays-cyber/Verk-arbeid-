package com.hearthstead.client.ui2.handbook;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves a page's key chips to the player's REAL bindings, so a rebound
 * key reads correctly (e.g. {@code [V] Finish} after moving it off R).
 */
public final class HandbookKeys {
    private HandbookKeys() {
    }

    public static KeyMapping find(String name) {
        if (name == null) return null;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options == null) return null;
        for (KeyMapping k : mc.options.keyMappings) {
            if (k.getName().equals(name)) return k;
        }
        return null;
    }

    /** The key's display label, following the fallback when it is unbound. */
    public static String label(String name, String fallback) {
        KeyMapping k = find(name);
        if ((k == null || k.isUnbound()) && fallback != null) {
            KeyMapping f = find(fallback);
            if (f != null && !f.isUnbound()) k = f;
        }
        if (k == null) return "?";
        if (k.isUnbound()) return Component.translatable("hearthstead.guide.ui.unbound").getString();
        return k.getTranslatedKeyMessage().getString();
    }

    public static HandbookPageLayout.Chip chip(HandbookBook.KeyChip spec) {
        List<String> caps = new ArrayList<>();
        if (spec.modifier() != null) caps.add(label(spec.modifier(), null));
        caps.add(label(spec.key(), spec.fallback()));
        String prefix = spec.hold() ? Component.translatable("hearthstead.guide.ui.hold").getString() : "";
        String action = spec.actionKey() == null ? "" : Component.translatable(spec.actionKey()).getString();
        return new HandbookPageLayout.Chip(List.copyOf(caps), prefix, action);
    }
}
