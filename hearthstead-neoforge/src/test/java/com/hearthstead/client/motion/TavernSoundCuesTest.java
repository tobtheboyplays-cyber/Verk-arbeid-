package com.hearthstead.client.motion;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tavern lane sound pass: every sound cue on a tavern / drunk clip is a hearthstead SoundEvent that
 * is both registered in ModSounds and listed in sounds.json, is quiet (volume at most 0.6), lands
 * inside the clip and carries its own pitch jitter (so two patrons never sound in unison).
 */
final class TavernSoundCuesTest {
    /** Clips that must carry cues (their contact frames have a sound). */
    private static final List<String> VOICED = List.of(
        "seated_idle", "seated_sip", "seated_sip_left", "seated_drink", "seated_drink_left", "seated_story",
        "seated_listen", "seated_listen_smile", "seated_listen_chuckle", "seated_toast", "table_cheer",
        "table_cheer_wipe", "table_cheer_quick", "sleepy_nod", "seated_fidget_chin", "seated_fidget_stretch",
        "seated_fidget_shift", "idle_innkeeper", "idle_innkeeper__v2", "idle_innkeeper__v3", "idle_innkeeper__v4",
        "counter_wipe", "counter_lean", "ale_pour", "inn_welcome", "dance_jig", "brawl_punch", "shoo_birds",
        "stumble", "stumble__v2", "fall_forward", "fall_side", "drunk_lean", "drunk_sit");
    /** Tavern clips that may be silent, but any cue they carry obeys the same rules. */
    private static final List<String> OTHERS = List.of(
        "serve_carry", "eat", "eat__v2", "village_chat", "village_chat__v2", "village_listen",
        "bard_play", "bard_play__v2", "bard_play__v3", "tipsy_walk", "drunk_walk", "very_drunk_walk");

    private static Path moduleRoot() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve("src/main/resources/assets/hearthstead"))) return dir;
            Path module = dir.resolve("hearthstead-neoforge");
            if (Files.isDirectory(module.resolve("src/main/resources/assets/hearthstead"))) return module;
        }
        throw new IOException("module not found from " + Path.of("").toAbsolutePath());
    }

    private static JsonObject read(Path p) throws IOException {
        try (Reader r = Files.newBufferedReader(p, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(r).getAsJsonObject();
        }
    }

    @Test
    void everyTavernClipSoundIsARegisteredQuietDesyncedHearthsteadSound() throws IOException {
        Path root = moduleRoot();
        Path assets = root.resolve("src/main/resources/assets/hearthstead");
        Set<String> listed = read(assets.resolve("sounds.json")).keySet();
        Set<String> registered = new HashSet<>();
        Matcher m = Pattern.compile("register\\(\"([a-z0-9_./-]+)\"")
            .matcher(Files.readString(root.resolve("src/main/java/com/hearthstead/registry/ModSounds.java")));
        while (m.find()) registered.add(m.group(1));
        assertFalse(registered.isEmpty(), "ModSounds parsed");

        int cues = 0;
        for (String slug : concat(VOICED, OTHERS)) {
            Path file = assets.resolve("animations/settler/" + slug + ".animation.json");
            assertTrue(Files.isRegularFile(file), slug + " is shipped");
            JsonObject anim = read(file).getAsJsonObject("animations").entrySet().iterator().next()
                .getValue().getAsJsonObject();
            float length = anim.get("animation_length").getAsFloat();
            JsonElement sounds = anim.get("hearthstead_sounds");
            if (VOICED.contains(slug)) assertTrue(sounds != null && sounds.getAsJsonArray().size() > 0, slug + " has cues");
            if (sounds == null) continue;
            for (JsonElement e : sounds.getAsJsonArray()) {
                JsonObject cue = e.getAsJsonObject();
                String id = cue.get("sound").getAsString();
                assertTrue(id.startsWith("hearthstead:"), slug + ": " + id + " is a hearthstead sound");
                String path = id.substring("hearthstead:".length());
                assertTrue(listed.contains(path), slug + ": " + id + " is listed in sounds.json");
                assertTrue(registered.contains(path), slug + ": " + id + " is registered in ModSounds");
                float t = cue.get("t").getAsFloat();
                assertTrue(t >= 0F && t <= length + 1e-4F, slug + ": cue at " + t + " inside the clip");
                assertTrue(cue.get("volume").getAsFloat() <= 0.6F, slug + ": " + id + " stays quiet");
                assertTrue(cue.has("pitch_jitter") && cue.get("pitch_jitter").getAsFloat() > 0F,
                    slug + ": " + id + " has its own pitch jitter");
                cues++;
            }
        }
        assertTrue(cues >= 80, "the sound pass covers the tavern: " + cues + " cues");
        assertEquals(VOICED.size(), new HashSet<>(VOICED).size());
    }

    private static List<String> concat(List<String> a, List<String> b) {
        List<String> out = new java.util.ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
