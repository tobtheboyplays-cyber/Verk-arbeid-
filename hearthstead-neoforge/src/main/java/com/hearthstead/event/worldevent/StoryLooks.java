package com.hearthstead.event.worldevent;

import com.hearthstead.entity.look.CharacterLooks;
import java.util.List;

/**
 * Fixed looks of the story cast: the costume id comes from the skins lane's
 * costume table ({@link CharacterLooks#COSTUME_KEYS}); a key that is not in
 * the table yet falls back to the settler's own clothes (never a missing
 * texture). Companions (family members) have their own fixed name, costume
 * and face.
 */
public final class StoryLooks {
    private StoryLooks() {
    }

    /** A family member who travels with a named visitor. */
    public record Companion(String name, String costume, int seed, double scale) {}

    public static final List<Companion> HOLLIN_FAMILY = List.of(
        new Companion("Tam Hollin", "neighbour_elder", 4416, 1.0D),
        new Companion("Pip Hollin", "neighbour_child", 709, 0.6D));
    public static final List<Companion> BRISK_FAMILY = List.of(
        new Companion("Nessa Brisk", "newcomer_woman", 9, 1.0D),
        new Companion("Wat Brisk", "newcomer_child", 1662, 0.6D));

    public static List<Companion> companions(StoryCharacter c) {
        return switch (c) {
            case HOLLINS -> HOLLIN_FAMILY;
            case BRISKS -> BRISK_FAMILY;
            default -> List.of();
        };
    }

    /** Costume id for a costume key, 0 when the table does not have it (yet). */
    public static int costumeId(String key) {
        if (key == null || key.isEmpty()) return CharacterLooks.COSTUME_NONE;
        String[] keys = CharacterLooks.COSTUME_KEYS;
        for (int i = 1; i < keys.length; i++) if (key.equals(keys[i])) return i;
        return CharacterLooks.COSTUME_NONE;
    }
}
