package com.hearthstead.sound;

import java.util.Locale;
import java.util.Map;

/**
 * Which voice a character speaks with, and at what pitch (sound pass; owner decisions 26 Sep:
 * "the voice must match the look", then "use the villager sounds, a bit altered into babble, so
 * it doesn't get annoying; brutes only dark grunting").
 *
 * <p>Voices are Minecraft's own villager / wandering-trader sounds, referenced from our
 * sounds.json as vanilla sound paths (nothing of Mojang's is copied into the jar) and altered only
 * at playback: pitch per character, low volume, a soft cadence. Brutes never babble; they get
 * one dark grunt ({@code voice.brute.grunt}, our own file) at the line start and sometimes one mid-line.
 *
 * <p>Playback rules: sound only in the first ~1.5 s of a line ({@link #MAX_SYLLABLES} at most),
 * one "hmm" every {@link #MIN_GAP_MS}-{@link #MAX_GAP_MS} ms, pause on punctuation, silence while
 * the text finishes; call at volume 0.75 (1.0 when stressed), times
 * {@code HearthsteadClientConfig.voiceVolume()}. sounds.json already sets each id to about
 * -27 LUFS at volume 1.
 *
 * <p>Inputs mirror the skins lane's {@code VoiceProfile} (sex 0 masc / 1 fem, age 0 young /
 * 1 adult / 2 old, build 0 slight / 1 average / 2 big, a stable pitch seed, an optional epithet
 * key). Each bank's pitch window keeps the character inside what still sounds like it.
 */
public final class VoiceBanks {
    /**
     * A bank: the sound set it plays ({@code npc}, {@code trader} or {@code brute}) and the
     * playback-pitch window its speakers may use.
     */
    public record Bank(String id, String set, float minPitch, float maxPitch) {
        /** The id for a tone: ambient (normal line), yes, no, trade, celebrate. Brutes always grunt. */
        public String voice(String tone) {
            if ("brute".equals(set)) return "voice.brute.grunt";
            String t = switch (tone == null ? "" : tone.toLowerCase(Locale.ROOT)) {
                case "yes", "agree", "mmhm" -> "yes";
                case "no", "angry", "refuse" -> "no";
                case "trade", "barter", "hmm", "think" -> "trade";
                case "celebrate", "laugh", "happy", "surprised" -> "celebrate";
                case "farewell" -> "yes";
                default -> "ambient";
            };
            return "voice." + set + "." + t;
        }

        /** Normal-line syllable id. */
        public String babble() {
            return voice("ambient");
        }

        /** Tone-tag id (laugh, hmm, angry, surprised, agree, farewell...). */
        public String emote(String emote) {
            return voice(emote);
        }
    }

    public static final Map<String, Bank> BANKS = Map.ofEntries(
        bank("m_young", "npc", 0.98F, 1.06F), bank("m_adult", "npc", 0.88F, 0.96F),
        bank("m_old", "npc", 0.78F, 0.86F), bank("f_young", "npc", 1.22F, 1.32F),
        bank("f_adult", "npc", 1.10F, 1.20F), bank("f_old", "npc", 1.02F, 1.10F),
        bank("goblin", "npc", 1.36F, 1.48F), bank("captain", "npc", 0.72F, 0.80F),
        bank("envoy", "npc", 0.86F, 0.94F), bank("minstrel", "npc", 1.00F, 1.08F),
        bank("peddler", "trader", 0.95F, 1.06F), bank("peddler_f", "trader", 1.12F, 1.22F),
        bank("brute", "brute", 0.92F, 1.00F));

    public static final int MAX_SYLLABLES = 6;
    public static final int MIN_GAP_MS = 180;
    public static final int MAX_GAP_MS = 260;
    /** Sound stops this long after a line starts even if syllables remain. */
    public static final int MAX_BABBLE_MS = 1500;

    private VoiceBanks() {
    }

    private static Map.Entry<String, Bank> bank(String id, String set, float lo, float hi) {
        return Map.entry(id, new Bank(id, set, lo, hi));
    }

    /**
     * The bank for a character. {@code archetype} is the skins lane's Archetype name (any case,
     * e.g. SETTLER, BRUTE, TOLL_BRUTE, GOBLIN, RAID_CAPTAIN, PEDDLER, ENVOY, MINSTREL) or the
     * conversation lane's short names (man, woman, old, brute, goblin, captain, peddler...).
     */
    public static Bank bankFor(String archetype, int sex, int age, int build) {
        String a = archetype == null ? "" : archetype.toLowerCase(Locale.ROOT);
        switch (a) {
            case "brute", "toll_brute", "toll_chief", "brute_chief" -> { return BANKS.get("brute"); }
            case "road_bandit" -> { return BANKS.get(build >= 2 ? "brute" : "m_adult"); }
            case "goblin" -> { return BANKS.get("goblin"); }
            case "captain", "raid_captain", "saga_captain", "hero_captain" -> {
                return BANKS.get(sex == 1 ? "f_adult" : "captain");
            }
            case "peddler", "merchant", "caravan_master" -> { return BANKS.get(sex == 1 ? "peddler_f" : "peddler"); }
            case "envoy", "envoy_attendant" -> { return BANKS.get(sex == 1 ? "f_adult" : "envoy"); }
            case "minstrel" -> { return BANKS.get(sex == 1 ? "f_young" : "minstrel"); }
            case "man" -> { return BANKS.get(age == 0 ? "m_young" : age >= 2 ? "m_old" : "m_adult"); }
            case "woman" -> { return BANKS.get(age == 0 ? "f_young" : age >= 2 ? "f_old" : "f_adult"); }
            case "old" -> { return BANKS.get(sex == 1 ? "f_old" : "m_old"); }
            default -> { }
        }
        boolean fem = sex == 1;
        String id = (fem ? "f_" : "m_") + (age == 0 ? "young" : age >= 2 ? "old" : "adult");
        return BANKS.get(id);
    }

    /**
     * Stable per-character base pitch inside the bank's window. Build shades it (big = lower,
     * slight = higher); an "ashen" or "reaper" epithet sits at the rough, low end.
     */
    public static float basePitch(Bank bank, long pitchSeed, int build, String epithetKey) {
        long h = pitchSeed * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 29);
        float u = (float) ((h >>> 11) & ((1L << 24) - 1)) / (float) (1 << 24);   // 0..1
        float shade = build >= 2 ? -0.25F : build == 0 ? 0.2F : 0.0F;
        if ("ashen".equals(epithetKey) || "reaper".equals(epithetKey)) shade -= 0.3F;
        float t = Math.max(0.0F, Math.min(1.0F, u * 0.8F + 0.1F + shade));
        return bank.minPitch() + t * (bank.maxPitch() - bank.minPitch());
    }

    /** Per-syllable pitch: +-3 % jitter around the base, clamped to the bank window. */
    public static float syllablePitch(Bank bank, float base, float random01) {
        float p = base * (0.97F + 0.06F * random01);
        return Math.max(bank.minPitch(), Math.min(bank.maxPitch(), p));
    }
}
