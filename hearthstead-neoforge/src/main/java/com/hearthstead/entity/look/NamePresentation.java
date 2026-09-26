package com.hearthstead.entity.look;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Masculine/feminine presentation implied by the mod's own name tables, so a
 * "Sigrun" never grows a beard and her voice matches. Unknown names return
 * -1 and the genome's own seed draw decides. Mirrors SettlerNames (which
 * alternates masculine, feminine) and the saga captain names (masculine).
 */
final class NamePresentation {
    private static final String[] SETTLER_NAMES = {
        "Aldric", "Sigrun", "Bramwell", "Eira", "Torvald", "Maren",
        "Godwin", "Astrid", "Halvor", "Ingrid", "Cedric", "Runa",
        "Osmund", "Solveig", "Wilmot", "Thyra", "Baldric", "Liv",
        "Eamon", "Gudrun", "Hartwin", "Signe", "Rowan", "Alfhild",
        "Dunstan", "Embla", "Leofric", "Yrsa", "Garrick", "Tove",
        "Ansgar", "Brenna", "Colwyn", "Dagny", "Edwin", "Freydis",
        "Gislebert", "Hedda", "Isolde", "Jorund", "Kettil", "Magnhild",
    };
    private static final String[] CAPTAIN_NAMES = {
        "Grimr", "Alrik", "Sveinung", "Bodvar", "Eirik", "Tostig", "Vidkun", "Haakon",
        "Ottar", "Skjold", "Runolf", "Asger", "Dagfinn", "Ingjald", "Skarde",
    };
    private static final Map<String, Integer> TABLE = new HashMap<>();

    static {
        for (int i = 0; i < SETTLER_NAMES.length; i++) {
            TABLE.put(SETTLER_NAMES[i].toLowerCase(Locale.ROOT), i % 2);
        }
        for (String n : CAPTAIN_NAMES) {
            TABLE.putIfAbsent(n.toLowerCase(Locale.ROOT), 0);
        }
    }

    static int of(String firstName) {
        Integer v = TABLE.get(firstName.toLowerCase(Locale.ROOT));
        return v == null ? -1 : v;
    }

    private NamePresentation() {
    }
}
