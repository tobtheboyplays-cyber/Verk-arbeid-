package com.hearthstead.client.motion;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.hearthstead.client.model.SettlerModel;
import net.minecraft.client.animation.AnimationDefinition;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every authored clip under assets/hearthstead/animations must parse
 * cleanly, override a real legacy clip, keep that clip's length and loop
 * flag (the sound-contact beats of WorkSoundSync / Employment are keyed to
 * the clip clock, so the length is the contract), and only name bones the
 * settler rig actually has.
 */
final class AuthoredClipAssetsTest {
    private static final String RELATIVE = "src/main/resources/assets/hearthstead/animations";

    /**
     * The source animations folder, found from the working directory upward
     * (moddev runs JUnit in build/minecraft-junit or a private build dir), or
     * the processed copy on the test classpath.
     */
    private static Path animationsRoot() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 8 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve(RELATIVE);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            Path module = dir.resolve("hearthstead-neoforge").resolve(RELATIVE);
            if (Files.isDirectory(module)) {
                return module;
            }
        }
        var url = AuthoredClipAssetsTest.class.getResource("/assets/hearthstead/animations");
        if (url != null && "file".equals(url.getProtocol())) {
            try {
                return Path.of(url.toURI());
            } catch (java.net.URISyntaxException bad) {
                throw new IOException(bad);
            }
        }
        throw new IOException("animations folder not found from " + Path.of("").toAbsolutePath());
    }

    @Test
    void everyAuthoredClipParsesAndKeepsItsLegacyContract() throws IOException {
        final Path ROOT = animationsRoot();
        assertTrue(Files.isDirectory(ROOT), ROOT.toString());
        Map<String, MotionClip> clips = new HashMap<>();
        List<String> warnings = new ArrayList<>();
        try (Stream<Path> files = Files.walk(ROOT)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String rig = ROOT.relativize(file).getName(0).toString();
                try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                    BedrockClipCodec.read(json, rig, file.toString(), (k, c) -> {
                        assertFalse(clips.containsKey(k), "duplicate clip key " + k);
                        clips.put(k, c);
                    }, warnings);
                }
            }
        }
        assertTrue(warnings.isEmpty(), warnings.toString());
        assertFalse(clips.isEmpty());
        MotionRig rig = new MotionRig(new SettlerModel(SettlerModel.createBodyLayer().bakeRoot()));
        Map<String, AnimationDefinition> legacy = LegacyClipBridge.allKeys();
        for (Map.Entry<String, MotionClip> entry : clips.entrySet()) {
            String base = MotionLibrary.baseKeyOf(entry.getKey());
            boolean variant = !base.equals(entry.getKey());
            if (base.startsWith("player/") || base.contains("/finisher_")
                || base.matches("settler/(longsword|spear|captain_[a-z]+)_(strike|heavy)")) {
                // Scripted clips (PlayerClips, MotionOverrides): no legacy twin; they
                // are played by key, so only parsing (above) is the contract.
                assertTrue(entry.getValue().length() > 0.0F, entry.getKey() + " has a length");
                continue;
            }
            AnimationDefinition def = legacy.get(base);
            assertNotNull(def, entry.getKey() + " does not override any SettlerAnimations/RaiderAnimations clip");
            MotionClip clip = entry.getValue();
            if (!variant) {
                assertEquals(def.lengthInSeconds(), clip.length(), 1.0E-4F, entry.getKey() + " length is the contact contract");
                assertEquals(def.looping(), clip.looping(), entry.getKey() + " loop flag");
            } else if (!base.substring(base.indexOf('/') + 1).startsWith("idle")) {
                // Work-loop variants keep the gameplay clock: a whole number of base cycles.
                float cycles = clip.length() / def.lengthInSeconds();
                assertEquals(Math.round(cycles), cycles, 1.0E-3F,
                    entry.getKey() + " must be a whole number of " + base + " cycles");
                assertTrue(Math.round(cycles) >= 1, entry.getKey());
            }
            if (entry.getKey().startsWith("settler/")) {
                for (MotionClip.Track track : clip.tracks()) {
                    assertTrue(rig.indexOf(track.bone()) >= 0, entry.getKey() + ": unknown bone " + track.bone());
                    for (int i = 0; i < track.size(); i++) {
                        assertTrue(track.time(i) >= 0.0F && track.time(i) <= clip.length() + 1.0E-4F,
                            entry.getKey() + "/" + track.bone() + " key outside the clip");
                    }
                }
            }
        }
    }

    /**
     * BUILDER lane: the three builder clips ship as authored JSON, override
     * their Java fallbacks, and keep the contact contract BuilderWorkGoal's
     * taps are timed to (BUILD_PLACE 1.6 s taps 0.9/1.2 s, BUILD_HAMMER
     * 1.0 s strike 0.45 s). CARRY_PLANKS is a walk overlay: no leg/root bones.
     */
    @Test
    void builderClipsShipWithTheirContactContract() throws IOException {
        Path root = animationsRoot().resolve("settler");
        Map<String, Float> lengths = Map.of("build_place", 1.6F, "build_hammer", 1.0F, "carry_planks", 1.2F);
        Map<String, AnimationDefinition> legacy = LegacyClipBridge.allKeys();
        for (Map.Entry<String, Float> e : lengths.entrySet()) {
            Path file = root.resolve(e.getKey() + ".animation.json");
            assertTrue(Files.isRegularFile(file), file + " is authored");
            AnimationDefinition fallback = legacy.get("settler/" + e.getKey());
            assertNotNull(fallback, e.getKey() + " has a CraftMotionAnimations fallback");
            assertEquals(e.getValue(), fallback.lengthInSeconds(), 1.0E-4F, e.getKey() + " fallback length");
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
                JsonObject anim = json.getAsJsonObject("animations")
                    .getAsJsonObject("animation.settler." + e.getKey());
                assertNotNull(anim, e.getKey() + " animation name");
                assertEquals(e.getValue(), anim.get("animation_length").getAsFloat(), 1.0E-4F, e.getKey());
                if ("carry_planks".equals(e.getKey())) {
                    JsonObject bones = anim.getAsJsonObject("bones");
                    for (String owned : List.of("root", "right_leg", "left_leg", "right_shin", "left_shin")) {
                        assertFalse(bones.has(owned), "the walk owns " + owned);
                    }
                }
            }
        }
    }

    /** motion/particles.json: every key names a shipped clip and every cue sits inside it. */
    @Test
    void contactCueSidecarMatchesShippedClips() throws IOException {
        Path root = animationsRoot();
        Path sidecar = root.getParent().resolve("motion/particles.json");
        if (!Files.exists(sidecar)) {
            return;
        }
        JsonObject cues;
        try (Reader reader = Files.newBufferedReader(sidecar, StandardCharsets.UTF_8)) {
            cues = JsonParser.parseReader(reader).getAsJsonObject();
        }
        for (var entry : cues.entrySet()) {
            String key = entry.getKey();
            Path clip = root.resolve(key + ".animation.json");
            assertTrue(Files.exists(clip), "particles.json: no clip file for " + key);
            JsonObject file;
            try (Reader reader = Files.newBufferedReader(clip, StandardCharsets.UTF_8)) {
                file = JsonParser.parseReader(reader).getAsJsonObject();
            }
            JsonObject anim = file.getAsJsonObject("animations").entrySet().iterator().next()
                .getValue().getAsJsonObject();
            float length = anim.get("animation_length").getAsFloat();
            for (var cue : entry.getValue().getAsJsonArray()) {
                float t = cue.getAsJsonObject().get("t").getAsFloat();
                assertTrue(t >= 0.0F && t <= length + 1.0E-4F,
                    key + ": cue t=" + t + " outside the clip length " + length);
            }
        }
    }
}
