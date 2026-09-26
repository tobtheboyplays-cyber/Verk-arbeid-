package com.hearthstead.entity.look;

/**
 * A settler's (or SettlerEntity visitor's) whole look, decoded from the one
 * synced and saved appearance seed ({@code SettlerEntity#getAppearanceSeed},
 * NBT key "Appearance"; pre-VISUAL-1 saves fall back to UUID.hashCode()).
 *
 * <p>Pure, deterministic and COMMON code: the same seed gives the same
 * genome on the dedicated server, on every client and after every reload,
 * so nothing but the seed ever needs syncing or saving. Bit-for-bit mirror
 * of tools/skins/genome.py (the contact sheets are rendered from that file;
 * CharacterGenomeTest pins the golden values it prints).
 *
 * <p>The voice-relevant traits ({@link #sex}, {@link #age}, {@link #build})
 * are part of the genome on purpose, so the voice lane picks a voice that
 * matches the face (see {@link CharacterLooks#voiceProfile}).
 *
 * @param sex        0 = masculine, 1 = feminine presentation
 * @param age        0 = young, 1 = adult, 2 = old
 * @param build      0 = slight, 1 = average, 2 = big
 * @param skinTone   0..7, porcelain to very deep
 * @param hairStyle  0..11 (see {@link #HAIR_STYLE_NAMES})
 * @param hairColor  0..8 (7 = grey, 8 = white; mostly from age)
 * @param beard      0..5 (always 0 for sex 1)
 * @param eyes       0..5 eye colour
 * @param brows      0..2 brow shape
 * @param mark       0..7 face mark (freckles, scars, eyepatch...)
 * @param clothing   0..7 everyday clothing palette
 * @param nose       0..3 nose shape (default, broad, hooked, button)
 */
