package com.hearthstead.client.conversation;

import com.hearthstead.client.sound.HsSound;
import com.hearthstead.entity.RaiderEntity;
import com.hearthstead.entity.SettlerEntity;
import com.hearthstead.sound.VoiceBanks;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.Locale;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.WanderingTrader;

/**
 * Sims-style gibberish voice for a line being typed out (owner request):
 * one babble syllable every 90-160 ms with a random variant and +-6 % pitch
 * jitter around the speaker's own base pitch (from its UUID), a pause on
 * {@code , . ? !}, silence when the line is done or skipped.
 *
 * <p>Sound ids are {@code hearthstead:voice.<archetype>.babble} and
 * {@code hearthstead:voice.<archetype>.<emote>} (the sound lane's banks);
 * until those files exist a quiet vanilla villager murmur stands in, so the
 * wiring is live now. Syllable times also drive the talking-head layer
 * ({@link TalkingHead}).
 */
public final class ConversationVoice {
    private static final RandomSource RANDOM = RandomSource.create();

    static final class Voice {
        final VoiceBanks.Bank bank;
        final float basePitch;
        long nextSyllableNanos;
        long lastSyllableNanos;
        boolean emphatic;
        boolean question;
        int lastChars;
        int line = -1;
        long lineStartNanos;
        int syllables;
        int budget;
        long lastRevealNanos;
        int voicedLine = -1;
        @Nullable VoiceLines.Clip clip;
        long clipStartNanos;
        float lastLevel;
        @Nullable net.minecraft.client.resources.sounds.SoundInstance playing;
        boolean midGrunt;
        final java.util.Map<Integer, String> tones = new java.util.HashMap<>();

        Voice(VoiceBanks.Bank bank, float basePitch) {
            this.bank = bank;
            this.basePitch = basePitch;
        }
    }

    private static final Int2ObjectOpenHashMap<Voice> VOICES = new Int2ObjectOpenHashMap<>();

    private ConversationVoice() {
    }

    /** Which voice bank a speaker uses. */
    public static String archetype(@Nullable Entity entity) {
        if (entity instanceof RaiderEntity raider) {
            if (raider.isGoblinThiefDemo()) return "goblin";
            if (raider.isCaptain()) return "captain";
            if (raider.variant() == RaiderEntity.Variant.BRUTE) return "brute";
            return "man";
        }
        if (entity instanceof WanderingTrader) return "peddler";
        // Settlers and others: VoiceBanks picks m/f young/adult/old from the same UUID bits as bank().
        return "settler";
    }

    /** The sound lane's bank for this speaker: voice matches look (sex/age from the UUID until the skins lane exposes them). */
    static VoiceBanks.Bank bank(Entity entity) {
        var profile = com.hearthstead.entity.look.CharacterLooks.voiceProfile(entity);
        return VoiceBanks.bankFor(profile.archetype().name(), profile.sex(), profile.age(), profile.build());
    }

    /** Stable per character (the skins lane's pitch seed and saga epithet): voice matches look. */
    private static float basePitch(Entity entity, VoiceBanks.Bank bank) {
        var profile = com.hearthstead.entity.look.CharacterLooks.voiceProfile(entity);
        return VoiceBanks.basePitch(bank, profile.pitchSeed(), profile.build(), profile.epithetKey());
    }

    /** A new line starts typing out. */
    public static void begin(Entity speaker) {
        VoiceBanks.Bank bank = bank(speaker);
        Voice voice = new Voice(bank, basePitch(speaker, bank));
        voice.nextSyllableNanos = System.nanoTime();
        Voice old = VOICES.put(speaker.getId(), voice);
        if (old != null) stopClip(old);
    }

