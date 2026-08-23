package com.hearthstead.settlement;

import net.minecraft.util.RandomSource;

import java.util.Set;

/** Original medieval-Norse flavored names for settlers and settlements. */
public final class SettlerNames {
    private static final String[] SETTLER_NAMES = {
        "Aldric", "Sigrun", "Bramwell", "Eira", "Torvald", "Maren",
        "Godwin", "Astrid", "Halvor", "Ingrid", "Cedric", "Runa",
        "Osmund", "Solveig", "Wilmot", "Thyra", "Baldric", "Liv",
        "Eamon", "Gudrun", "Hartwin", "Signe", "Rowan", "Alfhild",
        "Dunstan", "Embla", "Leofric", "Yrsa", "Garrick", "Tove",
        "Ansgar", "Brenna", "Colwyn", "Dagny", "Edwin", "Freydis",
        "Gislebert", "Hedda", "Isolde", "Jorund", "Kettil", "Magnhild",
    };

    private static final String[] SETTLEMENT_FIRST = {
        "Ash", "Elm", "Thorn", "Birch", "Stone", "Fen", "Harrow",
        "Wolf", "Raven", "Ember", "Frost", "Heather", "Oak", "Mill",
    };

    private static final String[] SETTLEMENT_SECOND = {
        "ford", "wick", "stead", "holm", "dale", "mere", "field",
        "brook", "gate", "haven", "moor", "bridge",
    };

    public static String pickSettlerName(RandomSource random, Set<String> taken) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String name = SETTLER_NAMES[random.nextInt(SETTLER_NAMES.length)];
            if (!taken.contains(name)) {
                return name;
            }
        }
        // Settlement is crowded with namesakes; add a byname.
        String base = SETTLER_NAMES[random.nextInt(SETTLER_NAMES.length)];
        return base + " the Younger";
    }

    public static String pickSettlementName(RandomSource random) {
        return SETTLEMENT_FIRST[random.nextInt(SETTLEMENT_FIRST.length)]
            + SETTLEMENT_SECOND[random.nextInt(SETTLEMENT_SECOND.length)];
    }

    private SettlerNames() {
    }
}