public record CharacterGenome(int sex, int age, int build, int skinTone, int hairStyle,
                              int hairColor, int beard, int eyes, int brows, int mark,
                              int clothing, int nose) {
    public static final int SKIN_COUNT = 8;
    public static final int HAIR_STYLE_COUNT = 12;
    public static final int HAIR_COLOR_COUNT = 9;
    public static final int BEARD_COUNT = 6;
    public static final int EYES_COUNT = 6;
    public static final int BROWS_COUNT = 3;
    public static final int MARK_COUNT = 8;
    public static final int CLOTHING_COUNT = 8;
    public static final int NOSE_COUNT = 4;

    public static final String[] HAIR_STYLE_NAMES = {"bald", "buzzed", "crop", "swept", "shaggy",
        "shoulder", "long", "braid", "twin_braids", "bun", "tail", "receding"};

    private static final int[] AGE_W = {3, 5, 2};
    private static final int[] BUILD_W = {3, 5, 2};
    private static final int[][] HAIR_COLOR_W = {
        {1, 3, 3, 2, 2, 3, 3}, {6, 8, 6, 2, 1, 2, 1}, {9, 6, 2, 1, 0, 0, 0}};
    private static final int[][][] HAIR_STYLE_W = {
        {{0, 3, 4, 3, 3, 2, 1, 1, 0, 0, 2, 0},
         {1, 3, 4, 3, 2, 2, 1, 1, 0, 1, 2, 1},
         {4, 2, 3, 2, 1, 1, 1, 1, 0, 0, 1, 4}},
        {{0, 1, 1, 2, 2, 3, 4, 3, 3, 2, 3, 0},
         {0, 1, 1, 2, 2, 3, 3, 3, 2, 3, 3, 0},
         {0, 0, 1, 1, 1, 2, 2, 2, 1, 5, 2, 0}}};
    private static final int[][] BEARD_W = {{5, 3, 1, 1, 0, 0}, {3, 2, 2, 3, 3, 1}, {2, 1, 2, 2, 3, 3}};
    private static final int[][] EYES_W = {{1, 2, 2, 2, 3, 2}, {3, 3, 2, 1, 1, 1}, {5, 3, 1, 0, 0, 0}};
    private static final int[][] BROWS_W = {{1, 3, 3}, {4, 3, 1}};
    private static final int[][] MARK_W = {{10, 5, 2, 2, 3, 2, 2, 1}, {10, 1, 2, 2, 2, 2, 2, 1}};

    static final int GENOME_SALT = 0x5EED1E55;

    /**
     * @param seed               the synced appearance seed
     * @param presentationHint   -1 unknown, else 0/1 (from the settler's name,
     *                           see {@link #presentationOf}) -- overrides only
     *                           the sex draw; every later draw stays in place
     */
    public static CharacterGenome decode(int seed, int presentationHint) {
        Stream s = new Stream(seed, GENOME_SALT);
        int sexRoll = s.pick(2);
        int sex = presentationHint == 0 || presentationHint == 1 ? presentationHint : sexRoll;
        int age = s.weighted(AGE_W);
        int build = s.weighted(BUILD_W);
        int tone = s.pick(SKIN_COUNT);
        int group = tone <= 2 ? 0 : tone <= 4 ? 1 : 2;
        int color = s.weighted(HAIR_COLOR_W[group]);
        int grey = s.pick(10);
        if (age == 2 && grey < 7) {
            color = grey < 4 ? 7 : 8;
        } else if (age == 1 && grey == 0) {
            color = 7;
        }
        int style = s.weighted(HAIR_STYLE_W[sex][age]);
        int beardRoll = s.weighted(BEARD_W[age]);
        int beard = sex == 0 ? beardRoll : 0;
        int eyes = s.weighted(EYES_W[group]);
        int brows = s.weighted(BROWS_W[sex]);
        int mark = s.weighted(MARK_W[group == 0 ? 0 : 1]);
        int clothing = s.pick(CLOTHING_COUNT);
        int nose = s.pick(NOSE_COUNT);
        return new CharacterGenome(sex, age, build, tone, style, color, beard, eyes, brows, mark, clothing, nose);
    }

    public static CharacterGenome decode(int seed) {
        return decode(seed, -1);
    }

    /** Old saves without an "Appearance" key: the same fallback SettlerEntity uses. */
    public static int seedFor(java.util.UUID uuid) {
        return uuid.hashCode();
    }

    /**
     * Presentation implied by a settler name from {@code SettlerNames}
     * (that list alternates masculine/feminine names), -1 when unknown.
     * Accepts decorated names ("[!] Sigrun, waiting", "Liv the Younger").
     */
    public static int presentationOf(String name) {
        if (name == null) {
            return -1;
        }
        String s = name.strip();
        if (s.startsWith("[!]")) {
            s = s.substring(3).strip();
        }
        int end = 0;
        while (end < s.length() && Character.isLetter(s.charAt(end))) {
            end++;
        }
        if (end == 0) {
            return -1;
        }
        return NamePresentation.of(s.substring(0, end));
    }

    public int[] toArray() {
        return new int[] {sex, age, build, skinTone, hairStyle, hairColor, beard, eyes, brows, mark, clothing, nose};
    }

    public static CharacterGenome fromArray(int[] a) {
        return new CharacterGenome(a[0], a[1], a[2], a[3], a[4], a[5], a[6], a[7], a[8], a[9], a[10], a[11]);
    }

    /** Unsigned 32-bit murmur3 finaliser stream (mirrors genome.Stream). */
    static final class Stream {
        private int state;

        Stream(int seed, int salt) {
            this.state = fmix(seed ^ salt);
        }

        static int fmix(int x) {
            x ^= x >>> 16;
            x *= 0x85EBCA6B;
            x ^= x >>> 13;
            x *= 0xC2B2AE35;
            x ^= x >>> 16;
            return x;
        }

        int next() {
            state += 0x9E3779B9;
            return fmix(state);
        }

        int pick(int bound) {
            return Integer.remainderUnsigned(next(), bound);
        }

        int weighted(int[] weights) {
            int total = 0;
            for (int w : weights) {
                total += w;
            }
            int r = Integer.remainderUnsigned(next(), total);
            for (int i = 0; i < weights.length; i++) {
                if (r < weights[i]) {
                    return i;
                }
                r -= weights[i];
            }
            throw new AssertionError();
        }
    }
}