    /**
     * Called every frame while typing. Owner rule ("softer Sims chatter"): each line babbles only for
     * its first 1-2 s, 4-8 syllables at 140-220 ms with natural gaps, then stays quiet while the text
     * finishes. {@code lines} are the node's lines; {@code shown} characters of their concatenation
     * (without separators) are visible.
     */
    public static void reveal(Entity speaker, java.util.List<String> lines, java.util.List<String> keys, int shown) {
        Voice voice = VOICES.get(speaker.getId());
        if (voice == null || lines == null) return;
        long now = System.nanoTime();
        voice.lastRevealNanos = now;
        int line = 0;
        int start = 0;
        while (line < lines.size() && shown >= start + lines.get(line).length()) {
            start += lines.get(line).length();
            line++;
        }
        if (line >= lines.size()) return;
        String text = lines.get(line);
        int chars = Math.max(0, shown - start);
        if (voicedLine(speaker, voice, line, line < keys.size() ? keys.get(line) : null, now)) return;
        boolean brute = "brute".equals(voice.bank.id());
        if (line != voice.line) {
            voice.line = line;
            voice.lineStartNanos = now;
            voice.syllables = 0;
            voice.budget = 3 + RANDOM.nextInt(VoiceBanks.MAX_SYLLABLES - 2);
            voice.nextSyllableNanos = now;
            voice.midGrunt = brute && text.length() > 24 && RANDOM.nextFloat() < 0.4F;
            if (brute) {
                grunt(speaker, voice);
                return;
            }
        }
        if (brute) {
            // Brutes never babble: one dark grunt opens the line, now and then one more mid-line.
            if (voice.midGrunt && chars >= text.length() / 2) {
                voice.midGrunt = false;
                grunt(speaker, voice);
            }
            return;
        }
        if (voice.syllables >= voice.budget || now - voice.lineStartNanos > VoiceBanks.MAX_BABBLE_MS * 1_000_000L) return;
        if (chars > voice.lastChars) {
            char c = chars > 0 && chars <= text.length() ? text.charAt(chars - 1) : ' ';
            voice.lastChars = chars;
            if (c == ',' || c == '.' || c == '!' || c == '?' || c == ';' || c == ':') {
                voice.nextSyllableNanos = now + (c == ',' ? 200_000_000L : 320_000_000L);
                voice.question = c == '?';
                return;
            }
        }
        if (now < voice.nextSyllableNanos) return;
        voice.emphatic = RANDOM.nextFloat() < 0.15F;
        // Owner decision: vanilla villager "hmm"s, per-character pitch (+-3 %), quiet; the line's tone picks the sound.
        String tone = voice.tones.getOrDefault(line, "ambient");
        float pitch = VoiceBanks.syllablePitch(voice.bank, voice.basePitch, 0.25F + 0.5F * RANDOM.nextFloat());
        // sounds.json masters every voice id to about -27 LUFS at 1.0.
        float volume = (voice.emphatic ? 1.0F : 0.75F) * com.hearthstead.HearthsteadClientConfig.voiceVolume();
        if (volume > 0.0F) {
            HsSound.at(voice.bank.voice(tone), villagerSound(tone), SoundSource.NEUTRAL,
                speaker.getX(), speaker.getEyeY(), speaker.getZ(), volume, pitch);
        }
        voice.syllables++;
        voice.lastSyllableNanos = now;
        long gap = VoiceBanks.MIN_GAP_MS * 1_000_000L
            + RANDOM.nextInt((VoiceBanks.MAX_GAP_MS - VoiceBanks.MIN_GAP_MS) * 1_000_000 + 1);
        if (RANDOM.nextFloat() < 0.25F) gap += 140_000_000L;
        voice.nextSyllableNanos = now + gap;
    }

    /** Real voiced lines: plays the recorded clip for a line, returns true when this line is voiced. */
    private static boolean voicedLine(Entity speaker, Voice voice, int line, @Nullable String key, long now) {
        if (line != voice.voicedLine) {
            voice.voicedLine = line;
            stopClip(voice);
            voice.clip = VoiceLines.clipFor(key, variant(speaker));
            voice.clipStartNanos = now;
            if (voice.clip != null) {
                float volume = com.hearthstead.HearthsteadClientConfig.voiceVolume();
                if (volume > 0.0F) {
                    voice.playing = new net.minecraft.client.resources.sounds.SimpleSoundInstance(voice.clip.sound(),
                        SoundSource.VOICE, volume, 1.0F, RANDOM, false, 0,
                        net.minecraft.client.resources.sounds.SoundInstance.Attenuation.LINEAR,
                        speaker.getX(), speaker.getEyeY(), speaker.getZ(), false);
                    mc().getSoundManager().play(voice.playing);
                }
            }
        }
        if (voice.clip == null) return false;
        // Head nods follow the recording: its loudness envelope, else a speech rhythm for its length.
        float t = (now - voice.clipStartNanos) / 1.0e9F;
        if (t * 1000.0F > voice.clip.ms()) return true;
        float level = voice.clip.level(t);
        if (level >= 0.0F) {
            if (level > 0.35F && voice.lastLevel <= 0.35F) {
                voice.lastSyllableNanos = now;
                voice.emphatic = level > 0.7F;
            }
            voice.lastLevel = level;
        } else if (now >= voice.nextSyllableNanos) {
            voice.lastSyllableNanos = now;
            voice.emphatic = RANDOM.nextFloat() < 0.15F;
            voice.nextSyllableNanos = now + VoiceBanks.MIN_GAP_MS * 1_000_000L
                + RANDOM.nextInt((VoiceBanks.MAX_GAP_MS - VoiceBanks.MIN_GAP_MS) * 1_000_000 + 1);
        }
        return true;
    }

    private static void stopClip(Voice voice) {
        if (voice.playing != null) {
            mc().getSoundManager().stop(voice.playing);
            voice.playing = null;
        }
    }

    /** A raid captain speaks with his epithet's own recordings (red, torch, reaper); others use the base line. */
    @Nullable
    static String variant(Entity speaker) {
        if (!(speaker instanceof com.hearthstead.entity.RaiderEntity raider) || !raider.isCaptain()) return null;
        return com.hearthstead.entity.look.CharacterLooks.voiceProfile(speaker).epithetKey();
    }

    /** How long line {@code key} should take to reveal: its recording plus a beat, or -1 when unvoiced. */
    public static int voicedMillis(Entity speaker, @Nullable String key) {
        VoiceLines.Clip clip = VoiceLines.clipFor(key, speaker == null ? null : variant(speaker));
        return clip == null ? -1 : clip.ms() + 150;
    }

