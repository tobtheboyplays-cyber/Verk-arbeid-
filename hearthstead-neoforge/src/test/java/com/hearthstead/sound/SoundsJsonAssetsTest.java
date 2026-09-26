package com.hearthstead.sound;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * sounds.json and the shipped .ogg files must agree: every hearthstead file a
 * sound event names exists, no .ogg ships that nothing references (orphans
 * only bloat the jar), every subtitle key has an English line, and every
 * sound event the Java code registers has a sounds.json entry.
 */
final class SoundsJsonAssetsTest {
    private static final String ASSETS = "src/main/resources/assets/hearthstead";
    private static final String JAVA = "src/main/java/com/hearthstead";
    private static final Pattern REGISTERED = Pattern.compile(
        "(?:\\bregister|\\bsound|\\bcreate)\\(\\s*\"([a-z0-9_.]+)\"\\s*\\)");

    private static Path moduleRoot() throws IOException {
        Path dir = Path.of("").toAbsolutePath();
        for (int up = 0; up < 8 && dir != null; up++, dir = dir.getParent()) {
            if (Files.isDirectory(dir.resolve(ASSETS))) {
                return dir;
            }
            Path module = dir.resolve("hearthstead-neoforge");
            if (Files.isDirectory(module.resolve(ASSETS))) {
                return module;
            }
        }
        throw new IOException("assets folder not found from " + Path.of("").toAbsolutePath());
    }

    private static JsonObject read(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        }
    }

    private static Set<String> referencedFiles(JsonObject sounds) {
        Set<String> out = new TreeSet<>();
        for (var event : sounds.entrySet()) {
            JsonObject body = event.getValue().getAsJsonObject();
            if (!body.has("sounds")) continue;
            for (JsonElement entry : body.getAsJsonArray("sounds")) {
                String name;
                if (entry.isJsonPrimitive()) {
                    name = entry.getAsString();
                } else {
                    JsonObject obj = entry.getAsJsonObject();
                    if (obj.has("type") && "event".equals(obj.get("type").getAsString())) continue;
                    name = obj.get("name").getAsString();
                }
                if (name.startsWith("hearthstead:")) {
                    out.add(name.substring("hearthstead:".length()));
                }
            }
        }
        return out;
    }

    @Test
    void everyReferencedFileExistsAndNoOrphansShip() throws IOException {
        Path root = moduleRoot();
        Path soundsDir = root.resolve(ASSETS).resolve("sounds");
        Set<String> referenced = referencedFiles(read(root.resolve(ASSETS).resolve("sounds.json")));

        List<String> missing = new ArrayList<>();
        for (String name : referenced) {
            if (!Files.isRegularFile(soundsDir.resolve(name + ".ogg"))) {
                missing.add(name);
            }
        }
        Set<String> shipped = new TreeSet<>();
        try (Stream<Path> files = Files.walk(soundsDir)) {
            files.filter(Files::isRegularFile).forEach(p -> {
                String rel = soundsDir.relativize(p).toString().replace('\\', '/');
                shipped.add(rel);
            });
        }
        List<String> orphans = new ArrayList<>();
        List<String> notOgg = new ArrayList<>();
        for (String rel : shipped) {
            if (!rel.endsWith(".ogg")) {
                notOgg.add(rel);
                continue;
            }
            if (!referenced.contains(rel.substring(0, rel.length() - 4))) {
                orphans.add(rel);
            }
        }
        assertTrue(missing.isEmpty(), "sounds.json names missing files: " + missing);
        assertTrue(orphans.isEmpty(), "unreferenced .ogg files ship in the jar: " + orphans);
        assertTrue(notOgg.isEmpty(), "non-.ogg files under sounds/: " + notOgg);
    }

    @Test
    void everySubtitleHasAnEnglishLine() throws IOException {
        Path root = moduleRoot();
        JsonObject sounds = read(root.resolve(ASSETS).resolve("sounds.json"));
        JsonObject lang = read(root.resolve(ASSETS).resolve("lang/en_us.json"));
        List<String> missing = new ArrayList<>();
        for (var event : sounds.entrySet()) {
            JsonObject body = event.getValue().getAsJsonObject();
            if (body.has("subtitle") && !lang.has(body.get("subtitle").getAsString())) {
                missing.add(event.getKey() + " -> " + body.get("subtitle").getAsString());
            }
        }
        assertTrue(missing.isEmpty(), "subtitle keys without an en_us line: " + missing);
    }

    @Test
    void everyRegisteredSoundEventHasASoundsJsonEntry() throws IOException {
        Path root = moduleRoot();
        JsonObject sounds = read(root.resolve(ASSETS).resolve("sounds.json"));
        List<Path> registries = List.of(
            root.resolve(JAVA).resolve("registry/ModSounds.java"),
            root.resolve(JAVA).resolve("registry/RoleItems.java"),
            root.resolve(JAVA).resolve("finisher/FinisherSounds.java"));
        List<String> missing = new ArrayList<>();
        int seen = 0;
        for (Path file : registries) {
            if (!Files.isRegularFile(file)) continue;
            String src = Files.readString(file, StandardCharsets.UTF_8);
            boolean soundFile = file.getFileName().toString().contains("Sound");
            Matcher m = REGISTERED.matcher(src);
            while (m.find()) {
                String id = m.group(1);
                // RoleItems registers items too; only its sound("...") calls are sound events.
                if (!soundFile && !m.group(0).startsWith("sound(")) continue;
                seen++;
                if (!sounds.has(id)) missing.add(file.getFileName() + ": " + id);
            }
        }
        assertTrue(seen > 20, "registration scan found too few sound ids (" + seen + ")");
        assertTrue(missing.isEmpty(), "registered sound events without a sounds.json entry: " + missing);
    }
}
