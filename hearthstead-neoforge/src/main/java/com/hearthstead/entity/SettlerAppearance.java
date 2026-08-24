package com.hearthstead.entity;

/**
 * Modular settler look: skin tone x hair style x hair color x face x
 * clothing, decoded from one synced/saved integer seed. Pure and
 * deterministic -- the same seed always decodes to the same appearance, on
 * either side and across restarts, without needing to sync five fields
 * separately.
 */
public record SettlerAppearance(int skinTone, int hairStyle, int hairColor,
                                 int faceVariant, int clothingVariant) {
    public static final int SKIN_COUNT = 4;
    public static final int HAIR_STYLE_COUNT = 4;
    public static final int HAIR_COLOR_COUNT = 4;
    public static final int FACE_COUNT = 3;
    public static final int CLOTHING_COUNT = 4;

    public static SettlerAppearance decode(int seed) {
        int remaining = seed;
        int skinTone = Math.floorMod(remaining, SKIN_COUNT);
        remaining = Math.floorDiv(remaining, SKIN_COUNT);
        int hairStyle = Math.floorMod(remaining, HAIR_STYLE_COUNT);
        remaining = Math.floorDiv(remaining, HAIR_STYLE_COUNT);
        int hairColor = Math.floorMod(remaining, HAIR_COLOR_COUNT);
        remaining = Math.floorDiv(remaining, HAIR_COLOR_COUNT);
        int faceVariant = Math.floorMod(remaining, FACE_COUNT);
        remaining = Math.floorDiv(remaining, FACE_COUNT);
        int clothingVariant = Math.floorMod(remaining, CLOTHING_COUNT);
        return new SettlerAppearance(skinTone, hairStyle, hairColor, faceVariant, clothingVariant);
    }
}
