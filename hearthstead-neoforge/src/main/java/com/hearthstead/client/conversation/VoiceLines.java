package com.hearthstead.client.conversation;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.Hearthstead;
import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

/**
 * Real voiced dialogue lines (owner decision). A line whose lang key is
 * {@code conversation.hearthstead.<rest>} plays the sound
 * {@code hearthstead:voice.line.<rest>} when the sound lane has recorded it;
 * its length (and optional 20 Hz loudness envelope) comes from
 * {@code assets/hearthstead/voice_lines.json}:
 * <pre>{"lines": {"brute_toll.demand": {"ms": 2400, "env": [0.0, 0.4, ...]}}}</pre>
 * Lines without a recording fall back to the soft villager "hmm".
 */
@EventBusSubscriber(modid = Hearthstead.MODID, value = Dist.CLIENT)
public final class VoiceLines {
    private static final String PREFIX = "conversation.hearthstead.";
    private static final ResourceLocation MANIFEST = Hearthstead.id("voice_lines.json");

    /** One recorded line: its length and optional loudness envelope (20 samples per second). */
    public record Clip(ResourceLocation sound, int ms, float[] envelope) {
        public float level(float seconds) {
            if (envelope.length == 0) return -1.0F;
            int i = (int) (seconds * 20.0F);
            return i < 0 || i >= envelope.length ? 0.0F : envelope[i];
        }
    }

    private static volatile Map<String, Clip> clips = Map.of();

    private VoiceLines() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) VoiceLines::load);
    }

    private static void load(ResourceManager manager) {
        Map<String, Clip> loaded = new HashMap<>();
        manager.getResource(MANIFEST).ifPresent(resource -> {
            try (Reader reader = resource.openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject lines = root.has("lines") ? root.getAsJsonObject("lines") : root;
                for (Map.Entry<String, JsonElement> entry : lines.entrySet()) {
                    JsonObject line = entry.getValue().getAsJsonObject();
                    int ms = line.has("ms") ? line.get("ms").getAsInt() : 0;
                    if (ms <= 0) continue;
                    float[] env = new float[0];
                    if (line.has("env")) {
                        var array = line.getAsJsonArray("env");
                        env = new float[array.size()];
                        for (int i = 0; i < env.length; i++) env[i] = array.get(i).getAsFloat();
                    }
                    loaded.put(entry.getKey(), new Clip(Hearthstead.id("voice.line." + entry.getKey()), ms, env));
                }
            } catch (Exception malformed) {
                Hearthstead.LOGGER.error("voice_lines.json is malformed", malformed);
            }
        });
        clips = Map.copyOf(loaded);
        Hearthstead.LOGGER.info("Loaded {} voiced conversation line(s)", loaded.size());
    }

    /** The lang key of a dialogue line, or null for a literal. */
    @Nullable
    public static String keyOf(Component line) {
        return line != null && line.getContents() instanceof TranslatableContents t ? t.getKey() : null;
    }

    /**
     * As {@link #clipFor(String)}, trying the speaker's own recording first
     * ({@code voice.line.<key>.<variant>}, e.g. a raid captain's epithet voice).
     */
    @Nullable
    public static Clip clipFor(@Nullable String langKey, @Nullable String variant) {
        if (langKey != null && variant != null && !variant.isBlank()) {
            Clip own = clipFor(langKey + "." + variant);
            if (own != null) return own;
        }
        return clipFor(langKey);
    }

    /** The recorded clip for a line, when its sound is registered and has a known length. */
    @Nullable
    public static Clip clipFor(@Nullable String langKey) {
        if (langKey == null) return null;
        String rest = langKey.startsWith(PREFIX) ? langKey.substring(PREFIX.length()) : langKey;
        Clip clip = clips.get(rest);
        if (clip == null) return null;
        return Minecraft.getInstance().getSoundManager().getSoundEvent(clip.sound()) != null ? clip : null;
    }
}