    private static void grunt(Entity speaker, Voice voice) {
        float volume = 1.0F * com.hearthstead.HearthsteadClientConfig.voiceVolume();
        if (volume <= 0.0F) return;
        // The grunt files are already dark: pitch stays in the brute bank window.
        HsSound.at(voice.bank.voice("grunt"), SoundEvents.VINDICATOR_AMBIENT, SoundSource.NEUTRAL,
            speaker.getX(), speaker.getEyeY(), speaker.getZ(), volume,
            VoiceBanks.syllablePitch(voice.bank, voice.basePitch, RANDOM.nextFloat()));
        voice.lastSyllableNanos = System.nanoTime();
        voice.emphatic = true;
    }

    /** Tone tag -> vanilla villager sound: yes/agree, no/angry, trade/hmm, laugh/celebrate, else ambient. */
    static net.minecraft.sounds.SoundEvent villagerSound(String tone) {
        return switch (tone) {
            case "yes" -> SoundEvents.VILLAGER_YES;
            case "no" -> SoundEvents.VILLAGER_NO;
            case "trade" -> SoundEvents.VILLAGER_TRADE;
            case "celebrate" -> SoundEvents.VILLAGER_CELEBRATE;
            default -> SoundEvents.VILLAGER_AMBIENT;
        };
    }

    /** Normalises a dialogue tone tag onto the five villager moods. */
    static String mood(String tag) {
        return switch (tag == null ? "" : tag.toLowerCase(Locale.ROOT)) {
            case "yes", "agree", "farewell", "bye" -> "yes";
            case "no", "angry", "refuse" -> "no";
            case "trade", "hmm", "think" -> "trade";
            case "laugh", "celebrate", "happy", "surprised", "oh" -> "celebrate";
            default -> "ambient";
        };
    }

    /** The screen reports a tone tag on line {@code line} ("{laugh} Ha!"): its hmms use that mood. */
    public static void tone(Entity speaker, int line, String tag) {
        Voice voice = VOICES.get(speaker.getId());
        if (voice != null) voice.tones.put(line, mood(tag));
    }

    /** Whether the speaker is still "talking" (head motion keeps going for the whole line, easing out after). */
    static float talkLevel(int entityId) {
        Voice voice = VOICES.get(entityId);
        if (voice == null || voice.nextSyllableNanos == Long.MAX_VALUE) return 0.0F;
        return 1.0F;
    }

    /** One-shot emote from a tone tag: laugh, angry, hmm, surprised, agree, farewell. */
    public static void emote(Entity speaker, String emote) {
        VoiceBanks.Bank bank = bank(speaker);
        HsSound.at(bank.emote(emote.toLowerCase(Locale.ROOT)), SoundEvents.VILLAGER_YES,
            SoundSource.NEUTRAL, speaker.getX(), speaker.getEyeY(), speaker.getZ(),
            0.35F * com.hearthstead.HearthsteadClientConfig.voiceVolume(), basePitch(speaker, bank));
    }

    public static void stop(Entity speaker) {
        stop(speaker.getId());
    }

    /** By id: also works once the speaker has died or unloaded (T32). */
    public static void stop(int entityId) {
        Voice voice = VOICES.get(entityId);
        if (voice != null) {
            voice.nextSyllableNanos = Long.MAX_VALUE;
            stopClip(voice);
        }
    }

    public static void end(int entityId) {
        Voice voice = VOICES.remove(entityId);
        if (voice != null) stopClip(voice);
    }

    /** Seconds since the line was last being typed (large when not typing). */
    static float sinceReveal(int entityId) {
        Voice voice = VOICES.get(entityId);
        if (voice == null || voice.lastRevealNanos == 0L) return 99.0F;
        return (System.nanoTime() - voice.lastRevealNanos) / 1.0e9F;
    }

    /** Seconds since the speaker's last syllable (large when silent). */
    static float sinceSyllable(int entityId) {
        Voice voice = VOICES.get(entityId);
        if (voice == null || voice.lastSyllableNanos == 0L) return 99.0F;
        return (System.nanoTime() - voice.lastSyllableNanos) / 1.0e9F;
    }

    static boolean emphatic(int entityId) {
        Voice voice = VOICES.get(entityId);
        return voice != null && voice.emphatic;
    }

    static boolean question(int entityId) {
        Voice voice = VOICES.get(entityId);
        return voice != null && voice.question;
    }

    /** Tone tag at the start of a line, "{laugh} ...": returns the emote or null. */
    @Nullable
    static String toneTag(String line) {
        if (line == null || !line.startsWith("{")) return null;
        int close = line.indexOf('}');
        return close > 1 && close < 16 ? line.substring(1, close) : null;
    }

    static String stripTone(String line) {
        String tag = toneTag(line);
        return tag == null ? line : line.substring(tag.length() + 2).stripLeading();
    }

    static boolean active() {
        return !VOICES.isEmpty();
    }

    static Minecraft mc() {
        return Minecraft.getInstance();
    }
}
