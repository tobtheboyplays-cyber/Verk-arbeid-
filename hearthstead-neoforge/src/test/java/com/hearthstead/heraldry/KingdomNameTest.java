package com.hearthstead.heraldry;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Kingdom names chosen in the Banner designer: shape rules and server-wide uniqueness. */
class KingdomNameTest {
    @Test
    void lengthAndCharacterRules() {
        assertEquals(KingdomName.Problem.NONE, KingdomName.checkShape("Stonebridge"));
        assertEquals(KingdomName.Problem.NONE, KingdomName.checkShape("  Old  Harrow-by-the-Sea  "));
        assertEquals(KingdomName.Problem.NONE, KingdomName.checkShape("King's Rest"));
        assertEquals(KingdomName.Problem.NONE, KingdomName.checkShape("Ash"));
        assertEquals(KingdomName.Problem.TOO_SHORT, KingdomName.checkShape("Ab"));
        assertEquals(KingdomName.Problem.TOO_SHORT, KingdomName.checkShape("   a  "));
        assertEquals(KingdomName.Problem.TOO_SHORT, KingdomName.checkShape(null));
        assertEquals(KingdomName.Problem.NONE, KingdomName.checkShape("Abcdefghijklmnopqrstuvwx"));
        assertEquals(KingdomName.Problem.TOO_LONG, KingdomName.checkShape("Abcdefghijklmnopqrstuvwxy"));
        assertEquals(KingdomName.Problem.BAD_CHARACTERS, KingdomName.checkShape("Hold#1"));
        assertEquals(KingdomName.Problem.BAD_CHARACTERS, KingdomName.checkShape("Castle 2"));
        assertEquals(KingdomName.Problem.BAD_CHARACTERS, KingdomName.checkShape("§cRed"));
        assertEquals(KingdomName.Problem.BAD_CHARACTERS, KingdomName.checkShape("--- ''"), "needs a letter");
    }

    @Test
    void normalizeTrimsAndFoldsSpaces() {
        assertEquals("Old Harrow", KingdomName.normalize("  Old \t  Harrow "));
        assertEquals("", KingdomName.normalize(null));
    }

    @Test
    void namesAreUniqueIgnoringCaseAndSpacing() {
        List<String> others = List.of("Stonebridge", "Elm Field");
        assertEquals(KingdomName.Problem.TAKEN, KingdomName.validate("stonebridge", others));
        assertEquals(KingdomName.Problem.TAKEN, KingdomName.validate("  ELM   FIELD ", others));
        assertEquals(KingdomName.Problem.NONE, KingdomName.validate("Elmfield", others));
        assertEquals(KingdomName.Problem.NONE, KingdomName.validate("Oakhold", List.of()));
        assertEquals(KingdomName.Problem.TOO_SHORT, KingdomName.validate("St", others),
            "shape problems are reported before uniqueness");
    }
}
