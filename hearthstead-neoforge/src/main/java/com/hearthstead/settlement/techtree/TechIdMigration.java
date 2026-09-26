package com.hearthstead.settlement.techtree;

import java.util.Locale;
import java.util.Map;

/**
 * Maps every name a node was ever saved or referenced under to its v3 id.
 *
 * <p>Development saves store ids (never enum ordinals), but older builds,
 * commands, QA fixtures and design drafts used other spellings: the enum
 * constant names ({@code FIRST_WATCH}), the folded {@code shelter} seal, and
 * design-draft ids that were merged away before v3. Everything that reads a
 * node id from outside (NBT, packets, commands) goes through
 * {@link #canonical}.
 */
public final class TechIdMigration {
    /** Old id -> v3 id. Never remove an entry; saves may still carry it. */
    public static final Map<String, String> ALIASES = Map.ofEntries(
        // v3: the separate Banner Raised seal folds into the Settlement Charter.
        Map.entry("shelter", "settlement_charter"),
        // v2 design drafts merged or renamed before shipping.
        Map.entry("varied_table", "kitchen_and_hall"),
        Map.entry("shieldbearers", "veteran_techniques"),
        Map.entry("arcane_tower", "high_runes"),
        Map.entry("commanders_voice", "commanders_horn"),
        Map.entry("captains_voice", "commanders_horn"),
        Map.entry("village_charter", "first_raid_aftermath"),
        Map.entry("banner_raised", "settlement_charter"));

    private TechIdMigration() {
    }

    /**
     * The v3 id for any legacy spelling: lower-cases enum constant names
     * ({@code HALL_AND_LEARNING} -> {@code hall_and_learning}), strips a
     * {@code hearthstead:} namespace, then applies {@link #ALIASES}.
     */
    public static String canonical(String raw) {
        if (raw == null) {
            return null;
        }
        String id = raw.trim();
        if (id.startsWith("hearthstead:")) {
            id = id.substring("hearthstead:".length());
        }
        if (id.startsWith("node:") || id.startsWith("upgrade:")) {
            id = id.substring(id.indexOf(':') + 1);
        }
        id = id.toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(id, id);
    }
}
