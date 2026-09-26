package com.hearthstead.entity.look;

/**
 * A raid captain's persistent look. Everything but the colour scheme comes
 * from the captain's identity seed (CRC32 of the saga first name, which
 * survives every return), so a returning captain is recognisable; the
 * earned epithet only recolours cloth and war paint. Mirrors
 * tools/skins/look_raiders.captain_look_from_seed.
 *
 * @param scheme  index into {@link CharacterLooks#SCHEMES}
 * @param helm    0..4 helm style
 * @param paint   1..5 war-paint pattern
 * @param scar    0..4 (0 = none)
 * @param skin    0..3 captain skin tone
 * @param hair    0..8 hair colour
 */
public record CaptainLook(int scheme, int helm, int paint, int scar, int skin, int hair) {
    public static final int HELMS = 5;
    public static final int PAINTS = 6;
}
