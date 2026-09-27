package com.hearthstead.heraldry;

import java.util.Collection;
import java.util.Locale;

/**
 * The kingdom (settlement) name a player may choose in the Banner designer:
 * 3 to 24 characters after trimming, only letters A-Z, spaces, apostrophes
 * and hyphens, runs of spaces folded to one, and unique on the server
 * regardless of case. It is stored in the settlement's one existing name
 * field; nothing keeps a second copy.
 */
public final class KingdomName {
    public static final int MIN = 3;
    public static final int MAX = 24;

    /** WAIT: the per-player rename cooldown (never returned by {@link #validate}). */
    public enum Problem { NONE, TOO_SHORT, TOO_LONG, BAD_CHARACTERS, TAKEN, WAIT }

    private KingdomName() {
    }

    /** Trimmed, with runs of whitespace folded to single spaces. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replaceAll("\\s+", " ");
    }

    /** Length and character rules only (the client checks these as you type). */
    public static Problem checkShape(String raw) {
        String name = normalize(raw);
        if (name.length() < MIN) {
            return Problem.TOO_SHORT;
        }
        if (name.length() > MAX) {
            return Problem.TOO_LONG;
        }
        boolean letter = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean isLetter = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
            letter |= isLetter;
            if (!isLetter && c != ' ' && c != '\'' && c != '-') {
                return Problem.BAD_CHARACTERS;
            }
        }
        return letter ? Problem.NONE : Problem.BAD_CHARACTERS;
    }

    /**
     * Full server check. {@code otherNames} are the names of every OTHER
     * settlement on the server (keeping your own name is never "taken").
     */
    public static Problem validate(String raw, Collection<String> otherNames) {
        Problem shape = checkShape(raw);
        if (shape != Problem.NONE) {
            return shape;
        }
        String key = normalize(raw).toLowerCase(Locale.ROOT);
        for (String other : otherNames) {
            if (other != null && normalize(other).toLowerCase(Locale.ROOT).equals(key)) {
                return Problem.TAKEN;
            }
        }
        return Problem.NONE;
    }

    public static String messageKey(Problem problem) {
        return "hearthstead.heraldry.name." + problem.name().toLowerCase(Locale.ROOT);
    }
}
