package com.hearthstead.entity.look;

/**
 * Voice-relevant traits of a character, derived from the same data as its
 * look so the voice always matches the face. Deterministic on both sides.
 *
 * @param archetype  what the character is
 * @param sex        0 masculine, 1 feminine
 * @param age        0 young, 1 adult, 2 old
 * @param build      0 slight, 1 average, 2 big
 * @param pitchSeed  stable per character (vary pitch/timbre inside a bank)
 * @param epithetKey saga-captain colour/epithet key ("ashen", "red", "torch",
 *                   "grain", "chain", "reaper", "woad", "plain"), "" otherwise
 */
public record VoiceProfile(Archetype archetype, int sex, int age, int build, int pitchSeed,
                           String epithetKey) {
}
